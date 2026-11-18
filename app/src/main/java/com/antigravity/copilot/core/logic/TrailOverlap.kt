package com.antigravity.copilot.core.logic

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max

data class LatLon(val lat: Double, val lon: Double)

/** A run of consecutive trail points that share the same pass count. */
data class TrailRun(val points: List<LatLon>, val passes: Int)

enum class PassColor(val argb: Long) {
    BLUE(0xFF2F80EDL), ORANGE(0xFFF2994AL), RED(0xFFEB5757L);

    companion object {
        fun forPasses(passes: Int) = when {
            passes >= 3 -> RED
            passes == 2 -> ORANGE
            else -> BLUE
        }
    }
}

/**
 * Overlap colouring for trails: a ~15 m grid counts how many separate times the vehicle passed
 * each spot (1 pass blue, 2 orange, 3+ red). Jitter back and forth across a cell edge is not a
 * new pass: a cell (and its 8 neighbours, so the opposite lane counts as the same road) only
 * registers a new pass when the vehicle has travelled [revisitM] since it was last there.
 * Runs are simplified with Douglas–Peucker for rendering performance.
 */
object TrailOverlap {

    fun passCounts(
        points: List<LatLon>,
        gridM: Double = Config.OVERLAP_GRID_M,
        revisitM: Double = gridM * 10
    ): IntArray {
        if (points.isEmpty()) return IntArray(0)
        val refLat = points.first().lat
        val mLon = GeoMath.mPerDegLon(refLat)
        fun xy(p: LatLon) = Pair(p.lon * mLon, p.lat * GeoMath.M_PER_DEG_LAT)
        fun cellOf(x: Double, y: Double) = Pair(floor(x / gridM).toLong(), floor(y / gridM).toLong())

        val visitStarts = HashMap<Pair<Long, Long>, MutableList<Double>>()
        val lastSeen = HashMap<Pair<Long, Long>, Double>()
        val pointCell = ArrayList<Pair<Long, Long>>(points.size)
        val pointDist = DoubleArray(points.size)

        var cum = 0.0
        var prev: Pair<Double, Double>? = null
        fun visit(c: Pair<Long, Long>, d: Double) {
            val last = lastSeen[c]
            if (last == null || d - last > revisitM) visitStarts.getOrPut(c) { ArrayList() }.add(d)
            lastSeen[c] = d
        }
        for ((i, p) in points.withIndex()) {
            val (x, y) = xy(p)
            val pr = prev
            if (pr != null) {
                val seg = hypot(x - pr.first, y - pr.second)
                // Densify long segments so no cell along the way is skipped.
                val steps = ceil(seg / (gridM / 2)).toInt()
                for (k in 1 until steps) {
                    val f = k.toDouble() / steps
                    visit(cellOf(pr.first + (x - pr.first) * f, pr.second + (y - pr.second) * f), cum + seg * f)
                }
                cum += seg
            }
            val c = cellOf(x, y)
            visit(c, cum)
            pointCell += c
            pointDist[i] = cum
            prev = Pair(x, y)
        }

        val out = IntArray(points.size)
        for (i in points.indices) {
            val (cx, cy) = pointCell[i]
            val starts = ArrayList<Double>()
            for (dx in -1..1) for (dy in -1..1) visitStarts[Pair(cx + dx, cy + dy)]?.let { starts += it }
            starts.sort()
            var passes = 0
            var clusterEnd = Double.NEGATIVE_INFINITY
            for (s in starts) {
                if (s - clusterEnd > revisitM) passes++
                clusterEnd = max(clusterEnd, s)
            }
            out[i] = max(1, passes)
        }
        return out
    }

    /** Douglas–Peucker; returns the kept indices (always includes first and last). */
    fun simplify(points: List<LatLon>, toleranceM: Double = Config.TRAIL_SIMPLIFY_TOLERANCE_M): List<Int> {
        if (points.size <= 2) return points.indices.toList()
        val refLat = points.first().lat
        val mLon = GeoMath.mPerDegLon(refLat)
        val xs = DoubleArray(points.size) { points[it].lon * mLon }
        val ys = DoubleArray(points.size) { points[it].lat * GeoMath.M_PER_DEG_LAT }
        val keep = BooleanArray(points.size)
        keep[0] = true; keep[points.lastIndex] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.lastIndex)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            if (b - a < 2) continue
            var maxD = -1.0
            var idx = -1
            for (i in a + 1 until b) {
                val d = perpendicular(xs[i], ys[i], xs[a], ys[a], xs[b], ys[b])
                if (d > maxD) { maxD = d; idx = i }
            }
            if (maxD > toleranceM) {
                keep[idx] = true
                stack.addLast(a to idx); stack.addLast(idx to b)
            }
        }
        return keep.indices.filter { keep[it] }
    }

    private fun perpendicular(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val len = hypot(dx, dy)
        if (len < 1e-9) return hypot(px - ax, py - ay)
        return abs(dy * px - dx * py + bx * ay - by * ax) / len
    }

    /** Coloured, simplified runs ready for a map line layer. Adjacent runs share an endpoint. */
    fun runs(
        points: List<LatLon>,
        gridM: Double = Config.OVERLAP_GRID_M,
        toleranceM: Double = Config.TRAIL_SIMPLIFY_TOLERANCE_M,
        maxPoints: Int = Config.TRAIL_MAX_RENDER_POINTS
    ): List<TrailRun> {
        if (points.size < 2) return emptyList()
        // Hard cap for very long trails: uniform decimation before the grid (keeps ends).
        val src = if (points.size > maxPoints * 4) {
            val step = points.size.toDouble() / (maxPoints * 4)
            (0 until maxPoints * 4).map { points[(it * step).toInt()] } + points.last()
        } else points
        val passes = passCounts(src, gridM)
        val result = ArrayList<TrailRun>()
        var start = 0
        for (i in 1..src.size) {
            if (i == src.size || passes[i] != passes[start]) {
                val end = minOf(i, src.lastIndex) // share the boundary point with the next run
                val slice = src.subList(start, end + 1)
                if (slice.size >= 2) {
                    val kept = simplify(slice, toleranceM).map { slice[it] }
                    result += TrailRun(kept, passes[start])
                }
                start = i
            }
        }
        return result
    }
}

/**
 * Old-trip point retention (audit M3): trips older than [Config]-style 90 days keep a
 * Douglas–Peucker (5 m) simplified track plus every point that borders a time gap (stops,
 * tunnels), so the replay keeps its shape and timing while 1 Hz storage shrinks.
 */
object TripPointRetention {
    const val TOLERANCE_M = 5.0
    const val KEEP_GAP_MS = 60_000L
    const val AGE_MS = 90L * 24 * 60 * 60 * 1000

    /** Indices to keep (sorted, always includes first and last). */
    fun keepIndices(points: List<LatLon>, timestampsMs: List<Long>, toleranceM: Double = TOLERANCE_M): List<Int> {
        if (points.size <= 2) return points.indices.toList()
        val keep = java.util.TreeSet(TrailOverlap.simplify(points, toleranceM))
        for (i in 1 until timestampsMs.size) {
            if (timestampsMs[i] - timestampsMs[i - 1] >= KEEP_GAP_MS) { keep += i - 1; keep += i }
        }
        return keep.toList()
    }
}
