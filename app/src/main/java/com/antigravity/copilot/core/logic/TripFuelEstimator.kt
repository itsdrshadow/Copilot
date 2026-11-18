package com.antigravity.copilot.core.logic

import kotlin.math.max

/**
 * Current-trip fuel ESTIMATE (Phase 5). Never feeds back into the tank level.
 *
 * Each moving step burns `km / learned_kmpl × load`, where the driving-load factor grows with
 * deviation from an efficient steady speed and with positive acceleration (braking wastes the
 * kinetic energy put in by the preceding acceleration, so it is captured through acceleration).
 * Idle steps (speed < [Config.IDLE_SPEED_KMH]) burn nothing — there is no idle model.
 */
class TripFuelEstimator {
    var litres: Double = 0.0
        private set
    var km: Double = 0.0
        private set
    /** Highest rolling km/L over windows of at least [BEST_WINDOW_KM]. */
    var bestKmpl: Double = 0.0
        private set

    private var windowKm = 0.0
    private var windowL = 0.0

    /**
     * Per-car calibration against refill truth (TripFuelCalibration): scales the fixed load-curve
     * normaliser so trip estimates add up to what the pump actually refilled.
     */
    var calibration: Double = 1.0
        set(value) { field = if (value.isFinite() && value > 0.0) value else 1.0 }

    fun restore(km: Double, litres: Double, bestKmpl: Double) {
        this.km = km; this.litres = litres; this.bestKmpl = bestKmpl
        windowKm = 0.0; windowL = 0.0
    }

    fun reset() = restore(0.0, 0.0, 0.0)

    /** Average km/L for the trip so far (0 until something was driven). */
    val tripKmpl: Double get() = if (litres > 1e-6) km / litres else 0.0

    /** @return litres attributed to this step. */
    fun onStep(stepKm: Double, speedKmh: Double, accelMps2: Double, learnedKmpl: Double): Double {
        if (stepKm <= 0.0 || speedKmh < Config.IDLE_SPEED_KMH || learnedKmpl <= 0.0) return 0.0
        val l = stepKm / learnedKmpl * loadFactor(speedKmh, accelMps2) * calibration
        litres += l
        km += stepKm
        windowKm += stepKm
        windowL += l
        if (windowKm >= BEST_WINDOW_KM) {
            bestKmpl = max(bestKmpl, windowKm / windowL)
            windowKm = 0.0
            windowL = 0.0
        }
        return l
    }

    companion object {
        const val BEST_WINDOW_KM = 0.5

        /** Relative fuel per km: 1.0 at the optimal steady speed. */
        fun loadFactor(speedKmh: Double, accelMps2: Double): Double {
            val opt = Config.TRIP_OPTIMAL_SPEED_KMH
            val low = max(0.0, opt - speedKmh) / opt
            val high = max(0.0, speedKmh - opt) / opt
            val speedLoad = 1.0 + Config.TRIP_SPEED_LOAD_LOW * low * low * 4.0 + Config.TRIP_SPEED_LOAD_HIGH * high * high * 4.0
            val accelLoad = 1.0 + Config.TRIP_ACCEL_LOAD_PER_MPS2 * max(0.0, accelMps2)
            // Normalised so that typical mixed driving averages ~1.0 relative to learned mileage.
            return speedLoad * accelLoad / NORMALISER
        }

        /** Typical mixed city/highway load, so the learned (whole-tank) mileage stays unbiased. */
        const val NORMALISER = 1.15
    }
}
