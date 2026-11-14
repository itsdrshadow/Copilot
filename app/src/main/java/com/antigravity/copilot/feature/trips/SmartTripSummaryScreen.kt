package com.antigravity.copilot.feature.trips

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Left Column: Interactive Map + Quick Metrics Dock (~48% width)
                    Column(
                        modifier = Modifier
                            .weight(1.05f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SmartTripMapView(
                            points = report.points,
                            stops = report.drivingStats.stops,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(16.dp))
                        )

                        // Quick Glance 4-Metric Dock under Map
                        QuickGlanceDock(
                            overview = report.overview
                        )
                    }

                    // Right Column: Scrollable Deep-Dive Cards (~52% width)
                    LazyColumn(
                        modifier = Modifier
                            .weight(1.15f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            TripOverviewCard(overview = report.overview)
                        }

                        item {
                            AiTripInsightsCard(
                                insights = report.aiInsights,
                                score = report.overview.maxSpeedKmh
                            )
                        }

                        item {
                            DrivingStatisticsCard(stats = report.drivingStats, overview = report.overview)
                        }

                        item {
                            FuelReportCard(fuelReport = report.fuelReport)
                        }

                        item {
                            TripTimelineCard(events = report.timelineEvents)
                        }
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
                        SmartTripMapView(
                            points = report.points,
                            stops = report.drivingStats.stops,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(16.dp))
                        )
                    }

                    item {
                        QuickGlanceDock(overview = report.overview)
                    }

                    item {
                        TripOverviewCard(overview = report.overview)
                    }

                    item {
                        AiTripInsightsCard(insights = report.aiInsights, score = report.overview.maxSpeedKmh)
                    }

                    item {
                        DrivingStatisticsCard(stats = report.drivingStats, overview = report.overview)
                    }

                    item {
                        FuelReportCard(fuelReport = report.fuelReport)
                    }

                    item {
                        TripTimelineCard(events = report.timelineEvents)
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
                        imageVector = Icons.Default.ArrowBack,
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
                    text = "${report.overview.startLocation} â†’ ${report.overview.endLocation} â€¢ ${report.overview.timeRangeFormatted}",
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
private fun QuickGlanceDock(
    overview: TripOverviewData
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF0F172A))
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
            .padding(vertical = 10.dp, horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        QuickMetricItem(
            label = "DISTANCE",
            value = "%.2f".format(Locale.US, overview.distanceKm),
            unit = "KM",
            color = Color(0xFF00E5FF)
        )

        Divider(
            modifier = Modifier
                .height(28.dp)
                .width(1.dp),
            color = Color(0xFF1E293B)
        )

        QuickMetricItem(
            label = "DURATION",
            value = overview.durationFormatted,
            unit = "",
            color = Color(0xFF38BDF8)
        )

        Divider(
            modifier = Modifier
                .height(28.dp)
                .width(1.dp),
            color = Color(0xFF1E293B)
        )

        QuickMetricItem(
            label = "AVG SPEED",
            value = "%.1f".format(Locale.US, overview.avgSpeedKmh),
            unit = "KM/H",
            color = Color(0xFF10B981)
        )

        Divider(
            modifier = Modifier
                .height(28.dp)
                .width(1.dp),
            color = Color(0xFF1E293B)
        )

        QuickMetricItem(
            label = "PEAK SPEED",
            value = "%.1f".format(Locale.US, overview.maxSpeedKmh),
            unit = "KM/H",
            color = Color(0xFFF59E0B)
        )
    }
}

@Composable
private fun QuickMetricItem(
    label: String,
    value: String,
    unit: String,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                letterSpacing = 0.6.sp
            ),
            color = Color(0xFF64748B)
        )
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Black,
                    fontSize = 17.sp,
                    fontFamily = FontFamily.Monospace
                ),
                color = color
            )
            if (unit.isNotBlank()) {
                Text(
                    text = unit,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp
                    ),
                    color = Color(0xFF94A3B8),
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun TripOverviewCard(overview: TripOverviewData) {
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
                Text(
                    text = "TRIP ROUTE & DWELL OVERVIEW",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Black,
                        fontSize = 10.5.sp,
                        letterSpacing = 0.8.sp
                    ),
                    color = Color(0xFF38BDF8)
                )
                Text(
                    text = "${overview.stopCount} STOPS DETECTED",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.5.sp,
                        color = Color(0xFFF59E0B)
                    )
                )
            }

            // Route Corridor
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1E293B).copy(alpha = 0.5f))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.DirectionsCar,
                    contentDescription = null,
                    tint = Color(0xFF00E5FF),
                    modifier = Modifier.size(24.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "FROM: ${overview.startLocation}",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                    Text(
                        text = "TO: ${overview.endLocation}",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFF00E676)
                    )
                }
            }

            // Moving vs Stopped Time Progress Split Bar
            val movingMin = overview.drivingDurationMs / 60000L
            val stoppedMin = overview.stationaryDurationMs / 60000L
            val totalMin = kotlin.math.max(1L, movingMin + stoppedMin)
            val movingRatio = (movingMin.toFloat() / totalMin.toFloat()).coerceIn(0.05f, 0.95f)

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Moving: ${movingMin}m",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFF10B981)
                    )
                    Text(
                        text = "Stopped: ${stoppedMin}m",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFFF59E0B)
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .weight(movingRatio)
                            .fillMaxHeight()
                            .background(Color(0xFF10B981))
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f - movingRatio)
                            .fillMaxHeight()
                            .background(Color(0xFFF59E0B))
                    )
                }
            }
        }
    }
}

@Composable
private fun AiTripInsightsCard(
    insights: List<AiInsightItem>,
    score: Double
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B132B)),
        border = CardDefaults.outlinedCardBorder().copy(brush = Brush.horizontalGradient(listOf(Color(0xFF00E5FF).copy(alpha = 0.4f), Color(0xFF6366F1).copy(alpha = 0.4f))))
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
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = "AI",
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "AI SMART TRIP INSIGHTS",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Black,
                            fontSize = 11.sp,
                            letterSpacing = 0.8.sp
                        ),
                        color = Color(0xFF00E5FF)
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF6366F1).copy(alpha = 0.25f))
                        .border(1.dp, Color(0xFF6366F1).copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "DETERMINISTIC EDGE AI",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 8.5.sp
                        ),
                        color = Color(0xFF818CF8)
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                insights.forEach { item ->
                    InsightRowItem(item = item)
                }
            }
        }
    }
}

@Composable
private fun InsightRowItem(item: AiInsightItem) {
    val categoryIcon = when (item.category) {
        InsightCategory.EFFICIENCY -> Icons.Default.EnergySavingsLeaf
        InsightCategory.SPEED -> Icons.Default.Speed
        InsightCategory.STATIONARY -> Icons.Default.HourglassBottom
        InsightCategory.HISTORICAL -> Icons.Default.QueryStats
        InsightCategory.TERRAIN -> Icons.Default.Terrain
        InsightCategory.RECOMMENDATION -> Icons.Default.TipsAndUpdates
    }

    val iconTint = if (item.isPositive) Color(0xFF10B981) else Color(0xFFF59E0B)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0F172A).copy(alpha = 0.7f))
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(10.dp))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(iconTint.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = categoryIcon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(16.dp)
            )
        }

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(iconTint.copy(alpha = 0.18f))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = item.badgeLabel,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Black,
                            fontSize = 9.sp
                        ),
                        color = iconTint
                    )
                }
            }

            Text(
                text = item.description,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
                color = Color(0xFF94A3B8)
            )
        }
    }
}

@Composable
private fun DrivingStatisticsCard(
    stats: DrivingStatsData,
    overview: TripOverviewData
) {
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
                Text(
                    text = "DRIVING TELEMETRY & GPS INTEGRITY",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Black,
                        fontSize = 10.5.sp,
                        letterSpacing = 0.8.sp
                    ),
                    color = Color(0xFF38BDF8)
                )

                // GPS Quality Rating Pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF10B981).copy(alpha = 0.18f))
                        .border(1.dp, Color(0xFF10B981).copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "GPS: ${stats.gpsQuality.qualityRating} (${stats.gpsQuality.highAccuracyPercentage}%)",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Black,
                            fontSize = 8.5.sp
                        ),
                        color = Color(0xFF10B981)
                    )
                }
            }

            // Speed Distribution Bands
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "SPEED DISTRIBUTION PROFILE",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                        color = Color(0xFF64748B)
                    )
                )

                stats.speedBands.forEach { band ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${band.label} (${band.speedRangeKmh})",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp),
                            color = Color(0xFFCBD5E1)
                        )
                        Text(
                            text = "${band.percentage}%",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            ),
                            color = Color(0xFF38BDF8)
                        )
                    }

                    LinearProgressIndicator(
                        progress = { band.percentage / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = Color(0xFF38BDF8),
                        trackColor = Color(0xFF1E293B)
                    )
                }
            }

            // Elevation and Missing GPS row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1E293B).copy(alpha = 0.4f))
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "ELEVATION PROFILE",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp, color = Color(0xFF64748B))
                    )
                    if (stats.isElevationAvailable) {
                        Text(
                            text = "+%.0fm / -%.0fm".format(Locale.US, stats.elevationGainMeters ?: 0.0, stats.elevationLossMeters ?: 0.0),
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                    } else {
                        Text(
                            text = "Flat / Unreliable",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Normal),
                            color = Color(0xFF94A3B8)
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "SIGNAL COVERAGE",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp, color = Color(0xFF64748B))
                    )
                    Text(
                        text = if (stats.gpsQuality.hasMissingSegments) "Tunnel / Outages Detected" else "100% Continuous Fixes",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        color = if (stats.gpsQuality.hasMissingSegments) Color(0xFFF59E0B) else Color(0xFF10B981)
                    )
                }
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
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp, color = Color(0xFF64748B))
                    )
                    Text(
                        text = "%.2f L".format(Locale.US, fuelReport.estimatedFuelConsumedLiters),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace),
                        color = Color.White
                    )
                }

                Divider(modifier = Modifier.height(28.dp).width(1.dp), color = Color(0xFF334155))

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "TRIP MILEAGE",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp, color = Color(0xFF64748B))
                    )
                    Text(
                        text = "%.1f KM/L".format(Locale.US, fuelReport.calculatedMileageKmpl),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace),
                        color = Color(0xFF10B981)
                    )
                }

                Divider(modifier = Modifier.height(28.dp).width(1.dp), color = Color(0xFF334155))

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "EST. TRIP COST",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp, color = Color(0xFF64748B))
                    )
                    Text(
                        text = "â‚¹%.0f".format(Locale.US, fuelReport.estimatedTripCost),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace),
                        color = Color(0xFFF59E0B)
                    )
                }
            }

            Text(
                text = "â€¢ ${fuelReport.measurementMethodLabel}",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = Color(0xFF64748B)
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
                    fontSize = 10.5.sp,
                    letterSpacing = 0.8.sp
                ),
                color = Color(0xFF38BDF8)
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val timeFmt = remember { java.text.SimpleDateFormat("hh:mm a", Locale.US) }
                events.forEachIndexed { idx, item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = timeFmt.format(java.util.Date(item.timestamp)),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.5.sp
                            ),
                            color = Color(0xFF64748B),
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
