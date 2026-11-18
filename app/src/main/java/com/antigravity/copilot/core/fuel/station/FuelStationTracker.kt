package com.antigravity.copilot.core.fuel.station

import android.content.Context
import android.util.Log
import com.antigravity.copilot.core.model.TripPoint
import com.antigravity.copilot.core.road.RoadAnomalyMath
import com.antigravity.copilot.core.util.NumberFormatUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Fuel POIs read from the offline PMTiles `poi` layer (class = fuel) while the map shows them.
 * Memory only and bounded; a station becomes permanent in Room on the first refuel there.
 */
object FuelPoiCache {
    data class Poi(val name: String, val latitude: Double, val longitude: Double)

    private const val CAPACITY = 600
    private val pois = object : LinkedHashMap<String, Poi>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Poi>?) = size > CAPACITY
    }

    @Synchronized
    fun addAll(list: List<Poi>) {
        list.forEach { pois["%.5f,%.5f".format(java.util.Locale.US, it.latitude, it.longitude)] = it }
    }

    @Synchronized
    fun nearest(lat: Double, lon: Double, radiusM: Double): Poi? =
        pois.values.asSequence()
            .map { it to RoadAnomalyMath.distanceM(lat, lon, it.latitude, it.longitude) }
            .filter { it.second <= radiusM }
            .minByOrNull { it.second }?.first

    @Synchronized
    fun clear() = pois.clear()
}

/** What the map bubble shows above one station pin. */
data class StationBubble(
    val stationId: Long,
    val latitude: Double,
    val longitude: Double,
    val text: String,
    val quality: StationQuality
)

/**
 * Smart proximity bubble: of the saved stations within 300 m, picks only the single closest one
 * ahead (±60° of heading) so bubbles never stack. Fed with every GPS fix from the tracking service.
 */
class FuelStationTracker private constructor(context: Context) {
    private val repo = FuelStationRepository.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    private var nearby: List<FuelStationEntity> = emptyList()
    private var nearbyLat = Double.NaN
    private var nearbyLon = Double.NaN
    @Volatile private var dirty = true

    private val _bubble = MutableStateFlow<StationBubble?>(null)
    val bubble: StateFlow<StationBubble?> = _bubble.asStateFlow()

    init {
        // Renames / new ratings / new stations must show without waiting for the car to move.
        scope.launch { repo.stations.collect { dirty = true } }
    }

    fun onLocation(p: TripPoint) {
        scope.launch {
            try {
                val moved = nearbyLat.isNaN() || RoadAnomalyMath.distanceM(p.latitude, p.longitude, nearbyLat, nearbyLon) > REFRESH_MOVE_M
                if (moved || dirty) {
                    dirty = false
                    nearby = repo.stationsAround(p.latitude, p.longitude, BUBBLE_RADIUS_M + REFRESH_MOVE_M)
                    nearbyLat = p.latitude; nearbyLon = p.longitude
                }
                val current = _bubble.value
                val pick = if (p.speedKmh < MIN_HEADING_SPEED_KMH) {
                    // Heading is noise when crawling: keep the current bubble while still in range.
                    nearby.firstOrNull { it.stationId == current?.stationId }
                        ?.takeIf { RoadAnomalyMath.distanceM(p.latitude, p.longitude, it.latitude, it.longitude) <= BUBBLE_RADIUS_M }
                } else {
                    selectAhead(p.latitude, p.longitude, p.bearing.toDouble(), nearby)
                }
                _bubble.value = pick?.let { StationBubble(it.stationId, it.latitude, it.longitude, bubbleText(it), it.quality) }
            } catch (e: Exception) {
                Log.w(TAG, "onLocation: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "FuelStationTracker"
        const val BUBBLE_RADIUS_M = 300.0
        const val BUBBLE_CONE_DEG = 60.0
        private const val REFRESH_MOVE_M = 150.0
        private const val MIN_HEADING_SPEED_KMH = 5f

        @Volatile private var instance: FuelStationTracker? = null
        fun getInstance(context: Context): FuelStationTracker =
            instance ?: synchronized(this) {
                instance ?: FuelStationTracker(context.applicationContext).also { instance = it }
            }

        /** Closest station within 300 m whose bearing is within ±60° of the heading; else null. */
        fun selectAhead(lat: Double, lon: Double, headingDeg: Double, stations: List<FuelStationEntity>): FuelStationEntity? {
            var best: FuelStationEntity? = null
            var bestD = Double.MAX_VALUE
            for (s in stations) {
                val d = RoadAnomalyMath.distanceM(lat, lon, s.latitude, s.longitude)
                if (d > BUBBLE_RADIUS_M || d >= bestD) continue
                val rel = RoadAnomalyMath.angleDiff(headingDeg, RoadAnomalyMath.bearingDeg(lat, lon, s.latitude, s.longitude))
                // Right next to the pump the bearing swings wildly; within 25 m always accept.
                if (d > 25.0 && abs(rel) > BUBBLE_CONE_DEG) continue
                best = s; bestD = d
            }
            return best
        }

        fun bubbleText(s: FuelStationEntity): String {
            val name = s.name.ifBlank { "Fuel Station" }
            val q = s.quality
            if (q == StationQuality.NOT_AVAILABLE || s.averageMileage <= 0.0) return "⛽ $name | Quality: Not Available"
            val rate = if (s.lastFuelRate > 0.0) " | ₹${NumberFormatUtils.formatPrecision(s.lastFuelRate, 2)}/L" else ""
            val label = q.name.lowercase().replaceFirstChar { it.uppercase() }
            return "⛽ $name$rate | ${NumberFormatUtils.formatPrecision(s.averageMileage, 1)} km/l (★ $label)"
        }
    }
}
