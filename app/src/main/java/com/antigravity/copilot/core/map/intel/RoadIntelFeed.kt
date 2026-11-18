package com.antigravity.copilot.core.map.intel

import android.content.Context
import android.os.SystemClock
import com.antigravity.copilot.core.fuel.FuelRepository
import com.antigravity.copilot.core.fuel.station.FuelPoiCache
import com.antigravity.copilot.core.fuel.station.FuelStationRepository
import com.antigravity.copilot.core.location.SpeedLimitEngine
import com.antigravity.copilot.core.map.OfflineMapManager
import com.antigravity.copilot.core.model.TripPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UI-facing results of the service's offline road intelligence. */
object RoadIntelState {
    internal val _hazard = MutableStateFlow<HazardAlert?>(null)
    /** School zone / speed camera ahead (audit I3). */
    val hazard: StateFlow<HazardAlert?> = _hazard.asStateFlow()

    internal val _pump = MutableStateFlow<FuelPumpSuggester.Suggestion?>(null)
    /** Low fuel: nearest / best-rated pump (audit H6). */
    val pump: StateFlow<FuelPumpSuggester.Suggestion?> = _pump.asStateFlow()
}

/**
 * Service-side road intelligence (audits I1 / J1 / I3 / H6): reads the PMTiles file directly, so
 * the speed limit, hazard alerts and pump suggestion work while the map is hidden. The rendered
 * map's own match wins while it is fresh. Throttled: road every 2 s / 25 m, POIs every 10 s / 200 m.
 */
class RoadIntelFeed(
    private val context: Context,
    private val scope: CoroutineScope,
    private val speedLimitEngine: SpeedLimitEngine,
    private val fuelRepository: FuelRepository
) {
    private val lookup = OfflineRoadLookup()
    private var job: Job? = null
    private var lastRoadMs = 0L
    private var lastRoadLat = 0.0
    private var lastRoadLon = 0.0
    private var lastPoiMs = 0L
    private var lastPoiLat = 0.0
    private var lastPoiLon = 0.0
    private var previousRoad: RoadLine? = null
    private var pois: List<TilePoi> = emptyList()

    fun onPoint(p: TripPoint) {
        if (p.latitude == 0.0 && p.longitude == 0.0) return
        if (job?.isActive == true) return
        val now = SystemClock.elapsedRealtime()
        val movedRoad = RoadGeometry.distanceM(LonLat(lastRoadLon, lastRoadLat), LonLat(p.longitude, p.latitude))
        val roadDue = now - lastRoadMs >= ROAD_INTERVAL_MS || movedRoad >= ROAD_DISTANCE_M && now - lastRoadMs >= MIN_INTERVAL_MS
        val movedPoi = RoadGeometry.distanceM(LonLat(lastPoiLon, lastPoiLat), LonLat(p.longitude, p.latitude))
        val poiDue = now - lastPoiMs >= POI_INTERVAL_MS || movedPoi >= POI_DISTANCE_M
        if (!roadDue && !poiDue) return
        job = scope.launch(Dispatchers.Default) {
            try {
                lookup.open(OfflineMapManager.getBestMapFileForLocation(context, p.latitude, p.longitude))
                if (roadDue) {
                    lastRoadMs = now; lastRoadLat = p.latitude; lastRoadLon = p.longitude
                    // Only while the rendered map is not matching roads itself (hidden / other app).
                    if (now - speedLimitEngine.lastUiMapRoadElapsedMs > UI_MATCH_FRESH_MS) {
                        val obs = lookup.roadObservation(p.latitude, p.longitude, p.bearing.toDouble(), p.speedKmh.toDouble(), previousRoad)
                        obs?.second?.let { previousRoad = it }
                        speedLimitEngine.onMapRoad(obs?.first, fromTiles = true)
                    }
                }
                if (poiDue) {
                    lastPoiMs = now; lastPoiLat = p.latitude; lastPoiLon = p.longitude
                    pois = lookup.poisAround(p.latitude, p.longitude)
                    FuelPoiCache.addAll(pois.filter { it.poiClass == "fuel" }.map {
                        FuelPoiCache.Poi(it.name, it.position.lat, it.position.lon)
                    })
                    updatePump(p)
                }
                val car = LonLat(p.longitude, p.latitude)
                RoadIntelState._hazard.value =
                    if (p.speedKmh >= HAZARD_MIN_SPEED_KMH) RoadHazardRules.ahead(car, p.bearing.toDouble(), pois) else null
            } catch (_: Exception) {
                // Best effort: a broken tile never affects tracking.
            }
        }
    }

    private suspend fun updatePump(p: TripPoint) {
        if (!fuelRepository.fuelState.value.isLowFuelWarning) {
            RoadIntelState._pump.value = null
            return
        }
        val stations = FuelStationRepository.getInstance(context)
            .stationsAround(p.latitude, p.longitude, FuelPumpSuggester.MAX_DISTANCE_M)
            .map { FuelPumpSuggester.Candidate(it.name, LonLat(it.longitude, it.latitude), it.qualityRating) }
        val tilePumps = pois.filter { it.poiClass == "fuel" }.map { FuelPumpSuggester.Candidate(it.name, it.position, null) }
        RoadIntelState._pump.value = FuelPumpSuggester.suggest(LonLat(p.longitude, p.latitude), p.bearing.toDouble(), stations + tilePumps)
    }

    fun close() = lookup.close()

    companion object {
        const val ROAD_INTERVAL_MS = 2_000L
        const val MIN_INTERVAL_MS = 700L
        const val ROAD_DISTANCE_M = 25.0
        const val POI_INTERVAL_MS = 10_000L
        const val POI_DISTANCE_M = 200.0
        const val UI_MATCH_FRESH_MS = 5_000L
        const val HAZARD_MIN_SPEED_KMH = 15f
    }
}
