package com.antigravity.copilot.core.map.intel

import com.antigravity.copilot.core.database.RoadSegmentStatsEntity
import com.antigravity.copilot.core.logic.Config
import com.antigravity.copilot.core.logic.TripFuelEstimator
import kotlin.math.floor
import kotlin.math.min

/** Movement driven on one road segment since the last flush (immutable snapshot). */
data class SegmentPass(
    val km: Double,
    /** Σ km · [TripFuelEstimator.loadFactor]: fuel-weighted distance, so km / (loadKm / ref) = km/L. */
    val loadKm: Double,
    /** Σ speed · km, for a distance-weighted average speed. */
    val speedKm: Double,
    val samples: Int
) {
    operator fun plus(o: SegmentPass) = SegmentPass(km + o.km, loadKm + o.loadKm, speedKm + o.speedKm, samples + o.samples)
}

/** Callout content for the segment the car is on. Integers so StateFlow de-dupes float noise. */
data class RoadCallout(
    val segmentKey: String,
    val avgSpeedKmh: Int,
    /** km/L × 10. */
    val mileageTenths: Int,
    /** False → show "~18.6 km/l" (no fuel-log calibration yet, or too little distance on this segment). */
    val calibrated: Boolean
)

/**
 * Pure math for road-segment stats and the fuel-log calibration (unit-tested, no Android).
 *
 * - Segment key: road class + a `floor(deg × 200)` grid (0.005° ≈ 555 m N–S, ~500 m E–W in India).
 * - Merging a pass into a row is a ratio of sums, with the stored history capped at
 *   [HISTORY_CAP_KM] so the averages keep adapting (exponential forgetting).
 * - Calibration: `multiplier = actual fuel-log km/L ÷ predicted km/L` over the same driving,
 *   blended across fills.
 */
object RoadSegmentMath {
    const val GRID_PER_DEG = 200.0
    const val MIN_MOVING_KMH = 5.0
    const val HISTORY_CAP_KM = 50.0

    /** Calibration needs at least this much tracked driving between two fuel-log anchors. */
    const val MIN_CALIBRATION_KM = 10.0
    /** ... and it must cover this share of the fuel segment, or the ratio isn't representative. */
    const val MIN_CALIBRATION_COVERAGE = 0.3
    const val MIN_MULTIPLIER = 0.5
    const val MAX_MULTIPLIER = 2.0
    const val ALPHA_FULL_TANK = 0.5
    const val ALPHA_GAUGE = 0.25

    fun gridIndex(deg: Double): Int = floor(deg * GRID_PER_DEG).toInt()

    fun segmentKey(roadClass: String, gridLat: Int, gridLon: Int): String = "${roadClass}_${gridLat}_$gridLon"

    /**
     * Merge [pass] into [row] (null = new segment). Returns null only when there is nothing at all.
     * @param refKmpl vehicle baseline km/L the raw (physics) estimate is expressed against.
     */
    fun merge(
        row: RoadSegmentStatsEntity?,
        key: String,
        roadClass: String,
        roadName: String?,
        pass: SegmentPass,
        refKmpl: Double,
        multiplier: Double,
        nowMs: Long
    ): RoadSegmentStatsEntity? {
        if (pass.km <= 0.0 || pass.loadKm <= 0.0 || refKmpl <= 0.0) return row
        val oldKm = row?.totalDistanceKm ?: 0.0
        val w = min(oldKm, HISTORY_CAP_KM)
        val km = w + pass.km
        val avgSpeed = ((row?.avgSpeedKmh ?: 0.0) * w + pass.speedKm) / km
        val oldRaw = row?.rawEstimatedMileageKmpl ?: 0.0
        val oldLitres = if (oldRaw > 0.0) w / oldRaw else 0.0
        val raw = clampKmpl(km / (oldLitres + pass.loadKm / refKmpl))
        return RoadSegmentStatsEntity(
            segmentKey = key,
            roadClass = roadClass,
            roadName = roadName ?: row?.roadName,
            avgSpeedKmh = avgSpeed,
            sampleCount = (row?.sampleCount ?: 0) + pass.samples,
            totalDistanceKm = oldKm + pass.km,
            rawEstimatedMileageKmpl = raw,
            calibratedMileageKmpl = clampKmpl(raw * multiplier),
            lastUpdatedTimestamp = nowMs
        )
    }

    /** Speed-band estimate for a road with no history yet (steady driving at [speedKmh]). */
    fun instantRawKmpl(speedKmh: Double, refKmpl: Double): Double =
        clampKmpl(refKmpl / TripFuelEstimator.loadFactor(speedKmh, 0.0))

    /**
     * New calibration multiplier after one fuel-log segment.
     * @param actualKmpl measured (tank-to-tank / gauge-to-gauge) km/L.
     * @param predictedKmpl what the uncalibrated road model predicted for the same driving.
     */
    fun updatedMultiplier(current: Double, calibrations: Int, actualKmpl: Double, predictedKmpl: Double, fullTank: Boolean): Double {
        if (actualKmpl <= 0.0 || predictedKmpl <= 0.0) return current
        val ratio = (actualKmpl / predictedKmpl).coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)
        if (calibrations <= 0) return ratio
        val alpha = if (fullTank) ALPHA_FULL_TANK else ALPHA_GAUGE
        return (current + alpha * (ratio - current)).coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)
    }

    fun clampKmpl(kmpl: Double): Double = kmpl.coerceIn(Config.MILEAGE_MIN_KMPL, Config.MILEAGE_MAX_KMPL)
}
