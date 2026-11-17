package com.antigravity.copilot.core.logic

import kotlin.math.abs
import kotlin.math.sqrt

enum class DrivingState { IDLE, ACCELERATING, CRUISING, BRAKING }

/**
 * Estimates the vehicle's forward axis in the phone's frame without knowing how the phone is
 * mounted: the linear-acceleration vector (gravity already removed by TYPE_LINEAR_ACCELERATION)
 * is correlated with the GPS speed change. Lateral (turns) and vertical (bumps) components are
 * uncorrelated with dv/dt and average out, leaving the longitudinal axis (with the right sign).
 */
class LongitudinalAccelEstimator(private val minEvidence: Double = 6.0) {
    private val axis = DoubleArray(3)
    private var evidence = 0.0
    private val sum = DoubleArray(3)
    private var count = 0

    /** Raw linear-acceleration sample in the device frame (m/s²). */
    fun onSensor(ax: Double, ay: Double, az: Double) {
        sum[0] += ax; sum[1] += ay; sum[2] += az; count++
    }

    /**
     * Called at every GPS fix with the GPS longitudinal acceleration (dv/dt, m/s²).
     * Returns the sensor's longitudinal acceleration over the interval, or null until the axis
     * has been learned (or if no sensor samples arrived).
     */
    fun onGps(gpsAccelMps2: Double): Double? {
        if (count == 0) return null
        val mx = sum[0] / count
        val my = sum[1] / count
        val mz = sum[2] / count
        sum.fill(0.0); count = 0
        // Only learn from clear speed changes so noise does not dominate the axis estimate.
        if (abs(gpsAccelMps2) >= 0.4) {
            axis[0] += mx * gpsAccelMps2
            axis[1] += my * gpsAccelMps2
            axis[2] += mz * gpsAccelMps2
            evidence += abs(gpsAccelMps2)
        }
        val n = sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2])
        if (evidence < minEvidence || n < 1e-6) return null
        return (mx * axis[0] + my * axis[1] + mz * axis[2]) / n
    }

    val isCalibrated: Boolean
        get() = evidence >= minEvidence

    fun reset() {
        axis.fill(0.0); sum.fill(0.0); evidence = 0.0; count = 0
    }
}

/**
 * IDLE / ACCELERATING / CRUISING / BRAKING from speed and longitudinal acceleration.
 * Acceleration = sensor (if calibrated) fused with GPS dv/dt, then low-pass filtered.
 */
class DrivingStateClassifier(
    private val accelThreshold: Double = Config.ACCEL_THRESHOLD_MPS2,
    private val brakeThreshold: Double = Config.BRAKE_THRESHOLD_MPS2,
    private val cruiseMaxAbs: Double = Config.CRUISE_MAX_ABS_ACCEL_MPS2,
    private val cruiseMinSpeedKmh: Double = Config.CRUISE_MIN_SPEED_KMH,
    private val cruiseHoldMs: Long = Config.CRUISE_HOLD_MS,
    private val idleSpeedKmh: Double = Config.IDLE_SPEED_KMH,
    private val sensorWeight: Double = Config.ACCEL_SENSOR_WEIGHT,
    private val smoothing: Double = Config.ACCEL_SMOOTHING_ALPHA,
    private val rearmMs: Long = Config.EVENT_REARM_MS
) {
    var state: DrivingState = DrivingState.IDLE
        private set
    var accelMps2: Double = 0.0
        private set
    var brakeEvents: Int = 0
        private set
    var accelEvents: Int = 0
        private set

    private var lastSpeedKmh = Double.NaN
    private var lastTimeMs = Long.MIN_VALUE
    private var steadySinceMs = Long.MIN_VALUE
    private var lastBrakeEventMs = Long.MIN_VALUE
    private var inBrakeExcursion = false
    private var inAccelExcursion = false
    private var lastAccelEventMs = Long.MIN_VALUE

    fun reset() {
        state = DrivingState.IDLE; accelMps2 = 0.0; brakeEvents = 0; accelEvents = 0
        lastSpeedKmh = Double.NaN; lastTimeMs = Long.MIN_VALUE; steadySinceMs = Long.MIN_VALUE
        lastBrakeEventMs = Long.MIN_VALUE; lastAccelEventMs = Long.MIN_VALUE
        inBrakeExcursion = false; inAccelExcursion = false
    }

    fun restoreCounts(brakes: Int, accels: Int) {
        brakeEvents = brakes; accelEvents = accels
    }

    /** GPS dv/dt for the step that ends at [timeMs]; null for the first sample. */
    fun gpsAccel(speedKmh: Double, timeMs: Long): Double? {
        if (lastSpeedKmh.isNaN() || lastTimeMs == Long.MIN_VALUE) return null
        val dt = (timeMs - lastTimeMs) / 1000.0
        if (dt <= 0.05 || dt > 10.0) return null
        return ((speedKmh - lastSpeedKmh) / 3.6) / dt
    }

    /**
     * @param sensorLongitudinal sensor longitudinal acceleration (m/s²) or null if unavailable.
     */
    fun onSample(timeMs: Long, speedKmh: Double, sensorLongitudinal: Double? = null): DrivingState {
        val gps = gpsAccel(speedKmh, timeMs)
        val dtOk = gps != null
        lastSpeedKmh = speedKmh
        lastTimeMs = timeMs
        val raw = when {
            sensorLongitudinal != null && gps != null -> sensorWeight * sensorLongitudinal + (1 - sensorWeight) * gps
            sensorLongitudinal != null -> sensorLongitudinal
            gps != null -> gps
            else -> null
        }
        if (raw != null) accelMps2 = smoothing * raw + (1 - smoothing) * accelMps2

        val newState = when {
            speedKmh < idleSpeedKmh -> DrivingState.IDLE
            accelMps2 > accelThreshold -> DrivingState.ACCELERATING
            accelMps2 < brakeThreshold -> DrivingState.BRAKING
            else -> {
                val steady = abs(accelMps2) < cruiseMaxAbs && speedKmh > cruiseMinSpeedKmh
                if (steady) {
                    if (steadySinceMs == Long.MIN_VALUE) steadySinceMs = timeMs
                } else {
                    steadySinceMs = Long.MIN_VALUE
                }
                when {
                    steady && timeMs - steadySinceMs >= cruiseHoldMs -> DrivingState.CRUISING
                    state == DrivingState.IDLE -> if (dtOk && accelMps2 > 0) DrivingState.ACCELERATING else DrivingState.CRUISING
                    else -> state
                }
            }
        }
        if (newState != DrivingState.CRUISING && newState != state) steadySinceMs = Long.MIN_VALUE
        countEvent(newState, timeMs)
        state = newState
        return state
    }

    /** Edge-triggered: one event per excursion beyond a threshold, re-armed after [rearmMs]. */
    private fun countEvent(newState: DrivingState, timeMs: Long) {
        val beyondBrake = newState == DrivingState.BRAKING && accelMps2 < brakeThreshold
        val beyondAccel = newState == DrivingState.ACCELERATING && accelMps2 > accelThreshold
        if (beyondBrake && !inBrakeExcursion &&
            (lastBrakeEventMs == Long.MIN_VALUE || timeMs - lastBrakeEventMs >= rearmMs)
        ) {
            brakeEvents++; lastBrakeEventMs = timeMs
        }
        if (beyondAccel && !inAccelExcursion &&
            (lastAccelEventMs == Long.MIN_VALUE || timeMs - lastAccelEventMs >= rearmMs)
        ) {
            accelEvents++; lastAccelEventMs = timeMs
        }
        inBrakeExcursion = beyondBrake
        inAccelExcursion = beyondAccel
    }
}
