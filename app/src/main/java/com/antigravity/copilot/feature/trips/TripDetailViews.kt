package com.antigravity.copilot.feature.trips

import com.antigravity.copilot.core.designsystem.components.contentColorOn
import com.antigravity.copilot.core.designsystem.components.cockpitSurface
import com.antigravity.copilot.core.designsystem.tokens.Palette
import com.antigravity.copilot.core.designsystem.tokens.CopilotShapes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.antigravity.copilot.core.designsystem.AutomotiveDesignTokens
import com.antigravity.copilot.core.database.TripEntity
import com.antigravity.copilot.core.database.VehicleStateTransitionEntity
import com.antigravity.copilot.core.database.FuelLedgerEntity
import com.antigravity.copilot.core.util.NumberFormatUtils
import com.antigravity.copilot.core.database.TripPointEntity
import com.antigravity.copilot.core.designsystem.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max

@Composable
internal fun TripDetailContainer(
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
            .cockpitSurface(CockpitTheme.colors.cardElevated, Palette.Ink750, CopilotShapes.R16, borderWidth = 1.2.dp)
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
                    tint = Palette.Sky400.copy(alpha = 0.6f),
                    modifier = Modifier.size(56.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Trip GPS Playback View",
                    style = CockpitTheme.typography.titleLarge,
                    color = CockpitTheme.colors.textPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Select any journey on the left to start fast-forward animated route replay.",
                    style = CockpitTheme.typography.bodyMedium,
                    color = Palette.Steel400
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top Detail Tabs Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(10f)
                        .background(CockpitTheme.colors.border)
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Tab Selector
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TripDetailTab.values().forEach { tab ->
                            val isSelected = tab == selectedTab
                            Row(
                                modifier = Modifier
                                    .height(46.dp)
                                    .clip(CopilotShapes.R10)
                                    .background(if (isSelected) Palette.Ink600 else CockpitTheme.colors.border)
                                    .border(
                                        width = 1.25.dp,
                                        color = if (isSelected) Palette.Sky400 else Palette.Ink750,
                                        shape = CopilotShapes.R10
                                    )
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = rememberRipple(color = Palette.Sky400),
                                        role = Role.Button,
                                        onClick = { onSelectTab(tab) }
                                    )
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                            ) {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = null,
                                    tint = if (isSelected) Palette.Sky400 else Palette.Steel400,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = tab.title,
                                    style = CockpitTheme.typography.labelMedium.copy(
                                        fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                                    ),
                                    color = if (isSelected) CockpitTheme.colors.textPrimary else Palette.Steel400
                                )
                            }
                        }
                    }

                    // Trip quick badge + Delete Option (badges ellipsize; delete always fits)
                    Row(
                        modifier = Modifier.weight(1f).padding(start = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
                    ) {
                        Text(
                            maxLines = 1,
                            softWrap = false,
                            text = "${NumberFormatUtils.formatDistanceWithUnit(trip.distanceMeters / 1000.0, 2)} • ${NumberFormatUtils.formatSpeedWithUnit(trip.avgSpeedKmh, 2)}",
                            style = CockpitTheme.typography.labelSmall.copy(
                                fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                            ),
                            color = Palette.Sky400
                        )
                        // Trip-history extras: moving/idle split, brake/accel counts, best mileage.
                        Text(
                            modifier = Modifier.weight(1f, fill = false),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            text = "move ${trip.drivingDurationMs / 60000}m • idle ${trip.stationaryDurationMs / 60000}m • " +
                                "${trip.brakeCount} brakes • ${trip.accelCount} accels" +
                                (if (trip.bestMileageKmpl > 0.0) " • best ${NumberFormatUtils.formatPrecision(trip.bestMileageKmpl, 1)} km/L" else ""),
                            style = CockpitTheme.typography.labelSmall.copy(
                                fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                            ),
                            color = Palette.Steel400
                        )

                        if (onDeleteTrip != null) {
                            IconButton(
                                onClick = { showDeleteConfirm = true },
                                modifier = Modifier
                                    .size(48.dp)
                                    .cockpitSurface(CockpitTheme.colors.critical.copy(alpha = 0.14f), Palette.Red500.copy(alpha = 0.5f), CircleShape, borderWidth = 1.2.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "Delete Trip",
                                    tint = Palette.Red500,
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
                                color = CockpitTheme.colors.textPrimary
                            )
                        },
                        text = {
                            Text(
                                text = "Permanently remove this trip and its recorded GPS breadcrumbs? This action cannot be undone.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Palette.Slate400
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showDeleteConfirm = false
                                    onDeleteTrip?.invoke(trip.id)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Palette.Red500)
                            ) {
                                Text("Delete", fontWeight = FontWeight.Bold, color = contentColorOn(Palette.Red500))
                            }
                        },
                        dismissButton = {
                            OutlinedButton(
                                onClick = { showDeleteConfirm = false }
                            ) {
                                Text("Cancel", color = CockpitTheme.colors.textPrimary)
                            }
                        },
                        containerColor = Palette.Slate900
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

@Composable
internal fun TripMapReplayView(
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

    var mapLayer by remember { mutableStateOf(TripMapLayer.OVERLAP) }

    if (isPointsLoading) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Palette.Sky400, modifier = Modifier.size(36.dp))
        }
    } else if (points.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "No GPS route points recorded for this journey.",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Steel400
            )
        }
    } else {
      BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val sidePanel = maxWidth >= 640.dp
      Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // Map (with layer toggle) on top, playback controls strictly beneath it
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 1. TOP: Map viewport - 100% visible, fully interactive, never blocked by controls
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(CopilotShapes.R12)
                    .border(1.dp, Palette.Ink750, CopilotShapes.R12)
            ) {
                TripPlaybackMapView(
                    points = points,
                    currentIndex = currentIndex,
                    layer = mapLayer,
                    modifier = Modifier.fillMaxSize()
                )

                com.antigravity.copilot.core.designsystem.components.SegmentedToggle(
                    options = visibleTripLayers.map { it to it.label },
                    selected = mapLayer,
                    onSelect = { mapLayer = it },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                )

                // Top Status HUD
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(CopilotShapes.R8)
                        .background(CockpitTheme.colors.cardElevated.copy(alpha = 0.92f))
                        .border(1.dp, Palette.Ink750, CopilotShapes.R8)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val statusDotColor = when (playbackStatus) {
                        PlaybackStatus.PLAYING -> Palette.Emerald500
                        PlaybackStatus.PAUSED -> Palette.Amber500
                        PlaybackStatus.COMPLETED -> Palette.Sky400
                        PlaybackStatus.STOPPED -> Palette.Steel400
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
                        style = CockpitTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Black,
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                        ),
                        color = statusDotColor
                    )

                    Text(
                        text = "• ${NumberFormatUtils.formatSpeedWithUnit(currentPt?.speedKmh ?: 0f, 2)}",
                        style = CockpitTheme.typography.labelSmall.copy(
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                        ),
                        color = CockpitTheme.colors.textPrimary
                    )

                    Text(
                        text = "• ${formatDurationShort(elapsedMs)} / ${formatDurationShort(totalDurationMs)}",
                        style = CockpitTheme.typography.labelSmall.copy(
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                        ),
                        color = Palette.Steel400
                    )
                }
            }

            // 2. BOTTOM: Dedicated Replay Controls Panel BELOW the Map
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .cockpitSurface(CockpitTheme.colors.cardElevated, Palette.Ink750, CopilotShapes.R12)
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
                            thumbColor = Palette.Sky400,
                            activeTrackColor = Palette.Sky600,
                            inactiveTrackColor = Palette.Ink750
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
                                containerColor = if (playbackStatus == PlaybackStatus.PLAYING) Palette.Amber500 else Palette.Sky600,
                                contentColor = CockpitTheme.colors.textPrimary
                            ),
                            shape = CopilotShapes.R8,
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
                                style = CockpitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = contentColorOn(if (playbackStatus == PlaybackStatus.PLAYING) Palette.Amber500 else Palette.Sky600)
                            )
                        }

                        // Right: Point Progress & Playback Speed
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Pt ${currentIndex + 1}/${points.size}",
                                style = CockpitTheme.typography.labelSmall.copy(
                                    fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                                ),
                                color = Palette.Steel400
                            )

                            Box(
                                modifier = Modifier
                                    .cockpitSurface(CockpitTheme.colors.border, Palette.Sky400.copy(alpha = 0.4f), CopilotShapes.R8)
                                    .clickable { onCycleSpeed() }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${playbackSpeed.toInt()}x Speed",
                                    style = CockpitTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Black,
                                        fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                                    ),
                                    color = Palette.Sky400
                                )
                            }
                        }
                    }
                }
            }
        }
        if (sidePanel) {
            TripSummaryPanel(
                trip = trip,
                modifier = Modifier
                    .width(310.dp)
                    .fillMaxHeight()
                    .padding(vertical = 6.dp)
            )
        }
      }
      }
    }
}

@Composable
internal fun TripTimelineView(
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
                color = Palette.Steel400,
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
                                .background(Palette.Ink750)
                        )
                    }

                    // Content Box
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .cockpitSurface(Palette.Ink925, Palette.Ink750, CopilotShapes.R10)
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
                                    style = CockpitTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Black
                                    ),
                                    color = CockpitTheme.colors.textPrimary
                                )
                                Text(
                                    text = event.description,
                                    style = CockpitTheme.typography.bodySmall,
                                    color = Palette.Slate400
                                )
                            }

                            Text(
                                text = timeStr,
                                style = CockpitTheme.typography.labelSmall.copy(
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
