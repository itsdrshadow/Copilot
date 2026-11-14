package com.antigravity.drivingreport.core.trip

import android.location.Location
import com.antigravity.drivingreport.core.database.TripGeofenceEntity
import com.antigravity.drivingreport.core.model.TripPoint
import com.antigravity.drivingreport.core.settings.AppSettingsManager
import java.util.ArrayDeque

enum class TripLifecycleState {
    WAITING_FOR_GEOFENCE,
    INSIDE_GEOFENCE,
    DEPARTURE_PENDING,
    TRIP_ACTIVE,
    MOVING,
    IDLE,
    ENGINE_OFF,
    UNKNOWN,
    ARRIVAL_PENDING,
    TRIP_COMPLETED
}

/**
 * Pure Geofence-Based Automatic Trip Lifecycle Engine.
 *
 * Strict Rules:
 * 1. TRIP START: Departure crossing beyond ANY enabled geofence boundary (radius + hysteresis, accuracy <= 25m)
 *    starts a new trip and starts the trip timer. Moving inside geofence NEVER starts a trip.
 * 2. TRIP END: Entry crossing inside ANY enabled geofence boundary ends the active trip immediately.
 *    Stopping outside geofences (traffic lights, parking) NEVER ends a trip.
 * 3. DISTANCE INDEPENDENCE: Telemetry, distance accumulation, speeds, and route trail continue
 *    independently throughout the journey via TripDetectionEngine.
 */
class GeofenceTripLifecycleEngine(
    private val geofenceManager: GeofenceProvider,
    private val onTripStartConfirmed: (startTime: Long, originGeofenceName: String, departurePoints: List<TripPoint>, departureDistanceMeters: Double) -> Unit,
    private val onTripActiveTelemetry: (point: TripPoint) -> Unit,
    private val onTripArrivalConfirmed: (endTime: Long, destinationGeofenceName: String) -> Unit,
    private val onStateChanged: (TripLifecycleState) -> Unit = {}
) {
    var state: TripLifecycleState = TripLifecycleState.WAITING_FOR_GEOFENCE
        private set

    var currentGeofence: TripGeofenceEntity? = null
        private set

    // Pre-trip rolling GPS buffer while INSIDE_GEOFENCE
    private val preTripBuffer = ArrayDeque<TripPoint>()
    private var departureAnchorPoint: TripPoint? = null
    private var stationaryStartTimeMs: Long = 0L

    // Departure Debounce (Anti-Jitter)
    private var departurePendingStartMs: Long = 0L
    private var departureOriginGeofence: TripGeofenceEntity? = null
    private val departureConfirmedPoints = mutableListOf<TripPoint>()

    // Arrival Confirmation
    private var arrivalCandidateGeofence: TripGeofenceEntity? = null
    private var arrivalFixesCount: Int = 0
    private var arrivalStationaryFixesCount: Int = 0

    // Active trip metadata
    var activeTripId: Long = 0L
        private set
    var activeTripStartTime: Long = 0L
        private set

    // 3-Second GPS Warmup & Stabilization
    var isGpsStabilized: Boolean = false
        internal set
    private var firstAccurateFixTimeMs: Long = 0L

    companion object {
        const val MAX_ACCURACY_THRESHOLD_METERS = 25.0f
        const val DEBOUNCE_HYSTERESIS_METERS = 15.0f
        const val MIN_DEPARTURE_SPEED_KMH = 3.0f
        const val MIN_STATIONARY_FIXES_FOR_ARRIVAL = 2
        const val MAX_PRE_TRIP_BUFFER_AGE_MS = 5 * 60 * 1000L // 5 minutes
        const val MAX_PRE_TRIP_BUFFER_SIZE = 360
    }

    /**
     * Restore an ongoing trip from database during startup recovery.
     */
    fun restoreActiveTrip(tripId: Long, startTime: Long) {
        activeTripId = tripId
        activeTripStartTime = startTime
        stationaryStartTimeMs = 0L
        GeofenceDiagnostics.log(
            eventType = "TRIP_RESTORED",
            previousState = state.name,
            currentState = TripLifecycleState.TRIP_ACTIVE.name,
            reason = "Restored active trip #$tripId from persisted storage"
        )
        transitionTo(TripLifecycleState.TRIP_ACTIVE)
    }

    fun reset() {
        state = TripLifecycleState.WAITING_FOR_GEOFENCE
        currentGeofence = null
        preTripBuffer.clear()
        departureAnchorPoint = null
        stationaryStartTimeMs = 0L
        departurePendingStartMs = 0L
        departureOriginGeofence = null
        departureConfirmedPoints.clear()
        arrivalCandidateGeofence = null
        arrivalFixesCount = 0
        arrivalStationaryFixesCount = 0
        activeTripId = 0L
        activeTripStartTime = 0L
        isGpsStabilized = false
        firstAccurateFixTimeMs = 0L
        GeofenceDiagnostics.log(
            eventType = "ENGINE_RESET",
            previousState = "",
            currentState = state.name,
            reason = "Factory reset or telemetry reinitialization"
        )
    }

    fun processLocation(location: Location) {
        val now = if (location.time > 0L) location.time else System.currentTimeMillis()
        val accuracy = if (location.hasAccuracy()) location.accuracy else 5.0f
        val speedKmh = if (location.hasSpeed()) location.speed * 3.6f else 0f

        val currentPoint = TripPoint(
            tripId = activeTripId,
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = if (location.hasAltitude()) location.altitude else 0.0,
            speedKmh = speedKmh,
            bearing = if (location.hasBearing()) location.bearing else 0f,
            accuracy = accuracy,
            timestamp = now
        )

        // Find which enabled geofence the vehicle is currently inside
        val containingGeofence = geofenceManager.findGeofenceContaining(location.latitude, location.longitude, onlyEnabled = true)

        // GPS Accuracy Gate for Geofence Boundary Transitions
        val isAccuracyReliable = accuracy <= MAX_ACCURACY_THRESHOLD_METERS

        if (!isGpsStabilized) {
            if (isAccuracyReliable) {
                if (firstAccurateFixTimeMs == 0L) {
                    firstAccurateFixTimeMs = now
                }
                if (now - firstAccurateFixTimeMs >= 3000L) {
                    isGpsStabilized = true
                }
            }
        }

        when (state) {
            TripLifecycleState.WAITING_FOR_GEOFENCE -> {
                if (containingGeofence != null && isAccuracyReliable) {
                    currentGeofence = containingGeofence
                    preTripBuffer.clear()
                    departureAnchorPoint = null
                    GeofenceDiagnostics.log(
                        eventType = "GEOFENCE_LOCKED",
                        previousState = state.name,
                        currentState = TripLifecycleState.INSIDE_GEOFENCE.name,
                        geofenceId = containingGeofence.id,
                        geofenceName = containingGeofence.name,
                        radiusMeters = containingGeofence.radiusMeters,
                        accuracy = accuracy,
                        reason = "Vehicle located inside enabled geofence ${containingGeofence.name}"
                    )
                    transitionTo(TripLifecycleState.INSIDE_GEOFENCE)
                }
            }

            TripLifecycleState.INSIDE_GEOFENCE -> {
                val gf = currentGeofence ?: containingGeofence
                if (gf != null) {
                    currentGeofence = gf
                    val distToCenter = geofenceManager.calculateDistance(gf, location.latitude, location.longitude)

                    // Maintain Rolling Pre-Trip Buffer inside geofence
                    updatePreTripBuffer(currentPoint)

                    // Check for Departure boundary crossing (only after 3s GPS stabilization)
                    if (distToCenter > gf.radiusMeters) {
                        if (!isGpsStabilized) {
                            return
                        }
                        if (!isAccuracyReliable) {
                            GeofenceDiagnostics.log(
                                eventType = "DEPARTURE_REJECTED",
                                previousState = state.name,
                                currentState = state.name,
                                geofenceId = gf.id,
                                geofenceName = gf.name,
                                radiusMeters = gf.radiusMeters,
                                distanceMeters = distToCenter,
                                accuracy = accuracy,
                                reason = "GPS accuracy (${accuracy}m) exceeds 25m threshold during boundary crossing"
                            )
                            return
                        }

                        departureOriginGeofence = gf
                        departurePendingStartMs = now
                        departureConfirmedPoints.clear()
                        departureConfirmedPoints.add(currentPoint)

                        GeofenceDiagnostics.log(
                            eventType = "DEPARTURE_PENDING",
                            previousState = state.name,
                            currentState = TripLifecycleState.DEPARTURE_PENDING.name,
                            geofenceId = gf.id,
                            geofenceName = gf.name,
                            radiusMeters = gf.radiusMeters,
                            distanceMeters = distToCenter,
                            accuracy = accuracy,
                            reason = "Vehicle crossed radius boundary outward (${distToCenter}m > ${gf.radiusMeters}m)"
                        )
                        transitionTo(TripLifecycleState.DEPARTURE_PENDING)
                    }
                } else {
                    // Vehicle is outside geofences without active trip
                    transitionTo(TripLifecycleState.WAITING_FOR_GEOFENCE)
                }
            }

            TripLifecycleState.DEPARTURE_PENDING -> {
                val originGf = departureOriginGeofence
                if (originGf == null) {
                    transitionTo(TripLifecycleState.WAITING_FOR_GEOFENCE)
                    return
                }

                val distToCenter = geofenceManager.calculateDistance(originGf, location.latitude, location.longitude)
                departureConfirmedPoints.add(currentPoint)

                // Check for False Exit (Vehicle turned back inside the geofence)
                if (distToCenter <= originGf.radiusMeters) {
                    GeofenceDiagnostics.log(
                        eventType = "DEPARTURE_CANCELLED",
                        previousState = state.name,
                        currentState = TripLifecycleState.INSIDE_GEOFENCE.name,
                        geofenceId = originGf.id,
                        geofenceName = originGf.name,
                        radiusMeters = originGf.radiusMeters,
                        distanceMeters = distToCenter,
                        accuracy = accuracy,
                        reason = "Vehicle returned inside geofence boundary (${distToCenter}m <= ${originGf.radiusMeters}m)"
                    )
                    currentGeofence = originGf
                    departurePendingStartMs = 0L
                    departureOriginGeofence = null
                    departureConfirmedPoints.clear()
                    transitionTo(TripLifecycleState.INSIDE_GEOFENCE)
                    return
                }

                // Check for Confirmed Departure:
                // Sustained movement outward (> radius + 15m) OR 2 consecutive fixes moving outward
                val isSustainedExit = distToCenter >= (originGf.radiusMeters + DEBOUNCE_HYSTERESIS_METERS)
                val isMovingOutward = speedKmh >= MIN_DEPARTURE_SPEED_KMH || departureConfirmedPoints.size >= 2

                if (isSustainedExit || isMovingOutward) {
                    GeofenceDiagnostics.log(
                        eventType = "TRIP_START_CONFIRMED",
                        previousState = state.name,
                        currentState = TripLifecycleState.TRIP_ACTIVE.name,
                        geofenceId = originGf.id,
                        geofenceName = originGf.name,
                        radiusMeters = originGf.radiusMeters,
                        distanceMeters = distToCenter,
                        accuracy = accuracy,
                        reason = "Confirmed exit from geofence ${originGf.name}. Starting new active trip."
                    )
                    confirmDeparture(now, originGf)
                }
            }

            TripLifecycleState.TRIP_ACTIVE,
            TripLifecycleState.MOVING,
            TripLifecycleState.IDLE,
            TripLifecycleState.ENGINE_OFF,
            TripLifecycleState.UNKNOWN -> {
                // Pass continuous telemetry to distance and metrics calculation
                onTripActiveTelemetry(currentPoint)

                // Check if vehicle has entered an enabled geofence (potential arrival)
                if (containingGeofence != null && isAccuracyReliable) {
                    val distToCenter = geofenceManager.calculateDistance(containingGeofence, location.latitude, location.longitude)
                    arrivalCandidateGeofence = containingGeofence
                    arrivalFixesCount = 1

                    val isDeepPenetration = distToCenter <= (containingGeofence.radiusMeters - 5.0f)
                    if (isDeepPenetration) {
                        GeofenceDiagnostics.log(
                            eventType = "TRIP_END_CONFIRMED",
                            previousState = state.name,
                            currentState = TripLifecycleState.TRIP_COMPLETED.name,
                            geofenceId = containingGeofence.id,
                            geofenceName = containingGeofence.name,
                            radiusMeters = containingGeofence.radiusMeters,
                            distanceMeters = distToCenter,
                            accuracy = accuracy,
                            reason = "Immediate arrival confirmed: Vehicle entered ${containingGeofence.name} boundary (${distToCenter}m <= ${containingGeofence.radiusMeters}m). Ending active trip."
                        )
                        confirmArrival(now, containingGeofence)
                    } else {
                        GeofenceDiagnostics.log(
                            eventType = "ARRIVAL_PENDING",
                            previousState = state.name,
                            currentState = TripLifecycleState.ARRIVAL_PENDING.name,
                            geofenceId = containingGeofence.id,
                            geofenceName = containingGeofence.name,
                            radiusMeters = containingGeofence.radiusMeters,
                            distanceMeters = distToCenter,
                            accuracy = accuracy,
                            reason = "Vehicle entered edge boundary of ${containingGeofence.name} (${distToCenter}m <= ${containingGeofence.radiusMeters}m). Verifying entry."
                        )
                        transitionTo(TripLifecycleState.ARRIVAL_PENDING)
                    }
                }
            }

            TripLifecycleState.ARRIVAL_PENDING -> {
                onTripActiveTelemetry(currentPoint)

                val destGf = arrivalCandidateGeofence
                if (destGf == null) {
                    transitionTo(TripLifecycleState.TRIP_ACTIVE)
                    return
                }

                val distToCenter = geofenceManager.calculateDistance(destGf, location.latitude, location.longitude)

                // Check if vehicle turned back outside the boundary (false arrival / drove straight through)
                if (distToCenter > destGf.radiusMeters) {
                    GeofenceDiagnostics.log(
                        eventType = "ARRIVAL_CANCELLED",
                        previousState = state.name,
                        currentState = TripLifecycleState.TRIP_ACTIVE.name,
                        geofenceId = destGf.id,
                        geofenceName = destGf.name,
                        radiusMeters = destGf.radiusMeters,
                        distanceMeters = distToCenter,
                        accuracy = accuracy,
                        reason = "Vehicle exited boundary of ${destGf.name} back to open road (${distToCenter}m > ${destGf.radiusMeters}m). Resuming active trip."
                    )
                    arrivalCandidateGeofence = null
                    arrivalFixesCount = 0
                    arrivalStationaryFixesCount = 0
                    transitionTo(TripLifecycleState.TRIP_ACTIVE)
                    return
                }

                arrivalFixesCount++
                val stationaryThreshold = AppSettingsManager.settingsState.value.stationarySpeedThresholdKmh
                val isStationary = speedKmh <= stationaryThreshold
                val isDeepPenetration = distToCenter <= (destGf.radiusMeters - 5.0f)

                if (isDeepPenetration || arrivalFixesCount >= 2 || isStationary) {
                    GeofenceDiagnostics.log(
                        eventType = "TRIP_END_CONFIRMED",
                        previousState = state.name,
                        currentState = TripLifecycleState.TRIP_COMPLETED.name,
                        geofenceId = destGf.id,
                        geofenceName = destGf.name,
                        radiusMeters = destGf.radiusMeters,
                        distanceMeters = distToCenter,
                        accuracy = accuracy,
                        reason = "Confirmed arrival inside geofence ${destGf.name} (fixes: $arrivalFixesCount, dist: ${distToCenter}m, stationary: $isStationary). Ending active trip."
                    )
                    confirmArrival(now, destGf)
                }
            }

            TripLifecycleState.TRIP_COMPLETED -> {
                if (containingGeofence != null) {
                    currentGeofence = containingGeofence
                    preTripBuffer.clear()
                    departureAnchorPoint = null
                    transitionTo(TripLifecycleState.INSIDE_GEOFENCE)
                } else {
                    transitionTo(TripLifecycleState.WAITING_FOR_GEOFENCE)
                }
            }
        }
    }

    private fun updatePreTripBuffer(point: TripPoint) {
        val now = point.timestamp

        // Prune stale points older than 5 minutes
        while (preTripBuffer.isNotEmpty() && (now - preTripBuffer.first.timestamp) > MAX_PRE_TRIP_BUFFER_AGE_MS) {
            preTripBuffer.removeFirst()
        }

        // Reset departure anchor only if vehicle came to continuous standstill for > 15s inside geofence
        if (point.speedKmh < 1.0f) {
            if (stationaryStartTimeMs == 0L) {
                stationaryStartTimeMs = now
            } else if ((now - stationaryStartTimeMs) > 15000L) {
                departureAnchorPoint = null
                // Prune previous movement points prior to this standstill
                while (preTripBuffer.isNotEmpty() && preTripBuffer.first.timestamp < stationaryStartTimeMs) {
                    preTripBuffer.removeFirst()
                }
            }
        } else {
            stationaryStartTimeMs = 0L
        }

        // Prune stale departure anchor if older than 5 minutes
        if (departureAnchorPoint != null && (now - departureAnchorPoint!!.timestamp) > MAX_PRE_TRIP_BUFFER_AGE_MS) {
            departureAnchorPoint = null
            stationaryStartTimeMs = 0L
        }

        // Noise filter: If speed is 0 and vehicle has not moved > 1.5m from previous point, don't duplicate
        if (preTripBuffer.isNotEmpty()) {
            val last = preTripBuffer.last
            val dist = TripDetectionEngine.computeGeodesicDistance(last.latitude, last.longitude, point.latitude, point.longitude)
            if (point.speedKmh < 1.0f && dist < 1.5) {
                return
            }
        }

        // Detect Departure Anchor: First point where vehicle initiates motion
        val isMoving = point.speedKmh >= 2.0f || (preTripBuffer.isNotEmpty() && run {
            val dist = TripDetectionEngine.computeGeodesicDistance(preTripBuffer.last.latitude, preTripBuffer.last.longitude, point.latitude, point.longitude)
            dist >= 2.5
        })

        if (isMoving && departureAnchorPoint == null) {
            // Anchor to the resting origin point right before movement initiated, if available
            departureAnchorPoint = preTripBuffer.lastOrNull() ?: point
        }

        preTripBuffer.addLast(point)
        if (preTripBuffer.size > MAX_PRE_TRIP_BUFFER_SIZE) {
            preTripBuffer.removeFirst()
        }
    }

    private fun confirmDeparture(now: Long, originGf: TripGeofenceEntity) {
        val departurePoints = mutableListOf<TripPoint>()

        val anchorTime = departureAnchorPoint?.timestamp ?: preTripBuffer.firstOrNull()?.timestamp ?: (now - 30000L)
        val windowStartTime = (anchorTime - 5000L).coerceAtLeast(0L)
        // Extract all pre-trip points from the departure anchor point onwards (including 5s buffer before movement)
        for (pt in preTripBuffer) {
            if (pt.timestamp >= windowStartTime) {
                if (departurePoints.none { it.latitude == pt.latitude && it.longitude == pt.longitude && it.timestamp == pt.timestamp }) {
                    departurePoints.add(pt)
                }
            }
        }
        // Ensure anchor point itself is definitely included at origin if available
        departureAnchorPoint?.let { anchor ->
            if (departurePoints.none { it.latitude == anchor.latitude && it.longitude == anchor.longitude }) {
                departurePoints.add(0, anchor)
            }
        }
        // Fallback: If buffer had points but anchorTime filter was empty, ensure buffer points are retained
        if (departurePoints.isEmpty() && preTripBuffer.isNotEmpty()) {
            departurePoints.addAll(preTripBuffer)
        }
        // Append debounced points outside boundary (without duplicating points already present)
        for (pt in departureConfirmedPoints) {
            if (departurePoints.none { it.latitude == pt.latitude && it.longitude == pt.longitude && it.timestamp == pt.timestamp }) {
                departurePoints.add(pt)
            }
        }

        val effectiveStartTime = departurePoints.firstOrNull()?.timestamp ?: now
        activeTripStartTime = effectiveStartTime
        activeTripId = effectiveStartTime

        // Ensure all departure trail points are stamped with the active tripId
        val finalDeparturePoints = departurePoints.map {
            if (it.tripId == effectiveStartTime) it else it.copy(tripId = effectiveStartTime)
        }

        // Calculate total distance traveled inside the geofence from departure origin to boundary
        var departureDistanceMeters = 0.0
        for (i in 0 until (finalDeparturePoints.size - 1)) {
            val p1 = finalDeparturePoints[i]
            val p2 = finalDeparturePoints[i + 1]
            departureDistanceMeters += TripDetectionEngine.computeGeodesicDistance(p1.latitude, p1.longitude, p2.latitude, p2.longitude)
        }

        transitionTo(TripLifecycleState.TRIP_ACTIVE)

        onTripStartConfirmed(
            effectiveStartTime,
            originGf.name,
            finalDeparturePoints,
            departureDistanceMeters
        )

        currentGeofence = null
        preTripBuffer.clear()
        departureAnchorPoint = null
        stationaryStartTimeMs = 0L
        departureConfirmedPoints.clear()
        departureOriginGeofence = null
    }

    private fun confirmArrival(now: Long, destGf: TripGeofenceEntity) {
        transitionTo(TripLifecycleState.TRIP_COMPLETED)
        onTripArrivalConfirmed(now, destGf.name)

        activeTripId = 0L
        activeTripStartTime = 0L
        arrivalCandidateGeofence = null
        arrivalFixesCount = 0
        arrivalStationaryFixesCount = 0
        currentGeofence = destGf
        preTripBuffer.clear()
        departureAnchorPoint = null
        stationaryStartTimeMs = 0L

        transitionTo(TripLifecycleState.INSIDE_GEOFENCE)
    }

    /**
     * Hardware ignition off / power disconnection handler.
     * If the vehicle has entered a geofence and is awaiting motion cessation,
     * turning off vehicle power conclusively completes the trip immediately.
     */
    fun onPowerDisconnected(now: Long = System.currentTimeMillis()) {
        if (state == TripLifecycleState.ARRIVAL_PENDING && arrivalCandidateGeofence != null) {
            val destGf = arrivalCandidateGeofence!!
            GeofenceDiagnostics.log(
                eventType = "TRIP_END_CONFIRMED",
                previousState = state.name,
                currentState = TripLifecycleState.TRIP_COMPLETED.name,
                geofenceId = destGf.id,
                geofenceName = destGf.name,
                radiusMeters = destGf.radiusMeters,
                distanceMeters = 0.0f,
                accuracy = 0.0f,
                reason = "Hardware power disconnected / engine off inside geofence ${destGf.name}. Ending active trip immediately."
            )
            confirmArrival(now, destGf)
        }
    }

    private fun transitionTo(newState: TripLifecycleState) {
        if (state == newState) return
        state = newState
        onStateChanged(newState)
    }
}
