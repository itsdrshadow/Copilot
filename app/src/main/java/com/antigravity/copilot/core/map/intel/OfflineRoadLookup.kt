package com.antigravity.copilot.core.map.intel

import com.antigravity.copilot.core.location.MapRoadObservation
import com.antigravity.copilot.core.map.tiles.MvtDecoder
import com.antigravity.copilot.core.map.tiles.PmtilesReader
import com.antigravity.copilot.core.map.tiles.TileMath
import com.antigravity.copilot.core.map.tiles.VtFeature
import java.io.File
import kotlin.math.abs

/** A POI read from the vector tiles (OpenMapTiles `poi` layer). */
data class TilePoi(val name: String, val poiClass: String?, val subclass: String?, val position: LonLat)

/**
 * Road and POI lookup straight from the PMTiles file (audit J1), independent of the map view:
 * the tracking service uses it when no rendered-map match is fresh (map hidden, another tab or
 * app in front), so the speed limit (I1), hazard alerts (I3) and pump suggestions (H6) keep
 * working. Decoded tiles are cached (a z14 tile spans ~2 km).
 */
class OfflineRoadLookup {
    private var reader: PmtilesReader? = null
    private var readerFile: File? = null
    private val tileCache = object : LinkedHashMap<String, Map<String, List<VtFeature>>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, List<VtFeature>>>?) = size > TILE_CACHE
    }

    @Synchronized
    fun open(file: File?) {
        if (file == readerFile && reader != null) return
        runCatching { reader?.close() }
        reader = null
        readerFile = file
        tileCache.clear()
        if (file != null && file.isFile) reader = runCatching { PmtilesReader(file) }.getOrNull()
    }

    @Synchronized
    fun close() = open(null)

    private fun zoom(r: PmtilesReader) = minOf(QUERY_ZOOM, r.header.maxZoom)

    @Synchronized
    private fun layers(z: Int, x: Int, y: Int): Map<String, List<VtFeature>> {
        val key = "$z/$x/$y"
        tileCache[key]?.let { return it }
        val r = reader ?: return emptyMap()
        val decoded = try {
            r.tile(z, x, y)?.let { MvtDecoder.decode(it, z, x, y, LAYERS) } ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
        tileCache[key] = decoded
        return decoded
    }

    /** Matched road under the car as the speed-limit engine's map observation (null = none). */
    fun roadObservation(lat: Double, lon: Double, headingDeg: Double, speedKmh: Double, previous: RoadLine?): Pair<MapRoadObservation, RoadLine>? {
        val r = synchronized(this) { reader } ?: return null
        val z = zoom(r)
        val l = layers(z, TileMath.lonToTileX(lon, z), TileMath.latToTileY(lat, z))
        val car = LonLat(lon, lat)
        val roads = l[LAYER_ROADS].orEmpty().flatMap(::toRoadLines)
        val match = RoadGeometry.matchRoad(car, headingDeg, speedKmh, roads, previous, MATCH_DISTANCE_M) ?: return null
        val names = l[LAYER_NAMES].orEmpty().flatMap(::toRoadLines)
        val sameClass = names.filter { it.roadClass == match.line.roadClass }
        val nameRoad = RoadGeometry.matchRoad(car, headingDeg, speedKmh, sameClass.ifEmpty { names }, null, MATCH_DISTANCE_M)?.line
        val label = RoadGeometry.roadLabel(nameRoad?.ref, nameRoad?.name, match.line.roadClass)
        return MapRoadObservation(
            roadClass = match.line.roadClass,
            ref = RoadGeometry.formatRef(nameRoad?.ref),
            name = nameRoad?.name,
            maxspeedRaw = match.line.maxspeed ?: nameRoad?.maxspeed,
            isRamp = match.line.isRamp,
            isExpressway = match.line.isExpressway,
            label = label ?: RoadGeometry.classLabel(match.line.roadClass)
        ) to match.line
    }

    /** POIs in the 3 × 3 tile block around the car. */
    fun poisAround(lat: Double, lon: Double): List<TilePoi> {
        val r = synchronized(this) { reader } ?: return emptyList()
        val z = zoom(r)
        val cx = TileMath.lonToTileX(lon, z)
        val cy = TileMath.latToTileY(lat, z)
        val max = (1 shl z) - 1
        val out = ArrayList<TilePoi>()
        for (dx in -1..1) for (dy in -1..1) {
            val x = cx + dx
            val y = cy + dy
            if (x !in 0..max || y !in 0..max) continue
            layers(z, x, y)[LAYER_POI].orEmpty().forEach { f ->
                val p = f.parts.firstOrNull()?.firstOrNull() ?: return@forEach
                val name = NAME_KEYS.firstNotNullOfOrNull { f.str(it) } ?: ""
                out += TilePoi(name, f.str("class"), f.str("subclass"), LonLat(p[0], p[1]))
            }
        }
        return out
    }

    private fun toRoadLines(f: VtFeature): List<RoadLine> {
        if (f.type != VtFeature.LINESTRING) return emptyList()
        val name = NAME_KEYS.firstNotNullOfOrNull { f.str(it) }
        val maxspeed = f.str("maxspeed") ?: f.num("maxspeed")?.toInt()?.toString()
        return f.parts.filter { it.size >= 2 }.map { pts ->
            RoadLine(
                pts.map { LonLat(it[0], it[1]) },
                f.str("class"), f.str("brunnel"),
                f.num("ramp")?.toInt() == 1, f.num("expressway")?.toInt() == 1,
                name, f.str("ref"), maxspeed
            )
        }
    }

    companion object {
        const val QUERY_ZOOM = 14
        const val TILE_CACHE = 12
        const val MATCH_DISTANCE_M = 30.0
        const val LAYER_ROADS = "transportation"
        const val LAYER_NAMES = "transportation_name"
        const val LAYER_POI = "poi"
        val LAYERS = setOf(LAYER_ROADS, LAYER_NAMES, LAYER_POI)
        private val NAME_KEYS = arrayOf("name:en", "name_en", "name:latin", "name")
    }
}

/** School-zone / speed-camera ahead alert (audit I3). */
enum class HazardKind { SCHOOL_ZONE, SPEED_CAMERA }

data class HazardAlert(val kind: HazardKind, val name: String, val distanceM: Double)

object RoadHazardRules {
    const val MAX_AHEAD_M = 300.0
    const val CONE_DEG = 35.0
    private val SCHOOL_SUBCLASSES = setOf("school", "kindergarten")

    fun kindOf(poi: TilePoi): HazardKind? = when {
        poi.subclass == "speed_camera" || poi.poiClass == "speed_camera" -> HazardKind.SPEED_CAMERA
        poi.poiClass == "school" && (poi.subclass == null || poi.subclass in SCHOOL_SUBCLASSES) -> HazardKind.SCHOOL_ZONE
        else -> null
    }

    /** Nearest hazard in the ahead cone within [MAX_AHEAD_M] (same cone idea as the pothole tracker). */
    fun ahead(car: LonLat, headingDeg: Double, pois: List<TilePoi>): HazardAlert? {
        if (!headingDeg.isFinite()) return null
        var best: HazardAlert? = null
        for (p in pois) {
            val kind = kindOf(p) ?: continue
            val d = RoadGeometry.distanceM(car, p.position)
            if (d > MAX_AHEAD_M || d < 5.0) continue
            val rel = abs(RoadGeometry.signedDelta(headingDeg, RoadGeometry.bearingDeg(car, p.position)))
            if (rel > CONE_DEG) continue
            if (best == null || d < best.distanceM) best = HazardAlert(kind, p.name, d)
        }
        return best
    }
}

/** Low-fuel pump suggestion (audit H6): nearest good pump, preferring the travel direction. */
object FuelPumpSuggester {
    data class Candidate(val name: String, val position: LonLat, val rating: String?)
    data class Suggestion(val name: String, val distanceM: Double, val rating: String?, val ahead: Boolean)

    const val MAX_DISTANCE_M = 25_000.0

    fun suggest(car: LonLat, headingDeg: Double, candidates: List<Candidate>): Suggestion? {
        var best: Suggestion? = null
        var bestScore = Double.MAX_VALUE
        for (c in candidates) {
            val d = RoadGeometry.distanceM(car, c.position)
            if (d > MAX_DISTANCE_M) continue
            val ahead = !headingDeg.isFinite() ||
                abs(RoadGeometry.signedDelta(headingDeg, RoadGeometry.bearingDeg(car, c.position))) <= 90.0 || d < 300.0
            val ratingFactor = when (c.rating) {
                "EXCELLENT" -> 0.8
                "POOR" -> 1.3
                else -> 1.0
            }
            val score = d * ratingFactor * (if (ahead) 1.0 else 2.0)
            if (score < bestScore) {
                bestScore = score
                best = Suggestion(c.name.ifBlank { "Fuel station" }, d, c.rating, ahead)
            }
        }
        return best
    }
}
