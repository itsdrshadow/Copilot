package com.antigravity.copilot.core.logic

/**
 * Harsh brake / rapid acceleration counter on the classifier's smoothed, sensor-fused
 * acceleration. Edge-triggered: one event per excursion beyond a threshold; the excursion ends
 * when |a| falls back below half the threshold. Steps whose dt is outside [minDtMs, maxDtMs]
 * (duplicate fixes, GPS gaps) never count, so raw Doppler noise cannot inflate the counters.
 */
class HarshEventCounter(
    private val harshBrakeMps2: Double = -3.2,
    private val rapidAccelMps2: Double = 3.0,
    private val minDtMs: Long = 500L,
    private val maxDtMs: Long = 2000L,
    private val minSpeedKmh: Double = 5.0
) {
    var harshBrakes: Int = 0
        private set
    var rapidAccels: Int = 0
        private set
    private var inBrake = false
    private var inAccel = false

    fun reset() = restore(0, 0)

    fun restore(brakes: Int, accels: Int) {
        harshBrakes = brakes; rapidAccels = accels; inBrake = false; inAccel = false
    }

    fun onSample(smoothedAccelMps2: Double, dtMs: Long, speedKmh: Double) {
        if (dtMs !in minDtMs..maxDtMs || !smoothedAccelMps2.isFinite()) return
        if (smoothedAccelMps2 <= harshBrakeMps2 && speedKmh >= minSpeedKmh) {
            if (!inBrake) { harshBrakes++; inBrake = true }
        } else if (smoothedAccelMps2 > harshBrakeMps2 / 2) {
            inBrake = false
        }
        if (smoothedAccelMps2 >= rapidAccelMps2 && speedKmh >= minSpeedKmh) {
            if (!inAccel) { rapidAccels++; inAccel = true }
        } else if (smoothedAccelMps2 < rapidAccelMps2 / 2) {
            inAccel = false
        }
    }
}

/**
 * Overspeed = speed above (limit + margin) continuously for at least [minDurationMs]; one event
 * per excursion, re-armed once speed drops back to the limit. Times are monotonic milliseconds.
 */
class OverspeedEventTracker(
    private val marginKmh: Double = 5.0,
    private val minDurationMs: Long = 5000L
) {
    var events: Int = 0
        private set
    private var overSinceMs = Long.MIN_VALUE
    private var counted = false

    fun reset() = restore(0)

    fun restore(count: Int) {
        events = count; overSinceMs = Long.MIN_VALUE; counted = false
    }

    fun onSample(timeMs: Long, speedKmh: Double, limitKmh: Double) {
        if (limitKmh <= 0.0 || !speedKmh.isFinite()) return
        when {
            speedKmh > limitKmh + marginKmh -> {
                if (overSinceMs == Long.MIN_VALUE) overSinceMs = timeMs
                if (!counted && timeMs - overSinceMs >= minDurationMs) {
                    events++; counted = true
                }
            }
            speedKmh <= limitKmh -> { overSinceMs = Long.MIN_VALUE; counted = false }
            // Between limit and limit+margin: hold the excursion state (hysteresis band).
            else -> if (!counted) overSinceMs = Long.MIN_VALUE
        }
    }

    /** Gap in fixes (tunnel / power cut): an excursion never spans a gap. */
    fun markGap() {
        if (!counted) overSinceMs = Long.MIN_VALUE
    }
}

/**
 * Driver score 0..100 from discrete events, normalised per 100 km so a long highway trip is not
 * punished for its length. Distances below [minNormKm] are treated as [minNormKm] so one hard brake
 * on a 3 km city trip does not zero the score.
 */
object DriverScoreCalculator {
    const val HARSH_BRAKE_PENALTY = 3.0
    const val RAPID_ACCEL_PENALTY = 2.0
    const val OVERSPEED_PENALTY = 2.0
    const val MIN_NORM_KM = 25.0
    /** Penalty points per 100 km are scaled by this factor (10 events/100 km of harsh braking = -30 x 1). */
    private const val PER_100KM_SCALE = 1.0

    fun score(harshBrakes: Int, rapidAccels: Int, overspeedEvents: Int, distanceKm: Double, enabled: Boolean = true): Int {
        if (!enabled) return 100
        val penalty = harshBrakes.coerceAtLeast(0) * HARSH_BRAKE_PENALTY +
            rapidAccels.coerceAtLeast(0) * RAPID_ACCEL_PENALTY +
            overspeedEvents.coerceAtLeast(0) * OVERSPEED_PENALTY
        val km = if (distanceKm.isFinite()) distanceKm.coerceAtLeast(MIN_NORM_KM) else MIN_NORM_KM
        val per100 = penalty * (100.0 / km) * PER_100KM_SCALE
        return (100.0 - per100).toInt().coerceIn(0, 100)
    }
}

/**
 * MOVING / IDLE classifier with hysteresis and debounce for the vehicle-state log: stop-and-go
 * traffic must not write hundreds of transition rows per trip. MOVING once speed >= threshold +
 * [bandKmh]; IDLE only after speed stayed below threshold - [bandKmh] for [idleDebounceMs].
 * Times are monotonic milliseconds.
 */
class VehicleMotionDebouncer(
    private val bandKmh: Double = 1.0,
    private val idleDebounceMs: Long = 10_000L
) {
    var isMoving: Boolean = false
        private set
    private var slowSinceMs = Long.MIN_VALUE

    /** Returns the debounced state after this sample (true = MOVING). */
    fun onSample(nowMs: Long, speedKmh: Double, thresholdKmh: Double): Boolean {
        if (!speedKmh.isFinite()) return isMoving
        if (speedKmh >= thresholdKmh + bandKmh) {
            isMoving = true
            slowSinceMs = Long.MIN_VALUE
        } else if (speedKmh < (thresholdKmh - bandKmh).coerceAtLeast(0.5)) {
            if (slowSinceMs == Long.MIN_VALUE) slowSinceMs = nowMs
            if (isMoving && nowMs - slowSinceMs >= idleDebounceMs) isMoving = false
        } else {
            // Inside the hysteresis band: keep the current state, restart the idle debounce.
            slowSinceMs = Long.MIN_VALUE
        }
        return isMoving
    }

    fun reset(moving: Boolean = false) {
        isMoving = moving
        slowSinceMs = Long.MIN_VALUE
    }
}
