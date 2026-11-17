package com.antigravity.copilot.domain.trip

import com.antigravity.copilot.core.database.TripEntity
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

data class MonthlyTripGroup(
    val monthIndex: Int,
    val monthName: String,
    val year: Int,
    val totalDistanceKm: Double,
    val totalDurationMs: Long,
    val trips: List<TripEntity>
)

data class YearlyTripAnalytics(
    val year: Int,
    val totalTrips: Int,
    val totalDistanceKm: Double,
    val totalDrivingDurationMs: Long,
    val totalIdleDurationMs: Long,
    val avgSpeedKmh: Double,
    val maxSpeedKmh: Double,
    val longestTripKm: Double,
    val longestTripDurationMs: Long,
    val estimatedFuelLiters: Double,
    val estimatedFuelCost: Double,
    val monthlyGroups: List<MonthlyTripGroup>,
    val weeklyDistribution: IntArray, // 7 days (Mon=0..Sun=6)
    val avgTripDistanceKm: Double = 0.0,
    val avgTripDurationMs: Long = 0L,
    val movingRatioPercent: Double = 0.0,
    val weekdayPercent: Double = 0.0,
    val weekendPercent: Double = 0.0,
    val dayPercent: Double = 0.0,
    val nightPercent: Double = 0.0,
    val avgDriverScore: Int = 100,
    val fuelCostPerKm: Double = 0.0
)

fun computeYearlyTripAnalytics(
    allTrips: List<TripEntity>,
    selectedYear: Int,
    fuelPrice: Double = 96.5
): YearlyTripAnalytics {
    val cal = Calendar.getInstance()
    val monthNames = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    val yearTrips = allTrips.filter {
        cal.timeInMillis = it.startTime
        cal.get(Calendar.YEAR) == selectedYear
    }.sortedByDescending { it.startTime }

    val totalTrips = yearTrips.size
    val totalDistanceKm = yearTrips.sumOf { it.distanceMeters } / 1000.0
    val totalDrivingDurationMs = yearTrips.sumOf { it.drivingDurationMs }
    val totalIdleDurationMs = yearTrips.sumOf { it.stationaryDurationMs }

    val avgSpeedKmh = if (totalDrivingDurationMs > 0 && totalDistanceKm > 0) {
        totalDistanceKm / (totalDrivingDurationMs / 3600000.0)
    } else if (yearTrips.isNotEmpty()) {
        val valid = yearTrips.map { it.avgSpeedKmh }.filter { it > 0 }
        if (valid.isNotEmpty()) valid.average() else 0.0
    } else 0.0

    val maxSpeedKmh = yearTrips.maxOfOrNull { it.maxSpeedKmh } ?: 0.0

    val longestTrip = yearTrips.maxByOrNull { it.distanceMeters }
    val longestTripKm = (longestTrip?.distanceMeters ?: 0.0) / 1000.0
    val longestTripDuration = longestTrip?.let {
        if (it.endTime > it.startTime) it.endTime - it.startTime else it.drivingDurationMs + it.stationaryDurationMs
    } ?: 0L

    val estimatedFuelLiters = yearTrips.sumOf { it.estimatedFuelLiters }
    val estimatedFuelCost = estimatedFuelLiters * fuelPrice

    // Monthly Grouping (0..11)
    val monthlyGroups = (0 until 12).map { monthIdx ->
        val mTrips = yearTrips.filter {
            cal.timeInMillis = it.startTime
            cal.get(Calendar.MONTH) == monthIdx
        }
        val mDist = mTrips.sumOf { it.distanceMeters } / 1000.0
        val mDur = mTrips.sumOf { it.drivingDurationMs + it.stationaryDurationMs }
        MonthlyTripGroup(
            monthIndex = monthIdx,
            monthName = monthNames[monthIdx],
            year = selectedYear,
            totalDistanceKm = mDist,
            totalDurationMs = mDur,
            trips = mTrips
        )
    }

    // 7-Day Weekly Distribution (Mon=0..Sun=6)
    val weeklyDistribution = IntArray(7)
    var weekendTrips = 0
    var nightTrips = 0
    var totalDriverScore = 0

    yearTrips.forEach { t ->
        cal.timeInMillis = t.startTime
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        val dayIdx = when (dow) {
            Calendar.MONDAY -> 0
            Calendar.TUESDAY -> 1
            Calendar.WEDNESDAY -> 2
            Calendar.THURSDAY -> 3
            Calendar.FRIDAY -> 4
            Calendar.SATURDAY -> 5
            Calendar.SUNDAY -> 6
            else -> 0
        }
        weeklyDistribution[dayIdx]++

        if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) {
            weekendTrips++
        }
        val hr = cal.get(Calendar.HOUR_OF_DAY)
        if (hr < 6 || hr >= 20) {
            nightTrips++
        }
        totalDriverScore += t.driverScore
    }

    val avgTripDistanceKm = if (totalTrips > 0) totalDistanceKm / totalTrips else 0.0
    val totalAllDurationMs = totalDrivingDurationMs + totalIdleDurationMs
    val avgTripDurationMs = if (totalTrips > 0) totalAllDurationMs / totalTrips else 0L
    val movingRatioPercent = if (totalAllDurationMs > 0) (totalDrivingDurationMs.toDouble() / totalAllDurationMs) * 100.0 else 0.0

    val weekendPercent = if (totalTrips > 0) (weekendTrips.toDouble() / totalTrips) * 100.0 else 0.0
    val weekdayPercent = 100.0 - weekendPercent
    val nightPercent = if (totalTrips > 0) (nightTrips.toDouble() / totalTrips) * 100.0 else 0.0
    val dayPercent = 100.0 - nightPercent
    val avgDriverScore = if (totalTrips > 0) (totalDriverScore.toDouble() / totalTrips).toInt() else 100
    val fuelCostPerKm = if (totalDistanceKm > 0.0) estimatedFuelCost / totalDistanceKm else 0.0

    return YearlyTripAnalytics(
        year = selectedYear,
        totalTrips = totalTrips,
        totalDistanceKm = totalDistanceKm,
        totalDrivingDurationMs = totalDrivingDurationMs,
        totalIdleDurationMs = totalIdleDurationMs,
        avgSpeedKmh = avgSpeedKmh,
        maxSpeedKmh = maxSpeedKmh,
        longestTripKm = longestTripKm,
        longestTripDurationMs = longestTripDuration,
        estimatedFuelLiters = estimatedFuelLiters,
        estimatedFuelCost = estimatedFuelCost,
        monthlyGroups = monthlyGroups,
        weeklyDistribution = weeklyDistribution,
        avgTripDistanceKm = avgTripDistanceKm,
        avgTripDurationMs = avgTripDurationMs,
        movingRatioPercent = movingRatioPercent,
        weekdayPercent = weekdayPercent,
        weekendPercent = weekendPercent,
        dayPercent = dayPercent,
        nightPercent = nightPercent,
        avgDriverScore = avgDriverScore,
        fuelCostPerKm = fuelCostPerKm
    )
}
