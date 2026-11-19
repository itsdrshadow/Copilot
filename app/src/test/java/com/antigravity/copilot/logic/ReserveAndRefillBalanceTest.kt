package com.antigravity.copilot.logic

import com.antigravity.copilot.core.fuel.intelligence.FuelStateEstimator
import com.antigravity.copilot.core.logic.fuel.FuelModel
import com.antigravity.copilot.core.logic.fuel.FuelModelState
import com.antigravity.copilot.core.logic.fuel.RefillInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regressions from the 5-trip device run: reserve lamp vs. a high model estimate, and refill balances. */
class ReserveAndRefillBalanceTest {

    private val now = 1_800_000_000_000L

    @Test
    fun reserveReadingPullsHighEstimateDownWithoutBreakingTheCurve() {
        // Model believes 40 L, the car's reserve lamp says otherwise.
        val s = FuelModelState(designCapacityL = 48.0, actualCapacityL = 48.0, defaultKmpl = 18.0, anchorLitres = 40.0, anchorOdoKm = 100.0)
        val after = FuelModel.onGaugeReading(s, 0.0, 100.0, now)

        assertTrue("tank follows the reserve lamp", after.anchorLitres <= 5.0 + 1e-6)
        val curve = FuelModel.curve(after)
        // Before the fix the "empty" bound used the model's 40 L and lifted the whole curve.
        assertTrue("empty mark stays near the hidden reserve, was ${curve.litresAt(0.0)}", curve.litresAt(0.0) <= 5.0 + 1e-6)
        assertTrue("half mark stays mid-tank, was ${curve.litresAt(5.0)}", curve.litresAt(5.0) < 30.0)
    }

    @Test
    fun fullReadingWithLowEstimateDoesNotCrushTheCurve() {
        val s = FuelModelState(designCapacityL = 48.0, actualCapacityL = 48.0, defaultKmpl = 18.0, anchorLitres = 8.0, anchorOdoKm = 100.0)
        val after = FuelModel.onGaugeReading(s, 10.0, 100.0, now)
        val curve = FuelModel.curve(after)
        assertTrue("full mark stays near capacity, was ${curve.litresAt(10.0)}", curve.litresAt(10.0) > 40.0)
    }

    @Test
    fun refillAfterReserveAddsToReserveLevel() {
        var s = FuelModelState(designCapacityL = 48.0, actualCapacityL = 48.0, defaultKmpl = 18.0, anchorLitres = 40.0, anchorOdoKm = 100.0)
        s = FuelModel.onGaugeReading(s, 0.0, 100.0, now)
        val reserveL = s.anchorLitres
        s = FuelModel.applyRefill(s, RefillInput(timeMs = now + 60_000, amountRs = 505.0, pricePerL = 101.0, odoKm = 100.0, fullTank = false), null)
        assertEquals(reserveL + 5.0, s.anchorLitres, 0.05)
    }

    @Test
    fun estimatorSyncMakesStoredBalanceDrainBetweenRefills() {
        val est = FuelStateEstimator(48.0, 48.0)
        est.syncNominal(10.0)
        est.applyRefuel(addedLiters = 5.0, observedGaugePoints = -1, odometerKm = 10.0)
        assertEquals(15.0, est.nominalFuelLiters, 1e-6)
        // 18 km at 18 km/L later the model says 14 L: the next stored balance starts there.
        est.syncNominal(14.0)
        est.applyRefuel(addedLiters = 5.0, observedGaugePoints = -1, odometerKm = 28.0)
        assertEquals(19.0, est.nominalFuelLiters, 1e-6)
        assertTrue(est.conservativeFuelLiters <= est.nominalFuelLiters)
        assertTrue(est.upperFuelLiters >= est.nominalFuelLiters)
    }
}
