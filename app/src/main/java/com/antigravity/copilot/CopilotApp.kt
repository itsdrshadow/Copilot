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

        // 2. Execute safe version-controlled persistent storage migration & verification
        try {
            runBlocking(Dispatchers.IO) {
                val migrationResult = StorageMigrationManager.initializeAndMigrate(this@CopilotApp)
                if (!migrationResult.isSuccess) {
                    Log.w(TAG, "Storage migration finished with warnings: ${migrationResult.errorMessage}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "StorageMigrationManager initialization exception: ${e.message}", e)
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

        // 5. Diagnostic database integrity scan, kept off the blocking startup path
        appScope.launch { StorageMigrationManager.verifyDatabaseIntegrity(this@CopilotApp) }
    }
}

typealias DrivingReportApp = CopilotApp
