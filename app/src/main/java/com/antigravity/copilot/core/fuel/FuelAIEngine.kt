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

    // Learned parameters
    var learnedBaseMileageKmpl: Double = prefs.getFloat("learned_base_mileage", 18.0f).toDouble()
        private set

    var driverAggressivenessIndex: Double = prefs.getFloat("driver_aggressiveness", 1.0f).toDouble()
        private set // 0.70 (aggressive) to 1.25 (hyper-miler)

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
