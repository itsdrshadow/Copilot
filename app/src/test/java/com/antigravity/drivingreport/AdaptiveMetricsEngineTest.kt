package com.antigravity.drivingreport

import com.antigravity.drivingreport.core.metrics.AdaptiveMetricsEngine
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AdaptiveMetricsEngineTest {

    private lateinit var engine: AdaptiveMetricsEngine

    @Before
    fun setUp() {
        engine = AdaptiveMetricsEngine("TestPanel")
    }

    @Test
    fun testDurationAlwaysWithinStrictBounds() {
        // Minimum duration must be >= 7000ms
        // Maximum duration must be <= 20000ms
        val initialDuration = engine.computeDurationMs("metric_1")
        assertTrue("Initial duration must be within [7s, 20s], was $initialDuration",
            initialDuration in 7_000L..20_000L)

        // Artificially simulate high engagement
        repeat(10) {
            engine.onMetricManuallySelected("metric_1")
            engine.onMetricViewFinished("metric_1", 25_000L)
        }
        val boostedDuration = engine.computeDurationMs("metric_1")
        assertTrue("Boosted duration must not exceed 20s, was $boostedDuration",
            boostedDuration <= 20_000L)
        assertTrue("Boosted duration must be >= initial", boostedDuration >= initialDuration)

        // Artificially simulate low engagement (skipping immediately)
        repeat(10) {
            engine.onMetricViewFinished("metric_skipped", 500L)
        }
        val reducedDuration = engine.computeDurationMs("metric_skipped")
        assertTrue("Reduced duration must never fall below 7s, was $reducedDuration",
            reducedDuration >= 7_000L)
    }

    @Test
    fun testCycleDecayPreventsDominance() {
        engine.onMetricManuallySelected("favorite")
        val scoreBefore = engine.getScore("favorite")

        // Run cycle decay
        engine.applyCycleDecay()
        val scoreAfter = engine.getScore("favorite")

        assertTrue("Cycle decay must gradually reduce boosted score towards baseline",
            scoreAfter < scoreBefore && scoreAfter >= 1.0f)
    }
}
