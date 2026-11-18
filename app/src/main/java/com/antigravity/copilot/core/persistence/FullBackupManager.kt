package com.antigravity.copilot.core.persistence

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.room.withTransaction
import com.antigravity.copilot.core.database.*
import com.antigravity.copilot.core.fuel.FuelRepository
import com.antigravity.copilot.core.odometer.OdometerManager
import com.antigravity.copilot.core.settings.AppSettingsManager
import com.antigravity.copilot.core.trip.TripRepository
import com.antigravity.copilot.core.trip.VehicleStateManager
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Enterprise Portable Backup and Restore Engine for DrivingReport Infotainment.
 *
 * 1. Portability: Packages complete application data into a standard ZIP archive.
 * 2. Manifest Verification: Each backup carries a versioned backup_manifest.json.
 * 3. Scope: Trips, GPS Breadcrumbs, Fuel Refill Log, Fuel Ledger Events,
 *    Vehicle Transitions, Preferences, and Offline Map metadata.
 * 4. Safety Guarantee: Creates an automatic safety backup prior to applying any restore.
 * 5. Storage Compliance: Works with internal/external directories and SAF file picker.
 */
object FullBackupManager {
    private const val TAG = "FullBackupManager"
    const val CURRENT_APP_VERSION = "1.18.0"
    const val CURRENT_VERSION_CODE = 33
    const val DATABASE_SCHEMA_VERSION = AppDatabase.DATABASE_SCHEMA_VERSION

    class BackupRestoreException(message: String) : IllegalArgumentException(message)

    fun validateManifest(manifest: BackupManifest) {
        if (manifest.schemaVersion > DATABASE_SCHEMA_VERSION) {
            throw BackupRestoreException("Incompatible schema version: ${manifest.schemaVersion} > $DATABASE_SCHEMA_VERSION. Please update the application before restoring this backup.")
        }
    }

    data class TypedPrefEntry(val type: String, val value: String)

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    data class BackupManifest(
        val appVersion: String = CURRENT_APP_VERSION,
        val versionCode: Int = CURRENT_VERSION_CODE,
        val schemaVersion: Int = DATABASE_SCHEMA_VERSION,
        val timestamp: Long = System.currentTimeMillis(),
        val formattedDate: String = "",
        val tripCount: Int = 0,
        val tripPointCount: Int = 0,
        val fuelEventCount: Int = 0,
        val fuelLedgerCount: Int = 0,
        val vehicleTransitionCount: Int = 0,
        val geofenceCount: Int = 0,
        val hasOfflineMaps: Boolean = false,
        val note: String = "Copilot Automotive Infotainment Backup Archive"
    )

    data class BackupResult(
        val isSuccess: Boolean,
        val backupFile: File? = null,
        val manifest: BackupManifest? = null,
        val errorMessage: String? = null
    )

    data class RestoreResult(
        val isSuccess: Boolean,
        val restoredTrips: Int = 0,
        val restoredPoints: Int = 0,
        val restoredFuelEvents: Int = 0,
        val restoredLedgerEvents: Int = 0,
        val manifest: BackupManifest? = null,
        val errorMessage: String? = null
    )

    /**
     * Resolves the primary directory for saving backup ZIP archives.
     */
    fun getBackupDirectory(context: Context): File {
        // Primary: External Storage /sdcard/Copilot/Backups
        try {
            val externalDir = File(Environment.getExternalStorageDirectory(), "Copilot/Backups")
            if (!externalDir.exists()) externalDir.mkdirs()
            if (externalDir.exists() && externalDir.canWrite()) {
                return externalDir
            }
        } catch (e: Exception) {
            Log.w(TAG, "External storage backup dir inaccessible: ${e.message}")
        }

        // Direct path fallback
        try {
            val sdDir = File("/sdcard/Copilot/Backups")
            if (!sdDir.exists()) sdDir.mkdirs()
            if (sdDir.exists() && sdDir.canWrite()) {
                return sdDir
            }
        } catch (_: Exception) {}

        // Reliable app-specific external files dir fallback (Android 11+)
        val appExtDir = File(context.getExternalFilesDir(null), "Backups")
        if (!appExtDir.exists()) appExtDir.mkdirs()
        return appExtDir
    }

    /**
     * Creates a complete portable ZIP backup archive with manifest.
     */
    suspend fun createFullBackupZip(
        context: Context,
        isSafetyBackup: Boolean = false,
        /** Auto-backups go to an app-private folder (audit N3/N6); null = the user backup folder. */
        targetDir: File? = null,
        filePrefix: String? = null
    ): BackupResult = withContext(Dispatchers.IO) {
        // Tracked outside the try so a failure mid-write still closes the stream and removes the
        // half-written archive (otherwise the fd leaks and a corrupt .zip is left in the backup list).
        var openZip: ZipOutputStream? = null
        var partialZip: File? = null
        try {
            val backupDir = targetDir ?: getBackupDirectory(context)
            if (!backupDir.exists()) backupDir.mkdirs()

            // Verify available storage space (require at least 15 MB)
            val freeBytes = backupDir.usableSpace
            if (freeBytes < 15 * 1024 * 1024) {
                return@withContext BackupResult(
                    isSuccess = false,
                    errorMessage = "Insufficient storage space ($freeBytes bytes available). At least 15 MB required."
                )
            }

            val now = System.currentTimeMillis()
            val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            val readableFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val timeTag = dateFormat.format(Date(now))
            val prefix = filePrefix ?: if (isSafetyBackup) "Safety_PreRestore" else "Copilot_Backup_v${CURRENT_APP_VERSION}"
            val zipFile = File(backupDir, "${prefix}_${timeTag}.zip")

            val db = AppDatabase.getInstance(context)

            // Gather all entities
            val trips = db.tripDao().getAllTrips()
            val tripPoints = db.tripDao().getAllTripPoints()
            val fuelEvents = db.fuelDao().getAllFuelEvents()
            val fuelLedgerEvents = db.fuelLedgerDao().getAllEventsAsc()
            val vehicleTransitions = db.vehicleStateDao().getAllTransitions()
            val geofences = db.tripGeofenceDao().getAllGeofencesList()
            val fuelModelState = db.fuelModelDao().get()
            val roadCells = db.roadCellDao().getAll()
            val roadAnomalies = db.roadAnomalyDao().getAll()
            val fuelStations = db.fuelStationDao().getAll()
            val refuelLogs = db.refuelLogDao().getAllChronological()

            fun encodePrefs(spName: String): Map<String, TypedPrefEntry> {
                val sp = context.getSharedPreferences(spName, Context.MODE_PRIVATE)
                val map = mutableMapOf<String, TypedPrefEntry>()
                for ((k, v) in sp.all) {
                    if (v == null) continue
                    val entry = when (v) {
                        is Boolean -> TypedPrefEntry("BOOLEAN", v.toString())
                        is Int -> TypedPrefEntry("INT", v.toString())
                        is Long -> TypedPrefEntry("LONG", v.toString())
                        is Float -> TypedPrefEntry("FLOAT", v.toString())
                        is String -> TypedPrefEntry("STRING", v)
                        is Set<*> -> TypedPrefEntry("STRING_SET", gson.toJson(v))
                        else -> TypedPrefEntry("STRING", v.toString())
                    }
                    map[k] = entry
                }
                return map
            }

            // Gather all 8 application preference files
            val prefsData = mapOf(
                "settings" to encodePrefs("driving_report_settings_prefs"),
                "fuel" to encodePrefs("driving_report_fuel_prefs"),
                "odometer" to encodePrefs("driving_report_odometer_prefs"),
                "station_prices" to encodePrefs("driving_report_station_prices"),
                "fuel_ai" to encodePrefs("fuel_ai_model_prefs"),
                "vehicle_state" to encodePrefs("vehicle_state_prefs"),
                "trip_lifecycle" to encodePrefs("trip_visual_lifecycle_prefs"),
                "last_pos" to encodePrefs("driving_report_last_pos"),
                "last_completed_trip" to encodePrefs("last_completed_trip_prefs"),
                "india_offline_map" to encodePrefs("india_offline_map_prefs"),
                "lifetime_odometer" to encodePrefs("lifetime_odometer_prefs")
            )

            // Check for India offline map manifest
            val mapManifestFile = File(context.getExternalFilesDir(null), "india_offline_manifest.json")
            val hasOfflineMaps = mapManifestFile.exists() && mapManifestFile.length() > 0

            val manifest = BackupManifest(
                appVersion = CURRENT_APP_VERSION,
                versionCode = CURRENT_VERSION_CODE,
                schemaVersion = DATABASE_SCHEMA_VERSION,
                timestamp = now,
                formattedDate = readableFormat.format(Date(now)),
                tripCount = trips.size,
                tripPointCount = tripPoints.size,
                fuelEventCount = fuelEvents.size,
                fuelLedgerCount = fuelLedgerEvents.size,
                vehicleTransitionCount = vehicleTransitions.size,
                geofenceCount = geofences.size,
                hasOfflineMaps = hasOfflineMaps,
                note = if (isSafetyBackup) "Automatic pre-restore safety snapshot" else "Full portable backup archive"
            )

            // Write ZIP archive
            partialZip = zipFile
            val zos = ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile)))
            openZip = zos

            fun writeZipStringEntry(entryName: String, jsonString: String) {
                val entry = ZipEntry(entryName)
                zos.putNextEntry(entry)
                val bytes = jsonString.toByteArray(Charsets.UTF_8)
                zos.write(bytes, 0, bytes.size)
                zos.closeEntry()
            }

            // 1. Manifest
            writeZipStringEntry("backup_manifest.json", gson.toJson(manifest))

            // 2. Database Entities
            writeZipStringEntry("trips.json", gson.toJson(trips))
            writeZipStringEntry("trip_points.json", gson.toJson(tripPoints))
            writeZipStringEntry("fuel_events.json", gson.toJson(fuelEvents))
            writeZipStringEntry("fuel_ledger_events.json", gson.toJson(fuelLedgerEvents))
            writeZipStringEntry("vehicle_state_transitions.json", gson.toJson(vehicleTransitions))
            writeZipStringEntry("trip_geofences.json", gson.toJson(geofences))
            if (fuelModelState != null) writeZipStringEntry("fuel_model_state.json", gson.toJson(fuelModelState))
            writeZipStringEntry("road_cells.json", gson.toJson(roadCells))
            writeZipStringEntry("road_anomalies.json", gson.toJson(roadAnomalies))
            writeZipStringEntry("fuel_stations.json", gson.toJson(fuelStations))
            writeZipStringEntry("refuel_logs.json", gson.toJson(refuelLogs))

            // 3. SharedPreferences
            writeZipStringEntry("preferences.json", gson.toJson(prefsData))

            // 4. Offline Map Metadata
            if (hasOfflineMaps) {
                try {
                    val mapJson = mapManifestFile.readText(Charsets.UTF_8)
                    writeZipStringEntry("maps/india_offline_manifest.json", mapJson)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed archiving offline map manifest: ${e.message}")
                }
            }

            zos.finish()
            zos.close() // also closes the underlying FileOutputStream
            openZip = null

            // Verify integrity of the created ZIP
            val hasManifest = ZipFile(zipFile).use { it.getEntry("backup_manifest.json") != null }
            if (!hasManifest) {
                zipFile.delete()
                return@withContext BackupResult(isSuccess = false, errorMessage = "Archive verification failed: manifest missing.")
            }
            partialZip = null

            Log.i(TAG, "Backup successfully created: ${zipFile.absolutePath} (${zipFile.length()} bytes)")
            BackupResult(isSuccess = true, backupFile = zipFile, manifest = manifest)
        } catch (e: Exception) {
            Log.e(TAG, "Failed creating backup ZIP: ${e.message}", e)
            try { openZip?.close() } catch (_: Exception) {}
            partialZip?.delete()
            BackupResult(isSuccess = false, errorMessage = e.message ?: "Unknown error creating backup archive.")
        }
    }

    /**
     * Inspects a backup archive from Uri without restoring to extract its manifest.
     */
    suspend fun inspectBackup(context: Context, uri: Uri): BackupManifest? = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
            ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == "backup_manifest.json") {
                        return@withContext gson.fromJson(InputStreamReader(zis, Charsets.UTF_8), BackupManifest::class.java)
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed inspecting backup manifest: ${e.message}")
            null
        }
    }

    /**
     * Restores complete application data from a selected backup ZIP Uri or File.
     */
    suspend fun restoreFromBackupZip(
        context: Context,
        zipUri: Uri,
        /** Startup auto-restore into an empty database: a safety snapshot of nothing is pointless. */
        skipSafetyBackup: Boolean = false
    ): RestoreResult = withContext(Dispatchers.IO) {
        var tempFile: File? = null
        var openArchive: ZipFile? = null
        try {
            // 1. Copy incoming stream to a temporary working file
            tempFile = File(context.cacheDir, "restore_temp_${System.currentTimeMillis()}.zip")
            context.contentResolver.openInputStream(zipUri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext RestoreResult(isSuccess = false, errorMessage = "Unable to open backup file.")

            if (!tempFile.exists() || tempFile.length() == 0L) {
                return@withContext RestoreResult(isSuccess = false, errorMessage = "Backup file is empty or corrupted.")
            }

            // 2. Validate archive integrity and read manifest
            val zipFile = ZipFile(tempFile)
            openArchive = zipFile
            val manifestEntry = zipFile.getEntry("backup_manifest.json")
                ?: run {
                    zipFile.close()
                    return@withContext RestoreResult(isSuccess = false, errorMessage = "Invalid backup archive: backup_manifest.json not found.")
                }

            val manifestText = zipFile.getInputStream(manifestEntry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val manifest = gson.fromJson(manifestText, BackupManifest::class.java)
                ?: run {
                    zipFile.close()
                    return@withContext RestoreResult(isSuccess = false, errorMessage = "Corrupted backup manifest.")
                }

            // Feature 20: Validate schema version before touching database or preferences
            try {
                validateManifest(manifest)
            } catch (e: Exception) {
                zipFile.close()
                return@withContext RestoreResult(
                    isSuccess = false,
                    errorMessage = e.message ?: "Incompatible schema version: ${manifest.schemaVersion}",
                    manifest = manifest
                )
            }

            // 3. Create an automatic safety backup of existing data prior to overwriting
            if (!skipSafetyBackup) {
                val safetyResult = createFullBackupZip(context, isSafetyBackup = true)
                Log.i(TAG, "Pre-restore safety backup created: ${safetyResult.backupFile?.name}")
            }

            // 4. Extract data from ZIP
            fun readEntryString(entryName: String): String? {
                val entry = zipFile.getEntry(entryName) ?: return null
                return zipFile.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }

            val tripsJson = readEntryString("trips.json")
            val pointsJson = readEntryString("trip_points.json")
            val fuelEventsJson = readEntryString("fuel_events.json")
            val fuelLedgerJson = readEntryString("fuel_ledger_events.json")
            val transitionsJson = readEntryString("vehicle_state_transitions.json")
            val geofencesJson = readEntryString("trip_geofences.json")
            val fuelModelJson = readEntryString("fuel_model_state.json")
            val roadCellsJson = readEntryString("road_cells.json")
            val roadAnomaliesJson = readEntryString("road_anomalies.json")
            val fuelStationsJson = readEntryString("fuel_stations.json")
            val refuelLogsJson = readEntryString("refuel_logs.json")

            val prefsJson = readEntryString("preferences.json")
            val mapManifestJson = readEntryString("maps/india_offline_manifest.json")

            zipFile.close()

            // Deserialization validation: deserialize in-memory FIRST before any DB clear
            val tripType = object : TypeToken<List<TripEntity>>() {}.type
            val tripsList: List<TripEntity>? = if (!tripsJson.isNullOrBlank()) {
                gson.fromJson<List<TripEntity>>(tripsJson, tripType)?.map { it.sanitizedForRestore() }
            } else null

            val pointType = object : TypeToken<List<TripPointEntity>>() {}.type
            val pointsList: List<TripPointEntity>? = if (!pointsJson.isNullOrBlank()) gson.fromJson(pointsJson, pointType) else null

            val fuelType = object : TypeToken<List<FuelEventEntity>>() {}.type
            // Archives older than schema 17 lack fullTank / gaugePoint; Gson would fill 0 / 0.0
            // ("not full", "gauge at empty"), so mark them as not recorded (-1) first.
            val fuelList: List<FuelEventEntity>? = if (!fuelEventsJson.isNullOrBlank()) gson.fromJson(withLegacyFuelDefaults(fuelEventsJson), fuelType) else null

            val ledgerType = object : TypeToken<List<FuelLedgerEntity>>() {}.type
            val ledgerList: List<FuelLedgerEntity>? = if (!fuelLedgerJson.isNullOrBlank()) gson.fromJson(fuelLedgerJson, ledgerType) else null

            val transType = object : TypeToken<List<VehicleStateTransitionEntity>>() {}.type
            val transList: List<VehicleStateTransitionEntity>? = if (!transitionsJson.isNullOrBlank()) gson.fromJson(transitionsJson, transType) else null

            val geofenceType = object : TypeToken<List<TripGeofenceEntity>>() {}.type
            val geofencesList: List<TripGeofenceEntity>? = if (!geofencesJson.isNullOrBlank()) gson.fromJson(geofencesJson, geofenceType) else null
            val fuelModelEntity: com.antigravity.copilot.core.database.FuelModelStateEntity? =
                if (!fuelModelJson.isNullOrBlank()) gson.fromJson(fuelModelJson, com.antigravity.copilot.core.database.FuelModelStateEntity::class.java) else null

            val db = AppDatabase.getInstance(context)

            // 5. Restore Database Entities atomically inside a transaction (Feature 19)
            var restoredTrips = 0
            var restoredPoints = 0
            var restoredFuel = 0
            var restoredLedger = 0
            val stationsToRestore: List<com.antigravity.copilot.core.fuel.station.FuelStationEntity>?
            val logsToRestore: List<com.antigravity.copilot.core.fuel.station.RefuelLogEntity>?
            if (!fuelStationsJson.isNullOrBlank() || !refuelLogsJson.isNullOrBlank()) {
                val stationType = object : TypeToken<List<com.antigravity.copilot.core.fuel.station.FuelStationEntity>>() {}.type
                val logType = object : TypeToken<List<com.antigravity.copilot.core.fuel.station.RefuelLogEntity>>() {}.type
                stationsToRestore = fuelStationsJson?.takeIf { it.isNotBlank() }?.let { gson.fromJson(it, stationType) }
                logsToRestore = refuelLogsJson?.takeIf { it.isNotBlank() }?.let { gson.fromJson(it, logType) }
            } else {
                stationsToRestore = null
                logsToRestore = null
            }
            val hasLifetimePrefs = prefsJson?.contains("\"lifetime_odometer\"") == true

            // Live tracking is paused for the whole replace (audit N2): no late trip flush can
            // write the old active trip into the restored database.
            com.antigravity.copilot.core.location.TrackingMaintenance.runPaused(context) {
            db.withTransaction {
                // Trips & Points
                if (tripsList != null) {
                    db.tripDao().clearAllTrips()
                    db.tripDao().insertTrips(tripsList)
                    restoredTrips = tripsList.size
                }

                if (pointsList != null) {
                    db.tripDao().clearAllTripPoints()
                    db.tripDao().insertPoints(pointsList)
                    restoredPoints = pointsList.size
                }

                // Fuel Events
                if (fuelList != null) {
                    db.fuelDao().clearAllFuelEvents()
                    db.fuelDao().insertFuelEvents(fuelList)
                    restoredFuel = fuelList.size
                }

                // Fuel Ledger Events
                if (ledgerList != null) {
                    db.fuelLedgerDao().clearAll()
                    db.fuelLedgerDao().insertEvents(ledgerList)
                    restoredLedger = ledgerList.size
                }

                // Vehicle State Transitions
                if (transList != null) {
                    db.vehicleStateDao().clearAll()
                    db.vehicleStateDao().insertTransitions(transList)
                }

                // Trip Geofences
                if (geofencesList != null) {
                    db.tripGeofenceDao().clearAllGeofences()
                    db.tripGeofenceDao().insertGeofences(geofencesList)
                }

                // Fuel model (anchor, gauge curve observations, mileage segments, unlogged entries)
                if (fuelModelEntity != null && fuelModelEntity.json.isNotBlank()) {
                    db.fuelModelDao().upsert(fuelModelEntity)
                }
                if (!roadCellsJson.isNullOrBlank()) {
                    val type = object : TypeToken<List<com.antigravity.copilot.core.database.RoadCellEntity>>() {}.type
                    val cells: List<com.antigravity.copilot.core.database.RoadCellEntity>? = gson.fromJson(roadCellsJson, type)
                    if (cells != null) {
                        db.roadCellDao().clearAll()
                        db.roadCellDao().upsertAll(cells)
                    }
                }
                if (!roadAnomaliesJson.isNullOrBlank()) {
                    val type = object : TypeToken<List<com.antigravity.copilot.core.road.RoadAnomalyEntity>>() {}.type
                    val rows: List<com.antigravity.copilot.core.road.RoadAnomalyEntity>? = gson.fromJson(roadAnomaliesJson, type)
                    if (rows != null) {
                        db.roadAnomalyDao().clearAll()
                        db.roadAnomalyDao().insertAll(rows)
                    }
                }
            }

            // Station tables are restored after the transaction (audit H5): the station repository
            // takes its own mutex, and waiting on it while holding the Room transaction could
            // deadlock against a station write that is itself waiting for the database.
            if (stationsToRestore != null || logsToRestore != null) {
                com.antigravity.copilot.core.fuel.station.FuelStationRepository.getInstance(context).restore(stationsToRestore, logsToRestore)
            }

            // 6. Restore SharedPreferences
            if (!prefsJson.isNullOrBlank()) {
                try {
                    val jsonTree = JsonParser.parseString(prefsJson).asJsonObject
                    for ((prefKey, prefFileElem) in jsonTree.entrySet()) {
                        if (!prefFileElem.isJsonObject) continue
                        val targetPrefName = when (prefKey) {
                            "settings" -> "driving_report_settings_prefs"
                            "fuel" -> "driving_report_fuel_prefs"
                            "odometer" -> "driving_report_odometer_prefs"
                            "station_prices" -> "driving_report_station_prices"
                            "fuel_ai" -> "fuel_ai_model_prefs"
                            "vehicle_state" -> "vehicle_state_prefs"
                            "trip_lifecycle" -> "trip_visual_lifecycle_prefs"
                            "last_pos" -> "driving_report_last_pos"
                            "last_completed_trip" -> "last_completed_trip_prefs"
                            "india_offline_map" -> "india_offline_map_prefs"
                            else -> if (prefKey.startsWith("driving_report_") || prefKey.endsWith("_prefs")) prefKey else "driving_report_${prefKey}_prefs"
                        }
                        val sp = context.getSharedPreferences(targetPrefName, Context.MODE_PRIVATE)
                        // Untyped legacy entries keep the type the app already uses for that key.
                        val existingTypes = sp.all
                        val editor = sp.edit()
                        val entriesObj = prefFileElem.asJsonObject
                        for ((key, valElem) in entriesObj.entrySet()) {
                            if (valElem.isJsonObject && valElem.asJsonObject.has("type") && valElem.asJsonObject.has("value")) {
                                val type = valElem.asJsonObject.get("type").asString
                                val strVal = valElem.asJsonObject.get("value").asString
                                when (type) {
                                    "BOOLEAN" -> editor.putBoolean(key, strVal.toBoolean())
                                    "INT" -> editor.putInt(key, strVal.toIntOrNull() ?: 0)
                                    "LONG" -> editor.putLong(key, strVal.toLongOrNull() ?: 0L)
                                    "FLOAT" -> editor.putFloat(key, strVal.toFloatOrNull() ?: 0f)
                                    "STRING" -> editor.putString(key, strVal)
                                    "STRING_SET" -> {
                                        try {
                                            val setType = object : TypeToken<Set<String>>() {}.type
                                            val set: Set<String> = gson.fromJson(strVal, setType)
                                            editor.putStringSet(key, set)
                                        } catch (_: Exception) {}
                                    }
                                }
                            } else if (valElem.isJsonPrimitive) {
                                // Legacy untyped entry (audit N4): use the type the key already has
                                // on this device, else a lossless guess (never truncate a Long to Int).
                                when (val restored = LegacyPrefTyping.coerce(key, valElem.asJsonPrimitive.asString, valElem.asJsonPrimitive.isBoolean, valElem.asJsonPrimitive.isNumber, existingTypes[key])) {
                                    is Boolean -> editor.putBoolean(key, restored)
                                    is Int -> editor.putInt(key, restored)
                                    is Long -> editor.putLong(key, restored)
                                    is Float -> editor.putFloat(key, restored)
                                    is String -> editor.putString(key, restored)
                                }
                            }
                        }
                        editor.commit()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error restoring preferences: ${e.message}")
                }
            }
            }

            // 7. Restore offline map manifest
            if (!mapManifestJson.isNullOrBlank()) {
                try {
                    val mapManifestFile = File(context.getExternalFilesDir(null), "india_offline_manifest.json")
                    mapManifestFile.writeText(mapManifestJson, Charsets.UTF_8)
                } catch (e: Exception) {
                    Log.w(TAG, "Error restoring offline map manifest: ${e.message}")
                }
            }

            // 8. Refresh App Repositories and in-memory singletons (Feature 21)
            try {
                TripRepository.getInstance(context).reloadFromDatabase()
            } catch (e: Exception) {
                Log.w(TAG, "Error reloading TripRepository: ${e.message}")
            }
            try {
                OdometerManager.reloadFromPreferences(context)
            } catch (e: Exception) {
                Log.w(TAG, "Error reloading OdometerManager: ${e.message}")
            }
            try {
                AppSettingsManager.reload(context)
                AppSettingsManager.migrateFromLegacyPreferences(context)
            } catch (e: Exception) {
                Log.w(TAG, "Error reloading AppSettingsManager: ${e.message}")
            }
            try {
                VehicleStateManager.getInstance(context).reloadFromDatabase()
            } catch (e: Exception) {
                Log.w(TAG, "Error reloading VehicleStateManager: ${e.message}")
            }
            try {
                // Restored model row + prefs replace the in-memory fuel state (audit N1).
                FuelRepository.getInstance(context).reloadAfterRestore(hasModelRow = fuelModelEntity != null && fuelModelEntity.json.isNotBlank())
            } catch (e: Exception) {
                Log.w(TAG, "Error reloading FuelRepository: ${e.message}")
            }
            try {
                val odo = com.antigravity.copilot.core.odometer.LifetimeOdometer
                if (hasLifetimePrefs) {
                    odo.reloadFromPrefs(context)
                } else {
                    // Older archive: rebuild the lifetime distance from the restored trips.
                    val dao = db.tripDao()
                    val activeM = dao.getActiveTrip()?.takeIf { it.endTime <= 0L }?.distanceMeters ?: 0.0
                    odo.resetTo((dao.sumQualifyingDistanceMeters() + activeM) / 1000.0)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error reloading LifetimeOdometer: ${e.message}")
            }

            Log.i(TAG, "Restore successfully completed: $restoredTrips trips, $restoredFuel refills.")
            RestoreResult(
                isSuccess = true,
                restoredTrips = restoredTrips,
                restoredPoints = restoredPoints,
                restoredFuelEvents = restoredFuel,
                restoredLedgerEvents = restoredLedger,
                manifest = manifest
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error during restore from ZIP: ${e.message}", e)
            RestoreResult(isSuccess = false, errorMessage = e.message ?: "Unknown restore error.")
        } finally {
            try { openArchive?.close() } catch (_: Exception) {}
            tempFile?.delete()
        }
    }
    /** Adds fullTank = -1 / gaugePoint = -1.0 to fuel_events rows from archives that predate them. */
    internal fun withLegacyFuelDefaults(json: String): String {
        val tree = JsonParser.parseString(json)
        if (!tree.isJsonArray) return json
        for (el in tree.asJsonArray) {
            if (!el.isJsonObject) continue
            val o = el.asJsonObject
            if (!o.has("fullTank")) o.addProperty("fullTank", -1)
            if (!o.has("gaugePoint")) o.addProperty("gaugePoint", -1.0)
        }
        return tree.toString()
    }

    /**
     * Gson bypasses Kotlin null-safety and defaults: older or hand-edited archives can carry null
     * strings (NOT NULL violation → whole restore rolls back) or corrupt numbers. Normalise them.
     */
    @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
    private fun TripEntity.sanitizedForRestore(): TripEntity {
        val safeDistance = distanceMeters.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        return copy(
            startLocationName = startLocationName ?: "",
            endLocationName = endLocationName ?: "",
            status = status ?: "COMPLETED",
            endTime = if (endTime != 0L && endTime < startTime) startTime else endTime,
            distanceMeters = safeDistance,
            drivingDurationMs = drivingDurationMs.coerceAtLeast(0L),
            stationaryDurationMs = stationaryDurationMs.coerceAtLeast(0L),
            avgSpeedKmh = avgSpeedKmh.takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
            maxSpeedKmh = maxSpeedKmh.takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
            isQualifying = isQualifying && safeDistance > 0.0
        )
    }
}
