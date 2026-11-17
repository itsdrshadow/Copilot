package com.antigravity.copilot.logic

import com.antigravity.copilot.core.logic.TripFuelEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripFuelEstimatorTest {

    @Test
    fun `idle is excluded and steady driving tracks learned mileage`() {
        val e = TripFuelEstimator()
        // 10 minutes idle at a light: nothing burnt
        repeat(600) { assertEquals(0.0, e.onStep(0.0, 0.0, 0.0, 18.0), 0.0) }
        assertEquals(0.0, e.litres, 0.0)
        // 20 km steady at the efficient speed
        repeat(200) { e.onStep(0.1, 55.0, 0.0, 18.0) }
        assertEquals(20.0, e.km, 1e-9)
        // Steady optimal driving is a little better than the whole-tank average
        assertTrue(e.tripKmpl in 18.0..22.0)
        assertTrue(e.bestKmpl >= e.tripKmpl - 1e-9)
    }

    @Test
    fun `aggressive stop-go driving is estimated worse than smooth driving`() {
        val smooth = TripFuelEstimator()
        val harsh = TripFuelEstimator()
        repeat(100) {
            smooth.onStep(0.1, 50.0, 0.1, 15.0)
            harsh.onStep(0.1, if (it % 2 == 0) 20.0 else 90.0, if (it % 2 == 0) 2.5 else -2.5, 15.0)
        }
        assertTrue("harsh ${harsh.tripKmpl} < smooth ${smooth.tripKmpl}", harsh.tripKmpl < smooth.tripKmpl)
    }

    @Test
    fun `restore continues an interrupted trip`() {
        val e = TripFuelEstimator()
        e.restore(12.0, 0.8, 16.0)
        e.onStep(1.0, 55.0, 0.0, 15.0)
        assertEquals(13.0, e.km, 1e-9)
        assertTrue(e.litres > 0.8)
    }
}
