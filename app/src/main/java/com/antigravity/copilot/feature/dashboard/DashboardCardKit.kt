package com.antigravity.copilot.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.copilot.core.designsystem.AutomotiveDesignTokens
import com.antigravity.copilot.core.designsystem.CockpitTheme
import com.antigravity.copilot.core.designsystem.tokens.CopilotShapes

/**
 * Shared anatomy for the four Drive-screen cards (Speedometer, Fuel, Trip, Clock) so they read as
 * one family: same surface, padding, header (icon tile + title + optional trailing) and the same
 * bottom stat strip.
 */
object DashCard {
    val Padding = 14.dp
    val HeaderHeight = 44.dp
    val TileSize = 30.dp
    val IconSize = 18.dp
    val StripHeight = 60.dp
    val BorderWidth = 1.25.dp
    val Shape = RoundedCornerShape(AutomotiveDesignTokens.Radii.card)
}

/** Card surface: top-light gradient, 1.25 dp border, card radius. Padding is added by the caller. */
@Composable
fun Modifier.dashCardSurface(borderColor: Color? = null): Modifier {
    val c = CockpitTheme.colors
    return this
        .clip(DashCard.Shape)
        .background(c.cardTopLightGradient)
        .border(DashCard.BorderWidth, borderColor ?: c.border, DashCard.Shape)
}

/** Card header: tinted icon tile + bold tracked title, optional trailing content on the right. */
@Composable
fun DashCardHeader(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    accent: Color = CockpitTheme.colors.accent,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = CockpitTheme.colors
    Row(
        modifier = modifier.fillMaxWidth().height(DashCard.HeaderHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(DashCard.TileSize)
                .clip(CopilotShapes.R6)
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(DashCard.IconSize))
        }
        Text(
            text = title,
            style = CockpitTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp),
            color = c.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke(this)
    }
}

/** Bottom stat strip shared by every card: same height, surface, radius and inner padding. */
@Composable
fun DashStatStrip(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val c = CockpitTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(DashCard.StripHeight)
            .clip(CopilotShapes.R10)
            .background(c.dockActionBrush)
            .border(1.dp, c.border, CopilotShapes.R10)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
