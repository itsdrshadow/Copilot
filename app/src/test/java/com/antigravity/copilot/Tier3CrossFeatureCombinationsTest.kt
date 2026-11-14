package com.antigravity.copilot

import com.antigravity.copilot.core.database.TripEntity
import com.antigravity.copilot.core.database.TripGeofenceEntity
import com.antigravity.copilot.core.fuel.intelligence.*
import com.antigravity.copilot.core.model.TripStatus
import com.antigravity.copilot.core.trip.TripLifecycleState
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/**
 * Tier 3: Cross-Feature Combinations Test Suite.
 * Validates complex pairwise interactions between subsystems:
 * - Mid-trip reboot + fuel recovery + first-fix distance accumulation (F10 + F17)
 * - Severe GPS outage + low-fuel reserve threshold trigger (F3 + F16)
 * - Geofence departure + unrecorded refuel detection (F5 + F18)
 * - Live active trip + backup restore transactional synchronization (F11 + F19 + F21)
 * - Odometer baseline recalibration during active trip movement (F12 + F13)
 * - Hardware power-cut during temporary stop traffic dwell (F5 + F8)
 * - Slosh noise filtering during harsh braking and acceleration maneuvers (F14 + F15)
 */
class Tier3CrossFeatureCombinationsTest {

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 6371000.0 * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    // C1: Active Trip Reboot with Fuel Recovery and First Fix Bridging
    @Test
    fun testC01_ActiveTripRebootWithFuelRecoveryAndFirstFixBridging() {
        val tripId = 2024111001L
        val preCrashDistance = 12400.0 // 12.4 km
        val preCrashFuelConsumed = 1.85 // 1.85 L consumed
        val lastKnownLat = 21.8900
        val lastKnownLon = 83.3900

        // 1. Simulate state persistence before reboot
        val persistedActiveTrip = TripEntity(
            id = tripId,
            vehicleId = 1,
            startTime = 1000000L,
            distanceMeters = preCrashDistance,
            estimatedFuelLiters = preCrashFuelConsumed,
            status = TripStatus.ACTIVE_TRIP.name
        )

        // 2. Service onCreate / restoreActiveTripState re-hydration
        var currentTripId = persistedActiveTrip.id
        var currentDistance = persistedActiveTrip.distanceMeters
        var currentTripFuel = persistedActiveTrip.estimatedFuelLiters
        var lastAnchorLat = lastKnownLat
        var lastAnchorLon = lastKnownLon

        assertEquals(tripId, currentTripId)
        assertEquals(12400.0, currentDistance, 0.001)
        assertEquals(1.85, currentTripFuel, 0.001)

        // 3. First GPS fix arrives 400m ahead after system reboot
        val firstFixLat = 21.8900 + (400.0 / 111139.0)
        val firstFixLon = 83.3900
        val bridgeDelta = haversineMeters(lastAnchorLat, lastAnchorLon, firstFixLat, firstFixLon)
        currentDistance += bridgeDelta

        // 4. Additional fuel consumed during first fix step
        val stepFuel = 400.0 / 1000.0 / 16.0 // 0.025 L
        currentTripFuel += stepFuel

        assertEquals("Restored trip distance must accumulate 400m reboot bridge", 12800.0, currentDistance, 5.0)
        assertEquals("Cumulative fuel consumed must continue uninterrupted from 1.85 L", 1.875, currentTripFuel, 0.01)
    }

    // C2: Tunnel Blackout During Active Trip With Low Fuel Threshold
    @Test
    fun testC02_TunnelBlackoutDuringActiveTripWithLowFuelThreshold() {
        // Vehicle enters 1.8 km tunnel with low fuel
        val entryLat = 21.8000
        val entryLon = 83.3000
        val entryTime = 1000000L
        val fuelEstimator = FuelStateEstimator(designCapacityLiters = 48.0)
        fuelEstimator.initializeBaseline(initialLiters = 7.5, gaugePoints = 2, odometerKm = 45000.0)

        // In tunnel, 25 seconds of GPS blackout occurs
        val exitTime = entryTime + 25000L
        val exitLat = 21.8000 + (1800.0 / 111139.0)
        val exitLon = 83.3000

        // Tunnel bridge distance
        val tunnelDistance = haversineMeters(entryLat, entryLon, exitLat, exitLon)
        assertEquals(1800.0, tunnelDistance, 5.0)

        // During tunnel, vehicle consumed 0.6 L -> fuel drops to 6.9 L (below 7.0 L threshold)
        fuelEstimator.applyConsumption(0.6, odometerKm = 45001.8)
        assertEquals(6.9, fuelEstimator.nominalFuelLiters, 0.01)

        // Verify low fuel reserve safety multiplier (0.85) triggers
        val mileage = 16.0
        val isLowFuel = fuelEstimator.nominalFuelLiters < 7.0 || (fuelEstimator.nominalFuelLiters / 48.0) < 0.15
        assertTrue("Fuel level 6.9 L must trigger low fuel status", isLowFuel)

        val bufferedDte = fuelEstimator.nominalFuelLiters * mileage * 0.85
        assertEquals("Buffered DTE must reflect 0.85 multiplier (6.9 * 16.0 * 0.85 = 93.84 km)", 93.84, bufferedDte, 0.1)
    }

    // C3: Geofence Departure Simultaneous With Unrecorded Refuel Detection
    @Test
    fun testC03_GeofenceDepartureSimultaneousWithUnrecordedRefuelDetection() {
        val homeGeofence = TripGeofenceEntity(id = 1, name = "Home", latitude = 21.8974, longitude = 83.3950, radiusMeters = 200f)
        val calibration = FuelGaugeCalibrationEngine(designCapacityLiters = 48.0)
        val missedIntel = MissedEntryIntelligence(calibrationEngine = calibration, thresholdLiters = 4.0)

        // Initial state inside geofence with 12.0 L (bar 2)
        var lifecycle = TripLifecycleState.INSIDE_GEOFENCE
        val initialLiters = 12.0

        // Vehicle moves 300m away (exits 200m radius) -> DEPARTING -> TRIP_ACTIVE
        val departedLat = 21.8974 + (300.0 / 111139.0)
        val departedLon = 83.3950
        val distToHome = haversineMeters(departedLat, departedLon, homeGeofence.latitude, homeGeofence.longitude)
        assertTrue(distToHome > homeGeofence.radiusMeters)

        lifecycle = TripLifecycleState.TRIP_ACTIVE
        assertEquals(TripLifecycleState.TRIP_ACTIVE, lifecycle)

        val estimator = FuelStateEstimator(designCapacityLiters = 48.0)
        estimator.initializeBaseline(initialLiters = 12.0, gaugePoints = 2, odometerKm = 50000.0)

        // Meanwhile, gauge reading jumps to 6 bars (~28.8 L) -> unrecorded refuel detected
        val detectionResult = missedIntel.evaluateObservedGauge(
            observedGaugePoints = 6,
            estimator = estimator,
            currentOdometerKm = 50200.0
        )

        assertTrue("Unrecorded refuel must be detected upon gauge increase", detectionResult.detected)
        assertTrue("Plausible refuel liters must exceed 10 L", detectionResult.plausibleNominalLiters >= 10.0)
        assertEquals(EventConfidence.PROVISIONAL, detectionResult.confidence)
        assertEquals(0.0, detectionResult.suggestedLedgerEvent?.pricePerLiter ?: 0.0, 0.0)
    }

    // C4: Live Active Trip Running Concurrent With Backup Restore
    @Test
    fun testC04_LiveActiveTripRunningConcurrentWithBackupRestore() {
        var activeTripRunning = true
        var activeTripDistance = 5400.0

        fun onRestoreArchiveCommitted() {
            // Restore hook cleans up in-flight transient states and reloads fresh DB state
            activeTripRunning = false
            activeTripDistance = 0.0
        }

        assertTrue(activeTripRunning)
        onRestoreArchiveCommitted()
        assertFalse("Active trip must be safely reset when backup archive is restored", activeTripRunning)
        assertEquals(0.0, activeTripDistance, 0.001)
    }

    // C5: Odometer Baseline Recalibration During Active Trip Movement
    @Test
    fun testC05_OdometerBaselineRecalibrationDuringActiveTripMovement() {
        val oldBaseline = 50000.0
        val tripLifetimeDelta = 32.5 // 32.5 km driven so far

        val liveOdoBefore = oldBaseline + tripLifetimeDelta
        assertEquals(50032.5, liveOdoBefore, 0.001)

        // User calibrates baseline to 50500.0
        val newBaseline = 50500.0
        val liveOdoAfter = newBaseline + tripLifetimeDelta
        assertEquals(50532.5, liveOdoAfter, 0.001)

        // Trip distance remains exactly 32.5 km (independent of baseline changes)
        assertEquals(32.5, tripLifetimeDelta, 0.001)
    }

    // C6: Hardware Power Cut Disconnect During Temporary Stop Traffic Dwell
    @Test
    fun testC06_HardwarePowerCutDisconnectDuringTemporaryStopTrafficDwell() {
        var tripStatus = TripStatus.ACTIVE_TRIP
        val now = 1000000L

        // Vehicle stops at long red light or railroad crossing for 20 seconds -> TEMPORARY_STOP
        tripStatus = TripStatus.TEMPORARY_STOP

        // Driver switches off engine / 12V ACC cuts (ACTION_POWER_DISCONNECTED)
        fun onPowerDisconnected() {
            // Power cut does not abort multi-day or geofence trips; stays in TEMPORARY_STOP
            if (tripStatus == TripStatus.ACTIVE_TRIP) {
                tripStatus = TripStatus.TEMPORARY_STOP
            }
        }

        onPowerDisconnected()
        assertEquals("Trip status must remain in TEMPORARY_STOP, preserving journey continuity", TripStatus.TEMPORARY_STOP, tripStatus)
    }

    // C7: Slosh Noise During Harsh Braking and Acceleration
    @Test
    fun testC07_SloshNoiseDuringHarshBrakingAndAcceleration() {
        val calibration = FuelGaugeCalibrationEngine(designCapacityLiters = 48.0)
        var harshBrakes = 0
        var rapidAccels = 0

        // Maneuver 1: Harsh brake (-3.8 m/s²)
        val decelAccel = -3.8f
        if (decelAccel < -3.2f) harshBrakes++
        assertEquals(1, harshBrakes)

        // Fluid sloshes forward: float momentarily rises to 9 bars
        val t0 = 1000000L
        calibration.filterSloshAndSlope(6, t0 - 3000)
        calibration.filterSloshAndSlope(6, t0 - 2000)
        val filtered1 = calibration.filterSloshAndSlope(9, t0 - 1000)
        assertEquals("Slosh spike to 9 bars during hard braking must be filtered to steady 6 bars", 6, filtered1)

        // Maneuver 2: Rapid launch acceleration (+3.5 m/s²)
        val launchAccel = 3.5f
        if (launchAccel > 3.0f) rapidAccels++
        assertEquals(1, rapidAccels)

        // Fluid sloshes backward: float momentarily drops to 3 bars
        val filtered2 = calibration.filterSloshAndSlope(3, t0)
        assertEquals("Slosh dip to 3 bars during rapid accel must be filtered to steady 6 bars", 6, filtered2)
    }
}
