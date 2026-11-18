package com.antigravity.copilot.core.road

import com.antigravity.copilot.core.logic.GeoMath
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Tunables for pothole detection, dedup, self-healing and the ahead alert. */
object RoadAnomalyConfig {
    const val WARMUP_MS = 5_000L
    const val TRIGGER_COOLDOWN_MS = 3_000L
    const val MODERATE_MSS = 3.5f
    const val SEVERE_MSS = 6.5f
    /** Window after the first sample over threshold in which the peak is collected. */
    const val PEAK_WINDOW_MS = 150L
    /** Low-pass smoothing factor for vertical acceleration at ~50 Hz (≈ 5–6 Hz cut-off). */
    const val LOW_PASS_ALPHA = 0.5f

    const val MIN_DETECT_SPEED_KMH = 10f
    const val MAX_ACCURACY_M = 15f
    const val MAX_FIX_AGE_MS = 3_000L
    /** Max dead-reckoning extrapolation of the last GPS fix to the moment of the jerk. */
    const val MAX_EXTRAPOLATION_MS = 2_000L

    const val DEDUP_RADIUS_M = 12.0
    const val COORD_SMOOTHING = 0.25
    const val INITIAL_CONFIDENCE = 2
    const val MAX_CONFIDENCE = 5

    const val VERIFY_RADIUS_M = 15.0
    const val VERIFY_MIN_SPEED_KMH = 15f
    const val PASS_COOLDOWN_MS = 60_000L
    /** Time after leaving the zone before a pass is judged (a late jerk callback can still land). */
    const val PASS_SETTLE_MS = 2_000L
    /** Smooth passes only decay an anomaly when travelling roughly the recorded direction. */
    const val VERIFY_HEADING_TOLERANCE_DEG = 45.0

    /** Warn up to 500 m ahead; still warn if an anomaly is first seen closer (turning onto a road). */
    const val ALERT_MIN_M = 40.0
    const val ALERT_MAX_M = 500.0
    const val ALERT_CONE_DEG = 30.0
    /** At 500 m a 30° cone is 250 m wide: also require the anomaly within this sideways offset. */
    const val ALERT_ACQUIRE_LATERAL_M = 60.0
    /** Once tracked, keep the target while its lateral offset stays within a lane or two. */
    const val ALERT_KEEP_LATERAL_M = 12.0
    const val ALERT_MIN_SPEED_KMH = 10f

    /** Radius of the in-memory working set refreshed from Room by bounding box. */
    const val NEARBY_RADIUS_M = 800.0
    const val NEARBY_REFRESH_MOVE_M = 150.0
}

object RoadAnomalyMath {
    private const val M_PER_DEG_LAT = 111_320.0

    data class Box(val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double)

    fun boundingBox(lat: Double, lon: Double, radiusM: Double): Box {
        val dLat = radiusM / M_PER_DEG_LAT
        val dLon = radiusM / (M_PER_DEG_LAT * cos(Math.toRadians(lat)).coerceAtLeast(0.01))
        return Box(lat - dLat, lat + dLat, lon - dLon, lon + dLon)
    }

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
        GeoMath.haversineM(lat1, lon1, lat2, lon2)

    /** Initial great-circle bearing from 1 to 2, degrees 0..360. */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Signed smallest difference b - a in (-180, 180]. */
    fun angleDiff(a: Double, b: Double): Double {
        var d = (b - a) % 360.0
        if (d > 180.0) d -= 360.0
        if (d <= -180.0) d += 360.0
        return d
    }

    fun classify(peakMss: Float): AnomalySeverity? = when {
        peakMss > RoadAnomalyConfig.SEVERE_MSS -> AnomalySeverity.RED
        peakMss >= RoadAnomalyConfig.MODERATE_MSS -> AnomalySeverity.YELLOW
        else -> null
    }

    /** Move a point [distanceM] along [bearingDeg] (flat-earth; fine for < 100 m). */
    fun offset(lat: Double, lon: Double, bearingDeg: Double, distanceM: Double): Pair<Double, Double> {
        val b = Math.toRadians(bearingDeg)
        val dLat = distanceM * cos(b) / M_PER_DEG_LAT
        val dLon = distanceM * sin(b) / (M_PER_DEG_LAT * cos(Math.toRadians(lat)).coerceAtLeast(0.01))
        return (lat + dLat) to (lon + dLon)
    }

    /**
     * Closest distance (m) from point P to segment A→B, in a local equirectangular frame centred on P.
     * Lets a 1 Hz GPS at highway speed still "see" a 15 m zone it jumped over between fixes.
     */
    fun distanceToSegmentM(pLat: Double, pLon: Double, aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val kx = M_PER_DEG_LAT * cos(Math.toRadians(pLat))
        val ax = (aLon - pLon) * kx; val ay = (aLat - pLat) * M_PER_DEG_LAT
        val bx = (bLon - pLon) * kx; val by = (bLat - pLat) * M_PER_DEG_LAT
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 < 1e-6) return hypot(ax, ay)
        val t = (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(ax + t * dx, ay + t * dy)
    }

    /** Smoothed coordinate merge used when a repeat hit lands within the dedup radius. */
    fun smooth(old: Double, new: Double, weight: Double = RoadAnomalyConfig.COORD_SMOOTHING): Double =
        old + (new - old) * weight

    fun headingCompatible(anomalyHeading: Float, carHeading: Double): Boolean =
        anomalyHeading < 0f || abs(angleDiff(anomalyHeading.toDouble(), carHeading)) <= RoadAnomalyConfig.VERIFY_HEADING_TOLERANCE_DEG

    fun norm(x: Float, y: Float, z: Float): Float = sqrt(x * x + y * y + z * z)
}

/** One detected vertical jerk, already positioned and classified. */
data class JerkHit(
    val latitude: Double,
    val longitude: Double,
    val peakMss: Float,
    val severity: AnomalySeverity,
    val headingDeg: Float,
    val elapsedMs: Long
)

/**
 * Sensor-free jerk detector core (unit-testable). Projects linear acceleration onto the gravity
 * axis so "vertical" is right however the head unit is mounted (an upright dash screen has its
 * device Z pointing along the car, not up). Falls back to device Z until gravity is known.
 */
class RoadJerkCore {
    private var armedAtMs = Long.MAX_VALUE
    private var lastTriggerMs = Long.MIN_VALUE / 2
    private var filtered = 0f
    private var gx = 0f; private var gy = 0f; private var gz = 1f
    private var hasGravity = false
    private var peakActive = false
    private var peakStartMs = 0L
    private var peak = 0f

    /** Start (or restart) the wake-up guard: nothing triggers for [RoadAnomalyConfig.WARMUP_MS]. */
    fun arm(nowMs: Long) {
        armedAtMs = nowMs + RoadAnomalyConfig.WARMUP_MS
        filtered = 0f
        peakActive = false
    }

    fun onGravity(x: Float, y: Float, z: Float) {
        val n = RoadAnomalyMath.norm(x, y, z)
        if (n < 1f) return
        gx = x / n; gy = y / n; gz = z / n
        hasGravity = true
    }

    /**
     * Feed one linear-acceleration sample. [gateOpen] is false when speed/accuracy/fix age make
     * a hit unusable. Returns the peak (m/s²) when a trigger completes, else null.
     */
    fun onLinear(x: Float, y: Float, z: Float, nowMs: Long, gateOpen: Boolean): Float? {
        val vertical = if (hasGravity) x * gx + y * gy + z * gz else z
        filtered += RoadAnomalyConfig.LOW_PASS_ALPHA * (vertical - filtered)
        if (nowMs < armedAtMs || !gateOpen) { peakActive = false; return null }
        if (nowMs - lastTriggerMs < RoadAnomalyConfig.TRIGGER_COOLDOWN_MS) return null
        val mag = abs(filtered)
        if (peakActive) {
            if (mag > peak) peak = mag
            if (nowMs - peakStartMs >= RoadAnomalyConfig.PEAK_WINDOW_MS) {
                peakActive = false
                lastTriggerMs = nowMs
                return peak
            }
        } else if (mag >= RoadAnomalyConfig.MODERATE_MSS) {
            peakActive = true
            peakStartMs = nowMs
            peak = mag
        }
        return null
    }
}

/**
 * Self-healing pass judge (unit-testable). Tracks the car through each anomaly's 15 m zone and,
 * once it has left and settled, reports whether the pass was smooth. A pass is voided (neither
 * smooth nor bumpy) if the car dropped under the verification speed inside it, e.g. braking for
 * a speed breaker.
 */
class AnomalyPassTracker {
    private class Pass(var lastInsideMs: Long, var hadJerk: Boolean = false, var voided: Boolean = false)

    private val active = HashMap<Long, Pass>()
    private val evaluatedAt = HashMap<Long, Long>()
    private var prevLat = Double.NaN
    private var prevLon = Double.NaN
    private var prevMs = 0L

    /** Returns ids of anomalies passed smoothly on this fix (to be decremented). */
    fun onFix(
        nowMs: Long, lat: Double, lon: Double, headingDeg: Double, speedKmh: Float,
        nearby: List<RoadAnomalyEntity>
    ): List<Long> {
        val usePrev = !prevLat.isNaN() && nowMs - prevMs <= 3_000L &&
            RoadAnomalyMath.distanceM(prevLat, prevLon, lat, lon) <= 100.0
        val fast = speedKmh >= RoadAnomalyConfig.VERIFY_MIN_SPEED_KMH
        for (a in nearby) {
            val d = if (usePrev) RoadAnomalyMath.distanceToSegmentM(a.latitude, a.longitude, prevLat, prevLon, lat, lon)
            else RoadAnomalyMath.distanceM(a.latitude, a.longitude, lat, lon)
            if (d > RoadAnomalyConfig.VERIFY_RADIUS_M) continue
            val pass = active[a.id]
            if (pass != null) {
                pass.lastInsideMs = nowMs
                if (!fast) pass.voided = true
                continue
            }
            if (inCooldown(a.id, nowMs) || !RoadAnomalyMath.headingCompatible(a.headingDeg, headingDeg)) continue
            // Entering slowly (braking for a breaker) voids the whole pass, even if the car speeds up inside.
            active[a.id] = Pass(nowMs, voided = !fast)
        }
        prevLat = lat; prevLon = lon; prevMs = nowMs

        val smooth = ArrayList<Long>(0)
        val it = active.entries.iterator()
        while (it.hasNext()) {
            val (id, pass) = it.next()
            if (pass.lastInsideMs == nowMs || nowMs - pass.lastInsideMs < RoadAnomalyConfig.PASS_SETTLE_MS) continue
            it.remove()
            evaluatedAt[id] = nowMs
            if (!pass.hadJerk && !pass.voided) smooth.add(id)
        }
        evaluatedAt.entries.removeAll { nowMs - it.value > RoadAnomalyConfig.PASS_COOLDOWN_MS }
        return smooth
    }

    /** A jerk merged into [id]: the current pass (if any) is bumpy, and no decay for a minute. */
    fun onJerk(id: Long, nowMs: Long) {
        val pass = active[id]
        if (pass != null) pass.hadJerk = true else evaluatedAt[id] = nowMs
    }

    fun forget(id: Long) { active.remove(id); evaluatedAt.remove(id) }

    private fun inCooldown(id: Long, nowMs: Long): Boolean =
        evaluatedAt[id]?.let { nowMs - it < RoadAnomalyConfig.PASS_COOLDOWN_MS } == true
}

/** What the flash card shows. Distance is dead-reckoned between fixes with [speedMps]. */
data class AnomalyAlert(
    val anomalyId: Long,
    val severity: AnomalySeverity,
    val distanceM: Double,
    val speedMps: Double,
    val atElapsedMs: Long
)

/**
 * 150–200 m ahead-cone geofence (unit-testable). Acquires the nearest anomaly in the ±30° cone
 * 150–200 m ahead, then follows it down to 0 while its lateral offset stays small, and drops it
 * once it is behind the car, the car turned away, or the distance starts growing again.
 */
class AheadAlertTracker {
    private var targetId: Long? = null
    private var minDistance = Double.MAX_VALUE
    private val recentlyAlerted = HashMap<Long, Long>()

    fun update(
        nowMs: Long, lat: Double, lon: Double, headingDeg: Double, speedKmh: Float,
        nearby: List<RoadAnomalyEntity>
    ): AnomalyAlert? {
        val speedMps = speedKmh / 3.6
        targetId?.let { id ->
            val a = nearby.firstOrNull { it.id == id }
            if (a != null) {
                val d = RoadAnomalyMath.distanceM(lat, lon, a.latitude, a.longitude)
                val rel = RoadAnomalyMath.angleDiff(headingDeg, RoadAnomalyMath.bearingDeg(lat, lon, a.latitude, a.longitude))
                val lateral = abs(d * sin(Math.toRadians(rel)))
                val behind = abs(rel) > 90.0
                val receding = minDistance < 40.0 && d > minDistance + 15.0
                val lost = abs(rel) > RoadAnomalyConfig.ALERT_CONE_DEG && lateral > RoadAnomalyConfig.ALERT_KEEP_LATERAL_M
                if (!behind && !receding && !lost && d <= RoadAnomalyConfig.ALERT_MAX_M + 60.0) {
                    if (d < minDistance) minDistance = d
                    return AnomalyAlert(a.id, a.severityEnum, d, speedMps, nowMs)
                }
            }
            recentlyAlerted[id] = nowMs
            targetId = null
            minDistance = Double.MAX_VALUE
        }
        recentlyAlerted.entries.removeAll { nowMs - it.value > RoadAnomalyConfig.PASS_COOLDOWN_MS }
        if (speedKmh < RoadAnomalyConfig.ALERT_MIN_SPEED_KMH) return null

        var best: RoadAnomalyEntity? = null
        var bestD = Double.MAX_VALUE
        for (a in nearby) {
            if (recentlyAlerted.containsKey(a.id)) continue
            // One jolt is not enough (audit K1): confirmed on another pass, or severe.
            if (!RoadAnomalyPattern.isAlertWorthy(a.confidenceScore, a.severityEnum)) continue
            val d = RoadAnomalyMath.distanceM(lat, lon, a.latitude, a.longitude)
            if (d < RoadAnomalyConfig.ALERT_MIN_M || d > RoadAnomalyConfig.ALERT_MAX_M || d >= bestD) continue
            val rel = RoadAnomalyMath.angleDiff(headingDeg, RoadAnomalyMath.bearingDeg(lat, lon, a.latitude, a.longitude))
            if (abs(rel) > RoadAnomalyConfig.ALERT_CONE_DEG) continue
            if (abs(d * sin(Math.toRadians(rel))) > RoadAnomalyConfig.ALERT_ACQUIRE_LATERAL_M) continue
            best = a; bestD = d
        }
        val hit = best ?: return null
        targetId = hit.id
        minDistance = bestD
        return AnomalyAlert(hit.id, hit.severityEnum, bestD, speedMps, nowMs)
    }

    fun reset() { targetId = null; minDistance = Double.MAX_VALUE }
}

/** Speed breaker vs pothole (audit K2). */
enum class AnomalyKind { UNKNOWN, SPEED_BREAKER, POTHOLE }

/**
 * Pattern rules for road anomalies (audit K1 / K2), pure and unit-tested.
 * - A speed breaker is felt twice per pass (front then rear axle) with similar peaks a fraction
 *   of a second apart; a pothole is a single dominant spike (or two very different ones).
 * - One jolt is not enough to warn other passes: an alert needs a re-confirmation on a separate
 *   pass (confidence above the initial value) or a severe (RED) hit.
 * - Repeat hits merge into an anomaly only when travelling in a compatible direction, so the
 *   opposite carriageway keeps its own record.
 */
object RoadAnomalyPattern {
    const val AXLE_PAIR_MIN_MS = 80L
    const val AXLE_PAIR_MAX_MS = 900L
    const val AXLE_PAIR_PEAK_RATIO = 0.6f
    const val SEPARATE_PASS_MS = RoadAnomalyConfig.PASS_COOLDOWN_MS

    fun classifyPair(firstPeak: Float, secondPeak: Float, gapMs: Long): AnomalyKind {
        if (gapMs !in AXLE_PAIR_MIN_MS..AXLE_PAIR_MAX_MS || firstPeak <= 0f || secondPeak <= 0f) return AnomalyKind.POTHOLE
        val ratio = minOf(firstPeak, secondPeak) / maxOf(firstPeak, secondPeak)
        return if (ratio >= AXLE_PAIR_PEAK_RATIO) AnomalyKind.SPEED_BREAKER else AnomalyKind.POTHOLE
    }

    /** True when a new hit belongs to a different pass than the previous hit on the same anomaly. */
    fun isSeparatePass(previousHitElapsedMs: Long?, hitElapsedMs: Long): Boolean =
        previousHitElapsedMs == null || hitElapsedMs - previousHitElapsedMs >= SEPARATE_PASS_MS

    fun isAlertWorthy(confidence: Int, severity: AnomalySeverity): Boolean =
        severity == AnomalySeverity.RED || confidence > RoadAnomalyConfig.INITIAL_CONFIDENCE

    fun canMerge(anomalyHeadingDeg: Float, hitHeadingDeg: Float): Boolean =
        hitHeadingDeg < 0f || RoadAnomalyMath.headingCompatible(anomalyHeadingDeg, hitHeadingDeg.toDouble())
}
