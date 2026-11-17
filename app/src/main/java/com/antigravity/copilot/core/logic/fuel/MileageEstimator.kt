package com.antigravity.copilot.core.logic.fuel

import com.antigravity.copilot.core.logic.Config
import kotlin.math.exp
import kotlin.math.sqrt

enum class SegmentType { FULL_TO_FULL, GAUGE_TO_GAUGE }

/** A measured stretch: [km] driven on [litres] of fuel, ending at [endTimeMs]. */
data class MileageSegment(
    val endTimeMs: Long,
    val km: Double,
    val litres: Double,
    val type: SegmentType
) {
    val kmpl: Double get() = if (litres > 0) km / litres else 0.0
}

data class MileageEstimate(
    val kmpl: Double,
    /** 0..1 — grows with every consistent segment; lowered by impossible reconciliations. */
    val confidence: Double,
    val segmentsUsed: Int
)

/**
 * Weighted moving average over recent full-to-full and gauge-to-gauge segments: the last
 * [Config.MILEAGE_WINDOW_SEGMENTS] segments plus anything else within [Config.MILEAGE_WINDOW_DAYS]
 * days. km/L = Σw·km / Σw·L (ratio of sums, robust to short segments), regularised by a small
 * prior pseudo-segment so the first refills do not swing it wildly.
 */
object MileageEstimator {
    private const val MAX_SEGMENTS = 6
    private const val PRIOR_KM = 100.0
    private const val PRIOR_WEIGHT = 0.3
    private const val RECENCY_DECAY = 0.8

    fun isValid(s: MileageSegment): Boolean =
        s.km >= Config.MILEAGE_MIN_SEGMENT_KM && s.litres >= Config.MILEAGE_MIN_SEGMENT_L &&
            s.kmpl in Config.MILEAGE_MIN_KMPL..Config.MILEAGE_MAX_KMPL

    fun select(segments: List<MileageSegment>, nowMs: Long): List<MileageSegment> {
        val valid = segments.filter(::isValid).sortedByDescending { it.endTimeMs }
        val windowStart = nowMs - Config.MILEAGE_WINDOW_DAYS * 86_400_000L
        return valid.filterIndexed { i, s -> i < Config.MILEAGE_WINDOW_SEGMENTS || s.endTimeMs >= windowStart }
            .take(MAX_SEGMENTS)
    }

    fun typeWeight(t: SegmentType) = when (t) {
        SegmentType.FULL_TO_FULL -> Config.MILEAGE_WEIGHT_FULL_TO_FULL
        SegmentType.GAUGE_TO_GAUGE -> Config.MILEAGE_WEIGHT_GAUGE_TO_GAUGE
    }

    fun estimate(
        segments: List<MileageSegment>,
        nowMs: Long,
        priorKmpl: Double,
        confidencePenalty: Double = 0.0
    ): MileageEstimate {
        val prior = priorKmpl.coerceIn(Config.MILEAGE_MIN_KMPL, Config.MILEAGE_MAX_KMPL)
        val chosen = select(segments, nowMs)
        if (chosen.isEmpty()) return MileageEstimate(prior, 0.0, 0)
        var wKm = PRIOR_WEIGHT * PRIOR_KM
        var wL = PRIOR_WEIGHT * PRIOR_KM / prior
        var evidence = 0.0
        chosen.forEachIndexed { i, s ->
            val w = typeWeight(s.type) * Math.pow(RECENCY_DECAY, i.toDouble())
            wKm += w * s.km
            wL += w * s.litres
            evidence += typeWeight(s.type)
        }
        val kmpl = (wKm / wL).coerceIn(Config.MILEAGE_MIN_KMPL, Config.MILEAGE_MAX_KMPL)
        val base = 1.0 - exp(-evidence / 1.5)
        val consistency = if (chosen.size >= 2) {
            val m = chosen.map { it.kmpl }
            val mean = m.average()
            val sd = sqrt(m.sumOf { (it - mean) * (it - mean) } / m.size)
            1.0 - (sd / mean * 2.0).coerceIn(0.0, 0.6)
        } else 0.85
        val conf = (base * consistency * (1.0 - confidencePenalty.coerceIn(0.0, 0.9))).coerceIn(0.0, 1.0)
        return MileageEstimate(kmpl, conf, chosen.size)
    }
}
