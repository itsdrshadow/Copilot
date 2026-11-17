package com.antigravity.copilot.core.logic

import kotlin.math.max

/**
 * Odometer = sum of filtered haversine segment distances (never speed x time).
 * Poor-accuracy fixes are skipped; while slow, movements smaller than the accuracy radius are not
 * counted (the anchor stays put so genuine slow creep is still counted once it exceeds it);
 * teleports above a plausible speed reset the anchor without adding distance.
 */
class OdometerAccumulator(
    private val maxAccuracyM: Double = Config.ODO_MAX_ACCURACY_M,
    private val minSegmentM: Double = Config.ODO_MIN_SEGMENT_M,
    private val stationarySpeedKmh: Double = Config.ODO_STATIONARY_SPEED_KMH,
    private val maxImpliedKmh: Double = Config.ODO_MAX_IMPLIED_SPEED_KMH
) {
    var totalM: Double = 0.0
        private set
    private var anchor: GeoFix? = null

    fun restore(totalM: Double) {
        this.totalM = totalM
        anchor = null
    }

    /** Returns the distance (m) added by this fix. */
    fun onFix(fix: GeoFix): Double {
        if (!fix.accuracyM.isFinite() || fix.accuracyM > maxAccuracyM) return 0.0
        val a = anchor
        if (a == null) {
            anchor = fix
            return 0.0
        }
        val d = GeoMath.haversineM(a.lat, a.lon, fix.lat, fix.lon)
        val dt = (fix.timeMs - a.timeMs) / 1000.0
        if (dt <= 0.0) return 0.0
        if (d / dt * 3.6 > maxImpliedKmh) {
            anchor = fix // teleport / multipath jump: re-anchor, add nothing
            return 0.0
        }
        val jitterM = max(minSegmentM, max(fix.accuracyM, a.accuracyM))
        val slow = fix.speedKmh < stationarySpeedKmh
        if (slow && d < jitterM) return 0.0 // stationary jitter
        if (d < minSegmentM) return 0.0
        totalM += d
        anchor = fix
        return d
    }

    /** Forget the anchor (e.g. trip end, long GPS gap) so the next fix starts a new segment. */
    fun breakSegment() {
        anchor = null
    }
}

/**
 * Real-odometer correction: at every refill the user enters the real odometer reading.
 * k = real_delta / app_delta (EMA-smoothed, clamped); shown = base + k * (app_km - app_km_at_base).
 */
data class OdometerCorrection(
    val baseRealKm: Double = 0.0,
    val appKmAtBase: Double = 0.0,
    val k: Double = 1.0,
    val samples: Int = 0,
    val hasBase: Boolean = false
) {
    fun shown(appKmTotal: Double): Double =
        if (!hasBase) appKmTotal else baseRealKm + k * (appKmTotal - appKmAtBase).coerceAtLeast(0.0)

    fun withRealReading(realKm: Double, appKmTotal: Double): OdometerCorrection {
        if (!hasBase) return OdometerCorrection(realKm, appKmTotal, k, samples, true)
        val appDelta = appKmTotal - appKmAtBase
        val realDelta = realKm - baseRealKm
        var newK = k
        var n = samples
        if (appDelta >= Config.ODO_K_MIN_INTERVAL_KM && realDelta > 0.0) {
            val obs = (realDelta / appDelta).coerceIn(Config.ODO_K_MIN, Config.ODO_K_MAX)
            val w = if (samples == 0) 1.0 else Config.ODO_K_SMOOTHING
            newK = (w * obs + (1 - w) * k).coerceIn(Config.ODO_K_MIN, Config.ODO_K_MAX)
            n++
        }
        return OdometerCorrection(realKm, appKmTotal, newK, n, true)
    }
}
