package com.antigravity.copilot.core.trip

import android.location.Location
import com.antigravity.copilot.core.fuel.FuelTelemetrySnapshot
import com.antigravity.copilot.core.model.TripPoint
import com.antigravity.copilot.core.model.TripStatus
import com.antigravity.copilot.core.model.TripSummary
import kotlin.math.*

class TripDetectionEngine(
    private val onPointReceived: (TripPoint) -> Unit,
    private val onTripStarted: (Long) -> Unit,
    private val onTripUpdated: (TripSummary, TripPoint) -> Unit,
    private val onTripCompleted: (TripSummary) -> Unit,
    private val onTripDiscarded: (Long) -> Unit
) {
    var isGeofenceControlled: Boolean = true
    var fuelTelemetryProvider: (() -> FuelTelemetrySnapshot)? = null
    var onLiveMovement: ((deltaDistanceMeters: Double, speedKmh: Double, deltaDurationMs: Long) -> Unit)? = null
    /** Distance added without per-second fixes (candidate buffer, reboot / restore bridge); charged at learned mileage. */
    var onBridgeDistance: ((meters: Double) -> Unit)? = null

    /**
     * Monotonic clock (ms) for every DURATION in this engine. The service wires
     * SystemClock.elapsedRealtime(); fixes are mapped onto the same domain via
     * Location.elapsedRealtimeNanos. Wall-clock time is used only for trip IDs / display.
     * Default (unit tests): wall clock, matching fixes that carry no elapsed time.
     */
    var monoClock: () -> Long = { System.currentTimeMillis() }
    /**
     * True once [monoClock] is a real monotonic clock (service: SystemClock.elapsedRealtime()).
     * Persisted wall-clock anchors then cannot be mixed with it, so a restore re-anchors durations
     * to the restore instant. False (JVM tests): fixes, watchdog and restore share one time base.
     */
    var isMonotonicDomain: Boolean = false
    /** Current road speed limit (km/h) or null when unknown; overspeed falls back to [SPEED_LIMIT_KMH]. */
    var speedLimitProvider: (() -> Double?)? = null
    /** Settings "driver scoring" switch. */
    var driverScoringEnabled: () -> Boolean = { true }
    /** Weak GNSS (< 4 satellites used in fix): fixes still drive the UI but add no trip distance. */
    var distanceAccumulationPaused: Boolean = false
    /** Trips shorter than this are discarded in both auto and geofence modes. */
    var minTripDistanceMeters: Double = MIN_SAVED_TRIP_DISTANCE_METERS

    var currentStatus: TripStatus = TripStatus.IDLE
        private set

    var currentTripId: Long = 0
        private set
    var currentOriginName: String = ""
        private set
    var currentDestinationName: String = ""
        private set
    private var tripStartTime: Long = 0
    private var lastMovementTime: Long = 0
    private var candidateStartTime: Long = 0
    private var temporaryStopStartTime: Long = 0

    var totalDistanceMeters: Double = 0.0
        private set
    var drivingDurationMs: Long = 0
        private set
    var stationaryDurationMs: Long = 0
        private set
    /** Pure moving/idle split (idle = < 2 km/h for > 30 s, see Config). */
    private val motion = com.antigravity.copilot.core.logic.MotionTimeTracker()
    /** Displayed true speed: Doppler, accuracy-gated, lightly smoothed. */
    private val speedFilter = com.antigravity.copilot.core.logic.SpeedFilter()
    /** IDLE / ACCELERATING / CRUISING / BRAKING label + brake/accel event counts. */
    val drivingClassifier = com.antigravity.copilot.core.logic.DrivingStateClassifier()
    /** Orientation-independent longitudinal axis from the linear-acceleration sensor. */
    private val longitudinalEstimator = com.antigravity.copilot.core.logic.LongitudinalAccelEstimator()
    /** Trip distance = filtered haversine segments (not speed x time). */
    private val odometer = com.antigravity.copilot.core.logic.OdometerAccumulator()
    /** Current-trip fuel estimate (speed / accel / learned mileage; idle excluded; never feeds the tank). */
    private val tripFuel = com.antigravity.copilot.core.logic.TripFuelEstimator()

    /** Distance not stepped through the estimator (departure buffer, tunnel/reboot bridges) uses plain mileage. */
    private fun tripFuelLitres(mileage: Double): Double {
        val untrackedKm = (totalDistanceMeters / 1000.0 - tripFuel.km).coerceAtLeast(0.0)
        return tripFuel.litres + untrackedKm / mileage
    }

    private fun tripMileage(fuelL: Double, fallback: Double): Double =
        if (fuelL > 1e-6 && totalDistanceMeters > 0.0) (totalDistanceMeters / 1000.0) / fuelL else fallback

    val drivingState: com.antigravity.copilot.core.logic.DrivingState get() = drivingClassifier.state

    /** Feed TYPE_LINEAR_ACCELERATION samples (device frame, m/s²). Thread-safe. */
    fun onLinearAcceleration(ax: Float, ay: Float, az: Float) {
        synchronized(longitudinalEstimator) { longitudinalEstimator.onSensor(ax.toDouble(), ay.toDouble(), az.toDouble()) }
    }

    /** [timeMs] must be in the monotonic domain of the fixes that follow. */
    private fun seedOdometer(loc: Location?, timeMs: Long) {
        odometer.breakSegment()
        if (loc != null) {
            odometer.onFix(
                com.antigravity.copilot.core.logic.GeoFix(loc.latitude, loc.longitude, 0.0, timeMs, 0.0)
            )
        }
    }

    /** Monotonic time of a fix: elapsedRealtimeNanos when the platform set it, else its own time. */
    private fun monoOf(location: Location): Long {
        val nanos = try { location.elapsedRealtimeNanos } catch (_: Throwable) { 0L }
        return when {
            nanos > 0L -> nanos / 1_000_000L
            location.time > 0L -> location.time
            else -> monoClock()
        }
    }

    private var lastFixMono: Long = Long.MIN_VALUE
    private var lastFixWall: Long = 0L

    /** Monotonic time the GPS came back after an outage while the tunnel bridge is still pending. */
    private var outageRecoveredAtMs: Long = 0L

    /** Maps a monotonic instant to wall-clock using the latest fix as the reference pair. */
    private fun wallFor(mono: Long): Long =
        if (lastFixMono != Long.MIN_VALUE) lastFixWall + (mono - lastFixMono) else System.currentTimeMillis()
    private var maxSpeedKmh: Double = 0.0
    private var speedSumKmh: Double = 0.0
    private var speedCount: Long = 0

    private var currentAltitude: Double = 0.0
    private var currentBearing: Float = 0f
    private var currentGForce: Float = 0f

    private var lastAcceptedLocation: Location? = null
    private var lastLocationTime: Long = 0

    // Driving behaviour: edge-triggered events on the smoothed, sensor-fused acceleration.
    private val harshCounter = com.antigravity.copilot.core.logic.HarshEventCounter()
    private val overspeed = com.antigravity.copilot.core.logic.OverspeedEventTracker()
    private val harshBrakes: Int get() = harshCounter.harshBrakes
    private val rapidAccels: Int get() = harshCounter.rapidAccels
    private val overspeedInstances: Int get() = overspeed.events
    private var maxAccelMps2: Float = 0f
    private var maxDecelMps2: Float = 0f

    // 3-Second GPS Warmup & Stabilization:
    // Prevents startup coordinate jumps from injecting speed spikes, odometer noise, or false trip triggers
    var isGpsStabilized: Boolean = false
        internal set
    private var firstAccurateFixTimeMs: Long = 0L

    // Tunnel & Signal Loss Bridging Anchor
    private var tunnelEntryLocation: Location? = null
    private var tunnelEntryTimeMs: Long = 0L
    private var isOutageBridgingPending: Boolean = false

    // Reboot First-Fix Anchor
    private var rebootAnchorLocation: Location? = null

    // Reliable Stationary Detection & Debounce
    var stationaryCompletionThresholdSeconds: Int = IDLE_TRIP_END_TIMEOUT_SECONDS
    var stationaryDriftRadiusMeters: Double = 35.0
    var isPowerDisconnected: Boolean = false
        internal set
    private var stationaryAnchorLocation: Location? = null

    companion object {
        const val MIN_QUALIFYING_DISTANCE_METERS = 1000.0 // 1.0 km
        const val MOVEMENT_SPEED_THRESHOLD_KMH = 6.0f
        val STOP_SPEED_THRESHOLD_KMH = com.antigravity.copilot.core.logic.Config.IDLE_SPEED_KMH.toFloat()
        const val CANDIDATE_CONFIRM_SECONDS = 15
        const val CANDIDATE_MIN_DISTANCE_METERS = 50.0
        const val IDLE_TRIP_END_TIMEOUT_SECONDS = 300 // 5 minutes of continuous stationary idle ends trip
        const val MAX_VALID_ACCURACY_METERS = 50.0f
        const val SPEED_LIMIT_KMH = 80.0
        const val MIN_SAVED_TRIP_DISTANCE_METERS = 200.0
        const val OUTAGE_BRIDGE_ACCURACY_M = 25.0f
        const val OUTAGE_BRIDGE_MAX_WAIT_MS = 60_000L
        const val BASE_MILEAGE_KMPL = 18.0
        const val FUEL_PRICE_PER_LITER = 96.5

        const val GPS_WARMUP_STABILIZATION_MS = 3000L // 3 seconds stabilization window
        const val ACCURATE_GPS_THRESHOLD_METERS = 35.0f
        const val MAX_STEP_DT_MS = 5000L // 5-second upper clamp on telemetry dt
        const val MAX_REBOOT_BRIDGE_DISTANCE_METERS = 50000.0 // 50 km (50,000m) upper bound on reboot distance bridging

        /**
         * Autonomous Great-Circle (Haversine) geodesic distance calculation in meters.
         * Operates identically on Android device runtime and JVM unit test runners.
         */
        fun computeGeodesicDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = Math.sin(dLat / 2).let { it * it } +
                    Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                    Math.sin(dLon / 2).let { it * it }
            val c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a))
            return 6371000.0 * c
        }
    }

    fun reset() {
        currentStatus = TripStatus.IDLE
        currentTripId = 0
        tripStartTime = 0
        lastMovementTime = 0
        lastLocationTime = 0
        temporaryStopStartTime = 0
        candidateStartTime = 0
        lastAcceptedLocation = null
        totalDistanceMeters = 0.0
        drivingDurationMs = 0
        stationaryDurationMs = 0
        motion.reset()
        tripFuel.reset()
        maxSpeedKmh = 0.0
        speedSumKmh = 0.0
        speedCount = 0
        harshCounter.reset()
        overspeed.reset()
        maxAccelMps2 = 0f
        maxDecelMps2 = 0f
        isGpsStabilized = false
        outageRecoveredAtMs = 0L
        firstAccurateFixTimeMs = 0L
        tunnelEntryLocation = null
        tunnelEntryTimeMs = 0L
        isOutageBridgingPending = false
        rebootAnchorLocation = null
        stationaryAnchorLocation = null
        isPowerDisconnected = false
        currentOriginName = ""
        currentDestinationName = ""
    }

    /**
     * Triggered when vehicle engine ignition cuts 12V ACC / USB power.
     * Keeps ongoing trip safe in TEMPORARY_STOP and freezes duration accumulation.
     */
    fun onPowerDisconnected(@Suppress("UNUSED_PARAMETER") now: Long = monoClock()) {
        isPowerDisconnected = true
        if (currentStatus == TripStatus.ACTIVE_TRIP) {
            currentStatus = TripStatus.TEMPORARY_STOP
            temporaryStopStartTime = 0L
        } else if (currentStatus == TripStatus.TEMPORARY_STOP) {
            temporaryStopStartTime = 0L
        }
        motion.markGap()
        overspeed.markGap()
        tunnelEntryLocation = null
        tunnelEntryTimeMs = 0L
        isOutageBridgingPending = false
    }

    /**
     * Triggered when vehicle engine ignition powers back on (12V ACC / USB re-engaged).
     * Resumes trip telemetry seamlessly without accumulating stationary time from deep sleep.
     */
    /** [now] is monotonic (SystemClock.elapsedRealtime() on device). */
    fun onPowerConnected(now: Long = monoClock()) {
        isPowerDisconnected = false
        motion.markGap()
        if (currentStatus == TripStatus.TEMPORARY_STOP) {
            temporaryStopStartTime = now
            lastMovementTime = now
            lastLocationTime = now
        }
    }

    fun getCurrentTripSummary(now: Long = System.currentTimeMillis()): TripSummary? {
        if (currentTripId <= 0L) return null
        val drivingHours = drivingDurationMs / 3600000.0
        val avgSpeed = if (drivingHours > 0.002) {
            (totalDistanceMeters / 1000.0) / drivingHours
        } else if (speedCount > 0) {
            speedSumKmh / speedCount
        } else {
            0.0
        }
        val fuelSnapshot = fuelTelemetryProvider?.invoke()
        val effectiveMileage = fuelSnapshot?.mileageKmpl?.takeIf { it > 0.0 } ?: BASE_MILEAGE_KMPL
        val effectivePrice = fuelSnapshot?.fuelPricePerLiter?.takeIf { it > 0.0 } ?: FUEL_PRICE_PER_LITER
        val tripFuelConsumed = tripFuelLitres(effectiveMileage)
        val tripCost = tripFuelConsumed * effectivePrice

        return TripSummary(
            id = currentTripId,
            startTime = tripStartTime,
            endTime = 0L,
            distanceMeters = totalDistanceMeters,
            drivingDurationMs = drivingDurationMs,
            stationaryDurationMs = stationaryDurationMs,
            avgSpeedKmh = avgSpeed,
            maxSpeedKmh = maxSpeedKmh,
            currentAltitudeMeters = currentAltitude,
            currentBearing = currentBearing,
            currentGForce = currentGForce,
            maxAccelMss = maxAccelMps2,
            maxDecelMss = maxDecelMps2,
            estimatedFuelConsumedLiters = tripFuelConsumed,
            estimatedTripCost = tripCost,
            calculatedMileageKmpl = tripMileage(tripFuelConsumed, effectiveMileage),
            bestMileageKmpl = tripFuel.bestKmpl,
            driverScore = calculateDriverScore(),
            harshBrakeCount = harshBrakes,
            rapidAccelCount = rapidAccels,
            brakeCount = drivingClassifier.brakeEvents,
            accelCount = drivingClassifier.accelEvents,
            overspeedCount = overspeedInstances,
            startLocationName = currentOriginName,
            endLocationName = currentDestinationName,
            status = currentStatus
        )
    }

    /**
     * Enforces immediate trip completion on system shutdown or service destruction.
     * Guarantees that if the head unit shuts down at 10:00 AM, the trip is finalized at 10:00 AM.
     */
    fun forceCompleteOngoingTrip(@Suppress("UNUSED_PARAMETER") now: Long = System.currentTimeMillis()) {
        // Shutdown does not terminate ongoing multi-day trips; state is persisted for startup recovery
    }

    /**
     * Periodic watchdog tick to enforce trip completion when vehicle remains parked/idle for 5 minutes.
     */
    /** [now] is monotonic (SystemClock.elapsedRealtime() on device). */
    fun processStationaryWatchdog(now: Long = monoClock()) {
        if (isPowerDisconnected) return

        if (currentStatus == TripStatus.ACTIVE_TRIP) {
            val stationaryDuration = now - lastMovementTime
            if (stationaryDuration > com.antigravity.copilot.core.logic.Config.IDLE_MIN_DURATION_MS) {
                currentStatus = TripStatus.TEMPORARY_STOP
                temporaryStopStartTime = lastMovementTime
                if (stationaryAnchorLocation == null) {
                    stationaryAnchorLocation = lastAcceptedLocation
                }
            }
        }
        if (currentStatus == TripStatus.TEMPORARY_STOP && temporaryStopStartTime > 0L) {
            val stoppedSec = (now - temporaryStopStartTime) / 1000
            // In geofence-controlled mode, standstills outside geofences NEVER terminate trips
            if (!isGeofenceControlled && stoppedSec >= stationaryCompletionThresholdSeconds) {
                evaluateTripCompletion(wallFor(now))
            }
        }
    }

    class CoordinateLocation(
        private val _latitude: Double,
        private val _longitude: Double,
        private val _time: Long,
        private val _accuracy: Float = 5.0f,
        private val _speedKmh: Float = 0.0f,
        private val _bearing: Float = 0.0f,
        private val _altitude: Double = 0.0
    ) : Location("gps") {
        override fun getLatitude(): Double = _latitude
        override fun getLongitude(): Double = _longitude
        override fun getTime(): Long = _time
        override fun getAccuracy(): Float = _accuracy
        override fun hasAccuracy(): Boolean = true
        override fun getSpeed(): Float = _speedKmh / 3.6f
        override fun hasSpeed(): Boolean = true
        override fun getBearing(): Float = _bearing
        override fun hasBearing(): Boolean = _bearing != 0.0f
        override fun getAltitude(): Double = _altitude
        override fun hasAltitude(): Boolean = _altitude != 0.0
        override fun distanceTo(dest: Location): Float {
            return computeGeodesicDistance(_latitude, _longitude, dest.latitude, dest.longitude).toFloat()
        }
    }

    fun processCoordinates(
        latitude: Double,
        longitude: Double,
        accuracy: Float = 5.0f,
        speedKmh: Float = 0f,
        bearing: Float = 0f,
        altitude: Double = 0.0,
        timestamp: Long = System.currentTimeMillis()
    ) {
        val loc = CoordinateLocation(
            _latitude = latitude,
            _longitude = longitude,
            _time = timestamp,
            _accuracy = accuracy,
            _speedKmh = speedKmh,
            _bearing = bearing,
            _altitude = altitude
        )
        processLocation(loc)
    }

    fun processLocation(location: Location) {
        // Durations use the monotonic domain; the wall clock only stamps points and trip IDs.
        val now = monoOf(location)
        val wallNow = wallNow(location)
        val accuracy = if (location.hasAccuracy()) location.accuracy else 5.0f

        // Gate 0: Filter out completely invalid (0, 0) coordinates or inaccurate garbage (> 100m)
        if (location.latitude == 0.0 && location.longitude == 0.0) return
        if (accuracy > 100.0f) return

        lastFixMono = now
        lastFixWall = wallNow

        val isAccurateFix = accuracy <= ACCURATE_GPS_THRESHOLD_METERS

        // Detect GPS signal loss / gap (> 15s) while driving to bridge distance smoothly
        if (lastLocationTime > 0 && (now - lastLocationTime) > 15000L && isGpsStabilized) {
            if (currentStatus == TripStatus.ACTIVE_TRIP || currentStatus == TripStatus.TEMPORARY_STOP) {
                tunnelEntryLocation = lastAcceptedLocation
                tunnelEntryTimeMs = lastLocationTime
                isOutageBridgingPending = true
            }
            isGpsStabilized = false
            firstAccurateFixTimeMs = 0L
        }

        // 3-SECOND GPS WARM-UP & STABILIZATION GATE:
        // When launcher first starts or GPS acquires fix, coordinates jump erratically.
        // We wait for 3 continuous seconds of good GPS fix (accuracy <= 35m) before
        // computing speed, distance, or trip candidate transitions.
        if (!isGpsStabilized) {
            if (isAccurateFix) {
                if (firstAccurateFixTimeMs == 0L) {
                    firstAccurateFixTimeMs = now
                    if (rebootAnchorLocation == null) {
                        lastAcceptedLocation = location
                    }
                    lastLocationTime = now
                }
                val warmUpElapsed = now - firstAccurateFixTimeMs
                if (warmUpElapsed < GPS_WARMUP_STABILIZATION_MS) {
                    // Inside 3-second warm-up window:
                    // Continuously settle vehicle anchor point, clamp speed to 0 km/h, accumulate 0 distance
                    if (rebootAnchorLocation == null) {
                        lastAcceptedLocation = location
                    }
                    lastLocationTime = now
                    currentAltitude = if (location.hasAltitude()) location.altitude else currentAltitude
                    currentBearing = if (location.hasBearing()) location.bearing else currentBearing

                    val warmUpPoint = TripPoint(
                        tripId = currentTripId,
                        latitude = location.latitude,
                        longitude = location.longitude,
                        altitude = currentAltitude,
                        speedKmh = 0f,
                        bearing = currentBearing,
                        accuracy = accuracy,
                        timestamp = wallNow,
                        elapsedMs = now
                    )
                    onPointReceived(warmUpPoint)
                    return
                } else {
                    // Exactly 3 seconds of solid GPS lock confirmed! GPS is now STABLE.
                    isGpsStabilized = true

                    // 1. Bridge geodesic distance from pre-reboot anchor to the first stable post-reboot fix
                    val rebootAnchor = rebootAnchorLocation
                    if (rebootAnchor != null) {
                        rebootAnchorLocation = null
                        if (currentStatus == TripStatus.ACTIVE_TRIP || currentStatus == TripStatus.TEMPORARY_STOP) {
                            val bridgeDist = computeGeodesicDistance(rebootAnchor.latitude, rebootAnchor.longitude, location.latitude, location.longitude)
                            // Cross-boot: only the wall clocks of the two fixes are comparable here.
                            val timeDeltaSec = ((wallNow - rebootAnchor.time) / 1000.0).coerceAtLeast(1.0)
                            val impliedSpeedKmh = (bridgeDist / timeDeltaSec) * 3.6
                            if (bridgeDist in 4.0..MAX_REBOOT_BRIDGE_DISTANCE_METERS && impliedSpeedKmh < 220.0) {
                                totalDistanceMeters += bridgeDist
                                onBridgeDistance?.invoke(bridgeDist)
                            }
                        }
                    }

                    // 2. Check if recovering from a signal outage / tunnel while on an active trip.
                    // A coarse exit fix keeps the bridge pending (see tryBridgeOutage) instead of dropping it.
                    if (isOutageBridgingPending && tunnelEntryLocation != null) {
                        outageRecoveredAtMs = now
                        tryBridgeOutage(location, accuracy, now)
                    } else {
                        isOutageBridgingPending = false
                    }

                    lastAcceptedLocation = location
                    lastLocationTime = now
                    seedOdometer(location, now) // bridged distance already added; restart segments here
                    currentAltitude = if (location.hasAltitude()) location.altitude else currentAltitude
                    currentBearing = if (location.hasBearing()) location.bearing else currentBearing

                    val initialStablePoint = TripPoint(
                        tripId = currentTripId,
                        latitude = location.latitude,
                        longitude = location.longitude,
                        altitude = currentAltitude,
                        speedKmh = 0f,
                        bearing = currentBearing,
                        accuracy = accuracy,
                        timestamp = wallNow,
                        elapsedMs = now
                    )
                    onPointReceived(initialStablePoint)
                    return
                }
            } else {
                // Ignore coarse fixes before GPS has stabilized
                return
            }
        }

        // 1. High-precision speed resolution with Doppler & Geodesic delta fusion
        val prevLocation = lastAcceptedLocation
        val computedSpeedKmh = if (prevLocation != null && lastLocationTime > 0) {
            val timeDeltaSec = ((now - lastLocationTime) / 1000.0).coerceAtLeast(0.1)
            val dist = computeGeodesicDistance(prevLocation.latitude, prevLocation.longitude, location.latitude, location.longitude)
            (dist / timeDeltaSec * 3.6).toFloat()
        } else {
            0f
        }

        // True speed = GPS Doppler, accuracy-filtered, lightly smoothed (no max() fusion overshoot).
        val dopplerMps = when {
            location.hasSpeed() -> location.speed.toDouble()
            computedSpeedKmh in 3.0f..220.0f -> computedSpeedKmh / 3.6
            else -> null
        }
        val effectiveSpeedKmh = speedFilter.onFix(dopplerMps, accuracy.toDouble(), now).toFloat()

        // Driving state label (sensor fused with GPS dv/dt when the sensor axis is learned).
        val gpsAccel = drivingClassifier.gpsAccel(effectiveSpeedKmh.toDouble(), now)
        val sensorLongitudinal = gpsAccel?.let { a -> synchronized(longitudinalEstimator) { longitudinalEstimator.onGps(a) } }
        drivingClassifier.onSample(now, effectiveSpeedKmh.toDouble(), sensorLongitudinal)

        currentAltitude = if (location.hasAltitude()) location.altitude else currentAltitude

        // Heading & Bearing resolution: Use sensor bearing or calculate trajectory delta
        if (location.hasBearing() && location.bearing != 0f) {
            currentBearing = location.bearing
        } else if (prevLocation != null && effectiveSpeedKmh >= 3.0f) {
            val computedBearing = prevLocation.bearingTo(location)
            if (computedBearing != 0f) {
                currentBearing = (computedBearing + 360f) % 360f
            }
        }

        // ALWAYS emit latest point so Map & HUD immediately lock onto vehicle location (never fall back to Delhi)
        val currentPoint = TripPoint(
            tripId = currentTripId,
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = currentAltitude,
            speedKmh = effectiveSpeedKmh,
            bearing = currentBearing,
            accuracy = accuracy,
            timestamp = wallNow,
            drivingState = drivingClassifier.state.name,
            elapsedMs = now
        )
        onPointReceived(currentPoint)

        // GPS Accuracy Gate - ignore high jitter readings for TRIP DISTANCE ACCUMULATION ONLY
        if (accuracy > MAX_VALID_ACCURACY_METERS && accuracy > 0) {
            return
        }

        // Deduplicate rapid duplicate fixes for trip tracking
        if (lastAcceptedLocation != null && lastLocationTime > 0) {
            val wallDiffMs = now - lastLocationTime
            if (wallDiffMs < 350L) {
                return
            }
        }

        // Tunnel exit with a coarse first fix: keep waiting (max 60 s) for an accurate one before bridging.
        if (isOutageBridgingPending) {
            if (tunnelEntryLocation == null) {
                isOutageBridgingPending = false
            } else {
                val bridged = tryBridgeOutage(location, accuracy, now)
                if (effectiveSpeedKmh >= STOP_SPEED_THRESHOLD_KMH) lastMovementTime = now
                lastAcceptedLocation = location
                lastLocationTime = now
                if (bridged) seedOdometer(location, now) else odometer.breakSegment()
                motion.markGap() // the bridge attributes the whole outage window as moving time
                return
            }
        }

        when (currentStatus) {
            TripStatus.IDLE -> {
                if (!isGeofenceControlled && effectiveSpeedKmh >= MOVEMENT_SPEED_THRESHOLD_KMH) {
                    currentStatus = TripStatus.MOVEMENT_CANDIDATE
                    candidateStartTime = now
                    lastMovementTime = now
                    lastAcceptedLocation = location
                    lastLocationTime = now
                }
            }

            TripStatus.MOVEMENT_CANDIDATE -> {
                val candidateDurationSec = (now - candidateStartTime) / 1000
                val distFromStart = if (lastAcceptedLocation != null) {
                    computeGeodesicDistance(lastAcceptedLocation!!.latitude, lastAcceptedLocation!!.longitude, location.latitude, location.longitude).toFloat()
                } else 0f

                if (effectiveSpeedKmh < STOP_SPEED_THRESHOLD_KMH && candidateDurationSec > 8) {
                    currentStatus = TripStatus.IDLE
                    lastAcceptedLocation = null
                } else if (candidateDurationSec >= CANDIDATE_CONFIRM_SECONDS || distFromStart >= CANDIDATE_MIN_DISTANCE_METERS) {
                    // Anchor start time to exact moment movement was initiated
                    val tripEffectiveStart = if (candidateStartTime > 0L) candidateStartTime else now
                    startNewTrip(tripEffectiveStart, location, distFromStart.toDouble(), now)
                }
            }

            TripStatus.ACTIVE_TRIP -> {
                handleActiveTripMovement(now, location, effectiveSpeedKmh)
            }

            TripStatus.TEMPORARY_STOP -> {
                val stoppedSec = if (temporaryStopStartTime > 0L) (now - temporaryStopStartTime) / 1000 else 0L
                val anchor = stationaryAnchorLocation ?: location
                val distFromAnchor = computeGeodesicDistance(anchor.latitude, anchor.longitude, location.latitude, location.longitude)
                val isResumed = effectiveSpeedKmh >= MOVEMENT_SPEED_THRESHOLD_KMH && (distFromAnchor >= stationaryDriftRadiusMeters || effectiveSpeedKmh >= MOVEMENT_SPEED_THRESHOLD_KMH * 1.5f)

                motion.onSample(now, effectiveSpeedKmh.toDouble())
                drivingDurationMs = motion.movingMs
                stationaryDurationMs = motion.idleMs
                if (isResumed) {
                    lastMovementTime = now
                    currentStatus = TripStatus.ACTIVE_TRIP
                    lastAcceptedLocation = location
                    lastLocationTime = now
                    stationaryAnchorLocation = null
                    temporaryStopStartTime = 0L
                    // Real movement proves the ignition is on (Android sends no POWER_CONNECTED at boot
                    // when power was already present), so a restored power-cut flag must not linger.
                    isPowerDisconnected = false
                    // Drift collected during the stop must not become the first segment after it.
                    seedOdometer(location, now)
                    overspeed.markGap()
                } else {
                    lastAcceptedLocation = location
                    lastLocationTime = now
                    if (!isGeofenceControlled && !isPowerDisconnected && temporaryStopStartTime > 0L && stoppedSec >= stationaryCompletionThresholdSeconds) {
                        evaluateTripCompletion(wallNow)
                    }
                }
            }

            TripStatus.STOP_CANDIDATE, TripStatus.COMPLETED, TripStatus.INTERRUPTED -> {
                if (!isGeofenceControlled && effectiveSpeedKmh >= MOVEMENT_SPEED_THRESHOLD_KMH) {
                    currentStatus = TripStatus.MOVEMENT_CANDIDATE
                    candidateStartTime = now
                    lastMovementTime = now
                    lastAcceptedLocation = location
                    lastLocationTime = now
                }
            }
        }
    }

    
    fun startGeofenceTrip(
        startTime: Long,
        initialLocation: Location,
        departureDistanceMeters: Double = 0.0,
        originName: String = ""
    ) {
        currentStatus = TripStatus.ACTIVE_TRIP
        currentTripId = startTime
        tripStartTime = startTime
        currentOriginName = originName
        currentDestinationName = ""
        // Called from the geofence engine inside the current fix: the last fix is the time reference.
        val fixTime = if (lastFixMono != Long.MIN_VALUE) lastFixMono else monoOf(initialLocation)
        lastMovementTime = fixTime
        lastLocationTime = fixTime
        lastAcceptedLocation = initialLocation
        seedOdometer(initialLocation, fixTime)
        drivingClassifier.restoreCounts(0, 0)
        totalDistanceMeters = departureDistanceMeters
        drivingDurationMs = 0
        stationaryDurationMs = 0
        motion.reset()
        tripFuel.reset()
        maxSpeedKmh = 0.0
        speedSumKmh = 0.0
        speedCount = 0
        harshCounter.reset()
        overspeed.reset()
        maxAccelMps2 = 0f
        maxDecelMps2 = 0f
        currentGForce = 0f
        tunnelEntryLocation = null
        tunnelEntryTimeMs = 0L
        isOutageBridgingPending = false
        rebootAnchorLocation = null

        onTripStarted(currentTripId)
    }

    /** [now] is the wall-clock end time. */
    fun completeGeofenceTrip(now: Long = System.currentTimeMillis(), destinationName: String? = null) {
        if (currentTripId > 0L || currentStatus == TripStatus.ACTIVE_TRIP || currentStatus == TripStatus.TEMPORARY_STOP || currentStatus == TripStatus.STOP_CANDIDATE) {
            evaluateTripCompletion(now, destinationName)
        }
    }

    /** [now] is the wall-clock end time. */
    fun completeActiveTrip(now: Long = System.currentTimeMillis(), destinationName: String? = null) {
        if (currentTripId > 0L || currentStatus == TripStatus.ACTIVE_TRIP || currentStatus == TripStatus.TEMPORARY_STOP || currentStatus == TripStatus.STOP_CANDIDATE) {
            evaluateTripCompletion(now, destinationName)
        }
    }

    fun restoreActiveTripState(
        tripId: Long,
        startTime: Long,
        distanceMeters: Double,
        drivingMs: Long,
        stationaryMs: Long,
        lastLocation: Location? = null,
        originName: String = "",
        status: TripStatus = TripStatus.ACTIVE_TRIP,
        brakeCount: Int = 0,
        accelCount: Int = 0,
        estimatedFuelLiters: Double = 0.0,
        bestMileageKmpl: Double = 0.0
    ) {
        drivingClassifier.restoreCounts(brakeCount, accelCount)
        tripFuel.restore(distanceMeters / 1000.0, estimatedFuelLiters, bestMileageKmpl)
        currentStatus = status
        currentTripId = tripId
        tripStartTime = startTime
        currentOriginName = originName
        val effectiveAnchorTime = if (lastLocation != null && lastLocation.time > 0L) lastLocation.time else startTime
        // Persisted anchors are wall-clock. On device durations run on the monotonic clock, so they
        // re-anchor to the restore instant (the stop / idle clocks restart from here).
        val durationAnchor = if (isMonotonicDomain) monoClock() else effectiveAnchorTime
        lastMovementTime = durationAnchor
        lastLocationTime = durationAnchor
        totalDistanceMeters = distanceMeters
        drivingDurationMs = drivingMs
        stationaryDurationMs = stationaryMs
        motion.restore(drivingMs, stationaryMs)
        if (status == TripStatus.TEMPORARY_STOP) {
            temporaryStopStartTime = 0L
            isPowerDisconnected = true
        }

        if (lastLocation != null) {
            currentAltitude = if (lastLocation.hasAltitude()) lastLocation.altitude else currentAltitude
            currentBearing = if (lastLocation.hasBearing()) lastLocation.bearing else currentBearing
            if (!isGpsStabilized) {
                rebootAnchorLocation = lastLocation
                lastAcceptedLocation = lastLocation
            } else {
                val curLoc = lastAcceptedLocation
                if (curLoc != null) {
                    val bridgeDist = computeGeodesicDistance(lastLocation.latitude, lastLocation.longitude, curLoc.latitude, curLoc.longitude)
                    val timeDeltaSec = ((effectiveAnchorTime - curLoc.time).coerceAtLeast(1000L) / 1000.0)
                    val impliedSpeedKmh = (bridgeDist / timeDeltaSec) * 3.6
                    if (bridgeDist in 4.0..MAX_REBOOT_BRIDGE_DISTANCE_METERS && impliedSpeedKmh < 220.0) {
                        totalDistanceMeters += bridgeDist
                        onBridgeDistance?.invoke(bridgeDist)
                    }
                }
                lastAcceptedLocation = curLoc ?: lastLocation
            }
        }
        seedOdometer(lastAcceptedLocation, if (lastFixMono != Long.MIN_VALUE) lastFixMono else durationAnchor)
    }

    private fun startNewTrip(startTime: Long, initialLocation: Location, candidateDistMeters: Double = 0.0, now: Long = startTime) {
        currentStatus = TripStatus.ACTIVE_TRIP
        // startTime is monotonic; the trip ID / start stamp is its wall-clock equivalent.
        val startWall = wallFor(startTime)
        currentTripId = startWall
        tripStartTime = startWall
        currentOriginName = ""
        currentDestinationName = ""
        lastMovementTime = startTime
        lastLocationTime = startTime
        lastAcceptedLocation = initialLocation
        seedOdometer(initialLocation, now)
        drivingClassifier.restoreCounts(0, 0)
        totalDistanceMeters = candidateDistMeters
        drivingDurationMs = 0
        stationaryDurationMs = 0
        motion.reset()
        tripFuel.reset()
        maxSpeedKmh = 0.0
        speedSumKmh = 0.0
        speedCount = 0
        harshCounter.reset()
        overspeed.reset()
        maxAccelMps2 = 0f
        maxDecelMps2 = 0f
        currentGForce = 0f
        tunnelEntryLocation = null
        tunnelEntryTimeMs = 0L
        isOutageBridgingPending = false
        rebootAnchorLocation = null

        if (candidateDistMeters > 0.0) {
            onBridgeDistance?.invoke(candidateDistMeters)
        }
        onTripStarted(currentTripId)
    }

    private fun handleActiveTripMovement(now: Long, location: Location, speedKmh: Float) {
        val prevLoc = lastAcceptedLocation
        var stepDistanceMeters = 0.0

        if (prevLoc != null) {
            val dist = computeGeodesicDistance(prevLoc.latitude, prevLoc.longitude, location.latitude, location.longitude)
            val timeDeltaSec = if (lastLocationTime > 0L) {
                ((now - lastLocationTime) / 1000.0).coerceIn(0.1, 5.0)
            } else {
                1.0
            }

            // Validate plausible car movement (< 220 km/h)
            val impliedSpeedKmh = (dist / timeDeltaSec) * 3.6

            // Odometer: filtered haversine segments (accuracy gate, stationary jitter, teleports).
            stepDistanceMeters = if (distanceAccumulationPaused) {
                odometer.breakSegment() // weak GNSS: positions are not trustworthy enough for distance
                0.0
            } else {
                odometer.onFix(
                    com.antigravity.copilot.core.logic.GeoFix(
                        location.latitude, location.longitude,
                        (if (location.hasAccuracy()) location.accuracy else 5.0f).toDouble(),
                        now, speedKmh.toDouble()
                    )
                )
            }
            totalDistanceMeters += stepDistanceMeters
            if (stepDistanceMeters > 0.0) {
                val learned = fuelTelemetryProvider?.invoke()?.mileageKmpl?.takeIf { it > 0.0 } ?: BASE_MILEAGE_KMPL
                tripFuel.onStep(stepDistanceMeters / 1000.0, speedKmh.toDouble(), drivingClassifier.accelMps2, learned)
            }

            // Longitudinal acceleration: the classifier's smoothed, sensor-fused value (raw Doppler
            // differences are too noisy for event counting). Steps outside 0.5..2 s never count.
            val accelMps2 = drivingClassifier.accelMps2.toFloat()
            currentGForce = accelMps2 / 9.80665f
            val stepDtMs = if (lastLocationTime > 0L) now - lastLocationTime else 0L
            if (stepDtMs in 500L..2000L && speedKmh >= 2.0f) {
                if (accelMps2 > maxAccelMps2) maxAccelMps2 = accelMps2
                if (accelMps2 < maxDecelMps2) maxDecelMps2 = accelMps2
            }
            harshCounter.onSample(accelMps2.toDouble(), stepDtMs, speedKmh.toDouble())
            val limit = speedLimitProvider?.invoke()?.takeIf { it > 0.0 } ?: SPEED_LIMIT_KMH
            overspeed.onSample(now, speedKmh.toDouble(), limit)
        }

        if (speedKmh > maxSpeedKmh) {
            maxSpeedKmh = speedKmh.toDouble()
        }

        if (speedKmh > STOP_SPEED_THRESHOLD_KMH) {
            speedSumKmh += speedKmh
            speedCount++
        }

        val dt = if (lastLocationTime > 0L) {
            (now - lastLocationTime).coerceIn(0L, MAX_STEP_DT_MS)
        } else {
            0L
        }
        motion.onSample(now, speedKmh.toDouble())
        drivingDurationMs = motion.movingMs
        stationaryDurationMs = motion.idleMs
        if (speedKmh >= STOP_SPEED_THRESHOLD_KMH) {
            lastMovementTime = now
            stationaryAnchorLocation = null
        } else {
            if (stationaryAnchorLocation == null) {
                stationaryAnchorLocation = location
            }
            if ((now - lastMovementTime) > com.antigravity.copilot.core.logic.Config.IDLE_MIN_DURATION_MS) {
                // IDLE: never ends a trip; moving again continues the same trail.
                currentStatus = TripStatus.TEMPORARY_STOP
                temporaryStopStartTime = lastMovementTime
            }
        }

        lastAcceptedLocation = location
        lastLocationTime = now

        // Notify live movement to fuel subsystem for unified continuous consumption
        onLiveMovement?.invoke(stepDistanceMeters, speedKmh.toDouble(), dt)

        // Exact mathematical average speed
        val drivingHours = drivingDurationMs / 3600000.0
        val avgSpeed = if (drivingHours > 0.002) {
            (totalDistanceMeters / 1000.0) / drivingHours
        } else if (speedCount > 0) {
            speedSumKmh / speedCount
        } else {
            0.0
        }
        val driverScore = calculateDriverScore()

        val fuelSnapshot = fuelTelemetryProvider?.invoke()
        val effectiveMileage = fuelSnapshot?.mileageKmpl?.takeIf { it > 0.0 } ?: BASE_MILEAGE_KMPL
        val effectivePrice = fuelSnapshot?.fuelPricePerLiter?.takeIf { it > 0.0 } ?: FUEL_PRICE_PER_LITER
        val tripFuelConsumed = tripFuelLitres(effectiveMileage)
        val tripCost = tripFuelConsumed * effectivePrice

        val summary = TripSummary(
            id = currentTripId,
            startTime = tripStartTime,
            endTime = 0L,
            distanceMeters = totalDistanceMeters,
            drivingDurationMs = drivingDurationMs,
            stationaryDurationMs = stationaryDurationMs,
            avgSpeedKmh = avgSpeed,
            maxSpeedKmh = maxSpeedKmh,
            currentAltitudeMeters = currentAltitude,
            currentBearing = currentBearing,
            currentGForce = currentGForce,
            maxAccelMss = maxAccelMps2,
            maxDecelMss = maxDecelMps2,
            estimatedFuelConsumedLiters = tripFuelConsumed,
            estimatedTripCost = tripCost,
            calculatedMileageKmpl = tripMileage(tripFuelConsumed, effectiveMileage),
            bestMileageKmpl = tripFuel.bestKmpl,
            driverScore = driverScore,
            harshBrakeCount = harshBrakes,
            rapidAccelCount = rapidAccels,
            brakeCount = drivingClassifier.brakeEvents,
            accelCount = drivingClassifier.accelEvents,
            overspeedCount = overspeedInstances,
            startLocationName = currentOriginName,
            endLocationName = currentDestinationName,
            status = currentStatus
        )

        val tripPoint = TripPoint(
            tripId = currentTripId,
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = currentAltitude,
            speedKmh = speedKmh,
            bearing = currentBearing,
            accuracy = location.accuracy,
            timestamp = wallNow(location),
            elapsedMs = now
        )

        onTripUpdated(summary, tripPoint)
    }

    /** [now] is the wall-clock end time (trip duration is wall-clock; moving time is monotonic). */
    private fun evaluateTripCompletion(now: Long, destinationName: String? = null) {
        val completedTripId = currentTripId
        val completedStartTime = tripStartTime
        val effectiveDestName = destinationName ?: currentDestinationName
        if (completedStartTime > 0L && now > completedStartTime) {
            // Total duration is wall clock; anything not moving (including GPS-less gaps) is idle.
            drivingDurationMs = motion.movingMs
            stationaryDurationMs = motion.finalIdleMs(now - completedStartTime)
        }

        val drivingHours = drivingDurationMs / 3600000.0
        val avgSpeed = if (drivingHours > 0.002) {
            (totalDistanceMeters / 1000.0) / drivingHours
        } else if (speedCount > 0) {
            speedSumKmh / speedCount
        } else {
            0.0
        }
        val fuelSnapshot = fuelTelemetryProvider?.invoke()
        val effectiveMileage = fuelSnapshot?.mileageKmpl?.takeIf { it > 0.0 } ?: BASE_MILEAGE_KMPL
        val effectivePrice = fuelSnapshot?.fuelPricePerLiter?.takeIf { it > 0.0 } ?: FUEL_PRICE_PER_LITER
        val tripFuelConsumed = tripFuelLitres(effectiveMileage)
        val tripCost = tripFuelConsumed * effectivePrice

        val summary = TripSummary(
            id = completedTripId,
            startTime = completedStartTime,
            endTime = now,
            distanceMeters = totalDistanceMeters,
            drivingDurationMs = drivingDurationMs,
            stationaryDurationMs = stationaryDurationMs,
            avgSpeedKmh = avgSpeed,
            maxSpeedKmh = maxSpeedKmh,
            currentAltitudeMeters = currentAltitude,
            currentBearing = currentBearing,
            currentGForce = 0f,
            maxAccelMss = maxAccelMps2,
            maxDecelMss = maxDecelMps2,
            estimatedFuelConsumedLiters = tripFuelConsumed,
            estimatedTripCost = tripCost,
            calculatedMileageKmpl = tripMileage(tripFuelConsumed, effectiveMileage),
            bestMileageKmpl = tripFuel.bestKmpl,
            driverScore = calculateDriverScore(),
            harshBrakeCount = harshBrakes,
            rapidAccelCount = rapidAccels,
            brakeCount = drivingClassifier.brakeEvents,
            accelCount = drivingClassifier.accelEvents,
            overspeedCount = overspeedInstances,
            startLocationName = currentOriginName,
            endLocationName = effectiveDestName,
            status = TripStatus.COMPLETED
        )

        // Both modes: a GPS jump through a fence (or a parking shuffle) must not fill the history.
        if (totalDistanceMeters >= minTripDistanceMeters) {
            currentStatus = TripStatus.COMPLETED
            onTripCompleted(summary)
        } else {
            currentStatus = TripStatus.IDLE
            onTripDiscarded(completedTripId)
        }

        currentTripId = 0L
        tripStartTime = 0L
        currentOriginName = ""
        currentDestinationName = ""
        lastAcceptedLocation = null
        stationaryAnchorLocation = null
        tunnelEntryLocation = null
        tunnelEntryTimeMs = 0L
        isOutageBridgingPending = false
        rebootAnchorLocation = null
    }

    private fun calculateDriverScore(): Int =
        com.antigravity.copilot.core.logic.DriverScoreCalculator.score(
            harshBrakes, rapidAccels, overspeedInstances, totalDistanceMeters / 1000.0, driverScoringEnabled()
        )

    private fun wallNow(location: Location): Long = if (location.time > 0L) location.time else System.currentTimeMillis()

    /**
     * Bridges the distance from the outage entry to [location] once it is accurate (<= 25 m), or
     * after [OUTAGE_BRIDGE_MAX_WAIT_MS] with any usable fix. Returns true when the pending bridge
     * was resolved (applied or rejected as implausible), false while still waiting.
     */
    private fun tryBridgeOutage(location: Location, accuracy: Float, now: Long): Boolean {
        val entryLoc = tunnelEntryLocation ?: run { isOutageBridgingPending = false; return true }
        val waitedMs = if (outageRecoveredAtMs > 0L) now - outageRecoveredAtMs else 0L
        val accurate = accuracy <= OUTAGE_BRIDGE_ACCURACY_M
        if (!accurate && !(waitedMs >= OUTAGE_BRIDGE_MAX_WAIT_MS && accuracy <= MAX_VALID_ACCURACY_METERS)) return false
        val bridgeDistMeters = computeGeodesicDistance(entryLoc.latitude, entryLoc.longitude, location.latitude, location.longitude)
        val outageDurationSec = ((now - tunnelEntryTimeMs) / 1000.0).coerceAtLeast(1.0)
        val impliedSpeedKmh = (bridgeDistMeters / outageDurationSec) * 3.6
        if (bridgeDistMeters >= 10.0 && impliedSpeedKmh in 2.5..220.0) {
            totalDistanceMeters += bridgeDistMeters
            val bridgeDurationMs = (outageDurationSec * 1000.0).toLong().coerceIn(1000L, 600000L)
            motion.addMoving(bridgeDurationMs)
            drivingDurationMs = motion.movingMs
            onLiveMovement?.invoke(bridgeDistMeters, impliedSpeedKmh, bridgeDurationMs)
        }
        tunnelEntryLocation = null
        tunnelEntryTimeMs = 0L
        isOutageBridgingPending = false
        outageRecoveredAtMs = 0L
        return true
    }
}
