package com.antigravity.copilot.core.location

import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.SupervisorJob
import android.annotation.SuppressLint
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.antigravity.copilot.MainActivity
import com.antigravity.copilot.R
import com.antigravity.copilot.core.fuel.FuelRepository
import com.antigravity.copilot.core.model.TripPoint
import com.antigravity.copilot.core.trip.TripDetectionEngine
import com.antigravity.copilot.core.trip.TripRepository
import com.antigravity.copilot.core.trip.VehicleStateManager
import com.antigravity.copilot.core.trip.GeofenceTripLifecycleEngine
import com.antigravity.copilot.core.trip.TripGeofenceManager
import com.antigravity.copilot.core.settings.AppSettingsManager
import com.antigravity.copilot.core.model.TripStatus

import com.google.android.gms.location.*
import kotlinx.coroutines.*

@AndroidEntryPoint
class DrivingTrackingService : Service(), LocationListener {

    private lateinit var locationManager: LocationManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    @Inject lateinit var tripRepository: TripRepository
    @Inject lateinit var fuelRepository: FuelRepository
    @Inject lateinit var speedLimitEngine: SpeedLimitEngine
    @Inject lateinit var database: com.antigravity.copilot.core.database.AppDatabase
    private lateinit var engine: TripDetectionEngine
    @Inject lateinit var vehicleStateManager: VehicleStateManager
    @Inject lateinit var geofenceManager: TripGeofenceManager
    private lateinit var geofenceEngine: GeofenceTripLifecycleEngine

    private var wakeLock: PowerManager.WakeLock? = null
    private var lastRecordedDistanceMeters: Double = 0.0
    private var isPowerCut: Boolean = false

    private var powerReceiver: BroadcastReceiver? = null
    // SupervisorJob: one failing loop (e.g. station lookup) must not cancel the speed watchdog and the rest.
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var lastPointTimestamp: Long = 0L
    private var latestEmittedPoint: TripPoint? = null
    private var stationaryStartTime: Long = 0L
    private var hasPromptedRefuelThisStop: Boolean = false
    private var hasCheckedStationThisStop: Boolean = false
    private var detectedStationResult: FuelStationDetectionResult? = null
    private var lastPromptStationTime = 0L
    private var lastPromptStationLat = 0.0
    private var lastPromptStationLon = 0.0
    private var lastFusedFixTimeMs: Long = 0L

    private val gpsFilter = com.antigravity.copilot.core.map.GpsLocationFilter()
    private var isLocationUpdatesActive: Boolean = false
    private var initialCachedFixProcessed: Boolean = false
    private var lastCachedFixTimestamp: Long = 0L

    private fun filterAndDispatchLocation(rawLocation: Location) {
        if (rawLocation.latitude == 0.0 && rawLocation.longitude == 0.0) return

        val rawSpeedKmh = if (rawLocation.hasSpeed()) rawLocation.speed * 3.6f else 0f
        val rawBearing = if (rawLocation.hasBearing()) rawLocation.bearing else 0f
        val rawAccuracy = if (rawLocation.hasAccuracy()) rawLocation.accuracy else 5.0f
        val timestamp = if (rawLocation.time > 0L) rawLocation.time else System.currentTimeMillis()

        val filtered = gpsFilter.filter(
            rawLat = rawLocation.latitude,
            rawLon = rawLocation.longitude,
            rawBearing = rawBearing,
            rawSpeedKmh = rawSpeedKmh,
            rawAccuracyMeters = rawAccuracy,
            timestamp = timestamp
        )

        val filteredLocation = Location(rawLocation).apply {
            latitude = filtered.latitude
            longitude = filtered.longitude
            bearing = filtered.bearing
            speed = filtered.speedKmh / 3.6f
            accuracy = filtered.accuracyMeters
            time = filtered.timestamp
        }

        if (::geofenceEngine.isInitialized) {
            geofenceEngine.processLocation(filteredLocation)
        }
        engine.processLocation(filteredLocation)
    }

    private val fusedCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            lastFusedFixTimeMs = android.os.SystemClock.elapsedRealtime()
            for (location in result.locations) {
                filterAndDispatchLocation(location)
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "driving_tracking_channel"
        const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, DrivingTrackingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        const val ACTION_MANUAL_START_TRIP = "ACTION_MANUAL_START_TRIP"
        const val ACTION_MANUAL_END_TRIP = "ACTION_MANUAL_END_TRIP"

        /** Manual override: start a trip now, regardless of geofences. */
        fun manualStartTrip(context: Context) = sendAction(context, ACTION_MANUAL_START_TRIP)

        /** Manual override: end the active trip now. */
        fun manualEndTrip(context: Context) = sendAction(context, ACTION_MANUAL_END_TRIP)

        private fun sendAction(context: Context, action: String) {
            val intent = Intent(context, DrivingTrackingService::class.java).apply { this.action = action }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun reset(context: Context) {
            val intent = Intent(context, DrivingTrackingService::class.java).apply {
                action = "ACTION_RESET_TELEMETRY"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_RESET_TELEMETRY") {
            if (::engine.isInitialized) engine.reset()
            if (::geofenceEngine.isInitialized) geofenceEngine.reset()
            gpsFilter.reset()
            lastRecordedDistanceMeters = 0.0
            return START_STICKY
        }
        if (intent?.action == ACTION_MANUAL_START_TRIP && ::geofenceEngine.isInitialized) {
            geofenceEngine.manualStartTrip(System.currentTimeMillis(), latestEmittedPoint)
        } else if (intent?.action == ACTION_MANUAL_END_TRIP && ::geofenceEngine.isInitialized) {
            if (geofenceEngine.isTripActive) {
                geofenceEngine.manualEndTrip(System.currentTimeMillis(), latestEmittedPoint)
            } else if (::engine.isInitialized && engine.currentTripId > 0L) {
                engine.completeActiveTrip(System.currentTimeMillis())
            }
        }
        startLocationUpdates()
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        // Repositories, engines and the database are injected by Hilt in super.onCreate().

        geofenceEngine = GeofenceTripLifecycleEngine(
            geofenceManager = geofenceManager,
            onTripStartConfirmed = { startTime, originName, departurePoints, departureDistMeters ->
                val loc = Location("geofence").apply {
                    if (departurePoints.isNotEmpty()) {
                        latitude = departurePoints.last().latitude
                        longitude = departurePoints.last().longitude
                        time = departurePoints.last().timestamp
                    }
                }
                engine.startGeofenceTrip(startTime, loc, departureDistMeters, originName)
                lastRecordedDistanceMeters = departureDistMeters
                tripRepository.attachDeparturePoints(startTime, departurePoints)
                tripRepository.updateTripOrigin(startTime, originName)
                if (departureDistMeters > 0.0) {
                    fuelRepository.consumeLiveFuel(
                        deltaDistanceMeters = departureDistMeters,
                        currentSpeedKmh = 15.0,
                        deltaDurationMs = 1000L
                    )
                }
            },
            onTripActiveTelemetry = { _ -> },
            onTripArrivalConfirmed = { endTime, destName ->
                val activeTripId = engine.currentTripId
                engine.completeGeofenceTrip(endTime, destName)
                if (activeTripId > 0L) {
                    serviceScope.launch {
                        tripRepository.updateTripDestination(activeTripId, destName)
                    }
                }
            }
        )

        serviceScope.launch {
            val db = database
            val activeTrip = db.tripDao().getActiveTrip()
            if (activeTrip != null && activeTrip.endTime <= 0L) {
                geofenceEngine.restoreActiveTrip(activeTrip.id, activeTrip.startTime)

                // Retrieve last recorded GPS coordinate for the active trip to bridge distance across reboot
                val lastPt = db.tripDao().getLastPointForTrip(activeTrip.id)
                val anchorLoc = if (lastPt != null && lastPt.latitude != 0.0 && lastPt.longitude != 0.0) {
                    Location("sqlite_reboot_anchor").apply {
                        latitude = lastPt.latitude
                        longitude = lastPt.longitude
                        altitude = lastPt.altitude
                        bearing = lastPt.bearing
                        time = lastPt.timestamp
                    }
                } else {
                    val (cachedLat, cachedLon) = com.antigravity.copilot.core.map.OfflineMapManager.getLastKnownPosition(applicationContext)
                    if (cachedLat != 0.0 || cachedLon != 0.0) {
                        Location("pref_reboot_anchor").apply {
                            latitude = cachedLat
                            longitude = cachedLon
                            time = activeTrip.startTime
                        }
                    } else null
                }

                val restoredStatus = try {
                    TripStatus.valueOf(activeTrip.status)
                } catch (e: Exception) {
                    TripStatus.ACTIVE_TRIP
                }

                engine.restoreActiveTripState(
                    tripId = activeTrip.id,
                    startTime = activeTrip.startTime,
                    distanceMeters = activeTrip.distanceMeters,
                    drivingMs = activeTrip.drivingDurationMs,
                    stationaryMs = activeTrip.stationaryDurationMs,
                    lastLocation = anchorLoc,
                    originName = activeTrip.startLocationName,
                    status = restoredStatus,
                    brakeCount = activeTrip.brakeCount,
                    accelCount = activeTrip.accelCount,
                    estimatedFuelLiters = activeTrip.estimatedFuelLiters,
                    bestMileageKmpl = activeTrip.bestMileageKmpl
                )
                lastRecordedDistanceMeters = activeTrip.distanceMeters

                // Feature 18: Reconstruct cumulative trip fuel consumption from Room SQLite
                if (activeTrip.estimatedFuelLiters > 0.0) {
                    fuelRepository.restoreActiveTripFuel(activeTrip.estimatedFuelLiters)
                }
            }
        }

        acquireWakeLock()

        createNotificationChannel()
        startForegroundWithNotification()

        engine = TripDetectionEngine(
            onPointReceived = { point ->
                lastPointTimestamp = System.currentTimeMillis()
                latestEmittedPoint = point
                com.antigravity.copilot.core.map.OfflineMapManager.saveLastKnownPosition(applicationContext, point.latitude, point.longitude)
                tripRepository.onLocationReceived(point)
                vehicleStateManager.onSpeedOrLocationUpdated(point.speedKmh, point.timestamp)
            },
            onTripStarted = { tripId ->
                lastRecordedDistanceMeters = 0.0
                fuelRepository.resetTripConsumption()
                tripRepository.onTripStarted(tripId)
                if (engine.currentOriginName.isBlank()) {
                    latestEmittedPoint?.let { pt ->
                        serviceScope.launch {
                            val originName = LocationNameResolver.resolveTripLocation(applicationContext, pt.latitude, pt.longitude)
                            tripRepository.updateTripOrigin(tripId, originName)
                        }
                    }
                }
            },
            onTripUpdated = { summary, point ->
                tripRepository.onTripUpdated(summary, point)
            },
            onTripCompleted = { summary ->
                if (summary.endLocationName.isBlank()) {
                    latestEmittedPoint?.let { pt ->
                        serviceScope.launch {
                            val destName = LocationNameResolver.resolveTripLocation(applicationContext, pt.latitude, pt.longitude)
                            tripRepository.updateTripDestination(summary.id, destName)
                        }
                    }
                }
                tripRepository.onTripCompleted(summary)
                fuelRepository.onTripCompleted(summary.id, summary.distanceMeters / 1000.0)
                lastRecordedDistanceMeters = 0.0
            },
            onTripDiscarded = { tripId ->
                tripRepository.onTripDiscarded(tripId)
                lastRecordedDistanceMeters = 0.0
            }
        )
        // Dynamically sync geofence control state with geofenceManager
        serviceScope.launch {
            geofenceManager.geofences.collect { geofences ->
                val hasEnabled = geofences.any { it.isEnabled }
                engine.isGeofenceControlled = hasEnabled
            }
        }
        registerLinearAccelerationSensor()
        tripRepository.learnedMileageProvider = { fuelRepository.getFuelTelemetrySnapshot().mileageKmpl }
        engine.fuelTelemetryProvider = {
            fuelRepository.getFuelTelemetrySnapshot()
        }
        engine.onLiveMovement = { stepDistMeters, speedKmh, dtMs ->
            fuelRepository.consumeLiveFuel(
                deltaDistanceMeters = stepDistMeters,
                currentSpeedKmh = speedKmh,
                deltaDurationMs = dtMs
            )
        }

        // Automotive Vehicle Ignition / Power Off & System Shutdown Detector
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SHUTDOWN)
            addAction(Intent.ACTION_REBOOT)
            addAction("android.intent.action.QUICKBOOT_POWEROFF")
            addAction("com.htc.intent.action.QUICKBOOT_POWEROFF")
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val now = System.currentTimeMillis()
                when (intent?.action) {
                    Intent.ACTION_POWER_CONNECTED -> {
                        isPowerCut = false
                        vehicleStateManager.onHardwarePowerConnected(now)
                        engine.onPowerConnected(now)
                        acquireWakeLock()
                        requestLocationUpdates()
                    }
                    Intent.ACTION_POWER_DISCONNECTED -> {
                        isPowerCut = true
                        if (::geofenceEngine.isInitialized) {
                            geofenceEngine.onPowerDisconnected(now)
                        }
                        engine.onPowerDisconnected(now)
                        vehicleStateManager.onHardwarePowerDisconnected(now)

                        // Safely persist ongoing journey in TEMPORARY_STOP in Room SQLite
                        engine.getCurrentTripSummary(now)?.let { summary ->
                            tripRepository.persistActiveTrip(summary)
                        }

                        // Immediately reset speed display to zero upon engine cut
                        latestEmittedPoint?.let { pt ->
                            if (pt.speedKmh > 0f) {
                                val zeroPoint = pt.copy(speedKmh = 0f, timestamp = now)
                                latestEmittedPoint = zeroPoint
                                tripRepository.onLocationReceived(zeroPoint)
                            }
                        }

                        // Protect 12V battery and allow deep sleep
                        releaseWakeLock()
                        stopLocationUpdates()
                    }
                    Intent.ACTION_SHUTDOWN, Intent.ACTION_REBOOT,
                    "android.intent.action.QUICKBOOT_POWEROFF",
                    "com.htc.intent.action.QUICKBOOT_POWEROFF" -> {
                        if (::geofenceEngine.isInitialized) {
                            geofenceEngine.onPowerDisconnected(now)
                        }
                        // Conclude ongoing trip immediately at exact shutdown timestamp
                        engine.forceCompleteOngoingTrip(now)
                        vehicleStateManager.onSystemShutdown("SYSTEM_SHUTDOWN", now)
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(
                this,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(receiver, filter)
        }
        powerReceiver = receiver

        // 1-second Automotive Speedometer Decay Watchdog & Parked Trip Auto-Finalizer
        serviceScope.launch {
            while (isActive) {
                delay(1000)
                if (isPowerCut) {
                    continue
                }
                val now = System.currentTimeMillis()
                val elapsed = now - lastPointTimestamp
                val lastPt = latestEmittedPoint
                // Only synthesize zero-speed after full 10-second confirmed standstill
                // Allows UI SpeedometerCluster to execute graceful deceleration curve
                if (elapsed > 10000L && lastPt != null && lastPt.speedKmh > 0f) {
                    val zeroPoint = lastPt.copy(speedKmh = 0f, timestamp = now)
                    latestEmittedPoint = zeroPoint
                    tripRepository.onLocationReceived(zeroPoint)
                }
                engine.processStationaryWatchdog(now)
                vehicleStateManager.onPeriodicTick(now)

                // Background Fuel Station Stop Intelligence (> 30s):
                // Strictly triggers only at confirmed Fuel Station POIs. Prevents false triggers at traffic lights or normal parking.
                // Enforces 30-minute / 200-meter cooldown between prompts at the same station.
                val currentPt = latestEmittedPoint
                if (currentPt != null) {
                    val settings = com.antigravity.copilot.core.settings.AppSettingsManager.settingsState.value
                    val isWatchdogEnabled = settings.stationStopWatchdog
                    val dwellMs = (settings.stationPromptDwellSeconds * 1000L).coerceIn(15000L, 180000L)
                    val stationaryThreshold = settings.stationarySpeedThresholdKmh

                    if (isWatchdogEnabled && currentPt.speedKmh < stationaryThreshold) {
                        if (stationaryStartTime == 0L) {
                            stationaryStartTime = now
                            hasCheckedStationThisStop = false
                            detectedStationResult = null
                        }
                        val stoppedDurationMs = now - stationaryStartTime

                        // When stopped for >= 3 seconds, resolve if current location is a fuel station POI
                        if (stoppedDurationMs >= 3000L && !hasCheckedStationThisStop) {
                            hasCheckedStationThisStop = true
                            serviceScope.launch {
                                val detection = LocationNameResolver.detectNearbyFuelStation(
                                    applicationContext,
                                    currentPt.latitude,
                                    currentPt.longitude
                                )
                                detectedStationResult = detection

                                // Check visit cooldown (30 min or vehicle moved > 200m)
                                val distanceMoved = FloatArray(1)
                                android.location.Location.distanceBetween(
                                    currentPt.latitude, currentPt.longitude,
                                    lastPromptStationLat, lastPromptStationLon,
                                    distanceMoved
                                )
                                val isCooldownActive = (System.currentTimeMillis() - lastPromptStationTime < 30 * 60 * 1000L) && (distanceMoved[0] < 200f)

                                // Trigger prompt if continuously stationary >= configured dwell time at confirmed fuel station
                                if (detection.isFuelStation && !isCooldownActive && (System.currentTimeMillis() - stationaryStartTime >= dwellMs) && !hasPromptedRefuelThisStop) {
                                    hasPromptedRefuelThisStop = true
                                    lastPromptStationTime = System.currentTimeMillis()
                                    lastPromptStationLat = currentPt.latitude
                                    lastPromptStationLon = currentPt.longitude
                                    val currentOdo = fuelRepository.fuelState.value.odometerKm
                                    fuelRepository.triggerRefuelPrompt(
                                        locationName = detection.stationName,
                                        lat = currentPt.latitude,
                                        lon = currentPt.longitude,
                                        odoKm = currentOdo
                                    )
                                }
                            }
                        }

                        // If already stationary >= configured dwell time and station is confirmed:
                        if (stoppedDurationMs >= dwellMs && !hasPromptedRefuelThisStop) {
                            val station = detectedStationResult
                            if (station != null && station.isFuelStation) {
                                val distanceMoved = FloatArray(1)
                                android.location.Location.distanceBetween(
                                    currentPt.latitude, currentPt.longitude,
                                    lastPromptStationLat, lastPromptStationLon,
                                    distanceMoved
                                )
                                val isCooldownActive = (System.currentTimeMillis() - lastPromptStationTime < 30 * 60 * 1000L) && (distanceMoved[0] < 200f)

                                if (!isCooldownActive) {
                                    hasPromptedRefuelThisStop = true
                                    lastPromptStationTime = System.currentTimeMillis()
                                    lastPromptStationLat = currentPt.latitude
                                    lastPromptStationLon = currentPt.longitude
                                    serviceScope.launch {
                                        val currentOdo = fuelRepository.fuelState.value.odometerKm
                                        fuelRepository.triggerRefuelPrompt(
                                            locationName = station.stationName,
                                            lat = currentPt.latitude,
                                            lon = currentPt.longitude,
                                            odoKm = currentOdo
                                        )
                                    }
                                }
                            }
                        }
                    } else if (currentPt.speedKmh >= 10.0f) {
                        // Reset when car moves again
                        stationaryStartTime = 0L
                        hasPromptedRefuelThisStop = false
                        hasCheckedStationThisStop = false
                        detectedStationResult = null
                    }
                }
            }
        }

        // Location updates are started in onStartCommand
    }

    private fun startForegroundWithNotification() {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Copilot Active")
            .setContentText("Monitoring live vehicle telemetry & trips")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Copilot Tracker",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors vehicle movement and GPS trip telemetry"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Copilot::TrackingLock")
            }
            wakeLock?.let {
                if (!it.isHeld) {
                    it.acquire(24 * 60 * 60 * 1000L)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun requestLocationUpdates() {
        startLocationUpdates()
    }

    fun stopLocationUpdates() {
        if (!isLocationUpdatesActive) return
        try {
            fusedLocationClient.removeLocationUpdates(fusedCallback)
            locationManager.removeUpdates(this)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isLocationUpdatesActive = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (isPowerCut || isLocationUpdatesActive) {
            return
        }
        stopLocationUpdates()

        try {
            if (!initialCachedFixProcessed) {
                processInitialCachedLocation()
            }

            // Request Fused Location updates (1 second, high accuracy)
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                .setMinUpdateIntervalMillis(500L)
                .setMinUpdateDistanceMeters(0.5f)
                .build()
            fusedLocationClient.requestLocationUpdates(request, fusedCallback, Looper.getMainLooper())

            // Native LocationManager fallback
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    1.0f,
                    this,
                    Looper.getMainLooper()
                )
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    2000L,
                    5.0f,
                    this,
                    Looper.getMainLooper()
                )
            }
            isLocationUpdatesActive = true
        } catch (e: SecurityException) {
            e.printStackTrace()
            isLocationUpdatesActive = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun processInitialCachedLocation() {
        initialCachedFixProcessed = true
        val now = System.currentTimeMillis()

        // Prefer Fused Location, fall back to LocationManager if absent
        fusedLocationClient.lastLocation.addOnSuccessListener { fusedLoc ->
            if (fusedLoc != null && (now - fusedLoc.time) < 5000L) {
                lastCachedFixTimestamp = fusedLoc.time
                com.antigravity.copilot.core.map.OfflineMapManager.saveLastKnownPosition(
                    applicationContext, fusedLoc.latitude, fusedLoc.longitude
                )
                filterAndDispatchLocation(fusedLoc)
            } else {
                queryLocationManagerCachedFix(now)
            }
        }.addOnFailureListener {
            queryLocationManagerCachedFix(now)
        }
    }

    @SuppressLint("MissingPermission")
    private fun queryLocationManagerCachedFix(now: Long) {
        if (lastCachedFixTimestamp > 0L) return
        try {
            val lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val lastNet = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            val lastPass = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            val bestInitial = lastGps ?: lastNet ?: lastPass

            if (bestInitial != null && (now - bestInitial.time) < 5000L) {
                lastCachedFixTimestamp = bestInitial.time
                com.antigravity.copilot.core.map.OfflineMapManager.saveLastKnownPosition(
                    applicationContext, bestInitial.latitude, bestInitial.longitude
                )
                filterAndDispatchLocation(bestInitial)
            }
        } catch (e: SecurityException) {
            android.util.Log.w("DrivingTrackingService", "Location permission missing for cached fix query: ${e.message}")
        }
    }

    override fun onLocationChanged(location: Location) {
        // Provider Arbitration: If Fused Location is actively emitting fixes (<3500ms),
        // ignore native LocationManager callbacks to prevent dual-processing & distance inflation
        val timeSinceFused = android.os.SystemClock.elapsedRealtime() - lastFusedFixTimeMs
        if (timeSinceFused < 3500L) {
            return
        }
        filterAndDispatchLocation(location)
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Ensure trip is saved if user swipes app away or OS purges task
        // Ongoing trip state is preserved in Room database across task removal and reboots
    }

    private var linearAccelListener: android.hardware.SensorEventListener? = null

    /** Linear-acceleration sensor (gravity removed) for the driving-state label; optional. */
    private fun registerLinearAccelerationSensor() {
        try {
            val sm = getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager ?: return
            val sensor = sm.getDefaultSensor(android.hardware.Sensor.TYPE_LINEAR_ACCELERATION) ?: return
            val listener = object : android.hardware.SensorEventListener {
                override fun onSensorChanged(event: android.hardware.SensorEvent) {
                    if (::engine.isInitialized) engine.onLinearAcceleration(event.values[0], event.values[1], event.values[2])
                }
                override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
            }
            sm.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_UI)
            linearAccelListener = listener
        } catch (e: Exception) {
            android.util.Log.w("DrivingTrackingService", "Linear acceleration sensor unavailable: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        linearAccelListener?.let { l ->
            try {
                (getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager)?.unregisterListener(l)
            } catch (_: Exception) {}
        }
        // Save ongoing journey on service shutdown
        // Ongoing trip state is preserved in Room database across task removal and reboots
        powerReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        serviceScope.cancel()
        stopLocationUpdates()
        gpsFilter.reset()
        releaseWakeLock()
    }
}
