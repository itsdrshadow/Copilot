package com.antigravity.copilot

import com.antigravity.copilot.core.database.TripEntity
import com.antigravity.copilot.core.fuel.intelligence.BestMileageSpeedEngine
import com.antigravity.copilot.core.fuel.intelligence.EfficiencyLearningStatus
import com.antigravity.copilot.core.fuel.intelligence.SpeedBand
import org.junit.Assert.*
import org.junit.Test

class BestMileageSpeedEngineTest {

    private fun createTrip(
        id: Long = 1,
        distanceMeters: Double,
        avgSpeedKmh: Double,
        estimatedFuelLiters: Double,
        isQualifying: Boolean = true
    ): TripEntity {
        return TripEntity(
            id = id,
            vehicleId = 1,
            startTime = System.currentTimeMillis() - 3600_000L,
            endTime = System.currentTimeMillis(),
            distanceMeters = distanceMeters,
            drivingDurationMs = 1800_000L,
            stationaryDurationMs = 200_000L,
            avgSpeedKmh = avgSpeedKmh,
            maxSpeedKmh = avgSpeedKmh + 15.0,
            estimatedFuelLiters = estimatedFuelLiters,
            calculatedMileageKmpl = if (estimatedFuelLiters > 0.0) (distanceMeters / 1000.0) / estimatedFuelLiters else 0.0,
            isQualifying = isQualifying
        )
    }

    @Test
    fun testEmptyTripsYieldsInsufficientData() {
        val result = BestMileageSpeedEngine.analyze(emptyList())
        assertEquals(EfficiencyLearningStatus.INSUFFICIENT_DATA, result.status)
        assertNull(result.recommendedBand)
        assertNull(result.estimatedMileageKmpl)
        assertEquals(0, result.confidencePercent)
        assertEquals(0, result.qualifyingTripCount)
    }

    @Test
    fun testShortNonQualifyingTripsAreExcluded() {
        // All trips are under 1.0 km
        val shortTrips = listOf(
            createTrip(id = 1, distanceMeters = 800.0, avgSpeedKmh = 45.0, estimatedFuelLiters = 0.08, isQualifying = false),
            createTrip(id = 2, distanceMeters = 500.0, avgSpeedKmh = 55.0, estimatedFuelLiters = 0.05, isQualifying = false)
        )
        val result = BestMileageSpeedEngine.analyze(shortTrips)
        assertEquals(EfficiencyLearningStatus.INSUFFICIENT_DATA, result.status)
        assertEquals(0, result.qualifyingTripCount)
    }

    @Test
    fun testUnphysicalFuelOrMileageOutliersAreFiltered() {
        val outlierTrips = listOf(
            // Fuel practically zero (unphysical 1000 km/L)
            createTrip(id = 1, distanceMeters = 10_000.0, avgSpeedKmh = 55.0, estimatedFuelLiters = 0.01),
            // Mileage 1.5 km/L (unphysical leak or error)
            createTrip(id = 2, distanceMeters = 15_000.0, avgSpeedKmh = 60.0, estimatedFuelLiters = 10.0)
        )
        val result = BestMileageSpeedEngine.analyze(outlierTrips)
        assertEquals(EfficiencyLearningStatus.INSUFFICIENT_DATA, result.status)
    }

    @Test
    fun testSpeedBelow20KmhIsExcluded() {
        // Heavy crawling / stop-and-go average speed below 20 km/h
        val crawlingTrips = listOf(
            createTrip(id = 1, distanceMeters = 8_000.0, avgSpeedKmh = 14.0, estimatedFuelLiters = 0.8)
        )
        val result = BestMileageSpeedEngine.analyze(crawlingTrips)
        assertEquals(EfficiencyLearningStatus.INSUFFICIENT_DATA, result.status)
    }

    @Test
    fun testModestDataYieldsLearningState() {
        // Total distance 12.0 km (above 5 km threshold, below 25 km confirmed threshold)
        val trips = listOf(
            createTrip(id = 1, distanceMeters = 6_000.0, avgSpeedKmh = 54.0, estimatedFuelLiters = 0.32), // ~18.75 km/L
            createTrip(id = 2, distanceMeters = 6_000.0, avgSpeedKmh = 56.0, estimatedFuelLiters = 0.31)  // ~19.35 km/L
        )
        val result = BestMileageSpeedEngine.analyze(trips)
        assertEquals(EfficiencyLearningStatus.LEARNING, result.status)
        assertNotNull(result.recommendedBand)
        assertEquals(SpeedBand(50, 60), result.recommendedBand)
        assertTrue("Confidence in learning state should be between 10% and 48%", result.confidencePercent in 10..48)
    }

    @Test
    fun testSufficientMultiBandDataIdentifiesMostEfficientSpeedRange() {
        // Multi-band dataset:
        // Band 30-40: 15 km, 1.25 L -> 12.0 km/L
        // Band 50-60: 35 km, 1.75 L -> 20.0 km/L (MOST EFFICIENT)
        // Band 80-90: 20 km, 1.33 L -> 15.0 km/L (FASTEST BUT LESS EFFICIENT)
        val trips = listOf(
            createTrip(id = 1, distanceMeters = 15_000.0, avgSpeedKmh = 35.0, estimatedFuelLiters = 1.25),
            createTrip(id = 2, distanceMeters = 20_000.0, avgSpeedKmh = 55.0, estimatedFuelLiters = 1.00),
            createTrip(id = 3, distanceMeters = 15_000.0, avgSpeedKmh = 58.0, estimatedFuelLiters = 0.75),
            createTrip(id = 4, distanceMeters = 20_000.0, avgSpeedKmh = 85.0, estimatedFuelLiters = 1.33)
        )

        val result = BestMileageSpeedEngine.analyze(trips)

        assertEquals(EfficiencyLearningStatus.CONFIRMED, result.status)
        assertEquals(SpeedBand(50, 60), result.recommendedBand)
        assertEquals("50–60 km/h", result.recommendedBand?.label)
        assertNotNull(result.estimatedMileageKmpl)
        assertEquals(20.0, result.estimatedMileageKmpl!!, 0.2)
        assertTrue("Confidence should be high (>= 60%)", result.confidencePercent >= 60)

        // Verify the engine did NOT pick Band 80-90 just because it was faster!
        assertNotEquals(SpeedBand(80, 90), result.recommendedBand)
    }
}
