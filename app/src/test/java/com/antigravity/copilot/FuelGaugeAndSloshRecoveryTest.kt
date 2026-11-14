package com.antigravity.copilot

import com.antigravity.copilot.core.fuel.intelligence.FuelGaugeCalibrationEngine
import com.antigravity.copilot.core.fuel.intelligence.FuelStateEstimator
import com.antigravity.copilot.core.fuel.intelligence.MissedEntryIntelligence
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FuelGaugeAndSloshRecoveryTest {

    private lateinit var calibration: FuelGaugeCalibrationEngine
    private lateinit var estimator: FuelStateEstimator
    private lateinit var missedEntryIntel: MissedEntryIntelligence

    @Before
    fun setUp() {
        calibration = FuelGaugeCalibrationEngine(designCapacityLiters = 48.0)
        estimator = FuelStateEstimator(
            designCapacityLiters = 48.0,
            maxCapacityLiters = 48.0,
            gaugeCalibration = calibration
        )
        missedEntryIntel = MissedEntryIntelligence(calibrationEngine = calibration, thresholdLiters = 4.0)
    }

    // --- FEATURE 15 TESTS: 10-Bar LED Gauge Dynamic Update & Strict Monotonicity ---

    @Test
    fun test15_1_GaugeDecrementsDynamicallyDuringActiveConsumption() {
        // Initialize at 48L (10 bars)
        estimator.initializeBaseline(initialLiters = 48.0, gaugePoints = 10, odometerKm = 10000.0)
        assertEquals(10, estimator.currentGaugeBar)

        // Consume 20 L (leaving 28 L, ~0.58 fraction, closest to bar 6 at 0.60)
        estimator.applyConsumption(consumedLiters = 20.0, odometerKm = 10300.0)
        assertEquals(28.0, estimator.nominalFuelLiters, 0.01)
        assertEquals("Gauge must dynamically decrement from 10 to 6 bars", 6, estimator.currentGaugeBar)

        // Consume another 15 L (leaving 13 L, ~0.27 fraction, closest to bar 3)
        estimator.applyConsumption(consumedLiters = 15.0, odometerKm = 10550.0)
        assertEquals(13.0, estimator.nominalFuelLiters, 0.01)
        assertEquals("Gauge must dynamically decrement to 3 bars", 3, estimator.currentGaugeBar)
    }

    @Test
    fun test15_2_StrictMonotonicityPreventsUpwardStepDuringDriving() {
        estimator.initializeBaseline(initialLiters = 30.0, gaugePoints = 6, odometerKm = 10000.0)
        assertEquals(6, estimator.currentGaugeBar)

        // Sensor glitch sends higher bar (8) during active trip consumption
        estimator.applyTripConsumption(consumedLiters = 2.0, newOdometerKm = 10030.0, currentGaugePoints = 8)
        assertEquals("Monotonicity guard must prevent gauge from stepping upwards to 8 bars during driving", 6, estimator.currentGaugeBar)

        // Sensor reports lower bar (5) — valid decrement
        estimator.applyTripConsumption(consumedLiters = 3.0, newOdometerKm = 10080.0, currentGaugePoints = 5)
        assertEquals("Monotonicity permits downward step to 5 bars", 5, estimator.currentGaugeBar)
    }

    @Test
    fun test15_3_GaugeClampsToZeroWhenTankDepleted() {
        estimator.initializeBaseline(initialLiters = 2.0, gaugePoints = 1, odometerKm = 10000.0)
        estimator.applyConsumption(consumedLiters = 5.0, odometerKm = 10050.0)
        assertEquals(0.0, estimator.nominalFuelLiters, 0.001)
        assertEquals("Gauge must clamp to 0 bar on empty tank", 0, estimator.currentGaugeBar)
    }

    @Test
    fun test15_4_RefuelAllowsUpwardGaugeStep() {
        estimator.initializeBaseline(initialLiters = 10.0, gaugePoints = 2, odometerKm = 10000.0)
        assertEquals(2, estimator.currentGaugeBar)

        // Confirmed refuel of 25 L (observed 7 bars)
        estimator.applyRefuel(addedLiters = 25.0, observedGaugePoints = 7, odometerKm = 10000.0)
        assertEquals(35.0, estimator.nominalFuelLiters, 0.01)
        assertEquals("Refuel must legitimately step gauge upwards to 7 bars", 7, estimator.currentGaugeBar)
    }

    // --- FEATURE 16 TESTS: Slosh & Slope Median Filter Integration ---

    @Test
    fun test16_1_SloshFilterRejectsTransientSpikesFromHillOrCornering() {
        val now = System.currentTimeMillis()

        // Steady vehicle state at 5 bars
        assertEquals(5, estimator.filterSloshAndSlope(5, now - 4000L))
        assertEquals(5, estimator.filterSloshAndSlope(5, now - 3000L))

        // Sudden transient slosh jump to 8 bars (due to braking or incline)
        val filtered = estimator.filterSloshAndSlope(8, now - 2000L)
        assertEquals("Transient 8-bar slosh spike must be rejected; median of [5,5,8] is 5", 5, filtered)

        // Transient dip to 2 bars (due to acceleration or downhill)
        val filteredDip = estimator.filterSloshAndSlope(2, now - 1000L)
        assertEquals("Transient dip must be filtered; median remains 5", 5, filteredDip)
    }

    @Test
    fun test16_2_SloshFilterPreventsFalseMissedRefuelAlert() {
        estimator.initializeBaseline(initialLiters = 24.0, gaugePoints = 5, odometerKm = 10000.0)
        val now = System.currentTimeMillis()

        // Establish steady 5-bar reading
        estimator.filterSloshAndSlope(5, now - 4000L)
        estimator.filterSloshAndSlope(5, now - 3000L)

        // Single transient slosh spike to 8 bars
        val filteredBar = estimator.filterSloshAndSlope(8, now - 1000L)
        val detection = missedEntryIntel.evaluateObservedGauge(
            observedGaugePoints = filteredBar,
            estimator = estimator,
            currentOdometerKm = 10020.0
        )

        assertFalse("Transient slosh must NOT trigger false missed refuel alert", detection.detected)
    }

    @Test
    fun test16_3_PersistentReadingAcknowledgedAsLegitimateChange() {
        estimator.initializeBaseline(initialLiters = 24.0, gaugePoints = 5, odometerKm = 10000.0)
        val now = System.currentTimeMillis()

        // True refuel occurs: readings stay at 8 bars consistently
        estimator.filterSloshAndSlope(8, now - 3000L)
        estimator.filterSloshAndSlope(8, now - 2000L)
        val filtered = estimator.filterSloshAndSlope(8, now - 1000L)

        assertEquals("Persistent readings must transition filter to 8 bars", 8, filtered)
        val detection = missedEntryIntel.evaluateObservedGauge(
            observedGaugePoints = filtered,
            estimator = estimator,
            currentOdometerKm = 10020.0
        )
        assertTrue("Persistent 8-bar shift must detect true missed refuel", detection.detected)
    }

    @Test
    fun test16_4_ManualCalibrationResetsSloshBuffer() {
        val now = System.currentTimeMillis()
        // Buffer currently populated with 4 bars
        calibration.filterSloshAndSlope(4, now - 4000L)
        calibration.filterSloshAndSlope(4, now - 3000L)

        // User manually calibrates to 8 bars in UI dialog
        calibration.resetSloshBuffer(8)
        val immediate = calibration.filterSloshAndSlope(8, now)
        assertEquals("Resetting buffer must allow immediate manual calibration to 8 bars without old lag", 8, immediate)
    }

    // --- FEATURE 18 TESTS: Reboot Active Trip Fuel Recovery ---

    @Test
    fun test18_1_ContinuousEstimatorReflectsRestoredActiveTripConsumption() {
        // Pre-trip state: 40 L
        estimator.initializeBaseline(initialLiters = 40.0, gaugePoints = 8, odometerKm = 20000.0)
        assertEquals(40.0, estimator.nominalFuelLiters, 0.01)

        // Active trip prior to reboot had consumed 2.75 L
        val restoredTripConsumed = 2.75
        estimator.applyConsumption(consumedLiters = restoredTripConsumed, odometerKm = 20045.0)

        assertEquals("Continuous fuel estimator must reflect 40 - 2.75 = 37.25 L", 37.25, estimator.nominalFuelLiters, 0.01)
        assertTrue("Gauge bar must adjust for 37.25 L", estimator.currentGaugeBar <= 8)
    }
}
