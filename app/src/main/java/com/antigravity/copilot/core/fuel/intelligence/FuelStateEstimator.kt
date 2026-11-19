package com.antigravity.copilot.core.fuel.intelligence

import kotlin.math.max
import kotlin.math.min

/**
 * Component B: Continuous Fuel State Estimator.
 * Maintains decimal-safe fuel balances, 3-point conservative/nominal/upper bounds,
 * physical tank constraints, and reliable anchors.
 */
class FuelStateEstimator(
    var designCapacityLiters: Double = 48.0,
    var maxCapacityLiters: Double = 48.0,
    var gaugeCalibration: FuelGaugeCalibrationEngine? = null
) {
    // Current 3-point estimated fuel levels (liters)
    var nominalFuelLiters: Double = 0.0
        private set
    var conservativeFuelLiters: Double = 0.0
        private set
    var upperFuelLiters: Double = 0.0
        private set

    // Calibrated 10-point gauge reading (0..10)
    var currentGaugeBar: Int = 0
        private set

    // Anchors
    var lastReliableFuelAnchorLiters: Double = 0.0
        private set
    var lastReliableOdometerAnchorKm: Double = 0.0
        private set
    var lastReliableAnchorTimestamp: Long = 0L
        private set

    // Current confidence (0..100)
    var confidenceScore: Int = 50
        private set

    var currentExplanation: String = "Initialized"
        private set

    init {
        ensureCapacityConsistency()
    }

    fun ensureCapacityConsistency() {
        if (designCapacityLiters < 10.0) designCapacityLiters = 10.0
        if (maxCapacityLiters < designCapacityLiters) maxCapacityLiters = designCapacityLiters
    }

    fun updateDesignCapacity(newCapacity: Double) {
        val safe = newCapacity.coerceIn(10.0, 200.0)
        designCapacityLiters = safe
        maxCapacityLiters = safe
        ensureCapacityConsistency()
        gaugeCalibration?.updateDesignCapacity(safe)
        nominalFuelLiters = nominalFuelLiters.coerceIn(0.0, maxCapacityLiters)
        conservativeFuelLiters = conservativeFuelLiters.coerceIn(0.0, nominalFuelLiters)
        upperFuelLiters = upperFuelLiters.coerceIn(nominalFuelLiters, maxCapacityLiters)
        gaugeCalibration?.let { calibration ->
            currentGaugeBar = calibration.getBarForLiters(nominalFuelLiters)
        }
    }

    /**
     * Initializes baseline state with verified parameters.
     */
    fun initializeBaseline(
        initialLiters: Double,
        gaugePoints: Int,
        odometerKm: Double,
        @Suppress("UNUSED_PARAMETER") timestamp: Long = System.currentTimeMillis()
    ) {
        ensureCapacityConsistency()
        val safeNominal = FuelMath.roundFuel(initialLiters.coerceIn(0.0, maxCapacityLiters))
        val uncertainty = calculateUncertaintySpread(EventConfidence.CONFIRMED, safeNominal)

        nominalFuelLiters = safeNominal
        conservativeFuelLiters = FuelMath.roundFuel((safeNominal - uncertainty).coerceAtLeast(0.0))
        upperFuelLiters = FuelMath.roundFuel((safeNominal + uncertainty).coerceAtMost(maxCapacityLiters))

        currentGaugeBar = gaugePoints.coerceIn(0, 10)
        lastReliableFuelAnchorLiters = safeNominal
        lastReliableOdometerAnchorKm = odometerKm.coerceAtLeast(0.0)
        lastReliableAnchorTimestamp = timestamp
        confidenceScore = 85
        currentExplanation = "Initial fuel baseline established at ${safeNominal} L ($gaugePoints/10 bars)"
    }

    /**
     * Applies a confirmed or provisional refuel.
     * Fuel balance = Previous fuel + Added fuel.
     */
    fun applyRefuel(
        addedLiters: Double,
        observedGaugePoints: Int,
        odometerKm: Double,
        confidence: EventConfidence = EventConfidence.CONFIRMED,
        @Suppress("UNUSED_PARAMETER") timestamp: Long = System.currentTimeMillis(),
        explanation: String = "Refuel applied"
    ) {
        ensureCapacityConsistency()
        val cleanAdded = FuelMath.roundFuel(addedLiters.coerceAtLeast(0.0))

        val potentialNominal = nominalFuelLiters + cleanAdded
        // If addition exceeds max capacity, expand maxCapacity (learned overfill) within safe bounds (up to 1.35x design)
        if (potentialNominal > maxCapacityLiters) {
            maxCapacityLiters = min(designCapacityLiters * 1.35, potentialNominal)
        }

        val clampedNominal = FuelMath.roundFuel(potentialNominal.coerceIn(0.0, maxCapacityLiters))
        val uncertainty = calculateUncertaintySpread(confidence, cleanAdded)

        nominalFuelLiters = clampedNominal
        conservativeFuelLiters = FuelMath.roundFuel((clampedNominal - uncertainty).coerceAtLeast(0.0))
        upperFuelLiters = FuelMath.roundFuel((clampedNominal + uncertainty).coerceAtMost(maxCapacityLiters))

        if (observedGaugePoints in 0..10) {
            currentGaugeBar = observedGaugePoints
        }

        if (confidence == EventConfidence.CONFIRMED) {
            lastReliableFuelAnchorLiters = clampedNominal
            if (odometerKm > 0.0) {
                lastReliableOdometerAnchorKm = odometerKm
            }
            lastReliableAnchorTimestamp = timestamp
            confidenceScore = min(98, confidenceScore + 15)
        } else {
            confidenceScore = max(30, confidenceScore - 10)
        }

        currentExplanation = explanation
    }

    /**
     * Moves the level to [liters] (the FuelModel's tank level), shifting the uncertainty band with it.
     * Live consumption is tracked only by the FuelModel, so this estimator must be re-synced before
     * each refill or its balance never drains between refills.
     */
    fun syncNominal(liters: Double) {
        if (!liters.isFinite()) return
        ensureCapacityConsistency()
        val target = FuelMath.roundFuel(liters.coerceIn(0.0, maxCapacityLiters))
        val delta = target - nominalFuelLiters
        nominalFuelLiters = target
        conservativeFuelLiters = FuelMath.roundFuel((conservativeFuelLiters + delta).coerceIn(0.0, target))
        upperFuelLiters = FuelMath.roundFuel((upperFuelLiters + delta).coerceIn(target, maxCapacityLiters))
        gaugeCalibration?.let { currentGaugeBar = it.getBarForLiters(nominalFuelLiters) }
    }

    /**
     * Deducts fuel consumed during driving or engine idle.
     * Fuel balance = Previous fuel - Consumed fuel.
     * Never silently allows impossible negative balance.
     */
    fun applyConsumption(
        consumedLiters: Double,
        odometerKm: Double,
        @Suppress("UNUSED_PARAMETER") timestamp: Long = System.currentTimeMillis()
    ) {
        val cleanBurn = FuelMath.roundFuel(consumedLiters.coerceAtLeast(0.0))

        // Deduct from nominal, conservative, and upper bounds
        nominalFuelLiters = FuelMath.roundFuel((nominalFuelLiters - cleanBurn).coerceAtLeast(0.0))
        conservativeFuelLiters = FuelMath.roundFuel((conservativeFuelLiters - cleanBurn).coerceAtLeast(0.0))
        upperFuelLiters = FuelMath.roundFuel((upperFuelLiters - cleanBurn).coerceAtLeast(0.0))

        // Feature 15: Dynamic 10-bar LED gauge update with strict monotonicity between fill-ups
        gaugeCalibration?.let { calibration ->
            val calibratedBar = calibration.getBarForLiters(nominalFuelLiters)
            // Enforce strict monotonicity: gauge bar must never step upwards due to sensor noise during active consumption
            currentGaugeBar = min(currentGaugeBar, calibratedBar)
        }

        if (odometerKm > lastReliableOdometerAnchorKm) {
            // Natural slight drift in confidence as distance increases between refills
            val kmSinceAnchor = odometerKm - lastReliableOdometerAnchorKm
            val decay = (kmSinceAnchor / 100.0).toInt()
            confidenceScore = (confidenceScore - decay).coerceIn(25, 95)
        }

        if (nominalFuelLiters <= 0.0) {
            currentExplanation = "Tank depleted (clamped at 0.0 L)"
            currentGaugeBar = 0
        }
    }

    /**
     * Deducts fuel consumed during a trip or distance advancement.
     */
    fun applyTripConsumption(
        consumedLiters: Double,
        newOdometerKm: Double,
        currentGaugePoints: Int = -1,
        @Suppress("UNUSED_PARAMETER") timestamp: Long = System.currentTimeMillis()
    ) {
        applyConsumption(consumedLiters, newOdometerKm, timestamp)
        if (newOdometerKm > lastReliableOdometerAnchorKm) {
            lastReliableOdometerAnchorKm = newOdometerKm
        }
        if (currentGaugePoints in 0..10) {
            // Feature 15: Monotonicity guard against upward sensor noise steps during driving
            currentGaugeBar = min(currentGaugeBar, currentGaugePoints)
        }
    }

    /**
     * Feature 16: Filters short-term gauge fluctuations caused by vehicle slope or fuel sloshing.
     * Delegates to [gaugeCalibration] if configured, otherwise clamps to valid bar bounds.
     */
    fun filterSloshAndSlope(newBar: Int, timestamp: Long = System.currentTimeMillis()): Int {
        return gaugeCalibration?.filterSloshAndSlope(newBar, timestamp) ?: newBar.coerceIn(0, 10)
    }

    /**
     * Direct adjustment from gauge reading reconciliation or manual adjustment.
     */
    fun applyManualAdjustment(
        adjustedLiters: Double,
        observedGaugePoints: Int,
        odometerKm: Double,
        reason: String,
        confidence: EventConfidence = EventConfidence.HIGH,
        @Suppress("UNUSED_PARAMETER") timestamp: Long = System.currentTimeMillis()
    ) {
        ensureCapacityConsistency()
        val safeNominal = FuelMath.roundFuel(adjustedLiters.coerceIn(0.0, maxCapacityLiters))
        val uncertainty = calculateUncertaintySpread(confidence, safeNominal)

        nominalFuelLiters = safeNominal
        conservativeFuelLiters = FuelMath.roundFuel((safeNominal - uncertainty).coerceAtLeast(0.0))
        upperFuelLiters = FuelMath.roundFuel((safeNominal + uncertainty).coerceAtMost(maxCapacityLiters))

        if (observedGaugePoints in 0..10) {
            currentGaugeBar = observedGaugePoints
        }

        if (odometerKm > 0.0) {
            lastReliableOdometerAnchorKm = odometerKm
        }
        lastReliableFuelAnchorLiters = safeNominal
        lastReliableAnchorTimestamp = timestamp

        confidenceScore = when (confidence) {
            EventConfidence.CONFIRMED -> 90
            EventConfidence.HIGH -> 80
            EventConfidence.MEDIUM -> 65
            EventConfidence.PROVISIONAL -> 45
            EventConfidence.LOW -> 30
        }
        currentExplanation = reason
    }

    /**
     * Computes the current snapshot with safe DTE range bounds.
     * In fuel reserve (F_nom < 7.0 L or F_nom / C_design < 0.15), applies a conservative
     * 0.85 safety multiplier so drivers are never stranded by overly optimistic predictions.
     */
    fun createSnapshot(estimatedMileageKmpl: Double = 18.0): FuelStateSnapshot {
        val safeMileage = estimatedMileageKmpl.coerceIn(5.0, 45.0)
        val safeDesign = designCapacityLiters.coerceAtLeast(10.0)
        val fuelFraction = if (safeDesign > 0.0) nominalFuelLiters / safeDesign else 0.0
        val isReserve = nominalFuelLiters < 7.0 || fuelFraction < 0.15
        val reserveMultiplier = if (isReserve) 0.85 else 1.0

        val nominalRange = FuelMath.roundDistance(nominalFuelLiters * safeMileage * reserveMultiplier)
        val minRange = FuelMath.roundDistance(conservativeFuelLiters * safeMileage * 0.90 * reserveMultiplier)
        val maxRange = FuelMath.roundDistance(upperFuelLiters * safeMileage * 1.05 * reserveMultiplier)

        return FuelStateSnapshot(
            nominalFuelLiters = nominalFuelLiters,
            conservativeFuelLiters = conservativeFuelLiters,
            upperFuelLiters = upperFuelLiters,
            current10PointBar = currentGaugeBar,
            estimatedRangeKm = nominalRange,
            rangeMinKm = minRange,
            rangeMaxKm = maxRange,
            estimatedMileageKmpl = safeMileage,
            confidencePercent = confidenceScore,
            explanation = currentExplanation
        )
    }

    fun toSnapshot(estimatedMileageKmpl: Double = 18.0): FuelStateSnapshot = createSnapshot(estimatedMileageKmpl)

    fun restoreFromSnapshot(snapshot: FuelStateSnapshot, odoKm: Double) {
        nominalFuelLiters = FuelMath.roundFuel(snapshot.nominalFuelLiters)
        conservativeFuelLiters = FuelMath.roundFuel(snapshot.conservativeFuelLiters)
        upperFuelLiters = FuelMath.roundFuel(snapshot.upperFuelLiters)
        currentGaugeBar = snapshot.current10PointBar
        confidenceScore = snapshot.confidencePercent
        currentExplanation = snapshot.explanation
        if (odoKm > 0.0) {
            lastReliableOdometerAnchorKm = odoKm
        }
    }

    private fun calculateUncertaintySpread(confidence: EventConfidence, baseVolume: Double): Double {
        val percentage = when (confidence) {
            EventConfidence.CONFIRMED -> 0.03  // ±3%
            EventConfidence.HIGH -> 0.06       // ±6%
            EventConfidence.MEDIUM -> 0.12     // ±12%
            EventConfidence.PROVISIONAL -> 0.22 // ±22%
            EventConfidence.LOW -> 0.35        // ±35%
        }
        return FuelMath.roundFuel(max(0.5, baseVolume * percentage))
    }
}
