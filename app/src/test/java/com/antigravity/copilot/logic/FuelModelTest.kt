package com.antigravity.copilot.logic

import com.antigravity.copilot.core.logic.fuel.FuelModel
import com.antigravity.copilot.core.logic.fuel.FuelModelState
import com.antigravity.copilot.core.logic.fuel.GaugeCurve
import com.antigravity.copilot.core.logic.fuel.GaugeObservation
import com.antigravity.copilot.core.logic.fuel.MileageEstimator
import com.antigravity.copilot.core.logic.fuel.MileageSegment
import com.antigravity.copilot.core.logic.fuel.RefillInput
import com.antigravity.copilot.core.logic.fuel.SegmentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class FuelModelTest {

    private val day = 86_400_000L

    @Test
    fun `uneven gauge curve is learned monotone with top and bottom buffers`() {
        val capacity = 48.0
        // True car: 4 L hidden below "0", 6 L above "10"; top half of the gauge holds more litres per point.
        fun trueLitres(p: Double) = 4.0 + 38.0 * (p / 10.0).pow(0.7)
        val obs = mutableListOf<GaugeObservation>()
        var t = 0L
        for (rep in 0 until 4) for (half in 1..19) {
            val p = half * 0.5
            obs += GaugeObservation(p, trueLitres(p) + if (rep % 2 == 0) 0.4 else -0.4, 1.0, t++)
        }
        obs += GaugeObservation(10.0, 47.5, 1.0) // still full at 47.5 L
        obs += GaugeObservation(10.0, 42.5, 1.0) // still full at 42.5 L -> full mark <= 42.5
        obs += GaugeObservation(0.0, 4.5, 1.0)   // already empty at 4.5 L -> bottom buffer >= 4.5

        val c = GaugeCurve.fit(capacity, obs)
        // Monotone non-decreasing
        c.knots.zipWithNext().forEach { (a, b) -> assertTrue(b >= a - 1e-9) }
        // Interior points close to the truth
        for (p in listOf(2.0, 5.0, 8.0)) assertEquals(trueLitres(p), c.litresAt(p), 1.2)
        // Not evenly spread: lower points hold more litres each than upper points for this curve
        val perPoint = c.litresPerPoint()
        assertTrue("uneven: $perPoint", perPoint[1] > perPoint[8] + 1.0)
        assertTrue(c.topBufferL >= 48.0 - 42.5 - 1e-6)
        assertTrue(c.bottomBufferL >= 4.5 - 1e-6)
        // Inverse mapping for the continuous bar
        assertEquals(10.0, c.pointFor(47.0), 1e-9)
        assertEquals(0.0, c.pointFor(2.0), 1e-9)
        assertEquals(5.0, c.pointFor(c.litresAt(5.0)), 0.05)
        // Smooth just below the full mark: 9.9, 9.8 ...
        val justBelow = c.pointFor(c.knots.last() - 0.2)
        assertTrue(justBelow in 9.5..9.999)
    }

    @Test
    fun `isotonic regression pools violators`() {
        val out = GaugeCurve.isotonic(doubleArrayOf(1.0, 3.0, 2.0, 4.0), doubleArrayOf(1.0, 1.0, 1.0, 1.0))
        assertEquals(listOf(1.0, 2.5, 2.5, 4.0), out.toList())
    }

    @Test
    fun `mileage is weighted over recent segments and confidence grows with each refill`() {
        val now = 100 * day
        val prior = 15.0
        val e0 = MileageEstimator.estimate(emptyList(), now, prior)
        assertEquals(prior, e0.kmpl, 1e-9)
        assertEquals(0.0, e0.confidence, 1e-9)

        val segs = mutableListOf<MileageSegment>()
        var lastConf = 0.0
        for (i in 0 until 4) {
            segs += MileageSegment(now - (4 - i) * 5 * day, 400.0, 400.0 / 18.0, SegmentType.FULL_TO_FULL)
            val e = MileageEstimator.estimate(segs, now, prior)
            assertTrue("confidence must grow (${e.confidence} > $lastConf)", e.confidence > lastConf)
            lastConf = e.confidence
        }
        val e = MileageEstimator.estimate(segs, now, prior)
        assertEquals(18.0, e.kmpl, 0.4)
        assertTrue(e.confidence > 0.8)

        // Old segments outside 30 days and beyond the last 3 are ignored.
        val old = listOf(MileageSegment(now - 200 * day, 400.0, 40.0, SegmentType.FULL_TO_FULL)) + segs
        assertEquals(e.kmpl, MileageEstimator.estimate(old, now, prior).kmpl, 1e-9)

        // Gauge-to-gauge segments count less than full-to-full.
        val mixed = segs.take(2) + MileageSegment(now, 100.0, 100.0 / 10.0, SegmentType.GAUGE_TO_GAUGE)
        val m = MileageEstimator.estimate(mixed, now, prior)
        assertTrue(m.kmpl > 13.0 && m.kmpl < 18.0)
    }

    @Test
    fun `tank estimate is anchor minus km over learned mileage`() {
        val s = FuelModelState(designCapacityL = 48.0, actualCapacityL = 48.0, defaultKmpl = 20.0, anchorLitres = 30.0, anchorOdoKm = 1000.0)
        val e = FuelModel.estimate(s, 1200.0, 0L)
        assertEquals(20.0, e.rawLitres, 1e-9)  // 30 - 200/20
        assertEquals(20.0 * 20.0, e.rangeKm, 1e-9)
        // Debt: raw goes below zero, display clamps at 0
        val d = FuelModel.estimate(s, 1800.0, 0L)
        assertEquals(-10.0, d.rawLitres, 1e-9)
        assertEquals(0.0, d.displayLitres, 1e-9)
        assertEquals(0.0, d.gaugePoints, 1e-9)
    }

    @Test
    fun `full to full refills create segments and actual capacity stays separate from design`() {
        var s = FuelModelState(designCapacityL = 48.0, actualCapacityL = 48.0, defaultKmpl = 15.0, anchorLitres = 20.0, anchorOdoKm = 0.0)
        var odo = 0.0
        var t = 0L
        // First full fill: 28 L
        s = FuelModel.applyRefill(s, RefillInput(t, 28.0 * 100, 100.0, odo, fullTank = true), null)
        // Three cycles of 450 km at 18 km/L, each refilled to full with 25 L
        repeat(3) {
            odo += 450.0; t += 7 * day
            s = FuelModel.applyRefill(s, RefillInput(t, 25.0 * 100, 100.0, odo, fullTank = true), null)
        }
        assertEquals(3, s.segments.size)
        val m = FuelModel.mileage(s, t)
        assertEquals(18.0, m.kmpl, 0.8)
        assertEquals(48.0, s.designCapacityL, 1e-9)
        assertTrue(s.actualCapacityL in 40.8..60.0)
        val est = FuelModel.estimate(s, odo, t)
        assertEquals(s.actualCapacityL, est.displayLitres, 1e-9)
        assertEquals(10.0, est.gaugePoints, 1e-9)
    }
}
