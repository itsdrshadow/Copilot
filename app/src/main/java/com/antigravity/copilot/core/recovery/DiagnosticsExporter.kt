package com.antigravity.copilot.core.recovery

import android.content.Context
import android.net.Uri
import android.os.Build
import com.antigravity.copilot.BuildConfig
import com.antigravity.copilot.core.settings.AppSettingsManager
import com.antigravity.copilot.core.trip.GeofenceDiagnostics
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * One-tap diagnostics bundle for head-unit bug reports (audit O3): app / device info, settings
 * snapshot, geofence lifecycle log, crash log and last crash trace, written as a ZIP to a
 * user-picked file (SAF). No trips, points or fuel history (no location trail).
 */
object DiagnosticsExporter {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun fileName(nowMs: Long = System.currentTimeMillis()): String =
        "copilot_diagnostics_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date(nowMs)) + ".zip"

    /** Entry name → text content of the bundle (pure apart from the inputs). */
    fun entries(context: Context): Map<String, String> {
        val info = linkedMapOf(
            "appVersion" to BuildConfig.VERSION_NAME,
            "versionCode" to BuildConfig.VERSION_CODE.toString(),
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "safeMode" to SafeMode.active.value.toString(),
            "crashCount" to CrashGuardian.getCrashCount(context).toString(),
            "generatedAt" to System.currentTimeMillis().toString()
        )
        val out = linkedMapOf(
            "info.json" to gson.toJson(info),
            "settings.json" to gson.toJson(AppSettingsManager.settingsState.value),
            "geofence_log.json" to gson.toJson(GeofenceDiagnostics.logs)
        )
        CrashGuardian.getLastCrashTrace(context)?.let { out["last_crash_trace.txt"] = it }
        File(context.filesDir, "crash_log.txt").takeIf { it.isFile }?.let { f ->
            // Last 256 KB is plenty for a report.
            val text = f.readText()
            out["crash_log.txt"] = if (text.length > 256_000) text.takeLast(256_000) else text
        }
        return out
    }

    suspend fun export(context: Context, target: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val content = entries(context)
            context.contentResolver.openOutputStream(target)?.use { os ->
                ZipOutputStream(os).use { zip ->
                    for ((name, text) in content) {
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(text.toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                    }
                }
            } != null
        }.getOrDefault(false)
    }
}
