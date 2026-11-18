package com.antigravity.copilot.core.logic.fuel

import com.antigravity.copilot.core.logic.Config

/**
 * Event-sourced rebuild of [FuelModelState] (audit E1 / E10). The fuel_events history is the
 * source of truth: whenever a past entry is edited or deleted (or a backup is restored) the model
 * is replayed from scratch over the corrected inputs, so the tank level, actual capacity, mileage
 * segments and gauge curve can never keep a mistyped value. The live per-fix path stays
 * incremental (same functions, applied one input at a time).
 */
object FuelReplay {

    sealed class Input {
        abstract val timeMs: Long
        abstract val odoKm: Double

        /** A logged refill (amount / price → litres), optional full-tank flag and gauge-after point. */
        data class Refill(
            override val timeMs: Long,
            override val odoKm: Double,
            val amountRs: Double,
            val pricePerL: Double,
            val fullTank: Boolean,
            val gaugeAfter: Double?
        ) : Input()

        /** The driver read the car's gauge ([point] 0..10, half points). */
        data class Gauge(override val timeMs: Long, override val odoKm: Double, val point: Double) : Input()

        /** A trip ended with the raw estimate at / below 0 (unlogged-consumption bookkeeping). */
        data class TripEnd(override val timeMs: Long, override val odoKm: Double, val tripId: Long, val km: Double) : Input()
    }

    /**
     * @param seed configuration-only start state (capacity, default km/L, hybrid lifetime, initial
     *   anchor litres); its anchor is moved to the first input's odometer so nothing is "burned"
     *   before the history starts.
     */
    fun replay(seed: FuelModelState, inputs: List<Input>): FuelModelState {
        val ordered = inputs.filter { it.odoKm.isFinite() && it.timeMs > 0L }.sortedBy { it.timeMs }
        if (ordered.isEmpty()) return seed
        val first = ordered.first()
        var s = seed.copy(
            observations = emptyList(),
            segments = emptyList(),
            lastFullOdoKm = -1.0,
            litresSinceLastFull = 0.0,
            lastGauge = null,
            confidencePenalty = 0.0,
            unlogged = emptyList(),
            maxRefillL = 0.0,
            mileageBias = 1.0,
            anchorOdoKm = first.odoKm,
            anchorTimeMs = first.timeMs
        )
        var prevOdo = first.odoKm
        for (input in ordered) {
            // An impossible jump (first real dashboard reading after an uncalibrated start) is an
            // odometer re-scale, not distance driven: shift the references like the live path does.
            if (FuelModel.isCalibrationJump(s, prevOdo, input.odoKm, firstCalibration = false, nowMs = input.timeMs)) {
                s = FuelModel.rebaseOdometer(s, input.odoKm - prevOdo)
            }
            s = apply(s, input)
            prevOdo = input.odoKm
        }
        return s.copy(tankUnknown = false)
    }

    /** One input, exactly as the live path applies it. */
    fun apply(s: FuelModelState, input: Input): FuelModelState = when (input) {
        is Input.Refill -> {
            val refill = RefillInput(
                timeMs = input.timeMs,
                amountRs = input.amountRs,
                pricePerL = input.pricePerL,
                odoKm = input.odoKm,
                fullTank = input.fullTank,
                gaugeAfter = input.gaugeAfter
            )
            if (refill.litres <= 0.0 || !refill.litres.isFinite()) s
            else {
                val gaugeLitres = input.gaugeAfter?.takeIf { it < Config.GAUGE_MAX_POINT }?.let { FuelModel.curve(s).litresAt(it) }
                UnloggedFuel.onRefill(s, refill, gaugeLitres).state
            }
        }
        is Input.Gauge -> FuelModel.onGaugeReading(s, input.point, input.odoKm, input.timeMs)
        is Input.TripEnd -> UnloggedFuel.onTripEnded(s, input.tripId, input.km, input.odoKm, input.timeMs).first
    }

    /** Parses the "[unlogged:<tripId>:<time>]" marker written into UNLOGGED_CONSUMPTION notes. */
    fun parseUnloggedMarker(notes: String): Pair<Long, Long>? {
        val m = Regex("""\[unlogged:(-?\d+):(\d+)]""").find(notes) ?: return null
        return m.groupValues[1].toLongOrNull()?.let { id -> m.groupValues[2].toLongOrNull()?.let { id to it } }
    }
}

/** Display thresholds that scale with the car instead of fixed litres / km/L (audit E8). */
object FuelDisplayPolicy {
    const val LOW_FUEL_FRACTION = 0.12
    const val LOW_FUEL_RANGE_KM = 60.0

    fun isLowFuel(litres: Double, capacityL: Double, rangeKm: Double, tankUnknown: Boolean): Boolean {
        if (tankUnknown) return false
        val fraction = if (capacityL > 0.0) litres / capacityL else 0.0
        return fraction < LOW_FUEL_FRACTION || rangeKm < LOW_FUEL_RANGE_KM
    }

    /** Efficiency tier relative to this car's learned mileage (a 10 km/L SUV can be ECO too). */
    fun efficiencyTier(instantKmpl: Double, learnedKmpl: Double): String {
        if (instantKmpl <= 0.0 || learnedKmpl <= 0.0) return "OPTIMAL"
        val ratio = instantKmpl / learnedKmpl
        return when {
            ratio >= 1.10 -> "ECO"
            ratio >= 0.90 -> "OPTIMAL"
            else -> "HIGH"
        }
    }

    /** Refill dialog nudge: no gauge reading on a partial refill while unlogged debt builds up (E9). */
    fun shouldNudgeGaugeAfter(debtL: Double, fullTank: Boolean, gaugeAfterGiven: Boolean): Boolean =
        !fullTank && !gaugeAfterGiven && debtL > 2.0
}

/**
 * Per-car calibration of the live trip-fuel estimate against refill truth (audit F2 / F4):
 * over a full-to-full interval the litres actually refilled are compared with the litres the trip
 * estimator predicted for the same km; the ratio (EMA-smoothed, clamped) scales every later
 * estimate, so the fixed load-curve normaliser stops biasing highway vs city cars.
 */
object TripFuelCalibration {
    const val MIN = 0.6
    const val MAX = 1.6
    const val SMOOTHING = 0.3
    const val MIN_PREDICTED_L = 2.0

    fun update(current: Double, actualL: Double, predictedL: Double): Double {
        if (!actualL.isFinite() || !predictedL.isFinite() || predictedL < MIN_PREDICTED_L || actualL <= 0.0) return current
        val observed = (actualL / predictedL).coerceIn(MIN, MAX)
        return (SMOOTHING * observed + (1 - SMOOTHING) * current).coerceIn(MIN, MAX)
    }
}
