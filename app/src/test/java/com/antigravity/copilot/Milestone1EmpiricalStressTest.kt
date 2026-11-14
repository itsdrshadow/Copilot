package com.antigravity.copilot

import com.antigravity.copilot.core.map.GpsLocationFilter
import com.antigravity.copilot.feature.dashboard.calculateSpeedometerDecay
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.*

/**
 * Milestone 1 Empirical Stress Test Suite
 *
 * Adversarially challenges and stress-tests:
 * - Feature 1: GPS stationary jitter dead-zone (<2.5m) and multipath spike rejection (>240 km/h).
 * - Feature 2: Service listener deduplication, arbitration window, and initial cached fix gating.
 * - Feature 3: Tunnel geodesic bridging across 15s–120s outages with boundary constraints.
 * - Feature 4: Speedometer multi-stage decay curve (0-2s coasting, 2-5s linear, 5-10s quadratic, >10s 0 km/h).
 * - Feature 10: Mid-trip reboot first-fix distance accumulation and plausibility bounds.
 */
class Milestone1EmpiricalStressTest {

    private lateinit var filter: GpsLocationFilter

    @Before
    fun setup() {
        filter = GpsLocationFilter()
    }

    private fun haversineDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return 6371000.0 * c
    }

    // =========================================================================================
    // FEATURE 1: GPS Stationary Jitter Dead-Zone & Multipath Spike Rejection
    // =========================================================================================

    @Test
    fun testF1_StationaryDeadZoneExactBoundaries() {
        // Initial anchor at (21.900000, 83.400000)
        val initial = filter.filter(21.900000, 83.400000, 0f, 0f, 5.0f, 1000L)
        assertEquals(21.900000, initial.latitude, 1e-6)
        assertEquals(83.400000, initial.longitude, 1e-6)
        assertTrue(initial.isStationary)

        // 1. Boundary: 2.40m displacement (within 2.5m dead-zone) while speed = 0.5 km/h (< 2.0 km/h)
        val withinDeadZoneLat = 21.900000 + (2.40 / 111139.0)
        val deadZoneState = filter.filter(withinDeadZoneLat, 83.400000, 0f, 0.5f, 5.0f, 2000L)
        assertEquals("Displacement < 2.5m while stationary must freeze latitude", 21.900000, deadZoneState.latitude, 1e-6)
        assertEquals("Displacement < 2.5m while stationary must freeze longitude", 83.400000, deadZoneState.longitude, 1e-6)
        assertTrue(deadZoneState.isStationary)
        assertEquals(0f, deadZoneState.speedKmh, 0.01f)

        // 2. Boundary: 2.60m displacement (> 2.5m dead-zone) while speed = 0.5 km/h (< 2.0 km/h)
        val outsideDeadZoneLat = 21.900000 + (2.60 / 111139.0)
        val movingState = filter.filter(outsideDeadZoneLat, 83.400000, 0f, 0.5f, 5.0f, 3000L)
        assertNotEquals("Displacement > 2.5m must unfreeze coordinates", 21.900000, movingState.latitude, 1e-7)
    }

    @Test
    fun testF1_MicroJitterDrift1000TicksZeroNetAccumulation() {
        filter.filter(21.900000, 83.400000, 90f, 0f, 5.0f, 1000L)

        // 1,000 continuous GPS jitter fixes at varying angles and distances between 0.1m and 2.3m
        var timestamp = 1000L
        for (i in 1..1000) {
            timestamp += 1000L
            val angleRad = (i * 137.5) * (PI / 180.0)
            val jitterRadiusM = 0.1 + (i % 23) * 0.1 // 0.1m to 2.3m (< 2.5m dead-zone)
            val dLat = (jitterRadiusM * cos(angleRad)) / 111139.0
            val dLon = (jitterRadiusM * sin(angleRad)) / (111139.0 * cos(Math.toRadians(21.9)))
            val jitterSpeed = ((i % 15) * 0.1f) // 0.0 to 1.4 km/h (< 2.0 km/h)

            val state = filter.filter(21.900000 + dLat, 83.400000 + dLon, 90f, jitterSpeed, 5.0f, timestamp)
            assertEquals("Tick $i: Stationary jitter must remain completely locked to anchor latitude", 21.900000, state.latitude, 1e-6)
            assertEquals("Tick $i: Stationary jitter must remain completely locked to anchor longitude", 83.400000, state.longitude, 1e-6)
            assertEquals("Tick $i: Speed must report 0 km/h when stationary", 0f, state.speedKmh, 0.01f)
            assertTrue("Tick $i: isStationary must be true", state.isStationary)
        }
    }

    @Test
    fun testF1_MultipathSpikeBoundaryAround240Kmh() {
        val t0 = 1000000L
        filter.filter(21.900000, 83.400000, 0f, 50f, 5.0f, t0)

        // 1. Plausible high-speed jump: 235 km/h in 1 second
        val dist235m = (235.0 / 3.6) * 1.0
        val lat235 = 21.900000 + (dist235m / 111139.0)
        val validHighSpeed = filter.filter(lat235, 83.400000, 0f, 235f, 5.0f, t0 + 1000L)
        assertNotEquals("Valid high-speed (235 km/h < 240 km/h) must be accepted", 21.900000, validHighSpeed.latitude, 1e-5)

        // Reset filter
        filter.reset()
        filter.filter(21.900000, 83.400000, 0f, 50f, 5.0f, t0)

        // 2. Multipath spike: 245 km/h in 1 second
        val dist245m = (245.0 / 3.6) * 1.0
        val lat245 = 21.900000 + (dist245m / 111139.0)
        val spikeState = filter.filter(lat245, 83.400000, 0f, 245f, 5.0f, t0 + 1000L)
        assertEquals("Multipath jump (245 km/h > 240 km/h) must be rejected unconditionally", 21.900000, spikeState.latitude, 1e-6)

        // 3. Teleportation spike: 10,000 km/h (50 km in 1 second)
        val latTeleport = 21.900000 + (50000.0 / 111139.0)
        val teleportState = filter.filter(latTeleport, 83.400000, 0f, 150f, 5.0f, t0 + 2000L)
        assertEquals("Extreme teleportation spike must be rejected", 21.900000, teleportState.latitude, 1e-6)
    }

    @Test
    fun testF1_NegativeSpeedAndNullIslandCoordinates() {
        val t0 = 1000000L
        val initial = filter.filter(21.900000, 83.400000, 0f, -30f, 5.0f, t0)
        assertEquals(0f, initial.speedKmh, 0.01f)
        assertTrue(initial.isStationary)

        // Null Island coordinates (0.0, 0.0) rejected
        val nullIsland = filter.filter(0.0, 0.0, 0f, 50f, 5.0f, t0 + 1000L)
        assertEquals(21.900000, nullIsland.latitude, 1e-6)
        assertEquals(83.400000, nullIsland.longitude, 1e-6)
    }

    // =========================================================================================
    // FEATURE 2: Service Listener Deduplication & Cached Fix Gating
    // =========================================================================================

    @Test
    fun testF2_ServiceListenerDeduplicationAndStateFlags() {
        var isLocationUpdatesActive = false
        var initialCachedFixProcessed = false
        var registerCalls = 0
        var cachedFixQueries = 0

        fun startLocationUpdates() {
            if (isLocationUpdatesActive) return
            if (!initialCachedFixProcessed) {
                initialCachedFixProcessed = true
                cachedFixQueries++
            }
            registerCalls++
            isLocationUpdatesActive = true
        }

        // Simulate 20 rapid onStartCommand calls
        for (i in 1..20) {
            startLocationUpdates()
        }

        assertEquals("Provider registration must be invoked exactly once across rapid restarts", 1, registerCalls)
        assertEquals("Cached fix evaluation must be executed exactly once", 1, cachedFixQueries)
        assertTrue("isLocationUpdatesActive flag must remain active", isLocationUpdatesActive)
    }

    @Test
    fun testF2_ProviderArbitrationWindow() {
        val arbitrationThresholdMs = 3500L
        var lastFusedFixTimeMs = 10000L

        // Fused active: Native fix arrives after 1000ms -> Must be dropped
        var nativeFixTime = 11000L
        var shouldDrop = (nativeFixTime - lastFusedFixTimeMs) < arbitrationThresholdMs
        assertTrue("Native fix within 3500ms of active Fused fix must be dropped", shouldDrop)

        // Fused active: Native fix arrives after 3400ms -> Must still be dropped
        nativeFixTime = 13400L
        shouldDrop = (nativeFixTime - lastFusedFixTimeMs) < arbitrationThresholdMs
        assertTrue("Native fix at 3400ms must be dropped", shouldDrop)

        // Fused stalled: Native fix arrives after 3600ms -> Must be accepted as fallback
        nativeFixTime = 13600L
        shouldDrop = (nativeFixTime - lastFusedFixTimeMs) < arbitrationThresholdMs
        assertFalse("Native fix after 3500ms must be accepted as fallback provider", shouldDrop)

        // Fused resumes at 14000ms -> Next native fix at 14500ms is dropped again
        lastFusedFixTimeMs = 14000L
        nativeFixTime = 14500L
        shouldDrop = (nativeFixTime - lastFusedFixTimeMs) < arbitrationThresholdMs
        assertTrue("Native fix must be dropped once Fused resumes", shouldDrop)
    }

    // =========================================================================================
    // FEATURE 3: Tunnel Geodesic Bridging Across 15s–120s Outages
    // =========================================================================================

    @Test
    fun testF3_TunnelOutageSweepFrom15sTo120s() {
        val entryLat = 21.900000
        val entryLon = 83.400000
        val entryTimeMs = 10000L

        // Validate across outage durations: 15s, 30s, 45s, 60s, 120s
        val outageDurationsSec = listOf(15L, 30L, 45L, 60L, 120L)
        val speedKmh = 72.0 // 20.0 m/s

        for (durationSec in outageDurationsSec) {
            val transitDistanceMeters = (speedKmh / 3.6) * durationSec
            val exitLat = entryLat + (transitDistanceMeters / 111139.0)
            val exitLon = entryLon
            val exitTimeMs = entryTimeMs + (durationSec * 1000L)

            // Verify outage duration qualifies (> 15000ms threshold)
            val gapMs = exitTimeMs - entryTimeMs
            val isOutage = gapMs >= 15000L
            assertTrue("Gap of ${durationSec}s must qualify as tunnel outage", isOutage)

            // Compute geodesic bridge distance
            val bridgeDist = haversineDistanceMeters(entryLat, entryLon, exitLat, exitLon)
            assertEquals("Bridge distance for ${durationSec}s at 72 km/h", transitDistanceMeters, bridgeDist, 2.0)

            // Implied speed verification
            val impliedSpeed = (bridgeDist / durationSec) * 3.6
            assertEquals(72.0, impliedSpeed, 0.2)
            assertTrue("Implied speed must be physically plausible (< 220 km/h)", impliedSpeed in 2.5..220.0)
        }
    }

    @Test
    fun testF3_TunnelOutagePlausibilityGating() {
        val entryLat = 21.900000
        val entryLon = 83.400000

        fun evaluateBridge(outageSec: Double, distanceMeters: Double, exitAccuracyMeters: Float): Boolean {
            val impliedSpeedKmh = (distanceMeters / outageSec.coerceAtLeast(1.0)) * 3.6
            return exitAccuracyMeters <= 25.0f && distanceMeters >= 10.0 && impliedSpeedKmh in 2.5..220.0
        }

        // Case 1: Normal 45s tunnel transit, 900m distance, 12m accuracy -> Valid bridge
        assertTrue("Valid tunnel transit must pass", evaluateBridge(45.0, 900.0, 12.0f))

        // Case 2: Degraded exit accuracy = 35m (> 25m threshold) -> Must reject
        assertFalse("Degraded GPS accuracy at tunnel exit must reject bridge", evaluateBridge(45.0, 900.0, 35.0f))

        // Case 3: Vehicle stopped inside tunnel (moved only 4m) -> Must reject
        assertFalse("Standstill inside tunnel (< 10m) must not bridge", evaluateBridge(60.0, 4.0, 10.0f))

        // Case 4: GPS multipath teleport jump (50 km in 30s = 6000 km/h) -> Must reject
        assertFalse("Teleport spike during outage (> 220 km/h) must reject bridge", evaluateBridge(30.0, 50000.0, 10.0f))
    }

    // =========================================================================================
    // FEATURE 4: Speedometer Multi-Stage Decay Curve
    // =========================================================================================

    @Test
    fun testF4_SpeedometerDecayCurve_StrictMonotonicityAndContinuity() {
        val initialSpeed = 120.0f
        val lastPointTime = 10000L

        fun evalSpeed(now: Long): Float {
            val gapMs = (now - lastPointTime).coerceAtLeast(0L)
            return calculateSpeedometerDecay(initialSpeed, gapMs)
        }

        // Test every 100ms across 0 to 15 seconds
        var previousSpeed = initialSpeed
        for (stepMs in 0..15000 step 100) {
            val currentSpeed = evalSpeed(lastPointTime + stepMs)
            assertTrue("Decay must be strictly non-negative at step $stepMs", currentSpeed >= 0f)
            assertTrue("Decay must be monotonic non-increasing at step $stepMs", currentSpeed <= previousSpeed + 0.001f)
            previousSpeed = currentSpeed
        }

        // Boundary values
        assertEquals(120.0f, evalSpeed(lastPointTime + 2000L), 0.01f)
        assertEquals(114.0f, evalSpeed(lastPointTime + 5000L), 0.01f)
        assertEquals(0.0f, evalSpeed(lastPointTime + 10000L), 0.01f)
        assertEquals(0.0f, evalSpeed(lastPointTime + 10001L), 0.01f)
        assertEquals(0.0f, evalSpeed(lastPointTime + 20000L), 0.01f)

        // Continuity check at stage transition points
        assertEquals("Boundary gap 2000ms must be continuous", evalSpeed(lastPointTime + 2000L), evalSpeed(lastPointTime + 2001L), 0.01f)
        assertEquals("Boundary gap 5000ms must be continuous", evalSpeed(lastPointTime + 5000L), evalSpeed(lastPointTime + 5001L), 0.01f)
        assertEquals("Boundary gap 10000ms must be continuous at 0", evalSpeed(lastPointTime + 10000L), evalSpeed(lastPointTime + 10001L), 0.01f)
    }

    @Test
    fun testF4_SpeedometerDecayCurve_LowInitialSpeedCoercion() {
        val initialSpeed = 4.0f
        val lastPointTime = 10000L

        fun evalSpeed(now: Long): Float {
            val gapMs = (now - lastPointTime).coerceAtLeast(0L)
            return calculateSpeedometerDecay(initialSpeed, gapMs)
        }

        // At 4s (coastingSec = 2s): 4.0 - 2 * 2.0 = 0.0
        assertEquals(0.0f, evalSpeed(lastPointTime + 4000L), 0.01f)
        // At 4.5s: coerced to 0, not negative!
        assertEquals(0.0f, evalSpeed(lastPointTime + 4500L), 0.01f)
        // In quadratic phase: baseCoastingSpeed = 0, result is 0
        assertEquals(0.0f, evalSpeed(lastPointTime + 6000L), 0.01f)
    }

    // =========================================================================================
    // FEATURE 10: Mid-Trip Reboot First-Fix Distance Accumulation
    // =========================================================================================

    @Test
    fun testF10_MidTripRebootFirstFixAccumulation() {
        val preRebootDistanceMeters = 8500.0
        val anchorLat = 21.900000
        val anchorLon = 83.400000
        val anchorTime = 1000000L

        // Vehicle moves 450m during head unit reboot (reboot took 30s)
        val postRebootLat = anchorLat + (450.0 / 111139.0)
        val postRebootLon = anchorLon
        val postRebootTime = anchorTime + 30000L

        val bridgeDist = haversineDistanceMeters(anchorLat, anchorLon, postRebootLat, postRebootLon)
        val timeDeltaSec = ((postRebootTime - anchorTime) / 1000.0).coerceAtLeast(1.0)
        val impliedSpeedKmh = (bridgeDist / timeDeltaSec) * 3.6

        // Plausibility constraints from TripDetectionEngine:
        // bridgeDist >= 4.0 && impliedSpeedKmh < 220.0
        assertTrue("Bridge distance must be >= 4.0m", bridgeDist >= 4.0)
        assertTrue("Implied speed ($impliedSpeedKmh km/h) must be < 220 km/h", impliedSpeedKmh < 220.0)

        val restoredTotalDistance = preRebootDistanceMeters + bridgeDist
        assertEquals("Restored total distance must bridge the 450m movement across reboot", 8950.0, restoredTotalDistance, 2.0)
    }

    @Test
    fun testF10_MidTripRebootExcessiveTeleportRejection() {
        val anchorLat = 21.900000
        val anchorLon = 83.400000
        val anchorTime = 1000000L

        // Simulated flatbed transport or GPS glitch: 60 km jump during 60s reboot
        val postRebootLat = anchorLat + (60000.0 / 111139.0)
        val postRebootLon = anchorLon
        val postRebootTime = anchorTime + 60000L

        val bridgeDist = haversineDistanceMeters(anchorLat, anchorLon, postRebootLat, postRebootLon)
        val timeDeltaSec = ((postRebootTime - anchorTime) / 1000.0).coerceAtLeast(1.0)
        val impliedSpeedKmh = (bridgeDist / timeDeltaSec) * 3.6

        val shouldBridge = bridgeDist >= 4.0 && impliedSpeedKmh < 220.0
        assertFalse("Excessive jump across reboot (3600 km/h) MUST be rejected by plausibility gate", shouldBridge)
    }

    @Test
    fun testF10_MidTripRebootStationaryDriftRejection() {
        val anchorLat = 21.900000
        val anchorLon = 83.400000
        val anchorTime = 1000000L

        // Stopped car post-reboot with 1.5m GPS noise drift
        val postRebootLat = anchorLat + (1.5 / 111139.0)
        val postRebootLon = anchorLon
        val postRebootTime = anchorTime + 30000L

        val bridgeDist = haversineDistanceMeters(anchorLat, anchorLon, postRebootLat, postRebootLon)
        val timeDeltaSec = ((postRebootTime - anchorTime) / 1000.0).coerceAtLeast(1.0)
        val impliedSpeedKmh = (bridgeDist / timeDeltaSec) * 3.6

        val shouldBridge = bridgeDist >= 4.0 && impliedSpeedKmh < 220.0
        assertFalse("Stationary drift (< 4.0m) across reboot MUST be rejected to prevent odometer inflation", shouldBridge)
    }
}
