package com.antigravity.copilot.core.model

/**
 * Automotive vehicle operational state.
 * Strictly decoupled from GPS trip geometry calculations.
 */
enum class VehicleState(val displayName: String) {
    /** Vehicle is actively in motion (speed above threshold). */
    MOVING("Moving"),

    /** Engine is running but vehicle is stationary (speed below threshold). */
    IDLE("Idle"),

    /** Engine is confirmed switched off (hardware power cut, ACC ignition off, or system shutdown). */
    ENGINE_OFF("Engine Off"),

    /** Unobserved / unconfirmed gap (e.g. tracking gap, app restart without shutdown signal). */
    UNKNOWN("Unknown / Unobserved")
}

/**
 * Level of confidence for a detected vehicle state or transition event.
 */
enum class StateConfidence {
    /** Confirmed via direct hardware/system evidence (12V ACC / USB disconnect, OS shutdown, ignition cut). */
    CONFIRMED,

    /** Inferred from telemetry sensors (GPS speed threshold, Doppler delta, stationary timeout). */
    INFERRED
}

/**
 * Extensible data model capturing complete vehicle state metrics and timeline duration counters.
 */
data class VehicleStateMetrics(
    val currentState: VehicleState = VehicleState.IDLE,
    val stateSinceTimestamp: Long = System.currentTimeMillis(),
    val movingDurationMs: Long = 0L,
    val idleDurationMs: Long = 0L,
    val engineOffDurationMs: Long = 0L,
    val unknownDurationMs: Long = 0L,
    val lastEngineOffTimestamp: Long? = null,
    val lastEngineOffReason: String? = null,
    val lastConfirmedEngineOffDurationMs: Long = 0L
) {
    val idleMinutes: Long get() = idleDurationMs / 60000L
}
