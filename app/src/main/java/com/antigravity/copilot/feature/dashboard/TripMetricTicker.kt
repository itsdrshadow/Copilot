package com.antigravity.copilot.feature.dashboard

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.antigravity.copilot.core.designsystem.CockpitTheme
import com.antigravity.copilot.core.designsystem.components.Raahi
import com.antigravity.copilot.core.model.TripSummary
import com.antigravity.copilot.core.trip.TimeFormatUtils
import kotlinx.coroutines.delay

private const val TICKER_INTERVAL_MS = 30_000L

/**
 * Translucent trip stat on the map (top-right): average speed, max speed, economy, fuel, cost,
 * score, braking and driving time, one at a time, sliding to the next every 30 s. Tap for the next.
 *
 * Reads the trip through [trip] so a trip update recomposes only this chip, never the map panel.
 * Hidden while parked with no trip to show.
 */
@Composable
fun TripMetricTicker(
    trip: () -> TripSummary,
    baselineMileage: () -> Double,
    modifier: Modifier = Modifier,
) {
    val currentTrip = trip()
    if (!currentTrip.isActive() && !currentTrip.isCompleted()) return

    val c = CockpitTheme.colors
    val base = rememberTripMetrics(currentTrip, baselineMileage())
    val drivingMs = currentTrip.drivingDurationMs
    val metrics =
        remember(base, drivingMs, c) {
            // Distance already sits in the trip strip; driving time comes from the old trip card dock.
            base.filterNot { it.id == "trip_distance" } +
                PremiumRotatingMetric(
                    id = "driving_time",
                    title = "DRIVING TIME",
                    value = if (drivingMs > 0L) TimeFormatUtils.formatAdaptiveDuration(drivingMs) else "--",
                    unit = "",
                    subtitle = "",
                    icon = Icons.Filled.Timer,
                    accentColor = c.accent,
                )
        }

    var index by remember { mutableIntStateOf(0) }
    // Restarts on every change, so a manual tap also gets the full 30 s.
    LaunchedEffect(index) {
        delay(TICKER_INTERVAL_MS)
        index = (index + 1) % metrics.size
    }

    val shape = Raahi.InnerShape
    AnimatedContent(
        targetState = index,
        transitionSpec = {
            (
                slideInVertically(tween(450, easing = FastOutSlowInEasing)) { it } +
                    fadeIn(tween(450))
            ).togetherWith(
                slideOutVertically(tween(350, easing = FastOutSlowInEasing)) { -it } +
                    fadeOut(tween(250)),
            ).using(SizeTransform(clip = true))
        },
        contentAlignment = Alignment.CenterStart,
        label = "TripMetricTicker",
        modifier =
            modifier
                .widthIn(min = 220.dp, max = 300.dp)
                .clip(shape)
                .background(c.card.copy(alpha = 0.55f))
                .border(1.dp, c.border.copy(alpha = 0.45f), shape)
                .clickable(role = Role.Button, onClickLabel = "Next trip stat") {
                    index = (index + 1) % metrics.size
                }.padding(horizontal = 18.dp, vertical = 12.dp),
    ) { i ->
        val m = metrics[i % metrics.size]
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(m.icon, contentDescription = null, tint = m.accentColor, modifier = Modifier.size(20.dp))
                Text(
                    m.title,
                    style = CockpitTheme.typography.labelMedium,
                    color = c.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row {
                Text(
                    m.value,
                    style = CockpitTheme.typography.headlineLarge,
                    color = c.textPrimary,
                    maxLines = 1,
                    modifier = Modifier.alignByBaseline(),
                )
                if (m.unit.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        m.unit,
                        style = CockpitTheme.typography.labelLarge,
                        color = m.accentColor,
                        maxLines = 1,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
            }
        }
    }
}
