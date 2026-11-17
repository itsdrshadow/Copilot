package com.antigravity.copilot.feature.fuel

import com.antigravity.copilot.core.designsystem.components.cockpitBackground
import com.antigravity.copilot.core.designsystem.components.cockpitSurface
import com.antigravity.copilot.core.designsystem.components.TintedBadge
import com.antigravity.copilot.core.designsystem.components.BadgeSize
import com.antigravity.copilot.core.designsystem.tokens.Palette
import com.antigravity.copilot.core.designsystem.tokens.CopilotShapes
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
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
import com.antigravity.copilot.core.util.NumberFormatUtils
import com.antigravity.copilot.core.database.FuelEventEntity
import com.antigravity.copilot.core.designsystem.*
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


// ==================== MAIN DIALOG COMPOSABLE ====================

@Composable
fun FuelHistoryScreen(
    fuelEvents: List<FuelEventEntity>,
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
            .cockpitBackground()
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
                                .cockpitSurface(Color(0xFF141C2B), Palette.Ink750, CopilotShapes.R12, borderWidth = 1.2.dp)
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
                                .cockpitSurface(Color(0xFF0F263E), Palette.Sky400.copy(alpha = 0.5f), CopilotShapes.R12, borderWidth = 1.2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocalGasStation,
                                contentDescription = null,
                                tint = Palette.Sky400,
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
                                    style = CockpitTheme.typography.headlineLarge.copy(
                                        letterSpacing = (-0.5).sp
                                    ),
                                    color = Color.White
                                )

                                // Current Year Badge
                                Box(
                                    modifier = Modifier
                                        .cockpitSurface(Color(0xFF142033), Palette.Sky400.copy(alpha = 0.35f), CopilotShapes.R6)
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = "$selectedYear",
                                        style = CockpitTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Black,
                                            fontFeatureSettings = "tnum"
                                        ),
                                        color = Palette.Sky400
                                    )
                                }
                            }

                            Text(
                                text = "HISTORICAL LOGS • TANK-TO-TANK TELEMETRY • ACCURATE CONSUMPTION",
                                style = CockpitTheme.typography.labelSmall.copy(
                                    letterSpacing = 0.5.sp
                                ),
                                color = Palette.Steel400
                            )
                        }
                    }

                    // Top Action Controls: Year Selector, Close Button
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
                                        .clip(CopilotShapes.R12)
                                        .background(if (isSelected) Palette.Ink600 else Palette.Ink900)
                                        .border(
                                            width = 1.2.dp,
                                            color = if (isSelected) Palette.Sky400 else Palette.Ink750,
                                            shape = CopilotShapes.R12
                                        )
                                        .clickable { selectedYear = year }
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "$year",
                                        style = CockpitTheme.typography.labelMedium.copy(
                                            fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                                            fontFeatureSettings = "tnum"
                                        ),
                                        color = if (isSelected) Color.White else Palette.Steel400
                                    )
                                }
                            }
                        }

                    }
                }

                // ==================== 2. YEARLY SUMMARY TILES ====================
                YearlySummaryBanner(
                    analytics = yearlyAnalytics,
                    isCompact = isCompact
                )

                // ==================== 3. CONTENT AREA ====================
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
                    color = CockpitTheme.colors.textSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteEvent(targetEvent.id)
                        eventToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Cockpit_RedAlert),
                    shape = CopilotShapes.R10
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
            .cockpitSurface(Color(0xFF0B1019), Palette.Ink750, CopilotShapes.R14, borderWidth = 1.2.dp)
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
            color = Palette.Sky400
        )

        // Total Cost
        FuelStatBadgeCard(
            modifier = Modifier.weight(1.25f),
            label = "TOTAL COST",
            value = if (analytics.totalCost > 0) NumberFormatUtils.formatCurrency(analytics.totalCost, 2, useGrouping = true) else "0",
            unit = "₹",
            color = Palette.Amber500
        )

        // Average Mileage (True Tank-to-Tank / Delta)
        FuelStatBadgeCard(
            modifier = Modifier.weight(1.3f),
            label = "AVG MILEAGE",
            value = if (analytics.avgMileageKmpl > 0) NumberFormatUtils.formatMileage(analytics.avgMileageKmpl, 2) else "--",
            unit = "km/L",
            color = Palette.Emerald500
        )

        // Total Distance
        FuelStatBadgeCard(
            modifier = Modifier.weight(1.25f),
            label = "TOTAL DISTANCE",
            value = if (analytics.totalDistanceKm > 0) NumberFormatUtils.formatDistance(analytics.totalDistanceKm, 2, useGrouping = true) else "--",
            unit = "km",
            color = Palette.Purple500
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
            .cockpitSurface(Palette.Ink900, Palette.Ink800, CopilotShapes.R10)
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
                    style = CockpitTheme.typography.headlineLarge.copy(
                        fontFeatureSettings = "tnum"
                    ),
                    color = color
                )
                if (unit.isNotBlank()) {
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = unit,
                        style = CockpitTheme.typography.labelSmall.copy(
                            fontFeatureSettings = "tnum"
                        ),
                        color = Palette.Steel400,
                        modifier = Modifier.padding(bottom = 1.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = label,
                style = CockpitTheme.typography.labelSmall.copy(
                    letterSpacing = 0.4.sp
                ),
                color = Palette.Steel400,
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
                        .cockpitSurface(Color(0xFF101726), Palette.Ink750, CircleShape, borderWidth = 1.2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.LocalGasStation,
                        contentDescription = null,
                        tint = Palette.Slate600,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Text(
                    text = "No Fuel Records in ${analytics.year}",
                    style = CockpitTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = Color.White
                )

                Text(
                    text = "Refuels recorded via '+ ADD FUEL' during ${analytics.year} will appear grouped here with live mileage and costs.",
                    style = CockpitTheme.typography.bodyMedium,
                    color = Palette.Steel400
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
                        .cockpitSurface(Color(0xFF0A0F1A), Color(0xFF1A2436), CopilotShapes.R14)
                ) {
                    // Month Header Accordion Bar (Bada touch target aur bada readable text)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 60.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = rememberRipple(color = Palette.Sky400),
                                onClick = { expandedMonths[monthKey] = !isExpanded }
                            )
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "${monthData.monthFullName} ${monthData.year}",
                                style = CockpitTheme.typography.headlineMedium.copy(
                                    fontWeight = FontWeight.Black
                                ),
                                color = Color.White
                            )

                            // Quick monthly summary pills
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                PillBadge(
                                    text = "${monthData.refillCount} refills",
                                    color = Palette.Slate400
                                )
                                PillBadge(
                                    text = NumberFormatUtils.formatFuelLitersWithUnit(monthData.totalLiters, 2),
                                    color = Palette.Sky400
                                )
                                PillBadge(
                                    text = NumberFormatUtils.formatCurrencyWithSymbol(monthData.totalCost, 2, useGrouping = true),
                                    color = Palette.Amber500
                                )
                                if (monthData.mileageKmpl > 0) {
                                    PillBadge(
                                        text = NumberFormatUtils.formatMileageWithUnit(monthData.mileageKmpl, 2),
                                        color = Palette.Emerald500
                                    )
                                }
                            }
                        }

                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = if (isExpanded) "Collapse" else "Expand",
                            tint = Palette.Steel400,
                            modifier = Modifier
                                .size(28.dp)
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
            .cockpitSurface(Color(0xFF0F1524), Palette.Ink750, CopilotShapes.R12)
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
                            .background(Palette.Sky600.copy(alpha = 0.2f))
                            .border(1.dp, Palette.Sky400.copy(alpha = 0.45f), RoundedCornerShape(5.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = when {
                                event.eventType == "UNLOGGED_CONSUMPTION" && event.notes.startsWith("RESOLVED") -> "✓ UNLOGGED (RESOLVED)"
                                event.eventType == "UNLOGGED_CONSUMPTION" -> "⚠ UNLOGGED (PROVISIONAL)"
                                event.eventType == "UNLOGGED_REFILL_AUTO" -> "⛽ UNLOGGED REFILL (AUTO)"
                                else -> "⛽ REFUEL"
                            },
                            style = CockpitTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Black,
                                fontFeatureSettings = "tnum"
                            ),
                            color = Palette.Sky400
                        )
                    }

                    // Mini 10-Pt MID Gauge Visualizer
                    if (event.midLevelObserved in 0..10) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier
                                .cockpitSurface(Palette.Ink850, Palette.Graphite700, RoundedCornerShape(5.dp))
                                .padding(horizontal = 5.dp, vertical = 3.dp)
                        ) {
                            for (i in 0 until 10) {
                                val isFilled = i < event.midLevelObserved
                                val barColor = when {
                                    isFilled && i < 2 -> Palette.Red500
                                    isFilled && i < 5 -> Palette.Amber500
                                    isFilled -> Palette.Sky400
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
                                style = CockpitTheme.typography.labelSmall.copy(
                                    fontFeatureSettings = "tnum"
                                ),
                                color = Palette.Slate300
                            )
                        }
                    }
                }

                Text(
                    text = dateStr,
                    style = CockpitTheme.typography.bodySmall.copy(
                        fontFeatureSettings = "tnum"
                    ),
                    color = Palette.Steel400
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
                            style = CockpitTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Black,
                                fontFeatureSettings = "tnum",
                                letterSpacing = (-0.5).sp
                            ),
                            color = Palette.Sky400
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "L",
                            style = CockpitTheme.typography.labelSmall.copy(
                                fontFeatureSettings = "tnum"
                            ),
                            color = Palette.Steel400,
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "@ ${NumberFormatUtils.formatCurrencyWithSymbol(event.pricePerLiter, 2)}/L",
                            style = CockpitTheme.typography.bodyMedium.copy(
                                fontFeatureSettings = "tnum"
                            ),
                            color = Palette.Steel400,
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                    }

                    if (event.locationName.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "📍", style = CockpitTheme.typography.bodySmall)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = event.locationName,
                                style = CockpitTheme.typography.bodySmall.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = Palette.Slate400,
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
                        style = CockpitTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Black,
                            fontFeatureSettings = "tnum"
                        ),
                        color = Palette.Amber500
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (event.odometerKm > 0) {
                            Box(
                                modifier = Modifier
                                    .cockpitSurface(Palette.Ink850, Palette.Graphite700, CopilotShapes.R6)
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = "🚗 ${NumberFormatUtils.formatOdometer(event.odometerKm, 2)} km",
                                    style = CockpitTheme.typography.labelSmall.copy(
                                        fontFeatureSettings = "tnum"
                                    ),
                                    color = Color.White
                                )
                            }
                        }

                        if (event.calculatedMileageKmpl > 0) {
                            Box(
                                modifier = Modifier
                                    .clip(CopilotShapes.R6)
                                    .background(Palette.Emerald500.copy(alpha = 0.15f))
                                    .border(1.dp, Palette.Emerald500.copy(alpha = 0.4f), CopilotShapes.R6)
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = "⚡ ${NumberFormatUtils.formatMileageWithUnit(event.calculatedMileageKmpl, 2)}",
                                    style = CockpitTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Black,
                                        fontFeatureSettings = "tnum"
                                    ),
                                    color = Palette.Emerald500
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
                            .size(48.dp)
                            .cockpitSurface(Color(0xFF0F2642), Palette.Sky400.copy(alpha = 0.55f), CircleShape, borderWidth = 1.2.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = rememberRipple(bounded = true, color = Palette.Sky400),
                                onClick = onEdit
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit Refuel",
                            tint = Palette.Sky400,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .cockpitSurface(Color(0xFF3B151E), Palette.Red500.copy(alpha = 0.55f), CircleShape, borderWidth = 1.2.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = rememberRipple(bounded = true, color = Palette.Red500),
                                onClick = onDelete
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Refuel",
                            tint = Palette.Red500,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}

// ==================== DEDICATED STATISTICS VIEW ====================

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
                .cockpitSurface(Color(0xFF0F182A), Color(0xFF2563EB), CopilotShapes.R22, borderWidth = 1.5.dp)
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
                            style = CockpitTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Black
                            ),
                            color = Color.White
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(36.dp).clip(CircleShape).background(Palette.Ink700)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }

                // Location field
                OutlinedTextField(
                    value = locationText,
                    onValueChange = { locationText = it },
                    label = { Text("Fuel Station / Location", color = CockpitTheme.colors.textSecondary) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = CockpitTheme.typography.titleMedium.copy(color = Color.White),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = CockpitTheme.colors.accent,
                        unfocusedBorderColor = Palette.Ink700
                    ),
                    shape = CopilotShapes.R12
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
                        label = { Text("Quantity (L)", color = CockpitTheme.colors.textSecondary) },
                        modifier = Modifier.weight(1f),
                        textStyle = CockpitTheme.typography.titleLarge.copy(color = Color.White, fontFeatureSettings = "tnum"),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = CockpitTheme.colors.accent,
                            unfocusedBorderColor = Palette.Ink700
                        ),
                        shape = CopilotShapes.R12
                    )

                    OutlinedTextField(
                        value = priceText,
                        onValueChange = {
                            priceText = it
                            val l = litersText.toDoubleOrNull() ?: 0.0
                            val p = it.toDoubleOrNull() ?: 0.0
                            costText = NumberFormatUtils.formatPrecision(l * p, 2)
                        },
                        label = { Text("Price (₹ / L)", color = CockpitTheme.colors.textSecondary) },
                        modifier = Modifier.weight(1f),
                        textStyle = CockpitTheme.typography.titleLarge.copy(color = Color.White, fontFeatureSettings = "tnum"),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = CockpitTheme.colors.accent,
                            unfocusedBorderColor = Palette.Ink700
                        ),
                        shape = CopilotShapes.R12
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = costText,
                        onValueChange = { costText = it },
                        label = { Text("Total Bill (₹)", color = CockpitTheme.colors.textSecondary) },
                        modifier = Modifier.weight(1f),
                        textStyle = CockpitTheme.typography.titleLarge.copy(color = Palette.Amber300, fontFeatureSettings = "tnum"),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Palette.Amber300,
                            unfocusedTextColor = Palette.Amber300,
                            focusedBorderColor = Palette.Amber300,
                            unfocusedBorderColor = Palette.Ink700
                        ),
                        shape = CopilotShapes.R12
                    )

                    OutlinedTextField(
                        value = odoText,
                        onValueChange = { odoText = it },
                        label = { Text("Odometer (km)", color = CockpitTheme.colors.textSecondary) },
                        modifier = Modifier.weight(1f),
                        textStyle = CockpitTheme.typography.titleLarge.copy(color = Color.White, fontFeatureSettings = "tnum"),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = CockpitTheme.colors.accent,
                            unfocusedBorderColor = Palette.Ink700
                        ),
                        shape = CopilotShapes.R12
                    )
                }

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = Color.White, style = CockpitTheme.typography.labelLarge)
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
                        shape = CopilotShapes.R12
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save Changes", style = CockpitTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
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
        if (it.eventType == "UNLOGGED_CONSUMPTION") return@filter false // consumption, not a purchase
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
private fun PillBadge(text: String, color: Color) = TintedBadge(
    text = text,
    color = color,
    size = BadgeSize.Regular,
    textStyle = CockpitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black, fontFeatureSettings = "tnum")
)
