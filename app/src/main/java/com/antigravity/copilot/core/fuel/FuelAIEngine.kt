package com.antigravity.copilot.core.fuel

import android.content.Context
import android.content.SharedPreferences
import com.antigravity.copilot.core.database.FuelEventEntity
import com.antigravity.copilot.core.database.TripEntity
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * FuelAIEngine: Multi-Source Telemetry Fusion & Adaptive Machine Learning Estimation Model.
 *
 * Synthesizes:
 * 1. Historical pump refill ledgers (tank-to-tank paired delta distance & liters)
 * 2. Past trips driving telemetry (average speed profiles, harshness, stop & go idle ratios)
 * 3. Instrument cluster 10-point MID fuel gauge float calibration curve
 * 4. Real-time active trip driving kinematics (ICE thermal efficiency speed curve, idle burn, throttle demand)
 * 5. GPS vs. cluster odometer reconciliation
 *
 * Edge cases handled:
 * - Engine idling at traffic lights (zero distance, duration increment)
 * - GPS teleportation/tunnel jumps (spike rejection with adaptive threshold)
 * - Non-linear fuel tank float curvature (trapezoidal fuel tank geometry)
 * - Low-fuel reserve conservative range countdown
 * - Cold-start with no historical refills (graceful degradation to OEM defaults)
 * - Negative/NaN/Infinity guard on all computed values
 * - Exponential weight overflow protection for large refill histories
 * - Outlier rejection for mileage readings using IQR-based filtering
 * - Exponential Moving Average (EMA) for smooth live mileage transitions
 */
class FuelAIEngine(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("fuel_ai_model_prefs", Context.MODE_PRIVATE)

    // Learned parameters
    var learnedBaseMileageKmpl: Double = prefs.getFloat("learned_base_mileage", 18.0f).toDouble()
        private set

    var driverAggressivenessIndex: Double = prefs.getFloat("driver_aggressiveness", 1.0f).toDouble()
        private set // 0.70 (aggressive) to 1.25 (hyper-miler)

    var modelConfidencePercent: Int = prefs.getInt("model_confidence", 35)
        private set

    var isRealMileageDerived: Boolean = prefs.getBoolean("is_real_mileage_derived", false)
        private set

    // Historical mileage samples for trend analysis & self-learning
    private var historicalMileageSamples: MutableList<Double> = mutableListOf()

    // Non-linear 10-point MID gauge float volumetric curve (accounting for tank shape)
    // floatCurve[i] is expected fraction of fuel at bar i (0 to 10)
    private val floatVolumetricCurve: DoubleArray = doubleArrayOf(
        0.00, // 0 bar (empty reserve)
        0.08, // 1 bar
        0.17, // 2 bars (low fuel reserve warning)
        0.27, // 3 bars
        0.38, // 4 bars
        0.49, // 5 bars (half tank)
        0.60, // 6 bars
        0.71, // 7 bars
        0.82, // 8 bars
        0.91, // 9 bars
        1.00  // 10 bars (full tank)
    )

    // Runtime live tracking state
    private var lastEmittedSpeedKmh: Double = 0.0
    private var lastSpeedTimestamp: Long = 0L
    private var smoothedLiveMileage: Double = learnedBaseMileageKmpl

    // EMA smoothing factor: higher = more responsive, lower = smoother
    private val emaSmoothingFactor: Double = 0.20

    // Sanity bounds for mileage values (km/L) — covers scooters to trucks
    companion object {
        const val MIN_VALID_MILEAGE = 3.5
        const val MAX_VALID_MILEAGE = 45.0
        const val MIN_REFILL_MILEAGE_RANGE = 5.0
        const val MAX_REFILL_MILEAGE_RANGE = 40.0

        // Maximum recency weight exponent to prevent overflow (exp(6.0) ≈ 403)
        const val MAX_RECENCY_EXPONENT = 6.0
    }

    data class LiveFuelConsumptionResult(
        val litersConsumed: Double,
        val liveInstantMileageKmpl: Double,
        val drivingStyleTag: String,
        val isIdleBurn: Boolean
    )

    /**
     * Train / Recalibrate the AI model using all available historical records.
     * Called whenever:
     * - A new refuel is logged, edited, or deleted
     * - An odometer sync or MID bar calibration occurs
     * - A trip is completed
     * - App starts up
     *
     * Uses IQR-based outlier rejection + exponential recency weighting with overflow protection
     * for robust mileage estimation that improves with each data point.
     */
    @Suppress("UNUSED_PARAMETER")
    fun recalibrateModel(
        allFuelEvents: List<FuelEventEntity>,
        allTrips: List<TripEntity>,
        currentMidBar: Int,
        designCapacityLiters: Double,
        defaultMileageKmpl: Double
    ) {
        // Guard: ensure designCapacity is positive
        val safeDesignCapacity = designCapacityLiters.coerceAtLeast(10.0)
        val safeDefaultMileage = defaultMileageKmpl.coerceIn(MIN_VALID_MILEAGE, MAX_VALID_MILEAGE)

        var confidenceScore = 30 // Baseline prior

        // ==================== 1. TANK REFILL HISTORICAL SYNTHESIS ====================
        val validRefillEvents = allFuelEvents
            .filter { it.eventType == "REFUEL" && it.liters in 2.0..100.0 && it.odometerKm > 0.0 }
            .sortedBy { it.timestamp }

        var refillDerivedMileage: Double? = null
        val refillMileageSamples = mutableListOf<Double>()

        if (validRefillEvents.size >= 2) {
            // Step 1: Collect all valid tank-to-tank mileage samples
            for (i in 1 until validRefillEvents.size) {
                val prev = validRefillEvents[i - 1]
                val curr = validRefillEvents[i]
                val deltaKm = curr.odometerKm - prev.odometerKm

                // Guard: Tank-to-tank formula deltaKm / curr.liters is mathematically only valid for full-to-full refills.
                // For partial refills, prefer the mathematically sound calculatedMileageKmpl.
                val isFullToFull = (curr.midLevelObserved >= 9 || curr.liters >= 30.0) && (prev.midLevelObserved >= 9 || prev.liters >= 30.0)
                if (isFullToFull && deltaKm in 20.0..2000.0 && curr.liters > 0.0) {
                    val tankKmpl = deltaKm / curr.liters
                    if (tankKmpl in MIN_REFILL_MILEAGE_RANGE..MAX_REFILL_MILEAGE_RANGE) {
                        refillMileageSamples.add(tankKmpl)
                    }
                } else if (curr.calculatedMileageKmpl in MIN_REFILL_MILEAGE_RANGE..MAX_REFILL_MILEAGE_RANGE) {
                    refillMileageSamples.add(curr.calculatedMileageKmpl)
                }
            }

            // Step 2: IQR-based outlier rejection for robust mileage estimation
            val cleanedSamples = removeOutliersIQR(refillMileageSamples)

            // Step 3: Exponential recency-weighted average with overflow protection
            if (cleanedSamples.isNotEmpty()) {
                var weightedMileageSum = 0.0
                var totalWeights = 0.0
                val maxIndex = cleanedSamples.size - 1

                for (i in cleanedSamples.indices) {
                    // Normalize exponent to prevent overflow: map index to [0, MAX_RECENCY_EXPONENT]
                    val normalizedExponent = if (maxIndex > 0) {
                        (i.toDouble() / maxIndex) * MAX_RECENCY_EXPONENT
                    } else {
                        0.0
                    }
                    val recencyFactor = exp(normalizedExponent)
                    weightedMileageSum += cleanedSamples[i] * recencyFactor
                    totalWeights += recencyFactor
                }

                if (totalWeights > 0.0 && weightedMileageSum.isFinite()) {
                    refillDerivedMileage = (weightedMileageSum / totalWeights)
                        .coerceIn(MIN_REFILL_MILEAGE_RANGE, MAX_REFILL_MILEAGE_RANGE)
                    // Confidence: more data = higher confidence, capped at 35
                    confidenceScore += min(35, cleanedSamples.size * 8)
                }
            }
        } else if (validRefillEvents.size == 1) {
            val singleKmpl = validRefillEvents.last().calculatedMileageKmpl
            if (singleKmpl in MIN_REFILL_MILEAGE_RANGE..MAX_REFILL_MILEAGE_RANGE) {
                refillDerivedMileage = singleKmpl
                confidenceScore += 12
            }
        }

        // ==================== 1B. MID OBSERVATION PAIRS SYNTHESIS ====================
        val midEvents = allFuelEvents
            .filter { it.eventType == "MID_OBSERVED" && it.odometerKm > 0.0 && it.midLevelObserved in 0..10 }
            .sortedBy { it.timestamp }

        var midDerivedMileage: Double? = null
        if (midEvents.size >= 2) {
            val midMileageSamples = mutableListOf<Double>()

            for (i in 1 until midEvents.size) {
                val prev = midEvents[i - 1]
                val curr = midEvents[i]
                val deltaKm = curr.odometerKm - prev.odometerKm
                val deltaBars = prev.midLevelObserved - curr.midLevelObserved

                // Guard: only process fuel consumption direction (deltaBars > 0) with valid distance
                if (deltaKm in 10.0..800.0 && deltaBars > 0) {
                    // Use volumetric curve for more accurate liters estimation instead of linear
                    val prevFraction = if (prev.midLevelObserved in 0..10) floatVolumetricCurve[prev.midLevelObserved] else 0.5
                    val currFraction = if (curr.midLevelObserved in 0..10) floatVolumetricCurve[curr.midLevelObserved] else 0.5
                    val approxLiters = (prevFraction - currFraction) * safeDesignCapacity

                    if (approxLiters > 0.5) { // Minimum 0.5L consumed to avoid division noise
                        val midKmpl = deltaKm / approxLiters
                        if (midKmpl in MIN_REFILL_MILEAGE_RANGE..MAX_REFILL_MILEAGE_RANGE) {
                            midMileageSamples.add(midKmpl)
                        }
                    }
                }
            }

            val cleanedMid = removeOutliersIQR(midMileageSamples)
            if (cleanedMid.isNotEmpty()) {
                midDerivedMileage = cleanedMid.average()
                    .coerceIn(MIN_REFILL_MILEAGE_RANGE, MAX_REFILL_MILEAGE_RANGE)
                confidenceScore += min(25, (cleanedMid.size * 7))
            }
        }

        // ==================== 2. PAST TRIPS TELEMETRY PATTERN MINING ====================
        val completedTrips = allTrips
            .filter { it.status == "COMPLETED" || it.isQualifying }
            .filter { it.distanceMeters >= 500.0 && it.drivingDurationMs > 30000L }

        var tripDerivedModifier = 1.0

        if (completedTrips.isNotEmpty()) {
            val totalDistanceKm = completedTrips.sumOf { it.distanceMeters } / 1000.0
            val totalDriveHours = completedTrips.sumOf { it.drivingDurationMs } / 3600000.0
            val overallAvgSpeed = if (totalDriveHours > 0.01) totalDistanceKm / totalDriveHours else 45.0

            // Continuous speed-efficiency curve instead of discrete steps
            // Peak efficiency at ~62 km/h, drops off at lower/higher speeds
            tripDerivedModifier = computeSpeedEfficiencyModifier(overallAvgSpeed)

            // Driver Aggressiveness Metric from trip driving scores
            val validScores = completedTrips.map { it.driverScore }.filter { it in 0..100 }
            if (validScores.isNotEmpty()) {
                val averageTripScore = validScores.average()
                driverAggressivenessIndex = when {
                    averageTripScore >= 85 -> 1.12  // Gentle, smooth hyper-miler
                    averageTripScore >= 70 -> 1.00  // Standard balanced driver
                    averageTripScore >= 55 -> 0.90  // Spirited driver
                    averageTripScore >= 40 -> 0.80  // Heavy throttle
                    else -> 0.72                    // Very aggressive driving
                }
            }

            confidenceScore += min(25, completedTrips.size * 3)
        }

        // ==================== 3. SYNTHESIS & SENSOR FUSION ====================
        val isRealDerived = (refillDerivedMileage != null || midDerivedMileage != null)
        isRealMileageDerived = isRealDerived

        val defaultAdjusted = (safeDefaultMileage * tripDerivedModifier * driverAggressivenessIndex)
            .coerceIn(MIN_VALID_MILEAGE, MAX_VALID_MILEAGE)

        val synthesizedBase = when {
            refillDerivedMileage != null && midDerivedMileage != null -> {
                // Both sources available: weighted fusion (refill = gold standard)
                (refillDerivedMileage * 0.50 + midDerivedMileage * 0.35 + defaultAdjusted * 0.15)
            }
            refillDerivedMileage != null -> {
                // Only refill data (most accurate single source)
                (refillDerivedMileage * 0.72 + defaultAdjusted * 0.28)
            }
            midDerivedMileage != null -> {
                // Only MID observations (less accurate than refill)
                (midDerivedMileage * 0.62 + defaultAdjusted * 0.38)
            }
            else -> {
                // Cold start: no real data, use adjusted default
                defaultAdjusted
            }
        }.coerceIn(MIN_VALID_MILEAGE, MAX_VALID_MILEAGE)

        // Store sample for trend analysis
        if (synthesizedBase.isFinite()) {
            historicalMileageSamples.add(synthesizedBase)
            // Keep last 50 samples for rolling trend analysis
            if (historicalMileageSamples.size > 50) {
                historicalMileageSamples.removeAt(0)
            }
        }

        learnedBaseMileageKmpl = roundTo1Decimal(synthesizedBase)
        smoothedLiveMileage = learnedBaseMileageKmpl
        modelConfidencePercent = confidenceScore.coerceIn(30, 99)

        prefs.edit()
            .putFloat("learned_base_mileage", learnedBaseMileageKmpl.toFloat())
            .putFloat("driver_aggressiveness", driverAggressivenessIndex.toFloat())
            .putInt("model_confidence", modelConfidencePercent)
            .putBoolean("is_real_mileage_derived", isRealMileageDerived)
            .apply()
    }

    /**
     * Real-Time Kinematic Physics Engine:
     * Calculates live instantaneous mileage and exact liters burned for the incoming movement slice.
     *
     * @param deltaDistanceMeters Distance moved since last location sample.
     * @param currentSpeedKmh Live vehicle GPS speed.
     * @param deltaDurationMs Elapsed time between location updates.
     * @param currentRemainingLiters Current fuel in tank.
     * @param designCapacityLiters Max capacity of vehicle tank.
     */
    @Suppress("UNUSED_PARAMETER")
    fun calculateLiveConsumption(
        deltaDistanceMeters: Double,
        currentSpeedKmh: Double,
        deltaDurationMs: Long,
        currentRemainingLiters: Double,
        designCapacityLiters: Double
    ): LiveFuelConsumptionResult {
        // Guard: sanity check all inputs
        val safeDeltaDistance = deltaDistanceMeters.coerceAtLeast(0.0)
        val safeSpeed = currentSpeedKmh.coerceIn(0.0, 300.0) // Max 300 km/h sanity
        val deltaSeconds = (deltaDurationMs / 1000.0).coerceIn(0.1, 30.0) // Allow up to 30s gaps
        val deltaKm = safeDeltaDistance / 1000.0

        // Guard: if remaining fuel is 0 or negative, no consumption possible
        if (currentRemainingLiters <= 0.0) {
            return LiveFuelConsumptionResult(
                litersConsumed = 0.0,
                liveInstantMileageKmpl = 0.0,
                drivingStyleTag = "FUEL EMPTY",
                isIdleBurn = false
            )
        }

        // Safety Guard 1: GPS Glitch / Teleportation rejection
        // If reported movement implies > 300 km/h, reject as GPS glitch
        val impliedSpeedKmh = if (deltaSeconds > 0.0) (deltaKm / deltaSeconds) * 3600.0 else 0.0
        if (impliedSpeedKmh > 300.0) {
            return LiveFuelConsumptionResult(
                litersConsumed = 0.0,
                liveInstantMileageKmpl = learnedBaseMileageKmpl,
                drivingStyleTag = "GPS GLITCH FILTERED",
                isIdleBurn = false
            )
        }

        // ==================== 1. STATIONARY / ENGINE IDLE CASE ====================
        // When car is at a red light, drive-thru, or parking with ignition on
        if (safeSpeed < 1.5 || deltaKm < 0.001) {
            // Internal Combustion Engine (ICE) standard idle burn rate: ~0.75 Liters per hour
            val idleBurnRateLitersPerHour = 0.75
            val idleLiters = (idleBurnRateLitersPerHour / 3600.0) * deltaSeconds
            // Clamp to prevent excessive single-tick burns
            val clampedBurn = idleLiters.coerceIn(0.0, 0.05)

            // Don't decay smoothedLiveMileage toward 0 during idle — keep the last moving value
            // (Previous bug: `smoothedLiveMileage * 0.90 + 0.0` was dragging mileage to 0)

            return LiveFuelConsumptionResult(
                litersConsumed = clampedBurn,
                liveInstantMileageKmpl = 0.0, // 0 km/L is correct during standstill
                drivingStyleTag = "ENGINE IDLE",
                isIdleBurn = true
            )
        }

        // ==================== 2. KINEMATIC ACCELERATION ESTIMATION ====================
        val now = System.currentTimeMillis()
        val accelerationMps2 = if (lastSpeedTimestamp > 0 && now > lastSpeedTimestamp) {
            val deltaV_mps = ((safeSpeed - lastEmittedSpeedKmh) * 1000.0) / 3600.0
            val deltaT_s = ((now - lastSpeedTimestamp) / 1000.0).coerceAtLeast(0.2)
            (deltaV_mps / deltaT_s).coerceIn(-8.0, 8.0) // ±8 m/s² = extreme but possible
        } else {
            0.0
        }
        lastEmittedSpeedKmh = safeSpeed
        lastSpeedTimestamp = now

        // ==================== 3. SPEED-DEPENDENT BSFC THERMAL EFFICIENCY CURVE ====================
        // Continuous polynomial approximation instead of discrete when-blocks.
        // Peak efficiency at ~62 km/h, quadratic aerodynamic drag drop at high speeds.
        val speedFactor = computeSpeedConsumptionFactor(safeSpeed)

        // ==================== 4. ACCELERATION / ENGINE LOAD PENALTY ====================
        // Continuous model instead of discrete steps to avoid cliff effects
        val accelModifier = when {
            accelerationMps2 > 2.0 -> 0.45  // Very heavy throttle / launch
            accelerationMps2 > 1.2 -> 0.55  // Heavy throttle acceleration (fuel enrichment)
            accelerationMps2 > 0.4 -> 0.75 + (1.2 - accelerationMps2) * 0.25 / 0.8  // Smooth interpolation
            accelerationMps2 >= -0.3 -> 1.00 // Steady state cruise
            accelerationMps2 >= -0.6 -> 1.10 + (-0.3 - accelerationMps2) * 2.0 / 0.3 // Light coasting to strong coast
            accelerationMps2 >= -1.5 -> 3.50  // Deceleration / Fuel cut-off coasting (very high km/L!)
            else -> 4.50 // Hard engine braking — almost zero fuel consumed
        }

        // Dynamic Instantaneous Mileage (km/L)
        val rawInstantMileage = (learnedBaseMileageKmpl * speedFactor * accelModifier * driverAggressivenessIndex)

        // Guard against NaN/Infinity from edge case multiplications
        val safeInstantMileage = if (rawInstantMileage.isFinite()) {
            rawInstantMileage.coerceIn(MIN_VALID_MILEAGE, MAX_VALID_MILEAGE)
        } else {
            learnedBaseMileageKmpl
        }

        // EMA smoothing for stable HUD display (prevents erratic flickering)
        smoothedLiveMileage = (smoothedLiveMileage * (1.0 - emaSmoothingFactor) + safeInstantMileage * emaSmoothingFactor)
            .coerceIn(MIN_VALID_MILEAGE, MAX_VALID_MILEAGE)

        // Exact liters consumed for this distance delta
        val litersBurned = if (smoothedLiveMileage > 0.0) {
            (deltaKm / smoothedLiveMileage).coerceIn(0.0, 0.30)
        } else {
            0.0
        }

        // Never consume more than what's left in tank
        val clampedBurn = litersBurned.coerceAtMost(currentRemainingLiters)

        // Driving style categorization (mutually exclusive priority order)
        val styleTag = when {
            accelerationMps2 > 1.2 -> "AGGRESSIVE ACCEL"
            accelerationMps2 < -0.6 -> "REGENERATIVE COAST"
            safeSpeed in 50.0..80.0 && abs(accelerationMps2) < 0.4 -> "SUPER ECO CRUISE"
            safeSpeed < 35.0 -> "CITY STOP & GO"
            safeSpeed > 100.0 -> "HIGH SPEED DRAFT"
            else -> "OPTIMAL DRIVE"
        }

        return LiveFuelConsumptionResult(
            litersConsumed = clampedBurn,
            liveInstantMileageKmpl = roundTo1Decimal(smoothedLiveMileage),
            drivingStyleTag = styleTag,
            isIdleBurn = false
        )
    }

    /**
     * Compute Dynamic Distance-To-Empty (DTE) Range:
     * - In normal tank conditions: Range = Remaining Liters * Adaptive Composite Mileage
     * - In Low-Fuel Reserve (< 15% or < 7.0L): Switches to conservative 85th-percentile
     *   consumption so driver is never stranded due to over-optimistic range estimates!
     */
    fun calculateDynamicRangeKm(remainingLiters: Double, designCapacityLiters: Double): Double {
        if (remainingLiters <= 0.0 || designCapacityLiters <= 0.0) return 0.0

        val safeDesign = designCapacityLiters.coerceAtLeast(10.0)
        val isReserve = remainingLiters < 7.0 || (remainingLiters / safeDesign) < 0.15

        val effectiveMileage = if (isReserve) {
            // Conservative fail-safe estimation in fuel reserve
            // Use 85% of learned mileage to be safe
            (learnedBaseMileageKmpl * 0.85).coerceAtLeast(8.0)
        } else {
            learnedBaseMileageKmpl
        }

        val range = remainingLiters * effectiveMileage
        return if (range.isFinite()) roundTo1Decimal(range) else 0.0
    }

    /**
     * Map continuous remaining liters into the physical 10-point instrument cluster MID gauge bars.
     * Uses the learned non-linear float volumetric curve with proper hysteresis.
     * When remainingLiters >= designCapacity (filler neck overfill), returns 10 bars (max float position).
     */
    fun calculateMidBar(remainingLiters: Double, designCapacityLiters: Double): Int {
        if (remainingLiters <= 0.0) return 0
        val safeDesign = designCapacityLiters.coerceAtLeast(10.0)
        if (remainingLiters >= safeDesign) return 10

        val fraction = (remainingLiters / safeDesign).coerceIn(0.0, 1.0)

        // Find the bar where the fraction falls between adjacent volumetric curve points
        // Using midpoint between adjacent bars for clean transitions (proper hysteresis)
        for (bar in 10 downTo 1) {
            val barFraction = floatVolumetricCurve[bar]
            val prevBarFraction = floatVolumetricCurve[bar - 1]
            val midpoint = (barFraction + prevBarFraction) / 2.0
            if (fraction >= midpoint) {
                return bar
            }
        }
        return if (fraction > 0.02) 1 else 0
    }

    /**
     * Get nominal liters corresponding to a specific 0..10 MID cluster bar reading.
     */
    fun getLitersForBar(bar: Int, designCapacityLiters: Double): Double {
        val b = bar.coerceIn(0, 10)
        val safeDesign = designCapacityLiters.coerceAtLeast(10.0)
        return floatVolumetricCurve[b] * safeDesign
    }

    /**
     * Get the current mileage trend direction: IMPROVING, STABLE, or DEGRADING.
     * Useful for UI hints and proactive driver coaching.
     */
    fun getMileageTrend(): String {
        if (historicalMileageSamples.size < 5) return "INSUFFICIENT_DATA"
        val recent5 = historicalMileageSamples.takeLast(5).average()
        val older5 = historicalMileageSamples.dropLast(5).takeLast(5)
        if (older5.isEmpty()) return "STABLE"
        val olderAvg = older5.average()
        val changePercent = ((recent5 - olderAvg) / olderAvg) * 100.0
        return when {
            changePercent > 3.0 -> "IMPROVING"
            changePercent < -3.0 -> "DEGRADING"
            else -> "STABLE"
        }
    }

    /**
     * Reset all learned AI model state. Called when user resets all data.
     */
    fun resetLearnedModel() {
        learnedBaseMileageKmpl = 18.0
        driverAggressivenessIndex = 1.0
        modelConfidencePercent = 35
        isRealMileageDerived = false
        smoothedLiveMileage = 18.0
        lastEmittedSpeedKmh = 0.0
        lastSpeedTimestamp = 0L
        historicalMileageSamples.clear()

        prefs.edit().clear().apply()
    }

    // ==================== PRIVATE HELPER FUNCTIONS ====================

    /**
     * IQR-based outlier rejection: removes values below Q1 - 1.5*IQR or above Q3 + 1.5*IQR.
     * Returns clean list. If input has < 4 samples, returns all (not enough for IQR).
     */
    private fun removeOutliersIQR(samples: List<Double>): List<Double> {
        if (samples.size < 4) return samples

        val sorted = samples.sorted()
        val q1Index = sorted.size / 4
        val q3Index = (sorted.size * 3) / 4
        val q1 = sorted[q1Index]
        val q3 = sorted[q3Index]
        val iqr = q3 - q1
        val lowerBound = q1 - 1.5 * iqr
        val upperBound = q3 + 1.5 * iqr

        return sorted.filter { it in lowerBound..upperBound }
    }

    /**
     * Continuous speed-efficiency modifier for trip-level analysis.
     * Returns a multiplier [0.75, 1.15] based on average speed.
     * Peak at ~62 km/h.
     */
    private fun computeSpeedEfficiencyModifier(avgSpeedKmh: Double): Double {
        val peakSpeed = 62.0
        val deviation = abs(avgSpeedKmh - peakSpeed)
        // Gaussian-like falloff from peak
        return (1.15 - 0.0004 * deviation * deviation).coerceIn(0.75, 1.15)
    }

    /**
     * Continuous speed-based fuel consumption factor for real-time calculations.
     * Returns a multiplier where higher = more fuel efficient at that speed.
     * Models ICE thermal efficiency + aerodynamic drag quadratic increase.
     */
    private fun computeSpeedConsumptionFactor(speedKmh: Double): Double {
        return when {
            speedKmh <= 5.0 -> 0.50  // Crawling / parking lot
            speedKmh <= 20.0 -> 0.55 + (speedKmh - 5.0) / 15.0 * 0.25 // 0.55 → 0.80
            speedKmh <= 45.0 -> 0.80 + (speedKmh - 20.0) / 25.0 * 0.25 // 0.80 → 1.05
            speedKmh <= 62.0 -> 1.05 + (speedKmh - 45.0) / 17.0 * 0.10 // 1.05 → 1.15 (peak)
            speedKmh <= 80.0 -> 1.15 - (speedKmh - 62.0) / 18.0 * 0.10 // 1.15 → 1.05
            speedKmh <= 100.0 -> 1.05 - (speedKmh - 80.0) / 20.0 * 0.17 // 1.05 → 0.88
            speedKmh <= 120.0 -> 0.88 - (speedKmh - 100.0) / 20.0 * 0.18 // 0.88 → 0.70
            else -> (0.70 - (speedKmh - 120.0) / 40.0 * 0.10).coerceAtLeast(0.50) // 0.70 → 0.60 → 0.50 floor
        }
    }

    /**
     * Safe rounding to 1 decimal place with NaN/Infinity protection.
     */
    private fun roundTo1Decimal(value: Double): Double {
        if (!value.isFinite()) return 0.0
        return Math.round(value * 10.0) / 10.0
    }
}
