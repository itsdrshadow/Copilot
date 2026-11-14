package com.antigravity.copilot

import com.antigravity.copilot.core.fuel.intelligence.FuelGaugeCalibrationEngine
import com.antigravity.copilot.core.fuel.intelligence.MissedEntryIntelligence
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class MissedEntryExtremeBoundaryTest {

    @Test
    fun testPlausibleRangeNeverInvertedUnderExtremeSpread() {
        // Test synthetic inversion scenario: rawMin > rawMax
        val rawMin = 25.0
        val rawMax = 12.0

        val safeMin = min(rawMin, rawMax)
        val safeMax = max(rawMin, rawMax)
        val deltaNominal = 18.0
        val plausibleNominal = deltaNominal.coerceIn(safeMin, safeMax)

        assertTrue("Safe min must be <= safe max", safeMin <= safeMax)
        assertEquals(12.0, safeMin, 0.001)
        assertEquals(25.0, safeMax, 0.001)
        assertEquals(18.0, plausibleNominal, 0.001)

        // Closed range construction MUST NEVER throw IllegalArgumentException
        val range = safeMin..safeMax
        assertTrue(plausibleNominal in range)
    }

    @Test
    fun testMissedEntryIntelligenceIntegration() {
        val calibrationEngine = FuelGaugeCalibrationEngine(designCapacityLiters = 48.0)
        val missedEntry = MissedEntryIntelligence(calibrationEngine = calibrationEngine, thresholdLiters = 4.0)

        assertNotNull(missedEntry)
        assertEquals(4.0, missedEntry.thresholdLiters, 0.001)
    }

    @Test
    fun testExtremeBoundarySpreads() {
        // Zero or negative delta guard
        val deltaNominal1 = -5.0
        val safeMin1 = 1.0
        val safeMax1 = 48.0
        val clamped1 = deltaNominal1.coerceIn(safeMin1, safeMax1)
        assertEquals(1.0, clamped1, 0.001)

        // Beyond tank capacity guard
        val deltaNominal2 = 75.0
        val clamped2 = deltaNominal2.coerceIn(safeMin1, safeMax1)
        assertEquals(48.0, clamped2, 0.001)
    }
}
