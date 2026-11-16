package com.antigravity.copilot.feature.trips

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.ViewGroup
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.antigravity.copilot.R
import com.antigravity.copilot.core.designsystem.AutomotiveDesignTokens
import com.antigravity.copilot.core.database.TripEntity
import com.antigravity.copilot.core.database.VehicleStateTransitionEntity
import com.antigravity.copilot.core.database.FuelLedgerEntity
import com.antigravity.copilot.core.util.NumberFormatUtils
import com.antigravity.copilot.core.database.TripPointEntity
import com.antigravity.copilot.core.designsystem.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import com.antigravity.copilot.core.map.OfflineMapManager
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max
import kotlin.math.roundToInt

// ==================== DOMAIN DATA MODELS ====================

private enum class TripDetailTab(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    MAP_REPLAY("Route Replay", Icons.Default.PlayCircleOutline),
    TIMELINE("Timeline", Icons.Default.Schedule),
    CHARTS("Trip Charts", Icons.AutoMirrored.Filled.ShowChart)
}

private enum class PlaybackStatus {
    STOPPED, PLAYING, PAUSED, COMPLETED
}

private sealed class TimelineEvent(
    val timestamp: Long,
    val title: String,
    val description: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val accentColor: Color,
    val speedKmh: Float = 0f
) {
    class TripStart(time: Long, loc: String) : TimelineEvent(
        time, "Trip Started", loc.ifBlank { "Vehicle powered on" },
        Icons.Default.PlayArrow, Color(0xFF10B981)
    )
    class MotionStarted(time: Long, speed: Float) : TimelineEvent(
        time, "Vehicle in Motion", "Accelerated to ${NumberFormatUtils.formatSpeedWithUnit(speed, 2)}",
        Icons.Default.DirectionsCar, Color(0xFF38BDF8), speed
    )
    class VehicleIdle(time: Long, durationMs: Long) : TimelineEvent(
        time, "Vehicle Stationary / Idle", "Standstill for ${formatDurationShort(durationMs)}",
        Icons.Default.PauseCircle, Color(0xFFF59E0B)
    )
    class MotionResumed(time: Long, speed: Float) : TimelineEvent(
        time, "Movement Resumed", "Cruising at ${NumberFormatUtils.formatSpeedWithUnit(speed, 2)}",
        Icons.Default.FastForward, Color(0xFF38BDF8), speed
    )
    class PeakSpeed(time: Long, speed: Float) : TimelineEvent(
        time, "Peak Speed Recorded", "Maximum speed of ${NumberFormatUtils.formatSpeedWithUnit(speed, 2)}",
        Icons.Default.Speed, Color(0xFFA855F7), speed
    )
    class RefuelRecorded(time: Long, liters: Double, cost: Double, station: String) : TimelineEvent(
        time, "Fuel Added",
        "${NumberFormatUtils.formatFuelLitersWithUnit(liters, 2)} refueled" +
            (if (cost > 0) " for ${NumberFormatUtils.formatCurrencyWithSymbol(cost, 2)}" else "") +
            (if (station.isNotBlank()) " at $station" else ""),
        Icons.Default.LocalGasStation, Color(0xFF06B6D4)
    )
    class EnginePowerEvent(time: Long, title: String, detail: String) : TimelineEvent(
        time, title, detail,
        Icons.Default.PowerSettingsNew, Color(0xFF94A3B8)
    )
    class TripEnd(time: Long, loc: String, totalKm: Double) : TimelineEvent(
        time, "Trip Completed", "${loc.ifBlank { "Destination reached" }} • ${NumberFormatUtils.formatDistanceWithUnit(totalKm, 2)}",
        Icons.Default.Flag, Color(0xFFEF4444)
    )
}

private data class MonthlyTripGroup(
    val monthIndex: Int,
    val monthName: String,
    val year: Int,
    val totalDistanceKm: Double,
    val totalDurationMs: Long,
    val trips: List<TripEntity>
)

private data class YearlyTripAnalytics(
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

// ==================== MAIN TRIP HISTORY SCREEN ====================

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
            .background(CockpitTheme.colors.backgroundBrush)
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
                    IconButton(
                        onClick = onBackToCockpit,
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF141A24))
                            .border(1.2.dp, Color(0xFF262A31), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Trip History",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Black,
                                    fontSize = 24.sp,
                                    letterSpacing = (-0.5).sp
                                ),
                                color = Color.White
                            )

                            // Year Pill
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF0F263E))
                                    .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = "$selectedYear",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Black,
                                        fontSize = 12.5.sp,
                                        fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                                    ),
                                    color = Color(0xFF38BDF8)
                                )
                            }
                        }

                        Text(
                            text = "GPS TELEMETRY ARCHIVE • ANIMATED ROUTE REPLAY • DRIVING INSIGHTS",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.5.sp,
                                letterSpacing = 0.5.sp
                            ),
                            color = Color(0xFF87929A)
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
                                        fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                                    ),
                                    color = if (isSelected) Color.White else Color(0xFF87929A)
                                )
                            }
                        }
                    }
                }
            }

            // ==================== 2. YEARLY SUMMARY BANNER ====================
            YearlyTripSummaryBanner(analytics = yearlyAnalytics)

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
                            .weight(if (isCompactWidth) 1f else 0.40f)
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
                                .weight(0.60f)
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
                        .background(Color(0xFF070B12))
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
                                    color = Color.White
                                )
                            )
                            IconButton(
                                onClick = { showMobileDetailDialog = false },
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF141A24))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = Color.White,
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

// ==================== YEARLY SUMMARY BANNER ====================

@Composable
private fun YearlyTripSummaryBanner(analytics: YearlyTripAnalytics) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF0B1019))
            .border(1.2.dp, Color(0xFF1E2838), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TripStatTile(
            modifier = Modifier.weight(1f),
            label = "TOTAL TRIPS",
            value = "${analytics.totalTrips}",
            unit = "Trips",
            color = Color.White
        )
        TripStatTile(
            modifier = Modifier.weight(1.2f),
            label = "TOTAL DISTANCE",
            value = NumberFormatUtils.formatDistance(analytics.totalDistanceKm, 2, useGrouping = true),
            unit = "km",
            color = Color(0xFF38BDF8)
        )
        TripStatTile(
            modifier = Modifier.weight(1.2f),
            label = "DRIVING TIME",
            value = formatDurationHoursMinutes(analytics.totalDrivingDurationMs),
            unit = "",
            color = Color(0xFFF59E0B)
        )
        TripStatTile(
            modifier = Modifier.weight(1.15f),
            label = "AVG SPEED",
            value = if (analytics.avgSpeedKmh > 0) NumberFormatUtils.formatSpeed(analytics.avgSpeedKmh, 2) else "--",
            unit = "km/h",
            color = Color(0xFF10B981)
        )
        TripStatTile(
            modifier = Modifier.weight(1.15f),
            label = "MAX SPEED",
            value = if (analytics.maxSpeedKmh > 0) NumberFormatUtils.formatSpeed(analytics.maxSpeedKmh, 2) else "--",
            unit = "km/h",
            color = Color(0xFFA855F7)
        )
        TripStatTile(
            modifier = Modifier.weight(1.1f),
            label = "IDLE TIME",
            value = formatDurationHoursMinutes(analytics.totalIdleDurationMs),
            unit = "",
            color = Color(0xFF94A3B8)
        )
    }
}

@Composable
private fun TripStatTile(
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
                        fontSize = 22.sp,
                        lineHeight = 24.sp,
                        fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum",
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
                            fontSize = 13.sp,
                            lineHeight = 14.sp,
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum",
                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                        ),
                        color = Color(0xFF87929A),
                        modifier = Modifier.padding(bottom = 1.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    lineHeight = 13.sp,
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

// ==================== LEFT COLUMN: MONTH-GROUPED TRIP LIST ====================

@Composable
private fun TripListGroupedFeed(
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
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF090E17))
                .border(1.2.dp, Color(0xFF1E2838), RoundedCornerShape(16.dp)),
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
                        .clip(CircleShape)
                        .background(Color(0xFF101726))
                        .border(1.dp, Color(0xFF1E2838), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.DirectionsCar,
                        contentDescription = null,
                        tint = Color(0xFF475569),
                        modifier = Modifier.size(28.dp)
                    )
                }
                Text(
                    text = "No Trips in ${analytics.year}",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 16.sp),
                    color = Color.White
                )
                Text(
                    text = "Drive to record telemetry trips with high-precision GPS playback.",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                    color = Color(0xFF87929A)
                )
            }
        }
    } else {
        LazyColumn(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(activeMonthGroups, key = { "${it.year}-${it.monthIndex}" }) { group ->
                val monthKey = "${group.year}-${group.monthIndex}"
                val isExpanded = expandedMonths.getOrPut(monthKey) { true }
                val arrowRotation by animateFloatAsState(
                    targetValue = if (isExpanded) 180f else 0f,
                    animationSpec = tween(200),
                    label = "arrow"
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF0A0F1A))
                        .border(1.dp, Color(0xFF1A2436), RoundedCornerShape(14.dp))
                ) {
                    // Month Header Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expandedMonths[monthKey] = !isExpanded }
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
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Black,
                                    fontSize = 14.5.sp
                                ),
                                color = Color.White
                            )
                            PillBadge(
                                text = "${group.trips.size} trips",
                                color = Color(0xFF94A3B8)
                            )
                            PillBadge(
                                text = NumberFormatUtils.formatDistanceWithUnit(group.totalDistanceKm, 2),
                                color = Color(0xFF38BDF8)
                            )
                        }

                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = if (isExpanded) "Collapse" else "Expand",
                            tint = Color(0xFF87929A),
                            modifier = Modifier
                                .size(18.dp)
                                .rotate(arrowRotation)
                        )
                    }

                    // Expanded Trips
                    AnimatedVisibility(
                        visible = isExpanded,
                        enter = fadeIn(tween(150)),
                        exit = fadeOut(tween(150))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            group.trips.forEach { trip ->
                                TripCardItem(
                                    trip = trip,
                                    isSelected = selectedTrip?.id == trip.id,
                                    onClick = { onSelectTrip(trip) }
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TripCardItem(
    trip: TripEntity,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
    val dateFormat = SimpleDateFormat("dd MMM", Locale.getDefault())
    val startStr = timeFormat.format(Date(trip.startTime))
    val endStr = if (trip.endTime > trip.startTime) timeFormat.format(Date(trip.endTime)) else ""
    val dateStr = dateFormat.format(Date(trip.startTime))

    val distanceKm = NumberFormatUtils.formatDistance(trip.distanceMeters / 1000.0, 2)
    val wallClockDurationMs = if (trip.endTime > trip.startTime) (trip.endTime - trip.startTime) else (trip.drivingDurationMs + trip.stationaryDurationMs)
    val activeDurationMs = trip.drivingDurationMs + trip.stationaryDurationMs
    val isMultiDay = (trip.endTime > trip.startTime) && (
        wallClockDurationMs >= 86_400_000L ||
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(trip.startTime)) !=
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(trip.endTime))
    )
    val durationText = if (isMultiDay) {
        val wallText = com.antigravity.copilot.core.trip.TimeFormatUtils.formatAdaptiveDuration(wallClockDurationMs)
        val activeText = com.antigravity.copilot.core.trip.TimeFormatUtils.formatAdaptiveDuration(activeDurationMs)
        "$wallText ($activeText active)"
    } else {
        com.antigravity.copilot.core.trip.TimeFormatUtils.formatAdaptiveDuration(activeDurationMs)
    }
    val dateDisplayStr = if (isMultiDay) {
        val df = SimpleDateFormat("dd MMM", Locale.getDefault())
        "${df.format(Date(trip.startTime))} $startStr -> ${df.format(Date(trip.endTime))} $endStr"
    } else {
        "$dateStr • $startStr${if (endStr.isNotBlank()) " - $endStr" else ""}"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) Color(0xFF0F2038) else Color(0xFF0C1322))
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) Color(0xFF38BDF8) else Color(0xFF1E2838),
                shape = RoundedCornerShape(12.dp)
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
                    Text(
                        text = "${trip.startLocationName.ifBlank { "Trip Origin" }} ➔ ${trip.endLocationName.ifBlank { "Destination" }}",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.5.sp
                        ),
                        color = Color.White,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Text(
                        text = dateDisplayStr,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 13.sp,
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                        ),
                        color = Color(0xFF87929A)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                Text(
                    text = "$distanceKm km",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Black,
                        fontSize = 19.sp,
                        fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                    ),
                    color = if (isSelected) Color(0xFF38BDF8) else Color.White
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
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 12.5.sp,
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum",
                            fontWeight = FontWeight.Bold
                        ),
                        color = Color(0xFFCBD5E1)
                    )
                    Text(text = "•", fontSize = 11.sp, color = Color(0xFF475569))
                    Text(
                        text = "${NumberFormatUtils.formatSpeed(trip.avgSpeedKmh, 2)} km/h avg",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 12.5.sp,
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum",
                            fontWeight = FontWeight.Bold
                        ),
                        color = Color(0xFFCBD5E1)
                    )
                    if (trip.maxSpeedKmh > 0) {
                        Text(text = "•", fontSize = 11.sp, color = Color(0xFF475569))
                        Text(
                            text = "max ${NumberFormatUtils.formatSpeed(trip.maxSpeedKmh, 2)}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 12.5.sp,
                                fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum",
                                fontWeight = FontWeight.Bold
                            ),
                            color = Color(0xFFA855F7)
                        )
                    }
                }

                val scoreColor = when {
                    trip.driverScore >= 88 -> Color(0xFF10B981)
                    trip.driverScore >= 72 -> Color(0xFFF59E0B)
                    else -> Color(0xFFEF4444)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(scoreColor.copy(alpha = 0.15f))
                        .border(1.dp, scoreColor.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "Score ${trip.driverScore}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Black,
                            fontSize = 12.sp,
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                        ),
                        color = scoreColor
                    )
                }
            }
        }
    }
}

// ==================== RIGHT COLUMN: TRIP DETAIL CONTAINER ====================

@Composable
private fun TripDetailContainer(
    trip: TripEntity?,
    points: List<TripPointEntity>,
    transitions: List<VehicleStateTransitionEntity> = emptyList(),
    fuelEvents: List<FuelLedgerEntity> = emptyList(),
    isPointsLoading: Boolean,
    selectedTab: TripDetailTab,
    onSelectTab: (TripDetailTab) -> Unit,
    playbackStatus: PlaybackStatus,
    currentIndex: Int,
    playbackSpeed: Float,
    onPlayPause: () -> Unit,
    onRestart: () -> Unit,
    onSeek: (Int) -> Unit,
    onCycleSpeed: () -> Unit,
    onDeleteTrip: ((Long) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF090E17))
            .border(1.2.dp, Color(0xFF1E2838), RoundedCornerShape(16.dp))
    ) {
        if (trip == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayCircleOutline,
                    contentDescription = null,
                    tint = Color(0xFF38BDF8).copy(alpha = 0.6f),
                    modifier = Modifier.size(56.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Trip GPS Playback View",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 18.sp),
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Select any journey on the left to start fast-forward animated route replay.",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp),
                    color = Color(0xFF87929A)
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top Detail Tabs Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(10f)
                        .background(Color(0xFF0B101D))
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Tab Selector
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TripDetailTab.values().forEach { tab ->
                            val isSelected = tab == selectedTab
                            Box(
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 44.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSelected) Color(0xFF1E3A5F) else Color(0xFF0F1726))
                                    .border(
                                        width = 1.2.dp,
                                        color = if (isSelected) Color(0xFF38BDF8) else Color(0xFF1E2838),
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .clickable { onSelectTab(tab) }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = tab.icon,
                                        contentDescription = null,
                                        tint = if (isSelected) Color(0xFF38BDF8) else Color(0xFF87929A),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = tab.title,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                                            fontSize = 14.sp
                                        ),
                                        color = if (isSelected) Color.White else Color(0xFF87929A)
                                    )
                                }
                            }
                        }
                    }

                    // Trip quick badge + Delete Option
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "${NumberFormatUtils.formatDistanceWithUnit(trip.distanceMeters / 1000.0, 2)} • ${NumberFormatUtils.formatSpeedWithUnit(trip.avgSpeedKmh, 2)}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.5.sp,
                                fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                            ),
                            color = Color(0xFF38BDF8)
                        )

                        if (onDeleteTrip != null) {
                            IconButton(
                                onClick = { showDeleteConfirm = true },
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF261010))
                                    .border(1.2.dp, Color(0xFFEF4444).copy(alpha = 0.5f), CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "Delete Trip",
                                    tint = Color(0xFFEF4444),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }

                if (showDeleteConfirm) {
                    AlertDialog(
                        onDismissRequest = { showDeleteConfirm = false },
                        title = {
                            Text(
                                text = "Delete Trip Record?",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                                color = Color.White
                            )
                        },
                        text = {
                            Text(
                                text = "Permanently remove this trip and its recorded GPS breadcrumbs? This action cannot be undone.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF94A3B8)
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showDeleteConfirm = false
                                    onDeleteTrip?.invoke(trip.id)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                            ) {
                                Text("Delete", fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        },
                        dismissButton = {
                            OutlinedButton(
                                onClick = { showDeleteConfirm = false }
                            ) {
                                Text("Cancel", color = Color(0xFF94A3B8))
                            }
                        },
                        containerColor = Color(0xFF0F172A)
                    )
                }

                // Tab Content
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    when (selectedTab) {
                        TripDetailTab.MAP_REPLAY -> {
                            TripMapReplayView(
                                trip = trip,
                                points = points,
                                isPointsLoading = isPointsLoading,
                                playbackStatus = playbackStatus,
                                currentIndex = currentIndex,
                                playbackSpeed = playbackSpeed,
                                onPlayPause = onPlayPause,
                                onRestart = onRestart,
                                onSeek = onSeek,
                                onCycleSpeed = onCycleSpeed,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        TripDetailTab.TIMELINE -> {
                            TripTimelineView(
                                trip = trip,
                                points = points,
                                transitions = transitions,
                                fuelEvents = fuelEvents,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        TripDetailTab.CHARTS -> {
                            TripChartsView(
                                trip = trip,
                                points = points,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==================== TAB 1: ROUTE REPLAY & MAP ====================

@Composable
private fun TripMapReplayView(
    trip: TripEntity,
    points: List<TripPointEntity>,
    isPointsLoading: Boolean,
    playbackStatus: PlaybackStatus,
    currentIndex: Int,
    playbackSpeed: Float,
    onPlayPause: () -> Unit,
    onRestart: () -> Unit,
    onSeek: (Int) -> Unit,
    onCycleSpeed: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentPt = points.getOrNull(currentIndex)
    val elapsedMs = if (currentPt != null && points.isNotEmpty()) {
        max(0L, currentPt.timestamp - points.first().timestamp)
    } else 0L

    val totalDurationMs = if (points.size >= 2) {
        max(1000L, points.last().timestamp - points.first().timestamp)
    } else (trip.drivingDurationMs + trip.stationaryDurationMs)

    if (isPointsLoading) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color(0xFF38BDF8), modifier = Modifier.size(36.dp))
        }
    } else if (points.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "No GPS route points recorded for this journey.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF87929A)
            )
        }
    } else {
        // Dedicated column layout: Unobstructed map on top, dedicated controls panel strictly beneath it
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 1. TOP: Map viewport - 100% visible, fully interactive, never blocked by controls
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFF1E2838), RoundedCornerShape(12.dp))
            ) {
                TripPlaybackMapView(
                    points = points,
                    currentIndex = currentIndex,
                    modifier = Modifier.fillMaxSize()
                )

                // Top Status HUD
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF070E1A).copy(alpha = 0.92f))
                        .border(1.dp, Color(0xFF1E2838), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val statusDotColor = when (playbackStatus) {
                        PlaybackStatus.PLAYING -> Color(0xFF10B981)
                        PlaybackStatus.PAUSED -> Color(0xFFF59E0B)
                        PlaybackStatus.COMPLETED -> Color(0xFF38BDF8)
                        PlaybackStatus.STOPPED -> Color(0xFF87929A)
                    }
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(statusDotColor)
                    )

                    Text(
                        text = when (playbackStatus) {
                            PlaybackStatus.PLAYING -> "REPLAYING"
                            PlaybackStatus.PAUSED -> "PAUSED"
                            PlaybackStatus.COMPLETED -> "FINISHED"
                            PlaybackStatus.STOPPED -> "READY"
                        },
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Black,
                            fontSize = 8.5.sp,
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                        ),
                        color = statusDotColor
                    )

                    Text(
                        text = "• ${NumberFormatUtils.formatSpeedWithUnit(currentPt?.speedKmh ?: 0f, 2)}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp,
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                        ),
                        color = Color.White
                    )

                    Text(
                        text = "• ${formatDurationShort(elapsedMs)} / ${formatDurationShort(totalDurationMs)}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.sp,
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                        ),
                        color = Color(0xFF87929A)
                    )
                }
            }

            // 2. BOTTOM: Dedicated Replay Controls Panel BELOW the Map
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF070E1A))
                    .border(1.dp, Color(0xFF1E2838), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // Scrubbing Slider
                    Slider(
                        value = currentIndex.toFloat(),
                        onValueChange = { onSeek(it.toInt()) },
                        valueRange = 0f..(points.size - 1).coerceAtLeast(1).toFloat(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(18.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF38BDF8),
                            activeTrackColor = Color(0xFF0284C7),
                            inactiveTrackColor = Color(0xFF1E2838)
                        )
                    )

                    // Control Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left: Essential Play/Pause/Replay Button
                        Button(
                            onClick = if (playbackStatus == PlaybackStatus.COMPLETED) onRestart else onPlayPause,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (playbackStatus == PlaybackStatus.PLAYING) Color(0xFFF59E0B) else Color(0xFF0284C7),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Icon(
                                imageVector = when (playbackStatus) {
                                    PlaybackStatus.PLAYING -> Icons.Default.Pause
                                    PlaybackStatus.COMPLETED -> Icons.Default.Replay
                                    else -> Icons.Default.PlayArrow
                                },
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = when (playbackStatus) {
                                    PlaybackStatus.PLAYING -> "Pause"
                                    PlaybackStatus.PAUSED -> "Resume"
                                    PlaybackStatus.COMPLETED -> "Replay"
                                    PlaybackStatus.STOPPED -> "Play"
                                },
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }

                        // Right: Point Progress & Playback Speed
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Pt ${currentIndex + 1}/${points.size}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 9.5.sp,
                                    fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                                ),
                                color = Color(0xFF87929A)
                            )

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF142033))
                                    .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                    .clickable { onCycleSpeed() }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${playbackSpeed.toInt()}x Speed",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Black,
                                        fontSize = 9.5.sp,
                                        fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                                    ),
                                    color = Color(0xFF38BDF8)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==================== TAB 2: TRIP TIMELINE ====================

@Composable
private fun TripTimelineView(
    trip: TripEntity,
    points: List<TripPointEntity>,
    transitions: List<VehicleStateTransitionEntity> = emptyList(),
    fuelEvents: List<FuelLedgerEntity> = emptyList(),
    modifier: Modifier = Modifier
) {
    val timelineEvents = remember(trip, points, transitions, fuelEvents) {
        generateTripTimeline(trip, points, transitions, fuelEvents)
    }

    if (timelineEvents.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "No timeline events available for this journey.",
                color = Color(0xFF87929A),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    } else {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(timelineEvents) { event ->
                val timeStr = SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date(event.timestamp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    // Node Icon & Connector
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(36.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(event.accentColor.copy(alpha = 0.15f))
                                .border(1.2.dp, event.accentColor, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = event.icon,
                                contentDescription = null,
                                tint = event.accentColor,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .height(28.dp)
                                .background(Color(0xFF1E2838))
                        )
                    }

                    // Content Box
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF0C1322))
                            .border(1.dp, Color(0xFF1E2838), RoundedCornerShape(10.dp))
                            .padding(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = event.title,
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Black,
                                        fontSize = 13.sp
                                    ),
                                    color = Color.White
                                )
                                Text(
                                    text = event.description,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 11.sp
                                    ),
                                    color = Color(0xFF94A3B8)
                                )
                            }

                            Text(
                                text = timeStr,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.5.sp,
                                    fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                                ),
                                color = event.accentColor
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==================== TAB 3: TRIP CHARTS ====================

@Composable
private fun TripChartsView(
    trip: TripEntity,
    points: List<TripPointEntity>,
    modifier: Modifier = Modifier
) {
    val movingMs = trip.drivingDurationMs
    val idleMs = trip.stationaryDurationMs
    val wallClockMs = if (trip.endTime > trip.startTime) (trip.endTime - trip.startTime) else (movingMs + idleMs)
    val engineOffMs = (wallClockMs - (movingMs + idleMs)).coerceAtLeast(0L)
    val totalMs = (movingMs + idleMs + engineOffMs).coerceAtLeast(1L)
    val movingPct = ((movingMs.toDouble() / totalMs) * 100).roundToInt().coerceIn(0, 100)
    val idlePct = ((idleMs.toDouble() / totalMs) * 100).roundToInt().coerceIn(0, 100)
    val engineOffPct = (100 - movingPct - idlePct).coerceAtLeast(0)

    val speedProfile = remember(points) {
        if (points.isEmpty()) emptyList()
        else points.map { it.speedKmh.toDouble() }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Speed vs Time Profile Chart
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0C1322))
                    .border(1.dp, Color(0xFF1E2838), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "SPEED VS TIME PROFILE",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Black,
                                fontSize = 9.5.sp,
                                letterSpacing = 0.5.sp
                            ),
                            color = Color(0xFF38BDF8)
                        )
                        Text(
                            text = "Peak: ${NumberFormatUtils.formatSpeedWithUnit(trip.maxSpeedKmh, 2)}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                            ),
                            color = Color.White
                        )
                    }

                    SpeedTimeCanvas(
                        speeds = speedProfile,
                        maxSpeed = max(trip.maxSpeedKmh, 60.0),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(95.dp)
                    )
                }
            }
        }

        // 2. Moving vs Idle Time Ratio
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0C1322))
                    .border(1.dp, Color(0xFF1E2838), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "MOVING TIME VS IDLE TIME",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Black,
                                fontSize = 9.5.sp,
                                letterSpacing = 0.5.sp
                            ),
                            color = Color(0xFFF59E0B)
                        )
                        Text(
                            text = if (engineOffMs >= 300_000L) "Moving $movingPct% • Idle $idlePct% • Parked $engineOffPct%" else "Moving $movingPct% • Idle $idlePct%",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                            ),
                            color = Color.White
                        )
                    }

                    // Ratio segmented bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(14.dp)
                            .clip(RoundedCornerShape(4.dp))
                    ) {
                        if (movingPct > 0) {
                            Box(
                                modifier = Modifier
                                    .weight(movingPct.toFloat().coerceAtLeast(1f))
                                    .fillMaxHeight()
                                    .background(Color(0xFF10B981))
                            )
                        }
                        if (idlePct > 0) {
                            Box(
                                modifier = Modifier
                                    .weight(idlePct.toFloat().coerceAtLeast(1f))
                                    .fillMaxHeight()
                                    .background(Color(0xFFF59E0B))
                            )
                        }
                        if (engineOffPct > 0 && engineOffMs >= 300_000L) {
                            Box(
                                modifier = Modifier
                                    .weight(engineOffPct.toFloat().coerceAtLeast(1f))
                                    .fillMaxHeight()
                                    .background(Color(0xFF64748B))
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "🟢 Moving: ${com.antigravity.copilot.core.trip.TimeFormatUtils.formatAdaptiveDuration(movingMs)}",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = Color(0xFF10B981)
                        )
                        Text(
                            text = "🟡 Idle: ${com.antigravity.copilot.core.trip.TimeFormatUtils.formatAdaptiveDuration(idleMs)}",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = Color(0xFFF59E0B)
                        )
                        if (engineOffMs >= 300_000L) {
                            Text(
                                text = "⚪ Parked: ${com.antigravity.copilot.core.trip.TimeFormatUtils.formatAdaptiveDuration(engineOffMs)}",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }
                }
            }
        }

        // 3. Speed Bands Distribution
        item {
            val speedBands = remember(points) {
                computeSpeedBands(points)
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0C1322))
                    .border(1.dp, Color(0xFF1E2838), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "SPEED DISTRIBUTION BANDS",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Black,
                            fontSize = 9.5.sp,
                            letterSpacing = 0.5.sp
                        ),
                        color = Color(0xFFA855F7)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        speedBands.forEach { band ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF141C2B))
                                    .padding(8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = band.label,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = Color(0xFF87929A)
                                    )
                                    Text(
                                        text = "${band.percent}%",
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontWeight = FontWeight.Black,
                                            fontSize = 14.sp,
                                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                                        ),
                                        color = band.color
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. Fuel & Efficiency Estimates
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0C1322))
                    .border(1.dp, Color(0xFF1E2838), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "ESTIMATED FUEL CONSUMPTION",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Black,
                                fontSize = 9.5.sp,
                                letterSpacing = 0.5.sp
                            ),
                            color = Color(0xFF38BDF8)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (trip.estimatedFuelLiters > 0) "${NumberFormatUtils.formatFuelLiters(trip.estimatedFuelLiters, 2)} Liters (~${NumberFormatUtils.formatCurrencyWithSymbol(trip.estimatedFuelLiters * 96.5, 2)})" else "-- Liters",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.5.sp,
                                fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                            ),
                            color = Color.White
                        )
                    }

                    if (trip.calculatedMileageKmpl > 0) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF10B981).copy(alpha = 0.15f))
                                .border(1.dp, Color(0xFF10B981).copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "⚡ ${NumberFormatUtils.formatMileageWithUnit(trip.calculatedMileageKmpl, 2)}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Black,
                                    fontSize = 11.sp,
                                    fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                                ),
                                color = Color(0xFF10B981)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==================== CANVAS VISUALIZERS ====================

@Composable
private fun SpeedTimeCanvas(
    speeds: List<Double>,
    maxSpeed: Double,
    modifier: Modifier = Modifier
) {
    val accentColor = Color(0xFF38BDF8)
    val gridColor = Color(0xFF1A2336)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Horizontal guide lines
        for (i in 0..2) {
            val y = (h / 2) * i
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 1f
            )
        }

        if (speeds.size >= 2) {
            val safeMax = if (maxSpeed > 0) maxSpeed else 60.0
            val stepX = w / (speeds.size - 1).toFloat()

            val path = Path()
            val fillPath = Path()

            val firstY = h * (1f - (speeds.first() / safeMax).coerceIn(0.0, 1.0).toFloat())
            path.moveTo(0f, firstY)
            fillPath.moveTo(0f, h)
            fillPath.lineTo(0f, firstY)

            for (i in 1 until speeds.size) {
                val x = i * stepX
                val y = h * (1f - (speeds[i] / safeMax).coerceIn(0.0, 1.0).toFloat())
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }

            fillPath.lineTo(w, h)
            fillPath.close()

            // Area fill
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(accentColor.copy(alpha = 0.35f), Color.Transparent),
                    startY = 0f,
                    endY = h
                )
            )

            // Outline stroke
            drawPath(
                path = path,
                color = accentColor,
                style = Stroke(width = 2.5f, cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
private fun WeeklyDrivingPatternCanvas(
    distribution: IntArray,
    modifier: Modifier = Modifier
) {
    val weekdayAccent = Color(0xFF38BDF8)
    val weekendAccent = Color(0xFFA855F7)
    val gridColor = Color(0xFF152033)

    val dayLabelPaint = remember {
        Paint().apply {
            color = android.graphics.Color.parseColor("#94A3B8")
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
    }
    val countValuePaint = remember {
        Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 18f
            isFakeBoldText = true
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
    }
    val peakBadgePaint = remember {
        Paint().apply {
            color = android.graphics.Color.parseColor("#38BDF8")
            textSize = 13f
            isFakeBoldText = true
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
    }
    val zoneHeaderPaint = remember {
        Paint().apply {
            color = android.graphics.Color.parseColor("#64748B")
            textSize = 13f
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
    }

    val dayNames = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")

    Canvas(modifier = modifier) {
        val totalWidth = size.width
        val totalHeight = size.height

        val topPadding = 42f
        val bottomPadding = 32f
        val leftPadding = 24f
        val rightPadding = 24f

        val graphWidth = totalWidth - leftPadding - rightPadding
        val graphHeight = totalHeight - topPadding - bottomPadding

        val maxVal = (distribution.maxOrNull() ?: 0).coerceAtLeast(1)
        val peakDay = distribution.indices.maxByOrNull { distribution[it] } ?: -1
        val colWidth = graphWidth / 7f
        val barWidth = (colWidth * 0.46f).coerceIn(24f, 64f)

        // 1. Weekday vs Weekend Zone Backgrounds
        val weekdayWidth = colWidth * 5f
        val weekendWidth = colWidth * 2f

        // Weekday zone background (Mon-Fri)
        drawRoundRect(
            color = Color(0xFF0F172A).copy(alpha = 0.35f),
            topLeft = Offset(leftPadding, topPadding),
            size = Size(weekdayWidth, graphHeight),
            cornerRadius = CornerRadius(6f, 6f)
        )
        drawContext.canvas.nativeCanvas.drawText(
            "WEEKDAYS (MON - FRI)",
            leftPadding + weekdayWidth / 2f,
            topPadding - 16f,
            zoneHeaderPaint
        )

        // Weekend zone background (Sat-Sun)
        drawRoundRect(
            color = Color(0xFF2E1065).copy(alpha = 0.22f),
            topLeft = Offset(leftPadding + weekdayWidth, topPadding),
            size = Size(weekendWidth, graphHeight),
            cornerRadius = CornerRadius(6f, 6f)
        )
        drawContext.canvas.nativeCanvas.drawText(
            "WEEKEND (SAT - SUN)",
            leftPadding + weekdayWidth + weekendWidth / 2f,
            topPadding - 16f,
            zoneHeaderPaint
        )

        // Zone separator line
        drawLine(
            color = Color(0xFF1E2E44),
            start = Offset(leftPadding + weekdayWidth, topPadding - 20f),
            end = Offset(leftPadding + weekdayWidth, topPadding + graphHeight),
            strokeWidth = 1.2f
        )

        // Horizontal baseline
        drawLine(
            color = gridColor,
            start = Offset(leftPadding, topPadding + graphHeight),
            end = Offset(totalWidth - rightPadding, topPadding + graphHeight),
            strokeWidth = 1f
        )

        // 2. Full-height Recessed Channel Tracks for all 7 days
        for (day in 0 until 7) {
            val centerX = leftPadding + colWidth * day + colWidth / 2f
            val barLeft = centerX - barWidth / 2f

            drawRoundRect(
                color = Color(0xFF101827),
                topLeft = Offset(barLeft, topPadding),
                size = Size(barWidth, graphHeight),
                cornerRadius = CornerRadius(6f, 6f)
            )
            drawRoundRect(
                color = Color(0xFF1A263B),
                topLeft = Offset(barLeft, topPadding),
                size = Size(barWidth, graphHeight),
                cornerRadius = CornerRadius(6f, 6f),
                style = Stroke(0.8f)
            )
        }

        // 3. Daily Bars
        for (day in 0 until 7) {
            val count = if (day in distribution.indices) distribution[day] else 0
            val centerX = leftPadding + colWidth * day + colWidth / 2f
            val barLeft = centerX - barWidth / 2f
            val isPeak = day == peakDay && count > 0
            val isWeekend = day >= 5
            val barAccent = if (isWeekend) weekendAccent else weekdayAccent

            if (count > 0) {
                val ratio = (count.toFloat() / maxVal).coerceIn(0f, 1f)
                val barH = (graphHeight * ratio).coerceAtLeast(10f)
                val barTop = topPadding + graphHeight - barH

                // Gradient bar
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = if (isPeak) {
                            listOf(barAccent, barAccent.copy(alpha = 0.40f))
                        } else {
                            listOf(barAccent.copy(alpha = 0.85f), barAccent.copy(alpha = 0.20f))
                        },
                        startY = barTop,
                        endY = topPadding + graphHeight
                    ),
                    topLeft = Offset(barLeft, barTop),
                    size = Size(barWidth, barH),
                    cornerRadius = CornerRadius(6f, 6f)
                )

                // Top highlight rim
                drawRoundRect(
                    color = if (isPeak) Color.White else barAccent,
                    topLeft = Offset(barLeft, barTop),
                    size = Size(barWidth, 4f),
                    cornerRadius = CornerRadius(2f, 2f)
                )

                // Peak beacon & tag
                if (isPeak) {
                    drawCircle(color = barAccent.copy(alpha = 0.35f), radius = 10f, center = Offset(centerX, barTop))
                    drawCircle(color = Color.White, radius = 4f, center = Offset(centerX, barTop))
                    peakBadgePaint.color = barAccent.toArgb()
                    drawContext.canvas.nativeCanvas.drawText("★ PEAK DAY", centerX, barTop - 24f, peakBadgePaint)
                }

                // Trip count text above bar
                drawContext.canvas.nativeCanvas.drawText(
                    "$count",
                    centerX,
                    barTop - 7f,
                    countValuePaint
                )
            } else {
                drawRoundRect(
                    color = Color(0xFF1C2738),
                    topLeft = Offset(barLeft, topPadding + graphHeight - 4f),
                    size = Size(barWidth, 4f),
                    cornerRadius = CornerRadius(2f, 2f)
                )
            }

            // Draw day labels below baseline
            val dayLabel = dayNames[day]
            dayLabelPaint.color = when {
                isPeak -> barAccent.toArgb()
                count > 0 -> android.graphics.Color.WHITE
                isWeekend -> weekendAccent.copy(alpha = 0.7f).toArgb()
                else -> android.graphics.Color.parseColor("#94A3B8")
            }
            drawContext.canvas.nativeCanvas.drawText(
                dayLabel,
                centerX,
                totalHeight - 8f,
                dayLabelPaint
            )
        }
    }
}

@Composable
private fun MonthlyDistanceCanvas(
    monthlyGroups: List<MonthlyTripGroup>,
    modifier: Modifier = Modifier
) {
    val accentColor = Color(0xFFF59E0B)
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
    val valPaint = remember {
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

        val maxKm = (monthlyGroups.maxOfOrNull { it.totalDistanceKm } ?: 0.0).coerceAtLeast(10.0)
        val effectiveMax = maxKm * 1.25
        val activeGroups = monthlyGroups.filter { it.totalDistanceKm > 0.0 }
        val avgKm = if (activeGroups.isNotEmpty()) activeGroups.map { it.totalDistanceKm }.average() else 0.0
        val maxKmIdx = monthlyGroups.indices.maxByOrNull { monthlyGroups[it].totalDistanceKm } ?: -1

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
            val label = if (scaleVal >= 1000) "${NumberFormatUtils.formatPrecision(scaleVal / 1000.0, 1)}k" else "${scaleVal.toInt()}km"
            drawContext.canvas.nativeCanvas.drawText(
                label,
                leftPadding - 8f,
                y + 5f,
                axisTextPaint
            )
        }

        val colWidth = graphWidth / 12f
        val barWidth = (colWidth * 0.52f).coerceIn(14f, 36f)

        // 2. Full-height Background Channel Tracks for all 12 months
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

        // 3. Yearly Average Distance Dashed Reference Line
        if (avgKm > 0.0) {
            val avgY = topPadding + graphHeight * (1f - (avgKm / effectiveMax).toFloat())
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
            val avgText = "AVG ${avgKm.toInt()} km"
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

        // 4. Monthly Distance Gradient Bars
        for (m in 0 until 12) {
            val group = monthlyGroups.getOrNull(m)
            val km = group?.totalDistanceKm ?: 0.0
            val centerX = leftPadding + colWidth * m + colWidth / 2f
            val barLeft = centerX - barWidth / 2f
            val isPeak = m == maxKmIdx && km > 0.0

            if (km > 0.0) {
                val ratio = (km / effectiveMax).coerceIn(0.0, 1.0).toFloat()
                val barH = (graphHeight * ratio).coerceAtLeast(8f)
                val barTop = topPadding + graphHeight - barH

                // Gradient bar
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
                    size = Size(barWidth, barH),
                    cornerRadius = CornerRadius(5f, 5f)
                )

                // Top highlight rim
                drawRoundRect(
                    color = if (isPeak) Color.White else accentColor,
                    topLeft = Offset(barLeft, barTop),
                    size = Size(barWidth, 3.5f),
                    cornerRadius = CornerRadius(2f, 2f)
                )

                // Peak beacon & tag
                if (isPeak) {
                    drawCircle(color = accentColor.copy(alpha = 0.35f), radius = 9f, center = Offset(centerX, barTop))
                    drawCircle(color = Color.White, radius = 3.5f, center = Offset(centerX, barTop))
                    drawContext.canvas.nativeCanvas.drawText("★ TOP", centerX, barTop - 24f, peakBadgePaint)
                }

                // Value above bar
                val valStr = if (km >= 1000) "${NumberFormatUtils.formatPrecision(km / 1000.0, 1)}k" else "${km.toInt()}"
                drawContext.canvas.nativeCanvas.drawText(
                    valStr,
                    centerX,
                    barTop - 8f,
                    valPaint
                )
            } else {
                drawRoundRect(
                    color = Color(0xFF1E2A3E),
                    topLeft = Offset(barLeft, topPadding + graphHeight - 4f),
                    size = Size(barWidth, 4f),
                    cornerRadius = CornerRadius(2f, 2f)
                )
            }

            // Month Labels at bottom (refined 17f size, highlighted for peak/active)
            val monthLabel = group?.monthName ?: ""
            monthTextPaint.color = if (isPeak) {
                accentColor.toArgb()
            } else if (km > 0.0) {
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

// ==================== OSMDROID MAP VIEW COMPOSABLE ====================

@Composable
fun TripPlaybackMapView(
    points: List<TripPointEntity>,
    currentIndex: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var mapViewInstance by remember { mutableStateOf<MapView?>(null) }
    var vehicleMarker by remember { mutableStateOf<Marker?>(null) }
    var fullRoutePolyline by remember { mutableStateOf<Polyline?>(null) }
    var trailPolyline by remember { mutableStateOf<Polyline?>(null) }
    var startMarker by remember { mutableStateOf<Marker?>(null) }
    var endMarker by remember { mutableStateOf<Marker?>(null) }
    val cachedGeoPoints = remember(points) {
        points.map { GeoPoint(it.latitude, it.longitude) }
    }

    DisposableEffect(Unit) {
        onDispose {
            mapViewInstance?.onPause()
            mapViewInstance?.onDetach()
            mapViewInstance = null
        }
    }

    LaunchedEffect(points, mapViewInstance) {
        val mv = mapViewInstance ?: return@LaunchedEffect
        if (points.size >= 2) {
            var minLat = Double.MAX_VALUE
            var maxLat = -Double.MAX_VALUE
            var minLon = Double.MAX_VALUE
            var maxLon = -Double.MAX_VALUE
            points.forEach { pt ->
                if (pt.latitude < minLat) minLat = pt.latitude
                if (pt.latitude > maxLat) maxLat = pt.latitude
                if (pt.longitude < minLon) minLon = pt.longitude
                if (pt.longitude > maxLon) maxLon = pt.longitude
            }
            val latSpan = (maxLat - minLat).coerceAtLeast(0.005)
            val lonSpan = (maxLon - minLon).coerceAtLeast(0.005)
            val box = BoundingBox(
                maxLat + latSpan * 0.25,
                maxLon + lonSpan * 0.25,
                minLat - latSpan * 0.25,
                minLon - lonSpan * 0.25
            )

            fullRoutePolyline?.setPoints(cachedGeoPoints)

            val startGeo = cachedGeoPoints.firstOrNull() ?: GeoPoint(points.first().latitude, points.first().longitude)
            startMarker?.apply {
                position = startGeo
                title = "Trip Start"
                icon = createTripFlagDrawable(context, "START", android.graphics.Color.parseColor("#00E676"))
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                setVisible(true)
            }

            val endGeo = cachedGeoPoints.lastOrNull() ?: GeoPoint(points.last().latitude, points.last().longitude)
            endMarker?.apply {
                position = endGeo
                title = "Trip End"
                icon = createTripFlagDrawable(context, "END", android.graphics.Color.parseColor("#FF5252"))
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                setVisible(true)
            }

            mv.post {
                mv.zoomToBoundingBox(box, false)
                mv.invalidate()
            }
        }
    }

    AndroidView(
        modifier = modifier.clipToBounds(),
        factory = { ctx ->
            MapView(ctx).apply {
                clipToOutline = true
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setMultiTouchControls(true)
                minZoomLevel = 0.0
                maxZoomLevel = 20.0
                zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
                setTileSource(OfflineMapManager.NORMAL_ROADMAP_SOURCE)
                overlayManager.tilesOverlay.setColorFilter(null)

                // Background route line (High contrast against photorealistic satellite)
                val fullLine = Polyline().apply {
                    outlinePaint.color = android.graphics.Color.parseColor("#8038BDF8")
                    outlinePaint.strokeWidth = 10f
                    outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                }
                overlays.add(fullLine)
                fullRoutePolyline = fullLine

                // Active glowing trail
                val trail = Polyline().apply {
                    outlinePaint.color = android.graphics.Color.parseColor("#00E5FF")
                    outlinePaint.strokeWidth = 14f
                    outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                }
                overlays.add(trail)
                trailPolyline = trail

                // Start Marker
                val startM = Marker(this).apply { setVisible(false) }
                overlays.add(startM)
                startMarker = startM

                // End Marker
                val endM = Marker(this).apply { setVisible(false) }
                overlays.add(endM)
                endMarker = endM

                // Vehicle Marker
                val carMarker = Marker(this).apply {
                    icon = ContextCompat.getDrawable(ctx, R.drawable.ic_navigation_car)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                }
                overlays.add(carMarker)
                vehicleMarker = carMarker

                mapViewInstance = this
            }
        },
        update = { mapView ->
            if (cachedGeoPoints.isNotEmpty() && currentIndex in cachedGeoPoints.indices) {
                val geoPt = cachedGeoPoints[currentIndex]
                val currentPt = points[currentIndex]

                vehicleMarker?.position = geoPt
                vehicleMarker?.rotation = 360f - currentPt.bearing

                val playedPts = cachedGeoPoints.subList(0, currentIndex + 1)
                trailPolyline?.setPoints(playedPts)

                mapView.invalidate()
            }
        }
    )
}

// ==================== TIMELINE & STATS GENERATORS ====================

private fun generateTripTimeline(
    trip: TripEntity,
    points: List<TripPointEntity>,
    transitions: List<VehicleStateTransitionEntity> = emptyList(),
    fuelEvents: List<FuelLedgerEntity> = emptyList()
): List<TimelineEvent> {
    val events = mutableListOf<TimelineEvent>()
    val startTime = trip.startTime
    val endTime = trip.endTime.coerceAtLeast(startTime)

    events.add(TimelineEvent.TripStart(startTime, trip.startLocationName))

    // Motion started
    val motionPt = points.firstOrNull { it.speedKmh >= 5.0f }
    if (motionPt != null && motionPt.timestamp > startTime) {
        events.add(TimelineEvent.MotionStarted(motionPt.timestamp, motionPt.speedKmh))
    }

    // Detect idle/stopped intervals (>= 30 seconds below 3 km/h)
    var idleStart: TripPointEntity? = null
    for (i in 1 until points.size) {
        val pt = points[i]
        if (pt.speedKmh < 3.0f) {
            if (idleStart == null) idleStart = pt
        } else {
            if (idleStart != null) {
                val idleDur = pt.timestamp - idleStart.timestamp
                if (idleDur >= 30_000L) {
                    events.add(TimelineEvent.VehicleIdle(idleStart.timestamp, idleDur))
                    events.add(TimelineEvent.MotionResumed(pt.timestamp, pt.speedKmh))
                }
                idleStart = null
            }
        }
    }

    // Refuel events recorded during this trip
    for (fe in fuelEvents) {
        if (fe.eventType == "REFUEL" || fe.eventType == "INITIAL_FUEL_BASELINE") {
            events.add(TimelineEvent.RefuelRecorded(fe.timestamp, fe.fuelQuantityLiters, fe.totalAmount, fe.locationName))
        }
    }

    // Verified Engine Power transitions
    for (tr in transitions) {
        if (tr.toState == "ENGINE_OFF") {
            events.add(TimelineEvent.EnginePowerEvent(tr.timestamp, "Engine Powered Off", "Ignition severed (${tr.triggerReason})"))
        } else if (tr.fromState == "ENGINE_OFF" && tr.toState == "IDLE") {
            events.add(TimelineEvent.EnginePowerEvent(tr.timestamp, "Engine Ignition Restored", "Electrical system active"))
        }
    }

    // Peak speed event
    val maxPt = points.maxByOrNull { it.speedKmh }
    if (maxPt != null && maxPt.speedKmh >= 20.0f) {
        events.add(TimelineEvent.PeakSpeed(maxPt.timestamp, maxPt.speedKmh))
    }

    // Trip Completed event
    val endPtTime = if (points.isNotEmpty()) points.last().timestamp else endTime
    events.add(TimelineEvent.TripEnd(endPtTime, trip.endLocationName, trip.distanceMeters / 1000.0))

    return events.sortedBy { it.timestamp }
}

private data class SpeedBand(val label: String, val percent: Int, val color: Color)

private fun computeSpeedBands(points: List<TripPointEntity>): List<SpeedBand> {
    if (points.isEmpty()) {
        return listOf(
            SpeedBand("0-20", 0, Color(0xFFF59E0B)),
            SpeedBand("20-40", 0, Color(0xFF38BDF8)),
            SpeedBand("40-60", 0, Color(0xFF10B981)),
            SpeedBand("60+", 0, Color(0xFFA855F7))
        )
    }
    var b1 = 0
    var b2 = 0
    var b3 = 0
    var b4 = 0
    points.forEach { pt ->
        val s = pt.speedKmh
        when {
            s < 20f -> b1++
            s < 40f -> b2++
            s < 60f -> b3++
            else -> b4++
        }
    }
    val n = points.size.toFloat()
    return listOf(
        SpeedBand("0-20", ((b1 / n) * 100).roundToInt(), Color(0xFFF59E0B)),
        SpeedBand("20-40", ((b2 / n) * 100).roundToInt(), Color(0xFF38BDF8)),
        SpeedBand("40-60", ((b3 / n) * 100).roundToInt(), Color(0xFF10B981)),
        SpeedBand("60+", ((b4 / n) * 100).roundToInt(), Color(0xFFA855F7))
    )
}

private fun computeYearlyTripAnalytics(
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

// ==================== UTILITY DRAWABLE & STRING HELPERS ====================

private fun formatDurationHoursMinutes(durationMs: Long): String {
    return com.antigravity.copilot.core.trip.TimeFormatUtils.formatAdaptiveDuration(durationMs)
}

private fun formatDurationShort(durationMs: Long): String {
    val totalSec = durationMs / 1000L
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}

private fun createTripFlagDrawable(context: android.content.Context, text: String, bgColor: Int): Drawable {
    val density = context.resources.displayMetrics.density
    val width = (46 * density).toInt()
    val height = (24 * density).toInt()
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = bgColor
        style = Paint.Style.FILL
    }
    val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
    canvas.drawRoundRect(rect, 8 * density, 8 * density, paint)

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 10 * density
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    val textY = (height / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
    canvas.drawText(text, width / 2f, textY, textPaint)

    return BitmapDrawable(context.resources, bitmap)
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
                fontSize = 9.sp,
                fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
            ),
            color = color
        )
    }
}
