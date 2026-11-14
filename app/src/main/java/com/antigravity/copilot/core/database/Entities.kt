package com.antigravity.copilot.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.antigravity.copilot.core.model.TripStatus

@Entity(tableName = "vehicles")
data class VehicleEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 1,
    val name: String = "My Car",
    val fuelType: String = "Petrol",
    val tankCapacityLiters: Double = 48.0,
    val initialFuelLiters: Double = 40.0,
    val midLevelsCount: Int = 10,
    val defaultMileageKmpl: Double = 14.5,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "trips",
    indices = [
        Index(value = ["status"]),
        Index(value = ["startTime"]),
        Index(value = ["isQualifying", "startTime"])
    ]
)
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: Int = 1,
    val startTime: Long,
    val endTime: Long = 0,
    val distanceMeters: Double = 0.0,
    val drivingDurationMs: Long = 0,
    val stationaryDurationMs: Long = 0,
    val avgSpeedKmh: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,
    val estimatedFuelLiters: Double = 0.0,
    val calculatedMileageKmpl: Double = 0.0,
    val driverScore: Int = 100,
    val startLocationName: String = "",
    val endLocationName: String = "",
    val status: String = TripStatus.ACTIVE_TRIP.name,
    val isQualifying: Boolean = false
)

@Entity(
    tableName = "trip_points",
    indices = [Index(value = ["tripId"]), Index(value = ["timestamp"])],
    foreignKeys = [
        ForeignKey(
            entity = TripEntity::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class TripPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 0.0,
    val speedKmh: Float,
    val bearing: Float,
    val accuracy: Float,
    val timestamp: Long
)

@Entity(
    tableName = "fuel_events",
    indices = [Index(value = ["vehicleId"]), Index(value = ["timestamp"])]
)
data class FuelEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: Int = 1,
    val timestamp: Long = System.currentTimeMillis(),
    val eventType: String, // REFUEL, MID_OBSERVED, CONSUMPTION, RECONCILIATION
    val liters: Double,
    val midLevelObserved: Int = -1,
    val pricePerLiter: Double = 0.0,
    val totalCost: Double = 0.0,
    val calculatedBalanceLiters: Double,
    val notes: String = "",
    val odometerKm: Double = 0.0,
    val calculatedMileageKmpl: Double = 0.0,
    val locationName: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0
)

@Entity(
    tableName = "vehicle_state_transitions",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["toState"])
    ]
)
data class VehicleStateTransitionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromState: String,
    val toState: String,
    val timestamp: Long = System.currentTimeMillis(),
    val confidence: String, // CONFIRMED or INFERRED
    val triggerReason: String, // POWER_DISCONNECTED, SYSTEM_SHUTDOWN, SPEED_ABOVE_THRESHOLD, VEHICLE_STOPPED, STARTUP_RECOVERY
    val tripId: Long? = null,
    val extraMetadata: String? = null
)

@Entity(
    tableName = "trip_geofences",
    indices = [
        Index(value = ["isEnabled"]),
        Index(value = ["name"])
    ]
)
data class TripGeofenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float = 200f,
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

