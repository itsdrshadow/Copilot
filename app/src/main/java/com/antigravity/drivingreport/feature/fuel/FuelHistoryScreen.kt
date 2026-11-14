package com.antigravity.drivingreport.feature.fuel

import android.graphics.Paint
import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.antigravity.drivingreport.core.util.NumberFormatUtils
import com.antigravity.drivingreport.core.database.FuelEventEntity
import com.antigravity.drivingreport.core.database.TripEntity
import com.antigravity.drivingreport.core.designsystem.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max

// ==================== STATISTICAL DATA MODELS ====================

private data class MonthlyFuelStats(
    val monthIndex: Int,          // 0 = Jan, 11 = Dec
    val monthShortName: String,   // "Jan"
    val monthFullName: String,    // "January"
    val year: Int,
    val totalCost: Double,
    val totalLiters: Double,
    val refillCount: Int,
    val distanceKm: Double,
    val mileageKmpl: Double,
    val avgPricePerLiter: Double,
    val events: List<FuelEventEntity>
)

private data class YearlyFuelAnalytics(
    val year: Int,
    val totalCost: Double,
    val totalLiters: Double,
    val totalRefills: Int,
    val totalDistanceKm: Double,
    val avgMileageKmpl: Double,
    val avgPricePerLiter: Double,
    val highestExpenseMonth: Pair<String, Double>?,
    val lowestExpenseMonth: Pair<String, Double>?,
    val bestMileageMonth: Pair<String, Double>?,
    val lowestMileageMonth: Pair<String, Double>?,
    val mostExpensiveRefill: FuelEventEntity?,
    val yoyCostDiffPercent: Double?,
    val prevYearCost: Double?,
    val monthlyStats: List<MonthlyFuelStats>
)

private enum class FuelChartType(val label: String, val unit: String, val color: Color) {
    EXPENSES("Expenses", "₹", Color(0xFFF59E0B)),
    CONSUMPTION("Fuel Filled", "L", Color(0xFF38BDF8)),
    MILEAGE("Mileage", "km/L", Color(0xFF10B981)),
    DISTANCE("Distance", "km", Color(0xFFA855F7)),
    PRICE_TREND("Price Trend", "₹/L", Color(0xFFFB923C)),
    FREQUENCY("Refills", "times", Color(0xFF38BDF8))
}

// ==================== MAIN DIALOG COMPOSABLE ====================

@Composable
fun FuelHistoryScreen(
    fuelEvents: List<FuelEventEntity>,
    trips: List<TripEntity> = emptyList(),
    onBackToCockpit: () -> Unit,
    modifier: Modifier = Modifier,
    onEditEvent: (FuelEventEntity) -> Unit = {},
    onDeleteEvent: (Long) -> Unit = {}
) {
    val currentCalYear = remember { Calendar.getInstance().get(Calendar.YEAR) }

    // Identify all years in database containing fuel records
    val availableYears = remember(fuelEvents) {
        val cal = Calendar.getInstance()
        val yearsInDb = fuelEvents.map {
            cal.timeInMillis = it.timestamp
            cal.get(Calendar.YEAR)
        }.distinct()
        if (yearsInDb.isEmpty()) listOf(currentCalYear)
        else (yearsInDb + currentCalYear).distinct().sortedDescending()
    }

    var selectedYear by remember(availableYears) {
        mutableStateOf(if (availableYears.contains(currentCalYear)) currentCalYear else availableYears.first())
    }

    var isStatisticsMode by remember { mutableStateOf(false) }

    // Track expanded monthly accordions (defaults to all expanded)
    val expandedMonths = remember { mutableStateMapOf<String, Boolean>() }

    // Dialog edit/delete triggers
    var eventToEdit by remember { mutableStateOf<FuelEventEntity?>(null) }
    var eventToDelete by remember { mutableStateOf<FuelEventEntity?>(null) }

    BackHandler {
        if (eventToEdit != null) {
            eventToEdit = null
        } else if (eventToDelete != null) {
            eventToDelete = null
        } else if (isStatisticsMode) {
            isStatisticsMode = false
        } else {
            onBackToCockpit()
        }
    }

    // Compute comprehensive analytics for selected year
    val yearlyAnalytics = remember(fuelEvents, selectedYear) {
        computeYearlyAnalytics(fuelEvents, selectedYear)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF070B12))
    ) {
            val isCompact = maxWidth < 750.dp

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(if (isCompact) 12.dp else 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ==================== 1. COCKPIT HEADER ====================
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Back button + Title and Icon
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        IconButton(
                            onClick = onBackToCockpit,
                            modifier = Modifier
                                .size(50.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF141C2B))
                                .border(1.2.dp, Color(0xFF1E2838), RoundedCornerShape(12.dp))
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF0F263E))
                                .border(1.2.dp, Color(0xFF38BDF8).copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocalGasStation,
                                contentDescription = null,
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Fuel Records",
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.Black,
                                        fontSize = if (isCompact) 20.sp else 24.sp,
                                        letterSpacing = (-0.5).sp
                                    ),
                                    color = Color.White
                                )

                                // Current Year Badge
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFF142033))
                                        .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = "$selectedYear",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Black,
                                            fontSize = 12.5.sp,
                                            fontFamily = FontFamily.Monospace
                                        ),
                                        color = Color(0xFF38BDF8)
                                    )
                                }
                            }

                            Text(
                                text = "HISTORICAL LOGS • TANK-TO-TANK TELEMETRY • ACCURATE CONSUMPTION",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    letterSpacing = 0.5.sp
                                ),
                                color = Color(0xFF87929A)
                            )
                        }
                    }

                    // Top Action Controls: Year Selector, Statistics Chip, Close Button
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Year Switcher Chips
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            items(availableYears) { year ->
                                val isSelected = year == selectedYear
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (isSelected) Color(0xFF1E3A5F) else Color(0xFF0F1522))
                                        .border(
                                            width = 1.2.dp,
                                            color = if (isSelected) Color(0xFF38BDF8) else Color(0xFF1E2838),
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                        .clickable { selectedYear = year }
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "$year",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                                            fontSize = 15.sp,
                                            fontFamily = FontFamily.Monospace
                                        ),
                                        color = if (isSelected) Color.White else Color(0xFF87929A)
                                    )
                                }
                            }
                        }

                        // Statistics Mode Chip
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isStatisticsMode) Color(0xFF2E1A47) else Color(0xFF111827))
                                .border(
                                    width = 1.2.dp,
                                    color = if (isStatisticsMode) Color(0xFFA855F7) else Color(0xFF1E2838),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable { isStatisticsMode = !isStatisticsMode }
                                .padding(horizontal = 18.dp, vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = if (isStatisticsMode) Icons.AutoMirrored.Filled.ReceiptLong else Icons.Default.BarChart,
                                    contentDescription = null,
                                    tint = if (isStatisticsMode) Color(0xFFC084FC) else Color(0xFF38BDF8),
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = if (isStatisticsMode) "View Records" else "Statistics",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    ),
                                    color = if (isStatisticsMode) Color(0xFFC084FC) else Color.White
                                )
                            }
                        }


                    }
                }

                // ==================== 2. YEARLY SUMMARY TILES (Shown in Records Feed) ====================
                if (!isStatisticsMode) {
                    YearlySummaryBanner(
                        analytics = yearlyAnalytics,
                        isCompact = isCompact
                    )
                }

                // ==================== 3. CONTENT AREA ====================
                if (isStatisticsMode) {
                    // Modern High-Fidelity Automotive Fuel Statistics Dashboard
                    FuelStatisticsDashboard(
                        fuelEvents = fuelEvents,
                        trips = trips,
                        selectedYear = selectedYear,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                } else {
                    // Chronological Month-Grouped Feed
                    FuelRecordsMonthGroupedFeed(
                        analytics = yearlyAnalytics,
                        expandedMonths = expandedMonths,
                        onEditEvent = { eventToEdit = it },
                        onDeleteEvent = { eventToDelete = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
            }
        }

    // ==================== EDIT REFUEL DIALOG ====================
    eventToEdit?.let { targetEvent ->
        EditFuelEventDialog(
            event = targetEvent,
            onDismiss = { eventToEdit = null },
            onSave = { updated ->
                onEditEvent(updated)
                eventToEdit = null
            }
        )
    }

    // ==================== DELETE CONFIRMATION DIALOG ====================
    eventToDelete?.let { targetEvent ->
        AlertDialog(
            onDismissRequest = { eventToDelete = null },
            title = {
                Text(
                    text = "Delete Fuel Record?",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                    color = Color.White
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to delete this record of ${NumberFormatUtils.formatFuelLiters(targetEvent.liters, 2)} L (${NumberFormatUtils.formatCurrencyWithSymbol(targetEvent.totalCost, 2)})? This will adjust historical distance and fuel analytics.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Cockpit_TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteEvent(targetEvent.id)
                        eventToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Cockpit_RedAlert),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Delete", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { eventToDelete = null }) {
                    Text("Cancel", color = Color.White)
                }
            },
            containerColor = Color(0xFF131D30),
            shape = RoundedCornerShape(20.dp)
        )
    }
}

// ==================== YEARLY SUMMARY BANNER ====================

@Composable
private fun YearlySummaryBanner(
    analytics: YearlyFuelAnalytics,
    @Suppress("UNUSED_PARAMETER") isCompact: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF0B1019))
            .border(1.2.dp, Color(0xFF1E2838), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Total Refills
        FuelStatBadgeCard(
            modifier = Modifier.weight(1f),
            label = "TOTAL REFILLS",
            value = "${analytics.totalRefills}",
            unit = "Logs",
            color = Color.White
        )

        // Total Fuel Filled
        FuelStatBadgeCard(
            modifier = Modifier.weight(1.15f),
            label = "TOTAL FUEL",
            value = if (analytics.totalLiters > 0) NumberFormatUtils.formatFuelLiters(analytics.totalLiters, 2) else "0",
            unit = "L",
            color = Color(0xFF38BDF8)
        )

        // Total Cost
        FuelStatBadgeCard(
            modifier = Modifier.weight(1.25f),
            label = "TOTAL COST",
            value = if (analytics.totalCost > 0) NumberFormatUtils.formatCurrency(analytics.totalCost, 2, useGrouping = true) else "0",
            unit = "₹",
            color = Color(0xFFF59E0B)
        )

        // Average Mileage (True Tank-to-Tank / Delta)
        FuelStatBadgeCard(
            modifier = Modifier.weight(1.3f),
            label = "AVG MILEAGE",
            value = if (analytics.avgMileageKmpl > 0) NumberFormatUtils.formatMileage(analytics.avgMileageKmpl, 2) else "--",
            unit = "km/L",
            color = Color(0xFF10B981)
        )

        // Total Distance
        FuelStatBadgeCard(
            modifier = Modifier.weight(1.25f),
            label = "TOTAL DISTANCE",
            value = if (analytics.totalDistanceKm > 0) NumberFormatUtils.formatDistance(analytics.totalDistanceKm, 2, useGrouping = true) else "--",
            unit = "km",
            color = Color(0xFFA855F7)
        )
    }
}

@Composable
private fun FuelStatBadgeCard(
    label: String,
    value: String,
    unit: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 62.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0F1522))
            .border(1.dp, Color(0xFF1A2332), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Black,
                        fontSize = 19.sp,
                        lineHeight = 20.sp,
                        fontFamily = FontFamily.Monospace,
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    ),
                    color = color
                )
                if (unit.isNotBlank()) {
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            lineHeight = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                        ),
                        color = Color(0xFF87929A),
                        modifier = Modifier.padding(bottom = 0.5.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.5.sp,
                    lineHeight = 12.sp,
                    letterSpacing = 0.4.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                ),
                color = Color(0xFF87929A),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ==================== MONTH-GROUPED CHRONOLOGICAL FEED ====================

@Composable
private fun FuelRecordsMonthGroupedFeed(
    analytics: YearlyFuelAnalytics,
    expandedMonths: MutableMap<String, Boolean>,
    onEditEvent: (FuelEventEntity) -> Unit,
    onDeleteEvent: (FuelEventEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    // Only display months that contain records
    val activeMonths = remember(analytics) {
        analytics.monthlyStats.filter { it.events.isNotEmpty() }
    }

    if (activeMonths.isEmpty()) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF101726))
                        .border(1.2.dp, Color(0xFF1E2838), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.LocalGasStation,
                        contentDescription = null,
                        tint = Color(0xFF475569),
                        modifier = Modifier.size(32.dp)
                    )
                }

                Text(
                    text = "No Fuel Records in ${analytics.year}",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    ),
                    color = Color.White
                )

                Text(
                    text = "Refuels recorded via '+ ADD FUEL' during ${analytics.year} will appear grouped here with live mileage and costs.",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp),
                    color = Color(0xFF87929A)
                )
            }
        }
    } else {
        LazyColumn(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(activeMonths, key = { "${it.year}-${it.monthIndex}" }) { monthData ->
                val monthKey = "${monthData.year}-${monthData.monthIndex}"
                // Defaults to expanded
                val isExpanded = expandedMonths.getOrPut(monthKey) { true }
                val arrowRotation by animateFloatAsState(
                    targetValue = if (isExpanded) 180f else 0f,
                    animationSpec = tween(250),
                    label = "arrowRotation"
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF0A0F1A))
                        .border(1.dp, Color(0xFF1A2436), RoundedCornerShape(14.dp))
                ) {
                    // Month Header Accordion Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expandedMonths[monthKey] = !isExpanded }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "${monthData.monthFullName} ${monthData.year}",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Black,
                                    fontSize = 16.sp
                                ),
                                color = Color.White
                            )

                            // Quick monthly summary pills
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                PillBadge(
                                    text = "${monthData.refillCount} refills",
                                    color = Color(0xFF94A3B8)
                                )
                                PillBadge(
                                    text = NumberFormatUtils.formatFuelLitersWithUnit(monthData.totalLiters, 2),
                                    color = Color(0xFF38BDF8)
                                )
                                PillBadge(
                                    text = NumberFormatUtils.formatCurrencyWithSymbol(monthData.totalCost, 2, useGrouping = true),
                                    color = Color(0xFFF59E0B)
                                )
                                if (monthData.mileageKmpl > 0) {
                                    PillBadge(
                                        text = NumberFormatUtils.formatMileageWithUnit(monthData.mileageKmpl, 2),
                                        color = Color(0xFF10B981)
                                    )
                                }
                            }
                        }

                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = if (isExpanded) "Collapse" else "Expand",
                            tint = Color(0xFF87929A),
                            modifier = Modifier
                                .size(20.dp)
                                .rotate(arrowRotation)
                        )
                    }

                    // Expandable List of Refuel Events for this month
                    AnimatedVisibility(
                        visible = isExpanded,
                        enter = fadeIn(tween(200)),
                        exit = fadeOut(tween(200))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            monthData.events.forEach { event ->
                                FuelEventCard(
                                    event = event,
                                    onEdit = { onEditEvent(event) },
                                    onDelete = { onDeleteEvent(event) }
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PillBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace
            ),
            color = color
        )
    }
}

// ==================== FUEL EVENT CARD ====================

@Composable
private fun FuelEventCard(
    event: FuelEventEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val dateStr = remember(event.timestamp) {
        SimpleDateFormat("dd MMM yyyy • hh:mm a", Locale.getDefault()).format(Date(event.timestamp))
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0F1524))
            .border(1.dp, Color(0xFF1E2838), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            // Header Row: Event Tag, MID Gauge Visualization, Date
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color(0xFF0284C7).copy(alpha = 0.2f))
                            .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.45f), RoundedCornerShape(5.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "⛽ REFUEL",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Black,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace
                            ),
                            color = Color(0xFF38BDF8)
                        )
                    }

                    // Mini 10-Pt MID Gauge Visualizer
                    if (event.midLevelObserved in 0..10) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(Color(0xFF141A24))
                                .border(1.dp, Color(0xFF262A31), RoundedCornerShape(5.dp))
                                .padding(horizontal = 5.dp, vertical = 3.dp)
                        ) {
                            for (i in 0 until 10) {
                                val isFilled = i < event.midLevelObserved
                                val barColor = when {
                                    isFilled && i < 2 -> Color(0xFFEF4444)
                                    isFilled && i < 5 -> Color(0xFFF59E0B)
                                    isFilled -> Color(0xFF38BDF8)
                                    else -> Color(0xFF1F2937)
                                }
                                Box(
                                    modifier = Modifier
                                        .size(width = 4.dp, height = 8.dp)
                                        .clip(RoundedCornerShape(1.dp))
                                        .background(barColor)
                                )
                            }
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "${event.midLevelObserved}/10 MID",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = Color(0xFFCBD5E1)
                            )
                        }
                    }
                }

                Text(
                    text = dateStr,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    color = Color(0xFF87929A)
                )
            }

            // Middle Telemetry Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Liters & Rate & Location
                Column(
                    modifier = Modifier.weight(1.3f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = NumberFormatUtils.formatSignedPrecision(event.liters, 2),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Black,
                                fontSize = 21.sp,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = (-0.5).sp
                            ),
                            color = Color(0xFF38BDF8)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "L",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            ),
                            color = Color(0xFF87929A),
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "@ ${NumberFormatUtils.formatCurrencyWithSymbol(event.pricePerLiter, 2)}/L",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace
                            ),
                            color = Color(0xFF87929A),
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                    }

                    if (event.locationName.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "📍", fontSize = 10.sp)
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = event.locationName,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                ),
                                color = Color(0xFF94A3B8),
                                maxLines = 1
                            )
                        }
                    }
                }

                // Total Cost & Badges
                Column(
                    modifier = Modifier.weight(1.2f),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = NumberFormatUtils.formatCurrencyWithSymbol(event.totalCost, 2, useGrouping = true),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Black,
                            fontSize = 19.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        color = Color(0xFFF59E0B)
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (event.odometerKm > 0) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(Color(0xFF141A24))
                                    .border(1.dp, Color(0xFF262A31), RoundedCornerShape(5.dp))
                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "🚗 ${NumberFormatUtils.formatOdometer(event.odometerKm, 2)} km",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    color = Color.White
                                )
                            }
                        }

                        if (event.calculatedMileageKmpl > 0) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(Color(0xFF10B981).copy(alpha = 0.15f))
                                    .border(1.dp, Color(0xFF10B981).copy(alpha = 0.4f), RoundedCornerShape(5.dp))
                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "⚡ ${NumberFormatUtils.formatMileageWithUnit(event.calculatedMileageKmpl, 2)}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Black,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    color = Color(0xFF10B981)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                // Action Buttons: Edit and Delete with distinct styling and proper touch boundaries
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF0F2642))
                            .border(1.2.dp, Color(0xFF38BDF8).copy(alpha = 0.55f), CircleShape)
                            .clickable(onClick = onEdit),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit Refuel",
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF3B151E))
                            .border(1.2.dp, Color(0xFFEF4444).copy(alpha = 0.55f), CircleShape)
                            .clickable(onClick = onDelete),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Refuel",
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}

// ==================== DEDICATED STATISTICS VIEW ====================

@Composable
private fun FuelStatisticsView(
    analytics: YearlyFuelAnalytics,
    selectedChartType: FuelChartType,
    onSelectChartType: (FuelChartType) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Chart Selector Tabs
        item {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(FuelChartType.values()) { chartType ->
                    val isSelected = chartType == selectedChartType
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) chartType.color.copy(alpha = 0.22f) else Color(0xFF0C121F))
                            .border(
                                width = 1.2.dp,
                                color = if (isSelected) chartType.color else Color(0xFF1E2838),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { onSelectChartType(chartType) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = chartType.label,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                                fontSize = 14.sp
                            ),
                            color = if (isSelected) chartType.color else Color(0xFF94A3B8)
                        )
                    }
                }
            }
        }

        // 2. Interactive Automotive Canvas Chart
        item {
            val chartValues = analytics.monthlyStats.map { stat ->
                when (selectedChartType) {
                    FuelChartType.EXPENSES -> stat.totalCost
                    FuelChartType.CONSUMPTION -> stat.totalLiters
                    FuelChartType.MILEAGE -> stat.mileageKmpl
                    FuelChartType.DISTANCE -> stat.distanceKm
                    FuelChartType.PRICE_TREND -> stat.avgPricePerLiter
                    FuelChartType.FREQUENCY -> stat.refillCount.toDouble()
                }
            }
            val activeChartValues = chartValues.filter { it > 0.0 }
            val annualTotal = when (selectedChartType) {
                FuelChartType.EXPENSES -> NumberFormatUtils.formatCurrencyWithSymbol(analytics.totalCost, 2, useGrouping = true)
                FuelChartType.CONSUMPTION -> NumberFormatUtils.formatFuelLitersWithUnit(analytics.totalLiters, 2)
                FuelChartType.MILEAGE -> if (analytics.avgMileageKmpl > 0.0) "${NumberFormatUtils.formatMileage(analytics.avgMileageKmpl, 2)} km/L" else "--"
                FuelChartType.DISTANCE -> NumberFormatUtils.formatDistanceWithUnit(analytics.totalDistanceKm, 2)
                FuelChartType.PRICE_TREND -> if (analytics.avgPricePerLiter > 0.0) "₹${NumberFormatUtils.formatPrecision(analytics.avgPricePerLiter, 2)}/L" else "--"
                FuelChartType.FREQUENCY -> "${analytics.totalRefills} Refills"
            }
            val monthlyAvg = if (activeChartValues.isNotEmpty()) {
                val avg = activeChartValues.average()
                when (selectedChartType) {
                    FuelChartType.EXPENSES -> "₹${NumberFormatUtils.formatPrecision(avg, 1)}/mo"
                    FuelChartType.CONSUMPTION -> "${NumberFormatUtils.formatPrecision(avg, 1)} L/mo"
                    FuelChartType.MILEAGE -> "${NumberFormatUtils.formatPrecision(avg, 1)} km/L"
                    FuelChartType.DISTANCE -> "${NumberFormatUtils.formatPrecision(avg, 1)} km/mo"
                    FuelChartType.PRICE_TREND -> "₹${NumberFormatUtils.formatPrecision(avg, 1)}/L"
                    FuelChartType.FREQUENCY -> "${NumberFormatUtils.formatPrecision(avg, 1)}/mo"
                }
            } else "--"

            val maxValIdx = chartValues.indices.maxByOrNull { chartValues[it] } ?: -1
            val peakMonthInfo = if (maxValIdx >= 0 && chartValues[maxValIdx] > 0.0) {
                val monthName = analytics.monthlyStats.getOrNull(maxValIdx)?.monthShortName ?: ""
                val peakVal = chartValues[maxValIdx]
                val formattedPeak = when (selectedChartType) {
                    FuelChartType.EXPENSES -> "₹${NumberFormatUtils.formatPrecision(peakVal, 0)}"
                    FuelChartType.CONSUMPTION -> "${NumberFormatUtils.formatPrecision(peakVal, 1)}L"
                    FuelChartType.MILEAGE -> "${NumberFormatUtils.formatPrecision(peakVal, 1)}"
                    FuelChartType.DISTANCE -> "${NumberFormatUtils.formatPrecision(peakVal, 0)}km"
                    FuelChartType.PRICE_TREND -> "₹${NumberFormatUtils.formatPrecision(peakVal, 1)}"
                    FuelChartType.FREQUENCY -> "${peakVal.toInt()}x"
                }
                "$monthName ($formattedPeak)"
            } else "None"

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF0C1527))
                    .border(1.2.dp, Color(0xFF1E2E44), RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Chart Top Legend & Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(selectedChartType.color)
                            )
                            Text(
                                text = "${selectedChartType.label.uppercase()} BY MONTH • ${analytics.year}",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Black, fontSize = 14.sp, letterSpacing = 0.5.sp),
                                color = Color.White
                            )
                        }

                        Text(
                            text = "Unit: ${selectedChartType.unit}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            ),
                            color = Color(0xFF87929A)
                        )
                    }

                    // Quick Telemetry Strip
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF080E1A))
                            .border(1.dp, Color(0xFF172338), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("ANNUAL TOTAL:", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color(0xFF64748B)))
                            Text(annualTotal, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Black, fontSize = 13.5.sp, fontFamily = FontFamily.Monospace, color = Color.White))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("MONTHLY AVG:", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color(0xFF64748B)))
                            Text(monthlyAvg, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Black, fontSize = 13.5.sp, fontFamily = FontFamily.Monospace, color = selectedChartType.color))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("PEAK MONTH:", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color(0xFF64748B)))
                            Text(peakMonthInfo, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Black, fontSize = 13.5.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFF59E0B)))
                        }
                    }

                    // Render Canvas Bar / Line Chart (expanded to 260dp for commanding presence)
                    AutomotiveFuelChartCanvas(
                        monthlyStats = analytics.monthlyStats,
                        chartType = selectedChartType,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                    )
                }
            }
        }

        // 3. Additional In-Depth Automotive Analytics Cards
        item {
            Text(
                text = "PERFORMANCE ANALYTICS & INSIGHTS",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Black,
                    fontSize = 11.5.sp,
                    letterSpacing = 0.8.sp
                ),
                color = Color(0xFF87929A),
                modifier = Modifier.padding(start = 2.dp, top = 2.dp)
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Row 1: Highest & Lowest Monthly Expenses
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    InsightCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.AutoMirrored.Filled.TrendingUp,
                        iconTint = Color(0xFFF59E0B),
                        title = "HIGHEST MONTHLY SPEND",
                        primaryValue = analytics.highestExpenseMonth?.let { NumberFormatUtils.formatCurrencyWithSymbol(it.second, 2, useGrouping = true) } ?: "--",
                        subtitle = analytics.highestExpenseMonth?.let { "Peak in ${it.first}" } ?: "No expense recorded"
                    )

                    InsightCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.AutoMirrored.Filled.TrendingDown,
                        iconTint = Color(0xFF10B981),
                        title = "LOWEST MONTHLY SPEND",
                        primaryValue = analytics.lowestExpenseMonth?.let { NumberFormatUtils.formatCurrencyWithSymbol(it.second, 2, useGrouping = true) } ?: "--",
                        subtitle = analytics.lowestExpenseMonth?.let { "Trough in ${it.first}" } ?: "No expense recorded"
                    )
                }

                // Row 2: Best & Lowest Mileage
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    InsightCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.Speed,
                        iconTint = Color(0xFF10B981),
                        title = "BEST MONTHLY MILEAGE",
                        primaryValue = analytics.bestMileageMonth?.let { NumberFormatUtils.formatMileageWithUnit(it.second, 2) } ?: "--",
                        subtitle = analytics.bestMileageMonth?.let { "Achieved in ${it.first}" } ?: "Need valid odometer delta"
                    )

                    InsightCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.Speed,
                        iconTint = Color(0xFFEF4444),
                        title = "LOWEST MONTHLY MILEAGE",
                        primaryValue = analytics.lowestMileageMonth?.let { NumberFormatUtils.formatMileageWithUnit(it.second, 2) } ?: "--",
                        subtitle = analytics.lowestMileageMonth?.let { "Recorded in ${it.first}" } ?: "Need valid odometer delta"
                    )
                }

                // Row 3: Average Price, Most Expensive Refill, and YoY Comparison
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    InsightCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.AttachMoney,
                        iconTint = Color(0xFF38BDF8),
                        title = "AVERAGE FUEL PRICE",
                        primaryValue = if (analytics.avgPricePerLiter > 0) "${NumberFormatUtils.formatCurrencyWithSymbol(analytics.avgPricePerLiter, 2)}/L" else "--",
                        subtitle = "Weighted average rate"
                    )

                    InsightCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.LocalGasStation,
                        iconTint = Color(0xFFFB923C),
                        title = "LARGEST REFILL BILL",
                        primaryValue = analytics.mostExpensiveRefill?.let { NumberFormatUtils.formatCurrencyWithSymbol(it.totalCost, 2, useGrouping = true) } ?: "--",
                        subtitle = analytics.mostExpensiveRefill?.let {
                            val d = SimpleDateFormat("dd MMM", Locale.US).format(Date(it.timestamp))
                            "${NumberFormatUtils.formatFuelLitersWithUnit(it.liters, 2)} on $d"
                        } ?: "No refills yet"
                    )

                    // YoY Comparison
                    InsightCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.AutoMirrored.Filled.ShowChart,
                        iconTint = Color(0xFFA855F7),
                        title = "YEAR-OVER-YEAR SPEND",
                        primaryValue = analytics.yoyCostDiffPercent?.let {
                            val sign = if (it >= 0) "+" else ""
                            "$sign${NumberFormatUtils.formatPrecision(it, 2)}%"
                        } ?: "--",
                        subtitle = analytics.prevYearCost?.let {
                            "vs ${NumberFormatUtils.formatCurrencyWithSymbol(it, 2, useGrouping = true)} in ${analytics.year - 1}"
                        } ?: "No ${analytics.year - 1} baseline"
                    )
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}

@Composable
private fun InsightCard(
    title: String,
    primaryValue: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 84.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF0C1527))
            .border(1.2.dp, Color(0xFF1E2E44), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(iconTint.copy(alpha = 0.15f))
                    .border(1.dp, iconTint.copy(alpha = 0.40f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = primaryValue,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Black,
                        fontSize = 19.sp,
                        fontFamily = FontFamily.Monospace,
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    ),
                    color = Color.White
                )

                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.5.sp,
                        letterSpacing = 0.5.sp,
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    ),
                    color = Color(0xFF87929A),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 11.5.sp,
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    ),
                    color = Color(0xFF94A3B8),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ==================== AUTOMOTIVE CANVAS CHART RENDERER ====================

private fun formatScaleValue(value: Double, chartType: FuelChartType): String {
    if (value <= 0.0) return "0"
    return when (chartType) {
        FuelChartType.EXPENSES -> if (value >= 1000) "₹${NumberFormatUtils.formatPrecision(value / 1000.0, 1)}k" else "₹${value.toInt()}"
        FuelChartType.CONSUMPTION -> "${NumberFormatUtils.formatPrecision(value, 1)}L"
        FuelChartType.MILEAGE -> "${NumberFormatUtils.formatPrecision(value, 1)}"
        FuelChartType.DISTANCE -> if (value >= 1000) "${NumberFormatUtils.formatPrecision(value / 1000.0, 1)}k" else "${value.toInt()}km"
        FuelChartType.PRICE_TREND -> "₹${NumberFormatUtils.formatPrecision(value, 0)}"
        FuelChartType.FREQUENCY -> "${value.toInt()}"
    }
}

@Composable
private fun AutomotiveFuelChartCanvas(
    monthlyStats: List<MonthlyFuelStats>,
    chartType: FuelChartType,
    modifier: Modifier = Modifier
) {
    val accentColor = chartType.color
    val gridColor = Color(0xFF152033)

    val axisTextPaint = remember {
        Paint().apply {
            color = android.graphics.Color.parseColor("#64748B")
            textSize = 15f
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
            textAlign = Paint.Align.RIGHT
        }
    }
    val monthTextPaint = remember {
        Paint().apply {
            color = android.graphics.Color.parseColor("#94A3B8")
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
    }
    val valueTextPaint = remember {
        Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 19f
            isFakeBoldText = true
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
    }
    val peakBadgePaint = remember {
        Paint().apply {
            color = android.graphics.Color.parseColor("#F59E0B")
            textSize = 13f
            isFakeBoldText = true
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
    }
    val avgBadgeTextPaint = remember {
        Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 13f
            isFakeBoldText = true
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
            textAlign = Paint.Align.LEFT
        }
    }

    Canvas(modifier = modifier) {
        val totalWidth = size.width
        val totalHeight = size.height

        val bottomPadding = 32f
        val topPadding = 38f
        val leftPadding = 64f
        val rightPadding = 24f

        val graphWidth = totalWidth - leftPadding - rightPadding
        val graphHeight = totalHeight - topPadding - bottomPadding

        // Extract values for 12 months
        val values = monthlyStats.map { stat ->
            when (chartType) {
                FuelChartType.EXPENSES -> stat.totalCost
                FuelChartType.CONSUMPTION -> stat.totalLiters
                FuelChartType.MILEAGE -> stat.mileageKmpl
                FuelChartType.DISTANCE -> stat.distanceKm
                FuelChartType.PRICE_TREND -> stat.avgPricePerLiter
                FuelChartType.FREQUENCY -> stat.refillCount.toDouble()
            }
        }

        val rawMax = values.maxOrNull() ?: 0.0
        val effectiveMax = if (rawMax > 0.0) rawMax * 1.25 else 10.0
        val activeValues = values.filter { it > 0.0 }
        val avgValue = if (activeValues.isNotEmpty()) activeValues.average() else 0.0
        val maxValIdx = values.indices.maxByOrNull { values[it] } ?: -1

        // 1. Draw 4 horizontal reference grid lines with Y-axis scale numbers
        val steps = 3
        for (i in 0..steps) {
            val y = topPadding + (graphHeight / steps) * i
            val scaleVal = effectiveMax * (1.0 - i.toDouble() / steps)

            drawLine(
                color = gridColor,
                start = Offset(leftPadding, y),
                end = Offset(totalWidth - rightPadding, y),
                strokeWidth = 1f
            )

            // Draw Y-axis scale value text on left
            val label = formatScaleValue(scaleVal, chartType)
            drawContext.canvas.nativeCanvas.drawText(
                label,
                leftPadding - 8f,
                y + 5f,
                axisTextPaint
            )
        }

        val colWidth = graphWidth / 12f
        val barWidth = (colWidth * 0.52f).coerceIn(14f, 36f)

        // 2. Full-height Background Channel Tracks for every month
        for (m in 0 until 12) {
            val centerX = leftPadding + colWidth * m + colWidth / 2f
            val barLeft = centerX - barWidth / 2f
            drawRoundRect(
                color = Color(0xFF101827),
                topLeft = Offset(barLeft, topPadding),
                size = Size(barWidth, graphHeight),
                cornerRadius = CornerRadius(5f, 5f)
            )
            drawRoundRect(
                color = Color(0xFF19253B),
                topLeft = Offset(barLeft, topPadding),
                size = Size(barWidth, graphHeight),
                cornerRadius = CornerRadius(5f, 5f),
                style = Stroke(0.8f)
            )
        }

        // 3. Yearly Average ("AVG") Dashed Reference Line
        if (avgValue > 0.0) {
            val avgY = topPadding + graphHeight * (1f - (avgValue / effectiveMax).toFloat())
            val dashWidth = 10f
            val dashGap = 6f
            var curX = leftPadding
            while (curX < totalWidth - rightPadding) {
                val endX = (curX + dashWidth).coerceAtMost(totalWidth - rightPadding)
                drawLine(
                    color = accentColor.copy(alpha = 0.55f),
                    start = Offset(curX, avgY),
                    end = Offset(endX, avgY),
                    strokeWidth = 1.6f
                )
                curX += dashWidth + dashGap
            }

            // Draw AVG floating badge on right edge
            val avgText = "AVG ${formatScaleValue(avgValue, chartType)}"
            val avgBadgeW = 90f
            val avgBadgeH = 20f
            val avgBadgeX = totalWidth - rightPadding - avgBadgeW
            val avgBadgeY = (avgY - avgBadgeH / 2f).coerceIn(topPadding, topPadding + graphHeight - avgBadgeH)
            drawRoundRect(
                color = Color(0xFF0C1527),
                topLeft = Offset(avgBadgeX, avgBadgeY),
                size = Size(avgBadgeW, avgBadgeH),
                cornerRadius = CornerRadius(4f, 4f)
            )
            drawRoundRect(
                color = accentColor.copy(alpha = 0.65f),
                topLeft = Offset(avgBadgeX, avgBadgeY),
                size = Size(avgBadgeW, avgBadgeH),
                cornerRadius = CornerRadius(4f, 4f),
                style = Stroke(1f)
            )
            drawContext.canvas.nativeCanvas.drawText(
                avgText,
                avgBadgeX + 6f,
                avgBadgeY + 14f,
                avgBadgeTextPaint
            )
        }

        // 4. Chart Visualization (Line or Gradient Bars)
        if (chartType == FuelChartType.PRICE_TREND) {
            // Draw Trend Line Chart with smooth cubic curve & glowing nodes
            val validPoints = mutableListOf<Offset>()
            for (m in 0 until 12) {
                val value = values[m]
                val centerX = leftPadding + colWidth * m + colWidth / 2f
                if (value > 0.0) {
                    val ratio = (value / effectiveMax).coerceIn(0.0, 1.0).toFloat()
                    val centerY = topPadding + graphHeight * (1f - ratio)
                    validPoints.add(Offset(centerX, centerY))
                }
            }

            if (validPoints.size >= 2) {
                val path = Path().apply {
                    moveTo(validPoints.first().x, validPoints.first().y)
                    for (i in 0 until validPoints.size - 1) {
                        val p0 = validPoints[i]
                        val p1 = validPoints[i + 1]
                        val cx = (p0.x + p1.x) / 2f
                        cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
                    }
                }

                // Fill area under line
                val fillPath = Path().apply {
                    addPath(path)
                    lineTo(validPoints.last().x, topPadding + graphHeight)
                    lineTo(validPoints.first().x, topPadding + graphHeight)
                    close()
                }
                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(accentColor.copy(alpha = 0.35f), Color.Transparent),
                        startY = topPadding,
                        endY = topPadding + graphHeight
                    )
                )

                // Outline stroke
                drawPath(
                    path = path,
                    color = accentColor,
                    style = Stroke(width = 3.5f, cap = StrokeCap.Round)
                )
            }

            // Draw Glowing Nodes & Price Callouts
            for (pt in validPoints) {
                drawCircle(color = accentColor.copy(alpha = 0.35f), radius = 8f, center = pt)
                drawCircle(color = accentColor, radius = 5f, center = pt)
                drawCircle(color = Color.White, radius = 2f, center = pt)
            }
            var ptIdx = 0
            for (m in 0 until 12) {
                val value = values[m]
                if (value > 0.0) {
                    val pt = validPoints.getOrNull(ptIdx)
                    if (pt != null) {
                        drawContext.canvas.nativeCanvas.drawText(
                            NumberFormatUtils.formatCurrencyWithSymbol(value, 2),
                            pt.x,
                            pt.y - 12f,
                            valueTextPaint
                        )
                    }
                    ptIdx++
                }
            }
        } else {
            // Draw Vertical Automotive Gradient Bars
            for (m in 0 until 12) {
                val value = values[m]
                val centerX = leftPadding + colWidth * m + colWidth / 2f
                val barLeft = centerX - barWidth / 2f

                if (value > 0.0) {
                    val ratio = (value / effectiveMax).coerceIn(0.0, 1.0).toFloat()
                    val barHeight = (graphHeight * ratio).coerceAtLeast(8f)
                    val barTop = topPadding + graphHeight - barHeight
                    val isPeak = m == maxValIdx && values[maxValIdx] > 0.0

                    // Gradient bar fill
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colors = if (isPeak) {
                                listOf(accentColor, accentColor.copy(alpha = 0.40f))
                            } else {
                                listOf(accentColor.copy(alpha = 0.85f), accentColor.copy(alpha = 0.20f))
                            },
                            startY = barTop,
                            endY = topPadding + graphHeight
                        ),
                        topLeft = Offset(barLeft, barTop),
                        size = Size(barWidth, barHeight),
                        cornerRadius = CornerRadius(5f, 5f)
                    )

                    // Top highlight rim
                    drawRoundRect(
                        color = if (isPeak) Color.White else accentColor,
                        topLeft = Offset(barLeft, barTop),
                        size = Size(barWidth, 3.5f),
                        cornerRadius = CornerRadius(2f, 2f)
                    )

                    // Peak Beacon & Badge
                    if (isPeak) {
                        drawCircle(color = accentColor.copy(alpha = 0.35f), radius = 9f, center = Offset(centerX, barTop))
                        drawCircle(color = Color.White, radius = 3.5f, center = Offset(centerX, barTop))
                        drawContext.canvas.nativeCanvas.drawText("★ TOP", centerX, barTop - 24f, peakBadgePaint)
                    }

                    // Value label above bar
                    val formattedVal = when (chartType) {
                        FuelChartType.EXPENSES -> if (value >= 1000) "${NumberFormatUtils.formatPrecision(value / 1000.0, 1)}k" else "${value.toInt()}"
                        FuelChartType.CONSUMPTION -> NumberFormatUtils.formatFuelLiters(value, 1)
                        FuelChartType.MILEAGE -> NumberFormatUtils.formatMileage(value, 1)
                        FuelChartType.DISTANCE -> if (value >= 1000) "${NumberFormatUtils.formatPrecision(value / 1000.0, 1)}k" else "${value.toInt()}"
                        FuelChartType.FREQUENCY -> "${value.toInt()}"
                        else -> "${value.toInt()}"
                    }
                    drawContext.canvas.nativeCanvas.drawText(
                        formattedVal,
                        centerX,
                        barTop - 8f,
                        valueTextPaint
                    )
                } else {
                    // Empty month recessed indicator
                    drawRoundRect(
                        color = Color(0xFF1E2A3E),
                        topLeft = Offset(barLeft, topPadding + graphHeight - 4f),
                        size = Size(barWidth, 4f),
                        cornerRadius = CornerRadius(2f, 2f)
                    )
                }
            }
        }

        // 5. Month Labels at bottom (refined 17f size, highlighted for peak/active)
        for (m in 0 until 12) {
            val monthLabel = monthlyStats.getOrNull(m)?.monthShortName ?: ""
            val centerX = leftPadding + colWidth * m + colWidth / 2f
            val isPeak = m == maxValIdx && values[m] > 0.0

            monthTextPaint.color = if (isPeak) {
                accentColor.toArgb()
            } else if (values[m] > 0.0) {
                android.graphics.Color.WHITE
            } else {
                android.graphics.Color.parseColor("#64748B")
            }

            drawContext.canvas.nativeCanvas.drawText(
                monthLabel,
                centerX,
                totalHeight - 8f,
                monthTextPaint
            )
        }
    }
}

// ==================== EDIT REFUEL DIALOG ====================

@Composable
private fun EditFuelEventDialog(
    event: FuelEventEntity,
    onDismiss: () -> Unit,
    onSave: (FuelEventEntity) -> Unit
) {
    var litersText by remember { mutableStateOf(event.liters.toString()) }
    var priceText by remember { mutableStateOf(event.pricePerLiter.toString()) }
    var costText by remember { mutableStateOf(event.totalCost.toString()) }
    var odoText by remember { mutableStateOf(NumberFormatUtils.formatOdometer(event.odometerKm, 0)) }
    var locationText by remember { mutableStateOf(event.locationName) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xFF0F182A))
                .border(1.5.dp, Color(0xFF2563EB), RoundedCornerShape(22.dp))
                .padding(22.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            tint = Color(0xFF60A5FA),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Edit Refuel Entry",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Black,
                                fontSize = 20.sp
                            ),
                            color = Color.White
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF1E2D4A))
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }

                // Location field
                OutlinedTextField(
                    value = locationText,
                    onValueChange = { locationText = it },
                    label = { Text("Fuel Station / Location", color = Cockpit_TextSecondary) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Cockpit_Cyan,
                        unfocusedBorderColor = Color(0xFF1E2D4A)
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                // 2x2 fields
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = litersText,
                        onValueChange = {
                            litersText = it
                            val l = it.toDoubleOrNull() ?: 0.0
                            val p = priceText.toDoubleOrNull() ?: 0.0
                            costText = NumberFormatUtils.formatPrecision(l * p, 2)
                        },
                        label = { Text("Quantity (L)", color = Cockpit_TextSecondary) },
                        modifier = Modifier.weight(1f),
                        textStyle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Black, color = Color.White),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Cockpit_Cyan,
                            unfocusedBorderColor = Color(0xFF1E2D4A)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )

                    OutlinedTextField(
                        value = priceText,
                        onValueChange = {
                            priceText = it
                            val l = litersText.toDoubleOrNull() ?: 0.0
                            val p = it.toDoubleOrNull() ?: 0.0
                            costText = NumberFormatUtils.formatPrecision(l * p, 2)
                        },
                        label = { Text("Price (₹ / L)", color = Cockpit_TextSecondary) },
                        modifier = Modifier.weight(1f),
                        textStyle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Black, color = Color.White),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Cockpit_Cyan,
                            unfocusedBorderColor = Color(0xFF1E2D4A)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = costText,
                        onValueChange = { costText = it },
                        label = { Text("Total Bill (₹)", color = Cockpit_TextSecondary) },
                        modifier = Modifier.weight(1f),
                        textStyle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Black, color = Color(0xFFFFD54F)),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(0xFFFFD54F),
                            unfocusedTextColor = Color(0xFFFFD54F),
                            focusedBorderColor = Color(0xFFFFD54F),
                            unfocusedBorderColor = Color(0xFF1E2D4A)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )

                    OutlinedTextField(
                        value = odoText,
                        onValueChange = { odoText = it },
                        label = { Text("Odometer (km)", color = Cockpit_TextSecondary) },
                        modifier = Modifier.weight(1f),
                        textStyle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Black, color = Color.White),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Cockpit_Cyan,
                            unfocusedBorderColor = Color(0xFF1E2D4A)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = Color.White, fontSize = 15.sp)
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Button(
                        onClick = {
                            val newL = litersText.toDoubleOrNull() ?: event.liters
                            val newP = priceText.toDoubleOrNull() ?: event.pricePerLiter
                            val newC = costText.toDoubleOrNull() ?: (newL * newP)
                            val newO = odoText.toDoubleOrNull() ?: event.odometerKm
                            val updated = event.copy(
                                liters = newL,
                                pricePerLiter = newP,
                                totalCost = newC,
                                odometerKm = newO,
                                locationName = locationText.trim()
                            )
                            onSave(updated)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Cockpit_BlueAccent),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save Changes", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }
            }
        }
    }
}

// ==================== CALCULATION & DATA AGGREGATION ENGINE ====================

private fun computeYearlyAnalytics(
    allEvents: List<FuelEventEntity>,
    selectedYear: Int
): YearlyFuelAnalytics {
    val cal = Calendar.getInstance()
    val monthShortNames = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val monthFullNames = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December"
    )

    // Filter events for the selected year
    val yearEvents = allEvents.filter {
        cal.timeInMillis = it.timestamp
        cal.get(Calendar.YEAR) == selectedYear
    }

    // Sort chronologically (oldest to newest) for accurate delta distance
    val chronEvents = yearEvents.sortedBy { it.timestamp }

    val totalCost = yearEvents.sumOf { it.totalCost }
    val totalLiters = yearEvents.sumOf { it.liters }
    val totalRefills = yearEvents.count { it.liters > 0 || it.eventType == "REFUEL" }

    // Accurate Year Distance calculation from odometer readings
    val validOdos = chronEvents.map { it.odometerKm }.filter { it > 0 }
    val totalDistanceKm = if (validOdos.size >= 2) {
        max(0.0, validOdos.last() - validOdos.first())
    } else 0.0

    // Average Mileage calculation: prioritize verified Fuel Engine interval calculations
    val validMileages = yearEvents.map { it.calculatedMileageKmpl }.filter { it in 5.0..45.0 }
    val avgMileageKmpl = if (validMileages.isNotEmpty()) {
        validMileages.average()
    } else if (totalDistanceKm > 0 && totalLiters > 0 && totalRefills >= 2) {
        val consumedLiters = chronEvents.drop(1).sumOf { it.liters }
        if (consumedLiters > 0) totalDistanceKm / consumedLiters else totalDistanceKm / totalLiters
    } else {
        0.0
    }

    val avgPricePerLiter = if (totalLiters > 0 && totalCost > 0) {
        totalCost / totalLiters
    } else {
        val validPrices = yearEvents.map { it.pricePerLiter }.filter { it > 0 }
        if (validPrices.isNotEmpty()) validPrices.average() else 0.0
    }

    // Monthly Aggregation (12 calendar months: Jan to Dec)
    val monthlyStats = (0 until 12).map { monthIdx ->
        val monthEvents = yearEvents.filter {
            cal.timeInMillis = it.timestamp
            cal.get(Calendar.MONTH) == monthIdx
        }.sortedByDescending { it.timestamp } // Latest first inside month

        val mCost = monthEvents.sumOf { it.totalCost }
        val mLiters = monthEvents.sumOf { it.liters }
        val mRefills = monthEvents.count { it.liters > 0 || it.eventType == "REFUEL" }

        // Monthly Distance
        val mChron = monthEvents.sortedBy { it.timestamp }
        val mOdos = mChron.map { it.odometerKm }.filter { it > 0 }
        val mDistance = if (mOdos.size >= 2) {
            max(0.0, mOdos.last() - mOdos.first())
        } else 0.0

        val mValidMileages = monthEvents.map { it.calculatedMileageKmpl }.filter { it in 5.0..45.0 }
        val mMileage = if (mValidMileages.isNotEmpty()) {
            mValidMileages.average()
        } else if (mDistance > 0 && mLiters > 0 && mRefills >= 2) {
            val mConsumed = mChron.drop(1).sumOf { it.liters }
            if (mConsumed > 0) mDistance / mConsumed else mDistance / mLiters
        } else {
            0.0
        }

        val mAvgPrice = if (mLiters > 0 && mCost > 0) {
            mCost / mLiters
        } else {
            val prices = monthEvents.map { it.pricePerLiter }.filter { it > 0 }
            if (prices.isNotEmpty()) prices.average() else 0.0
        }

        MonthlyFuelStats(
            monthIndex = monthIdx,
            monthShortName = monthShortNames[monthIdx],
            monthFullName = monthFullNames[monthIdx],
            year = selectedYear,
            totalCost = mCost,
            totalLiters = mLiters,
            refillCount = mRefills,
            distanceKm = mDistance,
            mileageKmpl = mMileage,
            avgPricePerLiter = mAvgPrice,
            events = monthEvents
        )
    }

    // Month insights
    val activeMonthsWithSpend = monthlyStats.filter { it.totalCost > 0 }
    val highestExpenseMonth = activeMonthsWithSpend.maxByOrNull { it.totalCost }?.let {
        Pair(it.monthFullName, it.totalCost)
    }
    val lowestExpenseMonth = activeMonthsWithSpend.minByOrNull { it.totalCost }?.let {
        Pair(it.monthFullName, it.totalCost)
    }

    val activeMonthsWithMileage = monthlyStats.filter { it.mileageKmpl > 0 }
    val bestMileageMonth = activeMonthsWithMileage.maxByOrNull { it.mileageKmpl }?.let {
        Pair(it.monthFullName, it.mileageKmpl)
    }
    val lowestMileageMonth = activeMonthsWithMileage.minByOrNull { it.mileageKmpl }?.let {
        Pair(it.monthFullName, it.mileageKmpl)
    }

    val mostExpensiveRefill = yearEvents.maxByOrNull { it.totalCost }

    // Year-over-Year (YoY) comparison
    val prevYearEvents = allEvents.filter {
        cal.timeInMillis = it.timestamp
        cal.get(Calendar.YEAR) == selectedYear - 1
    }
    val prevYearCost = if (prevYearEvents.isNotEmpty()) prevYearEvents.sumOf { it.totalCost } else null
    val yoyCostDiffPercent = if (prevYearCost != null && prevYearCost > 0.0 && totalCost > 0.0) {
        ((totalCost - prevYearCost) / prevYearCost) * 100.0
    } else null

    return YearlyFuelAnalytics(
        year = selectedYear,
        totalCost = totalCost,
        totalLiters = totalLiters,
        totalRefills = totalRefills,
        totalDistanceKm = totalDistanceKm,
        avgMileageKmpl = avgMileageKmpl,
        avgPricePerLiter = avgPricePerLiter,
        highestExpenseMonth = highestExpenseMonth,
        lowestExpenseMonth = lowestExpenseMonth,
        bestMileageMonth = bestMileageMonth,
        lowestMileageMonth = lowestMileageMonth,
        mostExpensiveRefill = mostExpensiveRefill,
        yoyCostDiffPercent = yoyCostDiffPercent,
        prevYearCost = prevYearCost,
        monthlyStats = monthlyStats
    )
}

@Composable
fun FuelHistoryDialog(
    fuelEvents: List<FuelEventEntity>,
    onDismiss: () -> Unit,
    onEditEvent: (FuelEventEntity) -> Unit = {},
    onDeleteEvent: (Long) -> Unit = {}
) {
    FuelHistoryScreen(
        fuelEvents = fuelEvents,
        onBackToCockpit = onDismiss,
        onEditEvent = onEditEvent,
        onDeleteEvent = onDeleteEvent
    )
}
