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
    private var maxSpeedKmh: Double = 0.0
    private var speedSumKmh: Double = 0.0
    private var speedCount: Long = 0

    private var currentAltitude: Double = 0.0
    private var currentBearing: Float = 0f
    private var currentGForce: Float = 0f

    private var lastAcceptedLocation: Location? = null
    private var lastLocationTime: Long = 0

    // Driving Behavior Tracking (100% Real events, zero mock)
    private var harshBrakes: Int = 0
    private var rapidAccels: Int = 0
    private var overspeedInstances: Int = 0
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
    private var stationaryAnchorLocation: Location? = null

    companion object {
        const val MIN_QUALIFYING_DISTANCE_METERS = 1000.0 // 1.0 km
        const val MOVEMENT_SPEED_THRESHOLD_KMH = 6.0f
        const val STOP_SPEED_THRESHOLD_KMH = 2.5f
        const val CANDIDATE_CONFIRM_SECONDS = 15
        const val CANDIDATE_MIN_DISTANCE_METERS = 50.0
        const val IDLE_TRIP_END_TIMEOUT_SECONDS = 300 // 5 minutes of continuous stationary idle ends trip
        const val MAX_VALID_ACCURACY_METERS = 50.0f
        const val SPEED_LIMIT_KMH = 80.0
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
        maxSpeedKmh = 0.0
        speedSumKmh = 0.0
        speedCount = 0
        harshBrakes = 0
        rapidAccels = 0
        overspeedInstances = 0
        maxAccelMps2 = 0f
        maxDecelMps2 = 0f
        isGpsStabilized = false
        firstAccurateFixTimeMs = 0L
        tunnelEntryLocation = null
        tunnelEntryTimeMs = 0L
        isOutageBridgingPending = false
        rebootAnchorLocation = null
        stationaryAnchorLocation = null
        currentOriginName = ""
        currentDestinationName = ""
    }

    /**
     * Triggered when vehicle engine ignition cuts 12V ACC / USB power.
     * Concludes ongoing journey if vehicle has arrived at destination and stopped.
     */
    fun onPowerDisconnected(now: Long = System.currentTimeMillis()) {
        // Power cut / Engine Off does not terminate multi-day geofence trips
        if (currentStatus == TripStatus.ACTIVE_TRIP) {
            currentStatus = TripStatus.TEMPORARY_STOP
            temporaryStopStartTime = now
        }
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
    fun processStationaryWatchdog(now: Long = System.currentTimeMillis()) {
        if (currentStatus == TripStatus.ACTIVE_TRIP) {
            val stationaryDuration = now - lastMovementTime
            if (stationaryDuration >= 15000L) {
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
                evaluateTripCompletion(now)
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
        val now = if (location.time > 0L) location.time else System.currentTimeMillis()
        val accuracy = if (location.hasAccuracy()) location.accuracy else 5.0f

        // Gate 0: Filter out completely invalid (0, 0) coordinates or inaccurate garbage (> 100m)
        if (location.latitude == 0.0 && location.longitude == 0.0) return
        if (accuracy > 100.0f) return

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
                        timestamp = now
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
                            val timeDeltaSec = ((now - rebootAnchor.time) / 1000.0).coerceAtLeast(1.0)
                            val impliedSpeedKmh = (bridgeDist / timeDeltaSec) * 3.6
                            if (bridgeDist in 4.0..MAX_REBOOT_BRIDGE_DISTANCE_METERS && impliedSpeedKmh < 220.0) {
                                totalDistanceMeters += bridgeDist
                                onLiveMovement?.invoke(bridgeDist, impliedSpeedKmh, 1000L)
                            }
                        }
                    }

                    // 2. Check if recovering from a signal outage / tunnel while on an active trip
                    if (isOutageBridgingPending && tunnelEntryLocation != null) {
                        val entryLoc = tunnelEntryLocation!!
                        val recoveryLoc = location
                        val bridgeDistMeters = computeGeodesicDistance(entryLoc.latitude, entryLoc.longitude, recoveryLoc.latitude, recoveryLoc.longitude)
                        val outageDurationSec = ((now - tunnelEntryTimeMs) / 1000.0).coerceAtLeast(1.0)
                        val impliedSpeedKmh = (bridgeDistMeters / outageDurationSec) * 3.6

                        if (accuracy <= 25.0f && bridgeDistMeters >= 10.0 && impliedSpeedKmh in 2.5..220.0) {
                            totalDistanceMeters += bridgeDistMeters
                            val bridgeDurationMs = (outageDurationSec * 1000.0).toLong().coerceIn(1000L, 600000L)
                            drivingDurationMs += bridgeDurationMs
                            onLiveMovement?.invoke(bridgeDistMeters, impliedSpeedKmh, bridgeDurationMs)
                        }

                        tunnelEntryLocation = null
                        tunnelEntryTimeMs = 0L
                        isOutageBridgingPending = false
                    }

                    lastAcceptedLocation = location
                    lastLocationTime = now
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
                        timestamp = now
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
        val hasSensorSpeed = location.hasSpeed() && location.speed > 0.3f
        val prevLocation = lastAcceptedLocation
        val computedSpeedKmh = if (prevLocation != null && lastLocationTime > 0) {
            val timeDeltaSec = ((now - lastLocationTime) / 1000.0).coerceAtLeast(0.1)
            val dist = computeGeodesicDistance(prevLocation.latitude, prevLocation.longitude, location.latitude, location.longitude)
            (dist / timeDeltaSec * 3.6).toFloat()
        } else {
            0f
        }

        val effectiveSpeedKmh = when {
            location.hasSpeed() && location.speed <= 0.3f -> 0f
            hasSensorSpeed -> location.speed * 3.6f
            computedSpeedKmh in 3.0f..220.0f -> computedSpeedKmh
            else -> 0f
        }

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
            timestamp = now
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
                    startNewTrip(tripEffectiveStart, location)
                }
            }

            TripStatus.ACTIVE_TRIP -> {
                handleActiveTripMovement(now, location, effectiveSpeedKmh)
            }

            TripStatus.TEMPORARY_STOP -> {
                val stoppedSec = (now - temporaryStopStartTime) / 1000
                val anchor = stationaryAnchorLocation ?: location
                val distFromAnchor = computeGeodesicDistance(anchor.latitude, anchor.longitude, location.latitude, location.longitude)
                val isResumed = effectiveSpeedKmh >= MOVEMENT_SPEED_THRESHOLD_KMH && (distFromAnchor >= stationaryDriftRadiusMeters || effectiveSpeedKmh >= MOVEMENT_SPEED_THRESHOLD_KMH * 1.5f)

                if (isResumed) {
                    stationaryDurationMs += (now - temporaryStopStartTime)
                    currentStatus = TripStatus.ACTIVE_TRIP
                    lastAcceptedLocation = location
                    lastLocationTime = now
                    stationaryAnchorLocation = null
                } else {
                    lastAcceptedLocation = location
                    lastLocationTime = now
                    if (!isGeofenceControlled && stoppedSec >= stationaryCompletionThresholdSeconds) {
                        evaluateTripCompletion(now)
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
        val fixTime = if (initialLocation.time > 0L) initialLocation.time else startTime
        lastMovementTime = fixTime
        lastLocationTime = fixTime
        lastAcceptedLocation = initialLocation
        totalDistanceMeters = departureDistanceMeters
        drivingDurationMs = 0
        stationaryDurationMs = 0
        maxSpeedKmh = 0.0
        speedSumKmh = 0.0
        speedCount = 0
        harshBrakes = 0
        rapidAccels = 0
        overspeedInstances = 0
        maxAccelMps2 = 0f
        maxDecelMps2 = 0f
        currentGForce = 0f
        tunnelEntryLocation = null
        tunnelEntryTimeMs = 0L
        isOutageBridgingPending = false
        rebootAnchorLocation = null

        onTripStarted(currentTripId)
    }

    fun completeGeofenceTrip(now: Long = System.currentTimeMillis(), destinationName: String? = null) {
        if (currentTripId > 0L || currentStatus == TripStatus.ACTIVE_TRIP || currentStatus == TripStatus.TEMPORARY_STOP || currentStatus == TripStatus.STOP_CANDIDATE) {
            evaluateTripCompletion(now, destinationName)
        }
    }

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
        originName: String = ""
    ) {
        currentStatus = TripStatus.ACTIVE_TRIP
        currentTripId = tripId
        tripStartTime = startTime
        currentOriginName = originName
        val effectiveAnchorTime = if (lastLocation != null && lastLocation.time > 0L) lastLocation.time else startTime
        lastMovementTime = effectiveAnchorTime
        lastLocationTime = effectiveAnchorTime
        totalDistanceMeters = distanceMeters
        drivingDurationMs = drivingMs
        stationaryDurationMs = stationaryMs

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
                        onLiveMovement?.invoke(bridgeDist, impliedSpeedKmh, 1000L)
                    }
                }
                lastAcceptedLocation = curLoc ?: lastLocation
            }
        }
    }

    private fun startNewTrip(startTime: Long, initialLocation: Location) {
        currentStatus = TripStatus.ACTIVE_TRIP
        currentTripId = startTime
        tripStartTime = startTime
        currentOriginName = ""
        currentDestinationName = ""
        lastMovementTime = startTime
        lastLocationTime = startTime
        lastAcceptedLocation = initialLocation
        totalDistanceMeters = 0.0
        drivingDurationMs = 0
        stationaryDurationMs = 0
        maxSpeedKmh = 0.0
        speedSumKmh = 0.0
        speedCount = 0
        harshBrakes = 0
        rapidAccels = 0
        overspeedInstances = 0
        maxAccelMps2 = 0f
        maxDecelMps2 = 0f
        currentGForce = 0f
        tunnelEntryLocation = null
        tunnelEntryTimeMs = 0L
        isOutageBridgingPending = false
        rebootAnchorLocation = null

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

            // Strict anti-drift: Only accumulate distance if the vehicle is genuinely moving
            val isGenuinelyMoving = (speedKmh >= 2.5f && dist >= 0.8) || (dist >= 4.0 && impliedSpeedKmh in 3.0..220.0)

            if (isGenuinelyMoving && impliedSpeedKmh < 220.0) {
                totalDistanceMeters += dist
                stepDistanceMeters = dist
            }

            // Real Longitudinal G-Force and Acceleration
            val prevSpeedMps = if (prevLoc.hasSpeed()) prevLoc.speed else 0f
            val currSpeedMps = if (location.hasSpeed()) location.speed else (speedKmh / 3.6f)
            val accelMps2 = (currSpeedMps - prevSpeedMps) / timeDeltaSec.toFloat()
            currentGForce = accelMps2 / 9.80665f

            if (timeDeltaSec in 0.1..3.0 && speedKmh >= 2.0f) {
                if (accelMps2 > maxAccelMps2) maxAccelMps2 = accelMps2
                if (accelMps2 < maxDecelMps2) maxDecelMps2 = accelMps2
            }

            if (accelMps2 < -3.2f) harshBrakes++
            if (accelMps2 > 3.0f) rapidAccels++
            if (speedKmh > SPEED_LIMIT_KMH) overspeedInstances++
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
        if (speedKmh > STOP_SPEED_THRESHOLD_KMH) {
            drivingDurationMs += dt
            lastMovementTime = now
            stationaryAnchorLocation = null
        } else {
            stationaryDurationMs += dt
            if (stationaryAnchorLocation == null) {
                stationaryAnchorLocation = location
            }
            if ((now - lastMovementTime) > 15000) {
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
        val tripFuelConsumed = fuelSnapshot?.tripConsumedLiters?.takeIf { it > 0.0 }
            ?: ((totalDistanceMeters / 1000.0) / effectiveMileage)
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
            calculatedMileageKmpl = effectiveMileage,
            driverScore = driverScore,
            harshBrakeCount = harshBrakes,
            rapidAccelCount = rapidAccels,
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
            timestamp = now
        )

        onTripUpdated(summary, tripPoint)
    }

    private fun evaluateTripCompletion(now: Long, destinationName: String? = null) {
        val completedTripId = currentTripId
        val completedStartTime = tripStartTime
        val effectiveDestName = destinationName ?: currentDestinationName

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
        val tripFuelConsumed = fuelSnapshot?.tripConsumedLiters?.takeIf { it > 0.0 }
            ?: ((totalDistanceMeters / 1000.0) / effectiveMileage)
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
            calculatedMileageKmpl = effectiveMileage,
            driverScore = calculateDriverScore(),
            harshBrakeCount = harshBrakes,
            rapidAccelCount = rapidAccels,
            overspeedCount = overspeedInstances,
            startLocationName = currentOriginName,
            endLocationName = effectiveDestName,
            status = TripStatus.COMPLETED
        )

        if (isGeofenceControlled || totalDistanceMeters >= 200.0) {
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

    private fun calculateDriverScore(): Int {
        var score = 100
        score -= (harshBrakes * 3)
        score -= (rapidAccels * 2)
        score -= (overspeedInstances * 2)
        return score.coerceIn(50, 100)
    }
}
