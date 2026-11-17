package com.antigravity.copilot.core.logic

import kotlin.math.floor
import kotlin.math.max

/** A trail sample for per-road mileage. */
data class RoadSample(val lat: Double, val lon: Double, val speedKmh: Double, val timeMs: Long)

/** Aggregated statistics of one ~40 m road cell over all passes (running sums). */
data class RoadCellStats(
    val key: String,
    val lat: Double,
    val lon: Double,
    val km: Double = 0.0,
    val litres: Double = 0.0,
    val passes: Int = 0,
    /** Σ speed·km, so avg speed is distance-weighted. */
    val speedKm: Double = 0.0
) {
    val avgKmpl: Double get() = if (litres > 1e-9) km / litres else 0.0
    val avgSpeedKmh: Double get() = if (km > 1e-9) speedKm / km else 0.0

    fun merge(other: RoadCellStats): RoadCellStats = copy(
        km = km + other.km,
        litres = litres + other.litres,
        passes = passes + other.passes,
        speedKm = speedKm + other.speedKm
    )
}

/**
 * Per-road mileage (Phase 7). Each trip's fuel — from the tank model (trip km / learned
 * mileage), idle excluded — is distributed over its ~40 m cells by a driving-load weight
 * (speed curve × positive acceleration, same as [TripFuelEstimator.loadFactor]). Each cell then
 * gets km/L = km / litres; cells aggregate over all passes and refine as data grows.
 */
object RoadMileage {

    fun cellKey(lat: Double, lon: Double, cellM: Double = Config.ROAD_CELL_M): Pair<Long, Long> {
        // Fixed global grid: latitude rows of cellM, longitude columns scaled per row.
        val row = floor(lat * GeoMath.M_PER_DEG_LAT / cellM).toLong()
        val rowLat = (row + 0.5) * cellM / GeoMath.M_PER_DEG_LAT
        val col = floor(lon * GeoMath.mPerDegLon(rowLat) / cellM).toLong()
        return row to col
    }

    fun cellCenter(row: Long, col: Long, cellM: Double = Config.ROAD_CELL_M): Pair<Double, Double> {
        val lat = (row + 0.5) * cellM / GeoMath.M_PER_DEG_LAT
        val lon = (col + 0.5) * cellM / GeoMath.mPerDegLon(lat)
        return lat to lon
    }

    /**
     * @param tripLitres fuel for the trip from the tank model (trip km / learned km/L).
     * @return per-cell contributions of this single trip (passes = 1 per touched cell).
     */
    fun distributeTrip(
        samples: List<RoadSample>,
        tripLitres: Double,
        cellM: Double = Config.ROAD_CELL_M
    ): List<RoadCellStats> {
        if (samples.size < 2 || tripLitres <= 0.0) return emptyList()
        data class Seg(val cell: Pair<Long, Long>, val km: Double, val speed: Double, val weight: Double)
        val segs = ArrayList<Seg>()
        var prevSpeed = samples.first().speedKmh
        for (i in 1 until samples.size) {
            val a = samples[i - 1]
            val b = samples[i]
            val speed = (a.speedKmh + b.speedKmh) / 2
            if (speed < Config.IDLE_SPEED_KMH) { prevSpeed = b.speedKmh; continue } // idle excluded
            val km = GeoMath.haversineM(a.lat, a.lon, b.lat, b.lon) / 1000.0
            if (km <= 0.0) continue
            val dt = (b.timeMs - a.timeMs) / 1000.0
            if (dt > 0 && km / dt * 3600.0 > Config.ODO_MAX_IMPLIED_SPEED_KMH) continue // GPS jump
            val accel = if (dt > 0) ((b.speedKmh - prevSpeed) / 3.6) / dt else 0.0
            prevSpeed = b.speedKmh
            val mid = cellKey((a.lat + b.lat) / 2, (a.lon + b.lon) / 2, cellM)
            segs += Seg(mid, km, speed, km * TripFuelEstimator.loadFactor(speed, accel))
        }
        val totalWeight = segs.sumOf { it.weight }
        if (totalWeight <= 0.0) return emptyList()
        val byCell = LinkedHashMap<Pair<Long, Long>, RoadCellStats>()
        for (s in segs) {
            val litres = tripLitres * s.weight / totalWeight
            val (clat, clon) = cellCenter(s.cell.first, s.cell.second, cellM)
            val key = "${s.cell.first}:${s.cell.second}"
            val add = RoadCellStats(key, clat, clon, s.km, litres, 0, s.speed * s.km)
            byCell[s.cell] = byCell[s.cell]?.merge(add) ?: add
        }
        return byCell.values
            .filter { it.km >= Config.ROAD_MIN_CELL_KM * 0.1 }
            .map { it.copy(passes = 1) }
    }

    /** Merge a trip's contributions into existing aggregates. */
    fun aggregate(existing: Map<String, RoadCellStats>, trip: List<RoadCellStats>): List<RoadCellStats> =
        trip.map { t -> existing[t.key]?.merge(t) ?: t }

    /** 0 = worst (red) .. 1 = best (green) relative to the learned mileage. */
    fun colourFraction(cellKmpl: Double, learnedKmpl: Double): Double {
        if (cellKmpl <= 0.0 || learnedKmpl <= 0.0) return 0.5
        val lo = learnedKmpl * 0.6
        val hi = learnedKmpl * 1.4
        return ((cellKmpl - lo) / max(1e-9, hi - lo)).coerceIn(0.0, 1.0)
    }
}
