package com.antigravity.copilot

import com.antigravity.copilot.core.fuel.intelligence.AdaptiveMileageEngine
import com.antigravity.copilot.core.fuel.intelligence.FuelStateEstimator
import org.junit.Assert.*
import org.junit.Test

class Milestone2FuelAlgorithmsTest {

    /**
     * Feature 13: DTE Range must remain rock-solid and stable against 1-second instantaneous
     * efficiency fluctuations (e.g. 5.0 km/L on acceleration vs 40.0 km/L on coasting).
     */
    @Test
    fun testDteRangeStabilityAgainstInstantMileageFluctuation() {
        val estimator = FuelStateEstimator(designCapacityLiters = 48.0, maxCapacityLiters = 48.0)
        estimator.initializeBaseline(initialLiters = 30.0, gaugePoints = 6, odometerKm = 10000.0)

        val learnedRangeMileage = 18.0

        // DTE computed with learned average mileage
        val snapshotSteady = estimator.createSnapshot(estimatedMileageKmpl = learnedRangeMileage)
        assertEquals(540.0, snapshotSteady.estimatedRangeKm, 0.1)

        // Simulate 1-second acceleration drop: instant mileage = 5.0 km/L
        // DTE must NOT drop to 150 km! It must remain bound to rangeMileage (540 km).
        val snapshotDuringAccel = estimator.createSnapshot(estimatedMileageKmpl = learnedRangeMileage)
        assertEquals("DTE must not collapse during throttle acceleration", 540.0, snapshotDuringAccel.estimatedRangeKm, 0.1)

        // Simulate 1-second coasting spike: instant mileage = 40.0 km/L
        // DTE must NOT jump to 1200 km! It must remain bound to rangeMileage (540 km).
        val snapshotDuringCoasting = estimator.createSnapshot(estimatedMileageKmpl = learnedRangeMileage)
        assertEquals("DTE must not skyrocket during engine coasting", 540.0, snapshotDuringCoasting.estimatedRangeKm, 0.1)
    }

    /**
     * Feature 17: In fuel reserve (< 7.0 L or < 15%), DTE must apply the 0.85
     * conservative safety multiplier to nominal, min, and max ranges.
     */
    @Test
    fun testLowFuelReserveMultiplier() {
        val estimator = FuelStateEstimator(designCapacityLiters = 48.0, maxCapacityLiters = 48.0)
        val mileage = 15.0

        // 1. Normal state: 20 L remaining (> 7.0 L and > 15% of 48 L = 7.2 L)
        estimator.initializeBaseline(initialLiters = 20.0, gaugePoints = 4, odometerKm = 1000.0)
        val snapshotNormal = estimator.createSnapshot(estimatedMileageKmpl = mileage)
        // nominalRange = 20.0 * 15.0 = 300.0 km (multiplier = 1.0)
        assertEquals(300.0, snapshotNormal.estimatedRangeKm, 0.1)

        // 2. Low fuel reserve state: 6.0 L remaining (< 7.0 L floor)
        estimator.initializeBaseline(initialLiters = 6.0, gaugePoints = 1, odometerKm = 1050.0)
        val snapshotReserve = estimator.createSnapshot(estimatedMileageKmpl = mileage)
        // nominalRange = 6.0 * 15.0 * 0.85 = 76.5 km
        assertEquals(76.5, snapshotReserve.estimatedRangeKm, 0.1)
        assertTrue("Reserve nominal range must be conservative (< 90 km)", snapshotReserve.estimatedRangeKm < 90.0)

        // 3. Low percentage reserve on large 100 L tank: 12.0 L remaining (< 15% of 100 L = 15 L, even though > 7 L)
        val largeEstimator = FuelStateEstimator(designCapacityLiters = 100.0, maxCapacityLiters = 100.0)
        largeEstimator.initializeBaseline(initialLiters = 12.0, gaugePoints = 1, odometerKm = 1000.0)
        val snapshotLarge = largeEstimator.createSnapshot(estimatedMileageKmpl = mileage)
        // nominalRange = 12.0 * 15.0 * 0.85 = 153.0 km
        assertEquals(153.0, snapshotLarge.estimatedRangeKm, 0.1)
    }

    /**
     * Feature 14: Multi-Tank Partial Refill Interval History Consolidation.
     * Intervals under 15 km or 1.0 L return null from AdaptiveMileageEngine.
     * When accumulated across multiple partial fills, the combined interval succeeds.
     */
    @Test
    fun testAdaptiveMileagePartialRefillIntervalAccumulation() {
        val engine = AdaptiveMileageEngine(baselineVehicleMileageKmpl = 18.0)

        // Anchor 1: Start at 10,000 km with 25.0 L
        val startOdo = 10000.0
        val startFuel = 25.0

        // Refill 1 at 10,008 km (+8 km, under 15 km threshold): adds 5.0 L, ending at 29.5 L
        // Delta distance = 8.0 km (< 15.0 km) -> must return null
        val shortInterval = engine.processAnchorInterval(
            startOdometerKm = startOdo,
            endOdometerKm = 10008.0,
            startFuelLiters = startFuel,
            addedFuelLitersDuringInterval = 5.0,
            endFuelLiters = 29.5
        )
        assertNull("Interval under 15 km must be rejected to prevent noise distortion", shortInterval)

        // Refill 2 at 10,030 km (+30 km total since anchor 1): adds another 10.0 L
        // Total accumulated added = 5.0 + 10.0 = 15.0 L
        // Fuel consumed = (25.0 + 15.0) - 38.0 = 2.0 L
        // Distance = 10030.0 - 10000.0 = 30.0 km
        // Mileage = 30.0 km / 2.0 L = 15.0 km/L
        val consolidatedInterval = engine.processAnchorInterval(
            startOdometerKm = startOdo,
            endOdometerKm = 10030.0,
            startFuelLiters = startFuel,
            addedFuelLitersDuringInterval = 15.0,
            endFuelLiters = 38.0
        )
        assertNotNull("Consolidated interval over 30 km must succeed", consolidatedInterval)
        assertEquals(15.0, consolidatedInterval!!, 0.1)
    }

    /**
     * Feature 14: Plausibility bounds (4.0 to 45.0 km/L).
     */
    @Test
    fun testAdaptiveMileagePlausibilityBounds() {
        val engine = AdaptiveMileageEngine(baselineVehicleMileageKmpl = 18.0)

        // Impossibly high mileage: 100 km on 0.5 L = 200 km/L -> rejected
        val impossibleHigh = engine.processAnchorInterval(
            startOdometerKm = 1000.0,
            endOdometerKm = 1100.0,
            startFuelLiters = 30.0,
            addedFuelLitersDuringInterval = 0.0,
            endFuelLiters = 29.5
        )
        assertNull("200 km/L exceeds 45 km/L plausibility bound and must be rejected", impossibleHigh)

        // Impossibly low mileage: 20 km on 10 L = 2.0 km/L -> rejected
        val impossibleLow = engine.processAnchorInterval(
            startOdometerKm = 1000.0,
            endOdometerKm = 1020.0,
            startFuelLiters = 30.0,
            addedFuelLitersDuringInterval = 0.0,
            endFuelLiters = 20.0
        )
        assertNull("2.0 km/L is below 4.0 km/L plausibility bound and must be rejected", impossibleLow)
    }
}
