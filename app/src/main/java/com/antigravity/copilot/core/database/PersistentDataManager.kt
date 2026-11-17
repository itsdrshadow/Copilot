package com.antigravity.copilot.core.database

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Enterprise Automotive Permanent Data Persistence Manager.
 * Ensures all trip logs, lifetime driving distance, odometer calibrations, fuel refills,
 * and tank capacity parameters permanently survive launcher updates, app reinstalls,
 * and ROM package updates by mirroring snapshots to permanent external storage.
 */
object PersistentDataManager {
    private const val TAG = "PersistentDataManager"
    private const val BACKUP_DIR_NAME = "Copilot"
    private const val LEGACY_BACKUP_DIR_NAME = "DrivingReport"
    private const val BACKUP_FILE_NAME = "permanent_data_store.json"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    const val MIN_BACKUP_INTERVAL_MS = 30_000L
    private var lastBackupTimestamp: Long = 0L
    private val backupLock = Any()

    fun getLastBackupTimestamp(): Long = lastBackupTimestamp

    fun resetBackupThrottleForTesting() {
        synchronized(backupLock) {
            lastBackupTimestamp = 0L
        }
    }

    private fun getBackupFiles(context: Context): List<File> {
        val files = mutableListOf<File>()
        try {
            // 1. Primary permanent storage on external sdcard / documents (survives APK uninstall & reinstall)
            val externalDir = File(Environment.getExternalStorageDirectory(), BACKUP_DIR_NAME)
            if (!externalDir.exists()) externalDir.mkdirs()
            if (externalDir.exists()) {
                files.add(File(externalDir, BACKUP_FILE_NAME))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed creating external backup dir", e)
        }

        try {
            // 2. Direct /sdcard path fallback
            val sdDir = File("/sdcard/$BACKUP_DIR_NAME")
            if (!sdDir.exists()) sdDir.mkdirs()
            if (sdDir.exists() && !files.any { it.absolutePath == File(sdDir, BACKUP_FILE_NAME).absolutePath }) {
                files.add(File(sdDir, BACKUP_FILE_NAME))
            }
        } catch (_: Exception) {}

        try {
            // 3. Reliable app-specific external files dir (guaranteed write access on Android 11+)
            val extDir = context.getExternalFilesDir(null)
            if (extDir != null) {
                if (!extDir.exists()) extDir.mkdirs()
                files.add(File(extDir, BACKUP_FILE_NAME))
            }
        } catch (_: Exception) {}

        try {
            // 4. Legacy DrivingReport fallback path check for migration
            val legacyDir = File("/sdcard/$LEGACY_BACKUP_DIR_NAME")
            val legacyFile = File(legacyDir, BACKUP_FILE_NAME)
            if (legacyFile.exists() && !files.any { it.absolutePath == legacyFile.absolutePath }) {
                files.add(legacyFile)
            }
        } catch (_: Exception) {}

        return files
    }

    /**
     * Checks if this is a fresh launch after a reinstall or data wipe,
     * and automatically restores all previous trips and fuel records.
     */
    fun restoreIfNeeded(context: Context) {
        try {
            val backupFiles = getBackupFiles(context)
            val validFile = backupFiles.firstOrNull { it.exists() && it.length() > 20 } ?: return

            val db = AppDatabase.getInstance(context)
            val fuelPrefs = context.getSharedPreferences("driving_report_fuel_prefs", Context.MODE_PRIVATE)

            val jsonStr = validFile.readText()
            if (jsonStr.isBlank()) return
            val root = JSONObject(jsonStr)

            // Check if Room database is empty (reinstall situation)
            val tripCount = db.openHelper.readableDatabase.compileStatement("SELECT COUNT(*) FROM trips").simpleQueryForLong()
            val fuelEventCount = db.openHelper.readableDatabase.compileStatement("SELECT COUNT(*) FROM fuel_events").simpleQueryForLong()

            if (tripCount == 0L && fuelEventCount == 0L) {
                Log.i(TAG, "Reinstall detected with empty DB. Restoring permanent backup from: ${validFile.absolutePath}")

                // 1. Restore SharedPreferences
                if (root.has("prefs")) {
                    val p = root.getJSONObject("prefs")
                    val edit = fuelPrefs.edit()
                    if (p.has("baseline_odometer")) edit.putFloat("baseline_odometer", p.getDouble("baseline_odometer").toFloat())
                    if (p.has("remaining_liters")) edit.putFloat("remaining_liters", p.getDouble("remaining_liters").toFloat())
                    if (p.has("design_capacity")) edit.putFloat("design_capacity", p.getDouble("design_capacity").toFloat())
                    if (p.has("max_capacity")) edit.putFloat("max_capacity", p.getDouble("max_capacity").toFloat())
                    if (p.has("current_mileage")) edit.putFloat("current_mileage", p.getDouble("current_mileage").toFloat())
                    if (p.has("fuel_price")) edit.putFloat("fuel_price", p.getDouble("fuel_price").toFloat())
                    edit.apply()
                }

                // 1a. Restore App Settings & Vehicle Profile
                if (root.has("app_settings")) {
                    try {
                        val s = root.getJSONObject("app_settings")
                        val settingsPrefs = context.getSharedPreferences("driving_report_settings_prefs", Context.MODE_PRIVATE)
                        val sEdit = settingsPrefs.edit()
                        for (key in s.keys()) {
                            val v = s.get(key)
                            when (v) {
                                is Boolean -> sEdit.putBoolean(key, v)
                                is Int -> sEdit.putInt(key, v)
                                is Long -> sEdit.putLong(key, v)
                                is Double -> sEdit.putFloat(key, v.toFloat())
                                is Float -> sEdit.putFloat(key, v)
                                is String -> sEdit.putString(key, v)
                            }
                        }
                        sEdit.apply()
                        com.antigravity.copilot.core.settings.AppSettingsManager.reload(context)
                    } catch (se: Exception) {
                        Log.w(TAG, "Error restoring app settings: ${se.message}")
                    }
                }

                // 1a2. Restore Last Completed Trip Reference
                if (root.has("last_completed_trip")) {
                    try {
                        val lct = root.getJSONObject("last_completed_trip")
                        val lastTripPrefs = context.getSharedPreferences("last_completed_trip_prefs", Context.MODE_PRIVATE)
                        lastTripPrefs.edit()
                            .putLong("last_completed_trip_id", lct.optLong("last_completed_trip_id", -1L))
                            .putLong("last_completed_trip_time", lct.optLong("last_completed_trip_time", 0L))
                            .apply()
                    } catch (le: Exception) {
                        Log.w(TAG, "Error restoring last completed trip reference: ${le.message}")
                    }
                }

                // 1b. Restore Odometer Calibrations
                val odoPrefs = context.getSharedPreferences("driving_report_odometer_prefs", Context.MODE_PRIVATE)
                if (root.has("odometer_prefs")) {
                    val o = root.getJSONObject("odometer_prefs")
                    val baselineKm = o.optDouble("baseline_odometer_km", 0.0)
                    val lifetimeKm = o.optDouble("baseline_lifetime_km_at_update", 0.0)
                    val verified = o.optBoolean("is_manually_verified", false)
                    odoPrefs.edit()
                        .putLong("baseline_odometer_km_bits", java.lang.Double.doubleToRawLongBits(baselineKm))
                        .putLong("baseline_lifetime_km_at_update_bits", java.lang.Double.doubleToRawLongBits(lifetimeKm))
                        .putFloat("baseline_odometer_km", baselineKm.toFloat())
                        .putFloat("baseline_lifetime_km_at_update", lifetimeKm.toFloat())
                        .putBoolean("is_manually_verified", verified)
                        .apply()
                    com.antigravity.copilot.core.odometer.OdometerManager.init(context)
                } else if (root.has("prefs")) {
                    val p = root.getJSONObject("prefs")
                    if (p.has("baseline_odometer")) {
                        val legacyOdo = p.getDouble("baseline_odometer")
                        if (legacyOdo > 0.0) {
                            com.antigravity.copilot.core.odometer.OdometerManager.setBaselineOdometer(context, legacyOdo, 0.0)
                        }
                    }
                }

                // 2. Restore Trips
                if (root.has("trips")) {
                    val tripsArr = root.getJSONArray("trips")
                    val tripsList = mutableListOf<TripEntity>()
                    for (i in 0 until tripsArr.length()) {
                        val t = tripsArr.getJSONObject(i)
                        // Sanitize corrupted records: non-finite / negative distance or speeds and
                        // reversed timestamps must never reach history lists or analytics maths.
                        val startTime = t.getLong("startTime")
                        val rawEnd = t.optLong("endTime", 0L)
                        val distance = t.optDouble("distanceMeters", 0.0).takeIf { it.isFinite() && it > 0.0 } ?: 0.0
                        tripsList.add(
                            TripEntity(
                                id = t.getLong("id"),
                                vehicleId = t.optInt("vehicleId", 1),
                                startTime = startTime,
                                endTime = if (rawEnd != 0L && rawEnd < startTime) startTime else rawEnd,
                                distanceMeters = distance,
                                drivingDurationMs = t.optLong("drivingDurationMs", 0L).coerceAtLeast(0L),
                                stationaryDurationMs = t.optLong("stationaryDurationMs", 0L).coerceAtLeast(0L),
                                avgSpeedKmh = t.optDouble("avgSpeedKmh", 0.0).takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
                                maxSpeedKmh = t.optDouble("maxSpeedKmh", 0.0).takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
                                startLocationName = t.optString("startLocationName", "Trip Origin"),
                                endLocationName = t.optString("endLocationName", "Trip Destination"),
                                status = t.optString("status", "COMPLETED"),
                                isQualifying = t.optBoolean("isQualifying", true) && distance > 0.0,
                                driverScore = t.optInt("driverScore", 100)
                            )
                        )
                    }

                    if (tripsList.isNotEmpty()) {
                        val sqldb = db.openHelper.writableDatabase
                        sqldb.beginTransaction()
                        try {
                            for (trip in tripsList) {
                                sqldb.execSQL(
                                    "INSERT OR REPLACE INTO trips (id, vehicleId, startTime, endTime, distanceMeters, drivingDurationMs, stationaryDurationMs, avgSpeedKmh, maxSpeedKmh, estimatedFuelLiters, calculatedMileageKmpl, driverScore, startLocationName, endLocationName, status, isQualifying) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                                    arrayOf(
                                        trip.id, trip.vehicleId, trip.startTime, trip.endTime, trip.distanceMeters,
                                        trip.drivingDurationMs, trip.stationaryDurationMs, trip.avgSpeedKmh, trip.maxSpeedKmh,
                                        trip.estimatedFuelLiters, trip.calculatedMileageKmpl, trip.driverScore,
                                        trip.startLocationName, trip.endLocationName, trip.status, if (trip.isQualifying) 1 else 0
                                    )
                                )
                            }
                            sqldb.setTransactionSuccessful()
                        } finally {
                            sqldb.endTransaction()
                        }
                    }
                }

                // 3. Restore Fuel Events
                if (root.has("fuel_events")) {
                    val fuelArr = root.getJSONArray("fuel_events")
                    val fuelList = mutableListOf<FuelEventEntity>()
                    for (i in 0 until fuelArr.length()) {
                        val f = fuelArr.getJSONObject(i)
                        fuelList.add(
                            FuelEventEntity(
                                id = f.optLong("id", 0L),
                                vehicleId = f.optInt("vehicleId", 1),
                                timestamp = f.getLong("timestamp"),
                                eventType = f.optString("eventType", "REFUEL"),
                                liters = f.optDouble("liters", 0.0),
                                midLevelObserved = f.optInt("midLevelObserved", -1),
                                pricePerLiter = f.optDouble("pricePerLiter", 96.5),
                                totalCost = f.optDouble("totalCost", 0.0),
                                calculatedBalanceLiters = f.optDouble("calculatedBalanceLiters", 0.0),
                                notes = f.optString("notes", ""),
                                odometerKm = f.optDouble("odometerKm", 0.0),
                                calculatedMileageKmpl = f.optDouble("calculatedMileageKmpl", 0.0),
                                locationName = f.optString("locationName", ""),
                                latitude = f.optDouble("latitude", 0.0),
                                longitude = f.optDouble("longitude", 0.0)
                            )
                        )
                    }

                    if (fuelList.isNotEmpty()) {
                        val sqldb = db.openHelper.writableDatabase
                        sqldb.beginTransaction()
                        try {
                            for (event in fuelList) {
                                sqldb.execSQL(
                                    "INSERT OR REPLACE INTO fuel_events (id, vehicleId, timestamp, eventType, liters, midLevelObserved, pricePerLiter, totalCost, calculatedBalanceLiters, notes, odometerKm, calculatedMileageKmpl, locationName, latitude, longitude) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                                    arrayOf(
                                        if (event.id > 0) event.id else null, event.vehicleId, event.timestamp, event.eventType,
                                        event.liters, event.midLevelObserved, event.pricePerLiter, event.totalCost,
                                        event.calculatedBalanceLiters, event.notes, event.odometerKm, event.calculatedMileageKmpl,
                                        event.locationName, event.latitude, event.longitude
                                    )
                                )
                            }
                            sqldb.setTransactionSuccessful()
                        } finally {
                            sqldb.endTransaction()
                        }
                    }
                }

                // 3b. Restore Immutable Fuel Ledger Events
                if (root.has("fuel_ledger_events")) {
                    val ledgerArr = root.getJSONArray("fuel_ledger_events")
                    val ledgerList = mutableListOf<FuelLedgerEntity>()
                    for (i in 0 until ledgerArr.length()) {
                        val e = ledgerArr.getJSONObject(i)
                        ledgerList.add(
                            FuelLedgerEntity(
                                id = e.optLong("id", 0L),
                                correlationId = e.optString("correlationId", "restored_$i"),
                                parentId = if (e.has("parentId") && !e.isNull("parentId")) e.getLong("parentId") else null,
                                eventType = e.optString("eventType", "REFUEL"),
                                timestamp = e.optLong("timestamp", System.currentTimeMillis()),
                                odometerKm = e.optDouble("odometerKm", 0.0),
                                fuelQuantityLiters = e.optDouble("fuelQuantityLiters", 0.0),
                                fuelGaugePoints = e.optInt("fuelGaugePoints", -1),
                                pricePerLiter = e.optDouble("pricePerLiter", 0.0),
                                totalAmount = e.optDouble("totalAmount", 0.0),
                                source = e.optString("source", "MANUAL"),
                                confidence = e.optString("confidence", "CONFIRMED"),
                                locationName = e.optString("locationName", ""),
                                latitude = e.optDouble("latitude", 0.0),
                                longitude = e.optDouble("longitude", 0.0),
                                nominalFuelLiters = e.optDouble("nominalFuelLiters", 0.0),
                                conservativeFuelLiters = e.optDouble("conservativeFuelLiters", 0.0),
                                upperFuelLiters = e.optDouble("upperFuelLiters", 0.0),
                                estimatedRangeKm = e.optDouble("estimatedRangeKm", 0.0),
                                estimatedMileageKmpl = e.optDouble("estimatedMileageKmpl", 18.0),
                                confidencePercent = e.optInt("confidencePercent", 80),
                                reasonNote = e.optString("reasonNote", ""),
                                calculationMetadataJson = e.optString("calculationMetadataJson", "")
                            )
                        )
                    }
                    if (ledgerList.isNotEmpty()) {
                        val sqldb = db.openHelper.writableDatabase
                        sqldb.beginTransaction()
                        try {
                            for (entity in ledgerList) {
                                sqldb.execSQL(
                                    "INSERT OR REPLACE INTO fuel_ledger_events (id, correlationId, parentId, eventType, timestamp, odometerKm, fuelQuantityLiters, fuelGaugePoints, pricePerLiter, totalAmount, source, confidence, locationName, latitude, longitude, nominalFuelLiters, conservativeFuelLiters, upperFuelLiters, estimatedRangeKm, estimatedMileageKmpl, confidencePercent, reasonNote, calculationMetadataJson) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                                    arrayOf(
                                        if (entity.id > 0) entity.id else null, entity.correlationId, entity.parentId, entity.eventType,
                                        entity.timestamp, entity.odometerKm, entity.fuelQuantityLiters, entity.fuelGaugePoints,
                                        entity.pricePerLiter, entity.totalAmount, entity.source, entity.confidence,
                                        entity.locationName, entity.latitude, entity.longitude, entity.nominalFuelLiters,
                                        entity.conservativeFuelLiters, entity.upperFuelLiters, entity.estimatedRangeKm,
                                        entity.estimatedMileageKmpl, entity.confidencePercent, entity.reasonNote,
                                        entity.calculationMetadataJson
                                    )
                                )
                            }
                            sqldb.setTransactionSuccessful()
                        } finally {
                            sqldb.endTransaction()
                        }
                    }
                }

                // 4. Restore Remembered Station Prices
                if (root.has("station_prices")) {
                    val stationJson = root.optString("station_prices", "")
                    if (stationJson.isNotBlank()) {
                        com.antigravity.copilot.core.fuel.StationPriceManager.importFromJsonString(context, stationJson)
                    }
                }
                Log.i(TAG, "Permanent backup successfully restored!")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring persistent data backup", e)
        }
    }

    /**
     * Atomically backs up current database state and preferences to permanent storage.
     * Throttles disk writes to minimum 30s interval to prevent automotive NAND flash wear (Feature 23).
     */
    /** Hash of the last backup body actually written (timestamp excluded): unchanged data is not rewritten. */
    @Volatile private var lastWrittenContentHash: Int = 0

    /** Backup paths the OS refused (EACCES / EPERM on scoped storage): not retried in this process. */
    private val deniedTargets: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    fun backupAsync(context: Context, minIntervalMs: Long = MIN_BACKUP_INTERVAL_MS, force: Boolean = false) {
        val now = System.currentTimeMillis()
        synchronized(backupLock) {
            if (!force && (now - lastBackupTimestamp < minIntervalMs)) {
                Log.d(TAG, "backupAsync throttled: ${now - lastBackupTimestamp}ms < ${minIntervalMs}ms")
                return
            }
            lastBackupTimestamp = now
        }
        val appContext = context.applicationContext
        scope.launch {
            try {
                val db = AppDatabase.getInstance(appContext)
                val fuelPrefs = appContext.getSharedPreferences("driving_report_fuel_prefs", Context.MODE_PRIVATE)

                val root = JSONObject()
                root.put("version", 1)
                root.put("backupTimestamp", System.currentTimeMillis())

                // 1. Prefs
                val p = JSONObject()
                p.put("baseline_odometer", fuelPrefs.getFloat("baseline_odometer", 0.0f).toDouble())
                p.put("remaining_liters", fuelPrefs.getFloat("remaining_liters", 48.0f).toDouble())
                p.put("design_capacity", fuelPrefs.getFloat("design_capacity", 48.0f).toDouble())
                p.put("max_capacity", fuelPrefs.getFloat("max_capacity", 48.0f).toDouble())
                p.put("current_mileage", fuelPrefs.getFloat("current_mileage", 18.0f).toDouble())
                p.put("fuel_price", fuelPrefs.getFloat("fuel_price", 96.5f).toDouble())
                root.put("prefs", p)

                // 1b. Odometer Prefs
                val odoPrefs = appContext.getSharedPreferences("driving_report_odometer_prefs", Context.MODE_PRIVATE)
                val odoObj = JSONObject()
                val baselineOdo = if (odoPrefs.contains("baseline_odometer_km_bits")) {
                    java.lang.Double.longBitsToDouble(odoPrefs.getLong("baseline_odometer_km_bits", 0L))
                } else {
                    odoPrefs.getFloat("baseline_odometer_km", 0.0f).toDouble()
                }
                val baselineLifetime = if (odoPrefs.contains("baseline_lifetime_km_at_update_bits")) {
                    java.lang.Double.longBitsToDouble(odoPrefs.getLong("baseline_lifetime_km_at_update_bits", 0L))
                } else {
                    odoPrefs.getFloat("baseline_lifetime_km_at_update", 0.0f).toDouble()
                }
                odoObj.put("baseline_odometer_km", baselineOdo)
                odoObj.put("baseline_lifetime_km_at_update", baselineLifetime)
                odoObj.put("is_manually_verified", odoPrefs.getBoolean("is_manually_verified", false))
                root.put("odometer_prefs", odoObj)

                // 1c. App Settings & Vehicle Profile
                val settings = com.antigravity.copilot.core.settings.AppSettingsManager.settingsState.value
                val sObj = JSONObject()
                sObj.put("screen_always_on", settings.screenAlwaysOn)
                sObj.put("auto_minimize_drawer", settings.autoMinimizeDrawer)
                sObj.put("app_language", settings.language)
                sObj.put("cockpit_theme", settings.cockpitTheme)
                sObj.put("theme_appearance_mode", settings.themeAppearanceMode)
                sObj.put("car_name", settings.carName)
                sObj.put("fuel_type", settings.fuelType)
                sObj.put("tank_capacity_liters", settings.tankCapacityLiters)
                sObj.put("expected_mileage_kmpl", settings.expectedMileageKmpl)
                sObj.put("default_geofence_radius", settings.defaultGeofenceRadiusMeters.toDouble())
                sObj.put("stationary_speed_threshold", settings.stationarySpeedThresholdKmh.toDouble())
                sObj.put("idle_duration_threshold", settings.idleDurationThresholdSeconds)
                sObj.put("auto_trip_detection", settings.autoTripDetection)
                sObj.put("engine_off_detection", settings.engineOffDetection)
                sObj.put("station_stop_watchdog", settings.stationStopWatchdog)
                sObj.put("station_dwell_seconds", settings.stationPromptDwellSeconds)
                sObj.put("missed_refill_threshold", settings.missedRefillThresholdLiters)
                sObj.put("dynamic_speed_limits", settings.dynamicSpeedLimits)
                sObj.put("overspeed_alerts", settings.overspeedAlerts)
                sObj.put("near_limit_warning", settings.nearLimitWarning)
                sObj.put("driver_scoring_enabled", settings.driverScoring)
                sObj.put("map_satellite_mode", settings.mapSatelliteMode)
                sObj.put("map_dark_mode", settings.mapDarkMode)
                sObj.put("map_building_footprints", settings.mapBuildingFootprints)
                sObj.put("auto_day_night_solar", settings.autoDayNightSolar)
                sObj.put("media_pill_enabled", settings.mediaPillEnabled)
                sObj.put("auto_foreground_on_boot", settings.autoForegroundOnBoot)
                root.put("app_settings", sObj)

                // 1d. Last Completed Trip Pointer
                val lastTripPrefs = appContext.getSharedPreferences("last_completed_trip_prefs", Context.MODE_PRIVATE)
                val lctObj = JSONObject()
                lctObj.put("last_completed_trip_id", lastTripPrefs.getLong("last_completed_trip_id", -1L))
                lctObj.put("last_completed_trip_time", lastTripPrefs.getLong("last_completed_trip_time", 0L))
                root.put("last_completed_trip", lctObj)

                // 2. Trips
                val trips = db.tripDao().getAllTrips()
                val tripsArr = JSONArray()
                for (t in trips) {
                    val o = JSONObject()
                    o.put("id", t.id)
                    o.put("vehicleId", t.vehicleId)
                    o.put("startTime", t.startTime)
                    o.put("endTime", t.endTime)
                    o.put("distanceMeters", t.distanceMeters)
                    o.put("drivingDurationMs", t.drivingDurationMs)
                    o.put("stationaryDurationMs", t.stationaryDurationMs)
                    o.put("avgSpeedKmh", t.avgSpeedKmh)
                    o.put("maxSpeedKmh", t.maxSpeedKmh)
                    o.put("startLocationName", t.startLocationName)
                    o.put("endLocationName", t.endLocationName)
                    o.put("status", t.status)
                    o.put("isQualifying", t.isQualifying)
                    o.put("driverScore", t.driverScore)
                    tripsArr.put(o)
                }
                root.put("trips", tripsArr)

                // 3. Fuel Events
                val fuelEvents = db.fuelDao().getAllFuelEvents()
                val fuelArr = JSONArray()
                for (f in fuelEvents) {
                    val o = JSONObject()
                    o.put("id", f.id)
                    o.put("vehicleId", f.vehicleId)
                    o.put("timestamp", f.timestamp)
                    o.put("eventType", f.eventType)
                    o.put("liters", f.liters)
                    o.put("midLevelObserved", f.midLevelObserved)
                    o.put("pricePerLiter", f.pricePerLiter)
                    o.put("totalCost", f.totalCost)
                    o.put("calculatedBalanceLiters", f.calculatedBalanceLiters)
                    o.put("notes", f.notes)
                    o.put("odometerKm", f.odometerKm)
                    o.put("calculatedMileageKmpl", f.calculatedMileageKmpl)
                    o.put("locationName", f.locationName)
                    o.put("latitude", f.latitude)
                    o.put("longitude", f.longitude)
                    fuelArr.put(o)
                }
                root.put("fuel_events", fuelArr)

                // 3b. Fuel Ledger Events
                val ledgerEvents = db.fuelLedgerDao().getAllEventsAsc()
                val ledgerArr = JSONArray()
                for (e in ledgerEvents) {
                    val o = JSONObject()
                    o.put("id", e.id)
                    o.put("correlationId", e.correlationId)
                    if (e.parentId != null) o.put("parentId", e.parentId)
                    o.put("eventType", e.eventType)
                    o.put("timestamp", e.timestamp)
                    o.put("odometerKm", e.odometerKm)
                    o.put("fuelQuantityLiters", e.fuelQuantityLiters)
                    o.put("fuelGaugePoints", e.fuelGaugePoints)
                    o.put("pricePerLiter", e.pricePerLiter)
                    o.put("totalAmount", e.totalAmount)
                    o.put("source", e.source)
                    o.put("confidence", e.confidence)
                    o.put("locationName", e.locationName)
                    o.put("latitude", e.latitude)
                    o.put("longitude", e.longitude)
                    o.put("nominalFuelLiters", e.nominalFuelLiters)
                    o.put("conservativeFuelLiters", e.conservativeFuelLiters)
                    o.put("upperFuelLiters", e.upperFuelLiters)
                    o.put("estimatedRangeKm", e.estimatedRangeKm)
                    o.put("estimatedMileageKmpl", e.estimatedMileageKmpl)
                    o.put("confidencePercent", e.confidencePercent)
                    o.put("reasonNote", e.reasonNote)
                    o.put("calculationMetadataJson", e.calculationMetadataJson)
                    ledgerArr.put(o)
                }
                root.put("fuel_ledger_events", ledgerArr)

                // 4. Station Prices
                val stationJson = com.antigravity.copilot.core.fuel.StationPriceManager.exportToJsonString(appContext)
                root.put("station_prices", stationJson)

                // Skip the disk write entirely when nothing changed since the last backup
                // (the 30 s cadence while driving used to rewrite identical data every time).
                val stamp = root.remove("backupTimestamp")
                val contentHash = root.toString().hashCode()
                root.put("backupTimestamp", stamp)
                if (!force && contentHash == lastWrittenContentHash) {
                    Log.d(TAG, "Backup unchanged; skipping write")
                    return@launch
                }
                val jsonContent = root.toString()
                val targetFiles = getBackupFiles(appContext).filter { it.absolutePath !in deniedTargets }
                for (target in targetFiles) {
                    try {
                        val parent = target.parentFile
                        if (parent != null && !parent.exists()) {
                            parent.mkdirs()
                        }
                        if (parent == null || !parent.exists()) {
                            continue
                        }
                        val tempFile = File(parent, "${target.name}.tmp")
                        FileOutputStream(tempFile).use { fos ->
                            fos.write(jsonContent.toByteArray(Charsets.UTF_8))
                            fos.flush()
                        }
                        if (tempFile.exists()) {
                            if (target.exists()) target.delete()
                            tempFile.renameTo(target)
                        }
                    } catch (fe: java.io.FileNotFoundException) {
                        // Permission denied (scoped storage): remember it, log once, never retry.
                        deniedTargets += target.absolutePath
                        Log.w(TAG, "Backup target not writable, skipping from now on: ${target.absolutePath} (${fe.message})")
                    } catch (fe: Exception) {
                        Log.w(TAG, "Failed writing to target ${target.absolutePath}", fe)
                    }
                }
                lastWrittenContentHash = contentHash
                Log.d(TAG, "Permanent backup saved (${trips.size} trips, ${fuelEvents.size} fuel events, ${ledgerEvents.size} ledger events)")
            } catch (e: Exception) {
                Log.e(TAG, "Error performing backup", e)
            }
        }
    }

    /**
     * Explicitly called only when user enters password @Shadow@511 to reset all data.
     */
    fun clearBackup(context: Context) {
        val targetFiles = getBackupFiles(context)
        for (target in targetFiles) {
            try {
                if (target.exists()) target.delete()
            } catch (_: Exception) {}
        }
        com.antigravity.copilot.core.fuel.StationPriceManager.clearAll(context)
    }
}
