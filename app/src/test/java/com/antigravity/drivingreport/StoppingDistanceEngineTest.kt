package com.antigravity.drivingreport

import com.antigravity.drivingreport.core.location.RoadSurfaceCondition
import com.antigravity.drivingreport.core.location.StoppingDistanceEngine
import org.junit.Assert.*
import org.junit.Test

class StoppingDistanceEngineTest {

    @Test
    fun testStationaryVehicleYieldsZeroStoppingDistance() {
        val result = StoppingDistanceEngine.calculate(speedKmh = 0f)
        assertEquals(0f, result.totalDistanceMeters, 0.01f)
        assertEquals(0f, result.reactionDistanceMeters, 0.01f)
        assertEquals(0f, result.brakingDistanceMeters, 0.01f)
        assertEquals("0 m", result.formattedTotalMeters)
    }

    @Test
    fun testCreepingBelowThresholdYieldsZeroStoppingDistance() {
        // Below MIN_SPEED_THRESHOLD_KMH (1.5 km/h)
        val result = StoppingDistanceEngine.calculate(speedKmh = 1.0f)
        assertEquals(0f, result.totalDistanceMeters, 0.01f)
        assertEquals("0 m", result.formattedTotalMeters)
    }

    @Test
    fun testInvalidGpsYieldsZeroStoppingDistance() {
        val result = StoppingDistanceEngine.calculate(speedKmh = 60f, isGpsValid = false)
        assertEquals(0f, result.totalDistanceMeters, 0.01f)
        assertFalse(result.isReliableSpeed)
    }

    @Test
    fun testNormalDryAsphaltAt50Kmh() {
        // 50 km/h = 13.889 m/s
        // d_r = 13.889 * 1.0 = 13.889 m
        // d_b = 13.889^2 / (2 * 0.72 * 9.80665) = 192.9 / 14.1216 = 13.66 m
        // total = 13.889 + 13.66 = 27.55 m -> rounds to ~28 m
        val result = StoppingDistanceEngine.calculate(speedKmh = 50f, roadCondition = RoadSurfaceCondition.DRY)
        assertTrue(result.isReliableSpeed)
        assertTrue("Total stopping distance at 50 km/h should be between 26m and 29m", result.totalDistanceMeters in 26f..29f)
        assertTrue("Reaction distance should be ~13.8m", result.reactionDistanceMeters in 13.5f..14.2f)
        assertTrue("Braking distance should be ~13.6m", result.brakingDistanceMeters in 13.0f..14.0f)
        assertEquals("~28 m", result.formattedTotalMeters)
    }

    @Test
    fun testNormalDryAsphaltAt100Kmh() {
        // 100 km/h = 27.778 m/s
        // d_r = 27.778 m
        // d_b = 771.6 / 14.1216 = 54.64 m
        // total = 82.42 m -> ~82 m
        val result = StoppingDistanceEngine.calculate(speedKmh = 100f, roadCondition = RoadSurfaceCondition.DRY)
        assertTrue("Total stopping distance at 100 km/h should be between 80m and 85m", result.totalDistanceMeters in 80f..85f)
        assertEquals("~82 m", result.formattedTotalMeters)
    }

    @Test
    fun testWetRoadIncreasesBrakingDistance() {
        val dryResult = StoppingDistanceEngine.calculate(speedKmh = 60f, roadCondition = RoadSurfaceCondition.DRY)
        val wetResult = StoppingDistanceEngine.calculate(speedKmh = 60f, roadCondition = RoadSurfaceCondition.WET)

        // Reaction distance must remain identical (driver reaction time is unchanged)
        assertEquals(dryResult.reactionDistanceMeters, wetResult.reactionDistanceMeters, 0.01f)

        // Wet road braking distance must be significantly higher due to lower friction
        assertTrue(wetResult.brakingDistanceMeters > dryResult.brakingDistanceMeters * 1.5f)
        assertTrue(wetResult.totalDistanceMeters > dryResult.totalDistanceMeters)
    }
}
