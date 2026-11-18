package com.antigravity.copilot.feature.trips

import com.antigravity.copilot.core.designsystem.components.cockpitBackground
import com.antigravity.copilot.domain.trip.*
import com.antigravity.copilot.core.designsystem.components.cockpitSurface
import com.antigravity.copilot.core.designsystem.components.TintedBadge
import com.antigravity.copilot.core.designsystem.components.BadgeSize
import com.antigravity.copilot.core.designsystem.tokens.Palette
import com.antigravity.copilot.core.designsystem.tokens.CopilotShapes
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.copilot.core.designsystem.AutomotiveDesignTokens
import com.antigravity.copilot.core.database.TripEntity
import com.antigravity.copilot.core.database.VehicleStateTransitionEntity
import com.antigravity.copilot.core.database.FuelLedgerEntity
import com.antigravity.copilot.core.util.NumberFormatUtils
import com.antigravity.copilot.core.database.TripPointEntity
import com.antigravity.copilot.core.designsystem.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max

@Composable
fun TripHistoryScreen(
    trips: List<TripEntity>,
    onLoadTripPoints: suspend (Long) -> List<TripPointEntity>,
    onLoadTripTransitions: (suspend (Long, Long, Long) -> List<VehicleStateTransitionEntity>)? = null,
    onLoadTripFuelEvents: (suspend (Long, Long) -> List<FuelLedgerEntity>)? = null,
    onBackToCockpit: () -> Unit,
    onDeleteTrip: ((Long) -> Unit)? = null,
    fuelPrice: Double = 96.5,
    modifier: Modifier = Modifier
) {
    val currentCalYear = remember { Calendar.getInstance().get(Calendar.YEAR) }

    // Identify all calendar years in database containing trip records
    val availableYears = remember(trips) {
        val cal = Calendar.getInstance()
        val yearsInDb = trips.map {
            cal.timeInMillis = it.startTime
            cal.get(Calendar.YEAR)
        }.distinct()
        if (yearsInDb.isEmpty()) listOf(currentCalYear)
        else (yearsInDb + currentCalYear).distinct().sortedDescending()
    }

    var selectedYear by remember(availableYears) {
        mutableStateOf(if (availableYears.contains(currentCalYear)) currentCalYear else availableYears.first())
    }

    // Selected Trip state
    var selectedTrip by remember { mutableStateOf<TripEntity?>(null) }
    var selectedDetailTab by remember { mutableStateOf(TripDetailTab.MAP_REPLAY) }
    var showMobileDetailDialog by remember { mutableStateOf(false) }

    BackHandler {
        if (showMobileDetailDialog) {
            showMobileDetailDialog = false
        } else {
            onBackToCockpit()
        }
    }

    // Yearly charts (monthly distance + weekday pattern), collapsed by default to keep the list tall.
    var showYearInsights by remember { mutableStateOf(false) }

    // Track expanded monthly groups
    val expandedMonths = remember { mutableStateMapOf<String, Boolean>() }

    // Points for active selected trip
    var tripPoints by remember { mutableStateOf<List<TripPointEntity>>(emptyList()) }
    var tripTransitions by remember { mutableStateOf<List<VehicleStateTransitionEntity>>(emptyList()) }
    var tripFuelEvents by remember { mutableStateOf<List<FuelLedgerEntity>>(emptyList()) }
    var isPointsLoading by remember { mutableStateOf(false) }

    // Playback state
    var playbackStatus by remember { mutableStateOf(PlaybackStatus.STOPPED) }
    var currentIndex by remember { mutableStateOf(0) }
    var playbackSpeedMultiplier by remember { mutableStateOf(5f) } // 1x, 2x, 5x, 10x, 20x

    // Analytics for selected year
    val yearlyAnalytics = remember(trips, selectedYear, fuelPrice) {
        computeYearlyTripAnalytics(trips, selectedYear, fuelPrice)
    }

    // Auto-select latest trip if none selected
    LaunchedEffect(trips, selectedYear) {
        val currentYearTrips = trips.filter {
            val cal = Calendar.getInstance().apply { timeInMillis = it.startTime }
            cal.get(Calendar.YEAR) == selectedYear
        }
        if (selectedTrip == null || !currentYearTrips.any { it.id == selectedTrip?.id }) {
            selectedTrip = currentYearTrips.firstOrNull()
        }
    }

    // Load points and journey events when selected trip changes
    LaunchedEffect(selectedTrip?.id) {
        val current = selectedTrip
        if (current != null) {
            isPointsLoading = true
            tripPoints = onLoadTripPoints(current.id)
            val startTime = current.startTime
            val endTime = current.endTime.coerceAtLeast(startTime)
            tripTransitions = onLoadTripTransitions?.invoke(current.id, startTime, endTime) ?: emptyList()
            tripFuelEvents = onLoadTripFuelEvents?.invoke(startTime, endTime) ?: emptyList()
            currentIndex = 0
            playbackStatus = PlaybackStatus.STOPPED
            isPointsLoading = false
        } else {
            tripPoints = emptyList()
            tripTransitions = emptyList()
            tripFuelEvents = emptyList()
            currentIndex = 0
            playbackStatus = PlaybackStatus.STOPPED
        }
    }

    // Coroutine for animated route replay
    LaunchedEffect(playbackStatus, playbackSpeedMultiplier, tripPoints.size) {
        if (playbackStatus == PlaybackStatus.PLAYING && tripPoints.size > 1) {
            // Calculate delay between steps based on speed multiplier
            val baseDelayMs = (400L / playbackSpeedMultiplier).toLong().coerceIn(16L, 200L)
            while (isActive && playbackStatus == PlaybackStatus.PLAYING && currentIndex < tripPoints.size - 1) {
                delay(baseDelayMs)
                currentIndex++
            }
            if (currentIndex >= tripPoints.size - 1) {
                playbackStatus = PlaybackStatus.COMPLETED
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .cockpitBackground()
            .padding(14.dp)
    ) {
        val isCompactWidth = maxWidth < 760.dp

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ==================== 1. COCKPIT HEADER BAR ====================
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Back & Title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .cockpitSurface(CockpitTheme.colors.accentSoft, Palette.Sky400.copy(alpha = 0.5f), CopilotShapes.R12, borderWidth = 1.2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Route,
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
                                text = "Trip Records",
                                style = CockpitTheme.typography.headlineLarge,
                                color = CockpitTheme.colors.textPrimary
                            )

                            // Year Pill
                            Box(
                                modifier = Modifier
                                    .cockpitSurface(CockpitTheme.colors.border, Palette.Sky400.copy(alpha = 0.35f), CopilotShapes.R6)
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = "$selectedYear",
                                    style = CockpitTheme.typography.labelSmall.copy(
                                        fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                                    ),
                                    color = Palette.Sky400
                                )
                            }
                        }

                        Text(
                            text = "${yearlyAnalytics.totalTrips} trips in $selectedYear  •  ${NumberFormatUtils.formatPrecision(yearlyAnalytics.totalDistanceKm, 0)} km driven",
                            style = CockpitTheme.typography.bodyMedium,
                            color = CockpitTheme.colors.textSecondary
                        )
                    }
                }

                // Controls: Year Selection Chips
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Year Selector
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        items(availableYears) { year ->
                            val isSelected = year == selectedYear
                            Box(
                                modifier = Modifier
                                    .height(44.dp)
                                    .clip(CopilotShapes.R12)
                                    .background(if (isSelected) Palette.Ink600 else Palette.Ink900)
                                    .border(
                                        width = 1.2.dp,
                                        color = if (isSelected) Palette.Sky400 else Palette.Ink750,
                                        shape = CopilotShapes.R12
                                    )
                                    .clickable { selectedYear = year }
                                    .padding(horizontal = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "$year",
                                    style = CockpitTheme.typography.labelMedium.copy(
                                        fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                                        fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                                    ),
                                    color = if (isSelected) CockpitTheme.colors.textPrimary else Palette.Steel400
                                )
                            }
                        }
                    }
                }
            }

            // ==================== 2. YEARLY SUMMARY BANNER ====================
            YearlyTripSummaryBanner(
                analytics = yearlyAnalytics,
                insightsExpanded = showYearInsights,
                onToggleInsights = { showYearInsights = !showYearInsights }
            )
            if (showYearInsights) {
                YearlyTripInsightsRow(analytics = yearlyAnalytics)
            }

            // ==================== 3. CONTENT AREA ====================
            // Journeys Feed + Split Interactive Detail View
            Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Left Column: Grouped Trip Feed (~40%)
                    TripListGroupedFeed(
                        analytics = yearlyAnalytics,
                        selectedTrip = selectedTrip,
                        expandedMonths = expandedMonths,
                        onSelectTrip = { trip ->
                            selectedTrip = trip
                            if (isCompactWidth) {
                                showMobileDetailDialog = true
                            }
                        },
                        modifier = Modifier
                            .weight(if (isCompactWidth) 1f else 0.34f)
                            .fillMaxHeight()
                    )

                    // Right Column: Trip Detail Container (~60%)
                    if (!isCompactWidth) {
                        TripDetailContainer(
                            trip = selectedTrip,
                            points = tripPoints,
                            transitions = tripTransitions,
                            fuelEvents = tripFuelEvents,
                            isPointsLoading = isPointsLoading,
                            selectedTab = selectedDetailTab,
                            onSelectTab = { selectedDetailTab = it },
                            fuelPricePerLiter = fuelPrice,
                            playbackStatus = playbackStatus,
                            currentIndex = currentIndex,
                            playbackSpeed = playbackSpeedMultiplier,
                            onPlayPause = {
                                playbackStatus = when (playbackStatus) {
                                    PlaybackStatus.PLAYING -> PlaybackStatus.PAUSED
                                    PlaybackStatus.PAUSED -> PlaybackStatus.PLAYING
                                    PlaybackStatus.STOPPED, PlaybackStatus.COMPLETED -> {
                                        currentIndex = 0
                                        PlaybackStatus.PLAYING
                                    }
                                }
                            },
                            onRestart = {
                                currentIndex = 0
                                playbackStatus = PlaybackStatus.PLAYING
                            },
                            onSeek = { newIndex ->
                                currentIndex = newIndex.coerceIn(0, (tripPoints.size - 1).coerceAtLeast(0))
                                if (playbackStatus == PlaybackStatus.COMPLETED && currentIndex < tripPoints.size - 1) {
                                    playbackStatus = PlaybackStatus.PAUSED
                                }
                            },
                            onCycleSpeed = {
                                playbackSpeedMultiplier = when (playbackSpeedMultiplier) {
                                    1f -> 2f
                                    2f -> 5f
                                    5f -> 10f
                                    10f -> 20f
                                    else -> 1f
                                }
                            },
                            onDeleteTrip = { id ->
                                onDeleteTrip?.invoke(id)
                                selectedTrip = null
                            },
                            modifier = Modifier
                                .weight(0.66f)
                                .fillMaxHeight()
                        )
                    }
                }
            }

        // Mobile Fullscreen Detail Dialog for Compact Width (< 760dp)
        if (isCompactWidth && showMobileDetailDialog && selectedTrip != null) {
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { showMobileDetailDialog = false },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(CockpitTheme.colors.cardElevated)
                        .padding(12.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Trip Details",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Black,
                                    color = CockpitTheme.colors.textPrimary
                                )
                            )
                            IconButton(
                                onClick = { showMobileDetailDialog = false },
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Palette.Ink850)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = CockpitTheme.colors.textPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        TripDetailContainer(
                            trip = selectedTrip,
                            points = tripPoints,
                            transitions = tripTransitions,
                            fuelEvents = tripFuelEvents,
                            isPointsLoading = isPointsLoading,
                            selectedTab = selectedDetailTab,
                            onSelectTab = { selectedDetailTab = it },
                            fuelPricePerLiter = fuelPrice,
                            playbackStatus = playbackStatus,
                            currentIndex = currentIndex,
                            playbackSpeed = playbackSpeedMultiplier,
                            onPlayPause = {
                                playbackStatus = when (playbackStatus) {
                                    PlaybackStatus.PLAYING -> PlaybackStatus.PAUSED
                                    PlaybackStatus.PAUSED -> PlaybackStatus.PLAYING
                                    PlaybackStatus.STOPPED, PlaybackStatus.COMPLETED -> {
                                        currentIndex = 0
                                        PlaybackStatus.PLAYING
                                    }
                                }
                            },
                            onRestart = {
                                currentIndex = 0
                                playbackStatus = PlaybackStatus.PLAYING
                            },
                            onSeek = { newIndex ->
                                currentIndex = newIndex.coerceIn(0, (tripPoints.size - 1).coerceAtLeast(0))
                                if (playbackStatus == PlaybackStatus.COMPLETED && currentIndex < tripPoints.size - 1) {
                                    playbackStatus = PlaybackStatus.PAUSED
                                }
                            },
                            onCycleSpeed = {
                                playbackSpeedMultiplier = when (playbackSpeedMultiplier) {
                                    1f -> 2f
                                    2f -> 5f
                                    5f -> 10f
                                    10f -> 20f
                                    else -> 1f
                                }
                            },
                            onDeleteTrip = { id ->
                                onDeleteTrip?.invoke(id)
                                selectedTrip = null
                                showMobileDetailDialog = false
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun YearlyTripSummaryBanner(
    analytics: YearlyTripAnalytics,
    insightsExpanded: Boolean = false,
    onToggleInsights: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .cockpitSurface(CockpitTheme.colors.cardElevated, Palette.Ink750, CopilotShapes.R14, borderWidth = 1.2.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TripStatTile(
            modifier = Modifier.weight(1f),
            label = "TOTAL TRIPS",
            value = "${analytics.totalTrips}",
            unit = "Trips",
            color = CockpitTheme.colors.textPrimary
        )
        TripStatTile(
            modifier = Modifier.weight(1.2f),
            label = "TOTAL DISTANCE",
            value = NumberFormatUtils.formatDistance(analytics.totalDistanceKm, 2, useGrouping = true),
            unit = "km",
            color = Palette.Sky400
        )
        TripStatTile(
            modifier = Modifier.weight(1.2f),
            label = "DRIVING TIME",
            value = formatDurationHoursMinutes(analytics.totalDrivingDurationMs),
            unit = "",
            color = Palette.Amber500
        )
        TripStatTile(
            modifier = Modifier.weight(1.15f),
            label = "AVG SPEED",
            value = if (analytics.avgSpeedKmh > 0) NumberFormatUtils.formatSpeed(analytics.avgSpeedKmh, 2) else "--",
            unit = "km/h",
            color = Palette.Emerald500
        )
        TripStatTile(
            modifier = Modifier.weight(1.15f),
            label = "MAX SPEED",
            value = if (analytics.maxSpeedKmh > 0) NumberFormatUtils.formatSpeed(analytics.maxSpeedKmh, 2) else "--",
            unit = "km/h",
            color = Palette.Purple500
        )
        TripStatTile(
            modifier = Modifier.weight(1.1f),
            label = "IDLE TIME",
            value = formatDurationHoursMinutes(analytics.totalIdleDurationMs),
            unit = "",
            color = Palette.Slate400
        )
        if (onToggleInsights != null) {
            Box(
                modifier = Modifier
                    .size(62.dp)
                    .cockpitSurface(
                        if (insightsExpanded) Palette.Ink600 else Palette.Ink900,
                        if (insightsExpanded) Palette.Sky400 else Palette.Ink800,
                        CopilotShapes.R10
                    )
                    .clickable(onClick = onToggleInsights),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.BarChart,
                        contentDescription = if (insightsExpanded) "Hide yearly charts" else "Show yearly charts",
                        tint = if (insightsExpanded) Palette.Sky400 else Palette.Steel400,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = "CHARTS",
                        style = CockpitTheme.typography.labelSmall,
                        color = if (insightsExpanded) Palette.Sky400 else Palette.Steel400
                    )
                }
            }
        }
    }
}

/** Monthly distance and weekday driving-pattern charts for the selected year. */
@Composable
internal fun YearlyTripInsightsRow(analytics: YearlyTripAnalytics) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(170.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1.4f)
                .fillMaxHeight()
                .cockpitSurface(CockpitTheme.colors.cardElevated, Palette.Ink750, CopilotShapes.R14, borderWidth = 1.2.dp)
                .padding(10.dp)
        ) {
            Text(
                text = "DISTANCE BY MONTH • ${analytics.year}",
                style = CockpitTheme.typography.labelSmall,
                color = Palette.Steel400
            )
            MonthlyDistanceCanvas(
                monthlyGroups = analytics.monthlyGroups,
                modifier = Modifier.fillMaxSize()
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .cockpitSurface(CockpitTheme.colors.cardElevated, Palette.Ink750, CopilotShapes.R14, borderWidth = 1.2.dp)
                .padding(10.dp)
        ) {
            Text(
                text = "TRIPS BY WEEKDAY",
                style = CockpitTheme.typography.labelSmall,
                color = Palette.Steel400
            )
            WeeklyDrivingPatternCanvas(
                distribution = analytics.weeklyDistribution,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
internal fun TripStatTile(
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
                        fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum",
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    ),
                    color = color
                )
                if (unit.isNotBlank()) {
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = unit,
                        style = CockpitTheme.typography.labelSmall.copy(
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum",
                            platformStyle = PlatformTextStyle(includeFontPadding = false)
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
                    letterSpacing = 0.4.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                ),
                color = Palette.Steel400,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun TripListGroupedFeed(
    analytics: YearlyTripAnalytics,
    selectedTrip: TripEntity?,
    expandedMonths: MutableMap<String, Boolean>,
    onSelectTrip: (TripEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val activeMonthGroups = remember(analytics) {
        analytics.monthlyGroups.filter { it.trips.isNotEmpty() }
    }

    if (activeMonthGroups.isEmpty()) {
        Box(
            modifier = modifier
                .cockpitSurface(CockpitTheme.colors.cardElevated, Palette.Ink750, CopilotShapes.R16, borderWidth = 1.2.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(20.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .cockpitSurface(CockpitTheme.colors.border, Palette.Ink750, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.DirectionsCar,
                        contentDescription = null,
                        tint = Palette.Slate600,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Text(
                    text = "No Trips in ${analytics.year}",
                    style = CockpitTheme.typography.titleMedium,
                    color = CockpitTheme.colors.textPrimary
                )
                Text(
                    text = "Drive to record telemetry trips with high-precision GPS playback.",
                    style = CockpitTheme.typography.bodySmall,
                    color = Palette.Steel400
                )
            }
        }
    } else {
        // Flattened: one lazy item per month header and per trip row, so only the rows on screen
        // are composed (a busy month no longer composes hundreds of cards at once).
        LazyColumn(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            activeMonthGroups.forEach { group ->
                val monthKey = "${group.year}-${group.monthIndex}"
                val isExpanded = expandedMonths[monthKey] ?: true
                item(key = "month-$monthKey", contentType = "month") {
                    TripMonthHeader(
                        group = group,
                        isExpanded = isExpanded,
                        onToggle = { expandedMonths[monthKey] = !isExpanded }
                    )
                }
                if (isExpanded) {
                    items(group.trips, key = { "trip-${it.id}" }, contentType = { "trip" }) { trip ->
                        TripCardItem(
                            trip = trip,
                            isSelected = selectedTrip?.id == trip.id,
                            onClick = { onSelectTrip(trip) },
                            modifier = Modifier.padding(horizontal = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TripMonthHeader(
    group: MonthlyTripGroup,
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    val arrowRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(200),
        label = "arrow"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .cockpitSurface(CockpitTheme.colors.cardElevated, CockpitTheme.colors.border, CopilotShapes.R14)
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "${group.monthName} ${group.year}",
                style = CockpitTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Black
                ),
                color = CockpitTheme.colors.textPrimary
            )
            PillBadge(
                text = "${group.trips.size} trips",
                color = Palette.Slate400
            )
            PillBadge(
                text = NumberFormatUtils.formatDistanceWithUnit(group.totalDistanceKm, 2),
                color = Palette.Sky400
            )
        }

        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = if (isExpanded) "Collapse" else "Expand",
            tint = Palette.Steel400,
            modifier = Modifier
                .size(18.dp)
                .rotate(arrowRotation)
        )
    }
}

@Composable
internal fun TripCardItem(
    trip: TripEntity,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Formatters are costly to build; create them once per row, not on every recomposition.
    val timeFormat = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }
    val dateFormat = remember { SimpleDateFormat("dd MMM", Locale.getDefault()) }
    val dayKeyFormat = remember { SimpleDateFormat("yyyyMMdd", Locale.US) }
    val startStr = timeFormat.format(Date(trip.startTime))
    val endStr = if (trip.endTime > trip.startTime) timeFormat.format(Date(trip.endTime)) else ""
    val dateStr = dateFormat.format(Date(trip.startTime))

    val distanceKm = NumberFormatUtils.formatDistance(trip.distanceMeters / 1000.0, 2)
    val wallClockDurationMs = if (trip.endTime > trip.startTime) (trip.endTime - trip.startTime) else (trip.drivingDurationMs + trip.stationaryDurationMs)
    val activeDurationMs = trip.drivingDurationMs + trip.stationaryDurationMs
    val isMultiDay = (trip.endTime > trip.startTime) && (
        wallClockDurationMs >= 86_400_000L ||
        dayKeyFormat.format(Date(trip.startTime)) != dayKeyFormat.format(Date(trip.endTime))
    )
    val durationText = if (isMultiDay) {
        val wallText = com.antigravity.copilot.core.trip.TimeFormatUtils.formatAdaptiveDuration(wallClockDurationMs)
        val activeText = com.antigravity.copilot.core.trip.TimeFormatUtils.formatAdaptiveDuration(activeDurationMs)
        "$wallText ($activeText active)"
    } else {
        com.antigravity.copilot.core.trip.TimeFormatUtils.formatAdaptiveDuration(activeDurationMs)
    }
    val dateDisplayStr = if (isMultiDay) {
        "${dateFormat.format(Date(trip.startTime))} $startStr -> ${dateFormat.format(Date(trip.endTime))} $endStr"
    } else {
        "$dateStr • $startStr${if (endStr.isNotBlank()) " - $endStr" else ""}"
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(CopilotShapes.R12)
            .background(if (isSelected) CockpitTheme.colors.accentSoft else Palette.Ink925)
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) Palette.Sky400 else Palette.Ink750,
                shape = CopilotShapes.R12
            )
            .clickable { onClick() }
            .padding(10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            // Origin ➔ Destination and Distance
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    val title = tripTitle(trip, LocalTripViaNames.current[trip.id])
                    Text(
                        text = title.title,
                        style = CockpitTheme.typography.titleSmall,
                        color = CockpitTheme.colors.textPrimary,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Text(
                        text = dateDisplayStr,
                        style = CockpitTheme.typography.bodySmall.copy(
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                        ),
                        color = Palette.Steel400
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                Text(
                    text = "$distanceKm km",
                    style = CockpitTheme.typography.titleLarge.copy(
                        fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                    ),
                    color = if (isSelected) Palette.Sky400 else CockpitTheme.colors.textPrimary
                )
            }

            // Duration, Speeds, and Driver Score
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "⏱️ $durationText",
                        style = CockpitTheme.typography.labelSmall.copy(
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                        ),
                        color = Palette.Slate300
                    )
                    Text(text = "•", style = CockpitTheme.typography.labelSmall, color = Palette.Slate600)
                    Text(
                        text = "${NumberFormatUtils.formatSpeed(trip.avgSpeedKmh, 2)} km/h avg",
                        style = CockpitTheme.typography.labelSmall.copy(
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                        ),
                        color = Palette.Slate300
                    )
                    if (trip.maxSpeedKmh > 0) {
                        Text(text = "•", style = CockpitTheme.typography.labelSmall, color = Palette.Slate600)
                        Text(
                            text = "max ${NumberFormatUtils.formatSpeed(trip.maxSpeedKmh, 2)}",
                            style = CockpitTheme.typography.labelSmall.copy(
                                fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                            ),
                            color = Palette.Purple500
                        )
                    }
                }

                val scoreColor = when {
                    trip.driverScore >= 88 -> Palette.Emerald500
                    trip.driverScore >= 72 -> Palette.Amber500
                    else -> Palette.Red500
                }
                Box(
                    modifier = Modifier
                        .clip(CopilotShapes.R6)
                        .background(scoreColor.copy(alpha = 0.15f))
                        .border(1.dp, scoreColor.copy(alpha = 0.35f), CopilotShapes.R6)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "Score ${trip.driverScore}",
                        style = CockpitTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Black,
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                        ),
                        color = scoreColor
                    )
                }
            }
        }
    }
}

@Composable
internal fun PillBadge(text: String, color: Color) = TintedBadge(
    text = text,
    color = color,
    size = BadgeSize.Compact,
    textStyle = CockpitTheme.typography.labelSmall.copy(
        fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily,
        fontFeatureSettings = "tnum"
    )
)
