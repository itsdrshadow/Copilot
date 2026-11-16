package com.antigravity.copilot.core.recovery

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.antigravity.copilot.MainActivity
import com.antigravity.copilot.core.settings.AppSettingsManager

/**
 * BootForegroundGuardian:
 * Protects Copilot Cockpit from being occluded or backgrounded by Chinese Car Head Unit
 * ROM autostart scripts (which force-launch stock BT Music into the foreground on car ignition).
 *
 * Behavior:
 * - Detects boot / quickboot / ignition wake.
 * - Arms an active protection window (15s).
 * - If an external app (such as BT Music) steals the foreground during this window,
 *   allows it 1200ms to complete its Bluetooth audio initialization, then smoothly
 *   brings Copilot back to the front via Intent.FLAG_ACTIVITY_REORDER_TO_FRONT.
 * - This pushes the BT Music app to the background without killing its activity/playback,
 *   enabling Bluetooth audio to continue streaming smoothly while keeping Copilot Cockpit visible.
 * - If the user explicitly launches an app from the App Drawer, protection is immediately disarmed.
 */
object BootForegroundGuardian {
    private const val TAG = "BootForegroundGuardian"
    const val BOOT_PROTECTION_WINDOW_MS = 15000L
    const val REASSERTION_DELAY_MS = 1200L

    private var bootTimestamp: Long = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private var reassertionRunnable: Runnable? = null
    var isUserInitiatedNav: Boolean = false

    fun onBootOrWake(@Suppress("UNUSED_PARAMETER") context: Context? = null) {
        bootTimestamp = SystemClock.elapsedRealtime()
        isUserInitiatedNav = false
        Log.i(TAG, "Car ignition/boot detected. Boot protection armed for ${BOOT_PROTECTION_WINDOW_MS / 1000}s.")
    }

    fun isProtectionActive(): Boolean {
        if (bootTimestamp <= 0L) return false
        val elapsed = SystemClock.elapsedRealtime() - bootTimestamp
        return elapsed in 0..BOOT_PROTECTION_WINDOW_MS
    }

    fun onMainActivityPaused(context: Context) {
        if (isUserInitiatedNav) {
            Log.d(TAG, "MainActivity paused due to explicit user action. Not reasserting.")
            return
        }

        val settings = AppSettingsManager.settingsState.value
        if (!settings.autoForegroundOnBoot) {
            Log.d(TAG, "Auto-foreground on boot is disabled in settings.")
            return
        }

        if (isProtectionActive()) {
            Log.i(TAG, "External app stole foreground during boot window. Scheduling Copilot reassertion in ${REASSERTION_DELAY_MS}ms to retain cockpit and keep BT music in background.")
            reassertionRunnable?.let { mainHandler.removeCallbacks(it) }
            val runnable = Runnable {
                if (isProtectionActive() && !isUserInitiatedNav) {
                    try {
                        val homeIntent = Intent(context, MainActivity::class.java).apply {
                            action = Intent.ACTION_MAIN
                            addCategory(Intent.CATEGORY_HOME)
                            addCategory(Intent.CATEGORY_DEFAULT)
                            addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                            )
                        }
                        context.startActivity(homeIntent)
                        Log.i(TAG, "Successfully reasserted Copilot in foreground; external BT music app relegated to background.")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to reassert Copilot foreground", e)
                    }
                }
            }
            reassertionRunnable = runnable
            mainHandler.postDelayed(runnable, REASSERTION_DELAY_MS)
        }
    }

    fun onMainActivityResumed() {
        // Cancel pending reassertion if Copilot is already in foreground
        reassertionRunnable?.let {
            mainHandler.removeCallbacks(it)
            reassertionRunnable = null
        }
    }

    fun notifyUserInitiatedAppLaunch() {
        isUserInitiatedNav = true
        reassertionRunnable?.let {
            mainHandler.removeCallbacks(it)
            reassertionRunnable = null
        }
    }

    fun setTestBootTimestamp(timestamp: Long) {
        bootTimestamp = timestamp
    }
}
