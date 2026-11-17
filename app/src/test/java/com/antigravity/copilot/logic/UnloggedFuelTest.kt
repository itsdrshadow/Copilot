package com.antigravity.copilot.logic

import com.antigravity.copilot.core.logic.Config
import com.antigravity.copilot.core.logic.fuel.FuelModel
import com.antigravity.copilot.core.logic.fuel.FuelModelState
import com.antigravity.copilot.core.logic.fuel.RefillInput
import com.antigravity.copilot.core.logic.fuel.UnloggedFuel
import com.antigravity.copilot.core.logic.fuel.UnloggedStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class UnloggedFuelTest {

    private fun base(anchorL: Double) = FuelModelState(
        designCapacityL = 48.0, actualCapacityL = 48.0, defaultKmpl = 20.0,
        anchorLitres = anchorL, anchorOdoKm = 0.0, anchorTimeMs = 0L
    )

    @Test
    fun `20 L, 400 km, forgotten 10 L refill is detected and allocated automatically`() {
        var s = base(20.0) // 20 L at 20 km/L = 400 km of range; user secretly added 10 L
        val trips = listOf(1L to 300.0, 2L to 100.0, 3L to 60.0, 4L to 40.0)
        var odo = 0.0
        val created = mutableListOf<Double>()
        for ((id, km) in trips) {
            odo += km
            val (next, entry) = UnloggedFuel.onTripEnded(s, id, km, odo, odo.toLong())
            s = next
            entry?.let { created += it.litres }
        }
        // Only trips ending at/below zero are provisional, and only their below-zero part.
        assertEquals(listOf(3.0, 2.0), created.map { Math.round(it * 1000) / 1000.0 })
        assertEquals(5.0, s.totalDebtL, 1e-9)
        assertEquals(-5.0, FuelModel.rawLitres(s, odo, 0L), 1e-9)
        assertEquals(0.0, FuelModel.estimate(s, odo, 0L).displayLitres, 1e-9) // UI clamps at 0
        assertTrue(s.unlogged.all { it.status == UnloggedStatus.PROVISIONAL })

        // Refill 30 L; gauge afterwards reads a point worth 35 L.
        val out = UnloggedFuel.onRefill(s, RefillInput(1_000L, 3000.0, 100.0, odo, fullTank = false, gaugeAfter = 7.0), gaugeAfterLitres = 35.0)
        assertEquals(30.0, out.litres, 1e-9)
        assertEquals(5.0, out.trueBeforeL!!, 1e-9)        // 35 - 30
        assertEquals(10.0, out.unexplainedL!!, 1e-9)      // true_before + debt
        assertEquals(10.0, out.autoUnloggedRefillL!!, 1e-9)
        assertNotNull(out.notice)
        // Debt (5 L) allocated back by km: 60 km -> 3 L, 40 km -> 2 L. Remainder 5 L was real fuel.
        val byTrip = out.resolved.associate { it.tripId to it.resolvedLitres }
        assertEquals(3.0, byTrip[3L]!!, 1e-9)
        assertEquals(2.0, byTrip[4L]!!, 1e-9)
        assertTrue(out.resolved.all { it.status == UnloggedStatus.RESOLVED && it.source == UnloggedFuel.SOURCE_RESOLVED })
        assertEquals(0.0, out.state.totalDebtL, 1e-9)
        assertEquals(35.0, out.state.anchorLitres, 1e-9)
        assertEquals(odo, out.state.anchorOdoKm, 1e-9)
    }

    @Test
    fun `impossible 136 L on a 48 L tank is not logged and lowers confidence instead`() {
        var s = base(10.0)
        // Seed some history so there is confidence to lose.
        s = s.copy(segments = List(3) { i ->
            com.antigravity.copilot.core.logic.fuel.MileageSegment(i.toLong(), 400.0, 20.0, com.antigravity.copilot.core.logic.fuel.SegmentType.FULL_TO_FULL)
        })
        val before = FuelModel.mileage(s, 10L)
        val (afterTrip, entry) = UnloggedFuel.onTripEnded(s, 7L, 2120.0, 2120.0, 10L)
        assertEquals(96.0, entry!!.litres, 1e-6)
        s = afterTrip

        val out = UnloggedFuel.onRefill(s, RefillInput(20L, 800.0, 100.0, 2120.0, fullTank = true), null)
        assertEquals(136.0, out.unexplainedL!!, 1e-6)
        assertNull("must not log an impossible refill", out.autoUnloggedRefillL)
        assertNotNull("quiet notice only", out.notice)
        assertTrue(out.resolved.all { it.resolvedLitres == 0.0 && it.source == UnloggedFuel.SOURCE_CLEARED })
        assertEquals(0.0, out.state.totalDebtL, 1e-9)
        val after = FuelModel.mileage(out.state, 20L)
        assertTrue("confidence lowered ${after.confidence} < ${before.confidence}", after.confidence < before.confidence)
        assertTrue("mileage correction bounded", abs(after.kmpl / before.kmpl - 1.0) <= Config.UNLOGGED_MAX_MILEAGE_CORRECTION + 1e-9)
        assertEquals(48.0, out.state.anchorLitres, 1e-9)
    }

    @Test
    fun `small surplus is explained by mileage, not by a phantom refill`() {
        val s = base(40.0)
        // 300 km at 20 km/L -> model expects 25 L; gauge says 26.5 L were there.
        val out = UnloggedFuel.onRefill(s, RefillInput(1L, 1000.0, 100.0, 300.0, false, 7.5), gaugeAfterLitres = 36.5)
        assertEquals(1.5, out.unexplainedL!!, 1e-9)
        assertNull(out.autoUnloggedRefillL)
        assertNull(out.notice)
        assertEquals(36.5, out.state.anchorLitres, 1e-9)
    }

    @Test
    fun `without a gauge reading provisional entries are kept for the next refill`() {
        var s = base(1.0)
        s = UnloggedFuel.onTripEnded(s, 1L, 100.0, 100.0, 0L).first
        assertEquals(4.0, s.totalDebtL, 1e-9)
        val out = UnloggedFuel.onRefill(s, RefillInput(1L, 1000.0, 100.0, 100.0, false, null), null)
        assertEquals(4.0, out.state.totalDebtL, 1e-9)
        assertTrue(out.resolved.isEmpty())
    }
}
