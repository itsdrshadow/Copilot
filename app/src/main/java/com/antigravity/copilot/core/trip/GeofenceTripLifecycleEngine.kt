package com.antigravity.copilot.core.trip

import android.location.Location
import com.antigravity.copilot.core.database.TripGeofenceEntity
import com.antigravity.copilot.core.model.TripPoint
import com.antigravity.copilot.core.logic.Config
import com.antigravity.copilot.core.logic.Fence
import com.antigravity.copilot.core.logic.FencePhase
import com.antigravity.copilot.core.logic.GeoFix
import com.antigravity.copilot.core.logic.GeofenceEvent
import com.antigravity.copilot.core.logic.GeofenceTripDetector
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
 * Decisions are delegated to the pure [GeofenceTripDetector] (see [Config] for thresholds):
 * 1. TRIP START: 3 consecutive fixes (accuracy < 30 m) beyond radius + 15 m of the parked fence.
 *    Moving inside a geofence NEVER starts a trip.
 * 2. TRIP END: 3 consecutive fixes inside ANY enabled geofence (radius) end the active trip.
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
    private val detector = GeofenceTripDetector()
    private var departureOriginGeofence: TripGeofenceEntity? = null
    private val departureConfirmedPoints = mutableListOf<TripPoint>()

    // Arrival Confirmation
    private var arrivalCandidateGeofence: TripGeofenceEntity? = null

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
        val MAX_ACCURACY_THRESHOLD_METERS = Config.GEOFENCE_MAX_ACCURACY_M.toFloat()
        val DEBOUNCE_HYSTERESIS_METERS = Config.GEOFENCE_EXIT_HYSTERESIS_M.toFloat()
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
        detector.restore(inTrip = true, currentFence = null)
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
        detector.reset()
        departureOriginGeofence = null
        departureConfirmedPoints.clear()
        arrivalCandidateGeofence = null
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

    /** Exposed for UI/diagnostics: true while the pure detector considers a trip active. */
    val isTripActive: Boolean get() = detector.inTrip

    private fun TripGeofenceEntity.toFence() = Fence(id, name, latitude, longitude, radiusMeters.toDouble())

    /** C5: fences change rarely; reuse the mapped list until the provider hands out a new one. */
    private var fenceCacheSource: List<TripGeofenceEntity>? = null
    private var fenceCache: List<Fence> = emptyList()

    private fun fencesFor(enabled: List<TripGeofenceEntity>): List<Fence> {
        if (enabled !== fenceCacheSource) {
            fenceCacheSource = enabled
            fenceCache = enabled.map { it.toFence() }
        }
        return fenceCache
    }

    private fun entityFor(fence: Fence?, all: List<TripGeofenceEntity>): TripGeofenceEntity? =
        fence?.let { f -> all.firstOrNull { it.id == f.id } }

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

        val isAccuracyReliable = accuracy < MAX_ACCURACY_THRESHOLD_METERS
        if (!isGpsStabilized && isAccuracyReliable) {
            if (firstAccurateFixTimeMs == 0L) firstAccurateFixTimeMs = now
            if (now - firstAccurateFixTimeMs >= 3000L) isGpsStabilized = true
        }

        val enabled = geofenceManager.enabledGeofences()
        val fences = fencesFor(enabled)
        val wasInTrip = detector.inTrip

        if (wasInTrip) {
            onTripActiveTelemetry(currentPoint)
        } else {
            // Buffer while parked (also before the fence lock is confirmed) so the trail can be
            // prepended from the true departure point.
            updatePreTripBuffer(currentPoint)
        }

        val event = detector.onFix(
            GeoFix(location.latitude, location.longitude, accuracy.toDouble(), now, speedKmh.toDouble()),
            fences
        )

        when (event) {
            is GeofenceEvent.TripStarted -> {
                val origin = entityFor(event.fence, enabled) ?: departureOriginGeofence
                departureConfirmedPoints.add(currentPoint)
                GeofenceDiagnostics.log(
                    eventType = "TRIP_START_CONFIRMED",
                    previousState = state.name,
                    currentState = TripLifecycleState.TRIP_ACTIVE.name,
                    geofenceId = origin?.id ?: 0L,
                    geofenceName = origin?.name ?: "",
                    radiusMeters = origin?.radiusMeters ?: 0f,
                    accuracy = accuracy,
                    reason = "${Config.GEOFENCE_DEBOUNCE_FIXES} consecutive fixes beyond radius+${Config.GEOFENCE_EXIT_HYSTERESIS_M.toInt()} m"
                )
                if (origin != null) {
                    confirmDeparture(event.timeMs, origin.name)
                } else if (event.fence == null && !event.manual) {
                    // C2: started from an unknown position (no parking fence known). The origin is
                    // the first buffered point; its coordinates stand in until it is reverse-geocoded.
                    val first = preTripBuffer.firstOrNull() ?: currentPoint
                    confirmDeparture(event.timeMs, TripNaming.coordinateName(first.latitude, first.longitude))
                }
                return
            }
            is GeofenceEvent.TripEnded -> {
                val dest = entityFor(event.fence, enabled) ?: return
                GeofenceDiagnostics.log(
                    eventType = "TRIP_END_CONFIRMED",
                    previousState = state.name,
                    currentState = TripLifecycleState.TRIP_COMPLETED.name,
                    geofenceId = dest.id,
                    geofenceName = dest.name,
                    radiusMeters = dest.radiusMeters,
                    accuracy = accuracy,
                    reason = "${Config.GEOFENCE_DEBOUNCE_FIXES} consecutive fixes inside ${dest.name}"
                )
                confirmArrival(event.timeMs, dest)
                return
            }
            null -> Unit
        }

        // Mirror detector phase into the legacy UI state enum.
        when (detector.phase) {
            FencePhase.UNKNOWN -> {
                currentGeofence = null
                transitionTo(TripLifecycleState.WAITING_FOR_GEOFENCE)
            }
            FencePhase.INSIDE_FENCE -> {
                currentGeofence = entityFor(detector.currentFence, enabled) ?: currentGeofence
                departureConfirmedPoints.clear()
                departureOriginGeofence = null
                transitionTo(TripLifecycleState.INSIDE_GEOFENCE)
            }
            FencePhase.EXIT_PENDING -> {
                departureOriginGeofence = entityFor(detector.currentFence, enabled) ?: currentGeofence
                departureConfirmedPoints.add(currentPoint)
                transitionTo(TripLifecycleState.DEPARTURE_PENDING)
            }
            FencePhase.IN_TRIP -> {
                arrivalCandidateGeofence = null
                transitionTo(TripLifecycleState.TRIP_ACTIVE)
            }
            FencePhase.ENTRY_PENDING -> {
                arrivalCandidateGeofence = entityFor(detector.pendingFence, enabled)
                transitionTo(TripLifecycleState.ARRIVAL_PENDING)
            }
        }
    }

    /** Manual override: start a trip now regardless of fences. */
    fun manualStartTrip(now: Long = System.currentTimeMillis(), lastPoint: TripPoint? = null) {
        val ev = detector.manualStart(now) as? GeofenceEvent.TripStarted ?: return
        val origin = currentGeofence
        val points = lastPoint?.let { listOf(it.copy(tripId = now, timestamp = now)) } ?: emptyList()
        activeTripStartTime = now
        activeTripId = now
        transitionTo(TripLifecycleState.TRIP_ACTIVE)
        onTripStartConfirmed(now, origin?.name ?: ev.fence?.name ?: "", points, 0.0)
        currentGeofence = null
        preTripBuffer.clear()
        departureAnchorPoint = null
        departureConfirmedPoints.clear()
        departureOriginGeofence = null
    }

    /** Manual override: end the active trip now (parks in a fence if the last fix is inside one). */
    fun manualEndTrip(now: Long = System.currentTimeMillis(), lastPoint: TripPoint? = null) {
        val enabled = geofenceManager.enabledGeofences()
        val fix = lastPoint?.let { GeoFix(it.latitude, it.longitude, it.accuracy.toDouble(), it.timestamp) }
        val ev = detector.manualEnd(now, fix, enabled.map { it.toFence() }) as? GeofenceEvent.TripEnded ?: return
        val dest = entityFor(ev.fence, enabled)
        transitionTo(TripLifecycleState.TRIP_COMPLETED)
        onTripArrivalConfirmed(now, dest?.name ?: "")
        activeTripId = 0L
        activeTripStartTime = 0L
        arrivalCandidateGeofence = null
        currentGeofence = dest
        preTripBuffer.clear()
        departureAnchorPoint = null
        stationaryStartTimeMs = 0L
        transitionTo(if (dest != null) TripLifecycleState.INSIDE_GEOFENCE else TripLifecycleState.WAITING_FOR_GEOFENCE)
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

    private fun confirmDeparture(now: Long, originName: String) {
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

        // Distance from the departure origin to the boundary, through the same odometer filter as
        // the trip itself (accuracy gate, stationary jitter, teleports), so parked GPS wander inside
        // the fence is not counted (C4).
        val departureOdometer = com.antigravity.copilot.core.logic.OdometerAccumulator()
        var departureDistanceMeters = 0.0
        for (p in finalDeparturePoints.sortedBy { it.timestamp }) {
            departureDistanceMeters += departureOdometer.onFix(
                GeoFix(p.latitude, p.longitude, p.accuracy.toDouble(), p.timestamp, p.speedKmh.toDouble())
            )
        }

        transitionTo(TripLifecycleState.TRIP_ACTIVE)

        onTripStartConfirmed(
            effectiveStartTime,
            originName,
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
            detector.manualEnd(now, GeoFix(destGf.latitude, destGf.longitude, 0.0, now), listOf(destGf.toFence()))
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
