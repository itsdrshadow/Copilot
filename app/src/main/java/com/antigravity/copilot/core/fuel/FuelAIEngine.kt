package com.antigravity.copilot.core.fuel

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.abs

/**
 * FuelAIEngine: real-time kinematic fuel-burn model for the live trip.
 *
 * Converts each GPS movement slice into litres burned using a speed-efficiency curve, an
 * acceleration load penalty and EMA-smoothed instantaneous mileage. Long-term mileage learning
 * lives in [com.antigravity.copilot.core.logic.fuel.FuelModel]; this engine only handles the live slice.
 *
 * Guards: GPS teleport rejection (> 300 km/h implied), stationary = zero burn, NaN/Infinity clamps,
 * and never burning more than what is left in the tank.
 */
class FuelAIEngine(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("fuel_ai_model_prefs", Context.MODE_PRIVATE)

    // Learned parameters: the base is the fuel model's learned km/L (pushed by FuelRepository on
    // every live slice), the aggressiveness index is 1 / the per-car trip-estimate calibration
    // (refill truth ÷ predicted), so the live HUD and the trip estimate share one model.
    var learnedBaseMileageKmpl: Double = prefs.getFloat("learned_base_mileage", 18.0f).toDouble()
        private set

    var driverAggressivenessIndex: Double = prefs.getFloat("driver_aggressiveness", 1.0f).toDouble()
        private set // 0.62 (heavy) to 1.67 (light), from TripFuelCalibration bounds

    /** Base km/L from the learned fuel model and the refill-calibrated load factor (F2). */
    fun updateLearnedBase(learnedKmpl: Double, tripCalibration: Double) {
        if (learnedKmpl.isFinite() && learnedKmpl in MIN_VALID_MILEAGE..MAX_VALID_MILEAGE && learnedKmpl != learnedBaseMileageKmpl) {
            learnedBaseMileageKmpl = learnedKmpl
            prefs.edit().putFloat("learned_base_mileage", learnedKmpl.toFloat()).apply()
        }
        val index = if (tripCalibration.isFinite() && tripCalibration > 0.0) (1.0 / tripCalibration).coerceIn(0.62, 1.67) else 1.0
        if (index != driverAggressivenessIndex) {
            driverAggressivenessIndex = index
            prefs.edit().putFloat("driver_aggressiveness", index.toFloat()).apply()
        }
    }

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
    }

    data class LiveFuelConsumptionResult(
        val litersConsumed: Double,
        val liveInstantMileageKmpl: Double,
        val drivingStyleTag: String,
        val isIdleBurn: Boolean
    )

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

        // ==================== 1. STATIONARY CASE ====================
        // No idle fuel-burn model: ignition/infotainment is often on with the engine off, so a
        // stationary vehicle consumes nothing in this model (spec: Phase 1, "NO idle fuel-burn").
        if (safeSpeed < 1.5 || deltaKm < 0.001) {
            return LiveFuelConsumptionResult(
                litersConsumed = 0.0,
                liveInstantMileageKmpl = 0.0,
                drivingStyleTag = "STATIONARY",
                isIdleBurn = false
            )
        }

        // ==================== 2. KINEMATIC ACCELERATION ESTIMATION ====================
        // dt is the slice's own duration (fix-time based); the wall clock of the call is irrelevant
        // and jumps with RTC / NTP corrections.
        val accelerationMps2 = if (lastSpeedTimestamp > 0L) {
            val deltaV_mps = ((safeSpeed - lastEmittedSpeedKmh) * 1000.0) / 3600.0
            (deltaV_mps / deltaSeconds.coerceAtLeast(0.2)).coerceIn(-8.0, 8.0) // ±8 m/s² = extreme but possible
        } else {
            0.0
        }
        lastEmittedSpeedKmh = safeSpeed
        lastSpeedTimestamp = 1L

        // ==================== 3. LOAD CURVE (shared with the trip estimator) ====================
        // Same speed / acceleration load as TripFuelEstimator, so the live km/L and the trip litres
        // agree (the old curves pulled in opposite directions on the highway). Coasting / engine
        // braking keeps a fuel-cut bonus for the HUD.
        val load = com.antigravity.copilot.core.logic.TripFuelEstimator.loadFactor(safeSpeed, accelerationMps2)
        val coastBonus = when {
            accelerationMps2 >= -0.6 -> 1.0
            accelerationMps2 >= -1.5 -> 3.5 // fuel cut-off coasting
            else -> 4.5 // hard engine braking
        }

        // Dynamic Instantaneous Mileage (km/L)
        val rawInstantMileage = learnedBaseMileageKmpl / load * coastBonus * driverAggressivenessIndex

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
     * Reset all learned AI model state. Called when user resets all data.
     */
    fun resetLearnedModel() {
        learnedBaseMileageKmpl = 18.0
        driverAggressivenessIndex = 1.0
        smoothedLiveMileage = 18.0
        lastEmittedSpeedKmh = 0.0
        lastSpeedTimestamp = 0L

        prefs.edit().clear().apply()
    }

    // ==================== PRIVATE HELPER FUNCTIONS ====================

    /**
     * Safe rounding to 1 decimal place with NaN/Infinity protection.
     */
    private fun roundTo1Decimal(value: Double): Double {
        if (!value.isFinite()) return 0.0
        return Math.round(value * 10.0) / 10.0
    }
}
