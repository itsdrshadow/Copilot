package com.antigravity.copilot.feature.fuel

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
import com.antigravity.copilot.core.database.FuelEventEntity
import com.antigravity.copilot.core.database.TripEntity
import com.antigravity.copilot.core.util.NumberFormatUtils
import java.util.*
import kotlin.math.max
import kotlin.math.min

/**
 * Modern High-Fidelity Automotive Fuel Statistics Dashboard.
 * Formatted for Cockpit landscape displays (1280x800) with complete
 * financial KPIs, Station brand share, Combo spend/mileage charts,
 * Price & Efficiency trend curves, Heatmaps, Scatter plots,
 * CO₂ emissions, Forecasting, and GPS + Fuel cross-telemetry integration.
 */

// ==================== DASHBOARD TAB SELECTOR ====================
enum class FuelStatsSection(val label: String, val icon: ImageVector) {
    OVERVIEW("Overview & KPIs", Icons.Default.AccountBalanceWallet),
    CHARTS("Charts & Trends", Icons.Default.BarChart),
    ANALYTICS("Forecast & Analytics", Icons.Default.AutoGraph)
}

// ==================== DATA STRUCTURES ====================
data class StationBrandShare(
    val brandName: String,
    val liters: Double,
    val totalCost: Double,
    val fillCount: Int,
    val avgPrice: Double,
    val color: Color
)

data class MonthlyFuelComboStat(
    val monthIndex: Int,
    val monthShortName: String,
    val totalCost: Double,
    val totalLiters: Double,
    val fillCount: Int,
    val mileageKmpl: Double,
    val avgPricePerLiter: Double
)

data class FuelScatterPoint(
    val eventId: Long,
    val liters: Double,
    val amount: Double,
    val pricePerLiter: Double,
    val odometerKm: Double,
    val mileageKmpl: Double
)

data class FuelDashboardAnalytics(
    val selectedYear: Int,
    // Core KPIs
    val totalCost: Double,
    val totalLiters: Double,
    val totalFillUps: Int,
    val avgFleetMileageKmpl: Double,
    val costPerKm: Double,
    val avgPricePerLiter: Double,
    val avgFillUpLiters: Double,
    val avgFillUpCost: Double,
    val avgDaysBetweenFills: Double,
    val avgKmBetweenFills: Double,
    val bestMileageKmpl: Double,
    val worstMileageKmpl: Double,
    val monthlyAvgSpend: Double,
    val carbonFootprintKg: Double,
    // Station Brand Breakdown
    val stationShares: List<StationBrandShare>,
    val cheapestStation: StationBrandShare?,
    val mostExpensiveStation: StationBrandShare?,
    val potentialSavingsIfCheapest: Double,
    // Monthly & Quarterly
    val monthlyStats: List<MonthlyFuelComboStat>,
    val q1Spend: Double,
    val q2Spend: Double,
    val q3Spend: Double,
    val q4Spend: Double,
    // Timing & Heatmap
    val weekdayFillsPercent: Double,
    val weekendFillsPercent: Double,
    val fillSizeTiers: Triple<Int, Int, Int>, // <20L, 20-40L, >40L
    val dowWomHeatmap: Array<IntArray>, // 7 DOW x 5 Weeks in Month
    val scatterPoints: List<FuelScatterPoint>,
    // Forecast & Anomalies
    val nextMonthForecastSpend: Double,
    val estimatedDaysUntilNextRefill: Int,
    val largeFillAnomalies: Int,
    val lowMileageAnomalies: Int,
    // MoM Comparisons
    val currentMonthSpend: Double,
    val priorMonthSpend: Double,
    val currentMonthLiters: Double,
    val priorMonthLiters: Double,
    // GPS Cross Telemetry
    val totalGpsDistanceKm: Double,
    val fuelOdometerDeltaKm: Double,
    val actualRouteCostPerKm: Double
)

// ==================== AGGREGATOR FUNCTION ====================
fun computeFuelDashboardAnalytics(
    allEvents: List<FuelEventEntity>,
    allTrips: List<TripEntity>,
    selectedYear: Int
): FuelDashboardAnalytics {
    val cal = Calendar.getInstance()
    val yearEvents = allEvents.filter { event ->
        cal.timeInMillis = event.timestamp
        cal.get(Calendar.YEAR) == selectedYear && event.eventType.equals("REFUEL", ignoreCase = true)
    }.sortedBy { it.timestamp }

    val totalCost = yearEvents.sumOf { it.totalCost }
    val totalLiters = yearEvents.sumOf { it.liters }
    val totalFills = yearEvents.size

    val validMileageEvents = yearEvents.filter { it.calculatedMileageKmpl > 0.0 }
    val avgMileage = if (validMileageEvents.isNotEmpty()) {
        validMileageEvents.map { it.calculatedMileageKmpl }.average()
    } else 0.0

    val bestMileage = validMileageEvents.maxOfOrNull { it.calculatedMileageKmpl } ?: 0.0
    val worstMileage = validMileageEvents.minOfOrNull { it.calculatedMileageKmpl } ?: 0.0

    val avgPricePerL = if (totalLiters > 0.0) totalCost / totalLiters else 0.0
    val avgFillL = if (totalFills > 0) totalLiters / totalFills else 0.0
    val avgFillC = if (totalFills > 0) totalCost / totalFills else 0.0

    // Refill interval cadence (days & km)
    var totalDaysDiff = 0.0
    var totalKmDiff = 0.0
    var intervalsCount = 0
    for (i in 1 until yearEvents.size) {
        val prev = yearEvents[i - 1]
        val curr = yearEvents[i]
        val days = (curr.timestamp - prev.timestamp) / (1000.0 * 60 * 60 * 24)
        val km = curr.odometerKm - prev.odometerKm
        if (days in 0.1..90.0) {
            totalDaysDiff += days
            intervalsCount++
        }
        if (km in 5.0..3000.0) {
            totalKmDiff += km
        }
    }
    val avgDaysBetween = if (intervalsCount > 0) totalDaysDiff / intervalsCount else 0.0
    val avgKmBetween = if (intervalsCount > 0) totalKmDiff / intervalsCount else 0.0

    // Odometer Delta
    val firstOdo = yearEvents.firstOrNull { it.odometerKm > 0 }?.odometerKm ?: 0.0
    val lastOdo = yearEvents.lastOrNull { it.odometerKm > 0 }?.odometerKm ?: 0.0
    val fuelOdoDelta = max(0.0, lastOdo - firstOdo)

    val costPerKm = if (fuelOdoDelta > 0.0) totalCost / fuelOdoDelta else (if (avgMileage > 0.0) avgPricePerL / avgMileage else 0.0)

    // Carbon Footprint: Petrol ~2.31 kg CO2 / L
    val carbonFootprint = totalLiters * 2.31

    // Station Brand Classification
    val brandColors = listOf(
        Color(0xFF00F5FF), // IndianOil
        Color(0xFFFFE600), // HPCL
        Color(0xFF10B981), // BPCL
        Color(0xFFEF4444), // Shell
        Color(0xFFA855F7), // Nayara
        Color(0xFFFB923C), // Jio-bp
        Color(0xFF64748B)  // Others
    )

    val brandBuckets = mutableMapOf<String, MutableList<FuelEventEntity>>()
    yearEvents.forEach { ev ->
        val loc = "${ev.locationName} ${ev.notes}".uppercase()
        val brand = when {
            loc.contains("INDIAN") || loc.contains("IOCL") || loc.contains("INDIANOIL") -> "IndianOil"
            loc.contains("HP") || loc.contains("HINDUSTAN") || loc.contains("HPCL") -> "HPCL"
            loc.contains("BPCL") || loc.contains("BHARAT") -> "BPCL"
            loc.contains("SHELL") -> "Shell"
            loc.contains("NAYARA") || loc.contains("ESSAR") -> "Nayara"
            loc.contains("JIO") || loc.contains("RELIANCE") -> "Jio-bp"
            else -> "Other / Retail"
        }
        brandBuckets.getOrPut(brand) { mutableListOf() }.add(ev)
    }

    var colorIdx = 0
    val stationShares = brandBuckets.entries.map { entry ->
        val bEvents = entry.value
        val bLit = bEvents.sumOf { it.liters }
        val bCost = bEvents.sumOf { it.totalCost }
        val bAvgPrice = if (bLit > 0.0) bCost / bLit else 0.0
        val c = brandColors[colorIdx % brandColors.size]
        colorIdx++
        StationBrandShare(
            brandName = entry.key,
            liters = bLit,
            totalCost = bCost,
            fillCount = bEvents.size,
            avgPrice = bAvgPrice,
            color = c
        )
    }.sortedByDescending { it.liters }

    val cheapestStation = stationShares.minByOrNull { it.avgPrice }
    val mostExpensiveStation = stationShares.maxByOrNull { it.avgPrice }
    val minPrice = cheapestStation?.avgPrice ?: avgPricePerL
    val potentialSavings = max(0.0, (avgPricePerL - minPrice) * totalLiters)

    // Monthly stats
    val monthNames = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val monthlyStats = (0..11).map { monthIdx ->
        val mEvents = yearEvents.filter {
            cal.timeInMillis = it.timestamp
            cal.get(Calendar.MONTH) == monthIdx
        }
        val mCost = mEvents.sumOf { it.totalCost }
        val mLit = mEvents.sumOf { it.liters }
        val mMileageList = mEvents.filter { it.calculatedMileageKmpl > 0.0 }
        val mMileage = if (mMileageList.isNotEmpty()) mMileageList.map { it.calculatedMileageKmpl }.average() else 0.0
        val mAvgPrice = if (mLit > 0.0) mCost / mLit else 0.0
        MonthlyFuelComboStat(
            monthIndex = monthIdx,
            monthShortName = monthNames[monthIdx],
            totalCost = mCost,
            totalLiters = mLit,
            fillCount = mEvents.size,
            mileageKmpl = mMileage,
            avgPricePerLiter = mAvgPrice
        )
    }

    val activeMonths = monthlyStats.filter { it.totalCost > 0.0 }
    val monthlyAvgSpend = if (activeMonths.isNotEmpty()) activeMonths.map { it.totalCost }.average() else 0.0

    val q1Spend = monthlyStats.take(3).sumOf { it.totalCost }
    val q2Spend = monthlyStats.drop(3).take(3).sumOf { it.totalCost }
    val q3Spend = monthlyStats.drop(6).take(3).sumOf { it.totalCost }
    val q4Spend = monthlyStats.drop(9).take(3).sumOf { it.totalCost }

    // Weekday vs Weekend & Size Tiers & Heatmap
    var weekdayFills = 0
    var weekendFills = 0
    var tierSmall = 0  // <20L
    var tierMedium = 0 // 20-40L
    var tierLarge = 0  // >40L

    val heatmap = Array(7) { IntArray(5) } // 7 DOW x 5 WOM

    var largeAnomalies = 0
    var lowMileageCount = 0

    val scatterPoints = mutableListOf<FuelScatterPoint>()

    yearEvents.forEach { ev ->
        cal.timeInMillis = ev.timestamp
        val calDow = cal.get(Calendar.DAY_OF_WEEK)
        val dowIdx = if (calDow == Calendar.SUNDAY) 6 else (calDow - Calendar.MONDAY)
        val wom = (cal.get(Calendar.DAY_OF_MONTH) - 1) / 7

        if (dowIdx in 0..6 && wom in 0..4) {
            heatmap[dowIdx][wom]++
        }

        if (dowIdx in 0..4) weekdayFills++ else weekendFills++

        when {
            ev.liters < 20.0 -> tierSmall++
            ev.liters <= 40.0 -> tierMedium++
            else -> tierLarge++
        }

        if (ev.liters > 45.0) largeAnomalies++
        if (ev.calculatedMileageKmpl in 0.1..8.5) lowMileageCount++

        scatterPoints.add(
            FuelScatterPoint(
                eventId = ev.id,
                liters = ev.liters,
                amount = ev.totalCost,
                pricePerLiter = ev.pricePerLiter,
                odometerKm = ev.odometerKm,
                mileageKmpl = ev.calculatedMileageKmpl
            )
        )
    }

    val weekdayPct = if (totalFills > 0) (weekdayFills.toDouble() / totalFills) * 100.0 else 0.0
    val weekendPct = if (totalFills > 0) (weekendFills.toDouble() / totalFills) * 100.0 else 0.0

    // Forecast: Current month spend vs burn rate
    val now = Calendar.getInstance()
    val curDayOfYear = now.get(Calendar.DAY_OF_YEAR)
    val dailySpend = if (curDayOfYear > 0) totalCost / curDayOfYear else 0.0
    val forecastNextMonth = dailySpend * 30.5

    val daysUntilNextRefill = if (avgDaysBetween > 0.0) {
        val lastFillTime = yearEvents.lastOrNull()?.timestamp ?: System.currentTimeMillis()
        val daysSinceLast = (System.currentTimeMillis() - lastFillTime) / (1000.0 * 60 * 60 * 24)
        max(1, (avgDaysBetween - daysSinceLast).toInt())
    } else 7

    // MoM Comparisons
    val curMonthIdx = now.get(Calendar.MONTH)
    val curMonthEvents = yearEvents.filter {
        cal.timeInMillis = it.timestamp
        cal.get(Calendar.MONTH) == curMonthIdx
    }
    val priorMonthEvents = yearEvents.filter {
        cal.timeInMillis = it.timestamp
        cal.get(Calendar.MONTH) == (curMonthIdx - 1 + 12) % 12
    }
    val curSpend = curMonthEvents.sumOf { it.totalCost }
    val priorSpend = priorMonthEvents.sumOf { it.totalCost }
    val curLit = curMonthEvents.sumOf { it.liters }
    val priorLit = priorMonthEvents.sumOf { it.liters }

    // GPS Telemetry Integration
    val yearTrips = allTrips.filter {
        cal.timeInMillis = it.startTime
        cal.get(Calendar.YEAR) == selectedYear
    }
    val totalGpsDist = yearTrips.sumOf { it.distanceMeters } / 1000.0
    val actualRouteCost = if (totalGpsDist > 0.0) totalCost / totalGpsDist else costPerKm

    return FuelDashboardAnalytics(
        selectedYear = selectedYear,
        totalCost = totalCost,
        totalLiters = totalLiters,
        totalFillUps = totalFills,
        avgFleetMileageKmpl = avgMileage,
        costPerKm = costPerKm,
        avgPricePerLiter = avgPricePerL,
        avgFillUpLiters = avgFillL,
        avgFillUpCost = avgFillC,
        avgDaysBetweenFills = avgDaysBetween,
        avgKmBetweenFills = avgKmBetween,
        bestMileageKmpl = bestMileage,
        worstMileageKmpl = worstMileage,
        monthlyAvgSpend = monthlyAvgSpend,
        carbonFootprintKg = carbonFootprint,
        stationShares = stationShares,
        cheapestStation = cheapestStation,
        mostExpensiveStation = mostExpensiveStation,
        potentialSavingsIfCheapest = potentialSavings,
        monthlyStats = monthlyStats,
        q1Spend = q1Spend,
        q2Spend = q2Spend,
        q3Spend = q3Spend,
        q4Spend = q4Spend,
        weekdayFillsPercent = weekdayPct,
        weekendFillsPercent = weekendPct,
        fillSizeTiers = Triple(tierSmall, tierMedium, tierLarge),
        dowWomHeatmap = heatmap,
        scatterPoints = scatterPoints,
        nextMonthForecastSpend = forecastNextMonth,
        estimatedDaysUntilNextRefill = daysUntilNextRefill,
        largeFillAnomalies = largeAnomalies,
        lowMileageAnomalies = lowMileageCount,
        currentMonthSpend = curSpend,
        priorMonthSpend = priorSpend,
        currentMonthLiters = curLit,
        priorMonthLiters = priorLit,
        totalGpsDistanceKm = totalGpsDist,
        fuelOdometerDeltaKm = fuelOdoDelta,
        actualRouteCostPerKm = actualRouteCost
    )
}

// ==================== MAIN COMPOSABLE ====================
@Composable
fun FuelStatisticsDashboard(
    fuelEvents: List<FuelEventEntity>,
    trips: List<TripEntity> = emptyList(),
    selectedYear: Int,
    modifier: Modifier = Modifier
) {
    val analytics = remember(fuelEvents, trips, selectedYear) {
        computeFuelDashboardAnalytics(fuelEvents, trips, selectedYear)
    }

    var currentSection by remember { mutableStateOf(FuelStatsSection.OVERVIEW) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF070B12))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Navigation Pill Bar (3 Sleek Automotive Tabs with Big Fonts)
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
            FuelStatsSection.values().forEach { section ->
                val isSelected = section == currentSection
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isSelected) Brush.horizontalGradient(
                                listOf(Color(0xFFD97706), Color(0xFFB45309))
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
                FuelStatsSection.OVERVIEW -> {
                    item { FinancialKpiCardsSection(analytics) }
                    item { MonthlyComboSpendAndMileageSection(analytics) }
                    item { StationBrandShareSection(analytics) }
                }
                FuelStatsSection.CHARTS -> {
                    item { MonthlyComboSpendAndMileageSection(analytics) }
                    item { PriceAndEfficiencyTrendsSection(analytics) }
                    item { StationPriceComparisonSection(analytics) }
                    item { QuarterlyAndVolumeTiersSection(analytics) }
                    item { RefillHeatmapAndScatterSection(analytics) }
                }
                FuelStatsSection.ANALYTICS -> {
                    item { SavingsAndCheapestStationSection(analytics) }
                    item { ForecastingAndBurnRateSection(analytics) }
                    item { CarbonFootprintAndEcoSection(analytics) }
                    item { GpsVsOdometerTelemetrySection(analytics) }
                    item { MonthOverMonthFinancialComparisonSection(analytics) }
                    item { FuelAuditAnomaliesSection(analytics) }
                }
            }
        }
    }
}

// ==================== 1. FINANCIAL KPI CARDS SECTION ====================
@Composable
private fun FinancialKpiCardsSection(analytics: FuelDashboardAnalytics) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Row 1: High Level Totals
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FuelKpiCard(
                modifier = Modifier.weight(1f),
                title = "TOTAL FUEL SPEND",
                value = NumberFormatUtils.formatCurrency(analytics.totalCost, 0, useGrouping = true),
                unit = "₹",
                subtitle = "${analytics.totalFillUps} refuels recorded in ${analytics.selectedYear}",
                accentColor = Color(0xFFF59E0B),
                icon = Icons.Default.CurrencyRupee
            )
            FuelKpiCard(
                modifier = Modifier.weight(1f),
                title = "TOTAL FUEL FILLED",
                value = NumberFormatUtils.formatFuelLiters(analytics.totalLiters, 1),
                unit = "L",
                subtitle = "Monthly avg: ${NumberFormatUtils.formatFuelLiters(analytics.totalLiters / 12.0, 1)} L/mo",
                accentColor = Color(0xFF00F5FF),
                icon = Icons.Default.LocalGasStation
            )
            FuelKpiCard(
                modifier = Modifier.weight(1f),
                title = "FLEET AVG MILEAGE",
                value = NumberFormatUtils.formatMileage(analytics.avgFleetMileageKmpl, 1),
                unit = "km/L",
                subtitle = "Best: ${NumberFormatUtils.formatMileage(analytics.bestMileageKmpl, 1)} • Min: ${NumberFormatUtils.formatMileage(analytics.worstMileageKmpl, 1)}",
                accentColor = Color(0xFF10B981),
                icon = Icons.AutoMirrored.Filled.TrendingUp
            )
            FuelKpiCard(
                modifier = Modifier.weight(1f),
                title = "RUNNING COST / KM",
                value = "₹${NumberFormatUtils.formatPrecision(analytics.costPerKm, 2)}",
                unit = "/km",
                subtitle = "Fuel expenditure per kilometer",
                accentColor = Color(0xFFA855F7),
                icon = Icons.Default.Speed
            )
        }

        // Row 2: Secondary Metrics
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FuelKpiCard(
                modifier = Modifier.weight(1f),
                title = "AVG FUEL PRICE",
                value = "₹${NumberFormatUtils.formatPrecision(analytics.avgPricePerLiter, 2)}",
                unit = "/L",
                subtitle = "Weighted price per litre",
                accentColor = Color(0xFF38BDF8),
                icon = Icons.Default.MonetizationOn
            )
            FuelKpiCard(
                modifier = Modifier.weight(1f),
                title = "AVG REFILL SIZE",
                value = NumberFormatUtils.formatFuelLiters(analytics.avgFillUpLiters, 1),
                unit = "L",
                subtitle = "Avg ${NumberFormatUtils.formatCurrencyWithSymbol(analytics.avgFillUpCost, 0)} per visit",
                accentColor = Color(0xFFFFE600),
                icon = Icons.Default.WaterDrop
            )
            FuelKpiCard(
                modifier = Modifier.weight(1f),
                title = "REFILL CADENCE",
                value = "${analytics.avgDaysBetweenFills.toInt()}",
                unit = "days",
                subtitle = "Avg ${NumberFormatUtils.formatDistance(analytics.avgKmBetweenFills, 0)} km between fills",
                accentColor = Color(0xFFEC4899),
                icon = Icons.Default.CalendarToday
            )
            FuelKpiCard(
                modifier = Modifier.weight(1f),
                title = "MONTHLY SPEND AVG",
                value = NumberFormatUtils.formatCurrency(analytics.monthlyAvgSpend, 0, useGrouping = true),
                unit = "₹/mo",
                subtitle = "Across active driving months",
                accentColor = Color(0xFF06B6D4),
                icon = Icons.Default.AccountBalanceWallet
            )
        }
    }
}

// ==================== 2. MONTHLY COMBO SPEND & MILEAGE ====================
@Composable
private fun MonthlyComboSpendAndMileageSection(analytics: FuelDashboardAnalytics) {
    FuelCardContainer(
        title = "MONTHLY EXPENDITURE (BARS) & MILEAGE (LINE COMBO)",
        subtitle = "12 months financial spend paired with fuel efficiency"
    ) {
        val peakSpendMonth = analytics.monthlyStats.maxByOrNull { it.totalCost }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF080E1A))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(12.dp, 12.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFFF59E0B)))
                    Text("Spend (₹)", style = MaterialTheme.typography.labelMedium.copy(color = Color(0xFFF59E0B), fontWeight = FontWeight.Black, fontSize = 13.sp))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(14.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF10B981)))
                    Text("Mileage (km/L)", style = MaterialTheme.typography.labelMedium.copy(color = Color(0xFF10B981), fontWeight = FontWeight.Black, fontSize = 13.sp))
                }
            }
            Text(
                text = "Peak: ${peakSpendMonth?.monthShortName?.uppercase() ?: "NONE"} (${NumberFormatUtils.formatCurrencyWithSymbol(peakSpendMonth?.totalCost ?: 0.0, 0, useGrouping = true)})",
                style = MaterialTheme.typography.labelMedium.copy(color = Color(0xFFFFE600), fontWeight = FontWeight.Black, fontSize = 13.sp)
            )
        }

        Spacer(Modifier.height(8.dp))

        // Chart.js Styled Dual-Axis Combo Canvas
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
        ) {
            val w = size.width
            val h = size.height
            val leftPad = 55f
            val rightPad = 48f
            val bottomPad = 38f
            val topPad = 25f
            val chartW = w - leftPad - rightPad
            val chartH = h - topPad - bottomPad

            val maxCost = max(5000.0, analytics.monthlyStats.maxOfOrNull { it.totalCost } ?: 5000.0)
            val maxMileage = max(25.0, analytics.monthlyStats.maxOfOrNull { it.mileageKmpl } ?: 25.0)
            val spacing = chartW / 12f
            val barW = spacing * 0.48f

            val leftGridPaint = Paint().apply {
                isAntiAlias = true
                textAlign = Paint.Align.RIGHT
                textSize = 26f
                color = android.graphics.Color.parseColor("#F59E0B")
                typeface = Typeface.MONOSPACE
            }
            val rightGridPaint = Paint().apply {
                isAntiAlias = true
                textAlign = Paint.Align.LEFT
                textSize = 26f
                color = android.graphics.Color.parseColor("#10B981")
                typeface = Typeface.MONOSPACE
            }
            val monthLabelPaint = Paint().apply {
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
                textSize = 30f
                color = android.graphics.Color.parseColor("#CBD5E1")
                typeface = Typeface.DEFAULT_BOLD
            }
            val barValPaint = Paint().apply {
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
                textSize = 26f
                color = android.graphics.Color.WHITE
                typeface = Typeface.MONOSPACE
            }

            // Chart.js Horizontal Gridlines with Dual-Axis Ticks
            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
            listOf(0f, 0.33f, 0.66f, 1f).forEach { norm ->
                val gy = (h - bottomPad) - (norm * chartH)
                val costVal = (norm * maxCost).toInt()
                val mileageVal = (norm * maxMileage).toInt()

                drawLine(
                    color = Color(0xFF1E2E44).copy(alpha = 0.55f),
                    start = Offset(leftPad, gy),
                    end = Offset(w - rightPad, gy),
                    strokeWidth = 1.2f,
                    pathEffect = dashEffect
                )
                // Left Axis: Spend
                drawContext.canvas.nativeCanvas.drawText(
                    if (costVal >= 1000) "₹${costVal / 1000}k" else "₹$costVal",
                    leftPad - 8f,
                    gy + 9f,
                    leftGridPaint
                )
                // Right Axis: Mileage
                drawContext.canvas.nativeCanvas.drawText(
                    "$mileageVal",
                    w - rightPad + 8f,
                    gy + 9f,
                    rightGridPaint
                )
            }

            // 1. Draw Bars (Monthly Spend) with Chart.js Column Tracks
            analytics.monthlyStats.forEachIndexed { i, stat ->
                val x = leftPad + spacing * i + (spacing / 2f)
                val barH = if (maxCost > 0) ((stat.totalCost / maxCost).toFloat() * chartH).coerceAtLeast(6f) else 6f
                val topY = h - bottomPad - barH

                // Column track in background
                drawRoundRect(
                    color = Color(0xFF0F192B),
                    topLeft = Offset(x - barW / 2f, topPad),
                    size = Size(barW, chartH),
                    cornerRadius = CornerRadius(7f, 7f)
                )

                val barBrush = Brush.verticalGradient(
                    listOf(Color(0xFFF59E0B), Color(0xFFB45309))
                )

                drawRoundRect(
                    brush = barBrush,
                    topLeft = Offset(x - barW / 2f, topY),
                    size = Size(barW, barH),
                    cornerRadius = CornerRadius(7f, 7f)
                )

                if (stat.totalCost > 0) {
                    drawContext.canvas.nativeCanvas.drawText(
                        "${(stat.totalCost / 1000).toInt()}k",
                        x,
                        topY - 8f,
                        barValPaint
                    )
                }

                drawContext.canvas.nativeCanvas.drawText(
                    stat.monthShortName.uppercase(),
                    x,
                    h - 8f,
                    monthLabelPaint
                )
            }

            // 2. Draw Smooth Spline Line (Mileage Overlay)
            val mileagePoints = mutableListOf<Offset>()
            analytics.monthlyStats.forEachIndexed { i, stat ->
                val x = leftPad + spacing * i + (spacing / 2f)
                if (stat.mileageKmpl > 0) {
                    val norm = (stat.mileageKmpl / maxMileage).toFloat().coerceIn(0f, 1f)
                    val y = (h - bottomPad) - (norm * chartH)
                    mileagePoints.add(Offset(x, y))
                }
            }

            if (mileagePoints.isNotEmpty()) {
                val path = Path()
                path.moveTo(mileagePoints.first().x, mileagePoints.first().y)

                for (i in 0 until mileagePoints.size - 1) {
                    val p0 = if (i > 0) mileagePoints[i - 1] else mileagePoints[i]
                    val p1 = mileagePoints[i]
                    val p2 = mileagePoints[i + 1]
                    val p3 = if (i + 2 < mileagePoints.size) mileagePoints[i + 2] else p2
                    val cp1x = p1.x + (p2.x - p0.x) * 0.2f
                    val cp1y = p1.y + (p2.y - p0.y) * 0.2f
                    val cp2x = p2.x - (p3.x - p1.x) * 0.2f
                    val cp2y = p2.y - (p3.y - p1.y) * 0.2f
                    path.cubicTo(cp1x, cp1y, cp2x, cp2y, p2.x, p2.y)
                }

                drawPath(path, color = Color(0xFF10B981), style = Stroke(width = 4.5f, cap = StrokeCap.Round))

                mileagePoints.forEach { pt ->
                    // Chart.js signature hollow ring point
                    drawCircle(color = Color(0xFF10B981), radius = 7.5f, center = pt)
                    drawCircle(color = Color(0xFF070B12), radius = 4f, center = pt)
                }
            }
        }
    }
}

// ==================== 3. STATION BRAND SHARE ====================
@Composable
private fun StationBrandShareSection(analytics: FuelDashboardAnalytics) {
    FuelCardContainer(
        title = "FUEL STATION / BRAND EXPENDITURE SHARE",
        subtitle = "Volume and expenditure breakdown by fuel retail brand"
    ) {
        if (analytics.stationShares.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                Text("No fuel station brands identified yet", color = Color(0xFF64748B))
            }
        } else {
            val totalLit = max(1.0, analytics.totalLiters)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Donut Canvas (Chart.js Style Ring)
                Box(modifier = Modifier.size(155.dp), contentAlignment = Alignment.Center) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val strokeW = 24f
                        val radius = (min(size.width, size.height) - strokeW) / 2f
                        val centerOffset = Offset(size.width / 2f, size.height / 2f)

                        var startAngle = -90f
                        analytics.stationShares.forEach { st ->
                            val sweep = ((st.liters / totalLit) * 360.0).toFloat()
                            if (sweep > 0.5f) {
                                drawArc(
                                    color = st.color,
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

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${analytics.stationShares.size}",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black, fontSize = 22.sp),
                            color = Color.White
                        )
                        Text("Brands", style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold), color = Color(0xFF94A3B8))
                    }
                }

                // Station List (Big Readable Badges)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    analytics.stationShares.forEach { st ->
                        val pct = ((st.liters / totalLit) * 100).toInt()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF080E1A))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.size(10.dp).clip(CircleShape).background(st.color))
                                Text(st.brandName, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Black, color = Color.White, fontSize = 13.sp))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("${NumberFormatUtils.formatFuelLiters(st.liters, 1)} ($pct%)", style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFFCBD5E1), fontSize = 12.5.sp))
                                Text("₹${NumberFormatUtils.formatPrecision(st.avgPrice, 2)}/L", style = MaterialTheme.typography.labelMedium.copy(color = st.color, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, fontSize = 13.5.sp))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==================== 4. PRICE & EFFICIENCY TRENDS ====================
@Composable
private fun PriceAndEfficiencyTrendsSection(analytics: FuelDashboardAnalytics) {
    FuelCardContainer(
        title = "FUEL PRICE & VEHICLE EFFICIENCY TREND CURVES",
        subtitle = "Continuous price per litre (₹/L) vs calculated mileage (km/L)"
    ) {
        val validPoints = analytics.scatterPoints.filter { it.pricePerLiter > 0 }
        if (validPoints.size < 2) {
            Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                Text("Need at least 2 refuels to plot trend curves", color = Color(0xFF64748B))
            }
        } else {
            // Chart.js Styled Smooth Cubic Spline Canvas
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
            ) {
                val w = size.width
                val h = size.height
                val padBottom = 38f
                val padTop = 25f
                val padLeft = 55f
                val padRight = 30f

                val plotW = w - padLeft - padRight
                val plotH = h - padBottom - padTop

                val minP = validPoints.minOf { it.pricePerLiter } * 0.96
                val maxP = validPoints.maxOf { it.pricePerLiter } * 1.04
                val rangeP = max(1.0, maxP - minP)

                val gridPaint = Paint().apply {
                    isAntiAlias = true
                    textAlign = Paint.Align.RIGHT
                    textSize = 28f
                    color = android.graphics.Color.parseColor("#FB923C")
                    typeface = Typeface.MONOSPACE
                }

                // Chart.js Horizontal dashed gridlines & Y-Axis values
                val dashEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                listOf(0f, 0.5f, 1f).forEach { norm ->
                    val gy = (h - padBottom) - (norm * plotH)
                    val priceVal = minP + norm * rangeP
                    drawLine(
                        color = Color(0xFF1E2E44).copy(alpha = 0.55f),
                        start = Offset(padLeft, gy),
                        end = Offset(w - padRight, gy),
                        strokeWidth = 1.2f,
                        pathEffect = dashEffect
                    )
                    drawContext.canvas.nativeCanvas.drawText(
                        "₹${priceVal.toInt()}",
                        padLeft - 8f,
                        gy + 9f,
                        gridPaint
                    )
                }

                val points = validPoints.mapIndexed { i, pt ->
                    val x = padLeft + (i.toFloat() / (validPoints.size - 1)) * plotW
                    val y = (h - padBottom) - (((pt.pricePerLiter - minP) / rangeP).toFloat() * plotH)
                    Offset(x, y)
                }

                if (points.isNotEmpty()) {
                    val pricePath = Path()
                    val fillPath = Path()
                    pricePath.moveTo(points.first().x, points.first().y)
                    fillPath.moveTo(points.first().x, h - padBottom)
                    fillPath.lineTo(points.first().x, points.first().y)

                    for (i in 0 until points.size - 1) {
                        val p0 = if (i > 0) points[i - 1] else points[i]
                        val p1 = points[i]
                        val p2 = points[i + 1]
                        val p3 = if (i + 2 < points.size) points[i + 2] else p2
                        val cp1x = p1.x + (p2.x - p0.x) * 0.2f
                        val cp1y = p1.y + (p2.y - p0.y) * 0.2f
                        val cp2x = p2.x - (p3.x - p1.x) * 0.2f
                        val cp2y = p2.y - (p3.y - p1.y) * 0.2f
                        pricePath.cubicTo(cp1x, cp1y, cp2x, cp2y, p2.x, p2.y)
                        fillPath.cubicTo(cp1x, cp1y, cp2x, cp2y, p2.x, p2.y)
                    }

                    fillPath.lineTo(points.last().x, h - padBottom)
                    fillPath.close()

                    // Gradient underfill
                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            listOf(Color(0xFFFB923C).copy(alpha = 0.30f), Color.Transparent),
                            startY = padTop,
                            endY = h - padBottom
                        )
                    )

                    // Stroke
                    drawPath(pricePath, color = Color(0xFFFB923C), style = Stroke(width = 4.5f, cap = StrokeCap.Round))

                    // Hollow ring markers
                    points.forEach { pt ->
                        drawCircle(color = Color(0xFFFB923C), radius = 7.5f, center = pt)
                        drawCircle(color = Color(0xFF070B12), radius = 4f, center = pt)
                    }
                }
            }
        }
    }
}

// ==================== 5. STATION PRICE COMPARISON ====================
@Composable
private fun StationPriceComparisonSection(analytics: FuelDashboardAnalytics) {
    FuelCardContainer(
        title = "STATION BRAND PRICE COMPARISON (₹ / LITRE)",
        subtitle = "Average tariff charged per fuel brand"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val maxPrice = max(1.0, analytics.stationShares.maxOfOrNull { it.avgPrice } ?: 1.0)
            analytics.stationShares.forEach { st ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF080E1A))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(st.brandName, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Black, color = Color.White, fontSize = 13.5.sp), modifier = Modifier.width(130.dp))

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(10.dp)
                            .padding(horizontal = 12.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color(0xFF172338))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(fraction = (st.avgPrice / maxPrice).toFloat().coerceIn(0.1f, 1f))
                                .clip(RoundedCornerShape(5.dp))
                                .background(st.color)
                        )
                    }

                    Text("₹${NumberFormatUtils.formatPrecision(st.avgPrice, 2)}/L", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, color = st.color, fontSize = 14.sp))
                }
            }
        }
    }
}

// ==================== 6. REFILL HEATMAP & SCATTER ====================
@Composable
private fun RefillHeatmapAndScatterSection(analytics: FuelDashboardAnalytics) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Refill Timing Heatmap (DOW x WOM)
        FuelCardContainer(
            modifier = Modifier.weight(1f),
            title = "REFILL MATRIX (DAY × WEEK)",
            subtitle = "When you tend to visit the petrol pump"
        ) {
            val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
            var maxVal = 1
            for (r in 0..6) {
                for (c in 0..4) {
                    if (analytics.dowWomHeatmap[r][c] > maxVal) maxVal = analytics.dowWomHeatmap[r][c]
                }
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
            ) {
                val w = size.width
                val h = size.height
                val leftM = 55f
                val topM = 15f
                val cellW = (w - leftM) / 5f
                val cellH = (h - topM) / 7f

                val p = Paint().apply {
                    isAntiAlias = true
                    textSize = 20f
                    color = android.graphics.Color.parseColor("#94A3B8")
                    textAlign = Paint.Align.RIGHT
                }

                for (r in 0..6) {
                    drawContext.canvas.nativeCanvas.drawText(days[r], leftM - 10f, topM + r * cellH + cellH * 0.7f, p)
                    for (c in 0..4) {
                        val count = analytics.dowWomHeatmap[r][c]
                        val intensity = if (count > 0) (count.toFloat() / maxVal).coerceIn(0.2f, 1f) else 0f
                        val color = if (count == 0) Color(0xFF0F1A2E) else Color(0xFFF59E0B).copy(alpha = intensity)
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(leftM + c * cellW + 1f, topM + r * cellH + 1f),
                            size = Size(cellW - 2f, cellH - 2f),
                            cornerRadius = CornerRadius(3f, 3f)
                        )
                    }
                }
            }
        }

        // Scatter: Liters vs Amount
        FuelCardContainer(
            modifier = Modifier.weight(1f),
            title = "LITERS VS AMOUNT (₹) SCATTER",
            subtitle = "Price consistency and outlier refills"
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
            ) {
                val w = size.width
                val h = size.height
                val padL = 45f
                val padB = 25f
                val plotW = w - padL - 15f
                val plotH = h - padB - 10f

                val maxLit = max(10.0, analytics.scatterPoints.maxOfOrNull { it.liters } ?: 10.0)
                val maxAmt = max(1000.0, analytics.scatterPoints.maxOfOrNull { it.amount } ?: 1000.0)

                analytics.scatterPoints.forEach { pt ->
                    val x = padL + ((pt.liters / maxLit).toFloat().coerceIn(0f, 1f) * plotW)
                    val y = (h - padB) - ((pt.amount / maxAmt).toFloat().coerceIn(0f, 1f) * plotH)

                    drawCircle(Color(0xFF00F5FF).copy(alpha = 0.3f), radius = 7f, center = Offset(x, y))
                    drawCircle(Color(0xFF00F5FF), radius = 3.5f, center = Offset(x, y))
                }

                val p = Paint().apply {
                    isAntiAlias = true
                    textSize = 18f
                    color = android.graphics.Color.parseColor("#64748B")
                }
                drawContext.canvas.nativeCanvas.drawText("0L", padL, h - 5f, p)
                drawContext.canvas.nativeCanvas.drawText("${maxLit.toInt()}L", w - 40f, h - 5f, p)
            }
        }
    }
}

// ==================== 7. QUARTERLY & VOLUME TIERS ====================
@Composable
private fun QuarterlyAndVolumeTiersSection(analytics: FuelDashboardAnalytics) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Quarterly spend
        FuelCardContainer(
            modifier = Modifier.weight(1f),
            title = "QUARTERLY EXPENDITURE BREAKDOWN",
            subtitle = "Q1 to Q4 cumulative spend"
        ) {
            val quarters = listOf(
                Pair("Q1 (Jan-Mar)", analytics.q1Spend),
                Pair("Q2 (Apr-Jun)", analytics.q2Spend),
                Pair("Q3 (Jul-Sep)", analytics.q3Spend),
                Pair("Q4 (Oct-Dec)", analytics.q4Spend)
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                quarters.forEach { q ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF080E1A))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(q.first, style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFF94A3B8), fontWeight = FontWeight.Bold, fontSize = 11.5.sp))
                        Text(NumberFormatUtils.formatCurrencyWithSymbol(q.second, 0, useGrouping = true), style = MaterialTheme.typography.labelSmall.copy(color = Color.White, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, fontSize = 12.sp))
                    }
                }
            }
        }

        // Fill Volume Tiers
        FuelCardContainer(
            modifier = Modifier.weight(1f),
            title = "REFILL VOLUME TIERS",
            subtitle = "Partial top-ups vs Full tank fills"
        ) {
            val tiers = listOf(
                Triple("Quick Top-Up (<20L)", analytics.fillSizeTiers.first, Color(0xFF38BDF8)),
                Triple("Standard Refill (20-40L)", analytics.fillSizeTiers.second, Color(0xFF10B981)),
                Triple("Full Tank (>40L)", analytics.fillSizeTiers.third, Color(0xFFF59E0B))
            )
            val totalT = max(1, analytics.totalFillUps)

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                tiers.forEach { t ->
                    val pct = ((t.second.toDouble() / totalT) * 100).toInt()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF080E1A))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(t.third))
                            Text(t.first, style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFF94A3B8), fontSize = 11.sp))
                        }
                        Text("${t.second} fills ($pct%)", style = MaterialTheme.typography.labelSmall.copy(color = t.third, fontWeight = FontWeight.Bold, fontSize = 11.sp))
                    }
                }
            }
        }
    }
}

// ==================== 8. SAVINGS & CHEAPEST STATION ====================
@Composable
private fun SavingsAndCheapestStationSection(analytics: FuelDashboardAnalytics) {
    FuelCardContainer(
        title = "PRICE VARIANCE & POTENTIAL SAVINGS",
        subtitle = "Optimal fuel brand rate arbitrage analysis"
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FuelMetricPillCard(
                modifier = Modifier.weight(1f),
                title = "CHEAPEST BRAND",
                value = analytics.cheapestStation?.brandName ?: "N/A",
                status = "₹${NumberFormatUtils.formatPrecision(analytics.cheapestStation?.avgPrice ?: 0.0, 2)}/L average",
                color = Color(0xFF10B981),
                icon = Icons.Default.Savings
            )
            FuelMetricPillCard(
                modifier = Modifier.weight(1f),
                title = "PRICIEST BRAND",
                value = analytics.mostExpensiveStation?.brandName ?: "N/A",
                status = "₹${NumberFormatUtils.formatPrecision(analytics.mostExpensiveStation?.avgPrice ?: 0.0, 2)}/L average",
                color = Color(0xFFEF4444),
                icon = Icons.AutoMirrored.Filled.TrendingUp
            )
            FuelMetricPillCard(
                modifier = Modifier.weight(1f),
                title = "POTENTIAL SAVINGS",
                value = NumberFormatUtils.formatCurrencyWithSymbol(analytics.potentialSavingsIfCheapest, 0, useGrouping = true),
                status = "If always filled at cheapest pump",
                color = Color(0xFFFFE600),
                icon = Icons.Default.MonetizationOn
            )
        }
    }
}

// ==================== 9. FORECASTING & BURN RATE ====================
@Composable
private fun ForecastingAndBurnRateSection(analytics: FuelDashboardAnalytics) {
    FuelCardContainer(
        title = "EXPENDITURE FORECASTING & REFILL RUNWAY",
        subtitle = "Projected fuel run rate and budget demand"
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FuelMetricPillCard(
                modifier = Modifier.weight(1f),
                title = "NEXT MONTH FORECAST",
                value = NumberFormatUtils.formatCurrencyWithSymbol(analytics.nextMonthForecastSpend, 0, useGrouping = true),
                status = "Projected 30-day burn rate",
                color = Color(0xFF38BDF8),
                icon = Icons.Default.AutoGraph
            )
            FuelMetricPillCard(
                modifier = Modifier.weight(1f),
                title = "NEXT REFILL ETA",
                value = "~${analytics.estimatedDaysUntilNextRefill} days",
                status = "Based on refill cadence of ${analytics.avgDaysBetweenFills.toInt()} days",
                color = Color(0xFFF59E0B),
                icon = Icons.Default.LocalGasStation
            )
        }
    }
}

// ==================== 10. CARBON FOOTPRINT & ECO ====================
@Composable
private fun CarbonFootprintAndEcoSection(analytics: FuelDashboardAnalytics) {
    FuelCardContainer(
        title = "CARBON FOOTPRINT & ECOLOGICAL IMPACT",
        subtitle = "Calculated greenhouse emissions from consumed volume"
    ) {
        val treesRequired = (analytics.carbonFootprintKg / 21.77).toInt() // 1 mature tree absorbs ~21.8 kg CO2/year
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
                Text(
                    text = "${NumberFormatUtils.formatPrecision(analytics.carbonFootprintKg, 1)} kg CO₂",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black, fontSize = 26.sp),
                    color = Color(0xFF10B981)
                )
                Text(
                    text = "Estimated vehicle exhaust footprint (${NumberFormatUtils.formatFuelLiters(analytics.totalLiters, 1)} total liters consumed)",
                    style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFF94A3B8), fontSize = 11.5.sp)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(imageVector = Icons.Default.Park, contentDescription = "Trees", tint = Color(0xFF10B981), modifier = Modifier.size(24.dp))
                Column {
                    Text("$treesRequired Trees", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black, color = Color.White))
                    Text("to offset annual carbon", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, color = Color(0xFF64748B)))
                }
            }
        }
    }
}

// ==================== 11. AUDIT & ANOMALIES ====================
@Composable
private fun FuelAuditAnomaliesSection(analytics: FuelDashboardAnalytics) {
    FuelCardContainer(
        title = "FUEL AUDIT & ABNORMALITY ALERTS",
        subtitle = "Capacity integrity and efficiency dip detection"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FuelAuditItem(
                title = "OVERFILL / CAPACITY SPIKES (>45L)",
                description = if (analytics.largeFillAnomalies == 0) "Zero capacity anomalies detected" else "${analytics.largeFillAnomalies} fill-ups exceeded typical 45L tank limit",
                isWarning = analytics.largeFillAnomalies > 0,
                color = if (analytics.largeFillAnomalies > 0) Color(0xFFEF4444) else Color(0xFF10B981),
                icon = Icons.Default.LocalGasStation
            )
            FuelAuditItem(
                title = "LOW MILEAGE WARNING (<8.5 km/L)",
                description = if (analytics.lowMileageAnomalies == 0) "Optimal efficiency across all fill events" else "${analytics.lowMileageAnomalies} fills logged suboptimal mileage (check tyre pressure/heavy traffic)",
                isWarning = analytics.lowMileageAnomalies > 0,
                color = if (analytics.lowMileageAnomalies > 0) Color(0xFFF59E0B) else Color(0xFF10B981),
                icon = Icons.Default.Speed
            )
        }
    }
}

// ==================== 12. GPS CROSS TELEMETRY ====================
@Composable
private fun GpsVsOdometerTelemetrySection(analytics: FuelDashboardAnalytics) {
    FuelCardContainer(
        title = "GPS TRACKING VS FUEL ODOMETER DELTA",
        subtitle = "Cross-validating satellite distance against vehicle odometer logs"
    ) {
        val diffKm = analytics.fuelOdometerDeltaKm - analytics.totalGpsDistanceKm
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FuelMetricPillCard(
                modifier = Modifier.weight(1f),
                title = "GPS SATELLITE DIST",
                value = NumberFormatUtils.formatDistanceWithUnit(analytics.totalGpsDistanceKm, 1),
                status = "Accumulated across all qualified trips",
                color = Color(0xFF00F5FF),
                icon = Icons.Default.GpsFixed
            )
            FuelMetricPillCard(
                modifier = Modifier.weight(1f),
                title = "FUEL ODOMETER DELTA",
                value = NumberFormatUtils.formatDistanceWithUnit(analytics.fuelOdometerDeltaKm, 1),
                status = "Delta logged at fuel pump stops",
                color = Color(0xFFFFE600),
                icon = Icons.Default.Speed
            )
            FuelMetricPillCard(
                modifier = Modifier.weight(1f),
                title = "VARIANCE / CALIBRATION",
                value = "${if (diffKm >= 0) "+" else ""}${NumberFormatUtils.formatDistanceWithUnit(diffKm, 1)}",
                status = if (kotlin.math.abs(diffKm) < 50.0) "ACCURATE SYNC" else "Calibration delta detected",
                color = if (kotlin.math.abs(diffKm) < 50.0) Color(0xFF10B981) else Color(0xFFF59E0B),
                icon = Icons.AutoMirrored.Filled.CompareArrows
            )
        }
    }
}

@Composable
private fun MonthOverMonthFinancialComparisonSection(analytics: FuelDashboardAnalytics) {
    FuelCardContainer(
        title = "MONTH-OVER-MONTH FINANCIAL COMPARISON",
        subtitle = "Current active month vs prior month spend"
    ) {
        val spendDiff = analytics.currentMonthSpend - analytics.priorMonthSpend
        val spendDiffPct = if (analytics.priorMonthSpend > 0) (spendDiff / analytics.priorMonthSpend) * 100 else 0.0

        val litDiff = analytics.currentMonthLiters - analytics.priorMonthLiters
        val litDiffPct = if (analytics.priorMonthLiters > 0) (litDiff / analytics.priorMonthLiters) * 100 else 0.0

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF080E1A))
                    .padding(12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("MONTHLY SPEND", style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFF87929A), fontWeight = FontWeight.Bold))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(NumberFormatUtils.formatCurrencyWithSymbol(analytics.currentMonthSpend, 0), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black, color = Color.White))
                        Text("Prev: ${NumberFormatUtils.formatCurrencyWithSymbol(analytics.priorMonthSpend, 0)}", style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFF64748B)))
                        Text("${if (spendDiff >= 0) "+" else ""}${NumberFormatUtils.formatPrecision(spendDiffPct, 1)}%", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black, color = if (spendDiff <= 0) Color(0xFF10B981) else Color(0xFFEF4444)))
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF080E1A))
                    .padding(12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("MONTHLY LITRES", style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFF87929A), fontWeight = FontWeight.Bold))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(NumberFormatUtils.formatFuelLitersWithUnit(analytics.currentMonthLiters, 1), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black, color = Color.White))
                        Text("Prev: ${NumberFormatUtils.formatFuelLitersWithUnit(analytics.priorMonthLiters, 1)}", style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFF64748B)))
                        Text("${if (litDiff >= 0) "+" else ""}${NumberFormatUtils.formatPrecision(litDiffPct, 1)}%", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black, color = if (litDiff <= 0) Color(0xFF10B981) else Color(0xFFEF4444)))
                    }
                }
            }
        }
    }
}

// ==================== REUSABLE ATOMIC CARDS & CONTAINERS ====================
@Composable
private fun FuelCardContainer(
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
private fun FuelKpiCard(
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
                Icon(imageVector = icon, contentDescription = title, tint = accentColor, modifier = Modifier.size(18.dp))
            }

            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
private fun FuelMetricPillCard(
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
private fun FuelAuditItem(
    title: String,
    description: String,
    isWarning: Boolean,
    color: Color,
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
                .background(color.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = title, tint = color, modifier = Modifier.size(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Black, fontSize = 12.sp, color = Color.White))
            Text(text = description, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, color = Color(0xFF94A3B8)))
        }
    }
}
