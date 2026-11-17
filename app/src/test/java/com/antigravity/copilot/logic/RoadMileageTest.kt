package com.antigravity.copilot.logic

import com.antigravity.copilot.core.logic.GeoMath
import com.antigravity.copilot.core.logic.RoadMileage
import com.antigravity.copilot.core.logic.RoadSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadMileageTest {

    private fun trip(speedAt: (Int) -> Double, n: Int = 200, stepM: Double = 10.0): List<RoadSample> {
        var t = 0L
        return (0 until n).map { i ->
            val v = speedAt(i)
            val s = RoadSample(21.0 + i * stepM / GeoMath.M_PER_DEG_LAT, 83.0, v, t)
            t += if (v > 0) (stepM / (v / 3.6) * 1000).toLong() else 1000L
            s
        }
    }

    @Test
    fun `trip fuel is fully distributed and cells get km per litre`() {
        val samples = trip({ 50.0 })
        val km = 199 * 10.0 / 1000.0
        val litres = km / 15.0
        val cells = RoadMileage.distributeTrip(samples, litres)
        assertEquals(litres, cells.sumOf { it.litres }, 1e-9)
        assertEquals(km, cells.sumOf { it.km }, 0.01)
        // Uniform driving -> every cell close to the trip mileage
        cells.filter { it.km > 0.03 }.forEach { assertEquals(15.0, it.avgKmpl, 0.5) }
        assertTrue(cells.all { it.passes == 1 })
    }

    @Test
    fun `slow congested stretch gets lower mileage, idle is excluded`() {
        // First half crawling at 10 km/h, then a 2-minute stop, second half at 55 km/h
        val samples = trip({ i -> when { i < 100 -> 10.0; i in 100..105 -> 0.0; else -> 55.0 } })
        val cells = RoadMileage.distributeTrip(samples, 1.0)
        val slow = cells.filter { it.avgSpeedKmh < 20 && it.km > 0.02 }
        val fast = cells.filter { it.avgSpeedKmh > 40 && it.km > 0.02 }
        assertTrue(slow.isNotEmpty() && fast.isNotEmpty())
        assertTrue(slow.map { it.avgKmpl }.average() < fast.map { it.avgKmpl }.average())
    }

    @Test
    fun `aggregation over passes is a running average with sample count`() {
        val a = RoadMileage.distributeTrip(trip({ 50.0 }), 0.2)
        val b = RoadMileage.distributeTrip(trip({ 50.0 }), 0.1)
        val merged = RoadMileage.aggregate(a.associateBy { it.key }, b)
        val c = merged.first { it.km > 0.05 }
        assertEquals(2, c.passes)
        val ca = a.first { it.key == c.key }
        val cb = b.first { it.key == c.key }
        assertEquals((ca.km + cb.km) / (ca.litres + cb.litres), c.avgKmpl, 1e-9)
        assertEquals(0.0, RoadMileage.colourFraction(5.0, 15.0), 1e-9)
        assertEquals(1.0, RoadMileage.colourFraction(30.0, 15.0), 1e-9)
    }
}
