package com.antigravity.copilot.feature.dashboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.antigravity.copilot.core.designsystem.CockpitTheme
import com.antigravity.copilot.core.designsystem.components.Raahi
import com.antigravity.copilot.core.designsystem.components.RaahiDialogHost
import com.antigravity.copilot.core.designsystem.components.StatCell
import com.antigravity.copilot.core.designsystem.components.VDivider
import com.antigravity.copilot.core.designsystem.components.raahiCard
import com.antigravity.copilot.core.media.MediaPlaybackManager
import com.antigravity.copilot.core.model.TripStatus
import com.antigravity.copilot.core.model.TripSummary
import com.antigravity.copilot.core.odometer.OdometerManager
import com.antigravity.copilot.core.trip.TimeFormatUtils
import com.antigravity.copilot.core.trip.TripVisualLifecycleEvent
import com.antigravity.copilot.core.trip.TripVisualState
import com.antigravity.copilot.core.util.NumberFormatUtils
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun TripSummary.isActive(): Boolean =
    status == TripStatus.ACTIVE_TRIP || status == TripStatus.MOVEMENT_CANDIDATE || status == TripStatus.TEMPORARY_STOP

internal fun TripSummary.isCompleted(): Boolean = !isActive() && (status == TripStatus.COMPLETED || (endTime > 0L && endTime >= startTime))

/** Elapsed trip wall-clock time (live while active, frozen once completed), ticking each second. */
@Composable
internal fun rememberTripElapsedMs(trip: TripSummary): Long {
    val active = trip.isActive()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) {
        while (active) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    return when {
        trip.isCompleted() -> {
            if (trip.endTime > trip.startTime && trip.startTime > 0L) {
                trip.endTime - trip.startTime
            } else {
                (trip.drivingDurationMs + trip.stationaryDurationMs).coerceAtLeast(0L)
            }
        }

        active && trip.startTime > 0L -> {
            (now - trip.startTime).coerceAtLeast(0L)
        }

        else -> {
            0L
        }
    }
}

/**
 * Top row of the Drive panel: trip pill (state + from → to) and a clock pill.
 * The pill also shows the trip time. Long-press it to start / end a trip by hand (trip stats
 * rotate on the map).
 * Tap the clock for the music player.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DriveTopRow(
    currentTrip: TripSummary,
    tripVisualState: TripVisualState,
    onOpenMedia: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = CockpitTheme.colors
    val context = LocalContext.current
    val active = currentTrip.isActive()
    val completed = currentTrip.isCompleted()
    var showManualTripDialog by remember { mutableStateOf(false) }

    val sinceEvent = System.currentTimeMillis() - tripVisualState.eventTimestamp
    val justStarted = tripVisualState.event == TripVisualLifecycleEvent.STARTED && sinceEvent < 10_000L
    val justEnded = tripVisualState.event == TripVisualLifecycleEvent.COMPLETED && sinceEvent < 10_000L

    val elapsed = rememberTripElapsedMs(currentTrip)
    val showTrip = active || completed
    val (stateText, dotColor) =
        when {
            justStarted -> "Trip started" to c.positive
            justEnded -> "Trip ended" to c.critical
            currentTrip.status == TripStatus.TEMPORARY_STOP -> "Paused" to c.warning
            active -> "Live" to c.accent
            completed -> "Last trip" to c.textSecondary
            else -> "Parked" to c.textSecondary
        }
    val from = currentTrip.startLocationName.ifBlank { if (active || completed) "Start" else "" }
    val to = currentTrip.endLocationName.ifBlank { if (active) "now" else "" }
    val route =
        when {
            from.isNotEmpty() && to.isNotEmpty() -> "$from → $to"
            from.isNotEmpty() -> from
            else -> "Hold to start a trip"
        }

    Row(
        modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(Raahi.Gap),
    ) {
        Row(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .heightIn(min = 68.dp)
                    .clip(Raahi.PillShape)
                    .background(c.card)
                    .border(Raahi.Border, if (active) c.accent.copy(alpha = 0.6f) else c.border, Raahi.PillShape)
                    .combinedClickable(
                        role = Role.Button,
                        onLongClickLabel = if (active) "End trip" else "Start trip",
                        // Tap does nothing (no trip popup); long-press is the manual trip control.
                        onClick = {},
                        onLongClick = { showManualTripDialog = true },
                    ).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(dotColor))
            Column(modifier = Modifier.weight(1f)) {
                Text(stateText, style = CockpitTheme.typography.titleMedium, color = c.textPrimary, maxLines = 1)
                Text(
                    route,
                    style = CockpitTheme.typography.bodyMedium,
                    color = c.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Trip time (moved here from the old trip strip).
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (showTrip) TimeFormatUtils.formatLiveTripTimer(elapsed) else "--",
                    style = CockpitTheme.typography.headlineSmall,
                    color = if (active) c.accent else c.textPrimary,
                    maxLines = 1,
                    softWrap = false,
                )
                Text("Trip time", style = CockpitTheme.typography.labelMedium, color = c.textSecondary, maxLines = 1)
            }
        }
        ClockPill(onClick = onOpenMedia, modifier = Modifier.fillMaxHeight())
    }

    if (showManualTripDialog) {
        AlertDialog(
            onDismissRequest = { showManualTripDialog = false },
            title = { Text(if (active) "End trip now?" else "Start trip now?") },
            text = {
                Text(
                    if (active) {
                        "Manually ends the current trip here. Geofences normally do this automatically."
                    } else {
                        "Manually starts a trip now. Geofences normally do this automatically."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showManualTripDialog = false
                    if (active) {
                        com.antigravity.copilot.core.location.DrivingTrackingService
                            .manualEndTrip(context)
                    } else {
                        com.antigravity.copilot.core.location.DrivingTrackingService
                            .manualStartTrip(context)
                    }
                }) { Text(if (active) "End trip" else "Start trip", color = CockpitTheme.colors.textPrimary) }
            },
            dismissButton = {
                TextButton(onClick = { showManualTripDialog = false }) { Text("Cancel", color = CockpitTheme.colors.textPrimary) }
            },
        )
    }
}

@Composable
private fun ClockPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = CockpitTheme.colors
    val playback by MediaPlaybackManager.playbackState.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            // Wake at the next minute boundary: one recomposition per minute, not per second.
            delay(60_000L - now % 60_000L)
        }
    }
    val time = remember(now / 60_000L) { SimpleDateFormat("h:mm", Locale.getDefault()).format(Date(now)) }
    val amPm = remember(now / 60_000L) { SimpleDateFormat("a", Locale.US).format(Date(now)) }
    Row(
        modifier =
            modifier
                .heightIn(min = 68.dp)
                .clip(Raahi.PillShape)
                .background(c.card)
                .border(Raahi.Border, c.border, Raahi.PillShape)
                .clickable(role = Role.Button, onClickLabel = "Music and clock", onClick = onClick)
                .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            if (playback.isMusicActive) Icons.Outlined.MusicNote else Icons.Outlined.Schedule,
            contentDescription = null,
            tint = if (playback.isMusicActive) c.accent else c.textSecondary,
            modifier = Modifier.size(22.dp),
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(time, style = CockpitTheme.typography.headlineLarge, color = c.textPrimary, maxLines = 1, softWrap = false)
            Spacer(Modifier.width(4.dp))
            Text(
                amPm,
                style = CockpitTheme.typography.labelLarge,
                color = c.textSecondary,
                maxLines = 1,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
    }
}

/** Secondary area: music player + clock card. */
@Composable
fun MediaDialog(onDismiss: () -> Unit) {
    RaahiDialogHost(onDismissRequest = onDismiss, maxWidth = 560.dp) {
        SmartMusicClockCard(modifier = Modifier.fillMaxWidth().height(340.dp))
    }
}
