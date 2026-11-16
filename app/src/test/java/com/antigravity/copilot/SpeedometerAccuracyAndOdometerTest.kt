package com.antigravity.copilot

import android.location.Location
import com.antigravity.copilot.core.map.GpsLocationFilter
import com.antigravity.copilot.core.trip.TripDetectionEngine
import org.junit.Assert.*
import org.junit.Test

class SpeedometerAccuracyAndOdometerTest {

    @Test
    fun testCruisingAt60KmhReportsAccurateSpeedWithoutLag() {
        val filter = GpsLocationFilter()
        val t0 = 1000000L

        // Initial point: 0 km/h at (21.900000, 83.400000)
        filter.filter(21.900000, 83.400000, 0f, 0f, 5.0f, t0)

        // Vehicle moves at 60 km/h (16.6667 meters per second) northward
        // 16.6667m / 111139m per deg lat = 0.0001499626 deg lat
        val deltaLat = 16.66667 / 111139.0
        val t1 = t0 + 1000L
        val fix1 = filter.filter(21.900000 + deltaLat, 83.400000, 0f, 60f, 5.0f, t1)

        // Coordinates must snap directly to genuine position without the 0.90 low-pass attenuation
        assertEquals(21.900000 + deltaLat, fix1.latitude, 1e-7)
        assertEquals(83.400000, fix1.longitude, 1e-7)
        assertEquals("Speed must report exact 60 km/h on first moving fix without lagging at ~54 km/h", 60f, fix1.speedKmh, 0.1f)
        assertFalse(fix1.isStationary)
    }

    @Test
    fun testTripEngineSpeedResolutionFusesInstantaneousSpeedWithoutLag() {
        var latestSpeed = 0f
        var totalDist = 0.0

        val engine = TripDetectionEngine(
            onPointReceived = { point -> latestSpeed = point.speedKmh },
            onTripStarted = {},
            onTripUpdated = { summary, _ -> totalDist = summary.distanceMeters },
            onTripCompleted = {},
            onTripDiscarded = {}
        )
        engine.isGeofenceControlled = false

        // Stabilize GPS with 3 seconds of stationary fixes
        val t0 = 1000000L
        engine.processCoordinates(21.900000, 83.400000, accuracy = 5f, speedKmh = 0f, timestamp = t0)
        engine.processCoordinates(21.900000, 83.400000, accuracy = 5f, speedKmh = 0f, timestamp = t0 + 1500L)
        engine.processCoordinates(21.900000, 83.400000, accuracy = 5f, speedKmh = 0f, timestamp = t0 + 3500L)
        assertTrue(engine.isGpsStabilized)

        // Vehicle accelerates to 60 km/h. Sensor reports Doppler 55 km/h while physical delta is 60 km/h
        val deltaLat60 = 16.66667 / 111139.0
        engine.processCoordinates(
            latitude = 21.900000 + deltaLat60,
            longitude = 83.400000,
            accuracy = 5f,
            speedKmh = 55f, // sensor slightly lagging
            timestamp = t0 + 4500L
        )

        // Engine must resolve to 60 km/h rather than lagging at 55 km/h
        assertTrue(
            "Effective speed must immediately reflect real 60 km/h movement instead of lagging at 55 km/h (was $latestSpeed)",
            latestSpeed >= 59.0f
        )
        assertTrue("Total distance should be non-negative", totalDist >= 0.0)
    }

    @Test
    fun testOdometerDistanceAccumulationDoesNotDropTenPercentDistance() {
        val filter = GpsLocationFilter()
        val t0 = 1000000L

        // Initial stationary fix
        filter.filter(21.900000, 83.400000, 0f, 0f, 5.0f, t0)

        // 10 continuous seconds at 60 km/h (166.67 meters total)
        val stepMeters = 60.0 / 3.6 // 16.66667 m/s
        val stepLat = stepMeters / 111139.0
        var currentLat = 21.900000
        var accumulatedDisplacementM = 0.0

        for (sec in 1..10) {
            val nextLat = 21.900000 + (sec * stepLat)
            val filtered = filter.filter(nextLat, 83.400000, 0f, 60f, 5.0f, t0 + sec * 1000L)
            val stepDist = (filtered.latitude - currentLat) * 111139.0
            accumulatedDisplacementM += stepDist
            currentLat = filtered.latitude
        }

        // Expected distance = 166.67 meters
        assertEquals(
            "Total filtered displacement over 10 seconds at 60 km/h must equal 166.67m without attenuation",
            166.67,
            accumulatedDisplacementM,
            1.0
        )
    }
}
