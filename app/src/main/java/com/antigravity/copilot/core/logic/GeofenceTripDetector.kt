package com.antigravity.copilot.core.logic

/** A user-defined circular fence. */
data class Fence(
    val id: Long,
    val name: String,
    val lat: Double,
    val lon: Double,
    val radiusM: Double = Config.GEOFENCE_DEFAULT_RADIUS_M
)

/** A single GPS fix as seen by the pure logic. */
data class GeoFix(
    val lat: Double,
    val lon: Double,
    val accuracyM: Double,
    val timeMs: Long,
    val speedKmh: Double = 0.0
)

sealed class GeofenceEvent {
    abstract val timeMs: Long

    /** [fence] is the fence that was left, or null for a manual start. */
    data class TripStarted(val fence: Fence?, override val timeMs: Long, val manual: Boolean = false) : GeofenceEvent()

    /** [fence] is the fence that was entered, or null for a manual end outside any fence. */
    data class TripEnded(val fence: Fence?, override val timeMs: Long, val manual: Boolean = false) : GeofenceEvent()
}

enum class FencePhase { UNKNOWN, INSIDE_FENCE, EXIT_PENDING, IN_TRIP, ENTRY_PENDING }

/**
 * Pure geofence trip state machine (no Android, no GeofencingClient).
 *
 * - Exit from the current fence is counted only beyond `radius + hysteresis`; entry at `radius`.
 * - Fixes with accuracy >= [Config.GEOFENCE_MAX_ACCURACY_M] are ignored (neither count nor reset).
 * - Both transitions need [Config.GEOFENCE_DEBOUNCE_FIXES] consecutive qualifying fixes.
 * - Leaving ANY fence starts a trip; entering ANY fence (including the origin) ends it.
 * - Idle / stopping never ends a trip — only fences or a manual override do.
 * The event time is the time of the first fix of the confirming run.
 */
class GeofenceTripDetector(
    private val debounceFixes: Int = Config.GEOFENCE_DEBOUNCE_FIXES,
    private val maxAccuracyM: Double = Config.GEOFENCE_MAX_ACCURACY_M,
    private val exitHysteresisM: Double = Config.GEOFENCE_EXIT_HYSTERESIS_M
) {
    var inTrip: Boolean = false
        private set

    /** The fence the vehicle is parked in while not on a trip (null = unknown / outside all). */
    var currentFence: Fence? = null
        private set

    private var runCount = 0
    private var runFirstTimeMs = 0L
    private var runFence: Fence? = null

    /** Fence currently being counted towards an exit/entry confirmation. */
    val pendingFence: Fence? get() = if (runCount > 0) runFence else null

    val phase: FencePhase
        get() = when {
            inTrip && runCount > 0 -> FencePhase.ENTRY_PENDING
            inTrip -> FencePhase.IN_TRIP
            currentFence != null && runCount > 0 -> FencePhase.EXIT_PENDING
            currentFence != null -> FencePhase.INSIDE_FENCE
            else -> FencePhase.UNKNOWN
        }

    /** Restore after process death. */
    fun restore(inTrip: Boolean, currentFence: Fence?) {
        this.inTrip = inTrip
        this.currentFence = if (inTrip) null else currentFence
        resetRun()
    }

    fun reset() = restore(false, null)

    fun manualStart(timeMs: Long): GeofenceEvent? {
        if (inTrip) return null
        val from = currentFence
        inTrip = true
        currentFence = null
        resetRun()
        return GeofenceEvent.TripStarted(from, timeMs, manual = true)
    }

    /** Manual end. [fences] lets the detector know if we are parked inside a fence right now. */
    fun manualEnd(timeMs: Long, lastFix: GeoFix? = null, fences: List<Fence> = emptyList()): GeofenceEvent? {
        if (!inTrip) return null
        inTrip = false
        currentFence = lastFix?.let { fix -> nearestContaining(fix, fences, 0.0) }
        resetRun()
        return GeofenceEvent.TripEnded(currentFence, timeMs, manual = true)
    }

    fun onFix(fix: GeoFix, fences: List<Fence>): GeofenceEvent? {
        if (fix.accuracyM >= maxAccuracyM || fix.accuracyM.isNaN()) return null
        if (fences.isEmpty()) {
            resetRun()
            return null
        }
        return if (inTrip) handleInTrip(fix, fences) else handleParked(fix, fences)
    }

    private fun handleParked(fix: GeoFix, fences: List<Fence>): GeofenceEvent? {
        val parkedIn = currentFence?.let { cf -> fences.firstOrNull { it.id == cf.id } ?: cf }
        if (parkedIn == null) {
            // Unknown position (e.g. fresh install / after manual end outside): lock onto a fence once
            // we are confidently inside one. Never start a trip from an unknown position.
            val inside = nearestContaining(fix, fences, 0.0)
            if (inside == null) {
                resetRun()
                return null
            }
            if (advanceRun(inside, fix)) {
                currentFence = inside
                resetRun()
            }
            return null
        }
        currentFence = parkedIn
        // Moving into an overlapping fence just re-parks us there.
        val dist = GeoMath.haversineM(fix.lat, fix.lon, parkedIn.lat, parkedIn.lon)
        if (dist <= parkedIn.radiusM + exitHysteresisM) {
            resetRun()
            return null
        }
        val other = nearestContaining(fix, fences, 0.0)
        if (other != null) {
            currentFence = other
            resetRun()
            return null
        }
        if (advanceRun(parkedIn, fix)) {
            val start = runFirstTimeMs
            inTrip = true
            currentFence = null
            resetRun()
            return GeofenceEvent.TripStarted(parkedIn, start)
        }
        return null
    }

    private fun handleInTrip(fix: GeoFix, fences: List<Fence>): GeofenceEvent? {
        val inside = nearestContaining(fix, fences, 0.0)
        if (inside == null) {
            resetRun()
            return null
        }
        if (advanceRun(inside, fix)) {
            val end = runFirstTimeMs
            inTrip = false
            currentFence = inside
            resetRun()
            return GeofenceEvent.TripEnded(inside, end)
        }
        return null
    }

    /** Returns true when the run for [fence] reached the debounce count. */
    private fun advanceRun(fence: Fence, fix: GeoFix): Boolean {
        if (runFence?.id != fence.id) {
            runFence = fence
            runCount = 0
        }
        if (runCount == 0) runFirstTimeMs = fix.timeMs
        runCount++
        return runCount >= debounceFixes
    }

    private fun resetRun() {
        runCount = 0
        runFence = null
        runFirstTimeMs = 0L
    }

    companion object {
        /** Fence whose centre is closest among those containing the fix (radius + extra). */
        fun nearestContaining(fix: GeoFix, fences: List<Fence>, extraM: Double): Fence? {
            var best: Fence? = null
            var bestDist = Double.MAX_VALUE
            for (f in fences) {
                val d = GeoMath.haversineM(fix.lat, fix.lon, f.lat, f.lon)
                if (d <= f.radiusM + extraM && d < bestDist) {
                    best = f
                    bestDist = d
                }
            }
            return best
        }
    }
}
