package com.antigravity.drivingreport

import com.antigravity.drivingreport.core.model.TripStatus
import com.antigravity.drivingreport.core.trip.TripDetectionEngine
import com.antigravity.drivingreport.feature.dashboard.calculateSpeedometerDecay
import org.junit.Assert.*
import org.junit.Test

class TunnelGeodesicBridgingTest {

    // =========================================================================
    // PART 1: TripDetectionEngine Geodesic Tunnel Outage Bridging
    // =========================================================================

    @Test
    fun testTripDetectionEngine_TunnelOutageGeodesicBridging_StandardTransit() {
        var liveMovementDistance = 0.0
        var liveMovementDurationMs = 0L

        val engine = TripDetectionEngine(
            onPointReceived = {},
            onTripStarted = {},
            onTripUpdated = { _, _ -> },
            onTripCompleted = {},
            onTripDiscarded = {}
        )
        engine.onLiveMovement = { dist, _, durMs ->
            liveMovementDistance += dist
            liveMovementDurationMs += durMs
        }

        val t0 = 1000000L
        engine.restoreActiveTripState(
            tripId = 42L,
            startTime = t0 - 600000L,
            distanceMeters = 10000.0,
            drivingMs = 600000L,
            stationaryMs = 0L,
            lastLocation = null
        )
        assertEquals(TripStatus.ACTIVE_TRIP, engine.currentStatus)

        // Vehicle enters tunnel at (21.900000, 83.400000)
        val entryLat = 21.900000
        val entryLon = 83.400000
        engine.processCoordinates(entryLat, entryLon, accuracy = 5.0f, speedKmh = 72.0f, timestamp = t0)
        engine.processCoordinates(entryLat, entryLon, accuracy = 5.0f, speedKmh = 72.0f, timestamp = t0 + 1000L)
        engine.processCoordinates(entryLat, entryLon, accuracy = 5.0f, speedKmh = 72.0f, timestamp = t0 + 2000L)
        engine.processCoordinates(entryLat, entryLon, accuracy = 5.0f, speedKmh = 72.0f, timestamp = t0 + 3100L)

        // 45s tunnel transit: GPS lost from t0 + 3100L until t0 + 48100L (> 15s threshold)
        val outageDurationMs = 45000L
        val exitTimeMs = t0 + 3100L + outageDurationMs
        // Vehicle travels 900m North at 72 km/h (20 m/s)
        val exitLat = entryLat + (900.0 / 111139.0)
        val exitLon = entryLon

        // Emerging from tunnel: GPS lock reacquired with 12m accuracy (<= 25m threshold)
        engine.processCoordinates(exitLat, exitLon, accuracy = 12.0f, speedKmh = 72.0f, timestamp = exitTimeMs)
        engine.processCoordinates(exitLat, exitLon, accuracy = 12.0f, speedKmh = 72.0f, timestamp = exitTimeMs + 1000L)
        engine.processCoordinates(exitLat, exitLon, accuracy = 12.0f, speedKmh = 72.0f, timestamp = exitTimeMs + 2000L)
        engine.processCoordinates(exitLat, exitLon, accuracy = 12.0f, speedKmh = 72.0f, timestamp = exitTimeMs + 3100L)

        // Geodesic bridge must accumulate ~900m into liveMovementDistance
        assertTrue("Live movement listener received bridged distance (actual: $liveMovementDistance)", liveMovementDistance in 890.0..910.0)
        assertTrue("Driving duration must accumulate at least 45s outage (actual: $liveMovementDurationMs)", liveMovementDurationMs >= 45000L)
    }

    @Test
    fun testTripDetectionEngine_TunnelOutagePlausibilityGates() {
        val entryLat = 21.900000
        val entryLon = 83.400000
        val t0 = 2000000L

        fun runOutageScenario(exitLat: Double, exitLon: Double, outageSec: Long, exitAccuracy: Float): Double {
            var bridgedDistance = 0.0
            val engine = TripDetectionEngine(
                onPointReceived = {},
                onTripStarted = {},
                onTripUpdated = { _, _ -> },
                onTripCompleted = {},
                onTripDiscarded = {}
            )
            engine.onLiveMovement = { dist, _, _ ->
                bridgedDistance += dist
            }
            engine.restoreActiveTripState(tripId = 99L, startTime = t0 - 10000L, distanceMeters = 0.0, drivingMs = 10000L, stationaryMs = 0L, lastLocation = null)
            engine.processCoordinates(entryLat, entryLon, accuracy = 5.0f, speedKmh = 60.0f, timestamp = t0)
            engine.processCoordinates(entryLat, entryLon, accuracy = 5.0f, speedKmh = 60.0f, timestamp = t0 + 1000L)
            engine.processCoordinates(entryLat, entryLon, accuracy = 5.0f, speedKmh = 60.0f, timestamp = t0 + 2000L)
            engine.processCoordinates(entryLat, entryLon, accuracy = 5.0f, speedKmh = 60.0f, timestamp = t0 + 3100L)

            val exitTime = t0 + 3100L + (outageSec * 1000L)
            engine.processCoordinates(exitLat, exitLon, accuracy = exitAccuracy, speedKmh = 60.0f, timestamp = exitTime)
            engine.processCoordinates(exitLat, exitLon, accuracy = exitAccuracy, speedKmh = 60.0f, timestamp = exitTime + 1000L)
            engine.processCoordinates(exitLat, exitLon, accuracy = exitAccuracy, speedKmh = 60.0f, timestamp = exitTime + 2000L)
            engine.processCoordinates(exitLat, exitLon, accuracy = exitAccuracy, speedKmh = 60.0f, timestamp = exitTime + 3100L)
            return bridgedDistance
        }

        // Gate 1: Degraded exit accuracy (35m > 25m threshold) -> Bridge rejected
        val exitLatNormal = entryLat + (900.0 / 111139.0)
        val degradedDist = runOutageScenario(exitLatNormal, entryLon, outageSec = 45L, exitAccuracy = 35.0f)
        assertEquals("Degraded accuracy (>25m) must reject geodesic bridge", 0.0, degradedDist, 0.01)

        // Gate 2: Vehicle stopped in tunnel (moved only 4m < 10m threshold) -> Bridge rejected
        val standstillLat = entryLat + (4.0 / 111139.0)
        val standstillDist = runOutageScenario(standstillLat, entryLon, outageSec = 60L, exitAccuracy = 10.0f)
        assertEquals("Standstill inside tunnel (<10m) must reject bridge", 0.0, standstillDist, 0.01)

        // Gate 3: Multipath teleport jump (50 km in 30s = 6000 km/h > 220 km/h) -> Bridge rejected
        val teleportLat = entryLat + (50000.0 / 111139.0)
        val teleportDist = runOutageScenario(teleportLat, entryLon, outageSec = 30L, exitAccuracy = 10.0f)
        assertEquals("Teleport jump (>220 km/h) must reject bridge", 0.0, teleportDist, 0.01)
    }

    // =========================================================================
    // PART 2: SpeedometerCluster calculateSpeedometerDecay Production Method
    // =========================================================================

    @Test
    fun testSpeedometerDecayCurve_ProductionModel() {
        val initialSpeed = 80.0f

        // Stage 1: Under 2s maintains 100% speed
        assertEquals(80.0f, calculateSpeedometerDecay(initialSpeed, 0L), 0.01f)
        assertEquals(80.0f, calculateSpeedometerDecay(initialSpeed, 1000L), 0.01f)
        assertEquals(80.0f, calculateSpeedometerDecay(initialSpeed, 2000L), 0.01f)

        // Stage 2: Aerodynamic coasting (-2.0 km/h per second)
        // At 3.5s (1.5s into coasting): 80 - (1.5 * 2) = 77.0 km/h
        assertEquals(77.0f, calculateSpeedometerDecay(initialSpeed, 3500L), 0.01f)
        // At 5.0s (3.0s into coasting): 80 - (3.0 * 2) = 74.0 km/h
        assertEquals(74.0f, calculateSpeedometerDecay(initialSpeed, 5000L), 0.01f)

        // Stage 3 Continuity: 5000ms to 5001ms boundary gap must be <= 0.01 km/h
        val speed5000 = calculateSpeedometerDecay(initialSpeed, 5000L)
        val speed5001 = calculateSpeedometerDecay(initialSpeed, 5001L)
        val delta5000 = Math.abs(speed5000 - speed5001)
        assertTrue("Boundary gap at 5000ms must be <= 0.01 km/h (actual: $delta5000)", delta5000 <= 0.01f)

        // Stage 3 Termination: reaches exactly 0 at 10s
        assertEquals(0.0f, calculateSpeedometerDecay(initialSpeed, 10000L), 0.01f)

        // Stage 4: Beyond 10s remains strictly 0
        assertEquals(0.0f, calculateSpeedometerDecay(initialSpeed, 10001L), 0.01f)
        assertEquals(0.0f, calculateSpeedometerDecay(initialSpeed, 15000L), 0.01f)
        assertEquals(0.0f, calculateSpeedometerDecay(initialSpeed, 60000L), 0.01f)
    }

    @Test
    fun testSpeedometerDecayCurve_StrictMonotonicityAcross15Seconds() {
        val initialSpeed = 120.0f
        var prevSpeed = initialSpeed

        for (stepMs in 0L..15000L step 50L) {
            val speed = calculateSpeedometerDecay(initialSpeed, stepMs)
            assertTrue("Speed must be non-negative at step $stepMs", speed >= 0f)
            assertTrue("Decay must be monotonic non-increasing at step $stepMs", speed <= prevSpeed + 0.001f)
            prevSpeed = speed
        }
    }

    @Test
    fun testSpeedometerDecayCurve_LowSpeedBoundaryCoercion() {
        val initialSpeed = 4.0f
        // 4.0 km/h decays at -2.0 km/h/s from 2000ms -> hits 0 at 4000ms
        assertEquals(0.0f, calculateSpeedometerDecay(initialSpeed, 4000L), 0.01f)
        assertEquals(0.0f, calculateSpeedometerDecay(initialSpeed, 4500L), 0.01f)
        assertEquals(0.0f, calculateSpeedometerDecay(initialSpeed, 7000L), 0.01f)
        assertEquals(0.0f, calculateSpeedometerDecay(initialSpeed, 12000L), 0.01f)

        // Zero and negative speed guards
        assertEquals(0.0f, calculateSpeedometerDecay(0.0f, 1000L), 0.01f)
        assertEquals(0.0f, calculateSpeedometerDecay(-10.0f, 1000L), 0.01f)
    }
}
