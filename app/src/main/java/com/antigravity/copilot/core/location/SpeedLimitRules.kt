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
