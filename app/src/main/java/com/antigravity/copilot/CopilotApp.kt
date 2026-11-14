package com.antigravity.copilot

import android.app.Application
import android.util.Log
import com.antigravity.copilot.core.location.DrivingTrackingService
import com.antigravity.copilot.core.map.OfflineMapManager
import com.antigravity.copilot.core.recovery.CrashGuardian
import com.antigravity.copilot.core.storage.StorageMigrationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class CopilotApp : Application() {
    companion object {
        private const val TAG = "CopilotApp"
    }

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

        // 3. Initialize OSMDroid 100% Offline-First Map Engine with 10-year SQLite persistence
        OfflineMapManager.initOfflineConfiguration(this)

        // 4. Auto-start continuous tracking service on app launch
        DrivingTrackingService.start(this)
    }
}

typealias DrivingReportApp = CopilotApp
