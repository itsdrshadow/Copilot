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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.copilot.core.designsystem.AutomotiveDesignTokens
import com.antigravity.copilot.core.designsystem.CockpitTheme
import com.antigravity.copilot.core.designsystem.colors.CockpitColors
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

/** Solid per-card colours; every one keeps white text at >= 5:1 contrast. */
object DashTone {
    val Trip = Color(0xFF6D28D9)
    val Clock = Color(0xFFBE185D)
    val FuelOk = Color(0xFF047857)
    val FuelMid = Color(0xFFB45309)
    val FuelLow = Color(0xFFB91C1C)

    fun forFuel(percent: Double): Color = when {
        percent < 15.0 -> FuelLow
        percent <= 40.0 -> FuelMid
        else -> FuelOk
    }

    /** Darkens [accent] until white text on it has >= ~5.5:1 contrast. */
    fun accentFill(accent: Color): Color {
        var c = accent
        var i = 0
        while (c.luminance() > 0.14f && i < 24) {
            c = lerp(c, Color.Black, 0.08f)
            i++
        }
        return c
    }
}

/**
 * Flat card surface. With [fill] the card is a solid colour (no border, except an optional
 * [borderColor] such as the trip flash); otherwise matte charcoal with a 1 dp hairline.
 */
@Composable
fun Modifier.dashCardSurface(borderColor: Color? = null, fill: Color? = null): Modifier {
    val c = CockpitTheme.colors
    val base = this.clip(DashCard.Shape)
    return if (fill != null) {
        val withFill = base.background(fill)
        if (borderColor != null && borderColor != Color.Transparent) {
            withFill.border(DashCard.BorderWidth, borderColor, DashCard.Shape)
        } else {
            withFill
        }
    } else {
        base.background(c.card).border(DashCard.BorderWidth, borderColor ?: c.border, DashCard.Shape)
    }
}

/** Palette for content drawn on a solid [fill]: white text, deeper wells, light status colours. */
fun CockpitColors.onFill(fill: Color): CockpitColors {
    val deep = lerp(fill, Color.Black, 0.30f)
    return copy(
        textPrimary = Color.White,
        textSecondary = Color.White.copy(alpha = 0.88f),
        textDisabled = Color.White.copy(alpha = 0.70f),
        accent = Color.White,
        accentText = Color.White,
        onAccent = fill,
        positive = Color.White,
        warning = Color(0xFFFDE68A),
        critical = Color(0xFFFECACA),
        border = Color.White.copy(alpha = 0.30f),
        card = deep,
        cardElevated = deep,
    )
}

/** Re-skins the theme colours for content drawn on a solid [fill] card. */
@Composable
fun FeaturedColors(fill: Color, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalCockpitColors provides CockpitTheme.colors.onFill(fill), content = content)
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
