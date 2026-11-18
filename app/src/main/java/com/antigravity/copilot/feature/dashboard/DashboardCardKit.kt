package com.antigravity.copilot.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.copilot.core.designsystem.AutomotiveDesignTokens
import com.antigravity.copilot.core.designsystem.CockpitTheme
import com.antigravity.copilot.core.designsystem.colors.LocalCockpitColors

/**
 * Bento anatomy for the four Drive-screen cards (Speedometer, Fuel, Trip, Clock): flat matte
 * surface with a 1 dp hairline, an eyebrow header (small icon + UPPERCASE tracked caption), a hero
 * number, and a dashed-divider stat strip. One card per screen can be [featured] (solid accent).
 */
object DashCard {
    val Padding = 18.dp
    val HeaderHeight = 36.dp
    val IconSize = 16.dp
    val StripHeight = 56.dp
    val BorderWidth = 1.dp
    val Shape = RoundedCornerShape(AutomotiveDesignTokens.Radii.card)
}

/** Flat card surface. [featured] = solid accent fill (use on one hero card only). */
@Composable
fun Modifier.dashCardSurface(borderColor: Color? = null, featured: Boolean = false): Modifier {
    val c = CockpitTheme.colors
    val base = this.clip(DashCard.Shape)
    return if (featured) {
        base.background(c.accent)
    } else {
        base.background(c.card).border(DashCard.BorderWidth, borderColor ?: c.border, DashCard.Shape)
    }
}

/** Re-skins the theme colours for content drawn on the solid accent card (white text, deep wells). */
@Composable
fun FeaturedColors(content: @Composable () -> Unit) {
    val c = CockpitTheme.colors
    val deep = lerp(c.accent, Color.Black, 0.38f)
    val fc = c.copy(
        textPrimary = Color.White,
        textSecondary = Color.White.copy(alpha = 0.80f),
        textDisabled = Color.White.copy(alpha = 0.60f),
        accent = Color.White,
        accentText = Color.White,
        border = Color.White.copy(alpha = 0.28f),
        card = deep,
        cardElevated = deep,
    )
    CompositionLocalProvider(LocalCockpitColors provides fc, content = content)
}

/** Eyebrow header: small muted icon + UPPERCASE medium-weight caption with wide tracking. */
@Composable
fun DashCardHeader(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    accent: Color = CockpitTheme.colors.textSecondary,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = CockpitTheme.colors
    Row(
        modifier = modifier.fillMaxWidth().height(DashCard.HeaderHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(DashCard.IconSize))
        Text(
            text = title.uppercase(),
            style = CockpitTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 1.04.sp),
            color = c.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke(this)
    }
}

/** 1 dp dashed section divider. */
@Composable
fun DashedDivider(modifier: Modifier = Modifier, color: Color = CockpitTheme.colors.border) {
    Canvas(modifier.fillMaxWidth().height(1.dp)) {
        drawLine(
            color = color,
            start = Offset(0f, 0f),
            end = Offset(size.width, 0f),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
        )
    }
}

/** Draws a 1 dp dashed line along the top edge of this element. */
@Composable
fun Modifier.dashedTopDivider(color: Color = CockpitTheme.colors.border): Modifier =
    this.drawBehind {
        drawLine(
            color = color,
            start = Offset(0f, 0f),
            end = Offset(size.width, 0f),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
        )
    }

/** Bottom stat strip: dashed divider on top, then two flat columns (no box, no border). */
@Composable
fun DashStatStrip(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        DashedDivider()
        Row(
            modifier = Modifier.fillMaxWidth().height(DashCard.StripHeight),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}
