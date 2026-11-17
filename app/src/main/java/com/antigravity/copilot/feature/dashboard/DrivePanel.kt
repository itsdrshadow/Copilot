package com.antigravity.copilot.feature.dashboard

import android.text.format.DateFormat
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

internal fun TripSummary.isCompleted(): Boolean =
    !isActive() && (status == TripStatus.COMPLETED || (endTime > 0L && endTime >= startTime))

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
        trip.isCompleted() ->
            if (trip.endTime > trip.startTime && trip.startTime > 0L) trip.endTime - trip.startTime
            else (trip.drivingDurationMs + trip.stationaryDurationMs).coerceAtLeast(0L)
        active && trip.startTime > 0L -> (now - trip.startTime).coerceAtLeast(0L)
        else -> 0L
    }
}

/**
 * Top row of the Drive panel: trip pill (state + from → to) and a clock pill.
 * Tap the trip pill for the full trip card; long-press it to start / end a trip by hand.
 * Tap the clock for the music player.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DriveTopRow(
    currentTrip: TripSummary,
    tripVisualState: TripVisualState,
    onOpenTripDetails: () -> Unit,
    onOpenMedia: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = CockpitTheme.colors
    val context = LocalContext.current
    val active = currentTrip.isActive()
    val completed = currentTrip.isCompleted()
    var showManualTripDialog by remember { mutableStateOf(false) }

    val sinceEvent = System.currentTimeMillis() - tripVisualState.eventTimestamp
    val justStarted = tripVisualState.event == TripVisualLifecycleEvent.STARTED && sinceEvent < 10_000L
    val justEnded = tripVisualState.event == TripVisualLifecycleEvent.COMPLETED && sinceEvent < 10_000L

    val (stateText, dotColor) = when {
        justStarted -> "Trip started" to c.positive
        justEnded -> "Trip ended" to c.critical
        currentTrip.status == TripStatus.TEMPORARY_STOP -> "Paused" to c.warning
        active -> "Live" to c.accent
        completed -> "Last trip" to c.textSecondary
        else -> "Parked" to c.textSecondary
    }
    val from = currentTrip.startLocationName.ifBlank { if (active || completed) "Start" else "" }
    val to = currentTrip.endLocationName.ifBlank { if (active) "now" else "" }
    val route = when {
        from.isNotEmpty() && to.isNotEmpty() -> "$from → $to"
        from.isNotEmpty() -> from
        else -> "Hold to start a trip"
    }

    Row(
        modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(Raahi.Gap)
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .heightIn(min = 52.dp)
                .clip(Raahi.PillShape)
                .background(c.card)
                .border(Raahi.Border, if (active) c.accent.copy(alpha = 0.6f) else c.border, Raahi.PillShape)
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = "Trip details",
                    onLongClickLabel = if (active) "End trip" else "Start trip",
                    onClick = onOpenTripDetails,
                    onLongClick = { showManualTripDialog = true }
                )
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(dotColor))
            Column(modifier = Modifier.weight(1f)) {
                Text(stateText, style = CockpitTheme.typography.labelLarge, color = c.textPrimary, maxLines = 1)
                Text(
                    route,
                    style = CockpitTheme.typography.bodySmall,
                    color = c.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
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
                    if (active) "Manually ends the current trip here. Geofences normally do this automatically."
                    else "Manually starts a trip now. Geofences normally do this automatically."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showManualTripDialog = false
                    if (active) com.antigravity.copilot.core.location.DrivingTrackingService.manualEndTrip(context)
                    else com.antigravity.copilot.core.location.DrivingTrackingService.manualStartTrip(context)
                }) { Text(if (active) "End trip" else "Start trip", color = c.textPrimary) }
            },
            dismissButton = {
                TextButton(onClick = { showManualTripDialog = false }) { Text("Cancel", color = c.textPrimary) }
            }
        )
    }
}

@Composable
private fun ClockPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = CockpitTheme.colors
    val context = LocalContext.current
    val playback by MediaPlaybackManager.playbackState.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            // Wake at the next minute boundary: one recomposition per minute, not per second.
            delay(60_000L - now % 60_000L)
        }
    }
    val is24h = remember(now / 60_000L) { DateFormat.is24HourFormat(context) }
    val time = remember(now / 60_000L, is24h) {
        SimpleDateFormat(if (is24h) "HH:mm" else "h:mm", Locale.getDefault()).format(Date(now))
    }
    Row(
        modifier = modifier
            .heightIn(min = 52.dp)
            .clip(Raahi.PillShape)
            .background(c.card)
            .border(Raahi.Border, c.border, Raahi.PillShape)
            .clickable(role = Role.Button, onClickLabel = "Music and clock", onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            if (playback.isMusicActive) Icons.Outlined.MusicNote else Icons.Outlined.Schedule,
            contentDescription = null,
            tint = if (playback.isMusicActive) c.accent else c.textSecondary,
            modifier = Modifier.size(20.dp)
        )
        Text(time, style = CockpitTheme.typography.headlineMedium, color = c.textPrimary, maxLines = 1)
    }
}

/**
 * Trip strip: trip time, distance, odometer. Time / distance open the trip card; the odometer
 * opens the calibration popup.
 */
@Composable
fun DriveTripStrip(
    currentTrip: TripSummary,
    totalLifetimeKm: Double,
    onOpenTripDetails: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = CockpitTheme.colors
    val context = LocalContext.current
    LaunchedEffect(Unit) { OdometerManager.init(context) }
    val odoConfig by OdometerManager.odometerConfig.collectAsStateWithLifecycle()
    val odometerKm = remember(odoConfig, totalLifetimeKm) { OdometerManager.calculateLiveOdometer(totalLifetimeKm) }
    var showOdometerDialog by remember { mutableStateOf(false) }

    val elapsed = rememberTripElapsedMs(currentTrip)
    val showTrip = currentTrip.isActive() || currentTrip.isCompleted()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .raahiCard()
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val cell = Modifier
            .clip(Raahi.InnerShape)
            .heightIn(min = Raahi.MinTouch)
            .padding(horizontal = 8.dp, vertical = 6.dp)
        StatCell(
            label = "Trip time",
            value = if (showTrip) TimeFormatUtils.formatLiveTripTimer(elapsed) else "--",
            valueStyle = CockpitTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f).clickable(role = Role.Button, onClick = onOpenTripDetails).then(cell)
        )
        VDivider()
        StatCell(
            label = "Distance",
            value = if (showTrip) NumberFormatUtils.formatDistance(currentTrip.distanceKm) else "--",
            unit = "km",
            valueStyle = CockpitTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f).clickable(role = Role.Button, onClick = onOpenTripDetails).then(cell)
        )
        VDivider()
        StatCell(
            label = "Odometer",
            value = String.format(Locale.US, "%,.0f", odometerKm),
            unit = "km",
            valueStyle = CockpitTheme.typography.headlineSmall,
            modifier = Modifier.weight(1.15f).clickable(role = Role.Button, onClickLabel = "Calibrate odometer") {
                showOdometerDialog = true
            }.then(cell)
        )
    }

    if (showOdometerDialog) {
        SpeedometerOdometerCalibrationDialog(
            currentOdometerKm = odometerKm,
            onDismiss = { showOdometerDialog = false },
            onConfirmCalibration = { newKm ->
                // Through FuelRepository so the first calibration re-bases the fuel model.
                com.antigravity.copilot.core.fuel.FuelRepository.getInstance(context).calibrateOdometer(newKm, totalLifetimeKm)
                showOdometerDialog = false
            }
        )
    }
}

/** Secondary area: the full trip card (rotating metrics, score, cost, peak braking). */
@Composable
fun TripDetailsDialog(
    currentTrip: TripSummary,
    tripVisualState: TripVisualState,
    baselineMileage: Double,
    onDismiss: () -> Unit
) {
    RaahiDialogHost(onDismissRequest = onDismiss, maxWidth = 560.dp) {
        CurrentTripCard(
            currentTrip = currentTrip,
            tripVisualState = tripVisualState,
            baselineMileage = baselineMileage,
            modifier = Modifier.fillMaxWidth().height(400.dp)
        )
    }
}

/** Secondary area: music player + clock card. */
@Composable
fun MediaDialog(onDismiss: () -> Unit) {
    RaahiDialogHost(onDismissRequest = onDismiss, maxWidth = 560.dp) {
        SmartMusicClockCard(modifier = Modifier.fillMaxWidth().height(340.dp))
    }
}

