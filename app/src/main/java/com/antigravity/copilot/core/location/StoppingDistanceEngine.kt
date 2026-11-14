package com.antigravity.copilot.core.location

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Road surface condition with friction coefficient (mu).
 */
enum class RoadSurfaceCondition(val label: String, val frictionCoefficient: Float) {
    DRY("Dry Asphalt", 0.72f),
    WET("Wet Asphalt", 0.45f),
    ICY("Icy / Snow", 0.20f)
}

/**
 * Result model for total stopping distance estimation.
 */
data class StoppingDistanceEstimate(
    val totalDistanceMeters: Float,
    val reactionDistanceMeters: Float,
    val brakingDistanceMeters: Float,
    val speedKmh: Float,
    val reactionTimeSeconds: Float,
    val roadCondition: RoadSurfaceCondition,
    val isReliableSpeed: Boolean
) {
    val formattedTotalMeters: String
        get() = if (totalDistanceMeters <= 0.5f) "0 m" else "~${totalDistanceMeters.roundToInt()} m"
}

/**
 * StoppingDistanceEngine:
 * Physics-based stopping distance estimator combining:
 * 1. Perception-Reaction Distance: d_r = v * t_reaction
 * 2. Braking Deceleration Distance: d_b = v^2 / (2 * mu * g)
 * Total estimated stopping distance: d = d_r + d_b
 *
 * Clearly reported as a physical estimate, not a guaranteed stopping distance.
 */
object StoppingDistanceEngine {
    const val GRAVITY_ACCEL = 9.80665f // Standard gravitational acceleration (m/s²)
    const val DEFAULT_REACTION_TIME_SEC = 1.0f // Standard attentive driver reaction time
    const val MIN_SPEED_THRESHOLD_KMH = 1.5f // Stationary filter threshold

    /**
     * Computes the estimated total stopping distance in metres.
     */
    fun calculate(
        speedKmh: Float,
        roadCondition: RoadSurfaceCondition = RoadSurfaceCondition.DRY,
        reactionTimeSec: Float = DEFAULT_REACTION_TIME_SEC,
        isGpsValid: Boolean = true
    ): StoppingDistanceEstimate {
        if (!isGpsValid || speedKmh < MIN_SPEED_THRESHOLD_KMH) {
            return StoppingDistanceEstimate(
                totalDistanceMeters = 0f,
                reactionDistanceMeters = 0f,
                brakingDistanceMeters = 0f,
                speedKmh = 0f,
                reactionTimeSeconds = reactionTimeSec,
                roadCondition = roadCondition,
                isReliableSpeed = isGpsValid
            )
        }

        val speedMs = speedKmh / 3.6f
        val reactionDistance = speedMs * reactionTimeSec
        val brakingDecel = roadCondition.frictionCoefficient * GRAVITY_ACCEL
        val brakingDistance = (speedMs.pow(2)) / (2f * brakingDecel)
        val totalDistance = reactionDistance + brakingDistance

        return StoppingDistanceEstimate(
            totalDistanceMeters = totalDistance,
            reactionDistanceMeters = reactionDistance,
            brakingDistanceMeters = brakingDistance,
            speedKmh = speedKmh,
            reactionTimeSeconds = reactionTimeSec,
            roadCondition = roadCondition,
            isReliableSpeed = true
        )
    }
}
