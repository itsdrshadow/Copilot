package com.antigravity.copilot.logic

import com.antigravity.copilot.core.map.CarMapController
import com.antigravity.copilot.core.map.MapMemoryGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapPerformanceTest {

    @Test
    fun `auto zoom follows the speed bands`() {
        val z = CarMapController.Companion::autoZoomForSpeed
        assertEquals(17.5, z(0.0), 1e-9)
        assertEquals(17.5, z(15.0), 1e-9)
        assertEquals(17.0, z(40.0), 1e-9)
        assertEquals(16.2, z(70.0), 1e-9)
        assertEquals(15.2, z(100.0), 1e-9)
        assertEquals(14.8, z(130.0), 1e-9)
        assertEquals(14.8, z(160.0), 1e-9)
        // Town speeds stay close in (regression: was 16.0 at 30 km/h).
        assertTrue(z(30.0) >= 17.1)
        assertEquals(17.5, z(-4.0), 1e-9)
    }

    @Test
    fun `auto zoom is continuous and never increases with speed`() {
        var prev = CarMapController.autoZoomForSpeed(0.0)
        var v = 0.0
        while (v <= 140.0) {
            val cur = CarMapController.autoZoomForSpeed(v)
            assertTrue("zoom rose at $v km/h", cur <= prev + 1e-12)
            assertTrue("zoom stepped at $v km/h", prev - cur < 0.01)
            prev = cur
            v += 0.1
        }
    }

    @Test
    fun `speed ema factor scales with sample interval`() {
        val a1 = CarMapController.emaAlpha(1_000L)
        val a2 = CarMapController.emaAlpha(2_000L)
        assertTrue(a1 in 0.0..1.0)
        assertTrue(a2 > a1)
        // Two 1 s steps must equal one 2 s step (interval-independent smoothing)
        assertEquals(1 - (1 - a1) * (1 - a1), a2, 1e-12)
    }

    @Test
    fun `memory guard trims, then disables the tile cache, then re-enables it`() {
        val g = MapMemoryGuard.Companion
        assertEquals(MapMemoryGuard.Action.NONE, g.decide(100, cacheEnabled = true, msSinceLastTrim = 60_000))
        assertEquals(MapMemoryGuard.Action.TRIM, g.decide(200, cacheEnabled = true, msSinceLastTrim = 60_000))
        assertEquals(MapMemoryGuard.Action.NONE, g.decide(200, cacheEnabled = true, msSinceLastTrim = 1_000)) // cooldown
        assertEquals(MapMemoryGuard.Action.DISABLE_CACHE, g.decide(300, cacheEnabled = true, msSinceLastTrim = 0))
        assertEquals(MapMemoryGuard.Action.NONE, g.decide(200, cacheEnabled = false, msSinceLastTrim = 0)) // hysteresis
        assertEquals(MapMemoryGuard.Action.ENABLE_CACHE, g.decide(150, cacheEnabled = false, msSinceLastTrim = 0))
    }
}
