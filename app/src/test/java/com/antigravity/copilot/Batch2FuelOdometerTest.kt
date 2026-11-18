package com.antigravity.copilot

import com.antigravity.copilot.core.fuel.station.HybridMileageEngine
import com.antigravity.copilot.core.fuel.station.RefuelMode
import com.antigravity.copilot.core.fuel.station.StationQuality
import com.antigravity.copilot.core.logic.LifetimeDistanceCounter
import com.antigravity.copilot.core.logic.OdometerReadingValidator
import com.antigravity.copilot.core.logic.TripFuelEstimator
import com.antigravity.copilot.core.logic.fuel.FuelDisplayPolicy
import com.antigravity.copilot.core.logic.fuel.FuelModel
import com.antigravity.copilot.core.logic.fuel.FuelModelState
import com.antigravity.copilot.core.logic.fuel.FuelReplay
import com.antigravity.copilot.core.logic.fuel.TripFuelCalibration
import com.antigravity.copilot.core.logic.fuel.UnloggedFuel
import org.junit.Assert.*
import org.junit.Test

/**
 * Batch 2 audit fixes: event-sourced replay (E1/E10), fresh-install unknown tank (E7),
 * relative thresholds (E8/E9), trip-estimate calibration (F2/F4), lifetime odometer (G1),
 * odometer typo guard (G4), station rating baseline / route mix / min cycles (H1/H2).
 */
class Batch2FuelOdometerTest {

    private val seed = FuelModelState(designCapacityL = 40.0, actualCapacityL = 40.0, defaultKmpl = 15.0, anchorLitres = 20.0)

    private fun refill(t: Long, odo: Double, rs: Double, full: Boolean = false, gauge: Double? = null) =
        FuelReplay.Input.Refill(t, odo, rs, 100.0, full, gauge)

    // ---------------- E1 / E10: edits replay the history ----------------

    @Test
    fun e1_correctedTypo_replaysToSameStateAsCorrectHistory() {
        val correct = listOf(
            refill(1_000, 1000.0, 3000.0, full = true),
            refill(2_000, 1300.0, 2000.0, full = true),
            refill(3_000, 1600.0, 2000.0, full = true)
        )
        // ₹10,000 typed instead of ₹1,000 on the middle refill (100 L into a 40 L tank).
        val typo = correct.toMutableList().also { it[1] = refill(2_000, 1300.0, 10_000.0, full = true) }
        val fromTypo = FuelReplay.replay(seed, typo)
        val fromCorrect = FuelReplay.replay(seed, correct)
        assertTrue("typo inflated capacity: ${fromTypo.actualCapacityL}", fromTypo.actualCapacityL > 40.0 * 1.2)
        // After the edit the replay must equal the clean history, not keep the typo's learning.
        val edited = FuelReplay.replay(seed, typo.toMutableList().also { it[1] = correct[1] })
        assertEquals(fromCorrect.actualCapacityL, edited.actualCapacityL, 1e-9)
        assertEquals(fromCorrect.anchorLitres, edited.anchorLitres, 1e-9)
        assertEquals(fromCorrect.segments.map { it.kmpl }, edited.segments.map { it.kmpl })
    }

    @Test
    fun e1_deletedRefill_isGoneFromModel() {
        val withRefill = listOf(refill(1_000, 1000.0, 4000.0, full = true), refill(2_000, 1200.0, 1500.0, gauge = 6.0))
        val deleted = withRefill.take(1)
        val a = FuelReplay.replay(seed, withRefill)
        val b = FuelReplay.replay(seed, deleted)
        assertNotEquals(a.anchorOdoKm, b.anchorOdoKm, 0.0)
        assertEquals(1000.0, b.anchorOdoKm, 0.0)
        assertEquals(b.actualCapacityL, b.anchorLitres, 1e-9) // full tank anchor
    }

    @Test
    fun e10_replayIsOrderIndependentOfInputListOrder() {
        val inputs = listOf(
            refill(3_000, 1600.0, 2000.0, full = true),
            FuelReplay.Input.Gauge(2_500, 1500.0, 4.0),
            refill(1_000, 1000.0, 3000.0, full = true)
        )
        val a = FuelReplay.replay(seed, inputs)
        val b = FuelReplay.replay(seed, inputs.sortedBy { it.timeMs })
        assertEquals(a, b)
    }

    @Test
    fun e10_unloggedMarkerParses() {
        assertEquals(42L to 1700000000000L, FuelReplay.parseUnloggedMarker("PROVISIONAL • x • [unlogged:42:1700000000000]"))
        assertNull(FuelReplay.parseUnloggedMarker("no marker"))
    }

    // ---------------- E7: fresh install ----------------

    @Test
    fun e7_unknownTank_neverBooksUnloggedDebt_andFirstRefillClearsIt() {
        val fresh = seed.copy(tankUnknown = true, anchorOdoKm = 0.0)
        val (afterTrip, entry) = UnloggedFuel.onTripEnded(fresh, 1L, 500.0, 500.0, 10_000L)
        assertNull("placeholder litres cannot prove the tank ran dry", entry)
        assertTrue(afterTrip.tankUnknown)
        val refilled = FuelReplay.apply(afterTrip, refill(20_000, 500.0, 1000.0, gauge = 4.0))
        assertFalse(refilled.tankUnknown)
        val gauged = FuelModel.onGaugeReading(fresh, 5.0, 10.0, 30_000L)
        assertFalse(gauged.tankUnknown)
    }

    // ---------------- E8 / E9 ----------------

    @Test
    fun e8_lowFuel_isRelativeToTankAndRange() {
        // 7 L in a 60 L tank with 140 km range: old rule (< 7 L) said fine; 11.7 % is low now.
        assertTrue(FuelDisplayPolicy.isLowFuel(7.0, 60.0, 140.0, tankUnknown = false))
        // 6 L in a 20 L scooter tank with 240 km range: 30 %, not low.
        assertFalse(FuelDisplayPolicy.isLowFuel(6.0, 20.0, 240.0, tankUnknown = false))
        assertTrue("range < 60 km", FuelDisplayPolicy.isLowFuel(20.0, 60.0, 50.0, tankUnknown = false))
        assertFalse(FuelDisplayPolicy.isLowFuel(1.0, 60.0, 10.0, tankUnknown = true))
    }

    @Test
    fun e8_efficiencyTier_isRelativeToLearnedMileage() {
        assertEquals("ECO", FuelDisplayPolicy.efficiencyTier(11.5, 10.0)) // SUV at its best
        assertEquals("OPTIMAL", FuelDisplayPolicy.efficiencyTier(10.0, 10.0))
        assertEquals("HIGH", FuelDisplayPolicy.efficiencyTier(14.0, 18.0))
    }

    @Test
    fun e9_gaugeNudge_onlyForPartialRefillWithDebt() {
        assertTrue(FuelDisplayPolicy.shouldNudgeGaugeAfter(2.5, fullTank = false, gaugeAfterGiven = false))
        assertFalse(FuelDisplayPolicy.shouldNudgeGaugeAfter(2.5, fullTank = true, gaugeAfterGiven = false))
        assertFalse(FuelDisplayPolicy.shouldNudgeGaugeAfter(1.0, fullTank = false, gaugeAfterGiven = false))
    }

    // ---------------- F2 / F4: calibration ----------------

    @Test
    fun f4_calibration_learnsFromRefillTruth_andScalesEstimator() {
        var c = 1.0
        repeat(10) { c = TripFuelCalibration.update(c, actualL = 36.0, predictedL = 30.0) }
        assertEquals(1.2, c, 0.02)
        assertEquals("too little evidence", 1.0, TripFuelCalibration.update(1.0, 1.0, 1.0), 0.0)
        assertEquals(TripFuelCalibration.MAX, TripFuelCalibration.update(TripFuelCalibration.MAX, 300.0, 3.0), 1e-9)

        val plain = TripFuelEstimator()
        val calibrated = TripFuelEstimator().apply { calibration = 1.2 }
        plain.onStep(1.0, 60.0, 0.0, 15.0)
        calibrated.onStep(1.0, 60.0, 0.0, 15.0)
        assertEquals(plain.litres * 1.2, calibrated.litres, 1e-12)
    }

    // ---------------- G1 / G4 ----------------

    @Test
    fun g1_lifetimeCounter_buffersBeforeSeed_andNeverDropsShortTrips() {
        val c = LifetimeDistanceCounter()
        c.addMeters(400.0, 0) // a 400 m trip before the seed finished
        assertEquals(0.0, c.totalKm, 0.0)
        c.seed(1234.5)
        assertEquals(1234.9, c.totalKm, 1e-9)
        c.addMeters(-5.0, 1); c.addMeters(Double.NaN, 2)
        assertEquals(1234.9, c.totalKm, 1e-9)
        c.seed(9999.0) // second seed ignored
        assertEquals(1234.9, c.totalKm, 1e-9)
    }

    @Test
    fun g1_lifetimeCounter_persistThrottle() {
        val c = LifetimeDistanceCounter(seeded = true)
        assertTrue(c.addMeters(10.0, 0)); c.markPersisted(0)
        assertFalse(c.addMeters(10.0, 1000))
        assertTrue("0.1 km since last write", c.addMeters(95.0, 2000)); c.markPersisted(2000)
        assertTrue("30 s since last write", c.addMeters(1.0, 33_000))
    }

    @Test
    fun g4_odometerTypoGuard() {
        assertEquals(OdometerReadingValidator.Result.OK, OdometerReadingValidator.validate(45_010.0, 45_000.0, false))
        assertEquals(OdometerReadingValidator.Result.TOO_LOW, OdometerReadingValidator.validate(4_500.0, 45_000.0, false))
        assertEquals(OdometerReadingValidator.Result.OK, OdometerReadingValidator.validate(44_999.5, 45_000.0, false))
        assertEquals(OdometerReadingValidator.Result.NEEDS_CONFIRMATION, OdometerReadingValidator.validate(54_000.0, 45_000.0, false))
        assertEquals("first reading sets the scale", OdometerReadingValidator.Result.OK, OdometerReadingValidator.validate(54_000.0, 12.0, true))
        assertEquals(OdometerReadingValidator.Result.INVALID, OdometerReadingValidator.validate(Double.NaN, 1.0, false))
    }

    // ---------------- H1 / H2 ----------------

    private fun cycle(id: Long, station: Long, kmpl: Double) =
        HybridMileageEngine.Cycle(id, station, kmpl, 'A', 300.0, 300.0 / kmpl, id + 1)

    @Test
    fun h1_rating_usesCarLifetime_notFixed16_5() {
        // A 10 km/L SUV: 10.6 km/L at a pump is +6 % for this car (EXCELLENT), but was "POOR" vs 16.5.
        assertEquals(StationQuality.EXCELLENT, HybridMileageEngine.rateStation(10.6, 3, lifetimeKmpl = 10.0))
        assertEquals(StationQuality.POOR, HybridMileageEngine.rate(10.6))
    }

    @Test
    fun h2_ratingNeedsThreeCycles_andHighwayCycleIsNormalised() {
        assertEquals(StationQuality.NOT_AVAILABLE, HybridMileageEngine.rateStation(20.0, 2, 15.0))
        val cycles = listOf(cycle(1, 7, 18.0), cycle(3, 7, 15.0), cycle(5, 7, 15.0))
        val raw = HybridMileageEngine.stationAverages(cycles)[7]!!.first
        // Cycle 1 was all highway at the optimal speed vs a usual 30 km/h city mix.
        val factor = HybridMileageEngine.routeFactor(55.0, 30.0)
        assertTrue("highway is an easier route: $factor", factor > 1.0)
        val normalised = HybridMileageEngine.stationAverages(cycles) { if (it.anchorLogId == 1L) factor else 1.0 }[7]!!.first
        assertTrue(normalised < raw)
        assertEquals(1.0, HybridMileageEngine.routeFactor(null, 30.0), 0.0)
    }

    @Test
    fun h2_cycleRecordsClosingLog() {
        val r = HybridMileageEngine.evaluate(
            listOf(
                HybridMileageEngine.Entry(10, 7, RefuelMode.FULL_TANK, 30.0, 1000.0),
                HybridMileageEngine.Entry(11, 7, RefuelMode.FULL_TANK, 20.0, 1300.0)
            )
        )
        assertEquals(11L, r.cycles.single().closeLogId)
    }
}
