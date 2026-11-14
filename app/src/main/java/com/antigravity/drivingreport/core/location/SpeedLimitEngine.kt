package com.antigravity.drivingreport.core.location

import android.content.Context
import com.antigravity.drivingreport.core.model.TripPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.roundToInt

data class SpeedLimitInfo(
    val speedLimitKmh: Int = 50,
    val roadName: String = "City Street",
    val zoneType: String = "Urban Zone",
    val isOverspeed: Boolean = false,
    val isNearLimit: Boolean = false
)

/**
 * High-Precision Automotive Speed Limit Engine.
 * Grounded in:
 *  1. OpenStreetMap (OSM) explicit maxspeed tags & OpenStreetMapSpeeds
 *  2. Valhalla / Overpass road attributes (highway, ref, lanes, surface)
 *  3. Government of India / MoRTH (Ministry of Road Transport and Highways) statutory regulations:
 *     - Expressways (Controlled Access / NE): 120 km/h
 *     - 4-Lane+ Divided National Highways (NH): 100 km/h
 *     - State Highways & 2-lane divided corridors (SH): 80 km/h
 *     - Major District Roads (MDR) & Arterials: 60-70 km/h
 *     - Urban Municipal City Limits: 50 km/h
 *     - Residential / Service / Commercial Collectors: 40 km/h
 *     - School / Hospital / Living Streets: 25-30 km/h
 */
class SpeedLimitEngine private constructor() {

    @Volatile
    private var appContext: Context? = null

    private val scope = CoroutineScope(Dispatchers.IO)
    private val _speedLimitInfo = MutableStateFlow(SpeedLimitInfo())
    val speedLimitInfo: StateFlow<SpeedLimitInfo> = _speedLimitInfo.asStateFlow()

    private var lastQueriedLat: Double = 0.0
    private var lastQueriedLon: Double = 0.0
    private var lastQueryTimestamp: Long = 0
    private val spatialCache = mutableMapOf<String, SpeedLimitInfo>()

    // Sustained automotive cruise telemetry tracking
    private var highSpeedSampleCount = 0
    private var mediumSpeedSampleCount = 0
    private var lowSpeedSampleCount = 0

    fun isNetworkConnected(context: Context? = appContext): Boolean {
        val ctx = context ?: appContext ?: return false
        return LocationNameResolver.isNetworkConnected(ctx)
    }

    fun updateTelemetry(point: TripPoint?, context: Context? = null) {
        if (context != null && appContext == null) {
            appContext = context.applicationContext
        }
        if (point == null) return
        val currentSpeed = point.speedKmh
        val currentLimit = _speedLimitInfo.value.speedLimitKmh

        val isOver = currentSpeed > currentLimit
        val isNear = currentSpeed >= (currentLimit * 0.88f) && !isOver

        // Immediate 60 FPS alert state evaluation
        if (_speedLimitInfo.value.isOverspeed != isOver || _speedLimitInfo.value.isNearLimit != isNear) {
            _speedLimitInfo.value = _speedLimitInfo.value.copy(
                isOverspeed = isOver,
                isNearLimit = isNear
            )
        }

        // Kinematic cruise pattern profiling
        if (currentSpeed >= 85f) {
            highSpeedSampleCount++
            mediumSpeedSampleCount = 0
            lowSpeedSampleCount = 0
        } else if (currentSpeed >= 58f) {
            mediumSpeedSampleCount++
            highSpeedSampleCount = 0
            lowSpeedSampleCount = 0
        } else if (currentSpeed in 8f..38f) {
            lowSpeedSampleCount++
            highSpeedSampleCount = 0
            mediumSpeedSampleCount = 0
        }

        // Intelligent spatial grid rate-limiting: Only query if vehicle moved > 120m or 10s elapsed
        val now = System.currentTimeMillis()
        val distMovedMeters = calculateDistance(lastQueriedLat, lastQueriedLon, point.latitude, point.longitude)

        if ((now - lastQueryTimestamp > 10000L || distMovedMeters > 120.0) && point.latitude != 0.0) {
            lastQueriedLat = point.latitude
            lastQueriedLon = point.longitude
            lastQueryTimestamp = now

            // 150-meter spatial bucket cache key
            val gridLat = (point.latitude * 500).roundToInt()
            val gridLon = (point.longitude * 500).roundToInt()
            val cacheKey = "${gridLat}_${gridLon}"

            spatialCache[cacheKey]?.let { cached ->
                _speedLimitInfo.value = cached.copy(
                    isOverspeed = currentSpeed > cached.speedLimitKmh,
                    isNearLimit = currentSpeed >= (cached.speedLimitKmh * 0.88f) && currentSpeed <= cached.speedLimitKmh
                )
                return
            }

            scope.launch {
                resolveSpeedLimitFromOsmAndMorth(point.latitude, point.longitude, currentSpeed, cacheKey)
            }
        } else if (spatialCache.isEmpty()) {
            // Contextual fallback according to MoRTH speed guidelines
            val fallbackLimit = when {
                highSpeedSampleCount >= 5 -> SpeedLimitInfo(
                    speedLimitKmh = 100,
                    roadName = "National Highway",
                    zoneType = "NH (MoRTH)",
                    isOverspeed = isOver,
                    isNearLimit = isNear
                )
                mediumSpeedSampleCount >= 5 -> SpeedLimitInfo(
                    speedLimitKmh = 70,
                    roadName = "State Arterial",
                    zoneType = "State Corridor",
                    isOverspeed = isOver,
                    isNearLimit = isNear
                )
                lowSpeedSampleCount >= 8 -> SpeedLimitInfo(
                    speedLimitKmh = 40,
                    roadName = "Residential / Collector",
                    zoneType = "Residential",
                    isOverspeed = isOver,
                    isNearLimit = isNear
                )
                else -> SpeedLimitInfo(
                    speedLimitKmh = 50,
                    roadName = "City Avenue",
                    zoneType = "Urban Zone",
                    isOverspeed = isOver,
                    isNearLimit = isNear
                )
            }
            if (_speedLimitInfo.value.speedLimitKmh != fallbackLimit.speedLimitKmh) {
                _speedLimitInfo.value = fallbackLimit
            }
        }
    }

    /**
     * Resolves the speed limit by interrogating:
     * 1. Overpass API / OpenStreetMap maxspeed & highway attributes
     * 2. Nominatim detailed address & road hierarchy fallback
     * 3. Government of India / MoRTH statutory speed limits for India
     */
    private fun resolveSpeedLimitFromOsmAndMorth(lat: Double, lon: Double, speed: Float, cacheKey: String) {
        var resolvedLimit: Int? = null
        var resolvedRoadName: String? = null
        var resolvedZone: String? = null

        val hasNetwork = isNetworkConnected(appContext)

        // Try to get cached speed limit first (works offline)
        val cachedInfo = spatialCache[cacheKey]
        val hasCachedLimit = cachedInfo != null && cachedInfo.speedLimitKmh > 0

        if (hasNetwork) {
            // ------------------ TIER 1: Overpass API (Exact OSM Way Tags & maxspeed) ------------------
            try {
                val query = "[out:json][timeout:3];way(around:40,$lat,$lon)[highway];out tags 1;"
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val overpassUrl = URL("https://overpass-api.de/api/interpreter?data=$encodedQuery")

                val conn = (overpassUrl.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3200
                    readTimeout = 3200
                    setRequestProperty("User-Agent", "DrivingReport-Cockpit/1.0 (MoRTH Automotive Telemetry)")
                }

                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val root = JSONObject(body)
                    val elements = root.optJSONArray("elements")
                    if (elements != null && elements.length() > 0) {
                        val way = elements.getJSONObject(0)
                        val tags = way.optJSONObject("tags")
                        if (tags != null) {
                            val maxspeedRaw = tags.optString("maxspeed", "")
                            val highway = tags.optString("highway", "")
                            val ref = tags.optString("ref", "")
                            val name = tags.optString("name", "")
                            val lanes = tags.optInt("lanes", 2)

                            // 1. Check explicit OSM maxspeed
                            val parsedExplicit = parseExplicitMaxspeed(maxspeedRaw)
                            if (parsedExplicit != null && parsedExplicit > 0) {
                                resolvedLimit = parsedExplicit
                                resolvedZone = "OSM Tagged ($parsedExplicit km/h)"
                            } else {
                                // 2. Map Highway Type & Ref to MoRTH India Guidelines
                                val morth = evaluateMorthLimit(highway, ref, name, lanes)
                                resolvedLimit = morth.first
                                resolvedZone = morth.second
                            }

                            resolvedRoadName = when {
                                name.isNotBlank() && ref.isNotBlank() -> "$name ($ref)"
                                name.isNotBlank() -> name
                                ref.isNotBlank() -> ref
                                else -> highway.replace("_", " ").replaceFirstChar { it.uppercase() }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Overpass unavailable or timeout, fallback seamlessly to Tier 2
            }

            // ------------------ TIER 2: Nominatim OSM Reverse Geocoder ------------------
            if (resolvedLimit == null || resolvedRoadName == null) {
                try {
                    val nominatimUrl = URL("https://nominatim.openstreetmap.org/reverse?format=json&lat=$lat&lon=$lon&zoom=18&addressdetails=1")
                    val conn = (nominatimUrl.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 3000
                        readTimeout = 3000
                        setRequestProperty("User-Agent", "DrivingReport-Cockpit/1.0 (Automotive Telemetry)")
                    }

                    if (conn.responseCode == 200) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(body)
                        val address = json.optJSONObject("address")
                        val road = address?.optString("road", "") ?: ""
                        val suburb = address?.optString("suburb", "") ?: ""
                        val roadType = address?.optString("type", "") ?: ""

                        val morth = evaluateMorthLimit(roadType, "", road, 2)
                        resolvedLimit = morth.first
                        resolvedZone = morth.second

                        resolvedRoadName = when {
                            road.isNotBlank() -> road
                            suburb.isNotBlank() -> suburb
                            else -> "City Sector"
                        }
                    }
                } catch (_: Exception) {
                    // Secondary fallback
                }
            }
        }

        // ------------------ TIER 3: Cached / Kinematic Fallback ------------------
        val finalLimit = resolvedLimit ?: (if (!hasNetwork && hasCachedLimit) {
            cachedInfo!!.speedLimitKmh
        } else {
            when {
                speed >= 85f -> 100
                speed >= 60f -> 80
                speed in 10f..38f -> 40
                else -> 50
            }
        })
        val finalRoadName = resolvedRoadName ?: (if (!hasNetwork && hasCachedLimit) cachedInfo!!.roadName else "City Road")
        val finalZone = resolvedZone ?: (if (!hasNetwork && hasCachedLimit) cachedInfo!!.zoneType else "Urban Sector")

        val info = SpeedLimitInfo(
            speedLimitKmh = finalLimit,
            roadName = finalRoadName,
            zoneType = finalZone,
            isOverspeed = speed > finalLimit,
            isNearLimit = speed >= (finalLimit * 0.88f) && speed <= finalLimit
        )

        spatialCache[cacheKey] = info
        _speedLimitInfo.value = info
    }

    /**
     * Parses explicit OSM maxspeed values such as:
     * "120", "100 km/h", "80", "50", "IN:expressway", "IN:urban", "IN:rural"
     */
    private fun parseExplicitMaxspeed(raw: String): Int? {
        if (raw.isBlank()) return null
        val clean = raw.trim().lowercase(Locale.US)
        when (clean) {
            "in:expressway" -> return 120
            "in:rural", "in:highway" -> return 100
            "in:urban", "in:city" -> return 50
            "walk" -> return 15
        }
        // Extract numeric digits
        val digits = clean.filter { it.isDigit() }
        val parsed = digits.toIntOrNull()
        return if (parsed != null && parsed in 15..160) parsed else null
    }

    /**
     * Government of India / MoRTH Statutory Speed Limit Evaluation
     * Official Notification S.O. 1522(E) for M1 Category (Passenger Motor Cars):
     *  - Expressways: 120 km/h
     *  - National Highways (4-lane and above divided carriageway): 100 km/h
     *  - State Highways & 2-lane divided corridors: 80 km/h
     *  - Major District Roads (MDR) / Secondary: 60-70 km/h
     *  - Urban Municipal / City Roads: 50 km/h
     *  - Residential / Collector Streets: 40 km/h
     *  - School / Hospital / Living Streets: 25-30 km/h
     */
    private fun evaluateMorthLimit(highway: String, ref: String, roadName: String, lanes: Int): Pair<Int, String> {
        val h = highway.lowercase(Locale.US)
        val r = ref.uppercase(Locale.US)
        val n = roadName.lowercase(Locale.US)

        return when {
            // MoRTH Expressways (120 km/h)
            h in listOf("motorway", "motorway_link") ||
            r.startsWith("NE") ||
            n.contains("expressway") ||
            n.contains("express highway") ||
            n.contains("mahamarg") -> {
                Pair(120, "Expressway (MoRTH)")
            }

            // MoRTH National Highways (100 km/h)
            h in listOf("trunk", "trunk_link") ||
            r.startsWith("NH") ||
            (lanes >= 4 && h == "primary") ||
            n.contains("national highway") ||
            n.contains("bypass") -> {
                Pair(100, "National Highway (MoRTH)")
            }

            // MoRTH State Highways (80 km/h)
            h in listOf("primary", "primary_link") ||
            r.startsWith("SH") ||
            n.contains("state highway") ||
            n.contains("ring road") -> {
                Pair(80, "State Highway (MoRTH)")
            }

            // Major District Roads / Arterials (60-70 km/h)
            h in listOf("secondary", "secondary_link") -> {
                Pair(70, "District Arterial (MoRTH)")
            }

            // Urban Collector / Tertiary Roads (50 km/h)
            h in listOf("tertiary", "tertiary_link", "unclassified") -> {
                Pair(50, "Urban Municipal (MoRTH)")
            }

            // Residential / Service (40 km/h)
            h in listOf("residential", "service", "road") -> {
                Pair(40, "Residential Street")
            }

            // School / Hospital / Pedestrian Living Streets (25-30 km/h)
            h in listOf("living_street", "pedestrian") ||
            n.contains("school") ||
            n.contains("hospital") -> {
                Pair(25, "School / Hospital Zone (MoRTH)")
            }

            else -> Pair(50, "Urban Road (MoRTH)")
        }
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        if (lat1 == 0.0 && lon1 == 0.0) return 999.0
        val earthRadius = 6371000.0 // meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return earthRadius * c
    }

    companion object {
        @Volatile
        private var INSTANCE: SpeedLimitEngine? = null

        fun getInstance(context: Context? = null): SpeedLimitEngine {
            val inst = INSTANCE ?: synchronized(this) {
                INSTANCE ?: SpeedLimitEngine().also { INSTANCE = it }
            }
            if (context != null && inst.appContext == null) {
                inst.appContext = context.applicationContext
            }
            return inst
        }
    }
}
