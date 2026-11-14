package com.antigravity.copilot

import android.location.Location
import com.antigravity.copilot.core.model.TripStatus
import com.antigravity.copilot.core.model.TripSummary
import com.antigravity.copilot.core.trip.TripDetectionEngine
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class RebootFirstFixDistanceTest {

    // Test-double subclassing android.location.Location to provide realistic geodesic calculations
    private class TestLocation(
        private val lat: Double,
        private val lon: Double,
        private val timeMs: Long,
        private val acc: Float = 5.0f,
        private val spd: Float = 0.0f
    ) : Location("gps") {
        override fun getLatitude(): Double = lat
        override fun getLongitude(): Double = lon
        override fun getTime(): Long = timeMs
        override fun getAccuracy(): Float = acc
        override fun hasAccuracy(): Boolean = true
        override fun getSpeed(): Float = spd
        override fun hasSpeed(): Boolean = spd > 0.0f
        override fun hasAltitude(): Boolean = false
        override fun hasBearing(): Boolean = false

        override fun distanceTo(dest: Location): Float {
            val dLat = Math.toRadians(dest.latitude - lat)
            val dLon = Math.toRadians(dest.longitude - lon)
            val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat)) * cos(Math.toRadians(dest.latitude)) * sin(dLon / 2).pow(2)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            return (6371000.0 * c).toFloat()
        }
    }

    @Test
    fun testRebootMovementAnchorAccumulation_DirectEngineBridge() {
        var liveMovementBridgeDist = 0.0
        var latestSummary: TripSummary? = null

        val engine = TripDetectionEngine(
            onPointReceived = {},
            onTripStarted = {},
            onTripUpdated = { summary, _ -> latestSummary = summary },
            onTripCompleted = {},
            onTripDiscarded = {}
        )
        engine.onLiveMovement = { dist, _, _ -> liveMovementBridgeDist = dist }

        // 1. Pre-reboot anchor: Vehicle was at (21.900000, 83.400000) with 5200m accumulated
        val preRebootLat = 21.900000
        val preRebootLon = 83.400000
        val preRebootTime = 100000L
        val preRebootDistance = 5200.0
        val preRebootLocation = TestLocation(preRebootLat, preRebootLon, preRebootTime, acc = 8.0f)

        // 2. Restore active trip state
        engine.restoreActiveTripState(
            tripId = 999L,
            startTime = 50000L,
            distanceMeters = preRebootDistance,
            drivingMs = 600000L,
            stationaryMs = 0L,
            lastLocation = preRebootLocation
        )
        assertEquals(TripStatus.ACTIVE_TRIP, engine.currentStatus)
        assertEquals(999L, engine.currentTripId)
        assertFalse("GPS must not be stabilized immediately after reboot", engine.isGpsStabilized)

        // 3. Post-reboot fixes arrive at (21.902700, 83.400000) (~300m away)
        val postRebootLat = 21.902700
        val postRebootLon = 83.400000
        val fix1Time = 120000L

        // Fix 1 initiates 3-second warm-up window
        val fix1 = TestLocation(postRebootLat, postRebootLon, fix1Time, acc = 10.0f)
        engine.processLocation(fix1)
        assertFalse(engine.isGpsStabilized)

        // Fix 2 at 1.5s warm-up
        val fix2 = TestLocation(postRebootLat, postRebootLon, fix1Time + 1500L, acc = 10.0f)
        engine.processLocation(fix2)
        assertFalse(engine.isGpsStabilized)

        // Fix 3 at 3.2s -> Solid GPS lock confirmed (> 3000ms), triggers reboot anchor bridging!
        val fix3 = TestLocation(postRebootLat, postRebootLon, fix1Time + 3200L, acc = 10.0f)
        engine.processLocation(fix3)

        // 4. Verify engine stabilization and genuine distance accumulation
        assertTrue("GPS must be stabilized after 3s accurate fixes", engine.isGpsStabilized)
        assertTrue("Bridge distance (~300m) must be dispatched to onLiveMovement", liveMovementBridgeDist in 280.0..320.0)

        // Subsequent location fix to produce updated summary
        val moveFix = TestLocation(postRebootLat + 0.0002, postRebootLon, fix1Time + 4500L, acc = 5.0f, spd = 10.0f)
        engine.processLocation(moveFix)

        assertNotNull(latestSummary)
        val totalDist = latestSummary!!.distanceMeters
        assertTrue("Restored total distance must include pre-reboot (5200m) + bridge (~300m)", totalDist in 5480.0..5550.0)
    }

    @Test
    fun testRebootBridgeDistanceClamp_RejectsCrossRegionSpikeOver50Km() {
        var liveMovementBridgeDist = 0.0
        val engine = TripDetectionEngine(
            onPointReceived = {},
            onTripStarted = {},
            onTripUpdated = { _, _ -> },
            onTripCompleted = {},
            onTripDiscarded = {}
        )
        engine.onLiveMovement = { dist, _, _ -> liveMovementBridgeDist = dist }

        // Anchor at (21.900000, 83.400000)
        val preRebootLocation = TestLocation(21.900000, 83.400000, 100000L, acc = 8.0f)
        engine.restoreActiveTripState(
            tripId = 888L,
            startTime = 50000L,
            distanceMeters = 5200.0,
            drivingMs = 300000L,
            stationaryMs = 0L,
            lastLocation = preRebootLocation
        )

        // Post-reboot first fix is 66 km away at (22.500000, 83.400000) (violating 50 km clamp)
        val postRebootTime = 120000L
        val farFix1 = TestLocation(22.500000, 83.400000, postRebootTime, acc = 10.0f)
        engine.processLocation(farFix1)

        val farFix2 = TestLocation(22.500000, 83.400000, postRebootTime + 3500L, acc = 10.0f)
        engine.processLocation(farFix2)

        assertTrue(engine.isGpsStabilized)
        assertEquals("Bridge distance >= 50km must be rejected by clamp", 0.0, liveMovementBridgeDist, 1e-6)
    }

    @Test
    fun testRebootBridgeJitterClamp_RejectsUnder4Meters() {
        var liveMovementBridgeDist = 0.0
        val engine = TripDetectionEngine(
            onPointReceived = {},
            onTripStarted = {},
            onTripUpdated = { _, _ -> },
            onTripCompleted = {},
            onTripDiscarded = {}
        )
        engine.onLiveMovement = { dist, _, _ -> liveMovementBridgeDist = dist }

        // Pre-reboot location
        val preLoc = TestLocation(21.900000, 83.400000, 100000L, acc = 5.0f)
        engine.restoreActiveTripState(
            tripId = 777L,
            startTime = 50000L,
            distanceMeters = 5200.0,
            drivingMs = 300000L,
            stationaryMs = 0L,
            lastLocation = preLoc
        )

        // Post-reboot location is only 1.5m away (stationary jitter)
        val jitterLat = 21.900010 // ~1.1m
        val fix1 = TestLocation(jitterLat, 83.400000, 120000L, acc = 5.0f)
        engine.processLocation(fix1)
        val fix2 = TestLocation(jitterLat, 83.400000, 123500L, acc = 5.0f)
        engine.processLocation(fix2)

        assertTrue(engine.isGpsStabilized)
        assertEquals("Bridge distance < 4m must be rejected as jitter", 0.0, liveMovementBridgeDist, 1e-6)
    }

    @Test
    fun testRebootBridgeExcessiveSpeed_RejectsImpliedOver220Kmh() {
        var liveMovementBridgeDist = 0.0
        val engine = TripDetectionEngine(
            onPointReceived = {},
            onTripStarted = {},
            onTripUpdated = { _, _ -> },
            onTripCompleted = {},
            onTripDiscarded = {}
        )
        engine.onLiveMovement = { dist, _, _ -> liveMovementBridgeDist = dist }

        // Anchor at t = 100000L
        val preLoc = TestLocation(21.900000, 83.400000, 100000L, acc = 5.0f)
        engine.restoreActiveTripState(
            tripId = 666L,
            startTime = 50000L,
            distanceMeters = 5200.0,
            drivingMs = 300000L,
            stationaryMs = 0L,
            lastLocation = preLoc
        )

        // Post-reboot fix is 5 km away only 5 seconds later (implied speed = 3600 km/h)
        val fastLat = 21.900000 + (5000.0 / 111139.0)
        val fix1 = TestLocation(fastLat, 83.400000, 105000L, acc = 5.0f)
        engine.processLocation(fix1)
        val fix2 = TestLocation(fastLat, 83.400000, 108500L, acc = 5.0f)
        engine.processLocation(fix2)

        assertTrue(engine.isGpsStabilized)
        assertEquals("Implied speed >= 220 km/h must reject bridge", 0.0, liveMovementBridgeDist, 1e-6)
    }
}
