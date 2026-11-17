package com.antigravity.copilot.logic

import com.antigravity.copilot.core.map.CarMapController
import com.antigravity.copilot.core.map.PmTilesReader
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
        assertEquals(17.5, z(5.0), 1e-9)
        assertEquals(16.75, z(17.5), 1e-9)
        assertEquals(16.0, z(30.0), 1e-9)
        assertEquals(15.4, z(45.0), 1e-9)
        assertEquals(14.8, z(60.0), 1e-9)
        assertEquals(13.5, z(100.0), 1e-9)
        assertEquals(13.5, z(160.0), 1e-9)
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
    fun `tile cache is bounded by bytes and evicts least recently used`() {
        val cache = PmTilesReader.ByteLruCache(maxBytes = 100)
        cache.put(1, ByteArray(40))
        cache.put(2, ByteArray(40))
        assertNotNull(cache.get(1)) // 1 becomes most recently used
        cache.put(3, ByteArray(40)) // 120 > 100 -> evict LRU (2)
        assertNull(cache.get(2))
        assertNotNull(cache.get(1))
        assertNotNull(cache.get(3))
        assertEquals(80L, cache.sizeBytes)
        cache.put(4, ByteArray(500)) // larger than the whole budget: ignored
        assertNull(cache.get(4))
        assertEquals(2, cache.entryCount)
        cache.clear()
        assertEquals(0L, cache.sizeBytes)
    }

    @Test
    fun `default tile cache budget is 50 MB`() {
        assertEquals(50L * 1024 * 1024, PmTilesReader.DEFAULT_TILE_CACHE_BYTES)
        assertTrue(PmTilesReader.recommendedTileCacheBytes() <= PmTilesReader.DEFAULT_TILE_CACHE_BYTES)
    }
}
