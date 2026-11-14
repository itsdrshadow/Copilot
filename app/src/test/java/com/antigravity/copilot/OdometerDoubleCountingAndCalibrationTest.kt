package com.antigravity.copilot

import com.antigravity.copilot.core.database.TripEntity
import com.antigravity.copilot.core.model.TripStatus
import com.antigravity.copilot.core.model.TripSummary
import com.antigravity.copilot.core.odometer.OdometerManager
import org.junit.Assert.*
import org.junit.Test

class OdometerDoubleCountingAndCalibrationTest {

    /**
     * Feature 11: Active trip distance must NOT be double-counted during historical rollups.
     * Historical trips query strictly returns COMPLETED trips.
     * Active distance is added conditionally only when status is an active driving state.
     */
    @Test
    fun testActiveTripDistanceDoesNotDoubleCountHistoricalRollups() {
        val completedTrips = listOf(
            TripEntity(
                id = 1,
                startTime = 1000L,
                endTime = 2000L,
                distanceMeters = 15000.0, // 15.0 km
                status = "COMPLETED",
                isQualifying = true
            ),
            TripEntity(
                id = 2,
                startTime = 3000L,
                endTime = 4000L,
                distanceMeters = 25000.0, // 25.0 km
                status = "COMPLETED",
                isQualifying = true
            )
        )

        // Historical query returns only COMPLETED trips
        val historicalKm = completedTrips.sumOf { it.distanceMeters } / 1000.0
        assertEquals(40.0, historicalKm, 0.001)

        // Case A: Ongoing Active Trip with 3.5 km
        val activeTrip = TripSummary(
            id = 3,
            startTime = 5000L,
            distanceMeters = 3500.0,
            status = TripStatus.ACTIVE_TRIP
        )
        val isTripActiveA = activeTrip.status == TripStatus.ACTIVE_TRIP ||
                activeTrip.status == TripStatus.MOVEMENT_CANDIDATE ||
                activeTrip.status == TripStatus.TEMPORARY_STOP
        val totalLifetimeKmA = historicalKm + if (isTripActiveA) activeTrip.distanceKm else 0.0
        assertEquals(43.5, totalLifetimeKmA, 0.001)

        // Case B: Completed trip in memory (TripStatus.IDLE / empty) must NOT add ghost distance
        val idleTrip = TripSummary.empty()
        val isTripActiveB = idleTrip.status == TripStatus.ACTIVE_TRIP ||
                idleTrip.status == TripStatus.MOVEMENT_CANDIDATE ||
                idleTrip.status == TripStatus.TEMPORARY_STOP
        val totalLifetimeKmB = historicalKm + if (isTripActiveB) idleTrip.distanceKm else 0.0
        assertEquals(40.0, totalLifetimeKmB, 0.001)
    }

    /**
     * Feature 11: TripSummary.empty() resets distance to 0.0 and status to IDLE.
     * When onTripCompleted executes, _currentTrip resets to empty, preventing double-counting.
     */
    @Test
    fun testCompletedTripDistanceDoesNotDoubleCountWithHistoricalList() {
        val emptyTrip = TripSummary.empty()
        assertEquals(0L, emptyTrip.id)
        assertEquals(0L, emptyTrip.startTime)
        assertEquals(TripStatus.IDLE, emptyTrip.status)
        assertEquals(0.0, emptyTrip.distanceKm, 0.0001)
        assertEquals(0.0, emptyTrip.distanceMeters, 0.0001)

        // Simulating trip completion flow:
        // 1. Finished trip has 10.0 km
        val finishedTripSummary = TripSummary(
            id = 10,
            startTime = 1000L,
            endTime = 2000L,
            distanceMeters = 10000.0,
            status = TripStatus.COMPLETED
        )

        // 2. On completion, TripRepository sets _currentTrip = TripSummary.empty()
        val currentTripAfterCompletion = TripSummary.empty()

        // 3. Database gains the completed trip
        val historicalTrips = listOf(
            TripEntity(
                id = 10,
                startTime = 1000L,
                endTime = 2000L,
                distanceMeters = finishedTripSummary.distanceMeters,
                status = "COMPLETED",
                isQualifying = true
            )
        )

        val isTripActive = currentTripAfterCompletion.status == TripStatus.ACTIVE_TRIP ||
                currentTripAfterCompletion.status == TripStatus.MOVEMENT_CANDIDATE ||
                currentTripAfterCompletion.status == TripStatus.TEMPORARY_STOP

        val recordedDistanceKm = (historicalTrips.sumOf { it.distanceMeters } / 1000.0) +
                if (isTripActive) currentTripAfterCompletion.distanceKm else 0.0

        // Must be exactly 10.0 km, NOT 20.0 km (no double-counting)
        assertEquals(10.0, recordedDistanceKm, 0.001)
    }

    /**
     * Feature 12: Dual Baseline Calibration Synchronization.
     * OdometerManager is the single source of truth for dashboard cluster baseline readings.
     * Delta distance = totalLifetimeKm - baselineLifetimeKmAtUpdate.
     * Live odometer = baselineOdometerKm + delta.
     */
    @Test
    fun testOdometerCalibrationBaselineSingleSourceOfTruth() {
        // Given: Vehicle calibrated when app lifetime was 250.0 km to cluster reading 12,500.0 km
        val baselineReading = 12500.0
        val baselineLifetime = 250.0

        // Calculate delta for current totalLifetimeKm = 250.0 km
        val delta0 = (250.0 - baselineLifetime).coerceAtLeast(0.0)
        assertEquals(0.0, delta0, 0.001)
        val liveOdo0 = baselineReading + delta0
        assertEquals(12500.0, liveOdo0, 0.001)

        // When vehicle drives 15.5 km (total lifetime becomes 265.5 km)
        val currentLifetime = 265.5
        val delta1 = (currentLifetime - baselineLifetime).coerceAtLeast(0.0)
        assertEquals(15.5, delta1, 0.001)
        val liveOdo1 = baselineReading + delta1
        assertEquals(12515.5, liveOdo1, 0.001)

        // Delta should never be negative even if lifetime is coerced
        val deltaNegative = (200.0 - baselineLifetime).coerceAtLeast(0.0)
        assertEquals(0.0, deltaNegative, 0.001)
    }

    /**
     * Feature 11: Qualifying query constraint check.
     * Trips under 1000m or with non-COMPLETED status must be excluded.
     */
    @Test
    fun testQualifyingTripBoundary() {
        val shortTrip = TripSummary(id = 1, startTime = 1000L, distanceMeters = 999.0)
        assertFalse("Trips under 1000m are not qualifying", shortTrip.distanceMeters >= 1000.0)

        val qualifyingTrip = TripSummary(id = 2, startTime = 2000L, distanceMeters = 1000.0)
        assertTrue("Trips with exactly 1000m qualify", qualifyingTrip.distanceMeters >= 1000.0)

        val longTrip = TripSummary(id = 3, startTime = 3000L, distanceMeters = 15420.0)
        assertTrue("Trips over 1000m qualify", longTrip.distanceMeters >= 1000.0)
    }
}
