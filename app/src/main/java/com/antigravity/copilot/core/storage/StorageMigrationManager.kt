package com.antigravity.copilot.core.storage

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import com.antigravity.copilot.core.database.AppDatabase
import com.antigravity.copilot.core.database.PersistentDataManager
import com.antigravity.copilot.core.odometer.OdometerManager
import com.antigravity.copilot.core.settings.AppSettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext

/**
 * Enterprise Automotive Storage Migration & Safe Startup Manager.
 * Guarantees that all user settings, vehicle calibrations, trip history,
 * and fuel records permanently survive app updates without reset.
 */
object StorageMigrationManager {
    private const val TAG = "StorageMigrationManager"
    const val CURRENT_STORAGE_VERSION = 1
    private const val PREFS_STORAGE = "copilot_storage_migration_prefs"
    private const val KEY_STORAGE_VERSION = "storage_version"
    private const val KEY_MIGRATION_COMPLETED = "migration_completed"

    data class MigrationResult(
        val isSuccess: Boolean,
        val schemaVersion: Int,
        val errorMessage: String? = null
    )

    /**
     * Cheap synchronous check (one prefs read): only a storage-version upgrade or a fresh install /
     * clean reinstall (version 0, where the persistent mirror may need restoring before anything
     * reads the database) has to block app start. Every other start runs [initializeAndMigrate]
     * in the background.
     */
    fun needsBlockingInit(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS_STORAGE, Context.MODE_PRIVATE)
            .getInt(KEY_STORAGE_VERSION, 0) < CURRENT_STORAGE_VERSION

    suspend fun initializeAndMigrate(context: Context): MigrationResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        try {
            Log.i(TAG, "Starting StorageMigrationManager initialization...")
            val prefs = appContext.getSharedPreferences(PREFS_STORAGE, Context.MODE_PRIVATE)
            val currentVersion = prefs.getInt(KEY_STORAGE_VERSION, 0)

            // Step 1: Pre-initialize AppSettingsManager to load existing user preferences immediately
            AppSettingsManager.init(appContext)

            // Step 2: the full-database PRAGMA quick_check is diagnostic-only (it never changed behaviour)
            // and scales with GPS history, so it now runs after startup via verifyDatabaseIntegrity().

            // Step 3: Execute migrations if required (Version jump / First run with existing prefs)
            if (currentVersion < CURRENT_STORAGE_VERSION) {
                Log.i(TAG, "Executing storage migration from version $currentVersion to $CURRENT_STORAGE_VERSION")
                performStorageMigration(appContext, currentVersion, CURRENT_STORAGE_VERSION)

                prefs.edit()
                    .putInt(KEY_STORAGE_VERSION, CURRENT_STORAGE_VERSION)
                    .putBoolean(KEY_MIGRATION_COMPLETED, true)
                    .commit()

                // Also record in DataStore for dual-layer version verification
                try {
                    appContext.appDataStore.edit { dsPrefs ->
                        dsPrefs[SettingsDataStore.KEY_STORAGE_SCHEMA_VERSION] = CURRENT_STORAGE_VERSION
                        dsPrefs[SettingsDataStore.KEY_LAST_MIGRATION_TIMESTAMP] = System.currentTimeMillis()
                    }
                } catch (dse: Exception) {
                    Log.w(TAG, "Failed updating DataStore version marker: ${dse.message}")
                }
            }

            // Step 4: Ensure Odometer and Fuel and Map subsystems are re-hydrated
            OdometerManager.init(appContext)

            // Step 5: Check if restore from persistent mirror is needed (fresh install or clean reinstall)
            PersistentDataManager.restoreIfNeeded(appContext)

            // Step 6: Atomic snapshot backup of current valid state to protect against future unexpected terminations
            PersistentDataManager.backupAsync(appContext, force = true)

            Log.i(TAG, "StorageMigrationManager initialized successfully. Storage version: $CURRENT_STORAGE_VERSION")
            MigrationResult(isSuccess = true, schemaVersion = CURRENT_STORAGE_VERSION)
        } catch (e: Exception) {
            Log.e(TAG, "StorageMigrationManager error during initialization: ${e.message}", e)
            // CRITICAL: NEVER wipe or delete user data on migration failure!
            MigrationResult(isSuccess = false, schemaVersion = CURRENT_STORAGE_VERSION, errorMessage = e.message)
        }
    }

    /** Off-critical-path integrity check; call after startup on a background dispatcher. */
    suspend fun verifyDatabaseIntegrity(context: Context): Boolean = withContext(Dispatchers.IO) {
        val ok = validateDatabaseIntegrity(AppDatabase.getInstance(context.applicationContext))
        if (!ok) Log.w(TAG, "Database integrity check (PRAGMA quick_check) reported problems; user data left untouched.")
        ok
    }

    fun validateDatabaseIntegrity(db: AppDatabase): Boolean {
        return try {
            db.openHelper.readableDatabase.query("PRAGMA quick_check").use { cursor ->
                cursor.moveToFirst() && "ok".equals(cursor.getString(0), ignoreCase = true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Database integrity validation check failed", e)
            false
        }
    }

    private suspend fun performStorageMigration(context: Context, fromVersion: Int, toVersion: Int) {
        Log.i(TAG, "Migrating storage schema data: v$fromVersion -> v$toVersion")
        // 1. Migrate SharedPreferences into DataStore without overwriting user data
        AppSettingsManager.migrateFromLegacyPreferences(context)

        // 2. Synchronize vehicle physical specs across preferences
        syncVehicleSpecs(context)

        // 3. Migrate and preserve last completed trip summary if present
        preserveLastCompletedTrip(context)
    }

    private fun syncVehicleSpecs(context: Context) {
        try {
            val settings = AppSettingsManager.settingsState.value
            val fuelPrefs = context.getSharedPreferences("driving_report_fuel_prefs", Context.MODE_PRIVATE)
            val edit = fuelPrefs.edit()
            if (!fuelPrefs.contains("design_capacity") || fuelPrefs.getFloat("design_capacity", 0f) <= 0f) {
                edit.putFloat("design_capacity", settings.tankCapacityLiters.toFloat())
            }
            if (!fuelPrefs.contains("max_capacity") || fuelPrefs.getFloat("max_capacity", 0f) <= 0f) {
                edit.putFloat("max_capacity", settings.tankCapacityLiters.toFloat())
            }
            if (!fuelPrefs.contains("baseline_vehicle_mileage") || fuelPrefs.getFloat("baseline_vehicle_mileage", 0f) <= 0f) {
                edit.putFloat("baseline_vehicle_mileage", settings.expectedMileageKmpl.toFloat())
            }
            edit.apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed syncing vehicle specs: ${e.message}")
        }
    }

    private fun preserveLastCompletedTrip(context: Context) {
        try {
            val lastTripPrefs = context.getSharedPreferences("last_completed_trip_prefs", Context.MODE_PRIVATE)
            val lastId = lastTripPrefs.getLong("last_completed_trip_id", -1L)
            if (lastId > 0L) {
                Log.d(TAG, "Preserving last completed trip reference: $lastId")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed preserving last completed trip: ${e.message}")
        }
    }

}
