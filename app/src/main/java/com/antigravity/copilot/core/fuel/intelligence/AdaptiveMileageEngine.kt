package com.antigravity.copilot.core.fuel.intelligence

import com.antigravity.copilot.core.model.MileageLearningState
import kotlin.math.exp
import kotlin.math.max

/**
 * Observation interval between two reliable points.
 */
data class FuelInterval(
    val startOdometerKm: Double,
    val endOdometerKm: Double,
    val startFuelLiters: Double,
    val fuelAddedDuringInterval: Double,
    val endFuelLiters: Double,
    val avgSpeedKmh: Double = 45.0,
    val isReliableAnchor: Boolean = true,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun calculateMileage(): Double {
        val dist = FuelMath.roundDistance(endOdometerKm - startOdometerKm)
        val consumed = FuelMath.roundFuel((startFuelLiters + fuelAddedDuringInterval) - endFuelLiters)
        return FuelMath.calculateMileage(dist, consumed)
    }
}

/**
 * Component C: Adaptive Statistical Mileage Engine.
 * Supports partial refills, multi-anchor reconciliation, IQR-based outlier rejection,
 * city vs highway classification, and transparent uncertainty estimation.
 */
class AdaptiveMileageEngine(
    var baselineVehicleMileageKmpl: Double = 18.0
) {
    companion object {
        const val MIN_PLAUSIBLE_MILEAGE = 4.0
        const val MAX_PLAUSIBLE_MILEAGE = 45.0
        const val MIN_INTERVAL_KM = 15.0
        const val MIN_INTERVAL_LITERS = 1.0
        const val MAX_SAMPLES_BUFFER = 50
    }

    data class MileageSample(
        val timestamp: Long,
        val distanceKm: Double,
        val fuelConsumedLiters: Double,
        val calculatedKmpl: Double,
        val avgSpeedKmh: Double,
        val isHighway: Boolean,
        val isReliableAnchor: Boolean
    )

    // Mileage outputs
    var overallEstimatedMileage: Double = baselineVehicleMileageKmpl
        private set

    var recentMileageEstimate: Double = baselineVehicleMileageKmpl
        private set

    var cityMileageEstimate: Double = baselineVehicleMileageKmpl * 0.82
        private set

    var highwayMileageEstimate: Double = baselineVehicleMileageKmpl * 1.15
        private set

    var measuredMileage: Double? = null
        private set

    var sampleCount: Int = 0
        private set

    var confidenceScore: Int = 30 // Prior baseline confidence
        private set

    var mileageTrend: String = "STABLE"
        private set

    var lastReliableUpdateTimestamp: Long = 0L
        private set

    val hasSufficientEvidence: Boolean get() = sampleCount >= 2 && measuredMileage != null

    val learningState: MileageLearningState
        get() = when {
            hasSufficientEvidence -> MileageLearningState.LEARNED
            sampleCount >= 1 -> MileageLearningState.LEARNING
            baselineVehicleMileageKmpl > 0.0 -> MileageLearningState.INITIAL_BASELINE
            else -> MileageLearningState.INSUFFICIENT_DATA
        }

    fun updateBaseline(newBaseline: Double) {
        val safe = if (newBaseline in MIN_PLAUSIBLE_MILEAGE..MAX_PLAUSIBLE_MILEAGE) newBaseline else 18.0
        baselineVehicleMileageKmpl = safe
        if (!hasSufficientEvidence) {
            if (samples.isNotEmpty()) {
                recalculateAdaptiveEstimates()
            } else {
                overallEstimatedMileage = safe
                recentMileageEstimate = safe
                cityMileageEstimate = FuelMath.roundMileage(safe * 0.82)
                highwayMileageEstimate = FuelMath.roundMileage(safe * 1.15)
            }
        }
    }

    fun resetLearnedModel(newBaseline: Double = 18.0) {
        val safe = if (newBaseline in MIN_PLAUSIBLE_MILEAGE..MAX_PLAUSIBLE_MILEAGE) newBaseline else 18.0
        baselineVehicleMileageKmpl = safe
        overallEstimatedMileage = safe
        recentMileageEstimate = safe
        cityMileageEstimate = FuelMath.roundMileage(safe * 0.82)
        highwayMileageEstimate = FuelMath.roundMileage(safe * 1.15)
        measuredMileage = null
        sampleCount = 0
        confidenceScore = 30
        mileageTrend = "STABLE"
        lastReliableUpdateTimestamp = 0L
        samples.clear()
    }

    private val samples = mutableListOf<MileageSample>()

    /**
     * Records a verified observation between two reliable anchors with any number of partial refills in between.
     * Formula:
     * Fuel Consumed = Starting Fuel + Fuel Added - Ending Fuel
     * Mileage = Reliable Distance / Fuel Consumed
     */
    fun processAnchorInterval(
        startOdometerKm: Double,
        endOdometerKm: Double,
        startFuelLiters: Double,
        addedFuelLitersDuringInterval: Double,
        endFuelLiters: Double,
        avgSpeedKmh: Double = 45.0,
        isReliableAnchor: Boolean = true,
        timestamp: Long = System.currentTimeMillis()
    ): Double? {
        val deltaDistance = FuelMath.roundDistance(endOdometerKm - startOdometerKm)
        if (deltaDistance < MIN_INTERVAL_KM) {
            return null // Insufficient distance to calculate reliable consumption
        }

        val fuelConsumed = FuelMath.roundFuel((startFuelLiters + addedFuelLitersDuringInterval) - endFuelLiters)
        if (fuelConsumed < MIN_INTERVAL_LITERS) {
            return null // Insufficient fuel change; division would be dominated by noise
        }

        val rawMileage = FuelMath.calculateMileage(deltaDistance, fuelConsumed)
        if (rawMileage !in MIN_PLAUSIBLE_MILEAGE..MAX_PLAUSIBLE_MILEAGE) {
            return null // Physically implausible outlier
        }

        val isHighway = avgSpeedKmh >= 60.0
        val sample = MileageSample(
            timestamp = timestamp,
            distanceKm = deltaDistance,
            fuelConsumedLiters = fuelConsumed,
            calculatedKmpl = rawMileage,
            avgSpeedKmh = avgSpeedKmh,
            isHighway = isHighway,
            isReliableAnchor = isReliableAnchor
        )

        samples.add(sample)
        if (samples.size > MAX_SAMPLES_BUFFER) {
            samples.removeAt(0)
        }

        sampleCount = samples.size
        recalculateAdaptiveEstimates()
        lastReliableUpdateTimestamp = timestamp

        return rawMileage
    }

    /**
     * Recalculates overall, recent, city, and highway mileage using IQR outlier rejection
     * and exponential recency-weighting.
     */
    fun recalculateAdaptiveEstimates() {
        if (samples.isEmpty()) {
            overallEstimatedMileage = baselineVehicleMileageKmpl
            recentMileageEstimate = baselineVehicleMileageKmpl
            cityMileageEstimate = FuelMath.roundMileage(baselineVehicleMileageKmpl * 0.82)
            highwayMileageEstimate = FuelMath.roundMileage(baselineVehicleMileageKmpl * 1.15)
            confidenceScore = 30
            mileageTrend = "STABLE"
            return
        }

        // 1. Separate reliable samples
        val reliableSamples = samples.filter { it.isReliableAnchor }
        val activePool = if (reliableSamples.isNotEmpty()) reliableSamples else samples

        // 2. Outlier Rejection via Interquartile Range (IQR)
        val validValues = activePool.map { it.calculatedKmpl }
        val filteredValues = filterOutliersIQR(validValues)

        if (filteredValues.isEmpty()) {
            return
        }

        // 3. Measured Mileage (simple median or mean of cleaned reliable samples)
        measuredMileage = FuelMath.roundMileage(filteredValues.average())

        // 4. Exponential recency weighting for overall estimate (normalized exponent = 2.0 to prevent sample dominance)
        var weightedSum = 0.0
        var totalWeights = 0.0
        val n = filteredValues.size
        for (i in filteredValues.indices) {
            val normalizedIdx = if (n > 1) (i.toDouble() / (n - 1)) * 2.0 else 0.0
            val weight = exp(normalizedIdx)
            weightedSum += filteredValues[i] * weight
            totalWeights += weight
        }
        val adaptiveMean = if (totalWeights > 0.0) weightedSum / totalWeights else filteredValues.average()

        // Blend with baseline prior depending on sample size; requires at least 3 samples before pure empirical dominance
        val priorWeight = if (filteredValues.size >= 3) 0.0 else max(0.05, 1.0 / (1.0 + (filteredValues.size * 0.7)))
        val blended = if (priorWeight == 0.0) adaptiveMean else (adaptiveMean * (1.0 - priorWeight)) + (baselineVehicleMileageKmpl * priorWeight)
        overallEstimatedMileage = FuelMath.roundMileage(blended.coerceIn(MIN_PLAUSIBLE_MILEAGE, MAX_PLAUSIBLE_MILEAGE))

        // 5. Recent estimate: Last 3 samples average
        val recentPool = filteredValues.takeLast(3)
        recentMileageEstimate = FuelMath.roundMileage(recentPool.average().coerceIn(MIN_PLAUSIBLE_MILEAGE, MAX_PLAUSIBLE_MILEAGE))

        // 6. City vs. Highway Segmentation
        val cityPool = activePool.filter { !it.isHighway }.map { it.calculatedKmpl }
        val hwyPool = activePool.filter { it.isHighway }.map { it.calculatedKmpl }

        cityMileageEstimate = if (cityPool.size >= 2) {
            FuelMath.roundMileage(filterOutliersIQR(cityPool).average().coerceIn(MIN_PLAUSIBLE_MILEAGE, MAX_PLAUSIBLE_MILEAGE))
        } else {
            FuelMath.roundMileage(overallEstimatedMileage * 0.82)
        }

        highwayMileageEstimate = if (hwyPool.size >= 2) {
            FuelMath.roundMileage(filterOutliersIQR(hwyPool).average().coerceIn(MIN_PLAUSIBLE_MILEAGE, MAX_PLAUSIBLE_MILEAGE))
        } else {
            FuelMath.roundMileage(overallEstimatedMileage * 1.15)
        }

        // 7. Trend Analysis
        mileageTrend = when {
            recentMileageEstimate > overallEstimatedMileage * 1.05 -> "IMPROVING"
            recentMileageEstimate < overallEstimatedMileage * 0.95 -> "DECLINING"
            else -> "STABLE"
        }

        // 8. Confidence Score
        confidenceScore = when {
            filteredValues.size >= 6 -> 90
            filteredValues.size >= 4 -> 80
            filteredValues.size >= 2 -> 65
            filteredValues.size == 1 -> 48
            else -> 30
        }
    }

    fun addInterval(interval: FuelInterval): Double? = processAnchorInterval(
        startOdometerKm = interval.startOdometerKm,
        endOdometerKm = interval.endOdometerKm,
        startFuelLiters = interval.startFuelLiters,
        addedFuelLitersDuringInterval = interval.fuelAddedDuringInterval,
        endFuelLiters = interval.endFuelLiters,
        avgSpeedKmh = interval.avgSpeedKmh,
        isReliableAnchor = interval.isReliableAnchor,
        timestamp = interval.timestamp
    )

    fun getAdaptiveMileage(): Double = overallEstimatedMileage

    /**
     * Interquartile Range (IQR) Outlier Filter.
     */
    private fun filterOutliersIQR(data: List<Double>): List<Double> {
        if (data.size < 4) return data // Need at least 4 items for meaningful quartiles

        val sorted = data.sorted()
        val q1 = sorted[sorted.size / 4]
        val q3 = sorted[(sorted.size * 3) / 4]
        val iqr = q3 - q1

        val lowerBound = q1 - 1.5 * iqr
        val upperBound = q3 + 1.5 * iqr

        val filtered = sorted.filter { it in lowerBound..upperBound }
        return if (filtered.isNotEmpty()) filtered else data
    }
}
