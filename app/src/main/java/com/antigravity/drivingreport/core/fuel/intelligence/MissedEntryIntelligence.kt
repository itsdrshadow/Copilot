package com.antigravity.drivingreport.core.fuel.intelligence

import kotlin.math.max
import kotlin.math.min

/**
 * Result of missed refuel detection analysis.
 */
data class MissedRefuelDetectionResult(
    val detected: Boolean = false,
    val plausibleMinLiters: Double = 0.0,
    val plausibleNominalLiters: Double = 0.0,
    val plausibleMaxLiters: Double = 0.0,
    val observedGaugePoints: Int = 0,
    val previousEstimatedFuelLiters: Double = 0.0,
    val confidence: EventConfidence = EventConfidence.PROVISIONAL,
    val odoWindowStartKm: Double = 0.0,
    val odoWindowEndKm: Double = 0.0,
    val promptMessage: String = "",
    val suggestedLedgerEvent: FuelLedgerEvent? = null
)

/**
 * Engine for detecting missed, unlogged refuels when observed gauge levels
 * significantly contradict physical consumption predictions.
 */
class MissedEntryIntelligence(
    val calibrationEngine: FuelGaugeCalibrationEngine,
    val thresholdLiters: Double = 4.0
) {

    /**
     * Inspects a newly submitted or observed gauge reading against the current fuel state.
     * If the observed fuel exceeds expected fuel by >= thresholdLiters, returns a MissedRefuelDetectionResult.
     */
    fun evaluateObservedGauge(
        observedGaugePoints: Int,
        estimator: FuelStateEstimator,
        currentOdometerKm: Double,
        timestamp: Long = System.currentTimeMillis()
    ): MissedRefuelDetectionResult {
        if (observedGaugePoints !in 0..10) {
            return MissedRefuelDetectionResult(detected = false)
        }

        val observedNominalLiters = calibrationEngine.getLitersForBar(observedGaugePoints)
        val expectedFuelNominal = estimator.nominalFuelLiters
        val expectedFuelConservative = estimator.conservativeFuelLiters
        val expectedFuelUpper = estimator.upperFuelLiters
        val maxCapacity = estimator.maxCapacityLiters

        val deltaNominal = observedNominalLiters - expectedFuelNominal

        // Detection threshold: unrecorded increase of at least thresholdLiters (default 4.0L)
        if (deltaNominal < thresholdLiters) {
            return MissedRefuelDetectionResult(
                detected = false,
                observedGaugePoints = observedGaugePoints,
                previousEstimatedFuelLiters = expectedFuelNominal
            )
        }

        // Calculate plausible fuel range based on gauge point interval
        // Gauge points are discrete bins, so a bar spans approximately from (bar-0.5) to (bar+0.5)
        val lowerBar = (observedGaugePoints - 1).coerceAtLeast(0)
        val upperBar = (observedGaugePoints + 1).coerceAtMost(10)

        val observedLowerBoundLiters = (calibrationEngine.getLitersForBar(lowerBar) + observedNominalLiters) / 2.0
        val observedUpperBoundLiters = (calibrationEngine.getLitersForBar(upperBar) + observedNominalLiters) / 2.0

        val rawMin = FuelMath.roundFuel(
            max(1.0, observedLowerBoundLiters - expectedFuelUpper)
        )
        val rawMax = FuelMath.roundFuel(
            min(maxCapacity - expectedFuelConservative, observedUpperBoundLiters - expectedFuelConservative)
        )
        val plausibleMin = min(rawMin, rawMax)
        val plausibleMax = max(rawMin, rawMax)
        val plausibleNominal = FuelMath.roundFuel(
            deltaNominal.coerceIn(plausibleMin, plausibleMax)
        )

        val odoStart = estimator.lastReliableOdometerAnchorKm
        val odoEnd = currentOdometerKm.coerceAtLeast(odoStart)

        val prompt = if (odoEnd > odoStart) {
            "Did you refuel ~${plausibleNominal} L between ${FuelMath.roundDistance(odoStart)} and ${FuelMath.roundDistance(odoEnd)} km?"
        } else {
            "Did you refuel ~${plausibleNominal} L recently?"
        }

        val inferredEvent = FuelLedgerEvent(
            correlationId = "inferred_${timestamp}",
            eventType = FuelEventType.INFERRED_REFUEL,
            timestamp = timestamp,
            odometerKm = odoEnd,
            fuelQuantityLiters = plausibleNominal,
            fuelGaugePoints = observedGaugePoints,
            pricePerLiter = 0.0, // Never fabricate fake receipt details
            totalAmount = 0.0,
            source = EventSource.INFERRED,
            confidence = EventConfidence.PROVISIONAL,
            previousState = estimator.toSnapshot(),
            resultingState = estimator.toSnapshot().copy(
                nominalFuelLiters = observedNominalLiters,
                explanation = "Inferred refuel ~${plausibleNominal} L ($observedGaugePoints/10 bars)"
            ),
            reasonNote = "Observed gauge at $observedGaugePoints/10 bars contradicted expected balance ($expectedFuelNominal L). Plausible range: [${plausibleMin}L - ${plausibleMax}L]",
            calculationMetadataJson = """{"plausibleMin":$plausibleMin,"plausibleMax":$plausibleMax,"deltaNominal":$deltaNominal}"""
        )

        return MissedRefuelDetectionResult(
            detected = true,
            plausibleMinLiters = plausibleMin,
            plausibleNominalLiters = plausibleNominal,
            plausibleMaxLiters = plausibleMax,
            observedGaugePoints = observedGaugePoints,
            previousEstimatedFuelLiters = expectedFuelNominal,
            confidence = EventConfidence.PROVISIONAL,
            odoWindowStartKm = odoStart,
            odoWindowEndKm = odoEnd,
            promptMessage = prompt,
            suggestedLedgerEvent = inferredEvent
        )
    }

    /**
     * Resolves an inferred refuel into a confirmed refuel event based on user input.
     */
    fun createConfirmedResolutionEvent(
        inferredEvent: FuelLedgerEvent,
        confirmedLiters: Double,
        pricePerLiter: Double,
        totalAmount: Double,
        odometerKm: Double = inferredEvent.odometerKm,
        fuelStation: String = "",
        timestamp: Long = System.currentTimeMillis()
    ): FuelLedgerEvent {
        return FuelLedgerEvent(
            correlationId = "resolve_${inferredEvent.correlationId}",
            parentId = inferredEvent.id.takeIf { it > 0 },
            eventType = FuelEventType.REFUEL,
            timestamp = timestamp,
            odometerKm = odometerKm,
            fuelQuantityLiters = FuelMath.roundFuel(confirmedLiters),
            fuelGaugePoints = inferredEvent.fuelGaugePoints,
            pricePerLiter = FuelMath.roundCurrency(pricePerLiter),
            totalAmount = FuelMath.roundCurrency(totalAmount),
            source = EventSource.MANUAL,
            confidence = EventConfidence.CONFIRMED,
            locationName = fuelStation,
            reasonNote = "Confirmed resolution of inferred refuel (${inferredEvent.fuelQuantityLiters} L provisional)",
            calculationMetadataJson = """{"resolvedInferredEventId":${inferredEvent.id}}"""
        )
    }
}
