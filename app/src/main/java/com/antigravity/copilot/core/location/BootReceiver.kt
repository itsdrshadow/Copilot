package com.antigravity.copilot.core.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.antigravity.copilot.MainActivity

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action in listOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_LOCKED_BOOT_COMPLETED,
                "android.intent.action.QUICKBOOT_POWERON",
                "com.htc.intent.action.QUICKBOOT_POWERON"
            )
        ) {
            Log.i("BootReceiver", "Received boot trigger ($action). Starting telemetry engine & bringing up Cockpit.")
            com.antigravity.copilot.core.recovery.BootForegroundGuardian.onBootOrWake(context)
            DrivingTrackingService.start(context)

            try {
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    this.action = Intent.ACTION_MAIN
                    addCategory(Intent.CATEGORY_HOME)
                    addCategory(Intent.CATEGORY_DEFAULT)
                }
                context.startActivity(launchIntent)
            } catch (e: Exception) {
                Log.e("BootReceiver", "Failed to start MainActivity on boot", e)
            }
        }
    }
}
