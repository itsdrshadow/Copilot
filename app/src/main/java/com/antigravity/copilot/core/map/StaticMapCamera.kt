package com.antigravity.copilot.core.map

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.tan

/**
 * A flat (no tilt, north-up) Web-Mercator camera over a [widthPx] x [heightPx] image.
 *
 * Uses the same projection as MapLibre (world = 512 px * 2^zoom), so a MapSnapshotter image taken
 * at this camera and a Compose Canvas overlay projected with [project] line up pixel-for-pixel.
 */
data class StaticMapCamera(
    val centerLat: Double,
    val centerLon: Double,
    val zoom: Double,
    val widthPx: Int,
    val heightPx: Int
) {
    private val worldSize: Double get() = TILE_SIZE * Math.pow(2.0, zoom)

    /** Screen x/y (px) for a coordinate; returned packed as [x, y]. */
    fun project(lat: Double, lon: Double): FloatArray {
        val ws = worldSize
        val x = (mercX(lon) - mercX(centerLon)) * ws + widthPx / 2.0
        val y = (mercY(lat) - mercY(centerLat)) * ws + heightPx / 2.0
        return floatArrayOf(x.toFloat(), y.toFloat())
    }

    /** Inverse of [project]: screen px → (lat, lon). */
    fun unproject(x: Float, y: Float): Pair<Double, Double> {
        val ws = worldSize
        val mx = mercX(centerLon) + (x - widthPx / 2.0) / ws
        val my = mercY(centerLat) + (y - heightPx / 2.0) / ws
        return invMercY(my) to (mx * 360.0 - 180.0)
    }

    /** Ground metres per screen pixel at [lat]. */
    fun metersPerPixel(lat: Double = centerLat): Double =
        cos(Math.toRadians(lat)) * EARTH_CIRCUMFERENCE_M / worldSize

    companion object {
        const val TILE_SIZE = 512.0
        private const val EARTH_CIRCUMFERENCE_M = 40_075_016.686
        private const val MAX_LAT = 85.0511
        const val MIN_ZOOM = 2.0
        const val MAX_ZOOM = 18.0

        fun isValidCoordinate(lat: Double, lon: Double): Boolean =
            lat.isFinite() && lon.isFinite() && lat in -MAX_LAT..MAX_LAT && lon in -180.0..180.0 &&
                !(lat == 0.0 && lon == 0.0)

        /** Mercator x in [0, 1]. */
        fun mercX(lon: Double): Double = (lon + 180.0) / 360.0

        /** Mercator y in [0, 1] (0 = north). */
        fun mercY(lat: Double): Double {
            val l = Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT))
            return (1.0 - ln(tan(PI / 4 + l / 2)) / PI) / 2.0
        }

        fun invMercY(y: Double): Double = Math.toDegrees(2 * atan(exp((1.0 - 2.0 * y) * PI)) - PI / 2)

        /**
         * Camera that fits the bounding box into the image with [paddingPx] on every side.
         * A degenerate box (single point / stationary trip) gets [maxZoom] instead of infinity.
         * Returns null for an empty image or invalid coordinates.
         */
        fun fit(
            minLat: Double, maxLat: Double, minLon: Double, maxLon: Double,
            widthPx: Int, heightPx: Int, paddingPx: Int,
            maxZoom: Double = 16.5
        ): StaticMapCamera? {
            if (widthPx <= 0 || heightPx <= 0) return null
            if (!isValidCoordinate(minLat, minLon) || !isValidCoordinate(maxLat, maxLon)) return null
            val x0 = mercX(minLon); val x1 = mercX(maxLon)
            val y0 = mercY(maxLat); val y1 = mercY(minLat)
            val availW = max(1, widthPx - 2 * paddingPx).toDouble()
            val availH = max(1, heightPx - 2 * paddingPx).toDouble()
            val spanX = x1 - x0
            val spanY = y1 - y0
            val zx = if (spanX > 1e-12) log2(availW / (spanX * TILE_SIZE)) else maxZoom
            val zy = if (spanY > 1e-12) log2(availH / (spanY * TILE_SIZE)) else maxZoom
            val zoom = minOf(zx, zy, maxZoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
            val cx = (x0 + x1) / 2
            val cy = (y0 + y1) / 2
            return StaticMapCamera(invMercY(cy), cx * 360.0 - 180.0, zoom, widthPx, heightPx)
        }

        /** Camera centred on a circle of [radiusMeters] so its diameter spans [fill] of the shorter side. */
        fun forCircle(
            lat: Double, lon: Double, radiusMeters: Double,
            widthPx: Int, heightPx: Int, fill: Double = 0.6
        ): StaticMapCamera? {
            if (widthPx <= 0 || heightPx <= 0 || !isValidCoordinate(lat, lon) || radiusMeters <= 0) return null
            val targetPx = minOf(widthPx, heightPx) * fill
            val mpp = 2 * radiusMeters / targetPx
            val zoom = log2(cos(Math.toRadians(lat)) * EARTH_CIRCUMFERENCE_M / (mpp * TILE_SIZE))
                .coerceIn(MIN_ZOOM, MAX_ZOOM)
            return StaticMapCamera(lat, lon, zoom, widthPx, heightPx)
        }
    }
}
