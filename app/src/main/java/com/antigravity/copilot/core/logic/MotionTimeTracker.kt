package com.antigravity.copilot.core.logic

enum class MotionState { DRIVING, IDLE }

/**
 * Splits trip wall-clock time into moving and idle time.
 * IDLE = speed < [Config.IDLE_SPEED_KMH] continuously for more than [Config.IDLE_MIN_DURATION_MS];
 * the slow period that qualifies is re-attributed to idle retroactively. Short stops (traffic
 * lights shorter than the threshold) remain moving time. Idle never ends a trip.
 */
class MotionTimeTracker(
    private val idleSpeedKmh: Double = Config.IDLE_SPEED_KMH,
    private val idleMinMs: Long = Config.IDLE_MIN_DURATION_MS,
    private val maxStepMs: Long = Config.MAX_TIME_STEP_MS
) {
    var movingMs: Long = 0L
        private set
    var idleMs: Long = 0L
        private set
    var state: MotionState = MotionState.DRIVING
        private set

    private var lastTimeMs = NO_TIME
    private var slowSinceMs = NO_TIME
    /** Time accumulated in the current slow run that is still counted as moving. */
    private var pendingSlowMs = 0L

    fun restore(movingMs: Long, idleMs: Long) {
        this.movingMs = movingMs
        this.idleMs = idleMs
        lastTimeMs = NO_TIME
        slowSinceMs = NO_TIME
        pendingSlowMs = 0L
        state = MotionState.DRIVING
    }

    fun reset() = restore(0L, 0L)

    /** Time bridged without fixes (e.g. tunnel) that is known to be driving. */
    fun addMoving(ms: Long) {
        if (ms > 0) movingMs += ms
    }

    /** Next sample starts a fresh interval (power cut / deep sleep: the gap is not attributed). */
    fun markGap() {
        lastTimeMs = NO_TIME
    }

    /** Idle time for a finished trip: wall-clock time not spent moving (covers GPS-less gaps too). */
    fun finalIdleMs(wallClockMs: Long): Long = maxOf(idleMs, wallClockMs - movingMs).coerceAtLeast(0L)

    fun onSample(timeMs: Long, speedKmh: Double): MotionState {
        val dt = if (lastTimeMs == NO_TIME) 0L else (timeMs - lastTimeMs).coerceIn(0L, maxStepMs)
        lastTimeMs = timeMs
        if (speedKmh < idleSpeedKmh) {
            if (slowSinceMs == NO_TIME) slowSinceMs = timeMs
            if (state == MotionState.IDLE) {
                idleMs += dt
            } else {
                movingMs += dt
                pendingSlowMs += dt
                if (timeMs - slowSinceMs > idleMinMs) {
                    // Re-attribute the whole slow run to idle.
                    movingMs -= pendingSlowMs
                    idleMs += pendingSlowMs
                    pendingSlowMs = 0L
                    state = MotionState.IDLE
                }
            }
        } else {
            movingMs += dt
            slowSinceMs = NO_TIME
            pendingSlowMs = 0L
            state = MotionState.DRIVING
        }
        return state
    }

    private companion object {
        const val NO_TIME = Long.MIN_VALUE
    }
}
