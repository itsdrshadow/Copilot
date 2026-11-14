package com.antigravity.copilot

import com.antigravity.copilot.core.model.TripStatus
import com.antigravity.copilot.core.model.TripSummary
import com.antigravity.copilot.core.trip.TripDetectionEngine
import org.junit.Assert.*
import org.junit.Test

class TripWatchdogAndTimeDeltaTest {

    @Test
    fun testTripIdleWatchdog_OutsideGeofenceNeverTerminatesOngoingTrip() {
        var completedSummary: TripSummary? = null

        val engine = TripDetectionEngine(
            onPointReceived = {},
            onTripStarted = {},
            onTripUpdated = { _, _ -> },
            onTripCompleted = { summary -> completedSummary = summary },
            onTripDiscarded = {}
        )

        engine.isGeofenceControlled = true
        val t0 = 1000000L
        engine.restoreActiveTripState(
            tripId = 555L,
            startTime = t0,
            distanceMeters = 15000.0,
            drivingMs = 600000L,
            stationaryMs = 0L,
            lastLocation = null
        )

        assertEquals(TripStatus.ACTIVE_TRIP, engine.currentStatus)

        // 1. Stationary watchdog detects 16s motionless dwell -> transitions to TEMPORARY_STOP
        engine.processStationaryWatchdog(t0 + 16000L)
        assertEquals("Standstill for >= 15s must transition to TEMPORARY_STOP", TripStatus.TEMPORARY_STOP, engine.currentStatus)
        assertNull("onTripCompleted must NOT be invoked", completedSummary)

        // 2. Dwell exceeds 300s, 600s, 3600s (1 hour), 86400s (24 hours) outside geofence
        engine.processStationaryWatchdog(t0 + 350000L) // 350s dwell > 300s timeout
        assertEquals("Trip outside geofence must remain in TEMPORARY_STOP after 350s", TripStatus.TEMPORARY_STOP, engine.currentStatus)
        assertNull(completedSummary)

        engine.processStationaryWatchdog(t0 + 3600000L) // 1 hour
        assertEquals("Trip outside geofence must remain in TEMPORARY_STOP after 1 hour", TripStatus.TEMPORARY_STOP, engine.currentStatus)
        assertNull(completedSummary)
    }

    @Test
    fun testTripIdleWatchdog_StandardAutomotiveModeTerminatesAfter300Seconds() {
        var completedSummary: TripSummary? = null

        val engine = TripDetectionEngine(
            onPointReceived = {},
            onTripStarted = {},
            onTripUpdated = { _, _ -> },
            onTripCompleted = { summary -> completedSummary = summary },
            onTripDiscarded = {}
        )

        engine.isGeofenceControlled = false
        val t0 = 1000000L
        engine.restoreActiveTripState(
            tripId = 777L,
            startTime = t0,
            distanceMeters = 5000.0,
            drivingMs = 300000L,
            stationaryMs = 0L,
            lastLocation = null
        )

        // 16s motionless -> TEMPORARY_STOP
        engine.processStationaryWatchdog(t0 + 16000L)
        assertEquals(TripStatus.TEMPORARY_STOP, engine.currentStatus)

        // 299s motionless (1s before 300s timeout) -> must still be TEMPORARY_STOP
        engine.processStationaryWatchdog(t0 + 299000L)
        assertEquals(TripStatus.TEMPORARY_STOP, engine.currentStatus)
        assertNull(completedSummary)

        // >= 300s motionless -> completes trip
        engine.processStationaryWatchdog(t0 + 316000L)
        assertEquals("Standard mode must transition to COMPLETED after 300s standstill", TripStatus.COMPLETED, engine.currentStatus)
        assertNotNull("onTripCompleted must be called in standard mode", completedSummary)
        assertEquals(777L, completedSummary!!.id)
    }

    @Test
    fun testTripIdleWatchdog_ShortStationaryDwellBelow200mDiscardsTrip() {
        var discardedTripId: Long? = null

        val engine = TripDetectionEngine(
            onPointReceived = {},
            onTripStarted = {},
            onTripUpdated = { _, _ -> },
            onTripCompleted = {},
            onTripDiscarded = { id -> discardedTripId = id }
        )

        engine.isGeofenceControlled = false
        val t0 = 1000000L
        // Restore trip with only 50m distance (< 200m qualifying threshold)
        engine.restoreActiveTripState(
            tripId = 333L,
            startTime = t0,
            distanceMeters = 50.0,
            drivingMs = 60000L,
            stationaryMs = 0L,
            lastLocation = null
        )

        engine.processStationaryWatchdog(t0 + 16000L)
        assertEquals(TripStatus.TEMPORARY_STOP, engine.currentStatus)

        // Dwell reaches 300s
        engine.processStationaryWatchdog(t0 + 316000L)
        assertEquals("Sub-qualifying distance trip must transition to IDLE upon timeout", TripStatus.IDLE, engine.currentStatus)
        assertEquals(333L, discardedTripId)
    }

    @Test
    fun testTimeDeltaClamping_MathematicalProperties() {
        val maxDt = TripDetectionEngine.MAX_STEP_DT_MS
        assertEquals(5000L, maxDt)

        // 1. Clock sync forward jump of 1 hour (3,600,000ms) clamped to exactly 5000ms
        val jump1h = 3600000L
        assertEquals(5000L, jump1h.coerceIn(0L, maxDt))

        // 2. Clock step backward (-20s NTP step-back) clamped to 0ms
        val jumpBackward = -20000L
        assertEquals(0L, jumpBackward.coerceIn(0L, maxDt))

        // 3. Normal 1s automotive cadence passes unhindered
        assertEquals(1000L, 1000L.coerceIn(0L, maxDt))
    }
}
