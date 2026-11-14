package com.antigravity.copilot

import com.antigravity.copilot.core.fuel.intelligence.AdaptiveMileageEngine
import com.antigravity.copilot.core.fuel.intelligence.FuelInterval
import com.antigravity.copilot.core.fuel.intelligence.FuelMath
import com.antigravity.copilot.core.model.FuelState
import com.antigravity.copilot.core.model.MileageLearningState
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit test suite verifying the complete Mileage Display Resolution
 * across zero samples (Learning), single verified interval (Estimated mileage),
 * and multi-sample aggregation (Confirmed Learned mileage).
 */
class FuelMileageDisplayResolutionTest {

    @Test
    fun test01_ZeroSamplesGenuinelyInsufficient_ShowsLearningNotBaseline() {
        val baselineSettings = 18.0
        val engine = AdaptiveMileageEngine(baselineVehicleMileageKmpl = baselineSettings)

        assertEquals(0, engine.sampleCount)
        assertNull("Measured mileage must be null with zero samples", engine.measuredMileage)
        assertEquals(MileageLearningState.INITIAL_BASELINE, engine.learningState)
        assertFalse(engine.hasSufficientEvidence)

        // When no empirical data exists, actual mileage is 0.0 (unknown/learning)
        val validEmpiricalMileage = if (engine.sampleCount >= 1 && engine.measuredMileage != null) {
            engine.measuredMileage!!
        } else {
            0.0
        }
        val isReal = engine.sampleCount >= 1 && engine.measuredMileage != null

        val fuelState = FuelState(
            currentMileageKmpl = validEmpiricalMileage,
            expectedMileageKmpl = baselineSettings,
            isRealMileageDerived = isReal,
            mileageLearningState = engine.learningState
        )

        // Verify UI Display Contract
        val isConfirmed = fuelState.mileageLearningState == MileageLearningState.LEARNED && fuelState.currentMileageKmpl > 0.0
        val isEstimated = (fuelState.mileageLearningState == MileageLearningState.LEARNING || fuelState.isRealMileageDerived) && fuelState.currentMileageKmpl > 0.0
        val hasActualMileage = isConfirmed || isEstimated

        assertFalse("Must not claim actual mileage before any refuel interval", hasActualMileage)
        assertFalse("Must not claim confirmed", isConfirmed)
        assertFalse("Must not claim estimated", isEstimated)

        val displayVal = if (hasActualMileage) "${fuelState.currentMileageKmpl} km/L" else "Learning"
        assertEquals("Learning", displayVal)
        assertNotEquals("Must not display baseline 18.0 as actual mileage", "18.0 km/L", displayVal)
    }

    @Test
    fun test02_SingleVerifiedRefuelInterval_DisplaysEstimatedMileageNotLearning() {
        val engine = AdaptiveMileageEngine(baselineVehicleMileageKmpl = 18.0)

        // 1st verified interval: 200 km driven, 11.67 L consumed
        // 200 / 11.67 = 17.1379... -> 17.14 km/L
        val interval = FuelInterval(
            startOdometerKm = 50000.0,
            endOdometerKm = 50200.0,
            startFuelLiters = 14.0,
            fuelAddedDuringInterval = 13.74,
            endFuelLiters = 16.07
        )
        val computedKmpl = engine.addInterval(interval)
        assertNotNull(computedKmpl)
        assertEquals(17.14, computedKmpl!!, 0.01)

        assertEquals(1, engine.sampleCount)
        assertNotNull(engine.measuredMileage)
        assertEquals(17.14, engine.measuredMileage!!, 0.01)
        assertEquals(MileageLearningState.LEARNING, engine.learningState)
        assertFalse("1 sample alone does not fulfill multi-sample confirmed threshold", engine.hasSufficientEvidence)

        val validEmpiricalMileage = engine.measuredMileage ?: engine.overallEstimatedMileage
        val isReal = engine.sampleCount >= 1 && engine.measuredMileage != null

        val fuelState = FuelState(
            currentMileageKmpl = FuelMath.roundMileage(validEmpiricalMileage),
            expectedMileageKmpl = 18.0,
            isRealMileageDerived = isReal,
            mileageLearningState = engine.learningState
        )

        // Verify UI Display Contract for 1 sample
        val isConfirmed = fuelState.mileageLearningState == MileageLearningState.LEARNED && fuelState.currentMileageKmpl > 0.0
        val isEstimated = (fuelState.mileageLearningState == MileageLearningState.LEARNING || fuelState.isRealMileageDerived) && fuelState.currentMileageKmpl > 0.0
        val hasActualMileage = isConfirmed || isEstimated

        assertTrue("Single verified interval MUST display actual calculated mileage", hasActualMileage)
        assertFalse("Single interval is not yet multi-sample confirmed", isConfirmed)
        assertTrue("Single interval must be identified as estimated empirical mileage", isEstimated)

        val displayVal = if (hasActualMileage) "${fuelState.currentMileageKmpl} km/L" else "Learning"
        val displayLabel = if (isEstimated && !isConfirmed) "EST. MILEAGE" else "MILEAGE"

        assertEquals("17.14 km/L", displayVal)
        assertEquals("EST. MILEAGE", displayLabel)
        assertNotEquals("Must not display Learning when valid interval exists", "Learning", displayVal)
    }

    @Test
    fun test03_MultipleReliableObservations_AggregatedIntoConfirmedLearnedMileage() {
        val engine = AdaptiveMileageEngine(baselineVehicleMileageKmpl = 18.0)

        // Interval 1: 200 km, 11.67 L -> 17.14 km/L
        engine.addInterval(
            FuelInterval(50000.0, 50200.0, 14.0, 13.74, 16.07)
        )
        // Interval 2: 300 km, 18.50 L -> 16.22 km/L
        engine.addInterval(
            FuelInterval(50200.0, 50500.0, 16.07, 20.0, 17.57)
        )

        assertEquals(2, engine.sampleCount)
        assertEquals(MileageLearningState.LEARNED, engine.learningState)
        assertTrue(engine.hasSufficientEvidence)
        assertNotNull(engine.measuredMileage)

        val validEmpiricalMileage = engine.overallEstimatedMileage
        assertTrue("Learned mileage must be between observations (16.22 and 17.14)",
            validEmpiricalMileage in 16.0..17.3)

        val fuelState = FuelState(
            currentMileageKmpl = FuelMath.roundMileage(validEmpiricalMileage),
            expectedMileageKmpl = 18.0,
            isRealMileageDerived = true,
            mileageLearningState = engine.learningState
        )

        // Verify UI Display Contract for 2+ samples
        val isConfirmed = fuelState.mileageLearningState == MileageLearningState.LEARNED && fuelState.currentMileageKmpl > 0.0
        val isEstimated = (fuelState.mileageLearningState == MileageLearningState.LEARNING || fuelState.isRealMileageDerived) && fuelState.currentMileageKmpl > 0.0
        val hasActualMileage = isConfirmed || isEstimated

        assertTrue(hasActualMileage)
        assertTrue("2+ verified samples must be confirmed", isConfirmed)

        val displayLabel = if (isEstimated && !isConfirmed) "EST. MILEAGE" else "MILEAGE"
        assertEquals("MILEAGE", displayLabel)
    }

    @Test
    fun test04_IncompleteOrShortIntervals_RejectedFromMileage() {
        val engine = AdaptiveMileageEngine(baselineVehicleMileageKmpl = 18.0)

        // Interval with only 5 km (< MIN_INTERVAL_KM of 15 km)
        val shortInterval = FuelInterval(50000.0, 50005.0, 20.0, 1.0, 20.0)
        val result = engine.addInterval(shortInterval)
        assertNull("Short intervals below 15 km must be rejected", result)
        assertEquals(0, engine.sampleCount)

        // Interval with fuelConsumed < 1.0 L
        val microFuelInterval = FuelInterval(50000.0, 50030.0, 20.0, 0.2, 20.0)
        val resultMicro = engine.addInterval(microFuelInterval)
        assertNull("Fuel consumption below 1.0 L must be rejected", resultMicro)
        assertEquals(0, engine.sampleCount)
    }

    @Test
    fun test05_ExtremePlausibilityOutliers_Rejected() {
        val engine = AdaptiveMileageEngine(baselineVehicleMileageKmpl = 18.0)

        // Buggy interval: 100 km on 1.0 L = 100 km/L (exceeds MAX_PLAUSIBLE of 45.0)
        val crazyHigh = FuelInterval(50000.0, 50100.0, 20.0, 1.0, 20.0)
        val resHigh = engine.addInterval(crazyHigh)
        assertNull("Physically implausible > 45 km/L must be rejected", resHigh)
        assertEquals(0, engine.sampleCount)

        // Buggy interval: 20 km on 20 L = 1.0 km/L (below MIN_PLAUSIBLE of 4.0)
        val crazyLow = FuelInterval(50000.0, 50020.0, 20.0, 20.0, 20.0)
        val resLow = engine.addInterval(crazyLow)
        assertNull("Physically implausible < 4 km/L must be rejected", resLow)
        assertEquals(0, engine.sampleCount)
    }
}
