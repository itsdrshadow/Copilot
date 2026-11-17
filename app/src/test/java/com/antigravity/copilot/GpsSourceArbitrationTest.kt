package com.antigravity.copilot

import com.antigravity.copilot.core.map.GpsLocationFilter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A far-off coarse first fix (network / stale cache) must not lock the map away from the real GNSS
 * position, and a coarse fix must never drag a live GNSS anchor across the map.
 */
class GpsSourceArbitrationTest {

    // Odisha (real GNSS position) and Mountain View (emulator / bogus network position).
    private val gnssLat = 21.859
    private val gnssLon = 83.2008
    private val farLat = 37.422
    private val farLon = -122.084

    @Test
    fun preciseFixImmediatelyOverridesCoarseAnchor() {
        val filter = GpsLocationFilter()
        filter.filter(farLat, farLon, 0f, 0f, 1f, 1_000L, isPrecise = false)

        val state = filter.filter(gnssLat, gnssLon, 0f, 0f, 5f, 2_000L, isPrecise = true)

        assertEquals(gnssLat, state.latitude, 1e-6)
        assertEquals(gnssLon, state.longitude, 1e-6)
    }

    @Test
    fun coarseFixCannotMoveRecentPreciseAnchor() {
        val filter = GpsLocationFilter()
        filter.filter(gnssLat, gnssLon, 0f, 0f, 5f, 1_000L, isPrecise = true)

        repeat(10) { i ->
            val state = filter.filter(farLat, farLon, 0f, 0f, 1f, 2_000L + i * 1_000L, isPrecise = false)
            assertEquals(gnssLat, state.latitude, 1e-6)
        }
    }

    @Test
    fun consistentPreciseJumpStillReanchors() {
        val filter = GpsLocationFilter()
        filter.filter(gnssLat, gnssLon, 0f, 0f, 5f, 1_000L, isPrecise = true)

        var last = filter.filter(farLat, farLon, 0f, 0f, 5f, 2_000L, isPrecise = true)
        for (i in 1 until GpsLocationFilter.REANCHOR_AFTER_REJECTIONS) {
            last = filter.filter(farLat, farLon, 0f, 0f, 5f, 2_000L + i * 1_000L, isPrecise = true)
        }

        assertEquals(farLat, last.latitude, 1e-6)
    }
}
