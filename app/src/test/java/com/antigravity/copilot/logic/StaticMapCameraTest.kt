package com.antigravity.copilot.logic

import com.antigravity.copilot.core.map.StaticMapCamera
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StaticMapCameraTest {

    @Test
    fun projectUnprojectRoundTrip() {
        val cam = StaticMapCamera(28.6139, 77.2090, 15.0, 800, 600)
        val xy = cam.project(28.62, 77.22)
        val (lat, lon) = cam.unproject(xy[0], xy[1])
        assertEquals(28.62, lat, 1e-6)
        assertEquals(77.22, lon, 1e-6)
        val c = cam.project(28.6139, 77.2090)
        assertEquals(400f, c[0], 0.01f)
        assertEquals(300f, c[1], 0.01f)
    }

    @Test
    fun fitKeepsBoundsInsidePadding() {
        val cam = StaticMapCamera.fit(28.50, 28.70, 77.10, 77.30, 1000, 600, 40)
        assertNotNull(cam)
        for ((lat, lon) in listOf(28.50 to 77.10, 28.70 to 77.30, 28.50 to 77.30, 28.70 to 77.10)) {
            val xy = cam!!.project(lat, lon)
            assertTrue("x=${xy[0]}", xy[0] >= 39f && xy[0] <= 961f)
            assertTrue("y=${xy[1]}", xy[1] >= 39f && xy[1] <= 561f)
        }
    }

    @Test
    fun degenerateBoundsGetFiniteZoom() {
        val cam = StaticMapCamera.fit(28.6, 28.6, 77.2, 77.2, 800, 600, 40)
        assertNotNull(cam)
        assertEquals(16.5, cam!!.zoom, 1e-9)
    }

    @Test
    fun invalidInputsYieldNoCamera() {
        assertNull(StaticMapCamera.fit(28.5, 28.7, 77.1, 77.3, 0, 600, 40))
        assertNull(StaticMapCamera.fit(0.0, 28.7, 0.0, 77.3, 800, 600, 40))
        assertNull(StaticMapCamera.forCircle(Double.NaN, 77.2, 100.0, 800, 600))
    }

    @Test
    fun circleFillsRequestedFraction() {
        val cam = StaticMapCamera.forCircle(28.6, 77.2, 100.0, 800, 600, fill = 0.6)!!
        val radiusPx = 100.0 / cam.metersPerPixel()
        assertEquals(600 * 0.6 / 2, radiusPx, 0.5)
    }
}
