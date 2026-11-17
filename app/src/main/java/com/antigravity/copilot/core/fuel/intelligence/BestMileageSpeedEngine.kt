package com.antigravity.copilot.core.fuel.intelligence

import com.antigravity.copilot.core.database.TripEntity
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Speed band range model for granular fuel efficiency analysis.
 */
data class SpeedBand(
    val minSpeedKmh: Int,
    val maxSpeedKmh: Int
) {
    val label: String get() = if (maxSpeedKmh >= 120) "$minSpeedKmh+ km/h" else "$minSpeedKmh–$maxSpeedKmh km/h"
    val compactLabel: String get() = if (maxSpeedKmh >= 120) "$minSpeedKmh+" else "$minSpeedKmh–$maxSpeedKmh"

    fun matches(speed: Double): Boolean = speed >= minSpeedKmh && (maxSpeedKmh >= 120 || speed < maxSpeedKmh)

    companion object {
        val DEFAULT_BANDS = listOf(
            SpeedBand(20, 30),
            SpeedBand(30, 40),
            SpeedBand(40, 50),
            SpeedBand(50, 60),
            SpeedBand(60, 70),
            SpeedBand(70, 80),
            SpeedBand(80, 90),
            SpeedBand(90, 100),
            SpeedBand(100, 120)
        )

        fun findBand(speed: Double): SpeedBand? {
            if (speed < 20.0) return null
            return DEFAULT_BANDS.firstOrNull { it.matches(speed) }
        }
    }
}

/**
 * Status of the Best Mileage Speed Learning Engine.
 */
enum class EfficiencyLearningStatus {
    INSUFFICIENT_DATA,
    LEARNING,
    CONFIRMED
}

/**
 * Aggregated statistics for a specific speed band.
 */
data class SpeedBandStat(
    val band: SpeedBand,
    val totalDistanceKm: Double,
    val totalFuelLiters: Double,
    val sampleCount: Int,
    val averageMileageKmpl: Double
)

/**
 * Output of the Best Mileage Speed analysis.
 */
data class BestMileageSpeedResult(
    val status: EfficiencyLearningStatus,
    val recommendedBand: SpeedBand?,
    val estimatedMileageKmpl: Double?,
    val confidencePercent: Int,
    val totalEvaluatedDistanceKm: Double,
    val qualifyingTripCount: Int,
    val bandStats: List<SpeedBandStat>
) {
    val confidenceLabel: String
        get() = when {
            confidencePercent >= 75 -> "High Confidence"
            confidencePercent >= 45 -> "Moderate Confidence"
            confidencePercent > 0 -> "Low Confidence"
            else -> "Insufficient Data"
        }
}

/**
 * BestMileageSpeedEngine:
 * Evaluates historical trip data and fuel engine consumption estimates across speed bands.
 * Identifies the specific vehicle's most fuel-efficient cruising speed range with confidence estimation.
 *
 * Strict Accuracy Rules:
 * 1. Uses Fuel Engine estimates exclusively; never fabricates synthetic mileage.
 * 2. Requires qualifying trips (>= 1.0 km) and filters out stationary periods and GPS noise.
 * 3. Enforces plausible mileage boundaries (4.0 <= km/L <= 45.0).
 * 4. Yields INSUFFICIENT_DATA / LEARNING until minimum statistical thresholds are fulfilled.
 * 5. Identifies maximum fuel efficiency (km/L), never confusing efficiency with fastest speed.
 */
object BestMileageSpeedEngine {
    const val MIN_QUALIFYING_TRIP_KM = 1.0
    const val MIN_BAND_DISTANCE_KM = 3.0
    const val MIN_CONFIRMED_TOTAL_KM = 25.0
    const val MIN_CONFIRMED_TRIP_COUNT = 3
    const val MIN_PLAUSIBLE_KMPL = 4.0
    const val MAX_PLAUSIBLE_KMPL = 45.0

    /**
     * Evaluates trips to find the vehicle's best mileage speed range.
     */
    fun analyze(trips: List<TripEntity>): BestMileageSpeedResult {
        if (trips.isEmpty()) {
            return BestMileageSpeedResult(
                status = EfficiencyLearningStatus.INSUFFICIENT_DATA,
                recommendedBand = null,
                estimatedMileageKmpl = null,
                confidencePercent = 0,
                totalEvaluatedDistanceKm = 0.0,
                qualifyingTripCount = 0,
                bandStats = emptyList()
            )
        }

        // 1. Filter reliable qualifying trips with valid fuel consumption
        val validTrips = trips.filter { trip ->
            val distKm = trip.distanceMeters / 1000.0
            val fuelLiters = trip.estimatedFuelLiters
            val avgSpeed = trip.avgSpeedKmh

            val isValidDistance = distKm >= MIN_QUALIFYING_TRIP_KM
            val isValidFuel = fuelLiters >= 0.05
            val isValidSpeed = avgSpeed >= 20.0 && avgSpeed <= 140.0
            val mileage = if (isValidFuel) distKm / fuelLiters else 0.0
            val isPlausibleMileage = mileage in MIN_PLAUSIBLE_KMPL..MAX_PLAUSIBLE_KMPL

            isValidDistance && isValidFuel && isValidSpeed && isPlausibleMileage
        }

        if (validTrips.isEmpty()) {
            return BestMileageSpeedResult(
                status = EfficiencyLearningStatus.INSUFFICIENT_DATA,
                recommendedBand = null,
                estimatedMileageKmpl = null,
                confidencePercent = 0,
                totalEvaluatedDistanceKm = 0.0,
                qualifyingTripCount = 0,
                bandStats = emptyList()
            )
        }

        // 2. Accumulate metrics per speed band
        class BandAccumulator {
            var distanceKm: Double = 0.0
            var fuelLiters: Double = 0.0
            var tripCount: Int = 0
        }

        val bandMap = mutableMapOf<SpeedBand, BandAccumulator>()
        SpeedBand.DEFAULT_BANDS.forEach { bandMap[it] = BandAccumulator() }

        var totalEvaluatedDistance = 0.0

        validTrips.forEach { trip ->
            val distKm = trip.distanceMeters / 1000.0
            val fuelLiters = trip.estimatedFuelLiters
            val band = SpeedBand.findBand(trip.avgSpeedKmh)
            if (band != null) {
                val acc = bandMap.getOrPut(band) { BandAccumulator() }
                acc.distanceKm += distKm
                acc.fuelLiters += fuelLiters
                acc.tripCount += 1
                totalEvaluatedDistance += distKm
            }
        }

        // 3. Compute per-band statistics
        val bandStats = bandMap.mapNotNull { (band, acc) ->
            if (acc.distanceKm > 0.1 && acc.fuelLiters > 0.01) {
                val mileage = acc.distanceKm / acc.fuelLiters
                SpeedBandStat(
                    band = band,
                    totalDistanceKm = acc.distanceKm,
                    totalFuelLiters = acc.fuelLiters,
                    sampleCount = acc.tripCount,
                    averageMileageKmpl = mileage
                )
            } else null
        }.sortedBy { it.band.minSpeedKmh }

        // 4. Candidate bands with minimum distance threshold
        val qualifiedBands = bandStats.filter { it.totalDistanceKm >= MIN_BAND_DISTANCE_KM }

        // Find the band with the highest mileage (NOT highest speed)
        val bestBandStat = qualifiedBands.maxByOrNull { it.averageMileageKmpl }

        // 5. Determine learning status and confidence level
        val distinctBandsCount = qualifiedBands.size
        val tripCount = validTrips.size

        return when {
            totalEvaluatedDistance < 5.0 || bestBandStat == null -> {
                BestMileageSpeedResult(
                    status = EfficiencyLearningStatus.INSUFFICIENT_DATA,
                    recommendedBand = null,
                    estimatedMileageKmpl = null,
                    confidencePercent = 0,
                    totalEvaluatedDistanceKm = totalEvaluatedDistance,
                    qualifyingTripCount = tripCount,
                    bandStats = bandStats
                )
            }
            totalEvaluatedDistance < MIN_CONFIRMED_TOTAL_KM || tripCount < MIN_CONFIRMED_TRIP_COUNT || distinctBandsCount < 2 -> {
                // Learning phase: preliminary best band with modest confidence
                val conf = ((totalEvaluatedDistance / MIN_CONFIRMED_TOTAL_KM) * 45.0).roundToInt().coerceIn(10, 48)
                BestMileageSpeedResult(
                    status = EfficiencyLearningStatus.LEARNING,
                    recommendedBand = bestBandStat.band,
                    estimatedMileageKmpl = bestBandStat.averageMileageKmpl,
                    confidencePercent = conf,
                    totalEvaluatedDistanceKm = totalEvaluatedDistance,
                    qualifyingTripCount = tripCount,
                    bandStats = bandStats
                )
            }
            else -> {
                // Confirmed phase: sufficient data across multiple speed bands
                val baseConf = 60
                val distanceBonus = min(25.0, (totalEvaluatedDistance - MIN_CONFIRMED_TOTAL_KM) * 0.35)
                val tripBonus = min(10.0, (tripCount - MIN_CONFIRMED_TRIP_COUNT) * 1.5)
                val totalConf = (baseConf + distanceBonus + tripBonus).roundToInt().coerceIn(60, 95)

                BestMileageSpeedResult(
                    status = EfficiencyLearningStatus.CONFIRMED,
                    recommendedBand = bestBandStat.band,
                    estimatedMileageKmpl = bestBandStat.averageMileageKmpl,
                    confidencePercent = totalConf,
                    totalEvaluatedDistanceKm = totalEvaluatedDistance,
                    qualifyingTripCount = tripCount,
                    bandStats = bandStats
                )
            }
        }
    }
}
