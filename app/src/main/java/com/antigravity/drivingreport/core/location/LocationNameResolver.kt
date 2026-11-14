package com.antigravity.drivingreport.core.location

import android.content.Context
import android.location.Geocoder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

data class FuelStationDetectionResult(
    val isFuelStation: Boolean,
    val stationName: String,
    val brand: String? = null
)

object LocationNameResolver {
    private const val TAG = "LocationNameResolver"

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

    /**
     * Specifically detects if coordinates are at or near a Fuel Station (Petrol Pump / CNG / Diesel).
     * Interrogates Overpass API (amenity=fuel within 180m), Nominatim, and Android Geocoder.
     */
    suspend fun detectNearbyFuelStation(context: Context, lat: Double, lon: Double): FuelStationDetectionResult {
        if (lat == 0.0 && lon == 0.0) return FuelStationDetectionResult(false, "")

        return withContext(Dispatchers.IO) {
            val hasNetwork = isNetworkConnected(context)

            // 1. Check Overpass API for explicit amenity=fuel within 180 meters (only if online)
            if (hasNetwork) {
                try {
                    val query = "[out:json][timeout:2];(node(around:180,$lat,$lon)[amenity=fuel];way(around:180,$lat,$lon)[amenity=fuel];node(around:180,$lat,$lon)[\"fuel:cng\"=yes];);out center tags 1;"
                    val encodedQuery = URLEncoder.encode(query, "UTF-8")
                    val overpassUrl = URL("https://overpass-api.de/api/interpreter?data=$encodedQuery")
                    val conn = (overpassUrl.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 1200
                        readTimeout = 1200
                        setRequestProperty("User-Agent", "DrivingReport-Cockpit/1.0 (Fuel Station Check)")
                    }
                    if (conn.responseCode == 200) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val root = JSONObject(body)
                        val elements = root.optJSONArray("elements")
                        if (elements != null && elements.length() > 0) {
                            val elem = elements.getJSONObject(0)
                            val tags = elem.optJSONObject("tags")
                            val name = tags?.optString("name", "") ?: ""
                            val brand = tags?.optString("brand", "") ?: tags?.optString("operator", "") ?: ""
                            val fuelTitle = when {
                                name.isNotBlank() -> name
                                brand.isNotBlank() -> "$brand Fuel Plaza"
                                else -> "Petrol Pump / Fuel Station"
                            }
                            return@withContext FuelStationDetectionResult(
                                isFuelStation = true,
                                stationName = fuelTitle,
                                brand = brand.ifBlank { null }
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Overpass fuel station check: ${e.message}")
                }

                // 2. Check OpenStreetMap Nominatim reverse geocode (zoom 18)
                try {
                    val url = URL("https://nominatim.openstreetmap.org/reverse?format=json&lat=$lat&lon=$lon&zoom=18&addressdetails=1")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 1200
                        readTimeout = 1200
                        setRequestProperty("User-Agent", "DrivingReport-Cockpit/1.0 (Fuel Station Check)")
                    }
                    if (conn.responseCode == 200) {
                        val resp = conn.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(resp)
                        val category = json.optString("category", "")
                        val type = json.optString("type", "")
                        val name = json.optString("name", "")
                        val displayName = json.optString("display_name", "")
                        val address = json.optJSONObject("address")
                        val amenity = address?.optString("amenity", "") ?: ""
                        val fuel = address?.optString("fuel", "") ?: ""

                        val isFuelAmenity = amenity.equals("fuel", ignoreCase = true) ||
                                           fuel.isNotBlank() ||
                                           type.equals("fuel", ignoreCase = true) ||
                                           (category.equals("amenity", ignoreCase = true) && type.contains("fuel", ignoreCase = true))

                        val hasFuelKeywords = listOf(
                            "petrol", "fuel", "diesel", "gas station", "filling station",
                            "indianoil", "iocl", "bpcl", "hpcl", "nayara", "reliance petroleum", "cng"
                        ).any {
                            displayName.contains(it, ignoreCase = true) || name.contains(it, ignoreCase = true)
                        }

                        if (isFuelAmenity || hasFuelKeywords) {
                            val stationTitle = when {
                                name.isNotBlank() -> name
                                fuel.isNotBlank() -> "$fuel Station"
                                amenity.isNotBlank() -> "Fuel Station"
                                else -> "Petrol Pump"
                            }
                            return@withContext FuelStationDetectionResult(
                                isFuelStation = true,
                                stationName = stationTitle
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Nominatim fuel check error: ${e.message}")
                }
            }

            // 3. Android Geocoder fallback
            try {
                if (Geocoder.isPresent()) {
                    val geocoder = Geocoder(context, Locale.getDefault())
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(lat, lon, 2)
                    if (!addresses.isNullOrEmpty()) {
                        for (addr in addresses) {
                            val feature = addr.featureName ?: ""
                            val thoroughfare = addr.thoroughfare ?: ""
                            val subLoc = addr.subLocality ?: ""
                            val text = "$feature $thoroughfare $subLoc".lowercase(Locale.ROOT)
                            if (listOf("petrol", "fuel", "pump", "diesel", "indianoil", "iocl", "bpcl", "hpcl", "nayara", "cng").any { text.contains(it) }) {
                                val name = feature.ifBlank { thoroughfare.ifBlank { "Fuel Station" } }
                                return@withContext FuelStationDetectionResult(
                                    isFuelStation = true,
                                    stationName = name
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Geocoder fuel check error: ${e.message}")
            }

            FuelStationDetectionResult(isFuelStation = false, stationName = "")
        }
    }

    /**
     * Resolves human-readable station name or address from coordinates.
     * Prioritizes Fuel Station / Amenity naming, then road/suburb/city.
     */
    suspend fun resolveStationOrAddress(context: Context, lat: Double, lon: Double): String {
        if (lat == 0.0 && lon == 0.0) {
            val defaultRoad = SpeedLimitEngine.getInstance().speedLimitInfo.value.roadName
            return if (defaultRoad.isNotBlank() && defaultRoad != "Navigating") "Near $defaultRoad" else "City Fuel Station"
        }

        return withContext(Dispatchers.IO) {
            // 1. Try local Android Geocoder first
            try {
                if (Geocoder.isPresent()) {
                    val geocoder = Geocoder(context, Locale.getDefault())
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(lat, lon, 1)
                    if (!addresses.isNullOrEmpty()) {
                        val addr = addresses[0]
                        val feature = addr.featureName
                        val road = addr.thoroughfare
                        val subLocality = addr.subLocality
                        val locality = addr.locality

                        // Filter out raw house numbers, "HN", "PO", plus codes
                        val cleanFeature = if (feature != null && (
                            feature.startsWith("HN", ignoreCase = true) ||
                            feature.contains("PO", ignoreCase = true) ||
                            feature.contains("+") ||
                            feature.all { it.isDigit() || it == '-' || it == '/' || it == '#' }
                        )) null else feature

                        val candidates = listOfNotNull(cleanFeature, road, subLocality, locality).filter {
                            it.isNotBlank() && !it.all { char -> char.isDigit() }
                        }
                        if (candidates.isNotEmpty()) {
                            val combined = candidates.distinct().take(3).joinToString(", ")
                            if (combined.isNotBlank()) {
                                return@withContext combined
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Geocoder error: ${e.message}")
            }

            // 2. Try OpenStreetMap Nominatim reverse geocoder (only if online)
            if (isNetworkConnected(context)) {
                try {
                    val url = URL("https://nominatim.openstreetmap.org/reverse?format=json&lat=$lat&lon=$lon&zoom=18&addressdetails=1")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 1200
                        readTimeout = 1200
                        setRequestProperty("User-Agent", "DrivingReport-Cockpit/1.0 (Automotive Fuel Station Finder)")
                    }

                if (conn.responseCode == 200) {
                    val resp = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(resp)
                    val name = json.optString("name", "")
                    val address = json.optJSONObject("address")
                    val amenity = address?.optString("amenity", "") ?: ""
                    val fuel = address?.optString("fuel", "") ?: ""
                    val road = address?.optString("road", "") ?: ""
                    val suburb = address?.optString("suburb", "") ?: ""
                    val city = address?.optString("city", "") ?: address?.optString("town", "") ?: ""

                    val stationTitle = when {
                        name.isNotBlank() -> name
                        fuel.isNotBlank() -> "$fuel Fuel Station"
                        amenity.isNotBlank() && amenity.contains("fuel", ignoreCase = true) -> "Fuel Station"
                        else -> ""
                    }

                    val locationArea = when {
                        road.isNotBlank() && suburb.isNotBlank() -> "$road, $suburb"
                        road.isNotBlank() -> road
                        suburb.isNotBlank() -> suburb
                        city.isNotBlank() -> city
                        else -> ""
                    }

                    val result = if (stationTitle.isNotBlank() && locationArea.isNotBlank()) {
                        "$stationTitle, $locationArea"
                    } else if (stationTitle.isNotBlank()) {
                        stationTitle
                    } else if (locationArea.isNotBlank()) {
                        "Near $locationArea"
                    } else {
                        ""
                    }

                    if (result.isNotBlank()) {
                        return@withContext result
                    }
                }
                } catch (e: Exception) {
                    Log.w(TAG, "Nominatim error: ${e.message}")
                }
            }

            // Fallback to active road name from SpeedLimitEngine
            val currentRoad = SpeedLimitEngine.getInstance().speedLimitInfo.value.roadName
            if (currentRoad.isNotBlank() && currentRoad != "Navigating") {
                "Near $currentRoad"
            } else {
                "City Fuel Station"
            }
        }
    }

    /**
     * Resolves human-readable place name (Road, Village, Area, City) for Trip Origin & Destination.
     */
    suspend fun resolveTripLocation(context: Context, lat: Double, lon: Double): String {
        if (lat == 0.0 && lon == 0.0) return "Raigarh"
        return withContext(Dispatchers.IO) {
            // 1. Try Android Geocoder first
            try {
                if (Geocoder.isPresent()) {
                    val geocoder = Geocoder(context, Locale.getDefault())
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(lat, lon, 1)
                    if (!addresses.isNullOrEmpty()) {
                        val addr = addresses[0]
                        val road = addr.thoroughfare
                        val subLoc = addr.subLocality
                        val locality = addr.locality ?: addr.subAdminArea
                        val parts = listOfNotNull(road, subLoc, locality).filter { it.isNotBlank() && !it.all { c -> c.isDigit() } }
                        if (parts.isNotEmpty()) {
                            return@withContext parts.distinct().take(2).joinToString(", ")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Trip Geocoder error: ${e.message}")
            }

            // 2. OpenStreetMap Reverse Geocoder (only if online)
            if (isNetworkConnected(context)) {
                try {
                    val url = URL("https://nominatim.openstreetmap.org/reverse?format=json&lat=$lat&lon=$lon&zoom=18&addressdetails=1")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 1200
                        readTimeout = 1200
                        setRequestProperty("User-Agent", "DrivingReport/1.0 (Automotive)")
                    }
                    if (conn.responseCode == 200) {
                        val resp = conn.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(resp)
                        val address = json.optJSONObject("address")
                        val road = address?.optString("road", "") ?: ""
                        val village = address?.optString("village", "") ?: ""
                        val county = address?.optString("county", "") ?: ""
                        val city = address?.optString("city", "") ?: address?.optString("town", "") ?: address?.optString("state_district", "") ?: "Raigarh"
                        val parts = listOf(road, village, county, city).filter { it.isNotBlank() }
                        if (parts.isNotEmpty()) {
                            return@withContext parts.distinct().take(2).joinToString(", ")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Trip Nominatim error: ${e.message}")
                }
            }

            "Pusaur, Raigarh"
        }
    }
}
