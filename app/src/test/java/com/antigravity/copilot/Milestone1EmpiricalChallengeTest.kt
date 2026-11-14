package com.antigravity.copilot

import android.location.Location
import com.antigravity.copilot.core.model.TripStatus
import com.antigravity.copilot.core.model.TripSummary
import com.antigravity.copilot.core.trip.TripDetectionEngine
import com.antigravity.copilot.feature.dashboard.calculateSpeedometerDecay
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/**
 * Milestone 1 Empirical Challenge Test Suite (Challenger 1)
 *
 * Exhaustively stress-tests:
 * 1. calculateSpeedometerDecay across 0..20,000ms (continuity at 2s, 5s, 10s; strict <= 0.01f jump; non-negativity; strict monotonic decrease).
 * 2. TripDetectionEngine.computeGeodesicDistance across global coordinates, antipode pairs, and millimetric steps.
 * 3. Reboot bridging < 50 km clamp (accepts 49,999m, rejects 50,001m; 4m jitter gate; 220 km/h speed gate).
 * 4. restoreActiveTripState under synthetic timestamps (stationary watchdog deterministic triggering without negative deltas).
 */
class Milestone1EmpiricalChallengeTest {

    private class SyntheticLocation(
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
            return TripDetectionEngine.computeGeodesicDistance(lat, lon, dest.latitude, dest.longitude).toFloat()
        }
    }

    // =========================================================================
    // OBJECTIVE 1: calculateSpeedometerDecay Stress Test across 0..20,000ms
    // =========================================================================

    @Test
    fun testSpeedometerDecay_ExhaustiveContinuityAndMonotonicity() {
        val testSpeeds = floatArrayOf(250.0f, 120.0f, 80.0f, 60.0f, 20.0f, 10.0f, 6.0f, 4.0f, 2.0f, 0.5f)

        for (initialSpeed in testSpeeds) {
            var previousSpeed = initialSpeed

            // High-density scan across 0..20,000ms in 10ms increments
            for (gapMs in 0L..20000L step 10L) {
                val speed = calculateSpeedometerDecay(initialSpeed, gapMs)

                // 1. Strict non-negativity
                assertTrue("Speed at gapMs=$gapMs must be non-negative for initialSpeed=$initialSpeed (was $speed)", speed >= 0f)

                // 2. Strict monotonic decrease (with floating point tolerance 1e-4f)
                assertTrue(
                    "Speed must monotonically decrease at gapMs=$gapMs (prev=$previousSpeed, curr=$speed)",
                    speed <= previousSpeed + 0.0001f
                )

                previousSpeed = speed
            }

            // Stage 1: 0..2000ms hold
            assertEquals("Speed at 0ms must equal initial speed", initialSpeed, calculateSpeedometerDecay(initialSpeed, 0L), 0.001f)
            assertEquals("Speed at 1000ms must equal initial speed", initialSpeed, calculateSpeedometerDecay(initialSpeed, 1000L), 0.001f)
            assertEquals("Speed at 2000ms must equal initial speed", initialSpeed, calculateSpeedometerDecay(initialSpeed, 2000L), 0.001f)

            // Stage 1->2 Boundary Continuity at 2000ms
            val speed2000 = calculateSpeedometerDecay(initialSpeed, 2000L)
            val speed2001 = calculateSpeedometerDecay(initialSpeed, 2001L)
            val delta2000 = abs(speed2000 - speed2001)
            assertTrue("Boundary delta at 2000ms must be <= 0.01 km/h (was $delta2000)", delta2000 <= 0.01f)

            // Stage 2->3 Boundary Continuity at 5000ms
            val speed5000 = calculateSpeedometerDecay(initialSpeed, 5000L)
            val speed5001 = calculateSpeedometerDecay(initialSpeed, 5001L)
            val delta5000 = abs(speed5000 - speed5001)
            assertTrue(
                "Boundary delta at 5000ms must be <= 0.01 km/h for initialSpeed=$initialSpeed (was $delta5000)",
                delta5000 <= 0.01f
            )

            // Stage 3 Termination at 10,000ms
            val speed10000 = calculateSpeedometerDecay(initialSpeed, 10000L)
            val speed10001 = calculateSpeedometerDecay(initialSpeed, 10001L)
            assertEquals("Speed at 10000ms must reach 0.0 km/h", 0.0f, speed10000, 0.001f)
            assertEquals("Speed at 10001ms must remain 0.0 km/h", 0.0f, speed10001, 0.001f)

            // Stage 4: > 10,000ms remains 0
            assertEquals("Speed at 15000ms must be 0", 0.0f, calculateSpeedometerDecay(initialSpeed, 15000L), 0.001f)
            assertEquals("Speed at 20000ms must be 0", 0.0f, calculateSpeedometerDecay(initialSpeed, 20000L), 0.001f)
        }
    }

    @Test
    fun testSpeedometerDecay_BoundaryAndDegenerateInputs() {
        // Zero initial speed
        assertEquals(0f, calculateSpeedometerDecay(0f, 0L), 0.001f)
        assertEquals(0f, calculateSpeedometerDecay(0f, 5000L), 0.001f)
        assertEquals(0f, calculateSpeedometerDecay(0f, 15000L), 0.001f)

        // Negative initial speed
        assertEquals(0f, calculateSpeedometerDecay(-50f, 0L), 0.001f)
        assertEquals(0f, calculateSpeedometerDecay(-10f, 3000L), 0.001f)

        // Negative gapMs
        assertEquals(80f, calculateSpeedometerDecay(80f, -100L), 0.001f)

        // Low speed that drops to 0 during coasting (e.g. 3.0 km/h drops to 0 after 1.5s of coasting, i.e. 3500ms)
        val lowSpeed = 3.0f
        assertEquals(3.0f, calculateSpeedometerDecay(lowSpeed, 2000L), 0.001f)
        assertEquals(1.0f, calculateSpeedometerDecay(lowSpeed, 3000L), 0.001f)
        assertEquals(0.0f, calculateSpeedometerDecay(lowSpeed, 3500L), 0.001f)
        assertEquals(0.0f, calculateSpeedometerDecay(lowSpeed, 4000L), 0.001f)
        assertEquals(0.0f, calculateSpeedometerDecay(lowSpeed, 5000L), 0.001f)
        assertEquals(0.0f, calculateSpeedometerDecay(lowSpeed, 8000L), 0.001f)
    }

    // =========================================================================
    // OBJECTIVE 2: TripDetectionEngine.computeGeodesicDistance Stress Test
    // =========================================================================

    @Test
    fun testComputeGeodesicDistance_IdenticalCoordinates() {
        // Distance from a point to itself must be exactly 0.0
        val dist1 = TripDetectionEngine.computeGeodesicDistance(21.9, 83.4, 21.9, 83.4)
        assertEquals(0.0, dist1, 1e-9)

        val dist2 = TripDetectionEngine.computeGeodesicDistance(0.0, 0.0, 0.0, 0.0)
        assertEquals(0.0, dist2, 1e-9)

        val dist3 = TripDetectionEngine.computeGeodesicDistance(90.0, 0.0, 90.0, 0.0)
        assertEquals(0.0, dist3, 1e-9)
    }

    @Test
    fun testComputeGeodesicDistance_GlobalCoordinatesAndKnownBenchmarks() {
        // Benchmark 1: London (51.5074, -0.1278) to Paris (48.8566, 2.3522) ~ 343.5 km
        val londonParis = TripDetectionEngine.computeGeodesicDistance(51.5074, -0.1278, 48.8566, 2.3522)
        assertEquals(343500.0, londonParis, 5000.0) // within 5km of expected 343.5km

        // Benchmark 2: New York (40.7128, -74.0060) to Los Angeles (34.0522, -118.2437) ~ 3935 km
        val nyLa = TripDetectionEngine.computeGeodesicDistance(40.7128, -74.0060, 34.0522, -118.2437)
        assertEquals(3935000.0, nyLa, 15000.0)

        // Benchmark 3: Equator 1 degree longitude ~ 111.195 km
        val equator1Deg = TripDetectionEngine.computeGeodesicDistance(0.0, 0.0, 0.0, 1.0)
        assertEquals(111195.0, equator1Deg, 100.0)

        // Benchmark 4: Meridian 1 degree latitude ~ 111.195 km
        val meridian1Deg = TripDetectionEngine.computeGeodesicDistance(0.0, 0.0, 1.0, 0.0)
        assertEquals(111195.0, meridian1Deg, 100.0)

        // Benchmark 5: Date line crossing (-179 to +179 across 180th meridian)
        val dateLineCrossing = TripDetectionEngine.computeGeodesicDistance(0.0, 179.0, 0.0, -179.0)
        // Shorter path across 180 is 2 degrees = ~222.39 km, but Haversine with dLon = -358 computes long way
        // In computeGeodesicDistance: dLon = toRadians(-179 - 179) = toRadians(-358). sin(-179) = -sin(1)
        val expectedDateLine = 2.0 * (PI / 180.0) * 6371000.0
        assertEquals(expectedDateLine, dateLineCrossing, 500.0)
    }

    @Test
    fun testComputeGeodesicDistance_MillimetricAndMicroSteps() {
        // Step 1: 1.11 mm displacement along meridian (1e-8 degrees)
        val lat0 = 21.90000000
        val latStep1 = 21.90000001
        val dist1mm = TripDetectionEngine.computeGeodesicDistance(lat0, 83.4, latStep1, 83.4)
        assertTrue("Millimetric step must compute non-zero distance (actual: $dist1mm)", dist1mm > 0.0005)
        assertTrue("Millimetric step must be approximately 0.0011m (actual: $dist1mm)", dist1mm in 0.0008..0.0015)

        // Step 2: 1.11 cm displacement (1e-7 degrees)
        val latStep2 = 21.90000010
        val dist1cm = TripDetectionEngine.computeGeodesicDistance(lat0, 83.4, latStep2, 83.4)
        assertTrue("Centimetric step must be approximately 0.011m (actual: $dist1cm)", dist1cm in 0.009..0.013)

        // Step 3: Numerical symmetry: dist(A, B) == dist(B, A)
        val distForward = TripDetectionEngine.computeGeodesicDistance(21.9, 83.4, 21.905, 83.405)
        val distBackward = TripDetectionEngine.computeGeodesicDistance(21.905, 83.405, 21.9, 83.4)
        assertEquals("Geodesic distance must be strictly symmetric", distForward, distBackward, 1e-9)
    }

    @Test
    fun testComputeGeodesicDistance_AntipodePairs() {
        // Antipode 1: North Pole (90, 0) to South Pole (-90, 0) -> Half Earth circumference (~20,015 km)
        val poleToPole = TripDetectionEngine.computeGeodesicDistance(90.0, 0.0, -90.0, 0.0)
        val halfCircumference = PI * 6371000.0
        assertFalse("Pole to pole distance must not be NaN", poleToPole.isNaN())
        assertEquals(halfCircumference, poleToPole, 100.0)

        // Antipode 2: Equator opposite points (0, 0) to (0, 180)
        val equatorAntipode = TripDetectionEngine.computeGeodesicDistance(0.0, 0.0, 0.0, 180.0)
        assertFalse("Equator antipode must not be NaN", equatorAntipode.isNaN())
        assertEquals(halfCircumference, equatorAntipode, 100.0)

        // Antipode 3: (45, 0) to (-45, 180)
        val midLatAntipode = TripDetectionEngine.computeGeodesicDistance(45.0, 0.0, -45.0, 180.0)
        assertFalse("Mid-latitude antipode must not be NaN", midLatAntipode.isNaN())
        assertEquals(halfCircumference, midLatAntipode, 100.0)
    }

    // =========================================================================
    // OBJECTIVE 3: Reboot Bridging < 50 km Clamp Stress Test
    // =========================================================================

    @Test
    fun testRebootBridgingClamp_ExactBoundariesAround50Km() {
        val anchorLat = 21.900000
        val anchorLon = 83.400000
        val t0 = 1000000L

        fun runRebootSimulation(distanceMeters: Double, rebootDurationSec: Long): Double {
            var bridgedMovement = 0.0
            val engine = TripDetectionEngine(
                onPointReceived = {},
                onTripStarted = {},
                onTripUpdated = { _, _ -> },
                onTripCompleted = {},
                onTripDiscarded = {}
            )
            engine.onLiveMovement = { dist, _, _ -> bridgedMovement = dist }

            // Pre-reboot location
            val preLoc = SyntheticLocation(anchorLat, anchorLon, t0, acc = 5.0f)
            engine.restoreActiveTripState(
                tripId = 101L,
                startTime = t0 - 60000L,
                distanceMeters = 5000.0,
                drivingMs = 60000L,
                stationaryMs = 0L,
                lastLocation = preLoc
            )

            // Calculate post-reboot coordinate that achieves exactly distanceMeters
            val postLat = anchorLat + (distanceMeters / 111195.0)
            val postTime = t0 + (rebootDurationSec * 1000L)

            // Warm-up fix 1
            engine.processLocation(SyntheticLocation(postLat, anchorLon, postTime, acc = 10.0f))
            assertFalse(engine.isGpsStabilized)

            // Warm-up fix 2
            engine.processLocation(SyntheticLocation(postLat, anchorLon, postTime + 1500L, acc = 10.0f))
            assertFalse(engine.isGpsStabilized)

            // Stable fix 3 at 3.2s
            engine.processLocation(SyntheticLocation(postLat, anchorLon, postTime + 3200L, acc = 10.0f))
            assertTrue(engine.isGpsStabilized)

            return bridgedMovement
        }

        // Case 1: 49,999m jump in 1000s (~180 km/h < 220 km/h) -> Within < 50 km clamp -> MUST ACCEPT
        val acceptedDist = runRebootSimulation(49999.0, 1000L)
        assertTrue(
            "Reboot jump of 49,999m must be ACCEPTED (actual bridged: $acceptedDist)",
            acceptedDist in 49900.0..50050.0
        )

        // Case 2: 50,001m jump in 1000s -> Violates < 50 km clamp -> MUST REJECT
        val rejectedDist = runRebootSimulation(50001.0, 1000L)
        assertEquals("Reboot jump of 50,001m must be REJECTED by 50km clamp", 0.0, rejectedDist, 1e-6)

        // Case 3: 3.9m jump (stationary noise) in 60s -> Violates >= 4.0m gate -> MUST REJECT
        val jitterDist = runRebootSimulation(3.9, 60L)
        assertEquals("Reboot jitter < 4.0m must be REJECTED", 0.0, jitterDist, 1e-6)

        // Case 4: 4.1m jump in 10s (implied speed 1.47 km/h < 220 km/h) -> MUST ACCEPT
        val validSmallDist = runRebootSimulation(4.1, 10L)
        assertTrue("Reboot movement >= 4.0m must be ACCEPTED (actual: $validSmallDist)", validSmallDist in 3.5..5.0)

        // Case 5: 10,000m jump in 100s (implied speed 360 km/h > 220 km/h gate) -> MUST REJECT
        val speedSpikeDist = runRebootSimulation(10000.0, 100L)
        assertEquals("Implied speed over 220 km/h must be REJECTED", 0.0, speedSpikeDist, 1e-6)
    }

    // =========================================================================
    // OBJECTIVE 4: restoreActiveTripState Synthetic Timestamps & Watchdog Stress Test
    // =========================================================================

    @Test
    fun testRestoreActiveTripState_DeterministicStationaryWatchdogUnderSyntheticEpoch() {
        var completedSummary: TripSummary? = null

        val engine = TripDetectionEngine(
            onPointReceived = {},
            onTripStarted = {},
            onTripUpdated = { _, _ -> },
            onTripCompleted = { summary -> completedSummary = summary },
            onTripDiscarded = {}
        )
        engine.isGeofenceControlled = false

        // Arbitrary synthetic timestamp far from System.currentTimeMillis() (e.g. 500,000,000L)
        val syntheticT0 = 500000000L
        val preLocation = SyntheticLocation(21.9, 83.4, syntheticT0, acc = 5.0f)

        engine.restoreActiveTripState(
            tripId = 444L,
            startTime = syntheticT0 - 300000L,
            distanceMeters = 12000.0,
            drivingMs = 300000L,
            stationaryMs = 0L,
            lastLocation = preLocation
        )

        assertEquals("Engine must restore to ACTIVE_TRIP", TripStatus.ACTIVE_TRIP, engine.currentStatus)
        assertEquals(444L, engine.currentTripId)

        // 1. Tick at syntheticT0 + 10s: standstill < 15s -> must remain ACTIVE_TRIP
        engine.processStationaryWatchdog(syntheticT0 + 10000L)
        assertEquals("Standstill < 15s must remain ACTIVE_TRIP", TripStatus.ACTIVE_TRIP, engine.currentStatus)
        assertNull(completedSummary)

        // 2. Tick at syntheticT0 + 15s: standstill >= 15s -> must transition to TEMPORARY_STOP
        engine.processStationaryWatchdog(syntheticT0 + 15000L)
        assertEquals("Standstill >= 15s must transition to TEMPORARY_STOP", TripStatus.TEMPORARY_STOP, engine.currentStatus)
        assertNull(completedSummary)

        // 3. Tick at syntheticT0 + 299s: stoppedSec = 299s < 300s -> must remain TEMPORARY_STOP
        engine.processStationaryWatchdog(syntheticT0 + 299000L)
        assertEquals("Stopped duration < 300s must remain TEMPORARY_STOP", TripStatus.TEMPORARY_STOP, engine.currentStatus)
        assertNull(completedSummary)

        // 4. Tick at syntheticT0 + 300s: stoppedSec = 300s >= 300s -> must terminate and invoke onTripCompleted
        engine.processStationaryWatchdog(syntheticT0 + 300000L)
        assertEquals("Stopped duration >= 300s in standard mode must transition to COMPLETED", TripStatus.COMPLETED, engine.currentStatus)
        assertNotNull("onTripCompleted must be called upon 300s standstill timeout", completedSummary)
        assertEquals(444L, completedSummary!!.id)
        assertEquals(syntheticT0 + 300000L, completedSummary!!.endTime)
    }

    @Test
    fun testRestoreActiveTripState_NegativeTimeDeltaResistance() {
        val engine = TripDetectionEngine(
            onPointReceived = {},
            onTripStarted = {},
            onTripUpdated = { _, _ -> },
            onTripCompleted = {},
            onTripDiscarded = {}
        )

        val syntheticT0 = 1000000000L
        val preLocation = SyntheticLocation(21.9, 83.4, syntheticT0, acc = 5.0f)

        engine.restoreActiveTripState(
            tripId = 555L,
            startTime = syntheticT0 - 60000L,
            distanceMeters = 3000.0,
            drivingMs = 60000L,
            stationaryMs = 0L,
            lastLocation = preLocation
        )

        // Negative time step (NTP jump backward by 10 minutes)
        val backwardTime = syntheticT0 - 600000L
        engine.processStationaryWatchdog(backwardTime)

        // Must safely remain in ACTIVE_TRIP without crashing or prematurely terminating
        assertEquals("Negative delta must not trigger watchdog state change", TripStatus.ACTIVE_TRIP, engine.currentStatus)
    }
}
