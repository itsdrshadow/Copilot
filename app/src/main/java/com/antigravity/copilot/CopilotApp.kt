package com.antigravity.copilot

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import android.util.Log
import com.antigravity.copilot.core.location.DrivingTrackingService
import com.antigravity.copilot.core.map.OfflineMapManager
import com.antigravity.copilot.core.recovery.CrashGuardian
import com.antigravity.copilot.core.storage.StorageMigrationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

@HiltAndroidApp
class CopilotApp : Application() {
    companion object {
        private const val TAG = "CopilotApp"
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // 1. Initialize Automotive CrashGuardian first to capture any runtime/init errors
        CrashGuardian.init(this)

        // Direct boot: the boot receiver is directBootAware, so this process can start before the
        // user unlocks. Prefs, Room and maps live in credential-encrypted storage, which throws until
        // unlock, so everything else waits for ACTION_USER_UNLOCKED.
        if (!androidx.core.os.UserManagerCompat.isUserUnlocked(this)) {
            Log.i(TAG, "Locked boot: deferring storage, maps and tracking until the user unlocks")
            registerReceiver(object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                    try { unregisterReceiver(this) } catch (_: Exception) {}
                    initAfterUnlock()
                }
            }, android.content.IntentFilter(android.content.Intent.ACTION_USER_UNLOCKED))
            return
        }
        initAfterUnlock()
    }

    @Volatile
    private var unlockedInitDone = false

    private fun initAfterUnlock() {
        if (unlockedInitDone) return
        unlockedInitDone = true
        com.antigravity.copilot.core.recovery.SafeMode.init(this)

        // 2. Storage migration: block app start only for a version upgrade / fresh install (one prefs
        // read decides); a normal start loads settings synchronously and verifies in the background.
        if (StorageMigrationManager.needsBlockingInit(this)) {
            try {
                runBlocking(Dispatchers.IO) { runStorageInit() }
            } catch (e: Exception) {
                Log.e(TAG, "StorageMigrationManager initialization exception: ${e.message}", e)
            }
        } else {
            com.antigravity.copilot.core.settings.AppSettingsManager.init(this)
            com.antigravity.copilot.core.odometer.OdometerManager.init(this)
            appScope.launch {
                try {
                    runStorageInit()
                } catch (e: Exception) {
                    Log.e(TAG, "StorageMigrationManager background init exception: ${e.message}", e)
                }
            }
        }

        // 3. Initialize MapLibre Native & Offline Map Engine (32-bit armeabi-v7a)
        try {
            org.maplibre.android.MapLibre.getInstance(this)
            // 100% offline map: no tile/style/glyph network access; debug builds crash on any attempt.
            com.antigravity.copilot.core.map.MapNetworkGuard.install(failLoudly = BuildConfig.DEBUG)
        } catch (e: Throwable) {
            Log.w(TAG, "MapLibre Native init: ${e.message}")
        }
        OfflineMapManager.init(this)

        // 4. Auto-start continuous tracking service on app launch
        DrivingTrackingService.start(this)

        // Debug builds: adb-triggered fake trips / fuel history for UI testing
        if (BuildConfig.DEBUG) com.antigravity.copilot.core.debug.DebugDemoDataSeeder.register(this)
        // Send any "add place to OSM" notes queued while offline (runs only with a network).
        com.antigravity.copilot.core.osm.OsmPlaceNotes.scheduleSync(this)

        // 5. Diagnostic database integrity scan, kept off the blocking startup path
        appScope.launch { StorageMigrationManager.verifyDatabaseIntegrity(this@CopilotApp) }
    }

    private suspend fun runStorageInit() {
        val migrationResult = StorageMigrationManager.initializeAndMigrate(this@CopilotApp)
        if (!migrationResult.isSuccess) {
            Log.w(TAG, "Storage migration finished with warnings: ${migrationResult.errorMessage}")
        }
    }
}

typealias DrivingReportApp = CopilotApp
