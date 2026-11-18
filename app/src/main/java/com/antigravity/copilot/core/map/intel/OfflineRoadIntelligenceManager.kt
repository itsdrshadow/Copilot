package com.antigravity.copilot.core.map.intel

import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import com.antigravity.copilot.core.location.MapRoadObservation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.sources.VectorSource
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** "Sharp turn ahead" badge state. */
data class SharpTurnAlert(val direction: TurnDirection, val distanceM: Int)

/**
 * Offline road intelligence from the local PMTiles vector tiles (no network):
 * 1. location pill "NH-49 • Near Kharsia" (road ref/name + nearest settlement);
 * 2. the matched road (class, ref, maxspeed) for [com.antigravity.copilot.core.location.SpeedLimitEngine];
 * 3. "Sharp turn ahead" from the road geometry 80–150 m ahead.
 *
 * Cost control for the RK3566 / 2 GB unit:
 * - Driven by GPS fixes but throttled: one evaluation per [QUERY_INTERVAL_MS], or sooner after
 *   [QUERY_DISTANCE_M] of movement (never closer than [MIN_INTERVAL_MS]); never while the map is
 *   hidden, and never with one still in flight.
 * - `queryRenderedFeatures` runs on the main thread (MapLibre requires it) over small boxes
 *   (a 24 dp square at the puck, the look-ahead corridor, the viewport for places only every
 *   [PLACE_INTERVAL_MS]); all geometry math runs on [Dispatchers.Default].
 * - Queries hit three invisible layers added to the style ([installQueryLayers]) so they work
 *   regardless of which basemap layers are drawn at the current zoom.
 *
 * Main-thread only. [detach] cancels the in-flight evaluation and drops the map reference.
 */
class OfflineRoadIntelligenceManager(
    private val scope: CoroutineScope,
    private val density: Float,
    /** Where [PlaceMemory] persists the places seen (null = memory only). */
    placeStore: File?,
    private val onRoadObserved: (MapRoadObservation?) -> Unit
) {
    companion object {
        private const val TAG = "RoadIntel"
        const val SOURCE_ID = "openmaptiles"
        const val Q_ROAD = "ri_query_road"
        const val Q_ROAD_NAME = "ri_query_road_name"

        const val QUERY_INTERVAL_MS = 1_500L
        const val QUERY_DISTANCE_M = 25.0
        const val MIN_INTERVAL_MS = 600L
        const val PLACE_INTERVAL_MS = 5_000L
        /** Tolerance box around the puck for the road match (GPS drift). */
        const val ROAD_BOX_DP = 24f
        const val CORRIDOR_PAD_DP = 16f

        const val TURN_MIN_SPEED_KMH = 35.0
        const val TURN_DISMISS_SPEED_KMH = 25.0
        const val TURN_MIN_ANGLE_DEG = 50.0
        const val TURN_WINDOW_M = 120.0

        /**
         * Query layers must not be fully transparent: MapLibre skips opacity-0 layers when it
         * answers `queryRenderedFeatures` (verified on the head unit). 1 % on a 1 px line is invisible.
         */
        private const val QUERY_OPACITY = 0.01f
        private const val WARMUP_TRIES = 6
        private const val WARMUP_INTERVAL_MS = 2_000L

        /** "Near X" only for remembered places within this distance. */
        const val PLACE_SEARCH_M = 8_000.0
        private const val PLACE_SAVE_INTERVAL_MS = 60_000L

        private val ROAD_CLASSES = arrayOf("motorway", "trunk", "primary", "secondary", "tertiary", "minor", "service", "track")
        private val PLACE_CLASSES = arrayOf("city", "town", "village", "suburb", "hamlet", "quarter", "neighbourhood")
        private val NAME_KEYS = arrayOf("name:en", "name_en", "name:latin", "name")

        private fun classFilter(classes: Array<String>): Expression = Expression.match(
            Expression.get("class"), Expression.literal(false),
            *classes.map { Expression.stop(it, true) }.toTypedArray()
        )
    }

    private val _pillText = MutableStateFlow<String?>(null)
    /** "NH-49 • Near Kharsia", "Near Kharsia", "NH-49", or null. */
    val pillText: StateFlow<String?> = _pillText.asStateFlow()

    private val _sharpTurn = MutableStateFlow<SharpTurnAlert?>(null)
    val sharpTurn: StateFlow<SharpTurnAlert?> = _sharpTurn.asStateFlow()

    private val placeMemory = PlaceMemory(placeStore)
    /** File I/O for [placeMemory]; independent of the UI scope so the final save completes. */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var placeMemoryLoadStarted = false
    private var lastPlaceSaveMs = 0L

    private var map: MapLibreMap? = null
    private var queryLayersReady = false
    private var job: Job? = null
    private var warmupJob: Job? = null

    /** Last fix seen, so a parked car (no new fixes) still gets evaluated after style load. */
    private var lastFix: DoubleArray? = null
    private var lastRenderingActive = true

    private var lastQueryMs = 0L
    private var lastQueryPos: LonLat? = null
    private var lastPlaceQueryMs = 0L

    // Continuity / debounce state (main thread).
    private var previousRoad: RoadLine? = null
    private var previousNameRoad: RoadLine? = null
    private val roadLabel = StickyValue<String>(confirm = 2, dropAfterMisses = 3)
    private val placeLabel = StickyValue<String>(confirm = 2, dropAfterMisses = Int.MAX_VALUE)

    private var turn: TurnState? = null
    private var turnMisses = 0

    private class TurnState(val direction: TurnDirection, val distanceAtDetectionM: Double, val detectedAt: LonLat)

    private class Snapshot(
        val car: LonLat,
        val headingDeg: Double,
        val speedKmh: Double,
        val maxMatchDistanceM: Double,
        val lookAheadM: Double,
        val roads: List<Feature>,
        val names: List<Feature>,
        val corridor: List<Feature>,
        val places: List<Feature>?
    )

    private class Result(
        val road: RoadLine?,
        val nameRoad: RoadLine?,
        val label: String?,
        val turn: SharpTurn?,
        val placesQueried: Boolean,
        val place: PlaceCandidate?,
        val newPlaces: Int
    )

    fun attach(map: MapLibreMap) {
        this.map = map
        if (!placeMemoryLoadStarted) {
            placeMemoryLoadStarted = true
            ioScope.launch { placeMemory.load() }
        }
    }

    /** Style is being replaced: query layers disappear with it. */
    fun onStyleUnloaded() {
        queryLayersReady = false
        job?.cancel()
        warmupJob?.cancel()
    }

    fun onStyleLoaded(style: Style) {
        queryLayersReady = installQueryLayers(style)
        if (queryLayersReady) warmUp()
    }

    /**
     * The car may be parked (fixes only arrive on movement) and tiles are still loading right
     * after a style load or un-hide: re-evaluate the last fix a few times until something is found.
     */
    fun warmUp() {
        warmupJob?.cancel()
        warmupJob = scope.launch {
            repeat(WARMUP_TRIES) {
                kotlinx.coroutines.delay(WARMUP_INTERVAL_MS)
                if (_pillText.value != null) return@launch
                val f = lastFix ?: return@repeat
                lastQueryMs = 0L
                onFix(f[0], f[1], f[2], f[3], lastRenderingActive)
            }
        }
    }

    fun detach() {
        job?.cancel()
        job = null
        warmupJob?.cancel()
        warmupJob = null
        lastFix = null
        map = null
        queryLayersReady = false
        previousRoad = null
        previousNameRoad = null
        roadLabel.reset()
        placeLabel.reset()
        ioScope.launch { placeMemory.saveIfDirty() }
        turn = null
        _pillText.value = null
        _sharpTurn.value = null
        onRoadObserved(null)
    }

    /** Every GPS fix (main thread). Cheap unless an evaluation is due. */
    fun onFix(lat: Double, lon: Double, bearingDeg: Double, speedKmh: Double, renderingActive: Boolean) {
        val car = LonLat(lon, lat)
        lastFix = doubleArrayOf(lat, lon, bearingDeg, speedKmh)
        lastRenderingActive = renderingActive
        tickTurn(car, speedKmh)

        val map = map ?: return
        if (!renderingActive || !queryLayersReady || job?.isActive == true) return
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastQueryMs
        val moved = lastQueryPos?.let { RoadGeometry.distanceM(it, car) } ?: Double.MAX_VALUE
        if (elapsed < QUERY_INTERVAL_MS && !(moved >= QUERY_DISTANCE_M && elapsed >= MIN_INTERVAL_MS)) return
        lastQueryMs = now
        lastQueryPos = car

        val snapshot = try {
            query(map, car, bearingDeg, speedKmh, now)
        } catch (e: Exception) {
            Log.w(TAG, "Feature query failed: ${e.message}")
            null
        } ?: return
        val prevRoad = previousRoad
        val prevName = previousNameRoad
        job = scope.launch {
            val result = withContext(Dispatchers.Default) { evaluate(snapshot, prevRoad, prevName) }
            if (this@OfflineRoadIntelligenceManager.map === map) apply(result, snapshot)
        }
    }

    // ---------------- Main thread: queries ----------------

    private fun query(map: MapLibreMap, car: LonLat, heading: Double, speed: Double, now: Long): Snapshot? {
        val latLng = LatLng(car.lat, car.lon)
        val projection = map.projection
        // Follow suspended and the puck panned off screen: nothing meaningful to query.
        if (!projection.visibleRegion.latLngBounds.contains(latLng)) return null
        val p = projection.toScreenLocation(latLng)
        val half = ROAD_BOX_DP / 2 * density
        val roadBox = RectF(p.x - half, p.y - half, p.x + half, p.y + half)
        val roads = map.queryRenderedFeatures(roadBox, Q_ROAD)
        val names = map.queryRenderedFeatures(roadBox, Q_ROAD_NAME)

        val lookAhead = RoadGeometry.lookAheadM(speed)
        val corridor = if (speed >= TURN_MIN_SPEED_KMH && heading.isFinite()) {
            val ahead = RoadGeometry.offset(car, heading, lookAhead + 40.0)
            val a = projection.toScreenLocation(LatLng(ahead.lat, ahead.lon))
            val pad = CORRIDOR_PAD_DP * density
            map.queryRenderedFeatures(
                RectF(min(p.x, a.x) - pad, min(p.y, a.y) - pad, max(p.x, a.x) + pad, max(p.y, a.y) + pad),
                Q_ROAD
            )
        } else emptyList()

        val places = if (now - lastPlaceQueryMs >= PLACE_INTERVAL_MS) {
            lastPlaceQueryMs = now
            // Source query, not rendered: at driving zoom (15–17.5) the viewport is only a few
            // hundred metres and rarely contains a place label, but the loaded tiles span
            // kilometres. Place points are few, so this stays cheap.
            map.style?.getSourceAs<VectorSource>(SOURCE_ID)
                ?.querySourceFeatures(arrayOf("place"), classFilter(PLACE_CLASSES))
                .orEmpty()
        } else null

        val metersPerPx = projection.getMetersPerPixelAtLatitude(car.lat)
        return Snapshot(
            car = car,
            headingDeg = heading,
            speedKmh = speed,
            // Box half-diagonal plus line half-width slack.
            maxMatchDistanceM = metersPerPx * half * 1.5 + 5.0,
            lookAheadM = lookAhead,
            roads = roads,
            names = names,
            corridor = corridor,
            places = places
        )
    }

    // ---------------- Background: geometry ----------------

    private fun evaluate(s: Snapshot, prevRoad: RoadLine?, prevName: RoadLine?): Result {
        val roadLines = s.roads.flatMap(::toRoadLines)
        val match = RoadGeometry.matchRoad(s.car, s.headingDeg, s.speedKmh, roadLines, prevRoad, s.maxMatchDistanceM)

        val nameLines = s.names.flatMap(::toRoadLines)
        val sameClass = match?.line?.roadClass?.let { c -> nameLines.filter { it.roadClass == c } }.orEmpty()
        val nameMatch = RoadGeometry.matchRoad(s.car, s.headingDeg, s.speedKmh, sameClass.ifEmpty { nameLines }, prevName, s.maxMatchDistanceM)

        val label = RoadGeometry.roadLabel(nameMatch?.line?.ref, nameMatch?.line?.name, match?.line?.roadClass)

        val sharp = if (match != null && s.speedKmh >= TURN_MIN_SPEED_KMH) {
            val others = s.corridor.flatMap(::toRoadLines) + roadLines
            var forkAtM: Double? = null
            val path = RoadGeometry.forwardPath(
                s.car, s.headingDeg, match, others, s.lookAheadM + 40.0,
                onFork = { forkAtM = it }, forkMinAngleDeg = TURN_MIN_ANGLE_DEG
            )
            val bend = RoadGeometry.detectSharpTurn(path, s.lookAheadM, TURN_MIN_ANGLE_DEG, TURN_WINDOW_M)
            val fork = forkAtM?.takeIf { it <= s.lookAheadM }
            // T-junction ahead (no straight way on): both directions, unless the road bends first.
            if (fork != null && (bend == null || bend.distanceM > fork)) SharpTurn(TurnDirection.BOTH, fork, 90.0) else bend
        } else null

        var newPlaces = 0
        val place = s.places?.let { features ->
            newPlaces = placeMemory.addAll(features.mapNotNull(::toPlace))
            RoadGeometry.choosePlace(s.car, placeMemory.near(s.car, PLACE_SEARCH_M))
        }
        return Result(match?.line, nameMatch?.line, label, sharp, s.places != null, place, newPlaces)
    }

    private fun toRoadLines(f: Feature): List<RoadLine> {
        val parts: List<List<Point>> = when (val g = f.geometry()) {
            is LineString -> listOf(g.coordinates())
            is MultiLineString -> g.coordinates()
            else -> return emptyList()
        }
        val cls = f.str("class")
        val brunnel = f.str("brunnel")
        val ramp = f.num("ramp") == 1
        val expressway = f.num("expressway") == 1
        val name = NAME_KEYS.firstNotNullOfOrNull { f.str(it) }
        val ref = f.str("ref")
        val maxspeed = f.str("maxspeed") ?: f.num("maxspeed")?.toString()
        return parts.filter { it.size >= 2 }.map { pts ->
            RoadLine(pts.map { LonLat(it.longitude(), it.latitude()) }, cls, brunnel, ramp, expressway, name, ref, maxspeed)
        }
    }

    private fun toPlace(f: Feature): PlaceCandidate? {
        val pt = f.geometry() as? Point ?: return null
        val name = NAME_KEYS.firstNotNullOfOrNull { f.str(it) } ?: return null
        return PlaceCandidate(name, f.str("class"), LonLat(pt.longitude(), pt.latitude()))
    }

    private fun Feature.str(key: String): String? =
        if (hasNonNullValueForProperty(key)) getProperty(key)?.let { if (it.isJsonPrimitive) it.asString else null }?.trim()?.takeIf { it.isNotEmpty() } else null

    private fun Feature.num(key: String): Int? =
        if (hasNonNullValueForProperty(key)) getProperty(key)?.let { runCatching { it.asInt }.getOrNull() } else null

    // ---------------- Main thread: publish ----------------

    private fun apply(r: Result, s: Snapshot) {
        if (com.antigravity.copilot.BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "q roads=${s.roads.size} names=${s.names.size} corridor=${s.corridor.size} places=${s.places?.size} " +
                    "match=${r.road?.roadClass} label=${r.label} place=${r.place?.name} turn=${r.turn}"
            )
        }
        if (r.road != null) previousRoad = r.road
        if (r.nameRoad != null) previousNameRoad = r.nameRoad

        roadLabel.offer(r.label)
        if (r.placesQueried) {
            placeLabel.offer(r.place?.name)
            if (r.place == null) placeLabel.reset()
            val now = SystemClock.elapsedRealtime()
            if (r.newPlaces > 0 && now - lastPlaceSaveMs >= PLACE_SAVE_INTERVAL_MS) {
                lastPlaceSaveMs = now
                ioScope.launch { placeMemory.saveIfDirty() }
            }
        }
        _pillText.value = RoadGeometry.pillText(roadLabel.value, placeLabel.value)

        onRoadObserved(
            r.road?.let { road ->
                MapRoadObservation(
                    roadClass = road.roadClass,
                    ref = RoadGeometry.formatRef(r.nameRoad?.ref),
                    name = r.nameRoad?.name,
                    maxspeedRaw = road.maxspeed ?: r.nameRoad?.maxspeed,
                    isRamp = road.isRamp,
                    isExpressway = road.isExpressway,
                    label = roadLabel.value ?: RoadGeometry.classLabel(road.roadClass)
                )
            }
        )

        val t = r.turn
        if (t != null && s.speedKmh >= TURN_MIN_SPEED_KMH) {
            turn = TurnState(t.direction, t.distanceM, s.car)
            turnMisses = 0
            publishTurn(t.direction, t.distanceM)
        } else if (turn != null && ++turnMisses >= 2) {
            clearTurn()
        }
    }

    /** Per fix: count the distance down and dismiss once passed or slow. */
    private fun tickTurn(car: LonLat, speedKmh: Double) {
        val t = turn ?: return
        val remaining = t.distanceAtDetectionM - RoadGeometry.distanceM(t.detectedAt, car)
        if (speedKmh < TURN_DISMISS_SPEED_KMH || remaining < -15.0) {
            clearTurn()
            return
        }
        publishTurn(t.direction, remaining)
    }

    private fun publishTurn(direction: TurnDirection, distanceM: Double) {
        // 10 m steps: the badge text changes ~1×/s at speed instead of every fix.
        val rounded = ((distanceM.coerceAtLeast(0.0) / 10.0).roundToInt() * 10)
        val alert = SharpTurnAlert(direction, rounded)
        if (_sharpTurn.value != alert) _sharpTurn.value = alert
    }

    private fun clearTurn() {
        turn = null
        turnMisses = 0
        _sharpTurn.value = null
    }

    // ---------------- Style ----------------

    /**
     * Invisible (opacity 0) query layers over the vector source. Returns false when the style has
     * no map source (no offline map installed).
     */
    private fun installQueryLayers(style: Style): Boolean = try {
        if (style.getSource(SOURCE_ID) == null) {
            false
        } else {
            listOf(Q_ROAD, Q_ROAD_NAME).forEach { id -> style.getLayer(id)?.let { style.removeLayer(it) } }
            style.addLayer(
                LineLayer(Q_ROAD, SOURCE_ID).withSourceLayer("transportation")
                    .withFilter(classFilter(ROAD_CLASSES))
                    .withProperties(lineOpacity(QUERY_OPACITY), lineWidth(1f))
                    .apply { minZoom = 12f }
            )
            style.addLayer(
                LineLayer(Q_ROAD_NAME, SOURCE_ID).withSourceLayer("transportation_name")
                    .withFilter(classFilter(ROAD_CLASSES))
                    .withProperties(lineOpacity(QUERY_OPACITY), lineWidth(1f))
                    .apply { minZoom = 12f }
            )
            true
        }
    } catch (e: Exception) {
        Log.w(TAG, "Query layers not installed: ${e.message}")
        false
    }
}

/**
 * Debounced label: a new non-null value replaces a shown one only after [confirm] consecutive
 * offers (no flicker at tile edges / junctions); null offers clear it after [dropAfterMisses].
 */
internal class StickyValue<T : Any>(private val confirm: Int, private val dropAfterMisses: Int) {
    var value: T? = null
        private set
    private var pending: T? = null
    private var pendingCount = 0
    private var misses = 0

    /** Returns true when [value] changed. */
    fun offer(candidate: T?): Boolean {
        val before = value
        when {
            candidate == null -> {
                pending = null; pendingCount = 0
                if (++misses >= dropAfterMisses) value = null
            }
            candidate == value -> { misses = 0; pending = null; pendingCount = 0 }
            value == null -> { value = candidate; misses = 0; pending = null; pendingCount = 0 }
            else -> {
                misses = 0
                if (candidate == pending) pendingCount++ else { pending = candidate; pendingCount = 1 }
                if (pendingCount >= confirm) { value = candidate; pending = null; pendingCount = 0 }
            }
        }
        return before != value
    }

    fun reset() {
        value = null; pending = null; pendingCount = 0; misses = 0
    }
}
