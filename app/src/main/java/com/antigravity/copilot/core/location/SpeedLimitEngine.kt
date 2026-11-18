package com.antigravity.copilot.core.location

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.antigravity.copilot.core.model.TripPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

data class SpeedLimitInfo(
    val speedLimitKmh: Int = 50,
    val roadName: String = "City Street",
    val zoneType: String = "Urban Zone",
    /** Debounced (see [SpeedLimitRules.OverspeedDebouncer]): 2 readings above limit + 3 km/h. */
    val isOverspeed: Boolean = false,
    val isNearLimit: Boolean = false,
    /** True when the limit is inferred (road class / driving pattern), false for a tagged maxspeed. */
    val isEstimated: Boolean = true,
    /**
     * Show the limit at all: the vehicle is moving on a road matched on the offline map.
     * False when stationary, off-road, or with no map match (sign and dial notch hidden).
     */
    val isApplicable: Boolean = false
)

/** The road the offline map matched under the vehicle (from OfflineRoadIntelligenceManager). */
data class MapRoadObservation(
    val roadClass: String?,
    val ref: String?,
    val name: String?,
    val maxspeedRaw: String?,
    val isRamp: Boolean,
    val isExpressway: Boolean,
    val label: String?
)

/**
 * Speed limit for the current road. Sources, best first:
 * 1. the offline map's road match: explicit `maxspeed` if the tiles carry it, otherwise the MoRTH
 *    limit for its road class / ref (marked estimated);
 * 2. online bonus: an explicit OSM `maxspeed` from Overpass for the same road class, fetched only
 *    with a network, throttled (≥ 150 m and ≥ 15 s apart) and cached per 150 m cell;
 * 3. no map match for [MAP_MATCH_STALE_MS]: a driving-pattern estimate.
 * Overspeed / near-limit flags are debounced so GPS spikes never flash the red state.
 */
class SpeedLimitEngine private constructor() {

    @Volatile
    private var appContext: Context? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _speedLimitInfo = MutableStateFlow(SpeedLimitInfo())
    val speedLimitInfo: StateFlow<SpeedLimitInfo> = _speedLimitInfo.asStateFlow()

    private val debouncer = SpeedLimitRules.OverspeedDebouncer()
    private var lastSpeedKmh = 0f
    /** Moving with hysteresis: on at [MOVING_ON_KMH], off below [MOVING_OFF_KMH]. */
    private var isMoving = false
    /** Consecutive map evaluations that found no road under the vehicle. */
    private var mapRoadMisses = 0

    // Offline map match
    private var mapRoad: MapRoadObservation? = null
    private var mapRoadLat = 0.0
    private var mapRoadLon = 0.0

    // Online bonus (Overpass), keyed by 150 m cell + road class
    private val onlineCache = object : LinkedHashMap<String, Int?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int?>?) = size > 256
    }
    private var onlineJob: Job? = null
    private var lastOnlineQueryMs = 0L
    private var lastOnlineLat = 0.0
    private var lastOnlineLon = 0.0
    private var lastLat = 0.0
    private var lastLon = 0.0
    private var lastBearing = Double.NaN

    /** elapsedRealtime of the last road match from the rendered map (null-safe: 0 = never). */
    @Volatile var lastUiMapRoadElapsedMs: Long = 0L
        private set

    // Driving-pattern fallback
    private var highSpeedSamples = 0
    private var mediumSpeedSamples = 0
    private var lowSpeedSamples = 0

    fun isNetworkConnected(context: Context? = appContext): Boolean {
        val ctx = context ?: appContext ?: return false
        return LocationNameResolver.isNetworkConnected(ctx)
    }

    /** Every GPS fix. */
    @Synchronized
    fun updateTelemetry(point: TripPoint?, context: Context? = null) {
        if (context != null && appContext == null) appContext = context.applicationContext
        if (point == null) return
        lastSpeedKmh = point.speedKmh
        if (point.speedKmh >= MOVING_ON_KMH) lastBearing = point.bearing.toDouble()
        isMoving = if (isMoving) point.speedKmh >= MOVING_OFF_KMH else point.speedKmh >= MOVING_ON_KMH
        if (point.latitude != 0.0 || point.longitude != 0.0) {
            lastLat = point.latitude
            lastLon = point.longitude
            // A match that arrived before the first fix is anchored here.
            if (mapRoad != null && mapRoadLat == 0.0 && mapRoadLon == 0.0) {
                mapRoadLat = lastLat
                mapRoadLon = lastLon
            }
        }
        trackPattern(point.speedKmh)
        maybeQueryOnline()
        publish(newReading = true)
    }

    /**
     * Road under the vehicle (null = no road matched this evaluation). [fromTiles] = the service's
     * direct PMTiles lookup (J1), used while the rendered map is not providing matches.
     */
    @Synchronized
    fun onMapRoad(observation: MapRoadObservation?, fromTiles: Boolean = false) {
        if (!fromTiles) lastUiMapRoadElapsedMs = SystemClock.elapsedRealtime()
        if (observation != null) {
            mapRoad = observation
            mapRoadLat = lastLat
            mapRoadLon = lastLon
            mapRoadMisses = 0
        } else {
            mapRoadMisses++
        }
        publish(newReading = false)
    }

    /** Only real GPS readings ([newReading]) count towards confirming overspeed. */
    private fun publish(newReading: Boolean) {
        val base = resolve()
        // Off-road (2 misses in a row debounce tile-edge gaps) or stationary: no limit to show or warn on.
        val applicable = isMoving && freshMapRoad() != null && mapRoadMisses < 2
        if (!applicable) debouncer.reset()
        else if (newReading) debouncer.update(lastSpeedKmh, base.speedLimitKmh)
        else debouncer.onLimitChanged(lastSpeedKmh, base.speedLimitKmh)
        val info = base.copy(
            isOverspeed = debouncer.isOverspeed,
            isNearLimit = debouncer.isNearLimit,
            isApplicable = applicable
        )
        if (info != _speedLimitInfo.value) _speedLimitInfo.value = info
    }

    private fun resolve(): SpeedLimitInfo {
        val road = freshMapRoad()
        if (road != null) {
            val label = road.label ?: "Road"
            SpeedLimitRules.parseMaxspeed(road.maxspeedRaw)?.let {
                return SpeedLimitInfo(it, label, "Signed limit (map)", isEstimated = false)
            }
            onlineCache[onlineKey(lastLat, lastLon, road.roadClass)]?.let {
                return SpeedLimitInfo(it, label, "Signed limit (OSM online)", isEstimated = false)
            }
            val est = SpeedLimitRules.estimateForRoad(road.roadClass, road.ref, road.isRamp, road.isExpressway)
            return SpeedLimitInfo(est.limitKmh, label, "${est.zone} (MoRTH est.)", isEstimated = true)
        }
        return when {
            highSpeedSamples >= 5 -> SpeedLimitInfo(100, "Highway", "Estimated from speed")
            mediumSpeedSamples >= 5 -> SpeedLimitInfo(70, "Arterial road", "Estimated from speed")
            lowSpeedSamples >= 8 -> SpeedLimitInfo(40, "Residential street", "Estimated from speed")
            else -> SpeedLimitInfo(50, "City road", "Urban default")
        }
    }

    /** The map match, unless the car has since driven [MAP_MATCH_STALE_M] without a new one. */
    private fun freshMapRoad(): MapRoadObservation? = mapRoad?.takeIf {
        distanceM(mapRoadLat, mapRoadLon, lastLat, lastLon) <= MAP_MATCH_STALE_M
    }

    private fun trackPattern(speed: Float) {
        when {
            speed >= 85f -> { highSpeedSamples++; mediumSpeedSamples = 0; lowSpeedSamples = 0 }
            speed >= 58f -> { mediumSpeedSamples++; highSpeedSamples = 0; lowSpeedSamples = 0 }
            speed in 8f..38f -> { lowSpeedSamples++; highSpeedSamples = 0; mediumSpeedSamples = 0 }
        }
    }

    // ---------------- Online bonus (Overpass) ----------------

    private fun maybeQueryOnline() {
        val road = freshMapRoad() ?: return
        if (SpeedLimitRules.parseMaxspeed(road.maxspeedRaw) != null) return
        val cls = road.roadClass ?: return
        if (cls == "service" || cls == "track") return
        if (lastLat == 0.0 && lastLon == 0.0) return
        val key = onlineKey(lastLat, lastLon, cls)
        if (onlineCache.containsKey(key)) return
        val now = SystemClock.elapsedRealtime()
        if (onlineJob?.isActive == true || now - lastOnlineQueryMs < ONLINE_MIN_INTERVAL_MS) return
        if (lastOnlineQueryMs != 0L && distanceM(lastOnlineLat, lastOnlineLon, lastLat, lastLon) < ONLINE_MIN_DISTANCE_M) return
        val ctx = appContext ?: return
        // Online lookups switch + shared rate limiter (audit R1).
        if (!NetworkLookupGate.isAllowed(ctx)) return
        lastOnlineQueryMs = now
        lastOnlineLat = lastLat
        lastOnlineLon = lastLon
        val lat = lastLat
        val lon = lastLon
        val heading = lastBearing
        onlineJob = scope.launch {
            val limit = try { queryOverpassMaxspeed(lat, lon, cls, heading) } catch (e: Exception) {
                Log.d(TAG, "Overpass unavailable: ${e.message}")
                return@launch
            }
            synchronized(this@SpeedLimitEngine) {
                onlineCache[key] = limit
                publish(newReading = false)
            }
        }
    }

    /**
     * Explicit maxspeed of the way the car is on (audit I2): ways within 25 m of the road class,
     * with geometry, filtered by bearing against the heading and ranked by distance, so a parallel
     * service road or flyover no longer donates its limit.
     */
    private suspend fun queryOverpassMaxspeed(lat: Double, lon: Double, omtClass: String, headingDeg: Double): Int? {
        val query = "[out:json][timeout:4];way(around:25,$lat,$lon)[highway][maxspeed];out tags geom;"
        val body = NetworkLookupGate.httpGet(
            "https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(query, "UTF-8"),
            "Copilot-Cockpit/1.0"
        ) ?: return null
        val elements = JSONObject(body).optJSONArray("elements") ?: return null
        val ways = ArrayList<SpeedLimitRules.WayCandidate>()
        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            val tags = el.optJSONObject("tags") ?: continue
            if (omtClassOf(tags.optString("highway")) != omtClass) continue
            val limit = SpeedLimitRules.parseMaxspeed(tags.optString("maxspeed")) ?: continue
            val geom = el.optJSONArray("geometry") ?: continue
            val pts = (0 until geom.length()).map { geom.getJSONObject(it).let { p -> p.optDouble("lat") to p.optDouble("lon") } }
            ways += SpeedLimitRules.WayCandidate(limit, pts, tags.optString("oneway") == "yes")
        }
        return SpeedLimitRules.bestWay(lat, lon, headingDeg, ways)?.limitKmh
    }

    private fun omtClassOf(highway: String): String? = when (highway.removeSuffix("_link")) {
        "motorway" -> "motorway"
        "trunk" -> "trunk"
        "primary" -> "primary"
        "secondary" -> "secondary"
        "tertiary" -> "tertiary"
        "residential", "unclassified", "living_street", "road" -> "minor"
        "service" -> "service"
        else -> null
    }

    private fun onlineKey(lat: Double, lon: Double, cls: String?) =
        "${(lat * 666).roundToInt()}_${(lon * 666).roundToInt()}_$cls"

    private fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 6_371_000.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    companion object {
        private const val TAG = "SpeedLimitEngine"
        /**
         * Without a map match for this far (map hidden, off-road, no tiles) fall back to the
         * pattern estimate. Distance, not time: a parked car gets no new fixes or matches.
         */
        const val MAP_MATCH_STALE_M = 150.0
        private const val MOVING_ON_KMH = 8f
        private const val MOVING_OFF_KMH = 3f
        private const val ONLINE_MIN_INTERVAL_MS = 15_000L
        private const val ONLINE_MIN_DISTANCE_M = 150.0

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
