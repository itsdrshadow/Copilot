package com.antigravity.copilot.core.fuel.intelligence

import kotlin.math.abs
import kotlin.math.min

/**
 * Component D: Fuel Gauge Calibration Engine.
 * Dynamically learns and maintains a non-linear calibration curve for the 10-point instrument cluster gauge (0..10 bars),
 * filters slosh/slope anomalies, and estimates fuel levels.
 */
class FuelGaugeCalibrationEngine(
    var designCapacityLiters: Double = 48.0
) {
    // Volume fraction associated with each bar index 0..10
    // Initialized with standard automotive geometric tank profile (reserve + progressive top)
    private val calibratedFractions = doubleArrayOf(
        0.00, // 0 bar (empty / reserve threshold)
        0.08, // 1 bar
        0.17, // 2 bars (low fuel warning)
        0.27, // 3 bars
        0.38, // 4 bars
        0.49, // 5 bars (approx half tank)
        0.60, // 6 bars
        0.71, // 7 bars
        0.82, // 8 bars
        0.91, // 9 bars
        1.00  // 10 bars (full tank)
    )

    // Confidence weight for each bar's calibration (0..100)
    private val barConfidences = IntArray(11) { 35 } // Baseline prior confidence

    // Rolling observations for slosh filtering (last 5 readings with timestamps)
    private val recentReadings = mutableListOf<Pair<Long, Int>>()

    fun updateDesignCapacity(newCapacity: Double) {
        if (newCapacity in 10.0..200.0) {
            designCapacityLiters = newCapacity
        }
    }

    fun getCalibratedFractions(): DoubleArray = calibratedFractions.clone()

    fun getBarConfidence(bar: Int): Int = barConfidences[bar.coerceIn(0, 10)]

    fun setCalibratedFractions(fractions: DoubleArray) {
        if (fractions.size == 11) {
            for (i in 0..10) {
                calibratedFractions[i] = fractions[i]
            }
            enforceMonotonicity()
        }
    }

    /**
     * Estimates liters corresponding to a specific bar index (0..10) based on current calibration.
     */
    fun getLitersForBar(bar: Int): Double {
        val clampedBar = bar.coerceIn(0, 10)
        val fraction = calibratedFractions[clampedBar]
        return FuelMath.roundFuel(fraction * designCapacityLiters)
    }

    /**
     * Maps a given fuel volume (liters) to the most probable gauge bar (0..10).
     */
    fun getBarForLiters(liters: Double): Int {
        val safeCap = designCapacityLiters.coerceAtLeast(10.0)
        val fraction = (liters / safeCap).coerceIn(0.0, 1.0)
        var bestBar = 0
        var minDiff = Double.MAX_VALUE

        for (i in 0..10) {
            val diff = abs(calibratedFractions[i] - fraction)
            if (diff < minDiff) {
                minDiff = diff
                bestBar = i
            }
        }
        return bestBar
    }

    /**
     * Updates calibration when a ground-truth refill or baseline is observed at a known gauge level.
     */
    fun learnCalibrationPoint(bar: Int, confirmedLiters: Double, confidenceWeight: Double = 0.20) {
        if (bar !in 0..10 || confirmedLiters <= 0.0) return

        val observedFraction = (confirmedLiters / designCapacityLiters).coerceIn(0.0, 1.2)
        val currentFraction = calibratedFractions[bar]

        // Adapt using weighted learning rate
        val updatedFraction = (currentFraction * (1.0 - confidenceWeight)) + (observedFraction * confidenceWeight)
        calibratedFractions[bar] = updatedFraction.coerceIn(0.0, 1.15)
        barConfidences[bar] = min(95, barConfidences[bar] + 12)

        // Enforce monotonicity (each higher bar must hold >= previous bar)
        enforceMonotonicity()
    }

    /**
     * Resets or seeds the rolling slosh buffer on explicit user calibration.
     * Prevents prior transient readings from overriding intentional manual calibrations.
     */
    fun resetSloshBuffer(initialBar: Int = -1) {
        synchronized(recentReadings) {
            recentReadings.clear()
            if (initialBar in 0..10) {
                recentReadings.add(Pair(System.currentTimeMillis(), initialBar))
            }
        }
    }

    /**
     * Filters short-term gauge fluctuations caused by vehicle slope or fuel sloshing.
     * Requires consistent observation over multiple samples or time window before acknowledging state change.
     */
    fun filterSloshAndSlope(newBar: Int, timestamp: Long = System.currentTimeMillis()): Int {
        val clamped = newBar.coerceIn(0, 10)
        synchronized(recentReadings) {
            recentReadings.add(Pair(timestamp, clamped))

            // Keep last 60 seconds of readings
            while (recentReadings.isNotEmpty() && (timestamp - recentReadings.first().first > 60000L)) {
                recentReadings.removeAt(0)
            }

            if (recentReadings.size < 3) {
                return clamped
            }

            // Return the median reading to reject transient slope / acceleration sloshes
            val sortedBars = recentReadings.map { it.second }.sorted()
            return sortedBars[sortedBars.size / 2]
        }
    }

    fun addGaugeObservation(bar: Int, timestamp: Long = System.currentTimeMillis()): Int = filterSloshAndSlope(bar, timestamp)

    fun getSloshFilteredBar(): Int {
        synchronized(recentReadings) {
            if (recentReadings.isEmpty()) return 0
            val sortedBars = recentReadings.map { it.second }.sorted()
            return sortedBars[sortedBars.size / 2]
        }
    }

    fun enforceMonotonicity() {
        calibratedFractions[0] = 0.0
        // Forward pass: strictly increasing with minimum step
        for (i in 1..10) {
            val minStep = 0.02
            if (calibratedFractions[i] < calibratedFractions[i - 1] + minStep) {
                calibratedFractions[i] = calibratedFractions[i - 1] + minStep
            }
        }
        // Ensure 10th bar represents full capacity
        if (calibratedFractions[10] < 1.0) {
            calibratedFractions[10] = 1.0
        }
        // Backward pass: ensure upper bars do not overflow beyond headroom
        for (i in 9 downTo 1) {
            val maxAllowed = calibratedFractions[i + 1] - 0.02
            if (calibratedFractions[i] > maxAllowed) {
                calibratedFractions[i] = maxAllowed
            }
        }
    }
}
