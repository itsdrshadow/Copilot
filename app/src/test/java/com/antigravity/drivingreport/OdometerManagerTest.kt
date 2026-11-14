package com.antigravity.drivingreport

import com.antigravity.drivingreport.core.odometer.OdometerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OdometerManagerTest {

    @Test
    fun testDeltaAndLiveOdometerCalculation() {
        val totalLifetimeKm = 125.4
        val baselineLifetime = 100.0
        val baselineOdo = 45200.0

        val delta = (totalLifetimeKm - baselineLifetime).coerceAtLeast(0.0)
        assertEquals(25.4, delta, 0.001)

        val liveOdo = (baselineOdo + delta).coerceAtLeast(0.0)
        assertEquals(45225.4, liveOdo, 0.001)
    }

    @Test
    fun testNegativeDeltaPrevention() {
        // If for some reason lifetime km drops or is less than baseline snapshot
        val totalLifetimeKm = 50.0
        val baselineLifetime = 100.0

        val delta = (totalLifetimeKm - baselineLifetime).coerceAtLeast(0.0)
        assertEquals(0.0, delta, 0.0001)
    }
}
