package com.antigravity.drivingreport.core.fuel

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.antigravity.drivingreport.core.database.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * StationPriceManager:
 * Automatically learns, remembers, and updates fuel prices (rates per liter) for every individual
 * fuel station based on GPS geofencing (~250m radius) and station naming.
 *
 * Capabilities:
 * 1. Background learning: Whenever a refuel is recorded or edited, updates the station's known rate.
 * 2. Instant auto-fill: When arriving near a fuel station, automatically pre-fills that station's
 *    rate into the Add Fuel dialog without user intervention.
 * 3. Proximity-based matching: Identifies returning visits to the same pump even if the GPS
 *    reverse-geocoded name varies slightly (e.g., "IOCL Bypass" vs "Indian Oil Corporation").
 * 4. Permanent persistence: Stored in shared preferences and synchronized with permanent backup.
 */
object StationPriceManager {
    private const val TAG = "StationPriceManager"
    private const val PREFS_NAME = "driving_report_station_prices"
    private const val KEY_STATIONS_JSON = "station_price_records"
    private const val MAX_MATCH_DISTANCE_METERS = 280.0 // 280 meter geofence radius for fuel plazas
    private val scope = CoroutineScope(Dispatchers.IO)

    data class StationPriceRecord(
        val stationName: String,
        val latitude: Double,
        val longitude: Double,
        val pricePerLiter: Double,
        val lastUpdated: Long = System.currentTimeMillis()
    )

    @Volatile
    private var cachedStations: MutableList<StationPriceRecord>? = null
    private val lock = Any()

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Loads remembered stations into memory cache if not already loaded.
     */
    private fun loadStations(context: Context): MutableList<StationPriceRecord> {
        cachedStations?.let { return it }
        synchronized(lock) {
            cachedStations?.let { return it }
            val list = mutableListOf<StationPriceRecord>()
            try {
                val jsonStr = getPrefs(context).getString(KEY_STATIONS_JSON, null)
                if (!jsonStr.isNullOrBlank()) {
                    val arr = JSONArray(jsonStr)
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        list.add(
                            StationPriceRecord(
                                stationName = obj.optString("stationName", "Fuel Station"),
                                latitude = obj.optDouble("latitude", 0.0),
                                longitude = obj.optDouble("longitude", 0.0),
                                pricePerLiter = obj.optDouble("pricePerLiter", 96.5),
                                lastUpdated = obj.optLong("lastUpdated", System.currentTimeMillis())
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading station prices", e)
            }

            // Seed from historical fuel events in database if cache is empty
            if (list.isEmpty()) {
                scope.launch {
                    try {
                        val db = AppDatabase.getInstance(context)
                        val events = db.fuelDao().getAllFuelEvents()
                        if (events.isNotEmpty()) {
                            synchronized(lock) {
                                val current = cachedStations ?: mutableListOf()
                                for (e in events) {
                                    if (e.pricePerLiter > 0.0 && (e.latitude != 0.0 || e.longitude != 0.0 || e.locationName.isNotBlank())) {
                                        current.add(
                                            StationPriceRecord(
                                                stationName = e.locationName.ifBlank { "Fuel Station" },
                                                latitude = e.latitude,
                                                longitude = e.longitude,
                                                pricePerLiter = e.pricePerLiter,
                                                lastUpdated = e.timestamp
                                            )
                                        )
                                    }
                                }
                                cachedStations = current
                            }
                            saveStationsAsync(context)
                        }
                    } catch (_: Exception) {}
                }
            }

            cachedStations = list
            return list
        }
    }

    /**
     * Persists memory cache to SharedPreferences in background.
     */
    private fun saveStationsAsync(context: Context) {
        val list = cachedStations ?: return
        scope.launch {
            try {
                val arr = JSONArray()
                synchronized(lock) {
                    for (record in list) {
                        val obj = JSONObject().apply {
                            put("stationName", record.stationName)
                            put("latitude", record.latitude)
                            put("longitude", record.longitude)
                            put("pricePerLiter", record.pricePerLiter)
                            put("lastUpdated", record.lastUpdated)
                        }
                        arr.put(obj)
                    }
                }
                getPrefs(context).edit().putString(KEY_STATIONS_JSON, arr.toString()).apply()
            } catch (e: Exception) {
                Log.e(TAG, "Error saving station prices", e)
            }
        }
    }

    /**
     * Finds the remembered price for a fuel station using GPS proximity (<= 280m)
     * and fallback station name matching.
     * Returns null if no previous record exists for this station.
     */
    suspend fun getRememberedPrice(
        context: Context,
        lat: Double,
        lon: Double,
        stationName: String? = null
    ): Double? = withContext(Dispatchers.IO) {
        val stations = loadStations(context)
        if (stations.isEmpty()) return@withContext null

        synchronized(lock) {
            // 1. Primary Match: Proximity by coordinates (within 280m radius)
            if (lat != 0.0 && lon != 0.0) {
                var closestRecord: StationPriceRecord? = null
                var closestDistance = Double.MAX_VALUE

                for (record in stations) {
                    if (record.latitude != 0.0 && record.longitude != 0.0) {
                        val dist = calculateDistanceMeters(lat, lon, record.latitude, record.longitude)
                        if (dist <= MAX_MATCH_DISTANCE_METERS && dist < closestDistance) {
                            closestDistance = dist
                            closestRecord = record
                        }
                    }
                }

                if (closestRecord != null && closestRecord.pricePerLiter > 0.0) {
                    Log.d(TAG, "Matched station by GPS proximity (${closestDistance.toInt()}m): ${closestRecord.stationName} -> ₹${closestRecord.pricePerLiter}/L")
                    return@withContext closestRecord.pricePerLiter
                }
            }

            // 2. Secondary Match: Station Name matching
            if (!stationName.isNullOrBlank()) {
                val cleanQuery = normalizeName(stationName)
                if (cleanQuery.length >= 4) {
                    val nameMatch = stations
                        .filter { record ->
                            val cleanTarget = normalizeName(record.stationName)
                            cleanTarget.contains(cleanQuery) || cleanQuery.contains(cleanTarget)
                        }
                        .maxByOrNull { it.lastUpdated }

                    if (nameMatch != null && nameMatch.pricePerLiter > 0.0) {
                        Log.d(TAG, "Matched station by name: ${nameMatch.stationName} -> ₹${nameMatch.pricePerLiter}/L")
                        return@withContext nameMatch.pricePerLiter
                    }
                }
            }

            return@withContext null
        }
    }

    /**
     * Learns or updates the price for a station.
     * Called in background whenever user confirms a refuel.
     */
    fun rememberStationPrice(
        context: Context,
        stationName: String,
        lat: Double,
        lon: Double,
        pricePerLiter: Double
    ) {
        if (pricePerLiter <= 0.0 || !pricePerLiter.isFinite()) return

        scope.launch {
            val stations = loadStations(context)
            val now = System.currentTimeMillis()
            val cleanName = stationName.trim().ifBlank { "Fuel Station" }

            synchronized(lock) {
                // Check if this station already exists in records (by proximity or name)
                var existingIndex = -1
                var minDistance = Double.MAX_VALUE

                for (i in stations.indices) {
                    val record = stations[i]
                    if (lat != 0.0 && lon != 0.0 && record.latitude != 0.0 && record.longitude != 0.0) {
                        val dist = calculateDistanceMeters(lat, lon, record.latitude, record.longitude)
                        if (dist <= MAX_MATCH_DISTANCE_METERS && dist < minDistance) {
                            minDistance = dist
                            existingIndex = i
                        }
                    }
                }

                // If not matched by proximity, try exact name match
                if (existingIndex == -1 && cleanName.length >= 4) {
                    val cleanNorm = normalizeName(cleanName)
                    existingIndex = stations.indexOfFirst { normalizeName(it.stationName) == cleanNorm }
                }

                if (existingIndex >= 0) {
                    // Update existing station record with new price and latest coordinates/name
                    val old = stations[existingIndex]
                    stations[existingIndex] = old.copy(
                        stationName = if (cleanName != "Fuel Station") cleanName else old.stationName,
                        latitude = if (lat != 0.0) lat else old.latitude,
                        longitude = if (lon != 0.0) lon else old.longitude,
                        pricePerLiter = pricePerLiter,
                        lastUpdated = now
                    )
                    Log.d(TAG, "Updated existing station price for '${stations[existingIndex].stationName}': ₹$pricePerLiter/L")
                } else {
                    // New station learned
                    stations.add(
                        StationPriceRecord(
                            stationName = cleanName,
                            latitude = lat,
                            longitude = lon,
                            pricePerLiter = pricePerLiter,
                            lastUpdated = now
                        )
                    )
                    Log.d(TAG, "Learned new fuel station: '$cleanName' at ($lat, $lon) -> ₹$pricePerLiter/L")
                }

                // Keep max 150 stations
                if (stations.size > 150) {
                    stations.sortBy { it.lastUpdated }
                    while (stations.size > 150) {
                        stations.removeAt(0)
                    }
                }
            }

            saveStationsAsync(context)
        }
    }

    /**
     * Exports all station price records as a JSON array string for backup.
     */
    fun exportToJsonString(context: Context): String {
        val stations = loadStations(context)
        val arr = JSONArray()
        synchronized(lock) {
            for (record in stations) {
                val obj = JSONObject().apply {
                    put("stationName", record.stationName)
                    put("latitude", record.latitude)
                    put("longitude", record.longitude)
                    put("pricePerLiter", record.pricePerLiter)
                    put("lastUpdated", record.lastUpdated)
                }
                arr.put(obj)
            }
        }
        return arr.toString()
    }

    /**
     * Imports station price records from a backup JSON string.
     */
    fun importFromJsonString(context: Context, jsonStr: String) {
        if (jsonStr.isBlank()) return
        try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<StationPriceRecord>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    StationPriceRecord(
                        stationName = obj.optString("stationName", "Fuel Station"),
                        latitude = obj.optDouble("latitude", 0.0),
                        longitude = obj.optDouble("longitude", 0.0),
                        pricePerLiter = obj.optDouble("pricePerLiter", 96.5),
                        lastUpdated = obj.optLong("lastUpdated", System.currentTimeMillis())
                    )
                )
            }
            synchronized(lock) {
                cachedStations = list
            }
            saveStationsAsync(context)
        } catch (e: Exception) {
            Log.e(TAG, "Error importing station prices from JSON", e)
        }
    }

    /**
     * Clears all remembered station prices.
     */
    fun clearAll(context: Context) {
        synchronized(lock) {
            cachedStations = mutableListOf()
        }
        getPrefs(context).edit().clear().apply()
    }

    // ==================== HELPERS ====================

    private fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1.0 - a))
        return r * c
    }

    private fun normalizeName(name: String): String {
        return name.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]"), "")
            .replace("petrol", "")
            .replace("pump", "")
            .replace("station", "")
            .replace("fuel", "")
    }
}
