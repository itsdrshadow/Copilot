package com.antigravity.copilot

import android.location.Location
import com.antigravity.copilot.core.model.TripPoint
import com.antigravity.copilot.core.model.TripStatus
import com.antigravity.copilot.core.model.TripSummary
import com.antigravity.copilot.core.trip.TripDetectionEngine
import org.junit.Assert.*
import org.junit.Test

class CarIgnitionDeepSleepBatteryProtectionTest {

    private class SyntheticLocation(
        private val lat: Double,
        private val lon: Double,
        private val timeMs: Long,
        private val speedKmhVal: Float = 0f,
        private val acc: Float = 5f
    ) : Location("gps") {
        override fun getLatitude(): Double = lat
        override fun getLongitude(): Double = lon
        override fun getTime(): Long = timeMs
        override fun getAccuracy(): Float = acc
        override fun hasAccuracy(): Boolean = true
        override fun getSpeed(): Float = speedKmhVal / 3.6f
        override fun hasSpeed(): Boolean = speedKmhVal > 0f
        override fun getBearing(): Float = 90f
        override fun hasBearing(): Boolean = true
        override fun getAltitude(): Double = 100.0
        override fun hasAltitude(): Boolean = true

        override fun distanceTo(dest: Location): Float {
            return TripDetectionEngine.computeGeodesicDistance(lat, lon, dest.latitude, dest.longitude).toFloat()
        }
    }

    @Test
    fun testIgnitionPowerCut_PreservesOngoingTripInTemporaryStop() {
        var completedSummary: TripSummary? = null
        var discardedTripId = 0L

        val engine = TripDetectionEngine(
            onPointReceived = {},
            onTripStarted = {},
            onTripUpdated = { _, _ -> },
            onTripCompleted = { completedSummary = it },
            onTripDiscarded = { discardedTripId = it }
        )
        engine.isGeofenceControlled = false
        engine.isGpsStabilized = true

        val t0 = 1000000L
        engine.restoreActiveTripState(
            tripId = 101L,
            startTime = t0,
            distanceMeters = 2000.0,
            drivingMs = 120000L,
            stationaryMs = 0L,
            lastLocation = SyntheticLocation(21.5, 83.0, t0, 40f),
            originName = "Raigarh"
        )

        assertEquals("Trip must be ACTIVE_TRIP", TripStatus.ACTIVE_TRIP, engine.currentStatus)
        assertEquals(101L, engine.currentTripId)

        // Ignition power cut (ACTION_POWER_DISCONNECTED)
        val powerCutTime = t0 + 130000L
        engine.onPowerDisconnected(powerCutTime)

        // Verify ongoing trip is preserved in TEMPORARY_STOP, NOT terminated or discarded
        assertEquals("Ongoing trip must transition to TEMPORARY_STOP on power cut", TripStatus.TEMPORARY_STOP, engine.currentStatus)
        assertTrue("isPowerDisconnected flag must be true", engine.isPowerDisconnected)
        assertNull("Trip must NOT be completed on power cut", completedSummary)
        assertEquals("Trip must NOT be discarded on power cut", 0L, discardedTripId)

        // Verify snapshot summary is accessible and holds TEMPORARY_STOP
        val summary = engine.getCurrentTripSummary(powerCutTime)
        assertNotNull("Current trip summary must be non-null", summary)
        assertEquals(TripStatus.TEMPORARY_STOP, summary!!.status)
        assertEquals(2000.0, summary.distanceMeters, 0.1)
    }

    @Test
    fun testIgnitionPowerCut_OneHourDeepSleep_NoExtraDrivingOrStationaryTimeAdded() {
        var completedSummary: TripSummary? = null

        val engine = TripDetectionEngine(
            onPointReceived = {},
            onTripStarted = {},
            onTripUpdated = { _, _ -> },
            onTripCompleted = { completedSummary = it },
            onTripDiscarded = {}
        )
        engine.isGeofenceControlled = false
        engine.isGpsStabilized = true

        val t0 = 1000000L
        engine.restoreActiveTripState(
            tripId = 555L,
            startTime = t0,
            distanceMeters = 5000.0,
            drivingMs = 180000L, // 3 minutes driving
            stationaryMs = 0L,
            lastLocation = SyntheticLocation(21.5, 83.0, t0 + 180000L, 0f),
            originName = "Pusaur"
        )

        val drivingMsBeforePowerCut = engine.drivingDurationMs
        val stationaryMsBeforePowerCut = engine.stationaryDurationMs
        assertEquals(180000L, drivingMsBeforePowerCut)
        assertEquals(0L, stationaryMsBeforePowerCut)

        // Car stops and ignition cuts
        val powerCutTime = t0 + 190000L
        engine.onPowerDisconnected(powerCutTime)
        assertEquals(TripStatus.TEMPORARY_STOP, engine.currentStatus)
        assertTrue(engine.isPowerDisconnected)

        // 1 Hour of Deep Sleep: 3600 seconds (3,600,000 ms)
        val oneHourLater = powerCutTime + 3600000L

        // Periodic watchdogs during deep sleep must NOT alter or finalize the trip
        engine.processStationaryWatchdog(powerCutTime + 300000L) // 5 min
        engine.processStationaryWatchdog(powerCutTime + 600000L) // 10 min
        engine.processStationaryWatchdog(oneHourLater)           // 1 hour
        assertNull("Trip must NOT be finalized by watchdog during deep sleep", completedSummary)
        assertEquals("Trip must remain in TEMPORARY_STOP during deep sleep", TripStatus.TEMPORARY_STOP, engine.currentStatus)

        // Car ignition powers ON (ACTION_POWER_CONNECTED)
        engine.onPowerConnected(oneHourLater)
        assertFalse("isPowerDisconnected must be false after power reconnected", engine.isPowerDisconnected)

        // Car idles with ignition on for 15 seconds
        val idlePoint = SyntheticLocation(21.5, 83.0, oneHourLater + 15000L, 0f)
        engine.processLocation(idlePoint)
        assertEquals(TripStatus.TEMPORARY_STOP, engine.currentStatus)

        // Car resumes driving at 40 km/h (moved 50 meters)
        val resumePoint = SyntheticLocation(21.5005, 83.0, oneHourLater + 20000L, 40f)
        engine.processLocation(resumePoint)
        assertEquals("Trip must resume to ACTIVE_TRIP", TripStatus.ACTIVE_TRIP, engine.currentStatus)

        // Driving duration must NOT have accumulated the 1 hour (3,600,000 ms)
        val drivingMsAfterResume = engine.drivingDurationMs
        val diffDriving = drivingMsAfterResume - drivingMsBeforePowerCut
        assertTrue("No extra driving time from deep sleep: diff=$diffDriving", diffDriving <= 10000L)

        // Stationary duration must ONLY reflect the 20 seconds idle after ignition on, NOT the 1 hour
        val stationaryMsAfterResume = engine.stationaryDurationMs
        val diffStationary = stationaryMsAfterResume - stationaryMsBeforePowerCut
        assertTrue(
            "Stationary timer must NOT include 1 hour deep sleep (diff was $diffStationary ms, expected <= 25000 ms)",
            diffStationary in 0L..25000L
        )
    }

    @Test
    fun testIgnitionPowerCut_FiveDaysDeepSleep_NoExtraTimeAddedAndSeamlessContinuation() {
        var completedSummary: TripSummary? = null

        val engine = TripDetectionEngine(
            onPointReceived = {},
            onTripStarted = {},
            onTripUpdated = { _, _ -> },
            onTripCompleted = { completedSummary = it },
            onTripDiscarded = {}
        )
        engine.isGeofenceControlled = false
        engine.isGpsStabilized = true

        val t0 = 1000000L
        // Restore active trip with 50 km and 1 hour driving duration
        engine.restoreActiveTripState(
            tripId = 888L,
            startTime = t0,
            distanceMeters = 50000.0,
            drivingMs = 3600000L,
            stationaryMs = 600000L,
            lastLocation = SyntheticLocation(21.5, 83.0, t0 + 3600000L, 0f),
            originName = "Raigarh"
        )

        assertEquals(TripStatus.ACTIVE_TRIP, engine.currentStatus)
        assertEquals(888L, engine.currentTripId)

        // Driver parks at hotel and cuts ignition (Intent.ACTION_POWER_DISCONNECTED)
        val powerCutTime = t0 + 3610000L
        engine.onPowerDisconnected(powerCutTime)
        assertEquals(TripStatus.TEMPORARY_STOP, engine.currentStatus)

        // 5 DAYS Deep Sleep: 5 * 24 * 3600 * 1000 ms = 432,000,000 ms
        val fiveDaysLater = powerCutTime + (5L * 24L * 3600L * 1000L)

        // Stationary watchdog ticks over the 5 days must NOT complete or discard the trip
        for (day in 1..5) {
            val dayTimestamp = powerCutTime + (day * 24L * 3600L * 1000L)
            engine.processStationaryWatchdog(dayTimestamp)
            assertNull("Trip must never be completed during multi-day engine off", completedSummary)
            assertEquals("Trip must remain in TEMPORARY_STOP across multi-day sleep", TripStatus.TEMPORARY_STOP, engine.currentStatus)
        }

        // Day 5: Ignition powers back ON (Intent.ACTION_POWER_CONNECTED)
        engine.onPowerConnected(fiveDaysLater)
        assertFalse(engine.isPowerDisconnected)

        // Vehicle moves after 10 seconds
        val resumeLoc = SyntheticLocation(21.501, 83.0, fiveDaysLater + 10000L, 45f)
        engine.processLocation(resumeLoc)

        assertEquals("Trip must seamlessly resume to ACTIVE_TRIP after 5 days", TripStatus.ACTIVE_TRIP, engine.currentStatus)
        assertEquals("Trip ID must be preserved", 888L, engine.currentTripId)

        // Driving duration must remain exactly ~3600000 ms (0 ms from 5 days)
        assertEquals(
            "Driving duration must not have 5 days added",
            3600000L,
            engine.drivingDurationMs
        )

        // Stationary duration must only have ~10000 ms added, NOT 432,000,000 ms
        val stationaryDiff = engine.stationaryDurationMs - 600000L
        assertTrue(
            "Stationary duration must NOT have 5 days added (diff was $stationaryDiff ms, expected <= 15000 ms)",
            stationaryDiff in 0L..15000L
        )

        // Drive another realistic distance (55 meters at 40 km/h in 5s)
        val nextPoint = SyntheticLocation(21.5015, 83.0, fiveDaysLater + 15000L, 40f)
        engine.processLocation(nextPoint)
        assertTrue("Distance must continue seamlessly past 50 km (was ${engine.totalDistanceMeters})", engine.totalDistanceMeters > 50000.0)
    }

    @Test
    fun testIgnitionPowerCut_WhileAlreadyStationary_PreservesExactStationaryDuration() {
        val engine = TripDetectionEngine(
            onPointReceived = {},
            onTripStarted = {},
            onTripUpdated = { _, _ -> },
            onTripCompleted = {},
            onTripDiscarded = {}
        )
        engine.isGeofenceControlled = false
        engine.isGpsStabilized = true

        val t0 = 1000000L
        engine.restoreActiveTripState(
            tripId = 999L,
            startTime = t0,
            distanceMeters = 10000.0,
            drivingMs = 600000L,
            stationaryMs = 0L,
            lastLocation = SyntheticLocation(21.5, 83.0, t0, 0f)
        )

        // Car stops for 30s with engine running (standstill detected)
        engine.processStationaryWatchdog(t0 + 16000L) // 16s motionless -> enters TEMPORARY_STOP
        assertEquals(TripStatus.TEMPORARY_STOP, engine.currentStatus)

        // Ignition cut at 30s
        val powerCutTime = t0 + 30000L
        engine.onPowerDisconnected(powerCutTime)

        // Stationary duration before power cut should be 30,000 ms
        assertEquals("Stationary duration before power cut must be 30s", 30000L, engine.stationaryDurationMs)

        // 2 hours pass with ignition OFF
        val twoHoursLater = powerCutTime + 7200000L

        // Power connected
        engine.onPowerConnected(twoHoursLater)
        assertEquals("Stationary duration must still be 30s at reconnect", 30000L, engine.stationaryDurationMs)

        // Resumes moving after 5 seconds
        val resumeLoc = SyntheticLocation(21.501, 83.0, twoHoursLater + 5000L, 35f)
        engine.processLocation(resumeLoc)

        // Total stationary duration must be 30s + 5s = 35s, NOT 2 hours + 35s
        assertEquals("Total stationary duration must be 35s", 35000L, engine.stationaryDurationMs)
        assertEquals(TripStatus.ACTIVE_TRIP, engine.currentStatus)
    }
}
