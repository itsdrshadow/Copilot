package com.antigravity.copilot.fuel

import com.antigravity.copilot.core.fuel.station.FuelStationEntity
import com.antigravity.copilot.core.fuel.station.FuelStationTracker
import com.antigravity.copilot.core.fuel.station.HybridMileageEngine
import com.antigravity.copilot.core.fuel.station.HybridMileageEngine.Entry
import com.antigravity.copilot.core.fuel.station.RefuelLogStatus
import com.antigravity.copilot.core.fuel.station.RefuelMode
import com.antigravity.copilot.core.fuel.station.StationQuality
import com.antigravity.copilot.core.road.RoadAnomalyMath
import com.antigravity.copilot.feature.fuel.RefuelInputSync
import com.antigravity.copilot.feature.fuel.SyncField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridMileageEngineTest {
    private var nextId = 1L
    private fun full(station: Long?, odo: Double, l: Double) = Entry(nextId++, station, RefuelMode.FULL_TANK, l, odo)
    private fun reserve(station: Long?, odo: Double, l: Double) = Entry(nextId++, station, RefuelMode.AT_RESERVE, l, odo)
    private fun topUp(odo: Double, l: Double) = Entry(nextId++, 99, RefuelMode.TOP_UP, l, odo)
    private fun marker(odo: Double) = Entry(nextId++, null, RefuelMode.RESERVE_MARKER, 0.0, odo)

    @Test fun ruleA_fullToFull_withTopUp_assignsStationA() {
        val a = full(1, 1000.0, 30.0); val t = topUp(1200.0, 5.0); val b = full(2, 1500.0, 20.0)
        val r = HybridMileageEngine.evaluate(listOf(a, t, b))
        assertEquals(1, r.cycles.size)
        assertEquals(1L, r.cycles[0].stationId)
        assertEquals(500.0 / 25.0, r.cycles[0].mileage, 1e-9)
        assertEquals(RefuelLogStatus.COMPLETED, r.outcomes[a.id]!!.status)
        assertEquals(20.0, r.outcomes[a.id]!!.mileage!!, 1e-9)
        assertEquals(RefuelLogStatus.COMPLETED, r.outcomes[t.id]!!.status)
        assertEquals(RefuelLogStatus.PENDING_EVALUATION, r.outcomes[b.id]!!.status)
    }

    @Test fun openCycleTopUpIsAccumulating() {
        val a = full(1, 1000.0, 30.0); val t = topUp(1200.0, 5.0)
        val r = HybridMileageEngine.evaluate(listOf(a, t))
        assertEquals(RefuelLogStatus.PENDING_EVALUATION, r.outcomes[a.id]!!.status)
        assertEquals(RefuelLogStatus.ACCUMULATING, r.outcomes[t.id]!!.status)
        assertTrue(r.cycles.isEmpty())
    }

    @Test fun ruleB_reserveToReserve_usesAnchorLitresPlusTopUps() {
        val a = reserve(1, 2000.0, 25.0); val t = topUp(2150.0, 5.0); val b = reserve(2, 2600.0, 28.0)
        val r = HybridMileageEngine.evaluate(listOf(a, t, b))
        assertEquals(1, r.cycles.size)
        assertEquals('B', r.cycles[0].rule)
        assertEquals(1L, r.cycles[0].stationId)
        assertEquals(600.0 / 30.0, r.cycles[0].mileage, 1e-9)
    }

    @Test fun reserveMarkerLocksOdometerForBothEnds() {
        val r = HybridMileageEngine.evaluate(
            listOf(marker(990.0), reserve(1, 1000.0, 25.0), marker(1580.0), reserve(2, 1600.0, 25.0))
        )
        assertEquals((1580.0 - 990.0) / 25.0, r.cycles.single().mileage, 1e-9)
        assertNull(r.pendingReserveOdometer)
    }

    @Test fun pendingMarkerIsReported() {
        val r = HybridMileageEngine.evaluate(listOf(full(1, 1000.0, 30.0), marker(1400.0)))
        assertEquals(1400.0, r.pendingReserveOdometer!!, 1e-9)
    }

    @Test fun ruleC_crossAnchorResetsWithoutRating_andReportsTrend() {
        val a = full(1, 1000.0, 30.0); val b = reserve(2, 1400.0, 20.0); val c = full(3, 1900.0, 25.0)
        val r = HybridMileageEngine.evaluate(listOf(a, b, c))
        assertTrue(r.cycles.isEmpty())
        assertEquals(RefuelLogStatus.COMPLETED, r.outcomes[a.id]!!.status)
        assertNull(r.outcomes[a.id]!!.mileage)
        assertEquals(RefuelLogStatus.COMPLETED, r.outcomes[b.id]!!.status)
        assertEquals(RefuelLogStatus.PENDING_EVALUATION, r.outcomes[c.id]!!.status)
        assertEquals(RefuelMode.FULL_TANK, r.openAnchorMode)
        assertEquals(900.0 / 50.0, r.trendKmpl!!, 1e-9)
    }

    @Test fun noTrendWhenACycleClosedRecently() {
        val r = HybridMileageEngine.evaluate(listOf(full(1, 1000.0, 30.0), full(1, 1500.0, 30.0), topUp(1600.0, 5.0)))
        assertNull(r.trendKmpl)
    }

    @Test fun implausibleMileageIsDiscarded() {
        // Odometer typo: went backwards.
        val a = full(1, 1000.0, 30.0)
        val r = HybridMileageEngine.evaluate(listOf(a, full(2, 900.0, 30.0)))
        assertTrue(r.cycles.isEmpty())
        assertNull(r.outcomes[a.id]!!.mileage)
    }

    @Test fun topUpWithoutAnchorIsCompleted() {
        val t = topUp(100.0, 5.0)
        assertEquals(RefuelLogStatus.COMPLETED, HybridMileageEngine.evaluate(listOf(t)).outcomes[t.id]!!.status)
    }

    @Test fun stationAverageIsRollingLastFive() {
        val cycles = (1..7).map { HybridMileageEngine.Cycle(it.toLong(), 5L, it.toDouble() * 2, 'A') }
        val (avg, count) = HybridMileageEngine.stationAverages(cycles)[5L]!!
        assertEquals((6.0 + 8 + 10 + 12 + 14) / 5, avg, 1e-9)
        assertEquals(7, count)
    }

    @Test fun ratingBands() {
        assertEquals(StationQuality.EXCELLENT, HybridMileageEngine.rate(17.4))
        assertEquals(StationQuality.AVERAGE, HybridMileageEngine.rate(16.5))
        assertEquals(StationQuality.AVERAGE, HybridMileageEngine.rate(15.4)) // -6.7 %: gap band
        assertEquals(StationQuality.POOR, HybridMileageEngine.rate(15.0))
        assertEquals(StationQuality.NOT_AVAILABLE, HybridMileageEngine.rate(0.0))
    }

    // ---- amount / rate / litres sync ----
    @Test fun syncDerivesThirdFromLastTwo() {
        var order = listOf(SyncField.RATE, SyncField.AMOUNT)
        assertEquals(SyncField.LITRES, RefuelInputSync.derivedField(order))
        assertEquals(10.0, RefuelInputSync.resolve(1000.0, 100.0, 0.0, SyncField.LITRES).litres, 1e-9)

        order = RefuelInputSync.touch(order, SyncField.LITRES) // amount + litres typed
        assertEquals(SyncField.RATE, RefuelInputSync.derivedField(order))
        assertEquals(104.0, RefuelInputSync.resolve(1040.0, 0.0, 10.0, SyncField.RATE).rate, 1e-9)

        order = RefuelInputSync.touch(order, SyncField.RATE) // litres + rate typed
        assertEquals(SyncField.AMOUNT, RefuelInputSync.derivedField(order))
        assertEquals(1000.0, RefuelInputSync.resolve(0.0, 100.0, 10.0, SyncField.AMOUNT).amount, 1e-9)
    }

    @Test fun syncZeroDivisorIsSafe() {
        assertEquals(0.0, RefuelInputSync.resolve(500.0, 0.0, 0.0, SyncField.LITRES).litres, 0.0)
        assertEquals(0.0, RefuelInputSync.resolve(500.0, 0.0, 0.0, SyncField.RATE).rate, 0.0)
    }

    // ---- proximity bubble ----
    private fun station(id: Long, north: Double, east: Double = 0.0, avg: Double = 0.0, q: StationQuality = StationQuality.NOT_AVAILABLE): FuelStationEntity {
        val (lat, lon) = RoadAnomalyMath.offset(21.0, 81.0, 0.0, north)
        val (lat2, lon2) = RoadAnomalyMath.offset(lat, lon, 90.0, east)
        return FuelStationEntity(stationId = id, name = "S$id", latitude = lat2, longitude = lon2, lastFuelRate = 104.5, averageMileage = avg, qualityRating = q.name)
    }

    @Test fun bubblePicksSingleClosestAhead() {
        val list = listOf(station(1, 250.0), station(2, 120.0), station(3, -50.0), station(4, 100.0, east = 200.0), station(5, 400.0))
        assertEquals(2L, FuelStationTracker.selectAhead(21.0, 81.0, 0.0, list)?.stationId)
    }

    @Test fun bubbleIgnoresStationsBehindOrOutOfRange() {
        assertNull(FuelStationTracker.selectAhead(21.0, 81.0, 0.0, listOf(station(3, -50.0), station(5, 400.0))))
    }

    @Test fun bubbleText() {
        // Unrated pump still shows its price (falls back to the latest when no average yet).
        assertEquals("⛽ S1 | avg ₹104.5/L | mileage: not rated yet", FuelStationTracker.bubbleText(station(1, 0.0)))
        val rated = FuelStationTracker.bubbleText(station(2, 0.0, avg = 17.8, q = StationQuality.EXCELLENT).copy(avgFuelRate = 102.25))
        assertTrue(rated, rated.startsWith("⛽ S2 | avg ₹102.25/L"))
        assertTrue(rated, rated.endsWith("17.8 km/l (★ Excellent)"))
    }
}
