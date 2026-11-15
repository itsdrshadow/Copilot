package com.antigravity.copilot.feature.trips

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.copilot.core.trip.*
import com.antigravity.copilot.core.designsystem.AutomotiveDesignTokens
import com.antigravity.copilot.core.designsystem.CockpitTheme
import java.util.Locale

@Composable
fun SmartTripSummaryScreen(
    report: SmartTripReport?,
    onBackToCockpit: () -> Unit,
    onOpenTripHistory: () -> Unit = {},
    onOpenFuelHistory: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    BackHandler {
        onBackToCockpit()
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF070B12))
    ) {
        val isLandscape = maxWidth > maxHeight

        if (report == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(
                        color = Color(0xFF00E5FF),
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(36.dp)
                    )
                    Text(
                        text = "Loading Smart Trip Summary...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF94A3B8)
                    )
                }
            }
            return@BoxWithConstraints
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp)
        ) {
            // ==================== 1. AUTOMOTIVE TOP HEADER ====================
            TopSummaryHeader(
                report = report,
                onBack = onBackToCockpit,
                onOpenTrips = onOpenTripHistory,
                onOpenFuel = onOpenFuelHistory
            )

            Spacer(modifier = Modifier.height(10.dp))

            // ==================== 2. MAIN REPORT BODY ====================
            if (isLandscape) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Left Column: Primary Telemetry + Motion Breakdown (~50% width)
                    Column(
                        modifier = Modifier
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        PrimaryTripMetricsCard(overview = report.overview)

                        MotionAndDriverRatingCard(
                            overview = report.overview,
                            stats = report.drivingStats
                        )
                    }

                    // Right Column: Fuel & Cost + Route Corridor & Milestones (~50% width)
                    Column(
                        modifier = Modifier
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FuelAndEfficiencyCard(
                            fuelReport = report.fuelReport,
                            stats = report.drivingStats
                        )

                        TripCorridorAndDetailsCard(
                            overview = report.overview,
                            gpsQuality = report.drivingStats.gpsQuality,
                            fuelReport = report.fuelReport,
                            events = report.timelineEvents,
                            aiInsights = report.aiInsights
                        )
                    }
                }
            } else {
                // Portrait Layout
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        TripCorridorDock(
                            overview = report.overview,
                            gpsQuality = report.drivingStats.gpsQuality
                        )
                    }

                    item {
                        PrimaryTripMetricsCard(overview = report.overview)
                    }

                    item {
                        MotionAndDriverRatingCard(
                            overview = report.overview,
                            stats = report.drivingStats
                        )
                    }

                    item {
                        FuelAndEfficiencyCard(
                            fuelReport = report.fuelReport,
                            stats = report.drivingStats
                        )
                    }

                    if (report.fuelReport.hasRefuel) {
                        item {
                            FuelReportCard(fuelReport = report.fuelReport)
                        }
                    }

                    if (report.timelineEvents.isNotEmpty()) {
                        item {
                            TripTimelineCard(events = report.timelineEvents)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopSummaryHeader(
    report: SmartTripReport,
    onBack: () -> Unit,
    onOpenTrips: () -> Unit,
    onOpenFuel: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF0F172A))
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Back to Cockpit Action
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1E293B))
                    .clickable { onBack() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "COCKPIT",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        ),
                        color = Color.White
                    )
                }
            }

            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "SMART TRIP SUMMARY",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.2.sp,
                            fontSize = 16.5.sp
                        ),
                        color = Color.White
                    )

                    // Completion Pill
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF00E676).copy(alpha = 0.18f))
                            .border(1.dp, Color(0xFF00E676).copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "COMPLETED",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Black,
                                fontSize = 9.sp,
                                letterSpacing = 0.5.sp
                            ),
                            color = Color(0xFF00E676)
                        )
                    }
                }

                Text(
                    text = "${report.overview.startLocation.ifBlank { "Origin" }} - ${report.overview.endLocation.ifBlank { "Destination" }} | ${report.overview.timeRangeFormatted}",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 11.5.sp
                    ),
                    color = Color(0xFF94A3B8),
                    maxLines = 1
                )
            }
        }

        // Right Quick Actions: History Buttons
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1E293B).copy(alpha = 0.8f))
                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(8.dp))
                    .clickable { onOpenTrips() }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "TRIP ARCHIVE",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    ),
                    color = Color(0xFF38BDF8)
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1E293B).copy(alpha = 0.8f))
                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(8.dp))
                    .clickable { onOpenFuel() }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "FUEL LEDGER",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    ),
                    color = Color(0xFFF59E0B)
                )
            }
        }
    }
}

@Composable
private fun TripCorridorAndDetailsCard(
    overview: TripOverviewData,
    gpsQuality: GpsQualityData,
    fuelReport: FuelReportData,
    events: List<TripTimelineItem>,
    aiInsights: List<AiInsightItem>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.verticalGradient(listOf(Color(0xFF1E293B), Color(0xFF0F172A)))
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header: Title + GPS Status Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DirectionsCar,
                        contentDescription = "Corridor",
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "ROUTE CORRIDOR & MILESTONES",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Black,
                            fontSize = 11.sp,
                            letterSpacing = 0.8.sp
                        ),
                        color = Color(0xFF38BDF8)
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF10B981).copy(alpha = 0.16f))
                        .border(1.dp, Color(0xFF10B981).copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "GPS: ${gpsQuality.qualityRating}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Black,
                            fontSize = 10.sp
                        ),
                        color = Color(0xFF10B981)
                    )
                }
            }

            // Route Origin -> Destination Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF142036))
                    .border(1.dp, Color(0xFF1E2F4A), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${overview.startLocation.ifBlank { "Origin" }} → ${overview.endLocation.ifBlank { "Destination" }}",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        ),
                        color = Color.White,
                        maxLines = 1
                    )
                    Text(
                        text = "${overview.departureTimeFormatted} – ${overview.arrivalTimeFormatted} • ${overview.stopCount} stops",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        ),
                        maxLines = 1
                    )
                }
            }

            // Refuel Event Highlight (if any)
            if (fuelReport.hasRefuel) {
                fuelReport.refuelEvents.forEach { rf ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF10B981).copy(alpha = 0.12f))
                            .border(1.dp, Color(0xFF10B981).copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(16.dp)
                        )
                        Column {
                            Text(
                                text = "Refuelled %.1f L at %s (Total: ₹%.0f)".format(Locale.US, rf.liters, rf.timeFormatted, rf.totalCost),
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                            Text(
                                text = "Station: %s • ₹%.2f/L".format(Locale.US, rf.locationName, rf.pricePerLiter),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }
                }
            }

            // Chronological Key Milestones
            if (events.isNotEmpty()) {
                val timeFmt = remember { java.text.SimpleDateFormat("hh:mm a", Locale.US) }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    events.take(3).forEach { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = timeFmt.format(java.util.Date(item.timestamp)),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily,
                                    fontFeatureSettings = "tnum",
                                    fontSize = 11.sp
                                ),
                                color = Color(0xFF94A3B8),
                                modifier = Modifier.width(62.dp)
                            )

                            val dotColor = when (item.type) {
                                TimelineType.START -> Color(0xFF00E676)
                                TimelineType.MOTION -> Color(0xFF00E5FF)
                                TimelineType.STOP -> Color(0xFFF59E0B)
                                TimelineType.REFUEL -> Color(0xFF10B981)
                                TimelineType.PEAK_SPEED -> Color(0xFFEC4899)
                                TimelineType.COMPLETE -> Color(0xFF38BDF8)
                            }

                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(dotColor)
                            )

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.5.sp
                                    ),
                                    color = Color.White,
                                    maxLines = 1
                                )
                                Text(
                                    text = item.subtitle,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = Color(0xFF94A3B8),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            // Smart Driver Insights Banner (if present)
            if (aiInsights.isNotEmpty()) {
                val insight = aiInsights.first()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF00E5FF).copy(alpha = 0.08f))
                        .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "${insight.title}: ${insight.description}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.5.sp,
                            color = Color(0xFFE2E8F0)
                        ),
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun TripCorridorDock(
    overview: TripOverviewData,
    gpsQuality: GpsQualityData
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF0F172A))
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = Icons.Default.DirectionsCar,
                contentDescription = null,
                tint = Color(0xFF38BDF8),
                modifier = Modifier.size(20.dp)
            )
            Column {
                Text(
                    text = "${overview.startLocation.ifBlank { "Origin" }} - ${overview.endLocation.ifBlank { "Destination" }}",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    ),
                    color = Color.White,
                    maxLines = 1
                )
                Text(
                    text = "${overview.departureTimeFormatted} – ${overview.arrivalTimeFormatted}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8)
                    ),
                    maxLines = 1
                )
            }
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF10B981).copy(alpha = 0.16f))
                .border(1.dp, Color(0xFF10B981).copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = "GPS: ${gpsQuality.qualityRating}",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Black,
                    fontSize = 10.sp
                ),
                color = Color(0xFF10B981)
            )
        }
    }
}

@Composable
private fun PrimaryTripMetricsCard(overview: TripOverviewData) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
        border = CardDefaults.outlinedCardBorder().copy(brush = Brush.verticalGradient(listOf(Color(0xFF1E293B), Color(0xFF0F172A))))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Row 1: TOTAL TRIP KM & TRIP TIME
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                BigMetricTile(
                    label = "TOTAL TRIP KM",
                    value = "%.2f".format(Locale.US, overview.distanceKm),
                    unit = "KM",
                    valueColor = Color(0xFF38BDF8),
                    modifier = Modifier.weight(1f)
                )

                BigMetricTile(
                    label = "TRIP TIME",
                    value = overview.durationFormatted,
                    unit = "",
                    valueColor = Color(0xFFFFFFFF),
                    modifier = Modifier.weight(1f)
                )
            }

            // Row 2: AVG SPEED & MAX SPEED
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                BigMetricTile(
                    label = "AVG SPEED",
                    value = "%.1f".format(Locale.US, overview.avgSpeedKmh),
                    unit = "KM/H",
                    valueColor = Color(0xFF10B981),
                    modifier = Modifier.weight(1f)
                )

                BigMetricTile(
                    label = "MAX SPEED",
                    value = "%.1f".format(Locale.US, overview.maxSpeedKmh),
                    unit = "KM/H",
                    valueColor = Color(0xFFF59E0B),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun BigMetricTile(
    label: String,
    value: String,
    unit: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF142036))
            .border(1.dp, Color(0xFF1E2F4A), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Black,
                    fontSize = 11.sp,
                    letterSpacing = 0.8.sp
                ),
                color = Color(0xFF94A3B8),
                maxLines = 1
            )
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontWeight = FontWeight.Black,
                        fontSize = 42.sp,
                        fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily,
                        fontFeatureSettings = "tnum"
                    ),
                    color = valueColor,
                    maxLines = 1
                )
                if (unit.isNotBlank()) {
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Black,
                            fontSize = 14.sp
                        ),
                        color = valueColor.copy(alpha = 0.8f),
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MotionAndDriverRatingCard(
    overview: TripOverviewData,
    stats: DrivingStatsData
) {
    val movingMin = overview.drivingDurationMs / 60000L
    val stoppedMin = overview.stationaryDurationMs / 60000L
    val totalMin = kotlin.math.max(1L, movingMin + stoppedMin)
    val driverScore = (100 - (overview.stationaryDurationMs.toFloat() / (totalMin * 60000L) * 30).toInt()).coerceIn(65, 100)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
        border = CardDefaults.outlinedCardBorder().copy(brush = Brush.verticalGradient(listOf(Color(0xFF1E293B), Color(0xFF0F172A))))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "MOTION & TIME BREAKDOWN",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Black,
                    fontSize = 11.sp,
                    letterSpacing = 0.8.sp
                ),
                color = Color(0xFF38BDF8)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MediumMetricTile(
                    label = "DRIVING TIME",
                    value = "${movingMin}m",
                    sub = "${stats.movingPercentage}% moving",
                    valueColor = Color(0xFF10B981),
                    modifier = Modifier.weight(1f)
                )

                MediumMetricTile(
                    label = "STOPPED / IDLE",
                    value = "${stoppedMin}m",
                    sub = "${overview.stopCount} stops (${stats.stoppedPercentage}%)",
                    valueColor = Color(0xFFF59E0B),
                    modifier = Modifier.weight(1f)
                )

                MediumMetricTile(
                    label = "DRIVER SCORE",
                    value = "$driverScore",
                    sub = "/ 100 score",
                    valueColor = Color(0xFF00E5FF),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun FuelAndEfficiencyCard(
    fuelReport: FuelReportData,
    stats: DrivingStatsData
) {
    val cruisingBand = stats.speedBands.firstOrNull { it.label.contains("Cruising", ignoreCase = true) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
        border = CardDefaults.outlinedCardBorder().copy(brush = Brush.verticalGradient(listOf(Color(0xFF1E293B), Color(0xFF0F172A))))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.LocalGasStation,
                        contentDescription = "Fuel",
                        tint = Color(0xFFF59E0B),
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "FUEL CONSUMPTION & RUNNING COST",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Black,
                            fontSize = 11.sp,
                            letterSpacing = 0.8.sp
                        ),
                        color = Color(0xFFF59E0B)
                    )
                }

                if (cruisingBand != null) {
                    Text(
                        text = "Cruising: ${cruisingBand.percentage}%",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            color = Color(0xFF38BDF8)
                        )
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MediumMetricTile(
                    label = "EST. FUEL BURNT",
                    value = "%.2fL".format(Locale.US, fuelReport.estimatedFuelConsumedLiters),
                    sub = "Estimated burn",
                    valueColor = Color(0xFFFB923C),
                    modifier = Modifier.weight(1f)
                )

                MediumMetricTile(
                    label = "TRIP MILEAGE",
                    value = "%.1f".format(Locale.US, fuelReport.calculatedMileageKmpl),
                    sub = "KM/L economy",
                    valueColor = Color(0xFF10B981),
                    modifier = Modifier.weight(1f)
                )

                MediumMetricTile(
                    label = "EST. TRIP COST",
                    value = "₹%.0f".format(Locale.US, fuelReport.estimatedTripCost),
                    sub = "Running cost",
                    valueColor = Color(0xFFFBBF24),
                    modifier = Modifier.weight(1f)
                )
            }

            Text(
                text = "• ${fuelReport.measurementMethodLabel}  • Cruising band: ${cruisingBand?.speedRangeKmh ?: "60-80 km/h"}",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                color = Color(0xFF94A3B8)
            )
        }
    }
}

@Composable
private fun MediumMetricTile(
    label: String,
    value: String,
    sub: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF142036))
            .border(1.dp, Color(0xFF1E2F4A), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Black,
                    fontSize = 11.5.sp,
                    letterSpacing = 0.6.sp
                ),
                color = Color(0xFF94A3B8),
                maxLines = 1
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Black,
                    fontSize = 28.sp,
                    fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily,
                    fontFeatureSettings = "tnum"
                ),
                color = valueColor,
                maxLines = 1
            )
            if (sub.isNotBlank()) {
                Text(
                    text = sub,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = Color(0xFF64748B),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun FuelReportCard(fuelReport: FuelReportData) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
        border = CardDefaults.outlinedCardBorder().copy(brush = Brush.verticalGradient(listOf(Color(0xFF1E293B), Color(0xFF0F172A))))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.LocalGasStation,
                        contentDescription = "Fuel",
                        tint = Color(0xFFF59E0B),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "FUEL CONSUMPTION & REFUELLING REPORT",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Black,
                            fontSize = 10.5.sp,
                            letterSpacing = 0.8.sp
                        ),
                        color = Color(0xFFF59E0B)
                    )
                }
            }

            // Refuelling Event Display or Empty State
            if (fuelReport.hasRefuel) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    fuelReport.refuelEvents.forEach { rf ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF10B981).copy(alpha = 0.12f))
                                .border(1.dp, Color(0xFF10B981).copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF10B981),
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Text(
                                    text = "Refuelled %.1f L at %s near %s".format(Locale.US, rf.liters, rf.timeFormatted, rf.locationName),
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White
                                )
                                Text(
                                    text = "Fuel Cost: â‚¹%.0f (â‚¹%.2f/L) â€¢ Fuel Type: %s".format(Locale.US, rf.totalCost, rf.pricePerLiter, rf.fuelType),
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = Color(0xFF94A3B8)
                                )
                            }
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1E293B).copy(alpha = 0.4f))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = Color(0xFF64748B),
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "No refuelling recorded during this trip.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8)
                    )
                }
            }

            // Telemetry Consumption Metrics
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1E293B).copy(alpha = 0.5f))
                    .padding(10.dp),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "EST. FUEL BURNT",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
                    )
                    Text(
                        text = "%.2f L".format(Locale.US, fuelReport.estimatedFuelConsumedLiters),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Black,
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily,
                            fontFeatureSettings = "tnum",
                            fontSize = 17.sp
                        ),
                        color = Color.White
                    )
                }

                Box(modifier = Modifier.height(28.dp).width(1.dp).background(Color(0xFF334155)))

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "TRIP MILEAGE",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
                    )
                    Text(
                        text = "%.1f KM/L".format(Locale.US, fuelReport.calculatedMileageKmpl),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Black,
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily,
                            fontFeatureSettings = "tnum",
                            fontSize = 17.sp
                        ),
                        color = Color(0xFF10B981)
                    )
                }

                Box(modifier = Modifier.height(28.dp).width(1.dp).background(Color(0xFF334155)))

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "EST. TRIP COST",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
                    )
                    Text(
                        text = "₹%.0f".format(Locale.US, fuelReport.estimatedTripCost),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Black,
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily,
                            fontFeatureSettings = "tnum",
                            fontSize = 17.sp
                        ),
                        color = Color(0xFFF59E0B)
                    )
                }
            }

            Text(
                text = "• ${fuelReport.measurementMethodLabel}",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp),
                color = Color(0xFF94A3B8)
            )
        }
    }
}

@Composable
private fun TripTimelineCard(events: List<TripTimelineItem>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
        border = CardDefaults.outlinedCardBorder().copy(brush = Brush.verticalGradient(listOf(Color(0xFF1E293B), Color(0xFF0F172A))))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "CHRONOLOGICAL TRIP TIMELINE",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Black,
                    fontSize = 12.sp,
                    letterSpacing = 0.8.sp
                ),
                color = Color(0xFF38BDF8)
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val timeFmt = remember { java.text.SimpleDateFormat("hh:mm a", Locale.US) }
                events.forEachIndexed { _, item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = timeFmt.format(java.util.Date(item.timestamp)),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily,
                                fontFeatureSettings = "tnum",
                                fontSize = 11.5.sp
                            ),
                            color = Color(0xFF94A3B8),
                            modifier = Modifier.width(68.dp)
                        )

                        val dotColor = when (item.type) {
                            TimelineType.START -> Color(0xFF00E676)
                            TimelineType.MOTION -> Color(0xFF00E5FF)
                            TimelineType.STOP -> Color(0xFFF59E0B)
                            TimelineType.REFUEL -> Color(0xFF10B981)
                            TimelineType.PEAK_SPEED -> Color(0xFFEC4899)
                            TimelineType.COMPLETE -> Color(0xFF38BDF8)
                        }

                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                            Text(
                                text = item.subtitle,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }
                }
            }
        }
    }
}
