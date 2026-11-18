package com.antigravity.copilot.feature.trips

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.antigravity.copilot.core.database.FuelLedgerEntity
import com.antigravity.copilot.core.database.TripEntity
import com.antigravity.copilot.core.database.TripPointEntity
import com.antigravity.copilot.core.database.VehicleStateTransitionEntity
import com.antigravity.copilot.core.designsystem.CockpitTheme
import com.antigravity.copilot.core.designsystem.components.Raahi
import com.antigravity.copilot.core.designsystem.tokens.CopilotShapes
import com.antigravity.copilot.core.trip.TripGeofenceManager
import com.antigravity.copilot.core.util.NumberFormatUtils
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Trip timeline: a journey ribbon across the top (every event placed on the trip's clock, tap a
 * marker to jump to it), the vertical event list on the left and an "at a glance" panel on the
 * right.
 */
@Composable
internal fun TripTimelineView(
    trip: TripEntity,
    points: List<TripPointEntity>,
    transitions: List<VehicleStateTransitionEntity> = emptyList(),
    fuelEvents: List<FuelLedgerEntity> = emptyList(),
    modifier: Modifier = Modifier
) {
    val c = CockpitTheme.colors
    val context = LocalContext.current
    val via = LocalTripViaNames.current[trip.id]
    val events = remember(trip, points, transitions, fuelEvents, via) {
        val geofences = TripGeofenceManager.getInstance(context)
        generateTripTimeline(
            trip, points, transitions, fuelEvents,
            zoneAt = { lat, lon -> geofences.findGeofenceContaining(lat, lon, onlyEnabled = false)?.name },
            turnaroundName = via
        )
    }
    if (events.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("No timeline events for this trip.", color = c.textSecondary, style = CockpitTheme.typography.bodyLarge)
        }
        return
    }
    val t0 = events.first().timestamp
    val t1 = events.last().timestamp.coerceAtLeast(t0 + 1)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Column(modifier = modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        JourneyRibbon(
            events = events,
            startMs = t0,
            endMs = t1,
            onPick = { i -> scope.launch { listState.animateScrollToItem(i) } }
        )
        Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxHeight()) {
                itemsIndexed(events, key = { i, e -> "${e.timestamp}-${e.kind}-$i" }) { i, e ->
                    TimelineRow(
                        event = e,
                        elapsedMs = e.timestamp - t0,
                        isFirst = i == 0,
                        isLast = i == events.lastIndex,
                        nextColor = events.getOrNull(i + 1)?.accentColor
                    )
                }
            }
            TimelineGlance(trip = trip, events = events, durationMs = t1 - t0, modifier = Modifier.width(260.dp).fillMaxHeight())
        }
    }
}

/** Horizontal overview: start → end with one coloured marker per event. */
@Composable
private fun JourneyRibbon(
    events: List<TimelineEvent>,
    startMs: Long,
    endMs: Long,
    onPick: (Int) -> Unit
) {
    val c = CockpitTheme.colors
    val timeFmt = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CopilotShapes.R12)
            .background(c.card)
            .border(1.dp, c.border, CopilotShapes.R12)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Journey", style = CockpitTheme.typography.labelLarge, color = c.textSecondary, modifier = Modifier.weight(1f))
            Text("${events.size} events · tap a marker", style = CockpitTheme.typography.labelMedium, color = c.textSecondary)
        }
        val track = c.border
        val span = (endMs - startMs).toFloat()
        Box(modifier = Modifier.fillMaxWidth().height(34.dp)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val y = size.height / 2f
                drawLine(track, Offset(0f, y), Offset(size.width, y), strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
                events.forEach { e ->
                    val x = ((e.timestamp - startMs) / span).coerceIn(0f, 1f) * size.width
                    val major = e.kind == TimelineKind.START || e.kind == TimelineKind.END
                    val r = if (major) 9.dp.toPx() else 6.dp.toPx()
                    drawCircle(Color.Black.copy(alpha = 0.35f), radius = r + 2.dp.toPx(), center = Offset(x, y))
                    drawCircle(e.accentColor, radius = r, center = Offset(x, y))
                }
            }
            // Invisible tap targets over the markers (48 dp wide each, clamped inside the bar).
            Row(modifier = Modifier.fillMaxSize()) {
                androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val w = maxWidth
                    events.forEachIndexed { i, e ->
                        val frac = ((e.timestamp - startMs) / span).coerceIn(0f, 1f)
                        Box(
                            modifier = Modifier
                                .padding(start = ((w - 40.dp) * frac).coerceAtLeast(0.dp))
                                .size(40.dp, 34.dp)
                                .clip(CircleShape)
                                .clickable(role = Role.Button, onClickLabel = e.title) { onPick(i) }
                        )
                    }
                }
            }
        }
        Row {
            Text(timeFmt.format(Date(startMs)), style = CockpitTheme.typography.labelMedium, color = c.textPrimary)
            Spacer(Modifier.weight(1f))
            Text(formatDurationHoursMinutes(endMs - startMs), style = CockpitTheme.typography.labelMedium, color = c.accentText)
            Spacer(Modifier.weight(1f))
            Text(timeFmt.format(Date(endMs)), style = CockpitTheme.typography.labelMedium, color = c.textPrimary)
        }
    }
}

/** One event: time column, coloured node on a connecting rail, and a tinted card. */
@Composable
private fun TimelineRow(
    event: TimelineEvent,
    elapsedMs: Long,
    isFirst: Boolean,
    isLast: Boolean,
    nextColor: Color?
) {
    val c = CockpitTheme.colors
    val timeStr = remember(event.timestamp) { SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(event.timestamp)) }
    val accent = event.accentColor
    val major = event.kind == TimelineKind.START || event.kind == TimelineKind.END
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        // Time
        Column(
            modifier = Modifier.width(96.dp).padding(top = 10.dp, end = 8.dp),
            horizontalAlignment = Alignment.End
        ) {
            Text(timeStr, style = CockpitTheme.typography.labelLarge, color = c.textPrimary, maxLines = 1)
        }
        // Rail + node
        Box(modifier = Modifier.width(48.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val x = size.width / 2f
                val nodeY = 30.dp.toPx()
                val w = 3.dp.toPx()
                if (!isFirst) drawLine(accent.copy(alpha = 0.5f), Offset(x, 0f), Offset(x, nodeY), strokeWidth = w)
                if (!isLast) drawLine((nextColor ?: accent).copy(alpha = 0.5f), Offset(x, nodeY), Offset(x, size.height), strokeWidth = w)
            }
            Box(
                modifier = Modifier
                    .padding(top = if (major) 8.dp else 12.dp)
                    .size(if (major) 44.dp else 36.dp)
                    .clip(CircleShape)
                    .background(if (major) accent else c.card)
                    .border(2.dp, accent, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    event.icon,
                    contentDescription = null,
                    tint = if (major) contentColorOnNode(accent) else accent,
                    modifier = Modifier.size(if (major) 24.dp else 20.dp)
                )
            }
        }
        // Card
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 6.dp, bottom = 10.dp)
                .clip(CopilotShapes.R12)
                .background(accent.copy(alpha = if (major) 0.12f else 0.06f))
                .border(1.dp, accent.copy(alpha = if (major) 0.5f else 0.25f), CopilotShapes.R12)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    event.title,
                    style = CockpitTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = c.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (event.distanceKm >= 0) {
                    Text(
                        NumberFormatUtils.formatDistanceWithUnit(event.distanceKm),
                        style = CockpitTheme.typography.labelLarge,
                        color = c.textSecondary,
                        maxLines = 1
                    )
                }
            }
            Text(event.description, style = CockpitTheme.typography.bodyMedium, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (event.chips.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    event.chips.forEach { chip ->
                        Text(
                            chip,
                            style = CockpitTheme.typography.labelMedium,
                            color = c.textPrimary,
                            maxLines = 1,
                            modifier = Modifier
                                .clip(Raahi.PillShape)
                                .background(accent.copy(alpha = 0.16f))
                                .padding(horizontal = 10.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }
    }
}

/** Right panel: counts per event type and the start/finish times. */
@Composable
private fun TimelineGlance(trip: TripEntity, events: List<TimelineEvent>, durationMs: Long, modifier: Modifier = Modifier) {
    val c = CockpitTheme.colors
    val stops = events.count { it.kind == TimelineKind.STOP }
    val stoppedMs = run {
        // Sum of each STOP → following RESUME gap.
        var total = 0L
        events.forEachIndexed { i, e ->
            if (e.kind == TimelineKind.STOP) {
                val resume = events.drop(i + 1).firstOrNull { it.kind == TimelineKind.RESUME }
                if (resume != null) total += resume.timestamp - e.timestamp
            }
        }
        total
    }
    // Same numbers as the Route replay and Charts views: the trip's saved record.
    val brakes = trip.brakeCount
    val accels = trip.accelCount
    val peak = trip.maxSpeedKmh.toFloat()
    Column(
        modifier = modifier
            .clip(CopilotShapes.R12)
            .background(c.card)
            .border(1.dp, c.border, CopilotShapes.R12)
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("At a glance", style = CockpitTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = c.textPrimary)
        GlanceRow("Duration", formatDurationHoursMinutes(durationMs), c.textPrimary)
        GlanceRow("Distance", NumberFormatUtils.formatDistanceWithUnit(trip.distanceMeters / 1000.0), c.textPrimary)
        GlanceRow("Top speed", NumberFormatUtils.formatSpeedWithUnit(peak, 0), c.textPrimary)
        GlanceRow("Stops", if (stops > 0) "$stops · ${formatDurationHoursMinutes(stoppedMs)}" else "None", if (stops > 0) c.warningText else c.textPrimary)
        GlanceRow("Hard braking", "$brakes", if (brakes > 0) c.criticalText else c.positiveText)
        GlanceRow("Quick starts", "$accels", if (accels > 0) c.warningText else c.positiveText)
        val smooth = (100 - brakes * 8 - accels * 5).coerceIn(0, 100)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row {
                Text("Smoothness", style = CockpitTheme.typography.labelLarge, color = c.textSecondary, modifier = Modifier.weight(1f))
                Text("$smooth", style = CockpitTheme.typography.headlineSmall, color = c.textPrimary)
            }
            Box(modifier = Modifier.fillMaxWidth().height(8.dp).clip(Raahi.PillShape).background(c.cardElevated)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(smooth / 100f)
                        .fillMaxHeight()
                        .clip(Raahi.PillShape)
                        .background(
                            when {
                                smooth >= 80 -> c.positive
                                smooth >= 55 -> c.warning
                                else -> c.critical
                            }
                        )
                )
            }
        }
    }
}

@Composable
private fun GlanceRow(label: String, value: String, valueColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = CockpitTheme.typography.labelLarge, color = CockpitTheme.colors.textSecondary, modifier = Modifier.weight(1f))
        Text(value, style = CockpitTheme.typography.headlineSmall, color = valueColor, maxLines = 1)
    }
}

private fun formatElapsed(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%d:%02d", m, sec)
}

private fun contentColorOnNode(bg: Color): Color =
    com.antigravity.copilot.core.designsystem.components.contentColorOn(bg)
