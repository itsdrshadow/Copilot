package com.antigravity.copilot.core.map.intel

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** A WGS84 coordinate. */
data class LonLat(val lon: Double, val lat: Double)

/** One road polyline from the vector tiles (OpenMapTiles `transportation` / `transportation_name`). */
class RoadLine(
    val points: List<LonLat>,
    val roadClass: String? = null,
    val brunnel: String? = null,
    val isRamp: Boolean = false,
    val isExpressway: Boolean = false,
    val name: String? = null,
    val ref: String? = null,
    val maxspeed: String? = null
) {
    /** Same road attributes (tile queries return fresh objects every time). */
    fun sameRoadAs(o: RoadLine?): Boolean =
        o != null && roadClass == o.roadClass && brunnel == o.brunnel && name == o.name && ref == o.ref
}

data class PlaceCandidate(val name: String, val placeClass: String?, val position: LonLat)

data class RoadMatch(
    val line: RoadLine,
    val distanceM: Double,
    val segmentIndex: Int,
    /** Projected position on the segment, 0..1. */
    val t: Double,
    val segmentBearing: Double,
    /** 0..90°, direction-agnostic (a two-way road matches either heading). */
    val headingDiff: Double
)

/** [BOTH]: a junction where the road only continues by turning sharply, both ways (T-junction). */
enum class TurnDirection { LEFT, RIGHT, BOTH }

data class SharpTurn(val direction: TurnDirection, val distanceM: Double, val angleDeg: Double)

/**
 * Pure road geometry for offline road intelligence. All math is done in a local equirectangular
 * frame around the vehicle (metres), which is exact enough over the few hundred metres used.
 */
object RoadGeometry {

    private const val M_PER_DEG_LAT = 110_540.0
    private const val M_PER_DEG_LON_EQUATOR = 111_320.0

    fun distanceM(a: LonLat, b: LonLat): Double {
        val k = cos(Math.toRadians((a.lat + b.lat) / 2))
        return hypot((b.lon - a.lon) * M_PER_DEG_LON_EQUATOR * k, (b.lat - a.lat) * M_PER_DEG_LAT)
    }

    /** Compass bearing a → b, 0..360 (0 = north, 90 = east). */
    fun bearingDeg(a: LonLat, b: LonLat): Double {
        val k = cos(Math.toRadians((a.lat + b.lat) / 2))
        val dx = (b.lon - a.lon) * M_PER_DEG_LON_EQUATOR * k
        val dy = (b.lat - a.lat) * M_PER_DEG_LAT
        return (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
    }

    /** Point [distanceM] metres from [from] along [bearingDeg]. */
    fun offset(from: LonLat, bearingDeg: Double, distanceM: Double): LonLat {
        val r = Math.toRadians(bearingDeg)
        val k = cos(Math.toRadians(from.lat))
        return LonLat(
            lon = from.lon + distanceM * sin(r) / (M_PER_DEG_LON_EQUATOR * k),
            lat = from.lat + distanceM * cos(r) / M_PER_DEG_LAT
        )
    }

    /** Signed smallest difference b − a in −180..180 (positive = clockwise = right turn). */
    fun signedDelta(a: Double, b: Double): Double {
        var d = (b - a) % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return d
    }

    /** Direction-agnostic angle between a road axis and a heading, 0..90. */
    fun axisDiff(roadBearing: Double, heading: Double): Double {
        val d = abs(signedDelta(roadBearing, heading))
        return if (d > 90.0) 180.0 - d else d
    }

    /** Nearest point of [line] to [p]: (distance m, segment index, t, segment bearing), or null. */
    fun nearestOnLine(p: LonLat, line: RoadLine): RoadMatch? {
        val pts = line.points
        if (pts.size < 2) return null
        val k = cos(Math.toRadians(p.lat))
        fun x(q: LonLat) = (q.lon - p.lon) * M_PER_DEG_LON_EQUATOR * k
        fun y(q: LonLat) = (q.lat - p.lat) * M_PER_DEG_LAT
        var best: RoadMatch? = null
        for (i in 0 until pts.size - 1) {
            val ax = x(pts[i]); val ay = y(pts[i])
            val bx = x(pts[i + 1]); val by = y(pts[i + 1])
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            if (len2 < 1e-6) continue
            val t = (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val d = hypot(ax + t * dx, ay + t * dy)
            if (best == null || d < best.distanceM) {
                val bearing = (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
                best = RoadMatch(line, d, i, t, bearing, 0.0)
            }
        }
        return best
    }

    /**
     * The road the vehicle is on. Rejects roads whose direction disagrees with the heading by
     * more than [maxHeadingDiffDeg] (overpasses, crossing streets, perpendicular service roads)
     * once the GPS heading is trustworthy ([speedKmh] ≥ [minSpeedForHeading]). Ties go to the
     * nearest road, with a bonus for staying on [previous] (parallel service roads / flyovers).
     */
    fun matchRoad(
        car: LonLat,
        headingDeg: Double,
        speedKmh: Double,
        lines: List<RoadLine>,
        previous: RoadLine? = null,
        maxDistanceM: Double = 30.0,
        maxHeadingDiffDeg: Double = 35.0,
        minSpeedForHeading: Double = 10.0
    ): RoadMatch? {
        val useHeading = speedKmh >= minSpeedForHeading && headingDeg.isFinite()
        var best: RoadMatch? = null
        var bestScore = Double.MAX_VALUE
        for (line in lines) {
            val m = nearestOnLine(car, line) ?: continue
            if (m.distanceM > maxDistanceM) continue
            val diff = if (useHeading) axisDiff(m.segmentBearing, headingDeg) else 0.0
            if (useHeading && diff > maxHeadingDiffDeg) continue
            val score = m.distanceM + diff * 0.3 - if (line.sameRoadAs(previous)) 6.0 else 0.0
            if (score < bestScore) {
                bestScore = score
                best = m.copy(headingDiff = diff)
            }
        }
        return best
    }

    /**
     * The road ahead of the vehicle as one polyline: from the projected position along [match]
     * in the travel direction, continued through [others] at shared endpoints (tile edges and
     * way joins), always taking the straightest same-class continuation. Stops after [lengthM].
     */
    fun forwardPath(
        car: LonLat,
        headingDeg: Double,
        match: RoadMatch,
        others: List<RoadLine>,
        lengthM: Double,
        joinToleranceM: Double = 8.0,
        maxHops: Int = 8,
        /**
         * Called once, at the first join with no straight-ish way on (every continuation turns
         * ≥ [forkMinAngleDeg]) but sharp continuations to both the left and the right. Without
         * a navigation destination the driver's choice is unknown, so both are reported.
         */
        onFork: ((distanceM: Double) -> Unit)? = null,
        forkMinAngleDeg: Double = 50.0
    ): List<LonLat> {
        val pts = match.line.points
        val i = match.segmentIndex
        val forward = abs(signedDelta(match.segmentBearing, headingDeg)) <= 90.0
        val a = pts[i]; val b = pts[i + 1]
        val start = LonLat(a.lon + (b.lon - a.lon) * match.t, a.lat + (b.lat - a.lat) * match.t)
        val path = ArrayList<LonLat>()
        path += start
        var walked = 0.0
        fun add(p: LonLat): Boolean {
            walked += distanceM(path.last(), p)
            path += p
            return walked >= lengthM
        }
        val first = if (forward) pts.subList(i + 1, pts.size) else pts.subList(0, i + 1).asReversed()
        for (p in first) if (add(p)) return path

        val used = HashSet<String>()
        used += key(match.line)
        var currentClass = match.line.roadClass
        repeat(maxHops) {
            val end = path.last()
            val prevBearing = lastBearing(path) ?: return path
            var bestPts: List<LonLat>? = null
            var bestLine: RoadLine? = null
            var bestScore = Double.MAX_VALUE
            var minTurn = Double.MAX_VALUE
            var hasLeft = false
            var hasRight = false
            for (o in others) {
                if (o.points.size < 2 || key(o) in used) continue
                val oriented = when {
                    distanceM(o.points.first(), end) <= joinToleranceM -> o.points
                    distanceM(o.points.last(), end) <= joinToleranceM -> o.points.asReversed()
                    else -> continue
                }
                val nextBearing = firstBearing(oriented) ?: continue
                val signed = signedDelta(prevBearing, nextBearing)
                val turn = abs(signed)
                if (turn > 120.0) continue
                minTurn = min(minTurn, turn)
                if (signed <= -forkMinAngleDeg) hasLeft = true
                if (signed >= forkMinAngleDeg) hasRight = true
                val score = turn + if (o.roadClass != currentClass) 40.0 else 0.0
                if (score < bestScore) { bestScore = score; bestPts = oriented; bestLine = o }
            }
            val next = bestPts ?: return path
            if (onFork != null && hasLeft && hasRight && minTurn >= forkMinAngleDeg) {
                onFork(walked)
                return path // the way on is the driver's choice: stop at the junction
            }
            used += key(bestLine!!)
            currentClass = bestLine.roadClass
            for (p in next.drop(1)) if (add(p)) return path
        }
        return path
    }

    /**
     * Sharp bend on [path] (starting at the vehicle): the road turns by at least [minAngleDeg]
     * within [windowM], starting no further than [maxDistanceM] ahead. Distance is to the bend's
     * apex (the sharpest vertex). Vertices closer than [minSegmentM] are merged to ignore noise.
     */
    fun detectSharpTurn(
        path: List<LonLat>,
        maxDistanceM: Double,
        minAngleDeg: Double = 50.0,
        windowM: Double = 120.0,
        minSegmentM: Double = 5.0
    ): SharpTurn? {
        if (path.size < 3) return null
        // Resample: drop vertices closer than minSegmentM to the previous kept one.
        val pts = ArrayList<LonLat>()
        pts += path.first()
        for (p in path.drop(1)) if (distanceM(pts.last(), p) >= minSegmentM) pts += p
        if (pts.size < 3) return null
        val bearings = DoubleArray(pts.size - 1) { bearingDeg(pts[it], pts[it + 1]) }
        val startDist = DoubleArray(pts.size - 1)
        for (s in 1 until bearings.size) startDist[s] = startDist[s - 1] + distanceM(pts[s - 1], pts[s])

        for (i in bearings.indices) {
            if (startDist[i] > maxDistanceM) break
            var j = i + 1
            while (j < bearings.size && startDist[j] - startDist[i] <= windowM) {
                val change = signedDelta(bearings[i], bearings[j])
                if (abs(change) >= minAngleDeg) {
                    // Apex: the vertex with the largest single bearing change in (i..j].
                    var apex = i + 1
                    var apexTurn = 0.0
                    for (m in i + 1..j) {
                        val t = abs(signedDelta(bearings[m - 1], bearings[m]))
                        if (t > apexTurn) { apexTurn = t; apex = m }
                    }
                    return SharpTurn(
                        direction = if (change > 0) TurnDirection.RIGHT else TurnDirection.LEFT,
                        distanceM = startDist[apex],
                        angleDeg = abs(change)
                    )
                }
                j++
            }
        }
        return null
    }

    /** Look-ahead distance for the sharp-turn check: 80 m at ≤ 35 km/h → 150 m at ≥ 100 km/h. */
    fun lookAheadM(speedKmh: Double): Double =
        80.0 + ((speedKmh - 35.0) / 65.0).coerceIn(0.0, 1.0) * 70.0

    // ---------------- Labels ----------------

    private val INDIAN_REF = Regex("""^(NH|SH|NE|MDR|ODR|AH)\s*-?\s*([0-9]+[A-Z]?)$""", RegexOption.IGNORE_CASE)

    /** `"NH49"`, `"nh 49"`, `"NH49;SH1"` → `"NH-49"`; other refs are returned trimmed. */
    fun formatRef(ref: String?): String? {
        val first = ref?.split(';')?.firstOrNull()?.trim().orEmpty()
        if (first.isEmpty()) return null
        val m = INDIAN_REF.matchEntire(first) ?: return first
        return "${m.groupValues[1].uppercase()}-${m.groupValues[2].uppercase()}"
    }

    fun classLabel(roadClass: String?): String? = when (roadClass) {
        "motorway" -> "Expressway"
        "trunk" -> "Highway"
        "primary" -> "Main road"
        "secondary" -> "District road"
        "tertiary" -> "Local road"
        "minor" -> "Street"
        "service" -> "Service road"
        "track" -> "Track"
        else -> null
    }

    /** Road part of the pill: ref ("NH-49") over name over class. */
    fun roadLabel(ref: String?, name: String?, roadClass: String?): String? =
        formatRef(ref) ?: name?.trim()?.takeIf { it.isNotEmpty() } ?: classLabel(roadClass)

    fun pillText(road: String?, place: String?): String? =
        listOfNotNull(road, place?.let { "Near $it" }).joinToString(" • ").takeIf { it.isNotEmpty() }

    private val PLACE_WEIGHT = mapOf(
        "city" to 3.0, "town" to 2.5, "suburb" to 1.6, "village" to 1.4,
        "quarter" to 1.0, "neighbourhood" to 1.0, "hamlet" to 0.8
    )

    /** Nearest meaningful settlement; cities/towns win over a slightly closer hamlet. */
    fun choosePlace(car: LonLat, places: List<PlaceCandidate>): PlaceCandidate? =
        places.minByOrNull { distanceM(car, it.position) / (PLACE_WEIGHT[it.placeClass] ?: 0.8) }

    private fun key(l: RoadLine): String {
        val a = l.points.first(); val b = l.points.last()
        return "${(a.lon * 1e6).toLong()},${(a.lat * 1e6).toLong()}|${(b.lon * 1e6).toLong()},${(b.lat * 1e6).toLong()}"
    }

    private fun lastBearing(path: List<LonLat>): Double? {
        for (k in path.size - 1 downTo 1) {
            if (distanceM(path[k - 1], path[k]) > 0.5) return bearingDeg(path[k - 1], path[k])
        }
        return null
    }

    private fun firstBearing(pts: List<LonLat>): Double? {
        for (k in 1 until pts.size) {
            if (distanceM(pts[0], pts[k]) > 0.5) return bearingDeg(pts[0], pts[k])
        }
        return null
    }
}
