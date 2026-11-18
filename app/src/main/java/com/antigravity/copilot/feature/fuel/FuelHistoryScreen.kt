package com.antigravity.copilot.feature.fuel

import com.antigravity.copilot.core.designsystem.components.RaahiYearDropdown
import com.antigravity.copilot.core.designsystem.components.currentMonthKey
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.outlined.Add

import com.antigravity.copilot.core.designsystem.components.RaahiButton

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.antigravity.copilot.core.designsystem.components.contentColorOn
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
    onDeleteEvent: (Long) -> Unit = {},
    fuelState: com.antigravity.copilot.core.model.FuelState? = null,
    onAddFuel: () -> Unit = {}
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
            // Raahi Fuel screen (same frame as Trips): header + yearly strip across the top, then
            // Tank + Gauge on the left and the month-grouped log on the right.
            val showSidePanel = fuelState != null && maxWidth >= 820.dp

          Column(
              modifier = Modifier
                  .fillMaxSize()
                  .padding(if (isCompact) 12.dp else 16.dp),
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
                      Box(
                          modifier = Modifier
                              .size(48.dp)
                              .cockpitSurface(CockpitTheme.colors.accentSoft, Palette.Sky400.copy(alpha = 0.5f), CopilotShapes.R12, borderWidth = 1.2.dp),
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
                                  text = "Fuel",
                                  style = CockpitTheme.typography.headlineLarge,
                                  color = CockpitTheme.colors.textPrimary
                              )
                          }

                          Text(
                              text = buildString {
                                  val n = yearlyAnalytics.totalRefills
                                  append("$n ${if (n == 1) "fill-up" else "fill-ups"} in $selectedYear")
                                  val price = fuelState?.fuelPricePerLiter ?: 0.0
                                  if (price > 0) append("  ·  Last price ₹${NumberFormatUtils.formatPrecision(price, 2)}/L")
                              },
                              style = CockpitTheme.typography.bodyMedium,
                              color = CockpitTheme.colors.textSecondary
                          )
                      }
                  }

                  // Top Action Controls: Year Selector, Close Button
                  Row(
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.spacedBy(10.dp)
                  ) {
                      // Year picker: dropdown showing three years at a time, the rest scroll.
                      RaahiYearDropdown(
                          years = availableYears,
                          selectedYear = selectedYear,
                          onYearSelected = { selectedYear = it }
                      )
                  }
              }


              // ==================== 2. YEARLY SUMMARY TILES ====================
              YearlySummaryBanner(
                  analytics = yearlyAnalytics,
                  isCompact = isCompact
              )

              Row(
                  modifier = Modifier.fillMaxWidth().weight(1f),
                  horizontalArrangement = Arrangement.spacedBy(12.dp)
              ) {
                  if (showSidePanel && fuelState != null) {
                      Column(
                          modifier = Modifier
                              .width(360.dp)
                              .fillMaxHeight()
                              .verticalScroll(rememberScrollState()),
                          verticalArrangement = Arrangement.spacedBy(12.dp)
                      ) {
                          FuelTankSummaryCard(fuelState, modifier = Modifier.fillMaxWidth())
                          FuelInsightCard(fuelEvents, modifier = Modifier.fillMaxWidth())
                      }
                  }
                  Column(
                      modifier = Modifier.weight(1f).fillMaxHeight(),
                      verticalArrangement = Arrangement.spacedBy(12.dp)
                  ) {
                      if (fuelState != null && !showSidePanel) {
                          FuelTankSummaryCard(fuelState, modifier = Modifier.fillMaxWidth())
                      }
                      // ==================== 3. CONTENT AREA ====================
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
        val isGauge = targetEvent.eventType == "MID_OBSERVED" || (targetEvent.liters <= 0.0 && targetEvent.totalCost <= 0.0)
        com.antigravity.copilot.core.designsystem.components.RaahiConfirmDialog(
            title = if (isGauge) "Delete this gauge reading?" else "Delete this fill-up?",
            message = if (isGauge) "The app will go back to its own fuel estimate."
            else "${NumberFormatUtils.formatFuelLiters(targetEvent.liters)} L for ${NumberFormatUtils.formatCurrencyWithSymbol(targetEvent.totalCost)}. Your mileage numbers will update.",
            confirmText = "Delete",
            destructive = true,
            icon = Icons.Default.Delete,
            onConfirm = {
                onDeleteEvent(targetEvent.id)
                eventToDelete = null
            },
            onDismiss = { eventToDelete = null }
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
            .cockpitSurface(CockpitTheme.colors.cardElevated, Palette.Ink750, CopilotShapes.R14, borderWidth = 1.2.dp)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FuelStatBadgeCard(
            modifier = Modifier.weight(1f),
            label = "Fill-ups",
            value = "${analytics.totalRefills}",
            unit = "",
            color = CockpitTheme.colors.textPrimary
        )
        FuelStatBadgeCard(
            modifier = Modifier.weight(1f),
            label = "Fuel bought",
            value = if (analytics.totalLiters > 0) NumberFormatUtils.formatFuelLiters(analytics.totalLiters, 0) else "--",
            unit = if (analytics.totalLiters > 0) "L" else "",
            color = CockpitTheme.colors.textPrimary
        )
        FuelStatBadgeCard(
            modifier = Modifier.weight(1.2f),
            label = "Spent",
            value = if (analytics.totalCost > 0) NumberFormatUtils.formatCurrencyWithSymbol(analytics.totalCost) else "--",
            unit = "",
            color = CockpitTheme.colors.textPrimary
        )
        FuelStatBadgeCard(
            modifier = Modifier.weight(1.1f),
            label = "Average mileage",
            value = if (analytics.avgMileageKmpl > 0) NumberFormatUtils.formatMileage(analytics.avgMileageKmpl) else "--",
            unit = if (analytics.avgMileageKmpl > 0) "km/L" else "",
            color = CockpitTheme.colors.textPrimary
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
            .defaultMinSize(minHeight = 72.dp)
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
                    style = CockpitTheme.typography.displaySmall.copy(
                        fontFeatureSettings = "tnum"
                    ),
                    color = color
                )
                if (unit.isNotBlank()) {
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = unit,
                        style = CockpitTheme.typography.labelLarge.copy(
                            fontFeatureSettings = "tnum"
                        ),
                        color = Palette.Steel400,
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = label,
                style = CockpitTheme.typography.labelMedium,
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
        analytics.monthlyStats.filter { it.events.isNotEmpty() }.sortedByDescending { it.year * 12 + it.monthIndex }
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
                        .cockpitSurface(CockpitTheme.colors.border, Palette.Ink750, CircleShape, borderWidth = 1.2.dp),
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
                    text = "No fill-ups yet in ${analytics.year}",
                    style = CockpitTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = CockpitTheme.colors.textPrimary
                )

                Text(
                    text = "Add a fill-up from the Fuel card on the Drive screen. Your fill-ups, costs and mileage will show here.",
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
                // Only the current month starts open; older/future months start collapsed.
                val isExpanded = expandedMonths.getOrPut(monthKey) { monthKey == currentMonthKey() }
                val arrowRotation by animateFloatAsState(
                    targetValue = if (isExpanded) 180f else 0f,
                    animationSpec = tween(250),
                    label = "arrowRotation"
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .cockpitSurface(CockpitTheme.colors.cardElevated, CockpitTheme.colors.border, CopilotShapes.R14)
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
                                style = CockpitTheme.typography.headlineMedium,
                                color = CockpitTheme.colors.textPrimary
                            )

                            Text(
                                text = buildString {
                                    val n = monthData.refillCount
                                    append("$n ${if (n == 1) "fill-up" else "fill-ups"}")
                                    if (monthData.totalLiters > 0) append("  ·  ${NumberFormatUtils.formatFuelLitersWithUnit(monthData.totalLiters)}")
                                    if (monthData.totalCost > 0) append("  ·  ${NumberFormatUtils.formatCurrencyWithSymbol(monthData.totalCost)}")
                                    if (monthData.mileageKmpl > 0) append("  ·  ${NumberFormatUtils.formatMileageWithUnit(monthData.mileageKmpl)}")
                                },
                                style = CockpitTheme.typography.bodyLarge,
                                color = CockpitTheme.colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
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
    val c = CockpitTheme.colors
    val dateStr = remember(event.timestamp) {
        SimpleDateFormat("d MMM · h:mm a", Locale.getDefault()).format(Date(event.timestamp))
    }
    // F1: a gauge reading is not a refuel; it has no litres or bill to show or edit.
    val isGaugeUpdate = event.eventType == "MID_OBSERVED" || (event.liters <= 0.0 && event.midLevelObserved in 0..10 && event.totalCost <= 0.0)
    val isAuto = event.eventType == "UNLOGGED_REFILL_AUTO" || event.eventType == "UNLOGGED_CONSUMPTION"
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CopilotShapes.R12)
            .background(c.card)
            .border(1.dp, c.border, CopilotShapes.R12)
            .then(if (!isGaugeUpdate) Modifier.clickable(onClickLabel = "Edit fill-up", onClick = onEdit) else Modifier)
            .padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(c.cardElevated),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isGaugeUpdate) Icons.Default.Speed else Icons.Default.LocalGasStation,
                contentDescription = null,
                tint = c.textSecondary,
                modifier = Modifier.size(24.dp)
            )
        }

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (isGaugeUpdate) {
                Text("Gauge updated", style = CockpitTheme.typography.titleMedium, color = c.textPrimary)
                Text(
                    "${event.midLevelObserved} of 10 bars  ·  $dateStr",
                    style = CockpitTheme.typography.bodyMedium,
                    color = c.textSecondary
                )
            } else {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = NumberFormatUtils.formatFuelLiters(event.liters),
                        style = CockpitTheme.typography.displaySmall,
                        color = c.textPrimary
                    )
                    Text("L", style = CockpitTheme.typography.titleMedium, color = c.textSecondary, modifier = Modifier.padding(bottom = 4.dp))
                    if (event.pricePerLiter > 0) {
                        Text(
                            "at ₹${NumberFormatUtils.formatPrecision(event.pricePerLiter, 2)}/L",
                            style = CockpitTheme.typography.bodyMedium,
                            color = c.textSecondary,
                            modifier = Modifier.padding(bottom = 4.dp, start = 4.dp)
                        )
                    }
                    if (isAuto) {
                        com.antigravity.copilot.core.designsystem.components.RaahiPill(
                            text = if (event.eventType == "UNLOGGED_REFILL_AUTO") "Added by app" else "Estimated",
                            color = c.warning,
                            filled = true
                        )
                    }
                }
                Text(
                    text = buildString {
                        append(if (event.locationName.isNotBlank()) event.locationName else "Fill-up")
                        append("  ·  ")
                        append(dateStr)
                    },
                    style = CockpitTheme.typography.bodyMedium,
                    color = c.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (!isGaugeUpdate) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = NumberFormatUtils.formatCurrencyWithSymbol(event.totalCost),
                    style = CockpitTheme.typography.headlineLarge,
                    color = c.textPrimary
                )
                Text(
                    text = buildString {
                        if (event.calculatedMileageKmpl > 0) append("${NumberFormatUtils.formatMileage(event.calculatedMileageKmpl)} km/L")
                        if (event.odometerKm > 0) {
                            if (isNotEmpty()) append("  ·  ")
                            append("${NumberFormatUtils.formatOdometer(event.odometerKm)} km")
                        }
                    },
                    style = CockpitTheme.typography.bodyMedium,
                    color = c.textSecondary,
                    maxLines = 1
                )
            }
        }

        // F3: edit/delete live in one overflow menu instead of two big buttons on every row.
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(52.dp)) {
                Icon(Icons.Default.MoreVert, contentDescription = "More actions", tint = c.textSecondary, modifier = Modifier.size(26.dp))
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                modifier = Modifier.background(c.cardElevated)
            ) {
                if (!isGaugeUpdate) {
                    DropdownMenuItem(
                        text = { Text("Edit", style = CockpitTheme.typography.titleMedium, color = c.textPrimary) },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = c.textSecondary) },
                        onClick = { menuOpen = false; onEdit() },
                        modifier = Modifier.heightIn(min = 56.dp)
                    )
                }
                DropdownMenuItem(
                    text = { Text("Delete", style = CockpitTheme.typography.titleMedium, color = c.criticalText) },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = c.critical) },
                    onClick = { menuOpen = false; onDelete() },
                    modifier = Modifier.heightIn(min = 56.dp)
                )
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
                .cockpitSurface(CockpitTheme.colors.border, CockpitTheme.colors.accent, CopilotShapes.R22, borderWidth = 1.5.dp)
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
                            tint = CockpitTheme.colors.accent,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Edit fill-up",
                            style = CockpitTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Black
                            ),
                            color = CockpitTheme.colors.textPrimary
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(52.dp).clip(CircleShape).background(Palette.Ink700)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = CockpitTheme.colors.textPrimary, modifier = Modifier.size(26.dp))
                    }
                }

                // Location field
                OutlinedTextField(
                    value = locationText,
                    onValueChange = { locationText = it },
                    label = { Text("Petrol pump", color = CockpitTheme.colors.textSecondary) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = CockpitTheme.typography.titleMedium.copy(color = CockpitTheme.colors.textPrimary),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = CockpitTheme.colors.textPrimary,
                        unfocusedTextColor = CockpitTheme.colors.textPrimary,
                        focusedBorderColor = CockpitTheme.colors.accent,
                        unfocusedBorderColor = CockpitTheme.colors.textSecondary.copy(alpha = 0.6f)
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
                        label = { Text("Litres", color = CockpitTheme.colors.textSecondary) },
                        modifier = Modifier.weight(1f),
                        textStyle = CockpitTheme.typography.titleLarge.copy(color = CockpitTheme.colors.textPrimary, fontFeatureSettings = "tnum"),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = CockpitTheme.colors.textPrimary,
                            unfocusedTextColor = CockpitTheme.colors.textPrimary,
                            focusedBorderColor = CockpitTheme.colors.accent,
                            unfocusedBorderColor = CockpitTheme.colors.textSecondary.copy(alpha = 0.6f)
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
                        label = { Text("Price per litre (₹)", color = CockpitTheme.colors.textSecondary) },
                        modifier = Modifier.weight(1f),
                        textStyle = CockpitTheme.typography.titleLarge.copy(color = CockpitTheme.colors.textPrimary, fontFeatureSettings = "tnum"),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = CockpitTheme.colors.textPrimary,
                            unfocusedTextColor = CockpitTheme.colors.textPrimary,
                            focusedBorderColor = CockpitTheme.colors.accent,
                            unfocusedBorderColor = CockpitTheme.colors.textSecondary.copy(alpha = 0.6f)
                        ),
                        shape = CopilotShapes.R12
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = costText,
                        onValueChange = { costText = it },
                        label = { Text("Total paid (₹) · auto", color = CockpitTheme.colors.textSecondary) },
                        modifier = Modifier.weight(1f),
                        textStyle = CockpitTheme.typography.titleLarge.copy(color = CockpitTheme.colors.textPrimary, fontFeatureSettings = "tnum"),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = CockpitTheme.colors.textPrimary,
                            unfocusedTextColor = CockpitTheme.colors.textPrimary,
                            focusedBorderColor = CockpitTheme.colors.accent,
                            unfocusedBorderColor = CockpitTheme.colors.textSecondary.copy(alpha = 0.6f)
                        ),
                        shape = CopilotShapes.R12
                    )

                    OutlinedTextField(
                        value = odoText,
                        onValueChange = { odoText = it },
                        label = { Text("Odometer (km)", color = CockpitTheme.colors.textSecondary) },
                        modifier = Modifier.weight(1f),
                        textStyle = CockpitTheme.typography.titleLarge.copy(color = CockpitTheme.colors.textPrimary, fontFeatureSettings = "tnum"),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = CockpitTheme.colors.textPrimary,
                            unfocusedTextColor = CockpitTheme.colors.textPrimary,
                            focusedBorderColor = CockpitTheme.colors.accent,
                            unfocusedBorderColor = CockpitTheme.colors.textSecondary.copy(alpha = 0.6f)
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
                        Text("Cancel", color = CockpitTheme.colors.textPrimary, style = CockpitTheme.typography.labelLarge)
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
                        colors = ButtonDefaults.buttonColors(containerColor = CockpitTheme.colors.overlapBlue),
                        shape = CopilotShapes.R12
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save Changes", style = CockpitTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = contentColorOn(CockpitTheme.colors.overlapBlue))
                    }
                }
            }
        }
    }
}

// ==================== CALCULATION & DATA AGGREGATION ENGINE ====================

/** Upper bound for any car's km/L; a refuel step implying more is an odometer recalibration. */
internal const val MAX_PLAUSIBLE_KMPL = 45.0
private const val MIN_FALLBACK_DISTANCE_KM = 20.0

/**
 * Distance between refuels from their odometer readings, summed step by step. A step longer than
 * [MAX_PLAUSIBLE_KMPL] x the litres put in at its end cannot have been driven on that fuel: it is
 * an odometer recalibration (e.g. the first real reading) and is skipped.
 */
internal fun plausibleOdometerDistanceKm(chron: List<FuelEventEntity>): Double {
    var total = 0.0
    var prevOdo = -1.0
    for (e in chron) {
        if (e.odometerKm <= 0.0) continue
        if (prevOdo > 0.0) {
            val step = e.odometerKm - prevOdo
            if (step > 0.0 && step <= MAX_PLAUSIBLE_KMPL * maxOf(e.liters, 1.0)) total += step
        }
        prevOdo = e.odometerKm
    }
    return total
}

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

    // Year distance: sum of refuel-to-refuel odometer steps, skipping impossible steps
    // (odometer recalibrations), so a calibration jump never reads as distance driven.
    val totalDistanceKm = plausibleOdometerDistanceKm(chronEvents)

    // Average Mileage calculation: prioritize verified Fuel Engine interval calculations
    val validMileages = yearEvents.map { it.calculatedMileageKmpl }.filter { it in 5.0..45.0 }
    val avgMileageKmpl = if (validMileages.isNotEmpty()) {
        validMileages.average()
    } else if (totalDistanceKm >= MIN_FALLBACK_DISTANCE_KM && totalLiters > 0 && totalRefills >= 2) {
        val consumedLiters = chronEvents.drop(1).sumOf { it.liters }
        val m = if (consumedLiters > 0) totalDistanceKm / consumedLiters else totalDistanceKm / totalLiters
        m.takeIf { it in 5.0..MAX_PLAUSIBLE_KMPL } ?: 0.0
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
        val mDistance = plausibleOdometerDistanceKm(mChron)

        val mValidMileages = monthEvents.map { it.calculatedMileageKmpl }.filter { it in 5.0..45.0 }
        val mMileage = if (mValidMileages.isNotEmpty()) {
            mValidMileages.average()
        } else if (mDistance >= MIN_FALLBACK_DISTANCE_KM && mLiters > 0 && mRefills >= 2) {
            val mConsumed = mChron.drop(1).sumOf { it.liters }
            val m = if (mConsumed > 0) mDistance / mConsumed else mDistance / mLiters
            m.takeIf { it in 5.0..MAX_PLAUSIBLE_KMPL } ?: 0.0
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
