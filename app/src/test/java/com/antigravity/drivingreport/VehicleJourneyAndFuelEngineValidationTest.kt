package com.antigravity.drivingreport

import com.antigravity.drivingreport.core.fuel.intelligence.EventConfidence
import com.antigravity.drivingreport.core.fuel.intelligence.FuelGaugeCalibrationEngine
import com.antigravity.drivingreport.core.fuel.intelligence.FuelStateEstimator
import com.antigravity.drivingreport.core.fuel.intelligence.MissedEntryIntelligence
import com.antigravity.drivingreport.core.trip.TimeFormatUtils
import org.junit.Assert.*
import org.junit.Test

class VehicleJourneyAndFuelEngineValidationTest {

    @Test
    fun testNonLinearGaugeCalibrationMonotonicity() {
        val engine = FuelGaugeCalibrationEngine(designCapacityLiters = 48.0)
        val initialFractions = engine.getCalibratedFractions()

        assertEquals(11, initialFractions.size)
        assertEquals(0.0, initialFractions[0], 0.0001)
        assertTrue("10th bar must represent full tank (>= 1.0)", initialFractions[10] >= 1.0)

        // Verify initial strict monotonicity
        for (i in 1..10) {
            assertTrue("Bar $i must be strictly greater than bar ${i - 1}", initialFractions[i] > initialFractions[i - 1])
        }

        // Learn empirical points (e.g. 26.0 L at bar 5, 49.5 L at bar 10)
        engine.learnCalibrationPoint(bar = 5, confirmedLiters = 26.0, confidenceWeight = 0.35)
        engine.learnCalibrationPoint(bar = 10, confirmedLiters = 49.5, confidenceWeight = 0.50)

        val updatedFractions = engine.getCalibratedFractions()
        // Monotonicity must remain strictly preserved
        for (i in 1..10) {
            assertTrue(
                "After empirical learning, bar $i (${updatedFractions[i]}) must exceed bar ${i - 1} (${updatedFractions[i - 1]})",
                updatedFractions[i] >= updatedFractions[i - 1] + 0.015
            )
        }
        assertTrue("Bar 10 must remain >= 1.0", updatedFractions[10] >= 1.0)
    }

    @Test
    fun testSloshAndSlopeFilterRejection() {
        val engine = FuelGaugeCalibrationEngine(designCapacityLiters = 48.0)
        val now = System.currentTimeMillis()

        // Steady reading of 6 bars
        assertEquals(6, engine.filterSloshAndSlope(6, now - 5000))
        assertEquals(6, engine.filterSloshAndSlope(6, now - 4000))

        // Sudden transient spike due to slope or fuel sloshing (jumps to 9 bars for one reading)
        val filtered = engine.filterSloshAndSlope(9, now - 3000)
        assertEquals("Transient slosh jump to 9 bars must be rejected by median filter", 6, filtered)
    }

    @Test
    fun testMissedRefillInferenceNeverFabricatesReceipts() {
        val calibration = FuelGaugeCalibrationEngine(designCapacityLiters = 48.0)
        val intel = MissedEntryIntelligence(calibrationEngine = calibration, thresholdLiters = 4.0)

        val estimator = FuelStateEstimator(designCapacityLiters = 48.0, maxCapacityLiters = 48.0)
        estimator.initializeBaseline(initialLiters = 10.0, gaugePoints = 1, odometerKm = 10000.0)

        // Cluster gauge jumps to 8 bars (~39.36 L) with no refuel logged
        val result = intel.evaluateObservedGauge(
            observedGaugePoints = 8,
            estimator = estimator,
            currentOdometerKm = 10050.0
        )

        assertTrue("Discrepancy must be detected as missed refuel", result.detected)
        assertTrue("Plausible nominal fuel must be positive", result.plausibleNominalLiters > 15.0)
        assertTrue("Plausible min must be <= plausible max", result.plausibleMinLiters <= result.plausibleMaxLiters)

        val suggested = result.suggestedLedgerEvent
        assertNotNull(suggested)
        assertEquals("Price per liter must be 0.0 to prevent fake financial records", 0.0, suggested!!.pricePerLiter, 0.001)
        assertEquals("Total amount must be 0.0 to prevent fake financial records", 0.0, suggested.totalAmount, 0.001)
        assertEquals("Location must be blank without confirmed hardware receipt", "", suggested.locationName)
        assertEquals("Inferred event must be provisional", EventConfidence.PROVISIONAL.name, suggested.confidence.name)
    }

    @Test
    fun testAddFuelCalculationAndOverfillValidation() {
        val totalAmount = 1500.0
        val pricePerLiter = 96.50
        val expectedLiters = totalAmount / pricePerLiter

        assertEquals(15.544, expectedLiters, 0.01)

        val designCap = 48.0
        val currentLiters = 40.0
        val predictedTank = currentLiters + expectedLiters
        val isOverfill = predictedTank > designCap

        assertTrue("Adding 15.54L to a 40L tank (48L capacity) must trigger overfill warning", isOverfill)
        assertEquals(48.0, predictedTank.coerceAtMost(designCap), 0.001)
    }

    @Test
    fun testMultiDayTripTimerAcrossMidnight() {
        // 2 days, 5 hours, 30 minutes, 15 seconds later
        val durationMs = (2 * 24 * 3600 + 5 * 3600 + 30 * 60 + 15) * 1000L

        val formatted = TimeFormatUtils.formatLiveTripTimer(durationMs)
        assertEquals("2d 05h 30m", formatted)
    }

    @Test
    fun testDynamicCapacityScaling() {
        val calibration = FuelGaugeCalibrationEngine(designCapacityLiters = 48.0)
        assertEquals(48.0, calibration.getLitersForBar(10), 0.1)

        // User updates vehicle capacity to 60.0 L in Settings
        calibration.updateDesignCapacity(60.0)
        assertEquals(60.0, calibration.getLitersForBar(10), 0.1)
        assertEquals(60.0, calibration.designCapacityLiters, 0.001)
    }
}
