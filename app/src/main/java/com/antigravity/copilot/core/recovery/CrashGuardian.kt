package com.antigravity.copilot.core.recovery

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Process
import android.util.Log
import com.antigravity.copilot.core.map.OfflineMapManager
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * CrashGuardian:
 * Enterprise-grade automotive crash loop detection, telemetry protection,
 * and emergency launcher recovery manager.
 *
 * Guarantees:
 * 1. Tracks consecutive crashes within a sliding time window (60 seconds).
 * 2. If single crash occurs, logs trace and allows clean Android restart.
 * 3. If >= 3 repeated crashes occur within 60s, breaks the infinite loop and
 *    launches an isolated Recovery UI in a separate JVM process (:recovery).
 * 4. Automatically resets crash counter when the launcher runs stably for 25 seconds.
 * 5. Provides zero-risk fallback to the original Blaupunkt stock launcher (com.android.launcher3).
 * 6. Never permanently disables the stock launcher.
 */
object CrashGuardian {
    private const val TAG = "CrashGuardian"

    private const val PREF_NAME = "driving_report_crash_guardian"
    private const val KEY_CRASH_COUNT = "crash_count"
    private const val KEY_LAST_CRASH_TIME = "last_crash_time"
    private const val KEY_FIRST_CRASH_TIME = "first_crash_time"
    private const val KEY_LAST_CRASH_TRACE = "last_crash_trace"
    private const val KEY_LAST_CRASH_MSG = "last_crash_msg"
    private const val KEY_SAFE_MODE = "is_safe_mode"

    const val STOCK_LAUNCHER_PACKAGE = "com.android.launcher3"
    const val STOCK_LAUNCHER_ACTIVITY = "com.android.launcher3.Launcher"

    const val MAX_CRASHES_BEFORE_RECOVERY = 3
    private const val CRASH_LOOP_WINDOW_MS = 60_000L // 60 seconds
    const val STABLE_BOOT_THRESHOLD_MS = 25_000L // 25 seconds of clean run = stable

    private var application: Application? = null
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var isInitialized = false

    /**
     * Initializes the CrashGuardian handler in the Application class.
     */
    fun init(app: Application) {
        if (isInitialized) return
        application = app
        isInitialized = true

        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            handleUncaughtException(app, thread, throwable)
        }

        Log.i(TAG, "CrashGuardian initialized successfully. Monitoring launcher stability.")
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    private fun handleUncaughtException(context: Context, thread: Thread, throwable: Throwable) {
        val now = System.currentTimeMillis()
        val prefs = getPrefs(context)

        val lastCrashTime = prefs.getLong(KEY_LAST_CRASH_TIME, 0L)
        val firstCrashTime = prefs.getLong(KEY_FIRST_CRASH_TIME, 0L)
        var crashCount = prefs.getInt(KEY_CRASH_COUNT, 0)

        // Check if this crash is within the crash-loop window
        val timeSinceLastCrash = now - lastCrashTime
        if (timeSinceLastCrash < CRASH_LOOP_WINDOW_MS && lastCrashTime > 0) {
            crashCount++
        } else {
            crashCount = 1
        }

        val stackTrace = getStackTraceString(throwable)
        val crashMessage = throwable.localizedMessage ?: throwable.javaClass.simpleName

        Log.e(TAG, "FATAL CRASH detected on thread '${thread.name}' (Crash #$crashCount). Message: $crashMessage", throwable)

        // 1. Persist crash telemetry synchronously before process termination
        prefs.edit()
            .putInt(KEY_CRASH_COUNT, crashCount)
            .putLong(KEY_LAST_CRASH_TIME, now)
            .putLong(KEY_FIRST_CRASH_TIME, if (crashCount == 1) now else firstCrashTime)
            .putString(KEY_LAST_CRASH_TRACE, stackTrace)
            .putString(KEY_LAST_CRASH_MSG, crashMessage)
            .commit() // Must be synchronous commit()

        // 2. Write crash log to disk for diagnostic inspection
        writeCrashLogToFile(context, now, crashCount, thread.name, crashMessage, stackTrace)

        // 3. Evaluate crash loop threshold
        if (crashCount >= MAX_CRASHES_BEFORE_RECOVERY) {
            Log.w(TAG, "CRASH LOOP DETECTED ($crashCount consecutive failures). Launching isolated Recovery UI...")

            try {
                // Launch RecoveryActivity in isolated :recovery process
                val recoveryIntent = Intent(context, RecoveryActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION
                    putExtra(RecoveryActivity.EXTRA_CRASH_COUNT, crashCount)
                    putExtra(RecoveryActivity.EXTRA_CRASH_MESSAGE, crashMessage)
                    putExtra(RecoveryActivity.EXTRA_CRASH_TRACE, stackTrace)
                }
                context.startActivity(recoveryIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed launching RecoveryActivity. Direct fallback to stock launcher.", e)
                launchStockLauncher(context)
            }
        } else {
            Log.i(TAG, "Single crash recorded ($crashCount/$MAX_CRASHES_BEFORE_RECOVERY). Allowing standard restart.")
        }

        // Cleanly terminate failing process
        try {
            Thread.sleep(300L) // Allow file buffers to flush
        } catch (_: Exception) {}

        // Chain to default handler (e.g., Play Console crash reporting) before killing process
        defaultHandler?.uncaughtException(thread, throwable)

        Process.killProcess(Process.myPid())
        System.exit(10)
    }

    /**
     * Resets the crash counter after the application has been running cleanly
     * and stably for STABLE_BOOT_THRESHOLD_MS (25 seconds).
     */
    fun notifySuccessfulBoot(context: Context) {
        val prefs = getPrefs(context)
        val currentCount = prefs.getInt(KEY_CRASH_COUNT, 0)
        if (currentCount > 0) {
            Log.i(TAG, "Launcher boot verified stable. Resetting crash count from $currentCount to 0.")
            prefs.edit()
                .putInt(KEY_CRASH_COUNT, 0)
                .putLong(KEY_FIRST_CRASH_TIME, 0L)
                .commit()
        }
        // Safe mode covers the session it was chosen for; once that session is stable the next
        // start runs normally (SafeMode keeps this session's state until the process ends).
        if (prefs.getBoolean(KEY_SAFE_MODE, false)) {
            Log.i(TAG, "Safe-mode session verified stable; next start will run in normal mode.")
            prefs.edit().putBoolean(KEY_SAFE_MODE, false).commit()
        }
    }

    /**
     * Sets Safe Mode flag. In Safe Mode, heavy map layers and animations are minimized.
     * Also applies safe mode settings immediately to reduce resource usage.
     */
    fun setSafeMode(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SAFE_MODE, enabled).commit()
        applySafeModeSettings(context, enabled)
    }

    /**
     * Applies safe mode to the running process via [SafeMode]: the tracking service lowers the GPS
     * request rate to 5 s and the map caps rendering at 30 fps (see SafeMode's consumers).
     */
    @Suppress("UNUSED_PARAMETER")
    fun applySafeModeSettings(context: Context, enabled: Boolean) {
        SafeMode.set(enabled)
        OfflineMapManager.setLightweightMode(enabled)

        Log.i(TAG, "Safe mode ${if (enabled) "enabled" else "disabled"} - resource usage ${if (enabled) "reduced" else "restored"}")
    }

    fun isSafeModeActive(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SAFE_MODE, false)
    }

    fun getCrashCount(context: Context): Int {
        return getPrefs(context).getInt(KEY_CRASH_COUNT, 0)
    }

    fun getLastCrashMessage(context: Context): String? {
        return getPrefs(context).getString(KEY_LAST_CRASH_MSG, null)
    }

    fun getLastCrashTrace(context: Context): String? {
        return getPrefs(context).getString(KEY_LAST_CRASH_TRACE, null)
    }

    /**
     * Resets all crash counters (used when driver confirms recovery or repairs launcher).
     */
    fun resetCrashHistory(context: Context) {
        getPrefs(context).edit().clear().commit()
    }

    /**
     * Instantly brings up the stock Blaupunkt Launcher3.
     */
    fun launchStockLauncher(context: Context): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                component = ComponentName(STOCK_LAUNCHER_PACKAGE, STOCK_LAUNCHER_ACTIVITY)
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            context.startActivity(intent)
            Log.i(TAG, "Successfully launched stock Blaupunkt launcher ($STOCK_LAUNCHER_PACKAGE).")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed launching stock launcher by component name. Attempting generic HOME intent...", e)
            try {
                val genericIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(genericIntent)
                true
            } catch (e2: Exception) {
                Log.e(TAG, "Failed generic HOME launch", e2)
                false
            }
        }
    }

    /**
     * Restores the Blaupunkt stock launcher as the default HOME app in Android package manager.
     */
    fun restoreStockLauncherAsDefault(context: Context): Boolean {
        var success = false
        // 1. Try su 0 root command if available
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "0", "cmd", "package", "set-home-activity", "$STOCK_LAUNCHER_PACKAGE/$STOCK_LAUNCHER_ACTIVITY"))
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                success = true
                Log.i(TAG, "Successfully restored stock launcher as default via root cmd package.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Root set-home-activity failed: ${e.message}")
        }

        // 2. Clear our own preferred HOME activities
        try {
            context.packageManager.clearPackagePreferredActivities(context.packageName)
            Log.i(TAG, "Cleared DrivingReport preferred HOME activities.")
        } catch (_: Exception) {}

        return success
    }

    /**
     * Sets Copilot as the default HOME application.
     */
    fun setCopilotAsDefaultHome(context: Context): Boolean {
        return try {
            val targetComponent = "${context.packageName}/com.antigravity.copilot.MainActivity"
            val process = Runtime.getRuntime().exec(arrayOf("su", "0", "cmd", "package", "set-home-activity", targetComponent))
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            Log.w(TAG, "Failed setting Copilot as default home via root: ${e.message}")
            false
        }
    }

    @Deprecated("Renamed to setCopilotAsDefaultHome", ReplaceWith("setCopilotAsDefaultHome(context)"))

    private fun getStackTraceString(throwable: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        pw.flush()
        return sw.toString()
    }

    private fun writeCrashLogToFile(
        context: Context,
        timestamp: Long,
        crashCount: Int,
        threadName: String,
        message: String,
        stackTrace: String
    ) {
        try {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val dateStr = dateFormat.format(Date(timestamp))

            val logEntry = buildString {
                appendLine("==================================================")
                appendLine("DRIVINGREPORT CRASH REPORT #$crashCount")
                appendLine("Timestamp: $dateStr ($timestamp)")
                appendLine("Thread: $threadName")
                appendLine("Message: $message")
                appendLine("Stack Trace:")
                appendLine(stackTrace)
                appendLine("==================================================")
                appendLine()
            }

            // Write to app internal files dir
            val internalFile = File(context.filesDir, "crash_log.txt")
            internalFile.appendText(logEntry)

            // Write to external SD card directory if accessible
            val externalDir = File("/sdcard/Copilot")
            if (!externalDir.exists()) externalDir.mkdirs()
            val externalFile = File(externalDir, "crash_log.txt")
            externalFile.appendText(logEntry)
        } catch (e: Exception) {
            Log.w(TAG, "Failed writing crash log to disk: ${e.message}")
        }
    }
}
