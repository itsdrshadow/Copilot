package com.antigravity.drivingreport.core.persistence

import android.content.Context
import com.antigravity.drivingreport.core.database.AppDatabase
import com.antigravity.drivingreport.core.database.PersistentDataManager
import com.antigravity.drivingreport.core.fuel.FuelRepository
import com.antigravity.drivingreport.core.location.DrivingTrackingService
import com.antigravity.drivingreport.core.odometer.OdometerManager
import com.antigravity.drivingreport.core.settings.AppSettingsManager
import com.antigravity.drivingreport.core.trip.GeofenceDiagnostics
import com.antigravity.drivingreport.core.trip.TripGeofenceManager
import com.antigravity.drivingreport.core.trip.TripRepository
import com.antigravity.drivingreport.core.trip.VehicleStateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Enterprise Automotive Full Factory Reset Manager.
 *
 * Truly purges ALL application-owned data and restores initial clean installation state:
 * 1. Stops ongoing tracking service telemetry and unregisters active geofences.
 * 2. Purges Room database: trips, trip points, fuel events, fuel ledger, vehicle state transitions, geofences.
 * 3. Clears all SharedPreferences across settings, fuel, odometer, station prices, last position, AI models.
 * 4. Clears temporary cache directories and internal backups.
 * 5. Reinitializes in-memory repository singletons to fresh factory defaults.
 */
object FullFactoryResetManager {

    suspend fun executeFactoryReset(context: Context) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext

        // 1. Reset Service & Active Telemetry Loop
        try {
            DrivingTrackingService.reset(appContext)
        } catch (_: Exception) {}

        // 2. Clear All Room Database Tables
        val db = AppDatabase.getInstance(appContext)
        db.vehicleDao().clearAllVehicles()
        db.tripDao().clearAllTripPoints()
        db.tripDao().clearAllTrips()
        db.fuelDao().clearAllFuelEvents()
        db.fuelLedgerDao().clearAll()
        db.tripGeofenceDao().clearAllGeofences()
        db.vehicleStateDao().clearAll()

        // 3. Clear Internal Backup Cache & App Cache Directory
        PersistentDataManager.clearBackup(appContext)
        try {
            appContext.cacheDir?.deleteRecursively()
        } catch (_: Exception) {}

        // 4. Wipe All Application SharedPreferences Files
        val prefNames = listOf(
            "driving_report_settings_prefs",
            "driving_report_fuel_prefs",
            "fuel_ai_model_prefs",
            "driving_report_station_prices",
            "driving_report_last_pos",
            "driving_report_odometer_prefs",
            "vehicle_state_prefs",
            "trip_visual_lifecycle_prefs"
        )
        for (name in prefNames) {
            try {
                appContext.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            } catch (_: Exception) {}
        }

        // 5. Reinitialize In-Memory Singletons & StateFlows
        withContext(Dispatchers.Main) {
            TripRepository.getInstance(appContext).resetAllData()
            FuelRepository.getInstance(appContext).resetAllData()
            TripGeofenceManager.getInstance(appContext).resetAllData()
            AppSettingsManager.resetToDefaults(appContext)
            VehicleStateManager.getInstance(appContext).resetAllData()
            OdometerManager.resetAllData(appContext)
            GeofenceDiagnostics.clear()
        }
    }
}
