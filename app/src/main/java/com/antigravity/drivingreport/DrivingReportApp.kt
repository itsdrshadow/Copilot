package com.antigravity.drivingreport

import android.app.Application
import com.antigravity.drivingreport.core.database.PersistentDataManager
import com.antigravity.drivingreport.core.location.DrivingTrackingService
import com.antigravity.drivingreport.core.map.OfflineMapManager
import com.antigravity.drivingreport.core.recovery.CrashGuardian
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
class DrivingReportApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 1. Initialize Automotive CrashGuardian first to capture any runtime/init errors
        CrashGuardian.init(this)

        // 2. Restore all lifetime trips, fuel ledger & odometer calibrations asynchronously off main UI thread
        CoroutineScope(Dispatchers.IO).launch {
            PersistentDataManager.restoreIfNeeded(this@DrivingReportApp)
        }

        // 3. Initialize OSMDroid 100% Offline-First Map Engine with 10-year SQLite persistence
        OfflineMapManager.initOfflineConfiguration(this)

        // 5. Auto-start continuous tracking service on app launch
        DrivingTrackingService.start(this)
    }
}
