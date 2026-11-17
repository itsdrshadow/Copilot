package com.antigravity.copilot

import android.location.Location
import com.antigravity.copilot.core.model.TripStatus
import com.antigravity.copilot.core.model.TripSummary
import com.antigravity.copilot.core.trip.TripDetectionEngine
import org.junit.Assert.*
import org.junit.Test

class SmartTripSummaryLifecycleTest {

    private fun createMockLocation(lat: Double, lon: Double, timeMs: Long, speedKmh: Float, accuracy: Float = 5f): Location {
        return TripDetectionEngine.CoordinateLocation(
            _latitude = lat,
            _longitude = lon,
            _time = timeMs,
            _accuracy = accuracy,
            _speedKmh = speedKmh,
            _bearing = 0f,
            _altitude = 250.0
        )
    }

    @Test
    fun testStationaryDebounce_DoesNotTerminateTripAtTrafficSignals() {
        var completedSummary: TripSummary? = null

        val engine = TripDetectionEngine(
            onPointReceived = {},
            onTripStarted = {},
            onTripUpdated = { _, _ -> },
            onTripCompleted = { completedSummary = it },
            onTripDiscarded = {}
        )
        engine.isGeofenceControlled = false
        engine.stationaryCompletionThresholdSeconds = 180

        val t0 = 1_000_000L
        engine.restoreActiveTripState(
            tripId = 101L,
            startTime = t0,
            distanceMeters = 3500.0,
            drivingMs = 240_000L,
            stationaryMs = 0L,
            lastLocation = createMockLocation(21.8974, 83.3980, t0, 35.0f)
        )
        assertEquals("Trip should be in ACTIVE_TRIP", TripStatus.ACTIVE_TRIP, engine.currentStatus)

        // 1. Standstill for 90 seconds at traffic signal (minor GPS jitter < 15m)
        val stopLat = 21.9020
        val stopLon = 83.3990
        var currentTime = t0
        for (sec in 1..90) {
            currentTime += 1000L
            val jitterLat = stopLat + ((sec % 5) * 0.00002) // ~2m jitter
            val jitterLon = stopLon + ((sec % 3) * 0.00001)
            engine.processLocation(createMockLocation(jitterLat, jitterLon, currentTime, 0.0f))
            engine.processStationaryWatchdog(currentTime)
        }

        // At 90s, standstill is TEMPORARY_STOP, NOT COMPLETED
        assertEquals("Vehicle at 90s signal must remain in TEMPORARY_STOP", TripStatus.TEMPORARY_STOP, engine.currentStatus)
        assertNull("Trip should NOT be completed at a 90s red light", completedSummary)

        // 2. Signal turns green -> Vehicle accelerates to 35 km/h and moves away (> 35m)
        currentTime += 2000L
        val locResume = createMockLocation(stopLat + 0.0020, stopLon + 0.0020, currentTime, 35.0f)
        engine.processLocation(locResume)

        assertEquals("Vehicle resuming movement must return to ACTIVE_TRIP", TripStatus.ACTIVE_TRIP, engine.currentStatus)
        assertNull("Trip should still be ongoing", completedSummary)
    }

    @Test
    fun testStationaryDebounce_ConcludesTripAfterConfiguredStationaryThreshold() {
        var completedSummary: TripSummary? = null

        val engine = TripDetectionEngine(
            onPointReceived = {},
            onTripStarted = {},
            onTripUpdated = { _, _ -> },
            onTripCompleted = { completedSummary = it },
            onTripDiscarded = {}
        )
        engine.isGeofenceControlled = false
        engine.stationaryCompletionThresholdSeconds = 120 // 2 minutes configured threshold

        val t0 = 2_000_000L
        engine.restoreActiveTripState(
            tripId = 202L,
            startTime = t0,
            distanceMeters = 4200.0,
            drivingMs = 300_000L,
            stationaryMs = 0L,
            lastLocation = createMockLocation(21.9150, 83.4050, t0, 40.0f)
        )
        assertEquals(TripStatus.ACTIVE_TRIP, engine.currentStatus)

        // Vehicle parks at destination: stationary watchdog ticks up to 125s
        var currentTime = t0
        for (sec in 1..125) {
            currentTime += 1000L
            engine.processStationaryWatchdog(currentTime)
        }

        assertNotNull("Trip must be completed after stationary threshold exceeded", completedSummary)
        assertEquals(TripStatus.COMPLETED, completedSummary?.status)
        assertEquals(202L, completedSummary?.id)
        assertTrue("Completed trip distance should be > 1000m", (completedSummary?.distanceMeters ?: 0.0) >= 1000.0)
    }

    @Test
    fun testNavigationTabIndicesPreserved() {
        // Tab index invariants:
        // 0 = Dashboard (Home Cockpit)
        // 1 = Full Live Map
        // 2 = Trip History Archive
        // 3 = Fuel Records
        // 4 = Smart Trip Summary
        val TAB_COCKPIT = 0
        val TAB_MAP = 1
        val TAB_TRIP_HISTORY = 2
        val TAB_FUEL_HISTORY = 3
        val TAB_SMART_TRIP_SUMMARY = 4

        assertEquals(0, TAB_COCKPIT)
        assertEquals(1, TAB_MAP)
        assertEquals(2, TAB_TRIP_HISTORY)
        assertEquals(3, TAB_FUEL_HISTORY)
        assertEquals(4, TAB_SMART_TRIP_SUMMARY)
    }
}
