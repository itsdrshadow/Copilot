package com.antigravity.copilot.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.antigravity.copilot.core.fuel.intelligence.*

@Entity(
    tableName = "fuel_ledger_events",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["eventType"]),
        Index(value = ["odometerKm"]),
        Index(value = ["parentId"])
    ]
)
data class FuelLedgerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val correlationId: String = "",
    val parentId: Long? = null,
    val eventType: String,
    val timestamp: Long = System.currentTimeMillis(),
    val odometerKm: Double = 0.0,
    val fuelQuantityLiters: Double = 0.0,
    val fuelGaugePoints: Int = -1,
    val pricePerLiter: Double = 0.0,
    val totalAmount: Double = 0.0,
    val source: String = EventSource.SYSTEM.name,
    val confidence: String = EventConfidence.CONFIRMED.name,
    val locationName: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val nominalFuelLiters: Double = 0.0,
    val conservativeFuelLiters: Double = 0.0,
    val upperFuelLiters: Double = 0.0,
    val estimatedRangeKm: Double = 0.0,
    val estimatedMileageKmpl: Double = 18.0,
    val confidencePercent: Int = 50,
    val reasonNote: String = "",
    val calculationMetadataJson: String = ""
) {
    fun toDomainEvent(): FuelLedgerEvent {
        return FuelLedgerEvent(
            id = id,
            correlationId = correlationId,
            parentId = parentId,
            eventType = try { FuelEventType.valueOf(eventType) } catch (_: Exception) { FuelEventType.REFUEL },
            timestamp = timestamp,
            odometerKm = odometerKm,
            fuelQuantityLiters = fuelQuantityLiters,
            fuelGaugePoints = fuelGaugePoints,
            pricePerLiter = pricePerLiter,
            totalAmount = totalAmount,
            source = try { EventSource.valueOf(source) } catch (_: Exception) { EventSource.SYSTEM },
            confidence = try { EventConfidence.valueOf(confidence) } catch (_: Exception) { EventConfidence.CONFIRMED },
            locationName = locationName,
            latitude = latitude,
            longitude = longitude,
            previousState = null,
            resultingState = FuelStateSnapshot(
                nominalFuelLiters = nominalFuelLiters,
                conservativeFuelLiters = conservativeFuelLiters,
                upperFuelLiters = upperFuelLiters,
                current10PointBar = fuelGaugePoints,
                estimatedRangeKm = estimatedRangeKm,
                estimatedMileageKmpl = estimatedMileageKmpl,
                confidencePercent = confidencePercent,
                explanation = reasonNote
            ),
            reasonNote = reasonNote,
            calculationMetadataJson = calculationMetadataJson
        )
    }

    companion object {
        fun fromDomainEvent(event: FuelLedgerEvent): FuelLedgerEntity {
            val state = event.resultingState ?: FuelStateSnapshot()
            return FuelLedgerEntity(
                id = event.id,
                correlationId = event.correlationId,
                parentId = event.parentId,
                eventType = event.eventType.name,
                timestamp = event.timestamp,
                odometerKm = event.odometerKm,
                fuelQuantityLiters = event.fuelQuantityLiters,
                fuelGaugePoints = event.fuelGaugePoints,
                pricePerLiter = event.pricePerLiter,
                totalAmount = event.totalAmount,
                source = event.source.name,
                confidence = event.confidence.name,
                locationName = event.locationName,
                latitude = event.latitude,
                longitude = event.longitude,
                nominalFuelLiters = state.nominalFuelLiters,
                conservativeFuelLiters = state.conservativeFuelLiters,
                upperFuelLiters = state.upperFuelLiters,
                estimatedRangeKm = state.estimatedRangeKm,
                estimatedMileageKmpl = state.estimatedMileageKmpl,
                confidencePercent = state.confidencePercent,
                reasonNote = event.reasonNote,
                calculationMetadataJson = event.calculationMetadataJson
            )
        }
    }
}
