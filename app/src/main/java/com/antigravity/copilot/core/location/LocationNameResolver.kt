package com.antigravity.copilot.core.location

import android.content.Context
import android.location.Geocoder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.antigravity.copilot.core.database.AppDatabase
import com.antigravity.copilot.core.database.GeoLookupCacheEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

data class FuelStationDetectionResult(
    val isFuelStation: Boolean,
    val stationName: String,
    val brand: String? = null
)

/**
 * Place / pump lookups. Network calls go through [NetworkLookupGate] (setting, rate limit,
 * disconnect) and results are cached per grid cell in Room (audit R1 / H4).
 */
object LocationNameResolver {
    private const val TAG = "LocationNameResolver"
    private const val UA = "Copilot-Cockpit/1.0"

    /** Same 40–60 m strictness as the offline pump check (audit H3). */
    const val FUEL_RADIUS_M = 50
    const val FUEL_MAX_MATCH_M = 60.0
    private const val FUEL_CELL_M = 50.0
    private const val PLACE_CELL_M = 200.0
    private const val POSITIVE_TTL_MS = 30L * 24 * 60 * 60 * 1000
    private const val PLACE_TTL_MS = 180L * 24 * 60 * 60 * 1000
    private const val NEGATIVE_TTL_MS = 24L * 60 * 60 * 1000

    fun isNetworkConnected(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val net = cm?.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(net) ?: return false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                @Suppress("DEPRECATION")
                cm?.activeNetworkInfo?.isConnected == true
            }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun cacheGet(context: Context, key: String, ttlMs: Long): String? = try {
        AppDatabase.getInstance(context).geoLookupCacheDao().get(key)
            ?.takeIf { GeoCell.isFresh(it.updatedAt, System.currentTimeMillis(), ttlMs) }?.value
    } catch (_: Exception) { null }

    private suspend fun cachePut(context: Context, key: String, value: String) {
        try {
            AppDatabase.getInstance(context).geoLookupCacheDao().put(GeoLookupCacheEntity(key, value, System.currentTimeMillis()))
        } catch (_: Exception) {}
    }

    private fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = FloatArray(1)
        android.location.Location.distanceBetween(lat1, lon1, lat2, lon2, r)
        return r[0].toDouble()
    }

    /**
     * Is the car at a fuel station? Online only (offline checks — saved stations and map POIs —
     * run before this): Overpass amenity=fuel within [FUEL_RADIUS_M], then a Nominatim object that
     * is itself tagged as fuel and lies within [FUEL_MAX_MATCH_M]. No keyword guessing.
     */
    suspend fun detectNearbyFuelStation(context: Context, lat: Double, lon: Double): FuelStationDetectionResult {
        if (lat == 0.0 && lon == 0.0) return FuelStationDetectionResult(false, "")
        return withContext(Dispatchers.IO) {
            val key = GeoCell.key("fuel", lat, lon, FUEL_CELL_M)
            cacheGet(context, key, POSITIVE_TTL_MS)?.let { cached ->
                val parts = cached.split('|', limit = 2)
                if (parts[0] == "1") return@withContext FuelStationDetectionResult(true, parts.getOrElse(1) { "Fuel Station" })
                if (cacheGet(context, key, NEGATIVE_TTL_MS) != null) return@withContext FuelStationDetectionResult(false, "")
            }
            if (!NetworkLookupGate.isAllowed(context)) return@withContext FuelStationDetectionResult(false, "")

            var result: FuelStationDetectionResult? = null
            try {
                val query = "[out:json][timeout:3];(node(around:$FUEL_RADIUS_M,$lat,$lon)[amenity=fuel];way(around:$FUEL_RADIUS_M,$lat,$lon)[amenity=fuel];);out center tags 1;"
                NetworkLookupGate.httpGet("https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(query, "UTF-8"), "$UA (Fuel Station Check)")?.let { body ->
                    val elements = JSONObject(body).optJSONArray("elements")
                    if (elements != null && elements.length() > 0) {
                        val tags = elements.getJSONObject(0).optJSONObject("tags")
                        val name = tags?.optString("name", "") ?: ""
                        val brand = tags?.optString("brand", "")?.ifBlank { tags.optString("operator", "") } ?: ""
                        result = FuelStationDetectionResult(
                            true,
                            when {
                                name.isNotBlank() -> name
                                brand.isNotBlank() -> "$brand Fuel Station"
                                else -> "Fuel Station"
                            },
                            brand.ifBlank { null }
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Overpass fuel station check: ${e.message}")
            }

            if (result == null) try {
                NetworkLookupGate.httpGet(
                    "https://nominatim.openstreetmap.org/reverse?format=json&lat=$lat&lon=$lon&zoom=18&addressdetails=1",
                    "$UA (Fuel Station Check)"
                )?.let { resp ->
                    val json = JSONObject(resp)
                    val isFuel = json.optString("type").equals("fuel", true) ||
                        (json.optString("category").equals("amenity", true) && json.optString("type").contains("fuel", true))
                    val oLat = json.optString("lat").toDoubleOrNull()
                    val oLon = json.optString("lon").toDoubleOrNull()
                    if (isFuel && oLat != null && oLon != null && distanceM(lat, lon, oLat, oLon) <= FUEL_MAX_MATCH_M) {
                        result = FuelStationDetectionResult(true, json.optString("name").ifBlank { "Fuel Station" })
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Nominatim fuel check error: ${e.message}")
            }

            val final = result ?: FuelStationDetectionResult(false, "")
            cachePut(context, key, if (final.isFuelStation) "1|${final.stationName}" else "0|")
            final
        }
    }

    /** Station or address label for the refill dialog. */
    suspend fun resolveStationOrAddress(context: Context, lat: Double, lon: Double): String {
        if (lat == 0.0 && lon == 0.0) {
            val defaultRoad = SpeedLimitEngine.getInstance().speedLimitInfo.value.roadName
            return if (defaultRoad.isNotBlank() && defaultRoad != "Navigating") "Near $defaultRoad" else "City Fuel Station"
        }

        return withContext(Dispatchers.IO) {
            // 1. Local Android Geocoder first
            try {
                if (Geocoder.isPresent()) {
                    val geocoder = Geocoder(context, Locale.getDefault())
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(lat, lon, 1)
                    if (!addresses.isNullOrEmpty()) {
                        val addr = addresses[0]
                        val feature = addr.featureName
                        val cleanFeature = if (feature != null && (
                            feature.startsWith("HN", ignoreCase = true) ||
                                feature.contains("PO", ignoreCase = true) ||
                                feature.contains("+") ||
                                feature.all { it.isDigit() || it == '-' || it == '/' || it == '#' }
                            )) null else feature
                        val candidates = listOfNotNull(cleanFeature, addr.thoroughfare, addr.subLocality, addr.locality).filter {
                            it.isNotBlank() && !it.all { char -> char.isDigit() }
                        }
                        if (candidates.isNotEmpty()) return@withContext candidates.distinct().take(3).joinToString(", ")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Geocoder error: ${e.message}")
            }

            // 2. Nominatim (setting + network + rate limit)
            val key = GeoCell.key("addr", lat, lon, FUEL_CELL_M)
            cacheGet(context, key, PLACE_TTL_MS)?.let { return@withContext it }
            if (NetworkLookupGate.isAllowed(context)) {
                try {
                    NetworkLookupGate.httpGet(
                        "https://nominatim.openstreetmap.org/reverse?format=json&lat=$lat&lon=$lon&zoom=18&addressdetails=1",
                        "$UA (Automotive Fuel Station Finder)"
                    )?.let { resp ->
                        val json = JSONObject(resp)
                        val name = json.optString("name", "")
                        val address = json.optJSONObject("address")
                        val amenity = address?.optString("amenity", "") ?: ""
                        val fuel = address?.optString("fuel", "") ?: ""
                        val road = address?.optString("road", "") ?: ""
                        val suburb = address?.optString("suburb", "") ?: ""
                        val city = address?.optString("city", "")?.ifBlank { address.optString("town", "") } ?: ""
                        val stationTitle = when {
                            name.isNotBlank() -> name
                            fuel.isNotBlank() -> "$fuel Fuel Station"
                            amenity.contains("fuel", ignoreCase = true) -> "Fuel Station"
                            else -> ""
                        }
                        val area = when {
                            road.isNotBlank() && suburb.isNotBlank() -> "$road, $suburb"
                            road.isNotBlank() -> road
                            suburb.isNotBlank() -> suburb
                            else -> city
                        }
                        val result = when {
                            stationTitle.isNotBlank() && area.isNotBlank() -> "$stationTitle, $area"
                            stationTitle.isNotBlank() -> stationTitle
                            area.isNotBlank() -> "Near $area"
                            else -> ""
                        }
                        if (result.isNotBlank()) {
                            cachePut(context, key, result)
                            return@withContext result
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Nominatim error: ${e.message}")
                }
            }

            val currentRoad = SpeedLimitEngine.getInstance().speedLimitInfo.value.roadName
            if (currentRoad.isNotBlank() && currentRoad != "Navigating") "Near $currentRoad" else "City Fuel Station"
        }
    }

    /**
     * Place name for a trip origin / destination. A failed lookup returns the coordinates (never
     * an invented place, audit D3); those are retried later by the trip repository.
     */
    suspend fun resolveTripLocation(context: Context, lat: Double, lon: Double): String {
        if (lat == 0.0 && lon == 0.0) return ""
        return resolvePlaceNameOrNull(context, lat, lon) ?: com.antigravity.copilot.core.trip.TripNaming.coordinateName(lat, lon)
    }

    /** Geocoder, then cached / online Nominatim; null when nothing resolves. */
    suspend fun resolvePlaceNameOrNull(context: Context, lat: Double, lon: Double): String? {
        if (lat == 0.0 && lon == 0.0) return null
        return withContext(Dispatchers.IO) {
            try {
                if (Geocoder.isPresent()) {
                    val geocoder = Geocoder(context, Locale.getDefault())
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(lat, lon, 1)
                    if (!addresses.isNullOrEmpty()) {
                        val addr = addresses[0]
                        val parts = listOfNotNull(addr.thoroughfare, addr.subLocality, addr.locality ?: addr.subAdminArea)
                            .filter { it.isNotBlank() && !it.all { c -> c.isDigit() } }
                        if (parts.isNotEmpty()) return@withContext parts.distinct().take(2).joinToString(", ")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Trip Geocoder error: ${e.message}")
            }

            val key = GeoCell.key("place", lat, lon, PLACE_CELL_M)
            cacheGet(context, key, PLACE_TTL_MS)?.let { return@withContext it }
            if (!NetworkLookupGate.isAllowed(context)) return@withContext null
            try {
                NetworkLookupGate.httpGet(
                    "https://nominatim.openstreetmap.org/reverse?format=json&lat=$lat&lon=$lon&zoom=18&addressdetails=1",
                    "$UA (Automotive)"
                )?.let { resp ->
                    val address = JSONObject(resp).optJSONObject("address")
                    val road = address?.optString("road", "") ?: ""
                    val village = address?.optString("village", "") ?: ""
                    val county = address?.optString("county", "") ?: ""
                    val city = address?.optString("city", "")?.ifBlank { address.optString("town", "") }
                        ?.ifBlank { address.optString("state_district", "") } ?: ""
                    val parts = listOf(road, village, county, city).filter { it.isNotBlank() }
                    if (parts.isNotEmpty()) {
                        val name = parts.distinct().take(2).joinToString(", ")
                        cachePut(context, key, name)
                        return@withContext name
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Trip Nominatim error: ${e.message}")
            }
            null
        }
    }
}
