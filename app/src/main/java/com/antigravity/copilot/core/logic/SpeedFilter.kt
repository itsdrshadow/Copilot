package com.antigravity.copilot.core.logic

/**
 * True-speed filter for the speedometer: GPS Doppler speed only (never position-derived max
 * fusion, which overshoots), accuracy-gated and lightly smoothed with an EMA.
 */
class SpeedFilter(
    private val maxAccuracyM: Double = Config.SPEED_MAX_ACCURACY_M,
    private val alpha: Double = Config.SPEED_SMOOTHING_ALPHA,
    private val zeroSnapKmh: Double = Config.SPEED_ZERO_SNAP_KMH,
    private val staleMs: Long = Config.SPEED_STALE_MS
) {
    var speedKmh: Double = 0.0
        private set
    private var lastAcceptedMs: Long = Long.MIN_VALUE

    fun reset() {
        speedKmh = 0.0
        lastAcceptedMs = Long.MIN_VALUE
    }

    /**
     * @param dopplerMps GPS Doppler speed in m/s, or null if the fix has no speed.
     * @return the filtered speed in km/h to display.
     */
    fun onFix(dopplerMps: Double?, accuracyM: Double, timeMs: Long): Double {
        val usable = dopplerMps != null && dopplerMps.isFinite() && dopplerMps >= 0.0 &&
            accuracyM.isFinite() && accuracyM <= maxAccuracyM &&
            dopplerMps * 3.6 <= Config.SPEED_MAX_PLAUSIBLE_KMH
        if (!usable) return tick(timeMs)
        val raw = dopplerMps!! * 3.6
        val first = lastAcceptedMs == Long.MIN_VALUE || timeMs - lastAcceptedMs > staleMs
        speedKmh = if (first) raw else alpha * raw + (1 - alpha) * speedKmh
        if (raw < zeroSnapKmh && speedKmh < zeroSnapKmh * 2) speedKmh = 0.0
        if (speedKmh < zeroSnapKmh) speedKmh = 0.0
        lastAcceptedMs = timeMs
        return speedKmh
    }

    /** Call periodically; decays to 0 if no usable fix arrived recently. */
    fun tick(timeMs: Long): Double {
        if (lastAcceptedMs != Long.MIN_VALUE && timeMs - lastAcceptedMs > staleMs) speedKmh = 0.0
        return speedKmh
    }
}
