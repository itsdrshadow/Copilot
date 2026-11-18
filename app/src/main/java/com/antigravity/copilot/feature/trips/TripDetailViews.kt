package com.antigravity.copilot.feature.trips

import com.antigravity.copilot.core.designsystem.components.Raahi

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
import kotlinx.coroutines.launch
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
    fuelPricePerLiter: Double = 96.5,
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
    var showRename by remember { mutableStateOf(false) }
    var showMergeConfirm by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val actionScope = rememberCoroutineScope()
    // GPX export through the system file picker (audit J3).
    val gpxLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { uri ->
        val t = trip
        if (uri != null && t != null) {
            val text = com.antigravity.copilot.core.trip.GpxWriter.write(t, points)
            actionScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val ok = runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
                }.getOrDefault(false)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(context, if (ok) "GPX saved" else "Could not save GPX", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

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
                    text = "Pick a trip",
                    style = CockpitTheme.typography.titleLarge,
                    color = CockpitTheme.colors.textPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Choose a trip on the left to see its route and replay it.",
                    style = CockpitTheme.typography.bodyMedium,
                    color = Palette.Steel400
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header: trip name + time on the left, view dropdown + delete on the right.
                // One slim row instead of a tab strip, so the views get the height.
                TripDetailHeader(
                    trip = trip,
                    selectedTab = selectedTab,
                    onSelectTab = onSelectTab,
                    onDelete = if (onDeleteTrip != null) ({ showDeleteConfirm = true }) else null,
                    onRename = { showRename = true },
                    onMergeNext = { showMergeConfirm = true },
                    onExportGpx = if (points.isNotEmpty()) ({ gpxLauncher.launch(com.antigravity.copilot.core.trip.GpxWriter.fileName(trip)) }) else null
                )

                if (showRename) {
                    TripRenameDialog(
                        start = trip.startLocationName,
                        end = trip.endLocationName,
                        onDismiss = { showRename = false },
                        onSave = { s, e ->
                            showRename = false
                            com.antigravity.copilot.core.trip.TripRepository.getInstance(context).renameTrip(trip.id, s, e)
                        }
                    )
                }

                if (showMergeConfirm) {
                    com.antigravity.copilot.core.designsystem.components.RaahiConfirmDialog(
                        title = "Merge with the next trip?",
                        message = "The next trip's route and totals are added to this one, and it ends where the next trip ended.",
                        confirmText = "Merge",
                        destructive = false,
                        icon = Icons.Default.Route,
                        onConfirm = {
                            showMergeConfirm = false
                            actionScope.launch {
                                val ok = com.antigravity.copilot.core.trip.TripRepository.getInstance(context).mergeWithNext(trip.id)
                                android.widget.Toast.makeText(context, if (ok) "Trips merged" else "No later trip to merge", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        onDismiss = { showMergeConfirm = false }
                    )
                }

                if (showDeleteConfirm) {
                    com.antigravity.copilot.core.designsystem.components.RaahiConfirmDialog(
                        title = "Delete this trip?",
                        message = "The trip and its route will be removed. This cannot be undone.",
                        confirmText = "Delete trip",
                        destructive = true,
                        icon = Icons.Default.DeleteOutline,
                        onConfirm = {
                            showDeleteConfirm = false
                            onDeleteTrip?.invoke(trip.id)
                        },
                        onDismiss = { showDeleteConfirm = false }
                    )
                }

                // T2: the three views as always-visible tabs instead of a hidden dropdown.
                com.antigravity.copilot.core.designsystem.components.SegmentedToggle(
                    options = TripDetailTab.values().map { it to it.title },
                    selected = selectedTab,
                    onSelect = onSelectTab,
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .fillMaxWidth()
                )

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
                                fuelPricePerLiter = fuelPricePerLiter,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Trip name, time range and kind, plus the view selector dropdown and delete. */
@Composable
private fun TripDetailHeader(
    trip: TripEntity,
    selectedTab: TripDetailTab,
    onSelectTab: (TripDetailTab) -> Unit,
    onDelete: (() -> Unit)?,
    onRename: (() -> Unit)? = null,
    onMergeNext: (() -> Unit)? = null,
    onExportGpx: (() -> Unit)? = null
) {
    val c = CockpitTheme.colors
    val title = tripTitle(trip, LocalTripViaNames.current[trip.id])
    val timeText = remember(trip.startTime, trip.endTime) {
        val day = SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date(trip.startTime))
        val t = SimpleDateFormat("h:mm a", Locale.getDefault())
        val end = if (trip.endTime > trip.startTime) " – ${t.format(Date(trip.endTime))}" else ""
        "$day · ${t.format(Date(trip.startTime))}$end"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(10f)
            .background(c.card)
            .padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CopilotShapes.R10)
                .background(c.accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (title.isRoundTrip) Icons.Default.Sync else Icons.Default.Route,
                contentDescription = null,
                tint = c.accent,
                modifier = Modifier.size(24.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = title.title,
                    style = CockpitTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = c.textPrimary,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                title.tag?.let {
                    Text(
                        text = it,
                        style = CockpitTheme.typography.labelMedium,
                        color = c.accentText,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(Raahi.PillShape)
                            .background(c.accent.copy(alpha = 0.12f))
                            .border(1.dp, c.accent.copy(alpha = 0.4f), Raahi.PillShape)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
            Text(
                text = timeText,
                style = CockpitTheme.typography.bodyMedium,
                color = c.textSecondary,
                maxLines = 1
            )
        }

        // Trip edits (audit D6) and GPX export (audit J3).
        onRename?.let {
            IconButton(onClick = it, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Edit, contentDescription = "Rename trip", tint = c.textSecondary, modifier = Modifier.size(22.dp))
            }
        }
        onMergeNext?.let {
            IconButton(onClick = it, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Link, contentDescription = "Merge with next trip", tint = c.textSecondary, modifier = Modifier.size(22.dp))
            }
        }
        onExportGpx?.let {
            IconButton(onClick = it, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Download, contentDescription = "Export GPX", tint = c.textSecondary, modifier = Modifier.size(22.dp))
            }
        }
        if (onDelete != null) {
            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .size(48.dp)
                    .cockpitSurface(c.critical.copy(alpha = 0.14f), Palette.Red500.copy(alpha = 0.5f), CircleShape, borderWidth = 1.2.dp)
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
                text = "No route was saved for this trip.",
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
            // 1. TOP: Map viewport (route drawn once; playback only moves the puck)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(CopilotShapes.R16)
                    .border(1.dp, CockpitTheme.colors.border, CopilotShapes.R16)
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
                        .padding(10.dp)
                )

                // Status HUD: playback state + live speed at the puck
                val statusDotColor = when (playbackStatus) {
                    PlaybackStatus.PLAYING -> CockpitTheme.colors.positive
                    PlaybackStatus.PAUSED -> CockpitTheme.colors.warning
                    PlaybackStatus.COMPLETED -> CockpitTheme.colors.accent
                    PlaybackStatus.STOPPED -> CockpitTheme.colors.textSecondary
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp)
                        .height(44.dp)
                        .clip(Raahi.PillShape)
                        .background(CockpitTheme.colors.card.copy(alpha = 0.92f))
                        .border(1.dp, CockpitTheme.colors.border, Raahi.PillShape)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
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
                        style = CockpitTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = statusDotColor
                    )
                    Text(
                        text = NumberFormatUtils.formatSpeedWithUnit(currentPt?.speedKmh ?: 0f),
                        style = CockpitTheme.typography.headlineSmall,
                        color = CockpitTheme.colors.textPrimary
                    )
                }
            }

            // 2. BOTTOM: replay controls, sized for a car screen (56 dp play button)
            val playColor = if (playbackStatus == PlaybackStatus.PLAYING) CockpitTheme.colors.warning else CockpitTheme.colors.accent
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(76.dp)
                    .clip(CopilotShapes.R16)
                    .background(CockpitTheme.colors.card)
                    .border(1.dp, CockpitTheme.colors.border, CopilotShapes.R16)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(playColor)
                        .clickable(
                            role = androidx.compose.ui.semantics.Role.Button,
                            onClickLabel = when (playbackStatus) {
                                PlaybackStatus.PLAYING -> "Pause"
                                PlaybackStatus.COMPLETED -> "Replay"
                                else -> "Play"
                            },
                            onClick = if (playbackStatus == PlaybackStatus.COMPLETED) onRestart else onPlayPause
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (playbackStatus) {
                            PlaybackStatus.PLAYING -> Icons.Default.Pause
                            PlaybackStatus.COMPLETED -> Icons.Default.Replay
                            else -> Icons.Default.PlayArrow
                        },
                        contentDescription = null,
                        tint = contentColorOn(playColor),
                        modifier = Modifier.size(30.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Slider(
                        value = currentIndex.toFloat(),
                        onValueChange = { onSeek(it.toInt()) },
                        valueRange = 0f..(points.size - 1).coerceAtLeast(1).toFloat(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(32.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = CockpitTheme.colors.accent,
                            activeTrackColor = CockpitTheme.colors.accent,
                            inactiveTrackColor = CockpitTheme.colors.cardElevated
                        )
                    )
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
                        Text(
                            text = formatDurationShort(elapsedMs),
                            style = CockpitTheme.typography.labelLarge,
                            color = CockpitTheme.colors.textPrimary
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = formatDurationShort(totalDurationMs),
                            style = CockpitTheme.typography.labelLarge,
                            color = CockpitTheme.colors.textSecondary
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .height(48.dp)
                        .widthIn(min = 64.dp)
                        .clip(Raahi.PillShape)
                        .background(CockpitTheme.colors.accent.copy(alpha = 0.14f))
                        .border(1.dp, CockpitTheme.colors.accent.copy(alpha = 0.5f), Raahi.PillShape)
                        .clickable(onClickLabel = "Change playback speed") { onCycleSpeed() }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${playbackSpeed.toInt()}x",
                        style = CockpitTheme.typography.headlineSmall,
                        color = CockpitTheme.colors.accentText
                    )
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

/** Edit a trip's start / end names (audit D6). */
@Composable
private fun TripRenameDialog(start: String, end: String, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var s by remember { mutableStateOf(if (com.antigravity.copilot.core.trip.TripNaming.isPlaceholder(start)) "" else start) }
    var e by remember { mutableStateOf(if (com.antigravity.copilot.core.trip.TripNaming.isPlaceholder(end)) "" else end) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CockpitTheme.colors.cardElevated,
        title = { Text("Rename trip", style = CockpitTheme.typography.headlineMedium, color = CockpitTheme.colors.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.OutlinedTextField(value = s, onValueChange = { s = it }, singleLine = true, label = { Text("From") })
                androidx.compose.material3.OutlinedTextField(value = e, onValueChange = { e = it }, singleLine = true, label = { Text("To") })
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { onSave(s, e) }) { Text("Save") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
