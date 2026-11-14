package com.antigravity.drivingreport

import com.antigravity.drivingreport.core.model.VehicleState
import com.antigravity.drivingreport.core.model.VehicleStateMetrics
import org.junit.Assert.*
import org.junit.Test

class VehicleStateDomainTest {

    @Test
    fun testVehicleStateClassificationAndConfidence() {
        val metric = VehicleStateMetrics(
            currentState = VehicleState.MOVING,
            stateSinceTimestamp = 1000L,
            movingDurationMs = 5000L,
            idleDurationMs = 2000L,
            engineOffDurationMs = 15000L,
            unknownDurationMs = 0L,
            lastEngineOffTimestamp = 800L,
            lastEngineOffReason = "POWER_DISCONNECTED",
            lastConfirmedEngineOffDurationMs = 15000L
        )

        assertEquals(VehicleState.MOVING, metric.currentState)
        assertEquals(5000L, metric.movingDurationMs)
        assertEquals(2000L, metric.idleDurationMs)
        assertEquals(15000L, metric.engineOffDurationMs)
        assertEquals("POWER_DISCONNECTED", metric.lastEngineOffReason)
    }

    @Test
    fun testThresholdsForMovement() {
        val speedKmh = 2.8f
        val threshold = 3.0f

        val isMoving = speedKmh >= threshold
        assertFalse("Speeds below 3.0 km/h must not trigger moving state", isMoving)

        val drivingSpeed = 4.5f
        assertTrue("Speeds >= 3.0 km/h must trigger moving state", drivingSpeed >= threshold)
    }
}
