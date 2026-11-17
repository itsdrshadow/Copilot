package com.antigravity.copilot.core.logic.fuel

import com.antigravity.copilot.core.logic.Config

/** A refill as entered by the user. Litres are derived from amount / price. */
data class RefillInput(
    val timeMs: Long,
    val amountRs: Double,
    val pricePerL: Double,
    /** Real odometer read from the dashboard (km). */
    val odoKm: Double,
    val fullTank: Boolean,
    /** Gauge point the car shows after refilling (0..10, half points allowed), if entered. */
    val gaugeAfter: Double? = null
) {
    val litres: Double get() = if (pricePerL > 0) amountRs / pricePerL else 0.0
}

enum class UnloggedStatus { PROVISIONAL, RESOLVED }

/** Phase 4: automatic "unlogged consumption" entry for a trip that ended with the tank at/below 0. */
data class UnloggedEntry(
    val tripId: Long,
    val timeMs: Long,
    val km: Double,
    val litres: Double,
    val status: UnloggedStatus = UnloggedStatus.PROVISIONAL,
    val resolvedLitres: Double = 0.0,
    val source: String = "unlogged consumption, auto"
)

data class GaugeAnchor(val odoKm: Double, val litres: Double, val addedSinceL: Double = 0.0)

/**
 * Complete persisted fuel-model state (JSON). Design and actual capacity are kept separate.
 * All collections are bounded.
 */
data class FuelModelState(
    val designCapacityL: Double = 48.0,
    val actualCapacityL: Double = 48.0,
    val defaultKmpl: Double = Config.DEFAULT_MILEAGE_KMPL,
    val anchorLitres: Double = 24.0,
    val anchorOdoKm: Double = 0.0,
    val anchorTimeMs: Long = 0L,
    val observations: List<GaugeObservation> = emptyList(),
    val segments: List<MileageSegment> = emptyList(),
    val lastFullOdoKm: Double = -1.0,
    val litresSinceLastFull: Double = 0.0,
    val lastGauge: GaugeAnchor? = null,
    val confidencePenalty: Double = 0.0,
    val unlogged: List<UnloggedEntry> = emptyList(),
    val maxRefillL: Double = 0.0
) {
    /** Gson leaves missing fields null/zero; restore safe defaults after loading old JSON. */
    @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
    fun normalized(): FuelModelState = copy(
        designCapacityL = if (designCapacityL > 0) designCapacityL else 48.0,
        actualCapacityL = if (actualCapacityL > 0) actualCapacityL else designCapacityL.takeIf { it > 0 } ?: 48.0,
        defaultKmpl = if (defaultKmpl > 0) defaultKmpl else Config.DEFAULT_MILEAGE_KMPL,
        observations = observations ?: emptyList(),
        segments = segments ?: emptyList(),
        unlogged = unlogged ?: emptyList()
    )

    val totalDebtL: Double get() = unlogged.filter { it.status == UnloggedStatus.PROVISIONAL }.sumOf { it.litres }

    companion object {
        const val MAX_OBSERVATIONS = 60
        const val MAX_SEGMENTS = 30
        const val MAX_UNLOGGED = 100
    }
}

/** Snapshot for the UI. */
data class FuelEstimate(
    /** May be negative ("debt"): consumption beyond what the model thinks was in the tank. */
    val rawLitres: Double,
    /** Clamped 0..actual capacity for display. */
    val displayLitres: Double,
    /** Continuous gauge position 0..10 (full bar until the top buffer is used, then smooth). */
    val gaugePoints: Double,
    val kmpl: Double,
    val mileageConfidence: Double,
    val rangeKm: Double,
    val designCapacityL: Double,
    val actualCapacityL: Double,
    val topBufferL: Double,
    val bottomBufferL: Double
)

/** Notice shown quietly (never a blocking prompt). */
data class FuelNotice(val message: String)

data class RefillOutcome(
    val state: FuelModelState,
    val litres: Double,
    val trueBeforeL: Double?,
    val unexplainedL: Double?,
    /** Entries resolved by this refill (to update in the fuel log). */
    val resolved: List<UnloggedEntry> = emptyList(),
    /** Litres attributed to an automatically detected unlogged refill (null if none). */
    val autoUnloggedRefillL: Double? = null,
    val notice: FuelNotice? = null
)

/**
 * Pure fuel model. Tank estimate = last anchor litres − km since anchor / learned mileage.
 * There is no idle fuel burn and live trip estimates never feed back into it.
 */
object FuelModel {

    fun curve(s: FuelModelState): GaugeCurve = GaugeCurve.fit(s.actualCapacityL, s.observations)

    fun mileage(s: FuelModelState, nowMs: Long): MileageEstimate =
        MileageEstimator.estimate(s.segments, nowMs, s.defaultKmpl, s.confidencePenalty)

    fun rawLitres(s: FuelModelState, odoKm: Double, nowMs: Long): Double {
        val km = (odoKm - s.anchorOdoKm).coerceAtLeast(0.0)
        return s.anchorLitres - km / mileage(s, nowMs).kmpl
    }

    fun estimate(s: FuelModelState, odoKm: Double, nowMs: Long): FuelEstimate {
        val m = mileage(s, nowMs)
        val c = curve(s)
        val raw = s.anchorLitres - (odoKm - s.anchorOdoKm).coerceAtLeast(0.0) / m.kmpl
        val shown = raw.coerceIn(0.0, s.actualCapacityL)
        return FuelEstimate(
            rawLitres = raw,
            displayLitres = shown,
            gaugePoints = c.pointFor(shown),
            kmpl = m.kmpl,
            mileageConfidence = m.confidence,
            rangeKm = shown * m.kmpl,
            designCapacityL = s.designCapacityL,
            actualCapacityL = s.actualCapacityL,
            topBufferL = c.topBufferL,
            bottomBufferL = c.bottomBufferL
        )
    }

    fun withConfig(s: FuelModelState, designCapacityL: Double, defaultKmpl: Double): FuelModelState {
        val design = designCapacityL.coerceIn(10.0, 200.0)
        val actual = if (s.actualCapacityL <= 0 || s.observations.isEmpty() && s.segments.isEmpty()) design
        else s.actualCapacityL.coerceIn(design * Config.ACTUAL_CAPACITY_MIN_FACTOR, design * Config.ACTUAL_CAPACITY_MAX_FACTOR)
        return s.copy(designCapacityL = design, actualCapacityL = actual, defaultKmpl = defaultKmpl.coerceIn(Config.MILEAGE_MIN_KMPL, Config.MILEAGE_MAX_KMPL))
    }

    /** Calibration popup: the user picked the [point] the car's gauge shows right now. */
    fun onGaugeReading(s: FuelModelState, point: Double, odoKm: Double, nowMs: Long): FuelModelState {
        val p = snap(point)
        val m = mileage(s, nowMs)
        val c = curve(s)
        val est = rawLitres(s, odoKm, nowMs).coerceIn(0.0, s.actualCapacityL)
        val gaugeL = when {
            p >= Config.GAUGE_MAX_POINT -> maxOf(est, c.litresAt(p)) // pinned full: at least the full mark
            p <= 0.0 -> minOf(est, c.litresAt(0.0))
            else -> c.litresAt(p)
        }
        // Fuse model and gauge; the gauge is coarse so it never fully overrides a confident model.
        val wModel = m.confidence
        val wGauge = 0.6
        val fused = (wModel * est + wGauge * gaugeL) / (wModel + wGauge)
        var next = s.copy(
            observations = (s.observations + GaugeObservation(p, est, weight = 0.3 + m.confidence, timeMs = nowMs))
                .takeLast(FuelModelState.MAX_OBSERVATIONS)
        )
        next = addGaugeSegment(next, odoKm, gaugeL, nowMs)
        return next.copy(
            anchorLitres = fused,
            anchorOdoKm = odoKm,
            anchorTimeMs = nowMs,
            lastGauge = GaugeAnchor(odoKm, gaugeL, 0.0)
        )
    }

    /** Refill without unlogged-fuel reconciliation (see [UnloggedFuel.onRefill] for the full flow). */
    fun applyRefill(
        s: FuelModelState,
        input: RefillInput,
        gaugeAfterLitres: Double?,
        extraUnloggedL: Double = 0.0
    ): FuelModelState {
        val litres = input.litres
        val now = input.timeMs
        val m = mileage(s, now)
        val rawBefore = rawLitres(s, input.odoKm, now)
        var next = s.copy(maxRefillL = maxOf(s.maxRefillL, litres))

        // Learn the actual (usable) capacity on confident full fills.
        if (input.fullTank && m.confidence >= 0.5 && rawBefore >= 0.0 && s.totalDebtL <= 0.0) {
            val candidate = (rawBefore + litres).coerceIn(
                s.designCapacityL * Config.ACTUAL_CAPACITY_MIN_FACTOR,
                s.designCapacityL * Config.ACTUAL_CAPACITY_MAX_FACTOR
            )
            val a = Config.ACTUAL_CAPACITY_SMOOTHING
            next = next.copy(actualCapacityL = a * candidate + (1 - a) * s.actualCapacityL)
        }
        if (litres > next.actualCapacityL) {
            next = next.copy(actualCapacityL = litres.coerceAtMost(s.designCapacityL * Config.ACTUAL_CAPACITY_MAX_FACTOR))
        }

        val modelAfter = rawBefore.coerceAtLeast(0.0) + litres + extraUnloggedL
        val after = when {
            input.fullTank -> next.actualCapacityL
            gaugeAfterLitres != null -> gaugeAfterLitres
            else -> modelAfter
        }.coerceIn(0.0, next.actualCapacityL)

        // Full-to-full segment.
        val addedSinceFull = s.litresSinceLastFull + litres + extraUnloggedL
        next = if (input.fullTank) {
            if (s.lastFullOdoKm >= 0.0 && input.odoKm > s.lastFullOdoKm) {
                next = addSegment(next, MileageSegment(now, input.odoKm - s.lastFullOdoKm, addedSinceFull, SegmentType.FULL_TO_FULL))
            }
            next.copy(lastFullOdoKm = input.odoKm, litresSinceLastFull = 0.0)
        } else next.copy(litresSinceLastFull = addedSinceFull)

        // Gauge-to-gauge segment + gauge observation (model-side litres = independent evidence).
        val g = input.gaugeAfter?.let { snap(it) }
        if (g != null && !input.fullTank) {
            if (s.lastGauge != null) {
                next = next.copy(lastGauge = s.lastGauge.copy(addedSinceL = s.lastGauge.addedSinceL + litres + extraUnloggedL))
                next = addGaugeSegment(next, input.odoKm, after, now)
            }
            if (s.totalDebtL <= 0.0) {
                next = next.copy(
                    observations = (next.observations + GaugeObservation(g, modelAfter.coerceAtMost(next.actualCapacityL), 0.3 + m.confidence, now))
                        .takeLast(FuelModelState.MAX_OBSERVATIONS)
                )
            }
        } else if (s.lastGauge != null) {
            next = next.copy(lastGauge = s.lastGauge.copy(addedSinceL = s.lastGauge.addedSinceL + litres + extraUnloggedL))
        }
        if (input.fullTank) {
            next = next.copy(
                observations = (next.observations + GaugeObservation(Config.GAUGE_MAX_POINT, next.actualCapacityL, 1.0, now))
                    .takeLast(FuelModelState.MAX_OBSERVATIONS)
            )
        }

        val gaugeAnchor = when {
            input.fullTank -> GaugeAnchor(input.odoKm, after)
            g != null -> GaugeAnchor(input.odoKm, after)
            else -> next.lastGauge
        }
        return next.copy(anchorLitres = after, anchorOdoKm = input.odoKm, anchorTimeMs = now, lastGauge = gaugeAnchor)
    }

    private fun addGaugeSegment(s: FuelModelState, odoKm: Double, litresNow: Double, nowMs: Long): FuelModelState {
        val prev = s.lastGauge ?: return s
        val km = odoKm - prev.odoKm
        val used = prev.litres + prev.addedSinceL - litresNow
        if (km <= 0 || used <= 0) return s
        return addSegment(s, MileageSegment(nowMs, km, used, SegmentType.GAUGE_TO_GAUGE))
    }

    fun addSegment(s: FuelModelState, seg: MileageSegment): FuelModelState {
        // A consistent new segment slowly heals a past confidence penalty.
        val healed = if (MileageEstimator.isValid(seg)) s.confidencePenalty * 0.5 else s.confidencePenalty
        return s.copy(segments = (s.segments + seg).takeLast(FuelModelState.MAX_SEGMENTS), confidencePenalty = healed)
    }

    fun snap(point: Double): Double =
        (Math.round(point.coerceIn(0.0, Config.GAUGE_MAX_POINT) / Config.GAUGE_STEP) * Config.GAUGE_STEP)
}
