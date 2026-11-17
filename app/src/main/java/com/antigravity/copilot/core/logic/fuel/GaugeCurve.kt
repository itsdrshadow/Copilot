package com.antigravity.copilot.core.logic.fuel

import com.antigravity.copilot.core.logic.Config

/** One (gauge point, litres) pair. Points are 0..10 in 0.5 steps. */
data class GaugeObservation(
    val point: Double,
    val litres: Double,
    val weight: Double = 1.0,
    val timeMs: Long = 0L
)

/**
 * Learned gauge curve: litres in the tank at each half-point of the 10-point gauge (21 knots,
 * monotone non-decreasing by construction via weighted isotonic regression / PAVA).
 *
 * - `knots[0]`  = litres when the gauge first reads 0  → the **bottom buffer** (hidden reserve).
 * - `knots[20]` = litres when the gauge starts dropping from full → `actual − knots[20]` is the
 *   **top buffer** (fuel above the top mark).
 * Litres per point are learned from data and are NOT evenly spread.
 */
data class GaugeCurve(
    val knots: List<Double>,
    val actualCapacityL: Double
) {
    init {
        require(knots.size == KNOTS) { "Gauge curve needs $KNOTS knots" }
    }

    val bottomBufferL: Double get() = knots.first()
    val topBufferL: Double get() = (actualCapacityL - knots.last()).coerceAtLeast(0.0)

    /** Litres at a (possibly fractional) gauge point 0..10. */
    fun litresAt(point: Double): Double {
        val x = (point.coerceIn(0.0, Config.GAUGE_MAX_POINT)) / Config.GAUGE_STEP
        val i = x.toInt().coerceAtMost(KNOTS - 2)
        val f = x - i
        return knots[i] + (knots[i + 1] - knots[i]) * f
    }

    /**
     * Continuous gauge position (0..10) for [litres] — used for the smooth bar fill.
     * Above the top-buffer threshold the bar is full; below the bottom buffer it is empty.
     */
    fun pointFor(litres: Double): Double {
        if (litres >= knots.last()) return Config.GAUGE_MAX_POINT
        if (litres <= knots.first()) return 0.0
        for (i in 0 until KNOTS - 1) {
            val a = knots[i]
            val b = knots[i + 1]
            if (litres < b || i == KNOTS - 2) {
                val f = if (b - a > 1e-9) ((litres - a) / (b - a)).coerceIn(0.0, 1.0) else 1.0
                return (i + f) * Config.GAUGE_STEP
            }
        }
        return Config.GAUGE_MAX_POINT
    }

    /** Litres represented by each whole point 1..10 (index 0 = point 0→1). */
    fun litresPerPoint(): List<Double> = (0 until 10).map { p -> litresAt(p + 1.0) - litresAt(p.toDouble()) }

    companion object {
        const val KNOTS = 21

        /** Evenly spread prior between the default bottom and top buffers. */
        fun prior(
            actualCapacityL: Double,
            topBufferL: Double = Config.DEFAULT_TOP_BUFFER_L,
            bottomBufferL: Double = Config.DEFAULT_BOTTOM_BUFFER_L
        ): GaugeCurve {
            val bottom = bottomBufferL.coerceIn(0.0, actualCapacityL * 0.3)
            val full = (actualCapacityL - topBufferL).coerceAtLeast(bottom + 1.0)
            return GaugeCurve(List(KNOTS) { j -> bottom + (full - bottom) * j / (KNOTS - 1) }, actualCapacityL)
        }

        /**
         * Weighted isotonic fit of [observations] on the half-point grid, regularised by the prior.
         * Readings of 10 are censored ("still full" ⇒ knot20 ≤ litres) and readings of 0 likewise
         * ("already empty" ⇒ knot0 ≥ litres).
         */
        fun fit(
            actualCapacityL: Double,
            observations: List<GaugeObservation>,
            priorWeight: Double = Config.GAUGE_PRIOR_WEIGHT
        ): GaugeCurve {
            val p = prior(actualCapacityL)
            val sumW = DoubleArray(KNOTS) { priorWeight }
            val sumWL = DoubleArray(KNOTS) { priorWeight * p.knots[it] }
            var upperFull = Double.POSITIVE_INFINITY
            var lowerEmpty = Double.NEGATIVE_INFINITY
            for (o in observations) {
                if (!o.litres.isFinite() || o.weight <= 0.0) continue
                val j = Math.round(o.point.coerceIn(0.0, Config.GAUGE_MAX_POINT) / Config.GAUGE_STEP).toInt()
                when (j) {
                    KNOTS - 1 -> upperFull = minOf(upperFull, o.litres)
                    0 -> lowerEmpty = maxOf(lowerEmpty, o.litres)
                    else -> {
                        sumW[j] += o.weight
                        sumWL[j] += o.weight * o.litres
                    }
                }
            }
            val values = DoubleArray(KNOTS) { sumWL[it] / sumW[it] }
            val fitted = isotonic(values, sumW)
            // Censored bounds, then restore monotonicity.
            if (upperFull.isFinite()) fitted[KNOTS - 1] = minOf(fitted[KNOTS - 1], upperFull)
            for (j in KNOTS - 2 downTo 0) fitted[j] = minOf(fitted[j], fitted[j + 1])
            if (lowerEmpty.isFinite()) fitted[0] = maxOf(fitted[0], lowerEmpty)
            for (j in 1 until KNOTS) fitted[j] = maxOf(fitted[j], fitted[j - 1])
            for (j in 0 until KNOTS) fitted[j] = fitted[j].coerceIn(0.0, actualCapacityL)
            return GaugeCurve(fitted.toList(), actualCapacityL)
        }

        /** Weighted pool-adjacent-violators: least-squares non-decreasing fit. */
        fun isotonic(values: DoubleArray, weights: DoubleArray): DoubleArray {
            val n = values.size
            val blockVal = DoubleArray(n)
            val blockW = DoubleArray(n)
            val blockLen = IntArray(n)
            var b = 0
            for (i in 0 until n) {
                blockVal[b] = values[i]; blockW[b] = weights[i]; blockLen[b] = 1
                while (b > 0 && blockVal[b - 1] > blockVal[b]) {
                    val w = blockW[b - 1] + blockW[b]
                    blockVal[b - 1] = (blockVal[b - 1] * blockW[b - 1] + blockVal[b] * blockW[b]) / w
                    blockW[b - 1] = w
                    blockLen[b - 1] += blockLen[b]
                    b--
                }
                b++
            }
            val out = DoubleArray(n)
            var k = 0
            for (i in 0 until b) repeat(blockLen[i]) { out[k++] = blockVal[i] }
            return out
        }
    }
}
