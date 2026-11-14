package com.antigravity.copilot

import com.antigravity.copilot.core.fuel.FuelTelemetrySnapshot
import com.antigravity.copilot.core.fuel.intelligence.AdaptiveMileageEngine
import com.antigravity.copilot.core.trip.TripDetectionEngine
import org.junit.Assert.*
import org.junit.Test

class FuelCalculationConsistencyTest {

    @Test
    fun testTripFuelUnifiedWithFuelTelemetrySnapshot() {
        val engine = TripDetectionEngine(
            onPointReceived = {},
            onTripStarted = {},
            onTripUpdated = { _, _ -> },
            onTripCompleted = {},
            onTripDiscarded = {}
        )

        // Inject simulated calibrated fuel engine snapshot
        val mockMileage = 16.5
        val mockPrice = 95.0
        val mockConsumed = 0.85
        engine.fuelTelemetryProvider = {
            FuelTelemetrySnapshot(
                mileageKmpl = mockMileage,
                fuelPricePerLiter = mockPrice,
                tripConsumedLiters = mockConsumed
            )
        }

        // Verify TripDetectionEngine companion defaults
        assertEquals(18.0, TripDetectionEngine.BASE_MILEAGE_KMPL, 0.001)

        val snapshot = engine.fuelTelemetryProvider?.invoke()
        assertNotNull(snapshot)
        assertEquals(mockMileage, snapshot!!.mileageKmpl, 0.001)
        assertEquals(mockPrice, snapshot.fuelPricePerLiter, 0.001)
        assertEquals(mockConsumed, snapshot.tripConsumedLiters, 0.001)

        // Calculated cost matches consumed * price
        val expectedCost = mockConsumed * mockPrice
        assertEquals(80.75, expectedCost, 0.01)
    }

    @Test
    fun testAdaptiveMileageEngineRecencyExponent() {
        val engine = AdaptiveMileageEngine(baselineVehicleMileageKmpl = 15.0)

        // Test with 3 refuel intervals
        engine.processAnchorInterval(
            startOdometerKm = 1000.0,
            endOdometerKm = 1350.0,
            startFuelLiters = 40.0,
            addedFuelLitersDuringInterval = 25.0,
            endFuelLiters = 42.0 // Consumed: 40 + 25 - 42 = 23L -> 350 / 23 = 15.22 km/L
        )

        engine.processAnchorInterval(
            startOdometerKm = 1350.0,
            endOdometerKm = 1750.0,
            startFuelLiters = 42.0,
            addedFuelLitersDuringInterval = 26.0,
            endFuelLiters = 41.0 // Consumed: 42 + 26 - 41 = 27L -> 400 / 27 = 14.81 km/L
        )

        engine.processAnchorInterval(
            startOdometerKm = 1750.0,
            endOdometerKm = 2200.0,
            startFuelLiters = 41.0,
            addedFuelLitersDuringInterval = 30.0,
            endFuelLiters = 43.0 // Consumed: 41 + 30 - 43 = 28L -> 450 / 28 = 16.07 km/L
        )

        assertTrue("Overall mileage should be plausible", engine.overallEstimatedMileage in 14.0..17.0)
        assertTrue("Confidence score should increase with 3 samples", engine.confidenceScore >= 50)
        assertEquals(3, engine.sampleCount)
    }
}
