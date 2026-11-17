package com.antigravity.copilot.logic

import com.antigravity.copilot.core.logic.GeoMath
import com.antigravity.copilot.core.logic.LatLon
import com.antigravity.copilot.core.logic.PassColor
import com.antigravity.copilot.core.logic.TrailOverlap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailOverlapTest {

    private fun north(m: Double, east: Double = 0.0) =
        LatLon(21.0 + m / GeoMath.M_PER_DEG_LAT, 83.0 + east / GeoMath.mPerDegLon(21.0))

    @Test
    fun `single pass is blue even with lateral jitter`() {
        val pts = (0..200).map { north(it * 5.0, if (it % 2 == 0) 4.0 else -4.0) }
        val passes = TrailOverlap.passCounts(pts)
        assertTrue(passes.all { it == 1 })
    }

    @Test
    fun `out and back is orange, third pass is red`() {
        val out = (0..100).map { north(it * 10.0) }
        val back = (100 downTo 0).map { north(it * 10.0, 6.0) } // opposite lane
        val again = (0..50).map { north(it * 10.0, -3.0) }
        val all = out + back + again
        val passes = TrailOverlap.passCounts(all)
        // A point near the start was passed 3 times, far end only by out+back
        assertEquals(3, passes[out.size + back.size + 10])
        assertEquals(2, passes[90])
        assertEquals(PassColor.RED, PassColor.forPasses(3))
        assertEquals(PassColor.ORANGE, PassColor.forPasses(2))
        assertEquals(PassColor.BLUE, PassColor.forPasses(1))
    }

    @Test
    fun `simplify keeps ends and drops collinear points`() {
        val line = (0..1000).map { north(it * 1.0) }
        val kept = TrailOverlap.simplify(line, 4.0)
        assertEquals(listOf(0, 1000), kept)
        val corner = (0..100).map { north(it * 1.0) } + (1..100).map { north(100.0, it * 1.0) }
        assertEquals(3, TrailOverlap.simplify(corner, 4.0).size)
    }

    @Test
    fun `runs are contiguous and colour-split`() {
        val out = (0..100).map { north(it * 10.0) }
        val back = (100 downTo 50).map { north(it * 10.0) }
        val runs = TrailOverlap.runs(out + back)
        assertTrue(runs.size >= 2)
        assertTrue(runs.any { it.passes == 2 })
        runs.zipWithNext().forEach { (a, b) -> assertEquals(a.points.last(), b.points.first()) }
    }
}
