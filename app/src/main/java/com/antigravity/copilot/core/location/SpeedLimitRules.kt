package com.antigravity.copilot.core.location

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pure speed-limit rules (no Android, unit-tested):
 * - [parseMaxspeed]: OpenStreetMap `maxspeed` values → km/h;
 * - [estimateForRoad]: MoRTH statutory limit for an OpenMapTiles road class when no tag exists;
 * - [OverspeedDebouncer]: hysteresis so a GPS spike never flashes the red overspeed state.
 *
 * MoRTH notification S.O. 1522(E), M1 passenger cars: expressway 120, national highway 100,
 * state highway / primary 80, district roads 70, urban 50, residential 40.
 */
object SpeedLimitRules {

    /** An Overpass way with an explicit maxspeed and its geometry (lat, lon). */
    class WayCandidate(val limitKmh: Int, val points: List<Pair<Double, Double>>, val oneway: Boolean)

    const val WAY_MAX_DISTANCE_M = 25.0
    const val WAY_MAX_HEADING_DIFF_DEG = 35.0

    /**
     * Audit I2: the nearest way whose direction matches the heading (axis-agnostic for two-way
     * roads, direction-aware for one-ways). Without a heading, the nearest way wins.
     */
    fun bestWay(lat: Double, lon: Double, headingDeg: Double, ways: List<WayCandidate>): WayCandidate? {
        val kx = 111_320.0 * Math.cos(Math.toRadians(lat))
        val ky = 110_540.0
        var best: WayCandidate? = null
        var bestDist = Double.MAX_VALUE
        for (w in ways) {
            for (i in 0 until w.points.size - 1) {
                val (aLat, aLon) = w.points[i]
                val (bLat, bLon) = w.points[i + 1]
                val ax = (aLon - lon) * kx; val ay = (aLat - lat) * ky
                val bx = (bLon - lon) * kx; val by = (bLat - lat) * ky
                val dx = bx - ax; val dy = by - ay
                val len2 = dx * dx + dy * dy
                if (len2 < 1e-6) continue
                val t = (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
                val px = ax + t * dx; val py = ay + t * dy
                val d = Math.hypot(px, py)
                if (d > WAY_MAX_DISTANCE_M || d >= bestDist) continue
                if (headingDeg.isFinite()) {
                    val segBearing = (Math.toDegrees(Math.atan2(dx, dy)) + 360.0) % 360.0
                    var diff = Math.abs(((segBearing - headingDeg) % 360.0 + 540.0) % 360.0 - 180.0)
                    if (!w.oneway) diff = minOf(diff, 180.0 - diff)
                    if (diff > WAY_MAX_HEADING_DIFF_DEG) continue
                }
                best = w
                bestDist = d
            }
        }
        return best
    }

    private const val MPH_TO_KMH = 1.609344
    private val NUMBER_WITH_UNIT = Regex("""^(\d+(?:\.\d+)?)\s*(km/h|kmh|kph|mph|knots)?$""")

    /**
     * OSM `maxspeed` → km/h, or null if absent / not a number (`none`, `signals`, `variable`…).
     * Handles `"60"`, `"60 km/h"`, `"40 mph"`, India zone codes (`IN:urban`…) and multi-values
     * (`"60;40"` → the lower, conservative one).
     */
    fun parseMaxspeed(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val values = raw.split(';', '|').mapNotNull { parseSingle(it) }
        return values.minOrNull()
    }

    private fun parseSingle(raw: String): Int? {
        val v = raw.trim().lowercase(Locale.US)
        when (v) {
            "in:motorway", "in:expressway" -> return 120
            "in:trunk", "in:nh", "in:national" -> return 100
            "in:rural", "in:sh" -> return 80
            "in:urban", "in:city" -> return 50
            "walk", "in:living_street" -> return 15
        }
        val m = NUMBER_WITH_UNIT.matchEntire(v) ?: return null
        val n = m.groupValues[1].toDoubleOrNull() ?: return null
        val kmh = when (m.groupValues[2]) {
            "mph" -> n * MPH_TO_KMH
            "knots" -> n * 1.852
            else -> n
        }.roundToInt()
        return kmh.takeIf { it in 5..200 }
    }

    data class Estimate(val limitKmh: Int, val zone: String)

    /**
     * MoRTH limit for an OpenMapTiles `transportation` feature. [roadClass] is the OMT class
     * (motorway, trunk, primary, secondary, tertiary, minor, service, track); [ref] the road number
     * from `transportation_name` (NE-/NH-/SH- refine the class); [isRamp] slip roads.
     */
    fun estimateForRoad(roadClass: String?, ref: String? = null, isRamp: Boolean = false, isExpressway: Boolean = false): Estimate {
        val r = ref.orEmpty().uppercase(Locale.US).replace(" ", "").replace("-", "")
        val base = when {
            roadClass == "motorway" || isExpressway || r.startsWith("NE") -> Estimate(120, "Expressway")
            roadClass == "trunk" || r.startsWith("NH") -> Estimate(100, "National Highway")
            roadClass == "primary" || r.startsWith("SH") -> Estimate(80, "State Highway / Primary")
            roadClass == "secondary" -> Estimate(70, "District Road")
            roadClass == "tertiary" -> Estimate(50, "Urban Road")
            roadClass == "minor" -> Estimate(40, "Residential Street")
            roadClass == "service" || roadClass == "track" -> Estimate(30, "Service Road")
            else -> Estimate(50, "Urban Road")
        }
        return if (isRamp) Estimate(minOf(base.limitKmh, 60), "${base.zone} ramp") else base
    }

    /**
     * Overspeed with hysteresis: turns ON only after [confirmSamples] consecutive readings above
     * limit + [bufferKmh]; turns OFF as soon as speed is back at or below the limit. Between the
     * two thresholds the current state holds, so hovering around the limit never flickers.
     */
    class OverspeedDebouncer(
        private val bufferKmh: Float = 3f,
        private val confirmSamples: Int = 2,
        private val nearFraction: Float = 0.92f
    ) {
        var isOverspeed = false
            private set
        var isNearLimit = false
            private set
        private var overCount = 0

        /** Feed one speed reading; returns true if either flag changed. */
        fun update(speedKmh: Float, limitKmh: Int): Boolean {
            val wasOver = isOverspeed
            val wasNear = isNearLimit
            if (limitKmh <= 0 || !speedKmh.isFinite()) {
                overCount = 0
                isOverspeed = false
                isNearLimit = false
            } else {
                if (speedKmh > limitKmh + bufferKmh) {
                    overCount++
                    if (overCount >= confirmSamples) isOverspeed = true
                } else {
                    overCount = 0
                    if (speedKmh <= limitKmh) isOverspeed = false
                }
                isNearLimit = !isOverspeed && speedKmh >= limitKmh * nearFraction
            }
            return wasOver != isOverspeed || wasNear != isNearLimit
        }

        /**
         * The limit changed without a new speed reading (new road matched): never turns
         * overspeed ON (that needs real readings), only clears it / refreshes near-limit.
         */
        fun onLimitChanged(speedKmh: Float, limitKmh: Int): Boolean {
            val wasOver = isOverspeed
            val wasNear = isNearLimit
            if (limitKmh <= 0 || speedKmh <= limitKmh) isOverspeed = false
            if (speedKmh <= limitKmh + bufferKmh) overCount = 0
            isNearLimit = limitKmh > 0 && !isOverspeed && speedKmh >= limitKmh * nearFraction
            return wasOver != isOverspeed || wasNear != isNearLimit
        }

        fun reset() {
            overCount = 0
            isOverspeed = false
            isNearLimit = false
        }
    }
}
