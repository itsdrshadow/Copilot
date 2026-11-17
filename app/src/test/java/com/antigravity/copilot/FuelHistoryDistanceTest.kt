package com.antigravity.copilot

import com.antigravity.copilot.core.database.FuelEventEntity
import com.antigravity.copilot.feature.fuel.plausibleOdometerDistanceKm
import org.junit.Assert.assertEquals
import org.junit.Test

/** Fuel log summary distance must not count odometer recalibration jumps as driving. */
class FuelHistoryDistanceTest {
    private fun refuel(odo: Double, litres: Double, t: Long) =
        FuelEventEntity(vehicleId = 1, timestamp = t, eventType = "REFUEL", liters = litres, calculatedBalanceLiters = 0.0, odometerKm = odo)

    @Test
    fun calibrationJumpIsNotDistance() {
        val events = listOf(refuel(1.0, 10.4, 1), refuel(3.95, 20.7, 2), refuel(45_000.0, 41.5, 3))
        assertEquals(2.95, plausibleOdometerDistanceKm(events), 1e-9)
    }

    @Test
    fun normalStepsAreSummed() {
        val events = listOf(refuel(45_000.0, 40.0, 1), refuel(45_500.0, 30.0, 2), refuel(46_100.0, 35.0, 3))
        assertEquals(1_100.0, plausibleOdometerDistanceKm(events), 1e-9)
    }

    @Test
    fun missingOdometerReadingsAreSkipped() {
        val events = listOf(refuel(45_000.0, 40.0, 1), refuel(0.0, 10.0, 2), refuel(45_400.0, 30.0, 3))
        assertEquals(400.0, plausibleOdometerDistanceKm(events), 1e-9)
    }
}
