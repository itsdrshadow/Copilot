package com.antigravity.copilot.feature.maps

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TurnSharpLeft
import androidx.compose.material.icons.outlined.TurnSharpRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.antigravity.copilot.core.designsystem.CockpitTheme
import com.antigravity.copilot.core.designsystem.components.Raahi
import com.antigravity.copilot.core.map.intel.SharpTurnAlert
import com.antigravity.copilot.core.map.intel.TurnDirection

/**
 * Top-centre map overlay: the offline "Road • Near Place" pill and, under it, the
 * "Sharp turn ahead" badge. Both fade/slide in and out; pill text cross-fades on change.
 */
@Composable
fun RoadIntelOverlay(pillText: String?, sharpTurn: SharpTurnAlert?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Keep the last text while fading out so the pill doesn't collapse mid-animation.
        val lastPill = remember { arrayOfNulls<String>(1) }
        if (pillText != null) lastPill[0] = pillText
        AnimatedVisibility(visible = pillText != null, enter = fadeIn(tween(300)), exit = fadeOut(tween(300))) {
            LocationPill(pillText ?: lastPill[0].orEmpty())
        }

        val lastTurn = remember { arrayOfNulls<SharpTurnAlert>(1) }
        if (sharpTurn != null) lastTurn[0] = sharpTurn
        AnimatedVisibility(
            visible = sharpTurn != null,
            enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { -it / 2 },
            exit = fadeOut(tween(300)) + slideOutVertically(tween(300)) { -it / 2 }
        ) {
            (sharpTurn ?: lastTurn[0])?.let { com.antigravity.copilot.core.designsystem.PopupScaled { SharpTurnBadge(it) } }
        }
    }
}

@Composable
private fun LocationPill(text: String) {
    val c = CockpitTheme.colors
    AnimatedContent(
        targetState = text,
        transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) },
        label = "roadIntelPill",
        modifier = Modifier
            .widthIn(max = 240.dp)
            .clip(Raahi.PillShape)
            .background(c.card)
            .border(1.dp, c.border, Raahi.PillShape)
            .padding(horizontal = 18.dp, vertical = 9.dp)
    ) { t ->
        Text(
            text = t,
            style = CockpitTheme.typography.titleMedium,
            color = c.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SharpTurnBadge(alert: SharpTurnAlert) {
    val c = CockpitTheme.colors
    val current by rememberUpdatedState(alert)
    Row(
        modifier = Modifier
            .clip(Raahi.PillShape)
            .background(c.card)
            .border(1.5.dp, c.warning, Raahi.PillShape)
            .padding(start = 10.dp, end = 14.dp, top = 6.dp, bottom = 6.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val dir = current.direction
        if (dir != TurnDirection.RIGHT) {
            Icon(Icons.Outlined.TurnSharpLeft, contentDescription = null, tint = c.warning, modifier = Modifier.size(22.dp))
        }
        if (dir != TurnDirection.LEFT) {
            Icon(Icons.Outlined.TurnSharpRight, contentDescription = null, tint = c.warning, modifier = Modifier.size(22.dp))
        }
        val label = when (dir) {
            TurnDirection.LEFT -> "Sharp left"
            TurnDirection.RIGHT -> "Sharp right"
            TurnDirection.BOTH -> "Sharp left / right"
        }
        Text(
            text = "$label · ${current.distanceM} m",
            style = CockpitTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = c.textPrimary,
            maxLines = 1
        )
    }
}
