package com.antigravity.copilot.core.logic

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object GeoMath {
    const val EARTH_RADIUS_M = 6_371_008.8

    /** Great-circle (haversine) distance in metres. */
    fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Metres per degree of latitude (constant enough for grid cells). */
    const val M_PER_DEG_LAT = 111_320.0

    fun mPerDegLon(lat: Double): Double = M_PER_DEG_LAT * cos(Math.toRadians(lat)).coerceAtLeast(1e-6)
}
