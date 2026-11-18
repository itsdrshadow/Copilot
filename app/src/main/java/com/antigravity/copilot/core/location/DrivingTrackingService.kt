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
class DrivingTrackingService : Service(), LocationListener, TrackingMaintenance.Handler {

    private lateinit var locationManager: LocationManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    @Inject lateinit var tripRepository: TripRepository
    @Inject lateinit var fuelRepository: FuelRepository
    @Inject lateinit var speedLimitEngine: SpeedLimitEngine
    @Inject lateinit var database: com.antigravity.copilot.core.database.AppDatabase
    private lateinit var engine: TripDetectionEngine
    @Inject lateinit var vehicleStateManager: VehicleStateManager
    @Inject lateinit var geofenceManager: TripGeofenceManager
    @Inject lateinit var roadAnomalyManager: com.antigravity.copilot.core.road.RoadAnomalyManager
    private lateinit var geofenceEngine: GeofenceTripLifecycleEngine

    private var wakeLock: PowerManager.WakeLock? = null
    private var lastRecordedDistanceMeters: Double = 0.0
    private var isPowerCut: Boolean = false

    private var powerReceiver: BroadcastReceiver? = null
    // SupervisorJob: one failing loop (e.g. station lookup) must not cancel the speed watchdog and the rest.
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var lastPointTimestamp: Long = 0L
    private var latestEmittedPoint: TripPoint? = null
    private var lastFusedFixTimeMs: Long = 0L
    /** elapsedRealtime of the last precise (GNSS-grade) fix, used to drop network fixes while GPS is live. */
    private var lastPreciseFixElapsedMs: Long = 0L
    private val serviceCreatedElapsedMs: Long = android.os.SystemClock.elapsedRealtime()

    private val gpsFilter = com.antigravity.copilot.core.map.GpsLocationFilter()
    private var isLocationUpdatesActive: Boolean = false
    private var initialCachedFixProcessed: Boolean = false
    private var lastCachedFixTimestamp: Long = 0L

    /** Active-trip restore gate: fixes that arrive before the DB restore are buffered and replayed. */
    private val restoreDone = CompletableDeferred<Unit>()
    private val fixesBeforeRestore = ArrayList<Location>()
    private val positionSaveThrottle = PositionSaveThrottle()
    private val pumpStop = PumpStopStateMachine()
    private val gnssGate = GnssQualityGate()
    private var gnssCallback: Any? = null
    private var lastActiveTripPersistMono: Long = 0L
    /** Factory reset / backup restore in progress: no fixes, no persistence (TrackingMaintenance). */
    @Volatile private var maintenancePaused: Boolean = false

    override suspend fun pauseForMaintenance() = withContext(Dispatchers.Main) {
        maintenancePaused = true
        stopLocationUpdates()
        fixesBeforeRestore.clear()
        if (::engine.isInitialized) engine.reset()
        if (::geofenceEngine.isInitialized) geofenceEngine.reset()
        gpsFilter.reset()
        lastRecordedDistanceMeters = 0.0
    }

    override suspend fun resumeAfterMaintenance() = withContext(Dispatchers.Main) {
        try {
            restoreActiveTripFromDatabase()
        } catch (e: Exception) {
            android.util.Log.w("DrivingTrackingService", "Post-maintenance restore failed: ${e.message}")
        }
        maintenancePaused = false
        startLocationUpdates()
    }

    private fun filterAndDispatchLocation(rawLocation: Location) {
        if (rawLocation.latitude == 0.0 && rawLocation.longitude == 0.0) return
        if (maintenancePaused) return
        // Spoofed / test-provider fixes never drive trips or the odometer.
        @Suppress("DEPRECATION")
        if (!gnssGate.accept(rawLocation.isFromMockProvider)) return
        if (!restoreDone.isCompleted) {
            // The DB restore of an active trip has not finished: replay this fix after it.
            if (fixesBeforeRestore.size < MAX_BUFFERED_FIXES) fixesBeforeRestore.add(Location(rawLocation).withElapsed())
            return
        }

        val rawSpeedKmh = if (rawLocation.hasSpeed()) rawLocation.speed * 3.6f else 0f
        val rawBearing = if (rawLocation.hasBearing()) rawLocation.bearing else 0f
        val rawAccuracy = if (rawLocation.hasAccuracy()) rawLocation.accuracy else 5.0f
        val wallTime = if (rawLocation.time > 0L) rawLocation.time else System.currentTimeMillis()
        val located = Location(rawLocation).withElapsed()
        // The smoothing filter measures dt on the monotonic clock (the RTC may be wrong or jump).
        val timestamp = located.elapsedRealtimeNanos / 1_000_000L
        val isPrecise = isPreciseFix(rawLocation)
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        if (isPrecise) {
            lastPreciseFixElapsedMs = nowElapsed
        } else if (lastPreciseFixElapsedMs == 0L && nowElapsed - serviceCreatedElapsedMs < COARSE_STARTUP_GRACE_MS) {
            // Startup: give GNSS a moment before trusting a coarse (network / cached) fix, so the map
            // never flashes to a far-away network position and back on load.
            return
        }

        val filtered = gpsFilter.filter(
            rawLat = rawLocation.latitude,
            rawLon = rawLocation.longitude,
            rawBearing = rawBearing,
            rawSpeedKmh = rawSpeedKmh,
            rawAccuracyMeters = rawAccuracy,
            timestamp = timestamp,
            isPrecise = isPrecise
        )

        val filteredLocation = Location(located).apply {
            latitude = filtered.latitude
            longitude = filtered.longitude
            bearing = filtered.bearing
            speed = filtered.speedKmh / 3.6f
            accuracy = filtered.accuracyMeters
            time = wallTime
        }

        engine.distanceAccumulationPaused = gnssGate.isWeak(nowElapsed)
        if (::geofenceEngine.isInitialized) {
            geofenceEngine.processLocation(filteredLocation)
        }
        engine.processLocation(filteredLocation)
    }

    /** Platform fixes carry elapsedRealtimeNanos; synthetic ones (simulator, cached) get the receive time. */
    private fun Location.withElapsed(): Location {
        if (elapsedRealtimeNanos <= 0L) elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
        return this
    }

    private val fusedCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            if (debugSim?.isRunning == true) return // debug drive simulator owns the position
            lastFusedFixTimeMs = android.os.SystemClock.elapsedRealtime()
            for (location in result.locations) {
                filterAndDispatchLocation(location)
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "driving_tracking_channel"
        const val NOTIFICATION_ID = 1001
        private const val PRECISE_ACCURACY_METERS = 50f
        private const val NETWORK_FALLBACK_AFTER_MS = 30_000L
        private const val COARSE_STARTUP_GRACE_MS = 15_000L
        private const val MAX_BUFFERED_FIXES = 30
        private const val RESTORE_GATE_TIMEOUT_MS = 5_000L
        private const val ACTIVE_TRIP_PERSIST_INTERVAL_MS = 30_000L

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
        val action = intent?.action
        if ((action == ACTION_MANUAL_START_TRIP || action == ACTION_MANUAL_END_TRIP) && ::geofenceEngine.isInitialized) {
            // Never act before the restored trip is in place, or a manual start would mint a second trip.
            serviceScope.launch {
                restoreDone.await()
                if (action == ACTION_MANUAL_START_TRIP) {
                    geofenceEngine.manualStartTrip(System.currentTimeMillis(), latestEmittedPoint)
                } else if (geofenceEngine.isTripActive) {
                    geofenceEngine.manualEndTrip(System.currentTimeMillis(), latestEmittedPoint)
                } else if (::engine.isInitialized && engine.currentTripId > 0L) {
                    engine.completeActiveTrip(System.currentTimeMillis())
                }
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
                    // Trip litres for it come from the engine's estimator (untracked km at learned
                    // mileage); the lifetime odometer counts the distance here.
                    com.antigravity.copilot.core.odometer.LifetimeOdometer.addMeters(departureDistMeters)
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

        acquireWakeLock()

        createNotificationChannel()
        startForegroundWithNotification()

        engine = createEngine()
        engine.monoClock = { android.os.SystemClock.elapsedRealtime() }
        engine.isMonotonicDomain = true
        engine.speedLimitProvider = {
            speedLimitEngine.speedLimitInfo.value.takeIf { it.isApplicable }?.speedLimitKmh?.toDouble()
        }
        engine.driverScoringEnabled = { AppSettingsManager.settingsState.value.driverScoring }

        // Restore runs first; fixes arriving meanwhile are buffered (see filterAndDispatchLocation).
        serviceScope.launch {
            try {
                restoreActiveTripFromDatabase()
            } catch (e: Exception) {
                android.util.Log.e("DrivingTrackingService", "Active trip restore failed: ${e.message}", e)
            } finally {
                releaseRestoreGate()
            }
            // Android sends no ACTION_POWER_CONNECTED at boot when power was already present, so a
            // trip restored in TEMPORARY_STOP would otherwise stay "power cut" forever.
            if (isExternalPowerPresent()) {
                engine.onPowerConnected(android.os.SystemClock.elapsedRealtime())
            }
        }
        serviceScope.launch {
            delay(RESTORE_GATE_TIMEOUT_MS)
            releaseRestoreGate()
        }
        registerGnssStatus()
        seedLifetimeOdometer()
        TrackingMaintenance.handler = this

        onCreateContinued()
    }

    /** One-time seed of the service-owned odometer from the legacy source (qualifying + active trip). */
    private fun seedLifetimeOdometer() {
        val odo = com.antigravity.copilot.core.odometer.LifetimeOdometer
        odo.init(applicationContext)
        if (odo.isSeeded) return
        serviceScope.launch(Dispatchers.IO) {
            try {
                val dao = database.tripDao()
                val qualifyingM = dao.sumQualifyingDistanceMeters()
                val activeM = dao.getActiveTrip()?.takeIf { it.endTime <= 0L }?.distanceMeters ?: 0.0
                odo.seedIfNeeded((qualifyingM + activeM) / 1000.0)
            } catch (e: Exception) {
                android.util.Log.w("DrivingTrackingService", "Lifetime odometer seed failed: ${e.message}")
            }
        }
    }

    private fun releaseRestoreGate() {
        if (restoreDone.isCompleted) return
        restoreDone.complete(Unit)
        val buffered = ArrayList(fixesBeforeRestore)
        fixesBeforeRestore.clear()
        for (loc in buffered) filterAndDispatchLocation(loc)
    }

    private fun isExternalPowerPresent(): Boolean = try {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        (battery?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    } catch (_: Exception) {
        false
    }

    private suspend fun restoreActiveTripFromDatabase() {
        run {
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
    }

    /** GnssStatus: satellites used in fix feed the weak-GNSS distance gate (API 24+). */
    @SuppressLint("MissingPermission")
    private fun registerGnssStatus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        try {
            val cb = object : android.location.GnssStatus.Callback() {
                override fun onSatelliteStatusChanged(status: android.location.GnssStatus) {
                    var used = 0
                    for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
                    gnssGate.onStatus(android.os.SystemClock.elapsedRealtime(), used)
                }
            }
            locationManager.registerGnssStatusCallback(cb, android.os.Handler(Looper.getMainLooper()))
            gnssCallback = cb
        } catch (e: Exception) {
            android.util.Log.w("DrivingTrackingService", "GnssStatus unavailable: ${e.message}")
        }
    }

    private fun unregisterGnssStatus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        (gnssCallback as? android.location.GnssStatus.Callback)?.let {
            try { locationManager.unregisterGnssStatusCallback(it) } catch (_: Exception) {}
        }
        gnssCallback = null
    }

    private fun saveLastKnownPosition(point: TripPoint?, force: Boolean) {
        point ?: return
        if (positionSaveThrottle.shouldSave(android.os.SystemClock.elapsedRealtime(), point.latitude, point.longitude, force)) {
            com.antigravity.copilot.core.map.OfflineMapManager.saveLastKnownPosition(applicationContext, point.latitude, point.longitude)
        }
    }

    private fun persistActiveTripNow() {
        if (!::engine.isInitialized) return
        engine.getCurrentTripSummary()?.let { tripRepository.persistActiveTrip(it) }
        lastActiveTripPersistMono = android.os.SystemClock.elapsedRealtime()
    }

    private fun createEngine(): TripDetectionEngine =
        TripDetectionEngine(
            onPointReceived = { point ->
                lastPointTimestamp = android.os.SystemClock.elapsedRealtime()
                latestEmittedPoint = point
                saveLastKnownPosition(point, force = false)
                tripRepository.onLocationReceived(point)
                vehicleStateManager.onSpeedOrLocationUpdated(point.speedKmh, point.timestamp, engine.currentTripId.takeIf { it > 0L })
                roadAnomalyManager.onLocation(point)
                com.antigravity.copilot.core.fuel.station.FuelStationTracker.getInstance(applicationContext).onLocation(point)
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
                // One trip-fuel number everywhere (dashboard = trip card): the engine's estimator.
                fuelRepository.onTripFuelEstimate(summary.estimatedFuelConsumedLiters)
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

    private fun onCreateContinued() {
        // Dynamically sync geofence control state with geofenceManager
        serviceScope.launch {
            geofenceManager.geofences.collect { geofences ->
                val hasEnabled = geofences.any { it.isEnabled }
                engine.isGeofenceControlled = hasEnabled
            }
        }
        registerLinearAccelerationSensor()
        roadAnomalyManager.start()
        registerDebugDriveSimulator()
        tripRepository.learnedMileageProvider = { fuelRepository.getFuelTelemetrySnapshot().mileageKmpl }
        engine.fuelTelemetryProvider = {
            fuelRepository.getFuelTelemetrySnapshot()
        }
        engine.onLiveMovement = { stepDistMeters, speedKmh, dtMs ->
            // Service-owned lifetime odometer (G1): every accepted segment, UI alive or not.
            com.antigravity.copilot.core.odometer.LifetimeOdometer.addMeters(stepDistMeters)
            fuelRepository.consumeLiveFuel(
                deltaDistanceMeters = stepDistMeters,
                currentSpeedKmh = speedKmh,
                deltaDurationMs = dtMs
            )
        }
        // Bridged distance: trip litres come from the engine's estimator; the odometer counts it.
        engine.onBridgeDistance = { meters -> com.antigravity.copilot.core.odometer.LifetimeOdometer.addMeters(meters) }

        // Automotive Vehicle Ignition / Power Off & System Shutdown Detector
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SHUTDOWN)
            addAction(Intent.ACTION_REBOOT)
            addAction("android.intent.action.QUICKBOOT_POWEROFF")
            addAction("com.htc.intent.action.QUICKBOOT_POWEROFF")
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val now = System.currentTimeMillis()
                val nowMono = android.os.SystemClock.elapsedRealtime()
                when (intent?.action) {
                    Intent.ACTION_POWER_CONNECTED -> {
                        isPowerCut = false
                        roadAnomalyManager.start() // ACC on: (re)starts the 5 s sensor wake-up guard
                        vehicleStateManager.onHardwarePowerConnected(now)
                        engine.onPowerConnected(nowMono)
                        acquireWakeLock()
                        requestLocationUpdates()
                    }
                    Intent.ACTION_SCREEN_ON -> roadAnomalyManager.onWake()
                    Intent.ACTION_POWER_DISCONNECTED -> {
                        isPowerCut = true
                        roadAnomalyManager.stop()
                        if (::geofenceEngine.isInitialized) {
                            geofenceEngine.onPowerDisconnected(now)
                        }
                        engine.onPowerDisconnected(nowMono)
                        vehicleStateManager.onHardwarePowerDisconnected(now, engine.currentTripId.takeIf { it > 0L })

                        // Safely persist ongoing journey in TEMPORARY_STOP in Room SQLite
                        persistActiveTripNow()
                        saveLastKnownPosition(latestEmittedPoint, force = true)
                        com.antigravity.copilot.core.odometer.LifetimeOdometer.flush()

                        // Immediately reset speed display to zero upon engine cut
                        latestEmittedPoint?.let { pt ->
                            if (pt.speedKmh > 0f) {
                                val zeroPoint = pt.copy(speedKmh = 0f, timestamp = now, elapsedMs = nowMono)
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
                        // Shutdown keeps the trip open for restore; persist its latest state now.
                        engine.forceCompleteOngoingTrip(now)
                        persistActiveTripNow()
                        saveLastKnownPosition(latestEmittedPoint, force = true)
                        com.antigravity.copilot.core.odometer.LifetimeOdometer.flush()
                        vehicleStateManager.onSystemShutdown("SYSTEM_SHUTDOWN", now, engine.currentTripId.takeIf { it > 0L })
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
                if (isPowerCut || maintenancePaused) {
                    continue
                }
                val now = System.currentTimeMillis()
                // Durations on the monotonic clock: the head-unit RTC may be wrong or jump at NTP sync.
                val nowMono = android.os.SystemClock.elapsedRealtime()
                val elapsed = nowMono - lastPointTimestamp
                val lastPt = latestEmittedPoint
                // Only synthesize zero-speed after full 10-second confirmed standstill
                // Allows UI SpeedometerCluster to execute graceful deceleration curve
                if (elapsed > 10000L && lastPt != null && lastPt.speedKmh > 0f) {
                    val zeroPoint = lastPt.copy(speedKmh = 0f, timestamp = now, elapsedMs = nowMono)
                    latestEmittedPoint = zeroPoint
                    tripRepository.onLocationReceived(zeroPoint)
                }
                engine.processStationaryWatchdog(nowMono)
                vehicleStateManager.onPeriodicTick(now)
                // Periodic cheap UPDATE of the live trip row so a kill loses at most 30 s of summary.
                if (engine.currentTripId > 0L && nowMono - lastActiveTripPersistMono >= ACTIVE_TRIP_PERSIST_INTERVAL_MS) {
                    persistActiveTripNow()
                }

                // Pump-stop prompt (pure state machine): station lookup after 3 s stopped, prompt after
                // the dwell time at a confirmed fuel station, 30 min / 200 m cooldown per pump.
                val currentPt = latestEmittedPoint
                if (currentPt != null) {
                    val settings = com.antigravity.copilot.core.settings.AppSettingsManager.settingsState.value
                    val action = pumpStop.onTick(
                        nowMs = nowMono,
                        speedKmh = currentPt.speedKmh.toDouble(),
                        lat = currentPt.latitude,
                        lon = currentPt.longitude,
                        enabled = settings.stationStopWatchdog,
                        dwellMs = (settings.stationPromptDwellSeconds * 1000L).coerceIn(15000L, 180000L),
                        stationarySpeedKmh = settings.stationarySpeedThresholdKmh.toDouble()
                    )
                    handlePumpStopAction(action)
                }
            }
        }

        // Location updates are started in onStartCommand
    }

    private fun handlePumpStopAction(action: PumpStopStateMachine.Action) {
        when (action) {
            is PumpStopStateMachine.Action.CheckStation -> serviceScope.launch {
                val detection = detectFuelStationOfflineFirst(action.lat, action.lon)
                handlePumpStopAction(
                    pumpStop.onStationResult(android.os.SystemClock.elapsedRealtime(), detection.isFuelStation, detection.stationName)
                )
            }
            is PumpStopStateMachine.Action.Prompt -> fuelRepository.triggerRefuelPrompt(
                locationName = action.stationName,
                lat = action.lat,
                lon = action.lon,
                odoKm = fuelRepository.fuelState.value.odometerKm
            )
            PumpStopStateMachine.Action.None -> Unit
        }
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
        if (isPowerCut || isLocationUpdatesActive || maintenancePaused) {
            return
        }
        stopLocationUpdates()

        try {
            if (!initialCachedFixProcessed) {
                processInitialCachedLocation()
            }

            // Request Fused Location updates (1 second, high accuracy)
            // Safe mode (after a crash loop) lowers the GPS rate to cut CPU / power load.
            val gpsIntervalMs = com.antigravity.copilot.core.recovery.SafeMode.gpsIntervalMs()
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, gpsIntervalMs)
                .setMinUpdateIntervalMillis(gpsIntervalMs / 2)
                .setMinUpdateDistanceMeters(0.5f)
                .build()
            fusedLocationClient.requestLocationUpdates(request, fusedCallback, Looper.getMainLooper())

            // Native LocationManager fallback
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    gpsIntervalMs,
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
                if (isPreciseFix(fusedLoc)) {
                    com.antigravity.copilot.core.map.OfflineMapManager.saveLastKnownPosition(
                        applicationContext, fusedLoc.latitude, fusedLoc.longitude
                    )
                }
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
                if (isPreciseFix(bestInitial)) {
                    com.antigravity.copilot.core.map.OfflineMapManager.saveLastKnownPosition(
                        applicationContext, bestInitial.latitude, bestInitial.longitude
                    )
                }
                filterAndDispatchLocation(bestInitial)
            }
        } catch (e: SecurityException) {
            android.util.Log.w("DrivingTrackingService", "Location permission missing for cached fix query: ${e.message}")
        }
    }

    /**
     * GNSS fixes are precise; network (cell / Wi-Fi / IP) fixes never are, whatever accuracy they claim
     * (emulators and some head units report bogus 1 m network accuracy). Fused fixes count as precise
     * only when their accuracy is GNSS-grade.
     */
    private fun isPreciseFix(location: Location): Boolean = when (location.provider) {
        LocationManager.GPS_PROVIDER -> true
        LocationManager.NETWORK_PROVIDER -> false
        else -> location.hasAccuracy() && location.accuracy <= PRECISE_ACCURACY_METERS
    }

    override fun onLocationChanged(location: Location) {
        if (debugSim?.isRunning == true) return // debug drive simulator owns the position
        // Network fixes are only a fallback: ignore them while GNSS is delivering.
        if (location.provider == LocationManager.NETWORK_PROVIDER &&
            android.os.SystemClock.elapsedRealtime() - lastPreciseFixElapsedMs < NETWORK_FALLBACK_AFTER_MS
        ) {
            return
        }
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

    /** Debug builds only: virtual drive (see DebugDriveSimulator). */
    private var debugSim: com.antigravity.copilot.core.debug.DebugDriveSimulator? = null
    private var debugSimReceiver: BroadcastReceiver? = null

    private fun registerDebugDriveSimulator() {
        if (!com.antigravity.copilot.BuildConfig.DEBUG) return
        val sim = com.antigravity.copilot.core.debug.DebugDriveSimulator(
            context = applicationContext,
            scope = serviceScope,
            fuelRepository = fuelRepository,
            roadAnomalyManager = roadAnomalyManager,
            currentPoint = { latestEmittedPoint },
            dispatch = { filterAndDispatchLocation(it) }
        )
        debugSim = sim
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getBooleanExtra("stop", false)) { sim.stop(); return }
                sim.start(
                    com.antigravity.copilot.core.debug.DebugDriveSimulator.Config(
                        destLat = i.getFloatExtra("destLat", com.antigravity.copilot.core.debug.DebugDriveSimulator.RAIGARH_LAT.toFloat()).toDouble(),
                        destLon = i.getFloatExtra("destLon", com.antigravity.copilot.core.debug.DebugDriveSimulator.RAIGARH_LON.toFloat()).toDouble(),
                        cruiseKmh = i.getFloatExtra("cruiseKmh", 100f).toDouble(),
                        refuels = i.getIntExtra("refuels", 4),
                        rupees = i.getFloatExtra("rupees", 100f).toDouble(),
                        rate = i.getFloatExtra("rate", 101f).toDouble(),
                        turnAtKm = i.getFloatExtra("turnAtKm", 0f).toDouble()
                    )
                )
            }
        }
        debugSimReceiver = receiver
        val filter = IntentFilter(com.antigravity.copilot.core.debug.DebugDriveSimulator.ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    /**
     * Pump-stop station check: a saved station (Room) or a PMTiles fuel POI within 40 m first —
     * both work offline and cost no network — then the existing online lookup as a fallback.
     */
    private suspend fun detectFuelStationOfflineFirst(lat: Double, lon: Double): FuelStationDetectionResult {
        val radius = com.antigravity.copilot.core.fuel.station.FuelStationRepository.STATION_MATCH_RADIUS_M
        com.antigravity.copilot.core.fuel.station.FuelStationRepository.getInstance(applicationContext)
            .findStationNear(lat, lon, radius)?.let { return FuelStationDetectionResult(true, it.name) }
        com.antigravity.copilot.core.fuel.station.FuelPoiCache.nearest(lat, lon, radius)?.let {
            return FuelStationDetectionResult(true, it.name.ifBlank { "Fuel Station" })
        }
        return LocationNameResolver.detectNearbyFuelStation(applicationContext, lat, lon)
    }

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
        if (TrackingMaintenance.handler === this) TrackingMaintenance.handler = null
        // Persist the live trip summary and position before the scope dies (kill / stopService).
        try {
            if (!maintenancePaused) persistActiveTripNow()
            saveLastKnownPosition(latestEmittedPoint, force = true)
            com.antigravity.copilot.core.odometer.LifetimeOdometer.flush()
        } catch (e: Exception) {
            android.util.Log.w("DrivingTrackingService", "onDestroy persist failed: ${e.message}")
        }
        unregisterGnssStatus()
        super.onDestroy()
        debugSim?.stop()
        debugSimReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        linearAccelListener?.let { l ->
            try {
                (getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager)?.unregisterListener(l)
            } catch (_: Exception) {}
        }
        roadAnomalyManager.stop()
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
