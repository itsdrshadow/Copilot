package com.antigravity.copilot.road

import com.antigravity.copilot.core.road.AheadAlertTracker
import com.antigravity.copilot.core.road.AnomalyPassTracker
import com.antigravity.copilot.core.road.AnomalySeverity
import com.antigravity.copilot.core.road.RoadAnomalyEntity
import com.antigravity.copilot.core.road.RoadAnomalyMath
import com.antigravity.copilot.core.road.RoadJerkCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadAnomalyLogicTest {
    private val baseLat = 21.0
    private val baseLon = 81.0

    /** Point [m] metres north of the base (heading 0). */
    private fun north(m: Double) = RoadAnomalyMath.offset(baseLat, baseLon, 0.0, m)

    private fun anomaly(id: Long, m: Double, east: Double = 0.0, heading: Float = 0f, sev: AnomalySeverity = AnomalySeverity.RED): RoadAnomalyEntity {
        val (lat, lon) = north(m)
        val (lat2, lon2) = RoadAnomalyMath.offset(lat, lon, 90.0, east)
        return RoadAnomalyEntity(id = id, latitude = lat2, longitude = lon2, maxZForce = 7f, severity = sev.name, headingDeg = heading)
    }

    // ---- classification ----
    @Test fun classifyThresholds() {
        assertNull(RoadAnomalyMath.classify(3.49f))
        assertEquals(AnomalySeverity.YELLOW, RoadAnomalyMath.classify(3.5f))
        assertEquals(AnomalySeverity.YELLOW, RoadAnomalyMath.classify(6.5f))
        assertEquals(AnomalySeverity.RED, RoadAnomalyMath.classify(6.51f))
    }

    // ---- jerk core ----
    private fun RoadJerkCore.spike(start: Long, mag: Float, gate: Boolean = true): Float? {
        var out: Float? = null
        var t = start
        repeat(12) { onLinear(0f, 0f, mag, t, gate)?.let { out = it }; t += 20 }
        repeat(5) { onLinear(0f, 0f, 0f, t, gate)?.let { out = it }; t += 20 }
        return out
    }

    @Test fun wakeUpGuardSuppressesFirstFiveSeconds() {
        val core = RoadJerkCore().apply { arm(0) }
        assertNull(core.spike(1_000, 9f))
        assertNotNull(core.spike(5_100, 9f))
    }

    @Test fun cooldownBlocksRearAxleDuplicate() {
        val core = RoadJerkCore().apply { arm(0) }
        assertNotNull(core.spike(6_000, 8f))
        assertNull(core.spike(7_000, 8f)) // rear axle 1 s later
        assertNotNull(core.spike(9_500, 8f))
    }

    @Test fun closedGateIgnoresJerk() {
        val core = RoadJerkCore().apply { arm(0) }
        assertNull(core.spike(6_000, 9f, gate = false))
    }

    @Test fun gravityProjectionUsesVerticalAxisOfUprightScreen() {
        // Upright head unit: gravity along device Y. A Z (longitudinal braking) jolt must not fire.
        val core = RoadJerkCore().apply { arm(0); onGravity(0f, 9.81f, 0f) }
        assertNull(core.spike(6_000, 9f))
        var peak: Float? = null
        var t = 10_000L
        repeat(12) { core.onLinear(0f, 8f, 0f, t, true)?.let { peak = it }; t += 20 }
        repeat(5) { core.onLinear(0f, 0f, 0f, t, true)?.let { peak = it }; t += 20 }
        assertNotNull(peak)
        assertTrue(peak!! > 6.5f)
    }

    // ---- geometry ----
    @Test fun boundingBoxContainsRadius() {
        val box = RoadAnomalyMath.boundingBox(baseLat, baseLon, 12.0)
        val (lat, lon) = north(11.9)
        assertTrue(lat in box.minLat..box.maxLat && lon in box.minLon..box.maxLon)
    }

    @Test fun segmentDistanceCatchesSkippedZone() {
        // 1 Hz at 100 km/h: fixes 14 m before and 14 m after the pothole, both > 12 m away... segment passes over it.
        val (aLat, aLon) = north(-14.0)
        val (bLat, bLon) = north(14.0)
        val d = RoadAnomalyMath.distanceToSegmentM(baseLat, baseLon, aLat, aLon, bLat, bLon)
        assertTrue(d < 1.0)
    }

    // ---- self-healing pass tracker ----
    private fun drive(tracker: AnomalyPassTracker, rows: List<RoadAnomalyEntity>, speed: Float = 40f, heading: Double = 0.0, from: Double = -60.0, to: Double = 60.0, t0: Long = 0L): List<Long> {
        val out = mutableListOf<Long>()
        var m = from; var t = t0
        while (m <= to) {
            val (lat, lon) = north(m)
            out += tracker.onFix(t, lat, lon, heading, speed, rows)
            m += 10.0; t += 1_000
        }
        return out
    }

    @Test fun smoothPassDecrementsOnce() {
        val tracker = AnomalyPassTracker()
        val rows = listOf(anomaly(1, 0.0))
        assertEquals(listOf(1L), drive(tracker, rows))
    }

    @Test fun passWithJerkIsNotSmooth() {
        val tracker = AnomalyPassTracker()
        val rows = listOf(anomaly(1, 0.0))
        val out = mutableListOf<Long>()
        var t = 0L
        for (m in -60..60 step 10) {
            val (lat, lon) = north(m.toDouble())
            if (m == 0) tracker.onJerk(1, t)
            out += tracker.onFix(t, lat, lon, 0.0, 40f, rows)
            t += 1_000
        }
        assertTrue(out.isEmpty())
    }

    @Test fun passCooldownOnePerMinute() {
        val tracker = AnomalyPassTracker()
        val rows = listOf(anomaly(1, 0.0))
        assertEquals(1, drive(tracker, rows, t0 = 0).size)
        assertEquals(0, drive(tracker, rows, t0 = 20_000).size)
        assertEquals(1, drive(tracker, rows, t0 = 100_000).size)
    }

    @Test fun slowPassThroughBreakerIsVoided() {
        val tracker = AnomalyPassTracker()
        val rows = listOf(anomaly(1, 0.0))
        val out = mutableListOf<Long>()
        var t = 0L
        for (m in -60..60 step 5) {
            val (lat, lon) = north(m.toDouble())
            val speed = if (m in -10..10) 8f else 40f
            out += tracker.onFix(t, lat, lon, 0.0, speed, rows)
            t += 1_000
        }
        assertTrue(out.isEmpty())
    }

    @Test fun oppositeCarriagewayDoesNotDecay() {
        val tracker = AnomalyPassTracker()
        val rows = listOf(anomaly(1, 0.0, heading = 180f))
        assertTrue(drive(tracker, rows, heading = 0.0).isEmpty())
    }

    // ---- ahead cone alert ----
    @Test fun alertAcquiresInConeAndDismissesWhenPassed() {
        val tracker = AheadAlertTracker()
        val rows = listOf(anomaly(7, 400.0))
        var t = 0L
        var first: Double? = null
        var shownAt100 = false
        var m = 0.0
        while (m <= 460.0) {
            val (lat, lon) = north(m)
            val a = tracker.update(t, lat, lon, 0.0, 50f, rows)
            if (a != null && first == null) first = a.distanceM
            if (m == 300.0) shownAt100 = a != null
            if (m >= 430.0) assertNull("dismissed after passing", a)
            m += 10.0; t += 1_000
        }
        assertNotNull(first)
        assertTrue(first!! in 150.0..200.0)
        assertTrue(shownAt100)
    }

    @Test fun anomalyOutsideConeIsIgnored() {
        val tracker = AheadAlertTracker()
        // 175 m ahead but 120 m to the side (≈ 34°): parallel road.
        val rows = listOf(anomaly(3, 145.0, east = 100.0))
        val d = RoadAnomalyMath.distanceM(baseLat, baseLon, rows[0].latitude, rows[0].longitude)
        assertTrue(d in 150.0..200.0)
        assertNull(tracker.update(0, baseLat, baseLon, 0.0, 50f, rows))
    }

    @Test fun anomalyBehindIsIgnored() {
        val tracker = AheadAlertTracker()
        assertNull(tracker.update(0, baseLat, baseLon, 0.0, 50f, listOf(anomaly(4, -180.0))))
    }

    @Test fun nearestInConeWins() {
        val tracker = AheadAlertTracker()
        val a = tracker.update(0, baseLat, baseLon, 0.0, 50f, listOf(anomaly(1, 195.0), anomaly(2, 160.0, sev = AnomalySeverity.YELLOW)))
        assertEquals(2L, a?.anomalyId)
        assertEquals(AnomalySeverity.YELLOW, a?.severity)
    }
}
