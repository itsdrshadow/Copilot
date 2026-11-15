package com.antigravity.copilot

import com.antigravity.copilot.core.fuel.intelligence.AdaptiveMileageEngine
import com.antigravity.copilot.core.fuel.intelligence.FuelInterval
import com.antigravity.copilot.core.fuel.intelligence.FuelMath
import com.antigravity.copilot.core.model.FuelState
import com.antigravity.copilot.core.model.MileageLearningState
import com.antigravity.copilot.core.model.TripStatus
import com.antigravity.copilot.core.model.TripSummary
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class CurrentTripAndFuelMileageFixTest {

    @Test
    fun testTripSummaryDefault_CalculatedMileageIsZeroNot15() {
        val emptyTrip = TripSummary.empty()
        assertEquals(
            "When trip hasn't started, default calculated mileage must be 0.0 and never 15.0",
            0.0,
            emptyTrip.calculatedMileageKmpl,
            0.0001
        )
        assertEquals(TripStatus.IDLE, emptyTrip.status)
        assertEquals(0.0, emptyTrip.distanceMeters, 0.0001)
    }

    @Test
    fun testTripNotStarted_DisplaysPlaceholderAndNever15OrBaseline() {
        val idleTrip = TripSummary.empty()
        val isTripActive = idleTrip.status == TripStatus.ACTIVE_TRIP ||
                idleTrip.status == TripStatus.MOVEMENT_CANDIDATE ||
                idleTrip.status == TripStatus.TEMPORARY_STOP
        val isTripCompleted = !isTripActive && (
                idleTrip.status == TripStatus.COMPLETED ||
                (idleTrip.endTime > 0L && idleTrip.endTime >= idleTrip.startTime)
        )
        val hasTripMoved = (isTripActive || isTripCompleted) && idleTrip.distanceMeters >= 50.0

        val fallbackMileage = 18.0
        val displayedMileage = if (hasTripMoved) {
            val effMileage = if (idleTrip.calculatedMileageKmpl > 0.0) idleTrip.calculatedMileageKmpl else fallbackMileage
            if (effMileage > 0.0) String.format(Locale.US, "%.2f", effMileage) else "--"
        } else {
            "--"
        }

        assertEquals(
            "Before trip starts or moves, trip economy must display '--' and never 15 or 18",
            "--",
            displayedMileage
        )
        assertFalse("Trip must not be considered moved", hasTripMoved)
    }

    @Test
    fun testTripActiveAndMoved_DisplaysCalculatedEconomy() {
        val activeTrip = TripSummary(
            startTime = System.currentTimeMillis() - 60_000L,
            distanceMeters = 3500.0,
            calculatedMileageKmpl = 17.5,
            status = TripStatus.ACTIVE_TRIP
        )
        val isTripActive = true
        val isTripCompleted = false
        val hasTripMoved = (isTripActive || isTripCompleted) && activeTrip.distanceMeters >= 50.0
        assertTrue(hasTripMoved)

        val fallbackMileage = 18.0
        val displayedMileage = if (hasTripMoved) {
            val effMileage = if (activeTrip.calculatedMileageKmpl > 0.0) activeTrip.calculatedMileageKmpl else fallbackMileage
            if (effMileage > 0.0) String.format(Locale.US, "%.2f", effMileage) else "--"
        } else {
            "--"
        }

        assertEquals("17.50", displayedMileage)
    }

    @Test
    fun testFuelSystem_BaselineFallbackAvoidsStuckInLearning() {
        val baselineVehicleMileage = 18.0
        val engine = AdaptiveMileageEngine(baselineVehicleMileageKmpl = baselineVehicleMileage)

        val hasConfirmedEmpirical = engine.hasSufficientEvidence && engine.measuredMileage != null
        val hasEstimatedEmpirical = engine.sampleCount >= 1 && (engine.measuredMileage != null || engine.overallEstimatedMileage > 0.0)

        // Verify fallback resolution when 0 refuels are logged
        val validEmpiricalMileage = when {
            hasConfirmedEmpirical -> engine.overallEstimatedMileage
            hasEstimatedEmpirical -> engine.measuredMileage ?: engine.overallEstimatedMileage
            baselineVehicleMileage > 0.0 -> baselineVehicleMileage
            else -> 18.0
        }

        val fuelState = FuelState(
            currentMileageKmpl = FuelMath.roundMileage(validEmpiricalMileage),
            expectedMileageKmpl = FuelMath.roundMileage(baselineVehicleMileage),
            mileageLearningState = engine.learningState
        )

        val isConfirmed = fuelState.mileageLearningState == MileageLearningState.LEARNED && fuelState.currentMileageKmpl > 0.0
        val isEstimated = (fuelState.mileageLearningState == MileageLearningState.LEARNING || fuelState.isRealMileageDerived) && fuelState.currentMileageKmpl > 0.0

        val displayMileage = when {
            fuelState.currentMileageKmpl > 0.0 -> fuelState.currentMileageKmpl
            fuelState.expectedMileageKmpl > 0.0 -> fuelState.expectedMileageKmpl
            else -> 18.0
        }
        val mileageText = String.format(Locale.US, "%.1f km/L", displayMileage)
        val mileageLabel = when {
            isConfirmed -> "CURRENT MILEAGE"
            isEstimated -> "EST. MILEAGE"
            else -> "EST. MILEAGE"
        }

        assertEquals("18.0 km/L", mileageText)
        assertEquals("EST. MILEAGE", mileageLabel)
        assertNotEquals("Must not display raw 'Learning' without mileage figures", "Learning", mileageText)
    }

    @Test
    fun testFuelSystem_ConfirmedRefuelDisplaysCurrentMileage() {
        val engine = AdaptiveMileageEngine(baselineVehicleMileageKmpl = 18.0)
        engine.addInterval(FuelInterval(50000.0, 50200.0, 20.0, 10.0, 20.0)) // 20.0 km/L
        engine.addInterval(FuelInterval(50200.0, 50400.0, 20.0, 10.0, 20.0)) // 20.0 km/L

        assertEquals(MileageLearningState.LEARNED, engine.learningState)
        assertTrue(engine.hasSufficientEvidence)

        val fuelState = FuelState(
            currentMileageKmpl = engine.overallEstimatedMileage,
            expectedMileageKmpl = 18.0,
            isRealMileageDerived = true,
            mileageLearningState = engine.learningState
        )

        val isConfirmed = fuelState.mileageLearningState == MileageLearningState.LEARNED && fuelState.currentMileageKmpl > 0.0
        val mileageLabel = if (isConfirmed) "CURRENT MILEAGE" else "EST. MILEAGE"
        val mileageText = String.format(Locale.US, "%.1f km/L", fuelState.currentMileageKmpl)

        assertTrue(isConfirmed)
        assertEquals("CURRENT MILEAGE", mileageLabel)
        assertTrue(fuelState.currentMileageKmpl in 18.0..21.0)
        assertTrue(mileageText.endsWith("km/L"))
    }
}
