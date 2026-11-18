package com.antigravity.copilot.baselineprofile

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import org.junit.AfterClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Frame timing for every [Journey], measured on the release build with the baseline profile
 * applied. Besides Macrobenchmark's [FrameTimingMetric], each iteration records `gfxinfo`
 * (UI-thread vs draw-command jank counters) and per-thread CPU time, so the report can tell
 * Compose work (main thread) from hwui composition (RenderThread) and MapLibre's GL thread.
 *
 * Run: ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
 * Report: build/outputs/connected_android_test_additional_output/.../jank_report.tsv
 */
@RunWith(Parameterized::class)
class FrameTimingBenchmark(private val journey: Journey) {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun frameTiming() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = ITERATIONS,
        startupMode = null,
        setupBlock = {
            grantPermissions()
            startActivityAndWait()
            device.goDrive()
        },
    ) {
        val probe = JankProbe(device, PACKAGE).start()
        device.runJourney(journey)
        JankReport.add(journey, probe.stop())
    }

    companion object {
        private const val ITERATIONS = 2

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun journeys(): List<Journey> = Journey.values().toList()

        @JvmStatic
        @AfterClass
        fun writeReport() = JankReport.write()
    }
}

internal const val PACKAGE = "com.antigravity.copilot"

private val PERMISSIONS = listOf(
    "ACCESS_FINE_LOCATION",
    "ACCESS_COARSE_LOCATION",
    "ACCESS_BACKGROUND_LOCATION",
    "READ_EXTERNAL_STORAGE",
    "WRITE_EXTERNAL_STORAGE",
)

internal fun MacrobenchmarkScope.grantPermissions() {
    PERMISSIONS.forEach { device.executeShellCommand("pm grant $PACKAGE android.permission.$it") }
}
