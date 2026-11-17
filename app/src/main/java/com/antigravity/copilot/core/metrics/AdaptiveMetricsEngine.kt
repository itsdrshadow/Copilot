package com.antigravity.copilot.core.metrics

import java.util.concurrent.ConcurrentHashMap

/**
 * AdaptiveMetricsEngine:
 * Dynamic user engagement and interest scoring engine for dashboard metric rotation.
 *
 * Requirements:
 * 1. Adaptive display duration: 7s (minimum) to 20s (maximum) based on driver interest.
 * 2. Tracks manual taps, view durations, and skip behavior.
 * 3. Incorporates gradual decay so no metric dominates permanently.
 * 4. Filters out accidental rapid taps with debouncing.
 * 5. Guarantees sequential rotation so all metrics continue to appear.
 */
class AdaptiveMetricsEngine(private val panelName: String) {

    private val engagementScores = ConcurrentHashMap<String, Float>()
    private val lastTapTime = ConcurrentHashMap<String, Long>()

    companion object {
        const val MIN_DURATION_MS = 7_000L
        const val MAX_DURATION_MS = 20_000L
        const val BASELINE_SCORE = 1.0f
        const val MAX_SCORE = 3.0f
        const val DEBOUNCE_TAP_MS = 1_200L
    }

    fun getScore(metricId: String): Float {
        return engagementScores[metricId] ?: BASELINE_SCORE
    }

    /**
     * Calculates the adaptive display time in milliseconds for a metric.
     * Maps score [1.0 .. 3.0] to [7,000ms .. 20,000ms].
     */
    fun computeDurationMs(metricId: String): Long {
        val score = getScore(metricId).coerceIn(BASELINE_SCORE, MAX_SCORE)
        val fraction = (score - BASELINE_SCORE) / (MAX_SCORE - BASELINE_SCORE)
        val duration = MIN_DURATION_MS + (fraction * (MAX_DURATION_MS - MIN_DURATION_MS)).toLong()
        return duration.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
    }

    /**
     * Invoked when the driver intentionally taps or views a metric.
     */
    fun onMetricManuallySelected(metricId: String) {
        val now = System.currentTimeMillis()
        val last = lastTapTime[metricId] ?: 0L
        if (now - last > DEBOUNCE_TAP_MS) {
            lastTapTime[metricId] = now
            val current = getScore(metricId)
            val updated = (current + 0.40f).coerceAtMost(MAX_SCORE)
            engagementScores[metricId] = updated
        }
    }

    /**
     * Invoked when a metric finishes its display cycle.
     * Rewards genuine dwell time and reduces score on quick skips.
     */
    fun onMetricViewFinished(metricId: String, dwellTimeMs: Long) {
        val current = getScore(metricId)
        val updated = when {
            dwellTimeMs >= 5_000L -> (current + 0.05f).coerceAtMost(MAX_SCORE)
            dwellTimeMs < 2_000L -> (current - 0.15f).coerceAtLeast(BASELINE_SCORE)
            else -> current
        }
        engagementScores[metricId] = updated
    }

    /**
     * Gradually decays engagement scores toward baseline so no single metric dominates forever.
     */
    fun applyCycleDecay() {
        for ((key, score) in engagementScores) {
            val decayed = BASELINE_SCORE + ((score - BASELINE_SCORE) * 0.92f)
            engagementScores[key] = decayed.coerceAtLeast(BASELINE_SCORE)
        }
    }

}
