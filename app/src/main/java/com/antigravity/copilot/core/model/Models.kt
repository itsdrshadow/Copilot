package com.antigravity.copilot.core.model

enum class TripStatus {
    IDLE,
    MOVEMENT_CANDIDATE,
    ACTIVE_TRIP,
    TEMPORARY_STOP,
    STOP_CANDIDATE,
    COMPLETED,
    INTERRUPTED
}

data class TripPoint(
    val id: Long = 0,
    val tripId: Long = 0,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 0.0,
    val speedKmh: Float = 0f,
    val bearing: Float = 0f,
    val accuracy: Float = 0f,
    val timestamp: Long = System.currentTimeMillis(),
    /** Live driving-state label (IDLE / ACCELERATING / CRUISING / BRAKING); not persisted. */
    val drivingState: String = "",
    /**
     * Monotonic receive time (SystemClock.elapsedRealtime() domain, ms); not persisted. 0 = unknown.
     * Use this, never [timestamp], for freshness / duration checks: the head-unit RTC can be wrong.
     */
    val elapsedMs: Long = 0L
)

data class TripSummary(
    val id: Long = 0,
    val startTime: Long,
    val endTime: Long = 0,
    val distanceMeters: Double = 0.0,
    val drivingDurationMs: Long = 0,
    val stationaryDurationMs: Long = 0,
    val avgSpeedKmh: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,
    val currentAltitudeMeters: Double = 0.0,
    val currentBearing: Float = 0f,
    val currentGForce: Float = 0f,
    val maxAccelMss: Float = 0f,
    val maxDecelMss: Float = 0f,
    val estimatedFuelConsumedLiters: Double = 0.0,
    val estimatedTripCost: Double = 0.0,
    val calculatedMileageKmpl: Double = 0.0,
    val driverScore: Int = 100,
    val harshBrakeCount: Int = 0,
    val rapidAccelCount: Int = 0,
    val overspeedCount: Int = 0,
    /** Classifier events: entries into BRAKING (< -1.5 m/s²) / ACCELERATING (> +1.2 m/s²). */
    val brakeCount: Int = 0,
    val accelCount: Int = 0,
    /** Best (highest) smoothed instantaneous km/L seen while moving. */
    val bestMileageKmpl: Double = 0.0,
    val startLocationName: String = "",
    val endLocationName: String = "",
    val status: TripStatus = TripStatus.IDLE
) {
    val distanceKm: Double get() = distanceMeters / 1000.0
    val drivingMinutes: Long get() = drivingDurationMs / 60000
    val idleMinutes: Long get() = stationaryDurationMs / 60000
    val isQualifyingTrip: Boolean get() = distanceMeters >= 1000.0 // >= 1 km

    companion object {
        fun empty(): TripSummary = TripSummary(
            id = 0,
            startTime = 0L,
            status = TripStatus.IDLE
        )
    }
}

enum class MileageLearningState {
    INITIAL_BASELINE,
    LEARNING,
    LEARNED,
    INSUFFICIENT_DATA
}

data class FuelState(
    val designCapacityLiters: Double = 48.0,
    val maxCapacityLiters: Double = 48.0,
    val tankCapacityLiters: Double = 48.0,
    val remainingLiters: Double = 48.0,
    val conservativeFuelLiters: Double = 48.0,
    val upperFuelLiters: Double = 48.0,
    val currentMileageKmpl: Double = 18.0,
    val expectedMileageKmpl: Double = 18.0,
    val cityMileageKmpl: Double = 14.8,
    val highwayMileageKmpl: Double = 20.7,
    val isRealMileageDerived: Boolean = false,
    val mileageLearningState: MileageLearningState = MileageLearningState.LEARNING,
    val isTankCalibrated: Boolean = false,
    val hasOverfill: Boolean = false,
    val overfillLiters: Double = 0.0,
    val remainingRangeKm: Double = 720.0,
    val rangeMinKm: Double = 650.0,
    val rangeMaxKm: Double = 790.0,
    val fuelPercentage: Int = 100,
    val tripFuelConsumedLiters: Double = 0.0,
    val tripFuelCost: Double = 0.0,
    val fuelPricePerLiter: Double = 96.5,
    val instantConsumptionRateL100Km: Double = 0.0,
    val midBarsCount: Int = 10,
    val currentMidBar: Int = 10,
    val isLowFuelWarning: Boolean = false,
    val fuelEfficiencyTier: String = "OPTIMAL", // ECO, OPTIMAL, AGGRESSIVE
    val odometerKm: Double = 0.0,
    val liveInstantMileageKmpl: Double = 18.0,
    val aiConfidencePercent: Int = 85,
    val aiDrivingStyle: String = "OPTIMAL DRIVE",
    val confidenceLabel: String = "CONFIRMED",
    val explanation: String = "",
    /** Learned usable capacity (separate from the OEM design capacity). */
    val actualCapacityLiters: Double = 48.0,
    /** Continuous gauge position 0..10 for the smooth bar fill (never shown as a number). */
    val gaugeFillPoints: Double = 10.0,
    /** 0..1 confidence of the learned mileage. */
    val mileageConfidence: Double = 0.0,
    /** Unclamped model estimate; negative = "debt" (consumption beyond the tank). */
    val rawFuelLiters: Double = 48.0,
    /** Learned gauge curve: single source for litres <-> bars on every screen (null = linear fallback). */
    val gaugeCurve: com.antigravity.copilot.core.logic.fuel.GaugeCurve? = null,
    val topBufferLiters: Double = 3.0,
    val bottomBufferLiters: Double = 5.0,
    /** Sum of provisional unlogged-consumption entries awaiting the next refill. */
    val unloggedDebtLiters: Double = 0.0,
    /** Quiet, non-blocking notice from the fuel model (null = none). */
    val fuelNotice: String? = null,
    /** Fresh install: no refill / gauge reading yet, litres are a placeholder (show "tank unknown"). */
    val isTankUnknown: Boolean = false
)

@androidx.compose.runtime.Immutable
data class AppDrawerItem(
    val packageName: String,
    val activityName: String,
    val label: String,
    val iconBitmap: android.graphics.Bitmap? = null
)
