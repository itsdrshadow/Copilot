package com.antigravity.copilot

import android.location.Location
import com.antigravity.copilot.core.location.GnssQualityGate
import com.antigravity.copilot.core.location.PositionSaveThrottle
import com.antigravity.copilot.core.location.PumpStopStateMachine
import com.antigravity.copilot.core.logic.DriverScoreCalculator
import com.antigravity.copilot.core.logic.HarshEventCounter
import com.antigravity.copilot.core.logic.OverspeedEventTracker
import com.antigravity.copilot.core.logic.VehicleMotionDebouncer
import com.antigravity.copilot.core.model.TripStatus
import com.antigravity.copilot.core.model.TripSummary
import com.antigravity.copilot.core.trip.TripDetectionEngine
import org.junit.Assert.*
import org.junit.Test

/**
 * Batch 1 audit fixes: monotonic clocks (A1), power-cut flag (A2), pump-stop state machine (A6),
 * position throttle (A5), GNSS gate (A7), driver score / harsh events (B1, B2), bridge fuel (B3),
 * tunnel bridge (B4), min trip distance (B5), resume drift (B7), motion debounce (L1).
 */
class Batch1ClockLifecycleTest {

    /** A fix whose wall clock and monotonic clock differ, like a head unit with a wrong RTC. */
    private class MonoLocation(
        private val lat: Double,
        private val lon: Double,
        private val wallMs: Long,
        private val monoMs: Long,
        private val speedKmh: Float,
        private val acc: Float = 5f
    ) : Location("gps") {
        override fun getLatitude() = lat
        override fun getLongitude() = lon
        override fun getTime() = wallMs
        override fun getElapsedRealtimeNanos() = monoMs * 1_000_000L
        override fun getAccuracy() = acc
        override fun hasAccuracy() = true
        override fun getSpeed() = speedKmh / 3.6f
        override fun hasSpeed() = true
        override fun getBearing() = 0f
        override fun hasBearing() = false
        override fun getAltitude() = 0.0
        override fun hasAltitude() = false
    }

    private fun engine(
        onCompleted: (TripSummary) -> Unit = {},
        onDiscarded: (Long) -> Unit = {}
    ) = TripDetectionEngine(
        onPointReceived = {},
        onTripStarted = {},
        onTripUpdated = { _, _ -> },
        onTripCompleted = onCompleted,
        onTripDiscarded = onDiscarded
    )

    /** ~ metres per degree latitude. */
    private val mPerDeg = 111_195.0

    // ---------------- A1: monotonic clocks ----------------

    @Test
    fun a1_wrongRtc_doesNotStopActiveTripOnWatchdog() {
        val e = engine()
        e.isGeofenceControlled = false
        var mono = 100_000L
        e.monoClock = { mono }
        e.isMonotonicDomain = true
        val wallSkew = 3 * 60_000L // RTC 3 minutes ahead of "real" time
        val wall0 = 1_700_000_000_000L
        var lat = 21.9
        // Drive 60 s at 36 km/h (10 m/s), 1 Hz.
        for (i in 0..60) {
            mono = 100_000L + i * 1000L
            e.processLocation(MonoLocation(lat, 83.4, wall0 + wallSkew + i * 1000L, mono, 36f))
            lat += 10.0 / mPerDeg
        }
        assertEquals(TripStatus.ACTIVE_TRIP, e.currentStatus)
        // Watchdog 2 s after the last fix on the monotonic clock: still driving.
        e.processStationaryWatchdog(mono + 2000L)
        assertEquals("RTC skew must not end / pause the trip", TripStatus.ACTIVE_TRIP, e.currentStatus)
        // Trip ID / start stamp stay wall-clock.
        assertTrue(e.currentTripId >= wall0)
    }

    // ---------------- A2: restored power-cut flag ----------------

    @Test
    fun a2_restoredTemporaryStop_movementClearsPowerCutFlag() {
        val e = engine()
        e.isGeofenceControlled = false
        val t0 = 1_000_000L
        e.restoreActiveTripState(
            tripId = 42L, startTime = t0, distanceMeters = 5000.0, drivingMs = 600_000L,
            stationaryMs = 0L, lastLocation = null, status = TripStatus.TEMPORARY_STOP
        )
        assertTrue(e.isPowerDisconnected)
        var lat = 21.9
        for (i in 0..20) {
            e.processCoordinates(lat, 83.4, 5f, 40f, timestamp = t0 + 10_000L + i * 1000L)
            lat += 11.0 / mPerDeg
        }
        assertEquals(TripStatus.ACTIVE_TRIP, e.currentStatus)
        assertFalse("Movement proves ignition is on", e.isPowerDisconnected)
    }

    // ---------------- B1 / B2: driver score & harsh events ----------------

    @Test
    fun b1_tenMinutesAt90_isOneOverspeedEvent_notSixHundred() {
        val e = engine()
        e.isGeofenceControlled = false
        val t0 = 2_000_000L
        var lat = 21.9
        for (i in 0..600) {
            e.processCoordinates(lat, 83.4, 5f, 90f, timestamp = t0 + i * 1000L)
            lat += 25.0 / mPerDeg
        }
        val s = e.getCurrentTripSummary()!!
        assertEquals("one continuous excursion = one event", 1, s.overspeedCount)
        assertTrue("score was floored at 50 before; got ${s.driverScore}", s.driverScore >= 90)
    }

    @Test
    fun b1_overspeedUsesSpeedLimitProvider() {
        val t = OverspeedEventTracker()
        for (i in 0..10) t.onSample(i * 1000L, 90.0, 100.0)
        assertEquals("90 under a 100 limit", 0, t.events)
        for (i in 11..20) t.onSample(i * 1000L, 110.0, 100.0)
        assertEquals(1, t.events)
        for (i in 21..22) t.onSample(i * 1000L, 95.0, 100.0)
        for (i in 23..24) t.onSample(i * 1000L, 110.0, 100.0)
        assertEquals("needs 5 s above limit + 5", 1, t.events)
    }

    @Test
    fun b1_scoreIsNormalisedPer100Km() {
        assertEquals(100, DriverScoreCalculator.score(0, 0, 0, 50.0))
        val shortTrip = DriverScoreCalculator.score(1, 0, 0, 2.0)
        val longTrip = DriverScoreCalculator.score(1, 0, 0, 200.0)
        assertTrue(longTrip > shortTrip)
        assertEquals("disabled scoring", 100, DriverScoreCalculator.score(50, 50, 50, 1.0, enabled = false))
        assertTrue(DriverScoreCalculator.score(1000, 0, 0, 1.0) >= 0)
    }

    @Test
    fun b2_harshCounter_ignoresBadDt_andIsEdgeTriggered() {
        val c = HarshEventCounter()
        c.onSample(-6.0, 100L, 50.0) // duplicate fix (dt 100 ms): ignored
        c.onSample(-6.0, 5000L, 50.0) // GPS gap: ignored
        assertEquals(0, c.harshBrakes)
        repeat(5) { c.onSample(-4.0, 1000L, 50.0) } // one long hard brake
        assertEquals(1, c.harshBrakes)
        c.onSample(-0.5, 1000L, 50.0)
        c.onSample(-4.0, 1000L, 40.0)
        assertEquals(2, c.harshBrakes)
        repeat(3) { c.onSample(3.5, 1000L, 30.0) }
        assertEquals(1, c.rapidAccels)
    }

    // ---------------- B3: bridge distance fuel ----------------

    @Test
    fun b3_candidateDistance_goesToBridgePath_notFakeSpeed() {
        val e = engine()
        e.isGeofenceControlled = false
        val bridged = mutableListOf<Double>()
        val live = mutableListOf<Triple<Double, Double, Long>>()
        e.onBridgeDistance = { bridged += it }
        e.onLiveMovement = { d, v, dt -> live += Triple(d, v, dt) }
        var lat = 21.9
        for (i in 0..30) {
            e.processCoordinates(lat, 83.4, 5f, 30f, timestamp = 3_000_000L + i * 1000L)
            lat += 8.3 / mPerDeg
        }
        assertEquals(TripStatus.ACTIVE_TRIP, e.currentStatus)
        assertTrue("candidate distance must be charged via the bridge path", bridged.sum() > 0.0)
        assertTrue("no synthetic 15 km/h / 1 s step", live.none { it.second == 15.0 && it.third == 1000L && it.first > 0.0 })
    }

    // ---------------- B4: tunnel exit with a coarse first fix ----------------

    @Test
    fun b4_coarseTunnelExit_keepsBridgePending_untilAccurateFix() {
        val e = engine()
        e.isGeofenceControlled = false
        val t0 = 4_000_000L
        var lat = 21.9
        var t = t0
        for (i in 0..40) {
            e.processCoordinates(lat, 83.4, 5f, 54f, timestamp = t)
            lat += 15.0 / mPerDeg; t += 1000L
        }
        val before = e.totalDistanceMeters
        // 60 s tunnel at 15 m/s = 900 m, no fixes.
        lat += 900.0 / mPerDeg; t += 60_000L
        // Exit: first fixes are coarse (30 m: good enough for the 35 m warm-up, not for the
        // 25 m bridge), then accurate.
        for (i in 0..5) {
            e.processCoordinates(lat, 83.4, 30f, 54f, timestamp = t)
            lat += 15.0 / mPerDeg; t += 1000L
        }
        for (i in 0..5) {
            e.processCoordinates(lat, 83.4, 5f, 54f, timestamp = t)
            lat += 15.0 / mPerDeg; t += 1000L
        }
        val gained = e.totalDistanceMeters - before
        assertTrue("tunnel distance must be bridged, gained=$gained", gained > 900.0)
        assertTrue("no double counting, gained=$gained", gained < 1300.0)
    }

    // ---------------- B5: minimum saved trip distance in geofence mode ----------------

    @Test
    fun b5_geofenceModeJunkTrip_isDiscarded() {
        var completed: TripSummary? = null
        var discarded = 0L
        val e = engine(onCompleted = { completed = it }, onDiscarded = { discarded = it })
        e.isGeofenceControlled = true
        val loc = TripDetectionEngine.CoordinateLocation(21.9, 83.4, 5_000_000L)
        e.startGeofenceTrip(5_000_000L, loc, departureDistanceMeters = 50.0)
        e.completeGeofenceTrip(5_060_000L, "Home")
        assertNull("50 m trip must not be saved", completed)
        assertEquals(5_000_000L, discarded)
    }

    // ---------------- B7: drift during a long stop ----------------

    @Test
    fun b7_resumeAfterLongStop_doesNotAddDriftSegment() {
        val e = engine()
        e.isGeofenceControlled = false
        var t = 6_000_000L
        var lat = 21.9
        for (i in 0..30) { e.processCoordinates(lat, 83.4, 5f, 36f, timestamp = t); lat += 10.0 / mPerDeg; t += 1000L }
        // Stop for 2 minutes at the same place.
        for (i in 0..120) { e.processCoordinates(lat, 83.4, 5f, 0f, timestamp = t); t += 1000L }
        assertEquals(TripStatus.TEMPORARY_STOP, e.currentStatus)
        val before = e.totalDistanceMeters
        // Resume fix arrives 60 m away (drift + creep), then normal driving.
        lat += 60.0 / mPerDeg
        e.processCoordinates(lat, 83.4, 5f, 20f, timestamp = t); t += 1000L
        assertEquals(TripStatus.ACTIVE_TRIP, e.currentStatus)
        lat += 6.0 / mPerDeg
        e.processCoordinates(lat, 83.4, 5f, 20f, timestamp = t)
        val added = e.totalDistanceMeters - before
        assertTrue("drift jump must not be added, added=$added", added < 20.0)
    }

    // ---------------- A6: pump-stop state machine ----------------

    @Test
    fun a6_pumpStop_checksOnce_promptsAfterDwell_thenCooldown() {
        val m = PumpStopStateMachine()
        fun tick(t: Long, v: Double) = m.onTick(t, v, 21.9, 83.4, true, 30_000L, 2.5)
        assertEquals(PumpStopStateMachine.Action.None, tick(0, 0.0))
        val check = tick(3000, 0.0)
        assertTrue(check is PumpStopStateMachine.Action.CheckStation)
        assertEquals(PumpStopStateMachine.Action.None, tick(4000, 0.0)) // only one lookup
        assertEquals("dwell not reached yet", PumpStopStateMachine.Action.None, m.onStationResult(5000, true, "HP Pump"))
        val prompt = tick(30_000, 0.0)
        assertEquals(PumpStopStateMachine.Action.Prompt("HP Pump", 21.9, 83.4), prompt)
        assertEquals("prompted once per stop", PumpStopStateMachine.Action.None, tick(31_000, 0.0))
        // Drive off and come back to the same pump 10 min later: cooldown.
        tick(40_000, 30.0)
        tick(600_000, 0.0)
        tick(603_000, 0.0)
        m.onStationResult(603_500, true, "HP Pump")
        assertEquals(PumpStopStateMachine.Action.None, tick(640_000, 0.0))
    }

    @Test
    fun a6_pumpStop_resultAfterDwell_promptsImmediately_andDriveOffCancels() {
        val m = PumpStopStateMachine()
        m.onTick(0, 0.0, 21.9, 83.4, true, 15_000L, 2.5)
        m.onTick(3000, 0.0, 21.9, 83.4, true, 15_000L, 2.5)
        m.onTick(20_000, 0.0, 21.9, 83.4, true, 15_000L, 2.5)
        assertTrue(m.onStationResult(20_500, true, "IOCL") is PumpStopStateMachine.Action.Prompt)
        val m2 = PumpStopStateMachine()
        m2.onTick(0, 0.0, 21.9, 83.4, true, 15_000L, 2.5)
        m2.onTick(3000, 0.0, 21.9, 83.4, true, 15_000L, 2.5)
        m2.onTick(4000, 20.0, 21.9, 83.4, true, 15_000L, 2.5)
        assertEquals(PumpStopStateMachine.Action.None, m2.onStationResult(20_000, true, "IOCL"))
    }

    // ---------------- A5 / A7 ----------------

    @Test
    fun a5_positionThrottle_writesEvery30sOr100m_andOnForce() {
        val p = PositionSaveThrottle()
        assertTrue(p.shouldSave(0, 21.9, 83.4))
        assertFalse(p.shouldSave(1000, 21.9001, 83.4))
        assertTrue(p.shouldSave(2000, 21.9 + 150.0 / mPerDeg, 83.4))
        assertFalse(p.shouldSave(3000, 21.9 + 150.0 / mPerDeg, 83.4))
        assertTrue(p.shouldSave(3500, 21.9 + 150.0 / mPerDeg, 83.4, force = true))
        assertTrue(p.shouldSave(40_000, 21.9 + 150.0 / mPerDeg, 83.4))
        assertFalse("never persist 0,0", p.shouldSave(100_000, 0.0, 0.0, force = true))
    }

    @Test
    fun a7_gnssGate_weakOnlyWithFreshLowSatelliteStatus() {
        val g = GnssQualityGate()
        assertFalse("no status (unsupported HAL) never blocks", g.isWeak(1000))
        g.onStatus(1000, 3)
        assertTrue(g.isWeak(2000))
        assertFalse("stale status", g.isWeak(20_000))
        g.onStatus(21_000, 7)
        assertFalse(g.isWeak(21_500))
        assertFalse(g.accept(isMock = true))
        assertTrue(g.accept(isMock = false))
    }

    @Test
    fun a7_weakGnss_pausesTripDistance() {
        val e = engine()
        e.isGeofenceControlled = false
        var lat = 21.9
        var t = 7_000_000L
        for (i in 0..30) { e.processCoordinates(lat, 83.4, 5f, 36f, timestamp = t); lat += 10.0 / mPerDeg; t += 1000L }
        val before = e.totalDistanceMeters
        e.distanceAccumulationPaused = true
        for (i in 0..10) { e.processCoordinates(lat, 83.4, 5f, 36f, timestamp = t); lat += 10.0 / mPerDeg; t += 1000L }
        assertEquals(before, e.totalDistanceMeters, 0.001)
    }

    // ---------------- L1: vehicle-state hysteresis ----------------

    @Test
    fun l1_stopAndGo_doesNotFlipStateEverySecond() {
        val d = VehicleMotionDebouncer()
        var flips = 0
        var prev = d.isMoving
        // 2 minutes of crawling traffic oscillating around the 2.5 km/h threshold.
        for (i in 0 until 120) {
            val v = if (i % 4 < 2) 4.0 else 1.0
            val now = d.onSample(i * 1000L, v, 2.5)
            if (now != prev) flips++
            prev = now
        }
        assertTrue("flips=$flips", flips <= 1)
        // A real 10 s stop does become idle.
        for (i in 120 until 135) d.onSample(i * 1000L, 0.0, 2.5)
        assertFalse(d.isMoving)
    }
}
