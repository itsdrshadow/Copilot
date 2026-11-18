package com.antigravity.copilot

import com.antigravity.copilot.core.map.intel.RoadSegmentMath
import com.antigravity.copilot.core.map.intel.SegmentPass
import com.antigravity.copilot.core.logic.TripFuelEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoadSegmentMathTest {

    private fun pass(km: Double, speed: Double, load: Double = TripFuelEstimator.loadFactor(speed, 0.0)) =
        SegmentPass(km = km, loadKm = km * load, speedKm = km * speed, samples = (km * 20).toInt())

    @Test
    fun segmentKeyCombinesClassAndGrid() {
        val gLat = RoadSegmentMath.gridIndex(22.06)
        val gLon = RoadSegmentMath.gridIndex(83.17)
        assertEquals("primary_4412_16634", RoadSegmentMath.segmentKey("primary", gLat, gLon))
        // ~600 m north lands in another cell; same cell for a few metres.
        assertNotEquals(gLat, RoadSegmentMath.gridIndex(22.06 + 0.0055))
        assertEquals(gLat, RoadSegmentMath.gridIndex(22.0601))
    }

    @Test
    fun firstPassUsesPhysicsEstimateAgainstBaseline() {
        val row = RoadSegmentMath.merge(null, "k", "primary", "NH-49", pass(1.0, 55.0), 18.0, 1.0, 1L)!!
        assertEquals(55.0, row.avgSpeedKmh, 1e-9)
        assertEquals(18.0 / TripFuelEstimator.loadFactor(55.0, 0.0), row.rawEstimatedMileageKmpl, 1e-9)
        assertEquals(row.rawEstimatedMileageKmpl, row.calibratedMileageKmpl, 1e-9)
        assertEquals(1.0, row.totalDistanceKm, 1e-9)
    }

    @Test
    fun mergeIsDistanceWeightedAndCalibrated() {
        val a = RoadSegmentMath.merge(null, "k", "primary", null, pass(1.0, 40.0), 18.0, 1.0, 1L)!!
        val b = RoadSegmentMath.merge(a, "k", "primary", null, pass(3.0, 80.0), 18.0, 1.2, 2L)!!
        assertEquals((40.0 + 3 * 80.0) / 4.0, b.avgSpeedKmh, 1e-9)
        assertEquals(4.0, b.totalDistanceKm, 1e-9)
        assertEquals(b.rawEstimatedMileageKmpl * 1.2, b.calibratedMileageKmpl, 1e-9)
        assertEquals("name kept from nothing", null, b.roadName)
    }

    @Test
    fun historyIsCappedSoAveragesAdapt() {
        var row = RoadSegmentMath.merge(null, "k", "trunk", null, pass(500.0, 30.0), 18.0, 1.0, 1L)
        row = RoadSegmentMath.merge(row, "k", "trunk", null, pass(50.0, 90.0), 18.0, 1.0, 2L)!!
        // 500 km of history counts as 50 km: an equal new 50 km moves the average halfway.
        assertEquals(60.0, row.avgSpeedKmh, 1e-9)
        assertEquals(550.0, row.totalDistanceKm, 1e-9)
    }

    @Test
    fun emptyPassReturnsExistingRow() {
        assertNull(RoadSegmentMath.merge(null, "k", "minor", null, SegmentPass(0.0, 0.0, 0.0, 0), 18.0, 1.0, 1L))
    }

    @Test
    fun calibrationMultiplierFirstReplacesThenBlends() {
        val first = RoadSegmentMath.updatedMultiplier(1.0, 0, actualKmpl = 20.0, predictedKmpl = 16.0, fullTank = true)
        assertEquals(1.25, first, 1e-9)
        val second = RoadSegmentMath.updatedMultiplier(first, 1, actualKmpl = 16.0, predictedKmpl = 16.0, fullTank = true)
        assertEquals(1.125, second, 1e-9)
        val gauge = RoadSegmentMath.updatedMultiplier(first, 1, actualKmpl = 16.0, predictedKmpl = 16.0, fullTank = false)
        assertEquals(1.1875, gauge, 1e-9)
        // Absurd ratios are clamped.
        assertEquals(RoadSegmentMath.MAX_MULTIPLIER, RoadSegmentMath.updatedMultiplier(1.0, 0, 90.0, 10.0, true), 1e-9)
    }
}
