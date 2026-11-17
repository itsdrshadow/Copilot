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
        isSafetyBackup: Boolean = false
    ): BackupResult = withContext(Dispatchers.IO) {
        try {
            val backupDir = getBackupDirectory(context)
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
            val prefix = if (isSafetyBackup) "Safety_PreRestore" else "Copilot_Backup_v${CURRENT_APP_VERSION}"
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
                "india_offline_map" to encodePrefs("india_offline_map_prefs")
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
            val fos = FileOutputStream(zipFile)
            val zos = ZipOutputStream(BufferedOutputStream(fos))

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
            zos.close()
            fos.close()

            // Verify integrity of the created ZIP
            val verifyZip = ZipFile(zipFile)
            val manifestEntry = verifyZip.getEntry("backup_manifest.json")
            if (manifestEntry == null) {
                verifyZip.close()
                zipFile.delete()
                return@withContext BackupResult(isSuccess = false, errorMessage = "Archive verification failed: manifest missing.")
            }
            verifyZip.close()

            Log.i(TAG, "Backup successfully created: ${zipFile.absolutePath} (${zipFile.length()} bytes)")
            BackupResult(isSuccess = true, backupFile = zipFile, manifest = manifest)
        } catch (e: Exception) {
            Log.e(TAG, "Failed creating backup ZIP: ${e.message}", e)
            BackupResult(isSuccess = false, errorMessage = e.message ?: "Unknown error creating backup archive.")
        }
    }

    /**
     * Inspects a backup archive from Uri without restoring to extract its manifest.
     */
    suspend fun inspectBackup(context: Context, uri: Uri): BackupManifest? = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
            val zis = ZipInputStream(BufferedInputStream(inputStream))
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == "backup_manifest.json") {
                    val reader = InputStreamReader(zis, Charsets.UTF_8)
                    val manifest = gson.fromJson(reader, BackupManifest::class.java)
                    zis.close()
                    return@withContext manifest
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
            zis.close()
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed inspecting backup manifest: ${e.message}")
            null
        }
    }

    /**
     * Restores complete application data from a selected backup ZIP Uri or File.
     */
    suspend fun restoreFromBackupZip(context: Context, zipUri: Uri): RestoreResult = withContext(Dispatchers.IO) {
        var tempFile: File? = null
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
            val safetyResult = createFullBackupZip(context, isSafetyBackup = true)
            Log.i(TAG, "Pre-restore safety backup created: ${safetyResult.backupFile?.name}")

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

            val prefsJson = readEntryString("preferences.json")
            val mapManifestJson = readEntryString("maps/india_offline_manifest.json")

            zipFile.close()

            // Deserialization validation: deserialize in-memory FIRST before any DB clear
            val tripType = object : TypeToken<List<TripEntity>>() {}.type
            val tripsList: List<TripEntity>? = if (!tripsJson.isNullOrBlank()) gson.fromJson(tripsJson, tripType) else null

            val pointType = object : TypeToken<List<TripPointEntity>>() {}.type
            val pointsList: List<TripPointEntity>? = if (!pointsJson.isNullOrBlank()) gson.fromJson(pointsJson, pointType) else null

            val fuelType = object : TypeToken<List<FuelEventEntity>>() {}.type
            val fuelList: List<FuelEventEntity>? = if (!fuelEventsJson.isNullOrBlank()) gson.fromJson(fuelEventsJson, fuelType) else null

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
                                val prim = valElem.asJsonPrimitive
                                if (prim.isBoolean) {
                                    editor.putBoolean(key, prim.asBoolean)
                                } else if (prim.isString) {
                                    editor.putString(key, prim.asString)
                                } else if (prim.isNumber) {
                                    val lowerKey = key.lowercase()
                                    if (lowerKey.endsWith("_timestamp") || lowerKey.endsWith("_time") || lowerKey == "timestamp" || lowerKey.endsWith("_id") || lowerKey == "id" || lowerKey.endsWith("_bits")) {
                                        editor.putLong(key, prim.asLong)
                                    } else if (lowerKey.contains("count") || lowerKey == "theme" || lowerKey == "model_confidence" || !prim.asString.contains(".")) {
                                        editor.putInt(key, prim.asInt)
                                    } else {
                                        editor.putFloat(key, prim.asFloat)
                                    }
                                }
                            }
                        }
                        editor.apply()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error restoring preferences: ${e.message}")
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
                FuelRepository.getInstance(context).rehydrateLedgerAsync()
            } catch (e: Exception) {
                Log.w(TAG, "Error reloading FuelRepository: ${e.message}")
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
            tempFile?.delete()
        }
    }
}
