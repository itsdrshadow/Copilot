package com.antigravity.drivingreport.core.fuel.intelligence

/**
 * All supported immutable event types in the Fuel Intelligence Ledger.
 */
enum class FuelEventType {
    INITIAL_FUEL_BASELINE,
    REFUEL,
    TRIP_FUEL_CONSUMPTION,
    ODOMETER_UPDATE,
    FUEL_GAUGE_UPDATE,
    INFERRED_REFUEL,
    FUEL_CORRECTION,
    MILEAGE_ESTIMATE_UPDATE,
    MANUAL_FUEL_ADJUSTMENT
}

/**
 * Provenance of fuel ledger information.
 */
enum class EventSource {
    MANUAL,     // Directly confirmed by user
    TRIP,       // Continuous kinematic trip tracking
    INFERRED,   // Detected by intelligence engine (e.g. missed refuel)
    SYSTEM      // Baseline initialization, power cut recovery, system default
}

/**
 * Transparent confidence rating for events and estimates.
 */
enum class EventConfidence {
    CONFIRMED,    // High ground-truth certainty (e.g. direct receipt/manual pump entry)
    HIGH,         // Multiple concurring observations
    MEDIUM,       // Reasonable inference with minor variance
    PROVISIONAL,  // Inferred or unconfirmed; awaiting supporting evidence
    LOW           // High uncertainty or conflicting signals
}

/**
 * Complete snapshot of fuel intelligence state at the time of an event.
 */
data class FuelStateSnapshot(
    val nominalFuelLiters: Double = 0.0,
    val conservativeFuelLiters: Double = 0.0,
    val upperFuelLiters: Double = 0.0,
    val current10PointBar: Int = 0,
    val estimatedRangeKm: Double = 0.0,
    val rangeMinKm: Double = 0.0,
    val rangeMaxKm: Double = 0.0,
    val estimatedMileageKmpl: Double = 18.0,
    val confidencePercent: Int = 50,
    val explanation: String = ""
)

/**
 * Immutable, timestamped domain representation of a Fuel Ledger Entry.
 * Historical records are never destructively overwritten.
 * Corrections create new events referencing the original event ID via parentId.
 */
data class FuelLedgerEvent(
    val id: Long = 0,
    val correlationId: String = "",
    val parentId: Long? = null,
    val eventType: FuelEventType,
    val timestamp: Long = System.currentTimeMillis(),
    val odometerKm: Double = 0.0,
    val fuelQuantityLiters: Double = 0.0,
    val fuelGaugePoints: Int = -1,
    val pricePerLiter: Double = 0.0,
    val totalAmount: Double = 0.0,
    val source: EventSource = EventSource.SYSTEM,
    val confidence: EventConfidence = EventConfidence.CONFIRMED,
    val locationName: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val previousState: FuelStateSnapshot? = null,
    val resultingState: FuelStateSnapshot? = null,
    val reasonNote: String = "",
    val calculationMetadataJson: String = ""
)
