package com.antigravity.copilot.baselineprofile

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File

/** One journey iteration: gfxinfo counters plus CPU milliseconds per thread name. */
data class JankSample(val gfx: Map<String, Double>, val threadCpuMs: Map<String, Long>)

/**
 * Resets `gfxinfo` and snapshots per-thread CPU time (`ps -T`, 10 ms resolution) before a
 * journey, then diffs both afterwards. Thread names are kept as reported: `main` is the UI /
 * Compose thread, `RenderThread` is hwui, and MapLibre's TextureView GL thread is unnamed
 * (`Thread-N`) — it is the `Thread-N` whose CPU rises with map gestures.
 */
class JankProbe(private val device: UiDevice, private val pkg: String) {
    private var pid = ""
    private var before: Map<String, Pair<String, Long>> = emptyMap()

    fun start(): JankProbe {
        pid = device.executeShellCommand("pidof $pkg").trim()
        device.executeShellCommand("dumpsys gfxinfo $pkg reset")
        before = threadTimes()
        return this
    }

    fun stop(): JankSample {
        val after = threadTimes()
        val cpu = after.entries
            .groupBy({ (tid, v) -> if (tid == pid) "main" else v.first }) { (tid, v) -> v.second - (before[tid]?.second ?: 0L) }
            .mapValues { (_, deltas) -> deltas.sum() }
            .filterValues { it > 0 }
        return JankSample(parseGfx(device.executeShellCommand("dumpsys gfxinfo $pkg")), cpu)
    }

    /** tid -> (thread name, cumulative CPU ms). */
    private fun threadTimes(): Map<String, Pair<String, Long>> {
        if (pid.isEmpty()) return emptyMap()
        return device.executeShellCommand("ps -T -p $pid -o TID,TIME+,CMD").lines().drop(1)
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 3)
                if (parts.size < 3) return@mapNotNull null
                parts[0] to (parts[2] to cpuMs(parts[1]))
            }.toMap()
    }

    private fun cpuMs(time: String): Long {
        // [[hh:]mm:]ss.cc
        val fields = time.split(":")
        var seconds = 0.0
        fields.forEach { seconds = seconds * 60 + (it.toDoubleOrNull() ?: 0.0) }
        return (seconds * 1000).toLong()
    }

    private fun parseGfx(dump: String): Map<String, Double> {
        val out = linkedMapOf<String, Double>()
        GFX_FIELDS.forEach { (key, regex) ->
            regex.find(dump)?.groupValues?.get(1)?.toDoubleOrNull()?.let { out[key] = it }
        }
        return out
    }

    private companion object {
        val GFX_FIELDS = listOf(
            "frames" to Regex("Total frames rendered: (\\d+)"),
            "janky" to Regex("Janky frames: (\\d+)"),
            "p50" to Regex("\\n50th percentile: (\\d+)ms"),
            "p90" to Regex("\\n90th percentile: (\\d+)ms"),
            "p95" to Regex("\\n95th percentile: (\\d+)ms"),
            "p99" to Regex("\\n99th percentile: (\\d+)ms"),
            "missedVsync" to Regex("Number Missed Vsync: (\\d+)"),
            "slowUiThread" to Regex("Number Slow UI thread: (\\d+)"),
            "slowDrawCmds" to Regex("Number Slow issue draw commands: (\\d+)"),
            "slowBitmap" to Regex("Number Slow bitmap uploads: (\\d+)"),
            "deadlineMissed" to Regex("Number Frame deadline missed: (\\d+)"),
            "gpuP50" to Regex("50th gpu percentile: (\\d+)ms"),
            "gpuP90" to Regex("90th gpu percentile: (\\d+)ms"),
        )
    }
}

/** Collects samples across the parameterized run and writes one averaged TSV row per journey. */
object JankReport {
    private const val TAG = "JankReport"
    private val samples = linkedMapOf<Journey, MutableList<JankSample>>()

    @Synchronized
    fun add(journey: Journey, sample: JankSample) {
        samples.getOrPut(journey) { mutableListOf() }.add(sample)
        Log.i(TAG, "$journey ${sample.gfx} cpu=${sample.threadCpuMs.entries.sortedByDescending { it.value }.take(6)}")
    }

    @Synchronized
    fun write() {
        if (samples.isEmpty()) return
        val gfxKeys = samples.values.flatten().flatMap { it.gfx.keys }.distinct()
        val sb = StringBuilder()
        sb.append("journey\titerations\t").append(gfxKeys.joinToString("\t")).append("\ttopThreadsCpuMs\n")
        samples.forEach { (journey, list) ->
            val gfx = gfxKeys.map { k -> list.mapNotNull { it.gfx[k] }.average() }
            val threads = list.flatMap { it.threadCpuMs.entries }
                .groupBy({ it.key }, { it.value })
                .mapValues { (_, v) -> v.sum() / list.size }
                .entries.sortedByDescending { it.value }.take(8)
                .joinToString(", ") { "${it.key}=${it.value}" }
            sb.append(journey).append('\t').append(list.size).append('\t')
                .append(gfx.joinToString("\t") { "%.1f".format(it) })
                .append('\t').append(threads).append('\n')
        }
        Log.i(TAG, "\n$sb")
        outputDir().resolve("jank_report.tsv").writeText(sb.toString())
    }

    private fun outputDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dir = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let(::File)
            ?: instrumentation.context.getExternalFilesDir(null)
            ?: instrumentation.context.filesDir
        return dir.apply { mkdirs() }
    }
}
