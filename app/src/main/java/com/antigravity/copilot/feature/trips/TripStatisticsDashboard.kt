package com.antigravity.copilot.feature.trips

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.copilot.core.database.TripEntity
import com.antigravity.copilot.core.util.NumberFormatUtils
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max
import kotlin.math.min

/**
 * Modern High-Fidelity Automotive Trip Statistics Dashboard.
 * Formatted for Cockpit landscape displays (1280x800) with comprehensive
 * KPI boxes, Donut charts, Bar charts, Trend curves, Heatmaps, Scatter plots,
 * Driving behavior analysis, and Period-over-Period comparisons.
 */

// ==================== DASHBOARD TAB SELECTOR ====================
enum class TripStatsSection(val label: String, val icon: ImageVector) {
    OVERVIEW("Overview & KPIs", Icons.Default.Dashboard),
    CHARTS("Charts & Distribution", Icons.Default.BarChart),
    INSIGHTS("Driving Insights & Trends", Icons.Default.Security)
}

// ==================== DATA STRUCTURES ====================
data class TimeOfDayDistribution(
    val morningCount: Int = 0,    // 06:00 - 12:00
    val afternoonCount: Int = 0,  // 12:00 - 17:00
    val eveningCount: Int = 0,    // 17:00 - 21:00
    val nightCount: Int = 0       // 21:00 - 06:00
)

data class TimeShareDuration(
    val movingMs: Long = 0L,
    val idleMs: Long = 0L,
    val stoppedMs: Long = 0L
)

data class TripDistanceTiers(
    val shortCount: Int = 0,  // < 5 km
    val mediumCount: Int = 0, // 5 - 20 km
    val longCount: Int = 0    // > 20 km
)

data class SpeedBandDistribution(
    val lowBandCount: Int = 0,    // 0 - 30 km/h (City/Crawling)
    val midBandCount: Int = 0,    // 30 - 60 km/h (Urban Cruising)
    val highBandCount: Int = 0    // 60+ km/h (Highway)
)

data class TopHubLocation(
    val locationName: String,
    val visitCount: Int,
    val totalDistanceKm: Double
)

data class MonthTripStat(
    val monthIndex: Int,
    val monthShortName: String,
    val totalDistanceKm: Double,
    val tripCount: Int,
    val totalDrivingTimeMs: Long
)

data class DayOfWeekStat(
    val dayIndex: Int, // 0 = Mon .. 6 = Sun
    val dayName: String,
    val tripCount: Int,
    val totalDistanceKm: Double
)

data class TripScatterPoint(
    val tripId: Long,
    val distanceKm: Double,
    val durationMin: Double,
    val avgSpeedKmh: Double,
    val isCongested: Boolean
)

data class TripDashboardAnalytics(
    val selectedYear: Int,
    val totalTrips: Int,
    val totalDistanceKm: Double,
    val totalDrivingDurationMs: Long,
    val totalIdleDurationMs: Long,
    val totalStoppageDurationMs: Long,
    val avgSpeedKmh: Double,
    val maxSpeedKmh: Double,
    val avgTripDistanceKm: Double,
    val avgTripDurationMs: Long,
    val estimatedFuelLiters: Double,
    val estimatedFuelCost: Double,
    val avgTripsPerActiveDay: Double,
    val activeDaysCount: Int,
    val avgDriverScore: Int,
    val longestTripKm: Double,
    val longestTripDurationMs: Long,
    val timeOfDay: TimeOfDayDistribution,
    val timeShare: TimeShareDuration,
    val distanceTiers: TripDistanceTiers,
    val speedBands: SpeedBandDistribution,
    val weeklyDistribution: List<DayOfWeekStat>,
    val monthlyStats: List<MonthTripStat>,
    val topLocations: List<TopHubLocation>,
    val weekdayTripsPercent: Double,
    val weekendTripsPercent: Double,
    val hourDayHeatmap: Array<IntArray>, // 7 rows (Mon..Sun) x 24 cols (0..23)
    val scatterPoints: List<TripScatterPoint>,
    // Driving Behavior & Anomalies
    val overspeedTripsCount: Int,
    val harshBrakeEvents: Int,
    val rapidAccelEvents: Int,
    val lateNightTripsCount: Int,
    // Comparisons
    val currentMonthDistanceKm: Double,
    val priorMonthDistanceKm: Double,
    val currentMonthTrips: Int,
    val priorMonthTrips: Int,
    val vehicleUtilizationPercent: Double
)

// ==================== AGGREGATOR FUNCTION ====================
fun computeTripDashboardAnalytics(
    allTrips: List<TripEntity>,
    selectedYear: Int,
    fuelPrice: Double = 96.5
): TripDashboardAnalytics {
    val cal = Calendar.getInstance()
    val yearTrips = allTrips.filter { trip ->
        cal.timeInMillis = trip.startTime
        cal.get(Calendar.YEAR) == selectedYear
    }

    val totalTrips = yearTrips.size
    val totalDistKm = yearTrips.sumOf { it.distanceMeters } / 1000.0
    val totalDrivingMs = yearTrips.sumOf { it.drivingDurationMs }
    val totalIdleMs = yearTrips.sumOf { it.stationaryDurationMs }
    val totalStoppageMs = yearTrips.sumOf {
        val totalSpan = max(0L, it.endTime - it.startTime)
        max(0L, totalSpan - it.drivingDurationMs - it.stationaryDurationMs)
    }

    val avgSpeed = if (totalDrivingMs > 0) (totalDistKm / (totalDrivingMs / 3_600_000.0)) else 0.0
    val maxSpeed = yearTrips.maxOfOrNull { it.maxSpeedKmh } ?: 0.0

    val avgTripDist = if (totalTrips > 0) totalDistKm / totalTrips else 0.0
    val avgTripDur = if (totalTrips > 0) totalDrivingMs / totalTrips else 0L

    val estFuelL = yearTrips.sumOf { it.estimatedFuelLiters }
    val estFuelCost = estFuelL * fuelPrice

    // Active calendar days
    val activeDaysSet = mutableSetOf<String>()
    val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    yearTrips.forEach {
        activeDaysSet.add(dateFormat.format(Date(it.startTime)))
    }
    val activeDaysCount = activeDaysSet.size
    val avgTripsPerDay = if (activeDaysCount > 0) totalTrips.toDouble() / activeDaysCount else 0.0

    val avgDriverScore = if (totalTrips > 0) {
        (yearTrips.sumOf { it.driverScore } / totalTrips).coerceIn(0, 100)
    } else 100

    val longestTrip = yearTrips.maxByOrNull { it.distanceMeters }
    val longestKm = (longestTrip?.distanceMeters ?: 0.0) / 1000.0
    val longestDurMs = longestTrip?.drivingDurationMs ?: 0L

    // Time of day
    var morningCount = 0
    var afternoonCount = 0
    var eveningCount = 0
    var nightCount = 0

    // Distance tiers
    var shortCount = 0
    var medCount = 0
    var longCount = 0

    // Speed bands
    var lowBandCount = 0
    var midBandCount = 0
    var highBandCount = 0

    // Weekday vs Weekend
    var weekdayCount = 0
    var weekendCount = 0

    // Heatmap: 7 days x 24 hours
    val heatmap = Array(7) { IntArray(24) }

    // Driving behavior & anomalies
    var overspeedCount = 0
    var harshBrakes = 0
    var rapidAccels = 0
    var lateNightCount = 0

    // Scatter points
    val scatterList = mutableListOf<TripScatterPoint>()

    yearTrips.forEach { trip ->
        cal.timeInMillis = trip.startTime
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val calDayOfWeek = cal.get(Calendar.DAY_OF_WEEK) // 1=Sun, 2=Mon...
        val dayIdx = if (calDayOfWeek == Calendar.SUNDAY) 6 else (calDayOfWeek - Calendar.MONDAY)

        if (dayIdx in 0..6 && hour in 0..23) {
            heatmap[dayIdx][hour]++
        }

        // Time of Day
        when (hour) {
            in 6..11 -> morningCount++
            in 12..16 -> afternoonCount++
            in 17..20 -> eveningCount++
            else -> nightCount++
        }

        if (hour >= 23 || hour < 4) {
            lateNightCount++
        }

        // Distance tiers
        val km = trip.distanceMeters / 1000.0
        when {
            km < 5.0 -> shortCount++
            km <= 20.0 -> medCount++
            else -> longCount++
        }

        // Speed bands
        when {
            trip.avgSpeedKmh < 30.0 -> lowBandCount++
            trip.avgSpeedKmh <= 60.0 -> midBandCount++
            else -> highBandCount++
        }

        // Weekday vs Weekend
        if (dayIdx in 0..4) weekdayCount++ else weekendCount++

        // Driving behavior
        if (trip.maxSpeedKmh >= 85.0) {
            overspeedCount++
        }
        val speedDelta = trip.maxSpeedKmh - trip.avgSpeedKmh
        if (speedDelta > 45.0) {
            rapidAccels++
        }
        if (trip.driverScore < 80) {
            harshBrakes++
        }

        // Scatter point
        val durMin = trip.drivingDurationMs / 60000.0
        val isCongested = durMin > 10.0 && (km / (durMin / 60.0)) < 18.0
        scatterList.add(
            TripScatterPoint(
                tripId = trip.id,
                distanceKm = km,
                durationMin = durMin,
                avgSpeedKmh = trip.avgSpeedKmh,
                isCongested = isCongested
            )
        )
    }

    val weekdayPct = if (totalTrips > 0) (weekdayCount.toDouble() / totalTrips) * 100.0 else 0.0
    val weekendPct = if (totalTrips > 0) (weekendCount.toDouble() / totalTrips) * 100.0 else 0.0

    // Day of week distribution
    val dayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val weeklyStats = (0..6).map { dayIndex ->
        val tripsForDay = yearTrips.filter {
            cal.timeInMillis = it.startTime
            val calDow = cal.get(Calendar.DAY_OF_WEEK)
            val mappedIdx = if (calDow == Calendar.SUNDAY) 6 else (calDow - Calendar.MONDAY)
            mappedIdx == dayIndex
        }
        DayOfWeekStat(
            dayIndex = dayIndex,
            dayName = dayNames[dayIndex],
            tripCount = tripsForDay.size,
            totalDistanceKm = tripsForDay.sumOf { it.distanceMeters } / 1000.0
        )
    }

    // Monthly stats
    val monthNames = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val monthlyStats = (0..11).map { monthIdx ->
        val tripsForMonth = yearTrips.filter {
            cal.timeInMillis = it.startTime
            cal.get(Calendar.MONTH) == monthIdx
        }
        MonthTripStat(
            monthIndex = monthIdx,
            monthShortName = monthNames[monthIdx],
            totalDistanceKm = tripsForMonth.sumOf { it.distanceMeters } / 1000.0,
            tripCount = tripsForMonth.size,
            totalDrivingTimeMs = tripsForMonth.sumOf { it.drivingDurationMs }
        )
    }

    // Top locations
    val locationFrequency = mutableMapOf<String, Pair<Int, Double>>()
    yearTrips.forEach { trip ->
        val dist = trip.distanceMeters / 1000.0
        listOf(trip.startLocationName, trip.endLocationName).forEach { loc ->
            val clean = loc.trim()
            if (clean.isNotBlank() && !clean.equals("Unknown", ignoreCase = true)) {
                val current = locationFrequency.getOrDefault(clean, Pair(0, 0.0))
                locationFrequency[clean] = Pair(current.first + 1, current.second + dist)
            }
        }
    }
    val topLocations = locationFrequency.entries
        .sortedByDescending { it.value.first }
        .take(6)
        .map { TopHubLocation(it.key, it.value.first, it.value.second) }

    // Period Comparisons (current calendar month vs prior calendar month)
    val now = Calendar.getInstance()
    val curMonthIdx = now.get(Calendar.MONTH)
    val curMonthTripsList = yearTrips.filter {
        cal.timeInMillis = it.startTime
        cal.get(Calendar.MONTH) == curMonthIdx
    }
    val priorMonthTripsList = yearTrips.filter {
        cal.timeInMillis = it.startTime
        cal.get(Calendar.MONTH) == (curMonthIdx - 1 + 12) % 12
    }
    val curMonthDist = curMonthTripsList.sumOf { it.distanceMeters } / 1000.0
    val priorMonthDist = priorMonthTripsList.sumOf { it.distanceMeters } / 1000.0

    // Vehicle utilization: active driving hours vs total calendar hours in year so far
    val totalYearHours = max(1.0, (System.currentTimeMillis() - Calendar.getInstance().apply { set(selectedYear, 0, 1, 0, 0, 0) }.timeInMillis) / 3_600_000.0)
    val activeDriveHours = totalDrivingMs / 3_600_000.0
    val vehicleUtilizationPct = ((activeDriveHours / totalYearHours) * 100.0).coerceIn(0.0, 100.0)

    return TripDashboardAnalytics(
        selectedYear = selectedYear,
        totalTrips = totalTrips,
        totalDistanceKm = totalDistKm,
        totalDrivingDurationMs = totalDrivingMs,
        totalIdleDurationMs = totalIdleMs,
        totalStoppageDurationMs = totalStoppageMs,
        avgSpeedKmh = avgSpeed,
        maxSpeedKmh = maxSpeed,
        avgTripDistanceKm = avgTripDist,
        avgTripDurationMs = avgTripDur,
        estimatedFuelLiters = estFuelL,
        estimatedFuelCost = estFuelCost,
        avgTripsPerActiveDay = avgTripsPerDay,
        activeDaysCount = activeDaysCount,
        avgDriverScore = avgDriverScore,
        longestTripKm = longestKm,
        longestTripDurationMs = longestDurMs,
        timeOfDay = TimeOfDayDistribution(morningCount, afternoonCount, eveningCount, nightCount),
        timeShare = TimeShareDuration(totalDrivingMs, totalIdleMs, totalStoppageMs),
        distanceTiers = TripDistanceTiers(shortCount, medCount, longCount),
        speedBands = SpeedBandDistribution(lowBandCount, midBandCount, highBandCount),
        weeklyDistribution = weeklyStats,
        monthlyStats = monthlyStats,
        topLocations = topLocations,
        weekdayTripsPercent = weekdayPct,
        weekendTripsPercent = weekendPct,
        hourDayHeatmap = heatmap,
        scatterPoints = scatterList,
        overspeedTripsCount = overspeedCount,
        harshBrakeEvents = harshBrakes,
        rapidAccelEvents = rapidAccels,
        lateNightTripsCount = lateNightCount,
        currentMonthDistanceKm = curMonthDist,
        priorMonthDistanceKm = priorMonthDist,
        currentMonthTrips = curMonthTripsList.size,
        priorMonthTrips = priorMonthTripsList.size,
        vehicleUtilizationPercent = vehicleUtilizationPct
    )
}

// ==================== MAIN COMPOSABLE ====================
@Composable
fun TripStatisticsDashboard(
    trips: List<TripEntity>,
    selectedYear: Int,
    fuelPrice: Double = 96.5,
    modifier: Modifier = Modifier
) {
    val analytics = remember(trips, selectedYear, fuelPrice) {
        computeTripDashboardAnalytics(trips, selectedYear, fuelPrice)
    }

    var currentSection by remember { mutableStateOf(TripStatsSection.OVERVIEW) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF070B12))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Interactive Section Navigation Pill Bar (3 Big Automotive Tabs)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF0C1527))
                .border(1.dp, Color(0xFF1E2E44), RoundedCornerShape(12.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TripStatsSection.values().forEach { section ->
                val isSelected = section == currentSection
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isSelected) Brush.horizontalGradient(
                                listOf(Color(0xFF0284C7), Color(0xFF0369A1))
                            ) else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
                        )
                        .clickable { currentSection = section }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = section.icon,
                            contentDescription = section.label,
                            tint = if (isSelected) Color.White else Color(0xFF64748B),
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = section.label,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                                fontSize = 14.sp,
                                letterSpacing = 0.4.sp
                            ),
                            color = if (isSelected) Color.White else Color(0xFF94A3B8)
                        )
                    }
                }
            }
        }

        // 2. Tab Content Lazy Column (3 Consolidated Sections)
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (currentSection) {
                TripStatsSection.OVERVIEW -> {
                    item { OverviewKpiCardsSection(analytics) }
                    item { TimeAndMovementDistributionSection(analytics) }
                    item { TopVisitedHubsSection(analytics) }
                }
                TripStatsSection.CHARTS -> {
                    item { DetailedDonutChartsSection(analytics) }
                    item { DayOfWeekBarChartSection(analytics) }
                    item { MonthlyTrendChartSection(analytics) }
                    item { HourByDayHeatmapSection(analytics) }
                    item { DistanceVsDurationScatterSection(analytics) }
                }
                TripStatsSection.INSIGHTS -> {
                    item { DrivingSafetyAndBehaviorSection(analytics) }
                    item { AnomalyDetectionSection(analytics) }
                    item { PeriodComparisonSection(analytics) }
                    item { VehicleUtilizationSection(analytics) }
                    item { WeekdayVsWeekendSection(analytics) }
                }
            }
        }
    }
}

// ==================== 1. OVERVIEW & KPI CARDS SECTION ====================
@Composable
private fun OverviewKpiCardsSection(analytics: TripDashboardAnalytics) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Row 1: Primary Metrics
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AutomotiveKpiCard(
                modifier = Modifier.weight(1f),
                title = "TOTAL DISTANCE",
                value = NumberFormatUtils.formatDistance(analytics.totalDistanceKm, 1, useGrouping = true),
                unit = "km",
                subtitle = "${analytics.totalTrips} recorded journeys in ${analytics.selectedYear}",
                accentColor = Color(0xFF00F5FF),
                icon = Icons.Default.DirectionsCar
            )
            AutomotiveKpiCard(
                modifier = Modifier.weight(1f),
                title = "DRIVING DURATION",
                value = formatHoursAndMinutes(analytics.totalDrivingDurationMs),
                unit = "",
                subtitle = "Active vehicle motion time",
                accentColor = Color(0xFF38BDF8),
                icon = Icons.Default.Timer
            )
            AutomotiveKpiCard(
                modifier = Modifier.weight(1f),
                title = "IDLE & STOPPAGE",
                value = formatHoursAndMinutes(analytics.totalIdleDurationMs),
                unit = "",
                subtitle = "Engine on, stopped in traffic",
                accentColor = Color(0xFFF59E0B),
                icon = Icons.Default.HourglassBottom
            )
            AutomotiveKpiCard(
                modifier = Modifier.weight(1f),
                title = "EST. FUEL SPEND",
                value = NumberFormatUtils.formatCurrency(analytics.estimatedFuelCost, 0, useGrouping = true),
                unit = "₹",
                subtitle = "${NumberFormatUtils.formatFuelLiters(analytics.estimatedFuelLiters, 1)} L approx burn",
                accentColor = Color(0xFF10B981),
                icon = Icons.Default.LocalGasStation
            )
        }

        // Row 2: Velocity & Averages
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AutomotiveKpiCard(
                modifier = Modifier.weight(1f),
                title = "AVERAGE VELOCITY",
                value = NumberFormatUtils.formatSpeed(analytics.avgSpeedKmh, 1),
                unit = "km/h",
                subtitle = "Peak velocity: ${NumberFormatUtils.formatSpeedWithUnit(analytics.maxSpeedKmh, 0)}",
                accentColor = Color(0xFFA855F7),
                icon = Icons.Default.Speed
            )
            AutomotiveKpiCard(
                modifier = Modifier.weight(1f),
                title = "AVERAGE TRIP",
                value = NumberFormatUtils.formatDistance(analytics.avgTripDistanceKm, 1),
                unit = "km",
                subtitle = "Avg time: ${formatHoursAndMinutes(analytics.avgTripDurationMs)}",
                accentColor = Color(0xFF06B6D4),
                icon = Icons.AutoMirrored.Filled.TrendingUp
            )
            AutomotiveKpiCard(
                modifier = Modifier.weight(1f),
                title = "DAILY CADENCE",
                value = NumberFormatUtils.formatPrecision(analytics.avgTripsPerActiveDay, 1),
                unit = "trips/day",
                subtitle = "${analytics.activeDaysCount} active driving days",
                accentColor = Color(0xFFEC4899),
                icon = Icons.Default.CalendarToday
            )
            AutomotiveKpiCard(
                modifier = Modifier.weight(1f),
                title = "DRIVER SAFETY SCORE",
                value = "${analytics.avgDriverScore}",
                unit = "/ 100",
                subtitle = when {
                    analytics.avgDriverScore >= 90 -> "EXCELLENT • Smooth & Safe"
                    analytics.avgDriverScore >= 75 -> "GOOD • Moderate Pace"
                    else -> "ATTENTION • Harsh events logged"
                },
                accentColor = if (analytics.avgDriverScore >= 80) Color(0xFF10B981) else Color(0xFFEF4444),
                icon = Icons.Default.Shield
            )
        }
    }
}

// ==================== 2. TIME & MOVEMENT DISTRIBUTION SECTION ====================
@Composable
private fun TimeAndMovementDistributionSection(analytics: TripDashboardAnalytics) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Donut: Time of Day
        CardContainer(
            modifier = Modifier.weight(1f),
            title = "TRIPS BY TIME OF DAY",
            subtitle = "Circadian driving pattern"
        ) {
            val segments = listOf(
                DonutSegment("Morning (06-12)", analytics.timeOfDay.morningCount.toFloat(), Color(0xFF00F5FF)),
                DonutSegment("Afternoon (12-17)", analytics.timeOfDay.afternoonCount.toFloat(), Color(0xFFFFE600)),
                DonutSegment("Evening (17-21)", analytics.timeOfDay.eveningCount.toFloat(), Color(0xFFF97316)),
                DonutSegment("Night (21-06)", analytics.timeOfDay.nightCount.toFloat(), Color(0xFFA855F7))
            )
            DonutChartCanvasWithLegend(
                segments = segments,
                centerLabel = "${analytics.totalTrips}",
                centerSub = "Trips",
                modifier = Modifier.fillMaxWidth().height(160.dp)
            )
        }

        // Donut: Moving vs Idle vs Stoppage
        CardContainer(
            modifier = Modifier.weight(1f),
            title = "VEHICLE TIME ALLOCATION",
            subtitle = "Active driving vs standing idle"
        ) {
            val totalTime = max(1L, analytics.timeShare.movingMs + analytics.timeShare.idleMs + analytics.timeShare.stoppedMs)
            val segments = listOf(
                DonutSegment("Moving Motion", analytics.timeShare.movingMs.toFloat(), Color(0xFF10B981)),
                DonutSegment("Engine Idle", analytics.timeShare.idleMs.toFloat(), Color(0xFFF59E0B)),
                DonutSegment("Stoppage / Wait", analytics.timeShare.stoppedMs.toFloat(), Color(0xFF64748B))
            )
            DonutChartCanvasWithLegend(
                segments = segments,
                centerLabel = "${((analytics.timeShare.movingMs.toDouble() / totalTime) * 100).toInt()}%",
                centerSub = "Moving",
                modifier = Modifier.fillMaxWidth().height(160.dp)
            )
        }
    }
}

// ==================== 3. DETAILED DONUT CHARTS SECTION ====================
@Composable
private fun DetailedDonutChartsSection(analytics: TripDashboardAnalytics) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Distance Tiers (<5km, 5-20km, >20km)
        CardContainer(
            modifier = Modifier.weight(1f),
            title = "TRIP DISTANCE CLASSIFICATION",
            subtitle = "Short commute vs highway journeys"
        ) {
            val segments = listOf(
                DonutSegment("Short (<5 km)", analytics.distanceTiers.shortCount.toFloat(), Color(0xFF38BDF8)),
                DonutSegment("Medium (5-20 km)", analytics.distanceTiers.mediumCount.toFloat(), Color(0xFF00F5FF)),
                DonutSegment("Long (>20 km)", analytics.distanceTiers.longCount.toFloat(), Color(0xFF818CF8))
            )
            DonutChartCanvasWithLegend(
                segments = segments,
                centerLabel = "${analytics.totalTrips}",
                centerSub = "Trips",
                modifier = Modifier.fillMaxWidth().height(160.dp)
            )
        }

        // Speed Bands (0-30, 30-60, 60+)
        CardContainer(
            modifier = Modifier.weight(1f),
            title = "VELOCITY SPECTRUM",
            subtitle = "Traffic crawling vs highway cruising"
        ) {
            val segments = listOf(
                DonutSegment("0-30 km/h (City Crawl)", analytics.speedBands.lowBandCount.toFloat(), Color(0xFFF59E0B)),
                DonutSegment("30-60 km/h (Urban Cruise)", analytics.speedBands.midBandCount.toFloat(), Color(0xFF10B981)),
                DonutSegment("60+ km/h (Highway)", analytics.speedBands.highBandCount.toFloat(), Color(0xFFEC4899))
            )
            DonutChartCanvasWithLegend(
                segments = segments,
                centerLabel = "${analytics.avgSpeedKmh.toInt()}",
                centerSub = "km/h Avg",
                modifier = Modifier.fillMaxWidth().height(160.dp)
            )
        }
    }
}

// ==================== 4. DAY OF WEEK BAR CHART ====================
@Composable
private fun DayOfWeekBarChartSection(analytics: TripDashboardAnalytics) {
    CardContainer(
        title = "DAY-OF-WEEK DRIVING PROFILE (MON - SUN)",
        subtitle = "Frequency and distance pattern across week days"
    ) {
        val peakDay = analytics.weeklyDistribution.maxByOrNull { it.tripCount }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF080E1A))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "PEAK DRIVING DAY: ${peakDay?.dayName?.uppercase() ?: "NONE"} (${peakDay?.tripCount ?: 0} trips • ${NumberFormatUtils.formatDistance(peakDay?.totalDistanceKm ?: 0.0, 1)} km)",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF00F5FF))
            )
            Text(
                text = "Weekday: ${analytics.weekdayTripsPercent.toInt()}%  •  Weekend: ${analytics.weekendTripsPercent.toInt()}%",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color(0xFF94A3B8))
            )
        }

        Spacer(Modifier.height(8.dp))

        // Custom Chart.js Styled Bar Chart Canvas
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
        ) {
            val w = size.width
            val h = size.height
            val leftPad = 45f
            val rightPad = 15f
            val bottomPad = 38f
            val topPad = 25f
            val chartW = w - leftPad - rightPad
            val chartH = h - topPad - bottomPad
            val maxCount = max(1, analytics.weeklyDistribution.maxOfOrNull { it.tripCount } ?: 1)
            val spacing = chartW / 7f
            val barWidth = spacing * 0.52f

            val gridPaint = Paint().apply {
                isAntiAlias = true
                textAlign = Paint.Align.RIGHT
                textSize = 28f
                color = android.graphics.Color.parseColor("#64748B")
                typeface = Typeface.MONOSPACE
            }
            val dayLabelPaint = Paint().apply {
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
                textSize = 30f
                color = android.graphics.Color.parseColor("#CBD5E1")
                typeface = Typeface.DEFAULT_BOLD
            }
            val valuePaint = Paint().apply {
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
                textSize = 30f
                color = android.graphics.Color.WHITE
                typeface = Typeface.MONOSPACE
            }

            // Chart.js Horizontal Grid Lines & Y-Axis Labels
            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
            listOf(0f, 0.5f, 1f).forEach { norm ->
                val gy = (h - bottomPad) - (norm * chartH)
                val countVal = (norm * maxCount).toInt()
                drawLine(
                    color = Color(0xFF1E2E44).copy(alpha = 0.6f),
                    start = Offset(leftPad, gy),
                    end = Offset(w - rightPad, gy),
                    strokeWidth = 1.2f,
                    pathEffect = dashEffect
                )
                drawContext.canvas.nativeCanvas.drawText(
                    "$countVal",
                    leftPad - 10f,
                    gy + 10f,
                    gridPaint
                )
            }

            analytics.weeklyDistribution.forEachIndexed { i, stat ->
                val x = leftPad + spacing * i + (spacing / 2f)
                val barH = if (maxCount > 0) ((stat.tripCount.toFloat() / maxCount) * chartH).coerceAtLeast(8f) else 8f
                val topY = h - bottomPad - barH
                val isPeak = stat.dayIndex == peakDay?.dayIndex && stat.tripCount > 0

                // Chart.js background column track
                drawRoundRect(
                    color = Color(0xFF0F1A2D),
                    topLeft = Offset(x - barWidth / 2f, topPad),
                    size = Size(barWidth, chartH),
                    cornerRadius = CornerRadius(8f, 8f)
                )

                val barBrush = if (isPeak) {
                    Brush.verticalGradient(listOf(Color(0xFF00F5FF), Color(0xFF0284C7)))
                } else if (stat.dayIndex >= 5) {
                    Brush.verticalGradient(listOf(Color(0xFFA855F7), Color(0xFF6B21A8)))
                } else {
                    Brush.verticalGradient(listOf(Color(0xFF38BDF8), Color(0xFF0369A1)))
                }

                // Bar with rounded top
                drawRoundRect(
                    brush = barBrush,
                    topLeft = Offset(x - barWidth / 2f, topY),
                    size = Size(barWidth, barH),
                    cornerRadius = CornerRadius(8f, 8f)
                )

                // Value above bar
                if (stat.tripCount > 0) {
                    drawContext.canvas.nativeCanvas.drawText(
                        "${stat.tripCount}",
                        x,
                        topY - 8f,
                        valuePaint
                    )
                }

                // Day name below bar
                drawContext.canvas.nativeCanvas.drawText(
                    stat.dayName.uppercase(),
                    x,
                    h - 8f,
                    dayLabelPaint
                )
            }
        }
    }
}

// ==================== 5. MONTHLY TREND CHART ====================
@Composable
private fun MonthlyTrendChartSection(analytics: TripDashboardAnalytics) {
    CardContainer(
        title = "ANNUAL DISTANCE TRAJECTORY (12 MONTHS)",
        subtitle = "Monthly accumulated kilometers driven"
    ) {
        val peakMonth = analytics.monthlyStats.maxByOrNull { it.totalDistanceKm }
        val activeMonths = analytics.monthlyStats.filter { it.totalDistanceKm > 0 }
        val avgMonthlyKm = if (activeMonths.isNotEmpty()) activeMonths.map { it.totalDistanceKm }.average() else 0.0

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF080E1A))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "PEAK: ${peakMonth?.monthShortName?.uppercase() ?: "NONE"} (${NumberFormatUtils.formatDistance(peakMonth?.totalDistanceKm ?: 0.0, 1)} km)",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFFFFE600))
            )
            Text(
                text = "Monthly Avg: ${NumberFormatUtils.formatDistance(avgMonthlyKm, 1)} km  •  Total: ${NumberFormatUtils.formatDistance(analytics.totalDistanceKm, 1)} km",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color(0xFF94A3B8))
            )
        }

        Spacer(Modifier.height(8.dp))

        // Chart.js Styled Line & Area Chart Canvas with Catmull-Rom Tension
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp)
        ) {
            val w = size.width
            val h = size.height
            val leftPad = 80f
            val rightPad = 20f
            val bottomPad = 38f
            val topPad = 25f
            val chartW = w - leftPad - rightPad
            val chartH = h - topPad - bottomPad
            val maxKm = max(10.0, analytics.monthlyStats.maxOfOrNull { it.totalDistanceKm } ?: 10.0)
            val spacing = chartW / 12f

            val gridPaint = Paint().apply {
                isAntiAlias = true
                textAlign = Paint.Align.RIGHT
                textSize = 28f
                color = android.graphics.Color.parseColor("#64748B")
                typeface = Typeface.MONOSPACE
            }
            val labelPaint = Paint().apply {
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
                textSize = 30f
                color = android.graphics.Color.parseColor("#CBD5E1")
                typeface = Typeface.DEFAULT_BOLD
            }

            // Chart.js Horizontal Gridlines
            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
            listOf(0f, 0.33f, 0.66f, 1f).forEach { norm ->
                val gy = (h - bottomPad) - (norm * chartH)
                val kmVal = (norm * maxKm).toInt()
                drawLine(
                    color = Color(0xFF1E2E44).copy(alpha = 0.6f),
                    start = Offset(leftPad, gy),
                    end = Offset(w - rightPad, gy),
                    strokeWidth = 1.2f,
                    pathEffect = dashEffect
                )
                drawContext.canvas.nativeCanvas.drawText(
                    "${kmVal}km",
                    leftPad - 10f,
                    gy + 10f,
                    gridPaint
                )
            }

            val path = Path()
            val fillPath = Path()
            val points = mutableListOf<Offset>()

            analytics.monthlyStats.forEachIndexed { i, stat ->
                val x = leftPad + spacing * i + (spacing / 2f)
                val norm = (stat.totalDistanceKm / maxKm).toFloat().coerceIn(0f, 1f)
                val y = (h - bottomPad) - (norm * chartH)
                points.add(Offset(x, y))
            }

            if (points.isNotEmpty()) {
                path.moveTo(points.first().x, points.first().y)
                fillPath.moveTo(points.first().x, h - bottomPad)
                fillPath.lineTo(points.first().x, points.first().y)

                // Chart.js smooth cubic bezier tension algorithm
                for (i in 0 until points.size - 1) {
                    val p0 = if (i > 0) points[i - 1] else points[i]
                    val p1 = points[i]
                    val p2 = points[i + 1]
                    val p3 = if (i + 2 < points.size) points[i + 2] else p2
                    val cp1x = p1.x + (p2.x - p0.x) * 0.2f
                    val cp1y = p1.y + (p2.y - p0.y) * 0.2f
                    val cp2x = p2.x - (p3.x - p1.x) * 0.2f
                    val cp2y = p2.y - (p3.y - p1.y) * 0.2f
                    path.cubicTo(cp1x, cp1y, cp2x, cp2y, p2.x, p2.y)
                    fillPath.cubicTo(cp1x, cp1y, cp2x, cp2y, p2.x, p2.y)
                }

                fillPath.lineTo(points.last().x, h - bottomPad)
                fillPath.close()

                // Draw gradient underfill
                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        listOf(Color(0xFF00F5FF).copy(alpha = 0.35f), Color.Transparent),
                        startY = topPad,
                        endY = h - bottomPad
                    )
                )

                // Draw stroke
                drawPath(
                    path = path,
                    color = Color(0xFF00F5FF),
                    style = Stroke(width = 4.5f, cap = StrokeCap.Round)
                )

                points.forEachIndexed { i, pt ->
                    // Chart.js signature hollow ring point
                    if (analytics.monthlyStats[i].totalDistanceKm > 0) {
                        drawCircle(color = Color(0xFF00F5FF), radius = 7.5f, center = pt)
                        drawCircle(color = Color(0xFF070B12), radius = 4f, center = pt)
                    }
                    // Month label
                    drawContext.canvas.nativeCanvas.drawText(
                        analytics.monthlyStats[i].monthShortName.uppercase(),
                        pt.x,
                        h - 8f,
                        labelPaint
                    )
                }
            }
        }
    }
}

// ==================== 6. HOUR × DAY HEATMAP ====================
@Composable
private fun HourByDayHeatmapSection(analytics: TripDashboardAnalytics) {
    CardContainer(
        title = "DRIVING INTENSITY HEATMAP (HOUR × DAY OF WEEK)",
        subtitle = "Peak mobility time slots throughout the week"
    ) {
        val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        var maxIntensity = 1
        for (r in 0..6) {
            for (c in 0..23) {
                if (analytics.hourDayHeatmap[r][c] > maxIntensity) {
                    maxIntensity = analytics.hourDayHeatmap[r][c]
                }
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp)
        ) {
            val w = size.width
            val h = size.height
            val leftMargin = 60f
            val topMargin = 20f
            val bottomMargin = 25f
            val gridW = w - leftMargin
            val gridH = h - topMargin - bottomMargin
            val cellW = gridW / 24f
            val cellH = gridH / 7f

            val dayLabelPaint = Paint().apply {
                isAntiAlias = true
                textAlign = Paint.Align.RIGHT
                textSize = 22f
                color = android.graphics.Color.parseColor("#94A3B8")
                typeface = Typeface.DEFAULT_BOLD
            }
            val hourLabelPaint = Paint().apply {
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
                textSize = 20f
                color = android.graphics.Color.parseColor("#64748B")
            }

            for (r in 0..6) {
                val y = topMargin + r * cellH
                drawContext.canvas.nativeCanvas.drawText(
                    days[r],
                    leftMargin - 12f,
                    y + cellH * 0.72f,
                    dayLabelPaint
                )
                for (c in 0..23) {
                    val count = analytics.hourDayHeatmap[r][c]
                    val x = leftMargin + c * cellW
                    val intensity = if (count > 0) (count.toFloat() / maxIntensity).coerceIn(0.18f, 1f) else 0f
                    val cellColor = if (count == 0) {
                        Color(0xFF0F1A2E)
                    } else {
                        Color(0xFF00F5FF).copy(alpha = intensity)
                    }

                    drawRoundRect(
                        color = cellColor,
                        topLeft = Offset(x + 1f, y + 1f),
                        size = Size(cellW - 2f, cellH - 2f),
                        cornerRadius = CornerRadius(3f, 3f)
                    )
                }
            }

            // Draw hour labels every 3 hours
            for (hr in 0..23 step 3) {
                val x = leftMargin + (hr + 0.5f) * cellW
                drawContext.canvas.nativeCanvas.drawText(
                    "${hr}h",
                    x,
                    h - 4f,
                    hourLabelPaint
                )
            }
        }
    }
}

// ==================== 7. DISTANCE VS DURATION SCATTER PLOT ====================
@Composable
private fun DistanceVsDurationScatterSection(analytics: TripDashboardAnalytics) {
    CardContainer(
        title = "DISTANCE VS DURATION CORRELATION (SCATTER)",
        subtitle = "Identify congested delays vs open highway flows"
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF080E1A))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF00F5FF)))
                Text("Normal / Highway Cruise", style = MaterialTheme.typography.labelMedium.copy(color = Color(0xFFCBD5E1), fontSize = 12.5.sp, fontWeight = FontWeight.Bold))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFEF4444)))
                Text("Heavy Traffic / Delay (>10m & <18km/h)", style = MaterialTheme.typography.labelMedium.copy(color = Color(0xFFEF4444), fontSize = 12.5.sp, fontWeight = FontWeight.Bold))
            }
        }

        Spacer(Modifier.height(8.dp))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
        ) {
            val w = size.width
            val h = size.height
            val leftPad = 65f
            val bottomPad = 38f
            val topPad = 25f
            val rightPad = 25f

            val plotW = w - leftPad - rightPad
            val plotH = h - bottomPad - topPad

            val maxDur = max(30.0, analytics.scatterPoints.maxOfOrNull { it.durationMin } ?: 30.0)
            val maxDist = max(15.0, analytics.scatterPoints.maxOfOrNull { it.distanceKm } ?: 15.0)

            // Chart.js Horizontal & Vertical dashed gridlines
            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
            listOf(0.25f, 0.5f, 0.75f, 1f).forEach { norm ->
                val gy = (h - bottomPad) - (norm * plotH)
                drawLine(
                    color = Color(0xFF1E2E44).copy(alpha = 0.5f),
                    start = Offset(leftPad, gy),
                    end = Offset(w - rightPad, gy),
                    strokeWidth = 1.2f,
                    pathEffect = dashEffect
                )
            }

            val axisPaint = Paint().apply {
                isAntiAlias = true
                textSize = 28f
                color = android.graphics.Color.parseColor("#94A3B8")
                typeface = Typeface.MONOSPACE
            }
            drawContext.canvas.nativeCanvas.drawText("0m", leftPad - 15f, h - 8f, axisPaint)
            drawContext.canvas.nativeCanvas.drawText("${maxDur.toInt()}m", w - rightPad - 35f, h - 8f, axisPaint)
            drawContext.canvas.nativeCanvas.drawText("${maxDist.toInt()}km", 5f, topPad + 18f, axisPaint)

            // Plot dots with glowing halos
            analytics.scatterPoints.forEach { pt ->
                val x = leftPad + ((pt.durationMin / maxDur).toFloat().coerceIn(0f, 1f) * plotW)
                val y = (h - bottomPad) - ((pt.distanceKm / maxDist).toFloat().coerceIn(0f, 1f) * plotH)

                val dotColor = if (pt.isCongested) Color(0xFFEF4444) else Color(0xFF00F5FF)
                drawCircle(color = dotColor.copy(alpha = 0.28f), radius = 9f, center = Offset(x, y))
                drawCircle(color = dotColor, radius = 4.5f, center = Offset(x, y))
                drawCircle(color = Color.White, radius = 2f, center = Offset(x, y))
            }
        }
    }
}

// ==================== 8. DRIVING SAFETY & BEHAVIOR SECTION ====================
@Composable
private fun DrivingSafetyAndBehaviorSection(analytics: TripDashboardAnalytics) {
    CardContainer(
        title = "DRIVING SAFETY & TELEMETRIC EVENTS",
        subtitle = "Telemetry analysis of speed stability and maneuvers"
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AutomotiveMetricPillCard(
                modifier = Modifier.weight(1f),
                title = "OVERSPEED EVENTS",
                value = "${analytics.overspeedTripsCount}",
                status = if (analytics.overspeedTripsCount == 0) "NONE DETECTED" else "Trips exceeded 85 km/h",
                color = if (analytics.overspeedTripsCount == 0) Color(0xFF10B981) else Color(0xFFEF4444),
                icon = Icons.Default.Speed
            )
            AutomotiveMetricPillCard(
                modifier = Modifier.weight(1f),
                title = "RAPID ACCELERATIONS",
                value = "${analytics.rapidAccelEvents}",
                status = if (analytics.rapidAccelEvents == 0) "SMOOTH PACE" else "High throttle variance",
                color = if (analytics.rapidAccelEvents <= 2) Color(0xFF10B981) else Color(0xFFF59E0B),
                icon = Icons.AutoMirrored.Filled.TrendingUp
            )
            AutomotiveMetricPillCard(
                modifier = Modifier.weight(1f),
                title = "HARSH BRAKING",
                value = "${analytics.harshBrakeEvents}",
                status = if (analytics.harshBrakeEvents == 0) "OPTIMAL" else "Sudden deceleration",
                color = if (analytics.harshBrakeEvents <= 2) Color(0xFF10B981) else Color(0xFFEF4444),
                icon = Icons.Default.Warning
            )
            AutomotiveMetricPillCard(
                modifier = Modifier.weight(1f),
                title = "LATE NIGHT RUNS",
                value = "${analytics.lateNightTripsCount}",
                status = "Trips between 11 PM - 4 AM",
                color = if (analytics.lateNightTripsCount == 0) Color(0xFF38BDF8) else Color(0xFFA855F7),
                icon = Icons.Default.NightsStay
            )
        }
    }
}

// ==================== 9. TOP VISITED HUBS SECTION ====================
@Composable
private fun TopVisitedHubsSection(analytics: TripDashboardAnalytics) {
    CardContainer(
        title = "FREQUENTLY VISITED HUBS & DESTINATIONS",
        subtitle = "Ranked origin and terminus points by visit count"
    ) {
        if (analytics.topLocations.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().height(100.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("No designated destination tags recorded yet", color = Color(0xFF64748B), style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val maxVisits = max(1, analytics.topLocations.first().visitCount)
                analytics.topLocations.forEachIndexed { i, hub ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0C1527))
                            .border(1.dp, Color(0xFF1E2E44), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(if (i == 0) Color(0xFF00F5FF) else Color(0xFF1E2E44)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${i + 1}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black, fontSize = 11.sp),
                                    color = if (i == 0) Color.Black else Color.White
                                )
                            }
                            Column {
                                Text(
                                    text = hub.locationName,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, fontSize = 13.sp),
                                    color = Color.White
                                )
                                Text(
                                    text = "${NumberFormatUtils.formatDistance(hub.totalDistanceKm, 1)} km accumulated transit",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                    color = Color(0xFF64748B)
                                )
                            }
                        }

                        // Progress Bar & Count
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(120.dp)
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color(0xFF172338))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(fraction = (hub.visitCount.toFloat() / maxVisits).coerceIn(0.05f, 1f))
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(Brush.horizontalGradient(listOf(Color(0xFF00F5FF), Color(0xFF38BDF8))))
                                )
                            }
                            Text(
                                text = "${hub.visitCount} visits",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                                color = Color(0xFF00F5FF)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==================== 10. ANOMALY DETECTION ====================
@Composable
private fun AnomalyDetectionSection(analytics: TripDashboardAnalytics) {
    CardContainer(
        title = "JOURNEY ANOMALIES & AUDIT",
        subtitle = "Automatic anomaly recognition and efficiency alerts"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AuditAnomalyItem(
                title = "LONGEST RUN RECORDED",
                description = "Single journey of ${NumberFormatUtils.formatDistance(analytics.longestTripKm, 1)} km lasting ${formatHoursAndMinutes(analytics.longestTripDurationMs)}",
                isWarning = false,
                accentColor = Color(0xFF00F5FF),
                icon = Icons.Default.DirectionsCar
            )
            AuditAnomalyItem(
                title = "PEAK RECORDED VELOCITY",
                description = "Maximum burst reached ${NumberFormatUtils.formatSpeedWithUnit(analytics.maxSpeedKmh, 1)}",
                isWarning = analytics.maxSpeedKmh > 110.0,
                accentColor = if (analytics.maxSpeedKmh > 110.0) Color(0xFFEF4444) else Color(0xFFFFE600),
                icon = Icons.Default.Speed
            )
            AuditAnomalyItem(
                title = "IDLE RATIO AUDIT",
                description = "Idle time is ${((analytics.totalIdleDurationMs.toDouble() / max(1L, analytics.totalDrivingDurationMs + analytics.totalIdleDurationMs)) * 100).toInt()}% of total engine runtime",
                isWarning = analytics.totalIdleDurationMs > analytics.totalDrivingDurationMs * 0.4,
                accentColor = if (analytics.totalIdleDurationMs > analytics.totalDrivingDurationMs * 0.4) Color(0xFFF59E0B) else Color(0xFF10B981),
                icon = Icons.Default.HourglassBottom
            )
        }
    }
}

// ==================== 11. PERIOD COMPARISONS ====================
@Composable
private fun PeriodComparisonSection(analytics: TripDashboardAnalytics) {
    CardContainer(
        title = "PERIOD-OVER-PERIOD COMPARISON (MONTH OVER MONTH)",
        subtitle = "Current active month vs previous month"
    ) {
        val distDelta = analytics.currentMonthDistanceKm - analytics.priorMonthDistanceKm
        val distDeltaPct = if (analytics.priorMonthDistanceKm > 0) ((distDelta / analytics.priorMonthDistanceKm) * 100) else 0.0

        val tripDelta = analytics.currentMonthTrips - analytics.priorMonthTrips

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ComparisonCard(
                modifier = Modifier.weight(1f),
                title = "DISTANCE (KM)",
                current = NumberFormatUtils.formatDistance(analytics.currentMonthDistanceKm, 1),
                previous = NumberFormatUtils.formatDistance(analytics.priorMonthDistanceKm, 1),
                diffPercent = distDeltaPct,
                isHigherBetter = false
            )
            ComparisonCard(
                modifier = Modifier.weight(1f),
                title = "TRIP COUNT",
                current = "${analytics.currentMonthTrips}",
                previous = "${analytics.priorMonthTrips}",
                diffPercent = if (analytics.priorMonthTrips > 0) ((tripDelta.toDouble() / analytics.priorMonthTrips) * 100) else 0.0,
                isHigherBetter = true
            )
        }
    }
}

// ==================== 12. VEHICLE UTILIZATION & WEEKDAY/WEEKEND ====================
@Composable
private fun VehicleUtilizationSection(analytics: TripDashboardAnalytics) {
    CardContainer(
        title = "VEHICLE UTILIZATION RATE",
        subtitle = "Percentage of annual calendar hours actively in motion"
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF0C1527))
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${NumberFormatUtils.formatPrecision(analytics.vehicleUtilizationPercent, 1)}%",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black, fontSize = 28.sp),
                    color = Color(0xFF00F5FF)
                )
                Text(
                    text = "Fleet Duty Cycle (${formatHoursAndMinutes(analytics.totalDrivingDurationMs)} on road)",
                    style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFF94A3B8), fontSize = 12.sp)
                )
            }

            Box(
                modifier = Modifier
                    .width(180.dp)
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0xFF172338))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction = (analytics.vehicleUtilizationPercent.toFloat() / 100f).coerceIn(0.02f, 1f))
                        .clip(RoundedCornerShape(5.dp))
                        .background(Brush.horizontalGradient(listOf(Color(0xFF00F5FF), Color(0xFF10B981))))
                )
            }
        }
    }
}

@Composable
private fun WeekdayVsWeekendSection(analytics: TripDashboardAnalytics) {
    CardContainer(
        title = "WEEKDAY VS WEEKEND ACTIVITY SHARE",
        subtitle = "Commute pattern split"
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF080E1A))
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("WEEKDAY COMMUTE", style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFF64748B), fontWeight = FontWeight.Bold))
                Text("${analytics.weekdayTripsPercent.toInt()}%", style = MaterialTheme.typography.titleLarge.copy(color = Color(0xFF38BDF8), fontWeight = FontWeight.Black))
            }
            Box(
                modifier = Modifier
                    .weight(2f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFFA855F7))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction = (analytics.weekdayTripsPercent.toFloat() / 100f).coerceIn(0f, 1f))
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF38BDF8))
                )
            }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text("WEEKEND LEISURE", style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFF64748B), fontWeight = FontWeight.Bold))
                Text("${analytics.weekendTripsPercent.toInt()}%", style = MaterialTheme.typography.titleLarge.copy(color = Color(0xFFA855F7), fontWeight = FontWeight.Black))
            }
        }
    }
}

// ==================== REUSABLE ATOMIC CARDS & CONTAINERS ====================
@Composable
private fun CardContainer(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF0C1527))
            .border(1.2.dp, Color(0xFF1E2E44), RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title.uppercase(),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Black,
                        fontSize = 15.sp,
                        letterSpacing = 0.5.sp
                    ),
                    color = Color.White
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 12.5.sp,
                        color = Color(0xFF64748B)
                    )
                )
            }
            content()
        }
    }
}

@Composable
private fun AutomotiveKpiCard(
    title: String,
    value: String,
    unit: String,
    subtitle: String,
    accentColor: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0C1527))
            .border(1.2.dp, Color(0xFF1E2E44), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        letterSpacing = 0.5.sp
                    ),
                    color = Color(0xFF94A3B8)
                )
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
            }

            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Black,
                        fontSize = 26.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    color = Color.White
                )
                if (unit.isNotBlank()) {
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        ),
                        color = accentColor,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
            }

            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 12.sp,
                    color = Color(0xFF64748B)
                ),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun AutomotiveMetricPillCard(
    title: String,
    value: String,
    status: String,
    color: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF080E1A))
            .border(1.dp, Color(0xFF172338), RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = title, style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8)))
                Icon(imageVector = icon, contentDescription = title, tint = color, modifier = Modifier.size(16.dp))
            }
            Text(text = value, style = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp, fontWeight = FontWeight.Black, color = color))
            Text(text = status, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp, color = Color(0xFF64748B)), maxLines = 1)
        }
    }
}

@Composable
private fun AuditAnomalyItem(
    title: String,
    description: String,
    isWarning: Boolean,
    accentColor: Color,
    icon: ImageVector
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF080E1A))
            .border(1.dp, if (isWarning) Color(0xFFEF4444).copy(alpha = 0.5f) else Color(0xFF172338), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(accentColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = title, tint = accentColor, modifier = Modifier.size(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Black, fontSize = 12.sp, color = Color.White))
            Text(text = description, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, color = Color(0xFF94A3B8)))
        }
    }
}

@Composable
private fun ComparisonCard(
    title: String,
    current: String,
    previous: String,
    diffPercent: Double,
    isHigherBetter: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF080E1A))
            .border(1.dp, Color(0xFF172338), RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color(0xFF87929A)))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("CURRENT", style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, color = Color(0xFF64748B)))
                    Text(current, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black, fontSize = 16.sp, color = Color.White))
                }
                Column {
                    Text("PREVIOUS", style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, color = Color(0xFF64748B)))
                    Text(previous, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(0xFF64748B)))
                }
                val isPositive = diffPercent > 0.001
                val badgeColor = if (isHigherBetter) {
                    if (isPositive) Color(0xFF10B981) else Color(0xFFEF4444)
                } else {
                    if (isPositive) Color(0xFFEF4444) else Color(0xFF10B981)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(badgeColor.copy(alpha = 0.2f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "${if (isPositive) "+" else ""}${NumberFormatUtils.formatPrecision(diffPercent, 1)}%",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black, fontSize = 11.sp, color = badgeColor)
                    )
                }
            }
        }
    }
}

// ==================== DONUT CANVAS WITH LEGEND ====================
data class DonutSegment(
    val label: String,
    val value: Float,
    val color: Color
)

@Composable
fun DonutChartCanvasWithLegend(
    segments: List<DonutSegment>,
    centerLabel: String,
    centerSub: String,
    modifier: Modifier = Modifier
) {
    val total = max(1f, segments.sumOf { it.value.toDouble() }.toFloat())

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Donut Canvas
        Box(
            modifier = Modifier
                .size(145.dp)
                .padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeW = 22f
                val radius = (min(size.width, size.height) - strokeW) / 2f
                val centerOffset = Offset(size.width / 2f, size.height / 2f)

                var startAngle = -90f
                segments.forEach { seg ->
                    val sweep = if (total > 0f) (seg.value / total) * 360f else 0f
                    if (sweep > 0.5f) {
                        drawArc(
                            color = seg.color,
                            startAngle = startAngle,
                            sweepAngle = sweep - 2f,
                            useCenter = false,
                            topLeft = Offset(centerOffset.x - radius, centerOffset.y - radius),
                            size = Size(radius * 2f, radius * 2f),
                            style = Stroke(width = strokeW, cap = StrokeCap.Round)
                        )
                    }
                    startAngle += sweep
                }
            }

            // Center Text (Large & Clear)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = centerLabel,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black, fontSize = 22.sp),
                    color = Color.White
                )
                Text(
                    text = centerSub,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                    color = Color(0xFF94A3B8)
                )
            }
        }

        // Legend Pills Column (Big Readable Labels & Percentages)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            segments.forEach { seg ->
                val pct = if (total > 0f) ((seg.value / total) * 100).toInt() else 0
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(seg.color)
                        )
                        Text(
                            text = seg.label,
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
                            color = Color(0xFFCBD5E1)
                        )
                    }
                    Text(
                        text = "$pct%",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, fontSize = 13.5.sp),
                        color = Color.White
                    )
                }
            }
        }
    }
}

// ==================== UTILITY FORMATTERS ====================
private fun formatHoursAndMinutes(ms: Long): String {
    if (ms <= 0L) return "0 min"
    val totalMinutes = ms / 60000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours > 0L -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}
