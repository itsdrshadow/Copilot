package com.antigravity.copilot.baselineprofile

import android.os.SystemClock
import android.util.Log
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import java.io.File
import org.junit.AfterClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Map FPS modes as labelled in Settings > General > Map Rendering. */
enum class FpsMode(val label: String, val id: String) {
    FPS_60("Smooth (60 FPS)", "smooth_60"),
    FPS_30("Balanced (30 FPS)", "balanced_30"),
    SMART("Smart Auto (30 / 60 FPS)", "smart_auto"),
}

enum class MapScene { HOME, FULLSCREEN }

/**
 * A/B benchmark of the map FPS cap: the same heavy map script (pan, pinch-zoom, recenter
 * camera ease, then follow-mode tracking) on the Drive screen and full screen, per FPS mode.
 *
 * Besides [FrameTimingMetric] and gfxinfo (via [JankProbe]) each iteration samples the Mali GPU
 * devfreq load, whole-system CPU utilisation (/proc/stat) and SoC temperature.
 *
 * The mode is chosen through the real Settings UI, so this run changes the user's Map Rendering
 * setting; the last mode run stays selected.
 *
 * Run: ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.antigravity.copilot.baselineprofile.MapFpsAbBenchmark
 * Report: map_fps_ab_report.tsv next to the benchmark JSON.
 */
@RunWith(Parameterized::class)
class MapFpsAbBenchmark(private val mode: FpsMode, private val scene: MapScene) {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun mapFps() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = ITERATIONS,
        startupMode = null,
        setupBlock = {
            grantPermissions()
            startActivityAndWait()
            device.selectMapFps(mode)
            device.goDrive()
            if (scene == MapScene.FULLSCREEN) device.rail("Maps")
            device.mapView()
            SystemClock.sleep(SETTLE_MS)
        },
    ) {
        val sys = SystemProbe(device).start()
        val probe = JankProbe(device, PACKAGE).start()
        val t0 = SystemClock.elapsedRealtime()
        device.heavyMapScript()
        val seconds = (SystemClock.elapsedRealtime() - t0) / 1000.0
        val jank = probe.stop()
        MapFpsReport.add(mode, scene, seconds, jank, sys.stop())
    }

    companion object {
        private const val ITERATIONS = 3
        private const val SETTLE_MS = 2_000L

        @JvmStatic
        @Parameterized.Parameters(name = "{0}_{1}")
        fun params(): List<Array<Any>> =
            FpsMode.values().flatMap { m -> MapScene.values().map { s -> arrayOf<Any>(m, s) } }

        @JvmStatic
        @AfterClass
        fun writeReport() = MapFpsReport.write()
    }
}

/** Pan, pinch-zoom, recenter (camera ease) and follow-mode tracking. Same for every mode. */
private fun UiDevice.heavyMapScript() {
    val map = mapView() ?: return
    panMap(map, rounds = 3)
    pinchMap(map, rounds = 2)
    panMap(map, rounds = 1)
    // Camera tracking: resume follow -> eased glide back to the vehicle, then follow for a while.
    if (!tapDesc("Resume follow")) tapDesc("Recenter")
    SystemClock.sleep(TRACKING_MS)
    waitForIdle()
}

private const val TRACKING_MS = 6_000L

/**
 * Settings > General > Map Rendering > [mode]. Taps only once the row is fully on screen, then
 * waits for the app's `MapFps` log line confirming the MapView cap switched to that mode.
 */
private fun UiDevice.selectMapFps(mode: FpsMode) {
    if (awaitModeApplied(mode, tries = 1)) return
    rail("Settings")
    settingsTab("General")
    val safeTop = displayHeight / 8
    val safeBottom = displayHeight * 7 / 8
    repeat(16) {
        val node = findObject(By.text(mode.label))
        val b = node?.visibleBounds
        if (b != null && b.top > safeTop && b.bottom < safeBottom && b.height() > 0) {
            click(b.centerX(), b.centerY())
            waitForIdle()
            if (awaitModeApplied(mode)) return
        }
        swipe(displayWidth * 2 / 3, displayHeight * 2 / 3, displayWidth * 2 / 3, displayHeight * 2 / 5, 25)
        waitForIdle()
    }
    error("Map Rendering '${mode.label}' could not be applied")
}

/** True once the app's latest `MapFps` log line reports [mode]. */
private fun UiDevice.awaitModeApplied(mode: FpsMode, tries: Int = 10): Boolean {
    repeat(tries) {
        val last = executeShellCommand("logcat -d -s MapFps:D").lines().lastOrNull { "mode=" in it }
        if (last != null && "mode=${mode.id} " in last) return true
        SystemClock.sleep(200)
    }
    return false
}

data class SystemSample(
    val gpuLoadAvg: Double,
    val gpuLoadMax: Int,
    val gpuMhzAvg: Double,
    val cpuUtilPct: Double,
    val socTempStartC: Double,
    val socTempEndC: Double,
    val gpuTempMaxC: Double,
)

/** Samples Mali devfreq load + temperature in the background, and /proc/stat at the ends. */
class SystemProbe(private val device: UiDevice) {
    private val loads = mutableListOf<Int>()
    private val freqs = mutableListOf<Long>()
    private val gpuTemps = mutableListOf<Double>()
    @Volatile private var running = false
    private var thread: Thread? = null
    private var cpuStart = longArrayOf()
    private var socStart = 0.0

    fun start(): SystemProbe {
        cpuStart = cpuStat()
        socStart = temp(SOC_ZONE)
        running = true
        thread = Thread {
            while (running) {
                // "<load>@<freq>Hz"
                val raw = device.executeShellCommand("cat $GPU_LOAD").trim()
                val load = raw.substringBefore('@').toIntOrNull()
                val freq = raw.substringAfter('@').removeSuffix("Hz").toLongOrNull()
                synchronized(this) {
                    if (load != null) loads += load
                    if (freq != null) freqs += freq
                    gpuTemps += temp(GPU_ZONE)
                }
                SystemClock.sleep(SAMPLE_MS)
            }
        }.apply { start() }
        return this
    }

    fun stop(): SystemSample {
        running = false
        thread?.join()
        val cpuEnd = cpuStat()
        val total = cpuEnd[0] - cpuStart[0]
        val idle = cpuEnd[1] - cpuStart[1]
        synchronized(this) {
            return SystemSample(
                gpuLoadAvg = loads.average(),
                gpuLoadMax = loads.maxOrNull() ?: 0,
                gpuMhzAvg = freqs.average() / 1e6,
                cpuUtilPct = if (total > 0) 100.0 * (total - idle) / total else 0.0,
                socTempStartC = socStart,
                socTempEndC = temp(SOC_ZONE),
                gpuTempMaxC = gpuTemps.maxOrNull() ?: 0.0,
            )
        }
    }

    /** [total jiffies, idle+iowait jiffies] from the aggregate cpu line. */
    private fun cpuStat(): LongArray {
        val f = device.executeShellCommand("head -1 /proc/stat").trim().split(Regex("\\s+")).drop(1)
            .mapNotNull { it.toLongOrNull() }
        return longArrayOf(f.sum(), f.getOrElse(3) { 0 } + f.getOrElse(4) { 0 })
    }

    private fun temp(zone: Int): Double =
        (device.executeShellCommand("cat /sys/class/thermal/thermal_zone$zone/temp").trim().toDoubleOrNull() ?: 0.0) / 1000.0

    private companion object {
        const val GPU_LOAD = "/sys/class/devfreq/ff400000.gpu/load"
        const val SOC_ZONE = 0
        const val GPU_ZONE = 1
        const val SAMPLE_MS = 250L
    }
}

/** One averaged TSV row per (mode, scene). */
object MapFpsReport {
    private const val TAG = "MapFpsReport"
    private data class Row(val seconds: Double, val jank: JankSample, val sys: SystemSample)
    private val rows = linkedMapOf<Pair<FpsMode, MapScene>, MutableList<Row>>()

    @Synchronized
    fun add(mode: FpsMode, scene: MapScene, seconds: Double, jank: JankSample, sys: SystemSample) {
        rows.getOrPut(mode to scene) { mutableListOf() }.add(Row(seconds, jank, sys))
        Log.i(TAG, "$mode $scene ${"%.1f".format(seconds)}s ${jank.gfx} $sys cpu=${jank.threadCpuMs.entries.sortedByDescending { it.value }.take(5)}")
    }

    @Synchronized
    fun write() {
        if (rows.isEmpty()) return
        val gfxKeys = listOf("frames", "janky", "p50", "p90", "p95", "p99", "missedVsync", "slowUiThread", "slowDrawCmds", "deadlineMissed", "gpuP50", "gpuP90")
        val sb = StringBuilder()
        sb.append("mode\tscene\tn\tseconds\tuiFps\tjankPct\t").append(gfxKeys.joinToString("\t"))
            .append("\tgpuLoadAvg\tgpuLoadMax\tgpuMhzAvg\tcpuUtilPct\tsocTempStart\tsocTempEnd\tgpuTempMax\tappCpuMs\ttopThreadsCpuMs\n")
        rows.forEach { (key, list) ->
            fun avg(f: (Row) -> Double) = list.map(f).average()
            val gfx = gfxKeys.map { k -> avg { it.jank.gfx[k] ?: 0.0 } }
            val frames = avg { it.jank.gfx["frames"] ?: 0.0 }
            val janky = avg { it.jank.gfx["janky"] ?: 0.0 }
            val threads = list.flatMap { it.jank.threadCpuMs.entries }
                .groupBy({ it.key }, { it.value })
                .mapValues { (_, v) -> v.sum() / list.size }
            sb.append(key.first).append('\t').append(key.second).append('\t').append(list.size).append('\t')
                .append(fmt(avg { it.seconds })).append('\t')
                .append(fmt(avg { (it.jank.gfx["frames"] ?: 0.0) / it.seconds })).append('\t')
                .append(fmt(if (frames > 0) 100 * janky / frames else 0.0)).append('\t')
                .append(gfx.joinToString("\t") { fmt(it) }).append('\t')
                .append(fmt(avg { it.sys.gpuLoadAvg })).append('\t')
                .append(fmt(avg { it.sys.gpuLoadMax.toDouble() })).append('\t')
                .append(fmt(avg { it.sys.gpuMhzAvg })).append('\t')
                .append(fmt(avg { it.sys.cpuUtilPct })).append('\t')
                .append(fmt(avg { it.sys.socTempStartC })).append('\t')
                .append(fmt(avg { it.sys.socTempEndC })).append('\t')
                .append(fmt(list.maxOf { it.sys.gpuTempMaxC })).append('\t')
                .append(threads.values.sum()).append('\t')
                .append(threads.entries.sortedByDescending { it.value }.take(6).joinToString(", ") { "${it.key}=${it.value}" })
                .append('\n')
        }
        Log.i(TAG, "\n$sb")
        val dir = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: InstrumentationRegistry.getInstrumentation().context.filesDir
        dir.mkdirs()
        dir.resolve("map_fps_ab_report.tsv").writeText(sb.toString())
    }

    private fun fmt(v: Double) = "%.1f".format(v)
}
