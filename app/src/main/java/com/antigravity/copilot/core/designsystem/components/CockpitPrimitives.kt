package com.antigravity.copilot.core.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.antigravity.copilot.core.designsystem.CockpitTheme
import com.antigravity.copilot.core.designsystem.colors.CockpitColors
import com.antigravity.copilot.core.designsystem.colors.GlassStyle
import com.antigravity.copilot.core.designsystem.colors.LocalCockpitColors
import com.antigravity.copilot.core.designsystem.tokens.CopilotShapes

/**
 * Clipped, filled and outlined surface — the most repeated modifier chain in the cockpit UI.
 *
 * Under Liquid Glass, neutral surfaces (theme `card` / `cardElevated` / `dockBackground`) gain a
 * translucent sheen overlay and a cheap outline shadow, and the neutral theme border becomes a
 * 1dp specular gradient. Accent/tinted surfaces are drawn exactly as requested. Every brush is
 * pre-allocated in [com.antigravity.copilot.core.designsystem.colors.GlassStyle]: no blur, no
 * offscreen layers, no per-frame allocations.
 */
@Composable
@ReadOnlyComposable
fun Modifier.cockpitSurface(
    background: Color,
    border: Color,
    shape: Shape,
    borderWidth: Dp = 1.dp
): Modifier {
    val colors = LocalCockpitColors.current
    val glass = colors.glass
        ?: return this.clip(shape).background(background).border(borderWidth, border, shape)
    val neutralFill = background == colors.card || background == colors.cardElevated ||
        background == colors.dockBackground
    return this
        .glassShadow(neutralFill, glass, shape)
        .clip(shape)
        .background(background)
        .then(if (neutralFill) Modifier.background(glass.sheen) else Modifier)
        .glassBorder(border, colors, glass, shape, borderWidth)
}

/** Gradient-filled variant of [cockpitSurface]. Theme brushes are already glass under Liquid Glass. */
@Composable
@ReadOnlyComposable
fun Modifier.cockpitSurface(
    background: Brush,
    border: Color,
    shape: Shape,
    borderWidth: Dp = 1.dp
): Modifier {
    val colors = LocalCockpitColors.current
    val glass = colors.glass
        ?: return this.clip(shape).background(background).border(borderWidth, border, shape)
    return this
        .glassShadow(border == colors.border, glass, shape)
        .clip(shape)
        .background(background)
        .glassBorder(border, colors, glass, shape, borderWidth)
}

private fun Modifier.glassShadow(enabled: Boolean, glass: GlassStyle, shape: Shape): Modifier =
    if (enabled) shadow(glass.shadowElevationDp.dp, shape, clip = false) else this

private fun Modifier.glassBorder(
    border: Color,
    colors: CockpitColors,
    glass: GlassStyle,
    shape: Shape,
    borderWidth: Dp
): Modifier = if (border == colors.border || border == colors.dockBorder) {
    border(1.dp, glass.specularBorder, shape)
} else {
    border(borderWidth, border, shape)
}

/** Visual presets for [TintedBadge]. */
enum class BadgeSize(
    val shape: Shape,
    val padding: PaddingValues,
    val fillAlpha: Float,
    val borderAlpha: Float,
    val borderWidth: Dp
) {
    Compact(CopilotShapes.R6, PaddingValues(horizontal = 6.dp, vertical = 2.dp), 0.12f, 0.35f, 1.dp),
    Regular(CopilotShapes.R8, PaddingValues(horizontal = 10.dp, vertical = 5.dp), 0.15f, 0.45f, 1.2.dp)
}

/** Small pill whose fill, outline and text are all tinted from a single [color]. */
@Composable
fun TintedBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    size: BadgeSize = BadgeSize.Regular,
    textStyle: TextStyle = CockpitTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum")
) {
    Box(
        modifier = modifier
            .cockpitSurface(
                background = color.copy(alpha = size.fillAlpha),
                border = color.copy(alpha = size.borderAlpha),
                shape = size.shape,
                borderWidth = size.borderWidth
            )
            .padding(size.padding)
    ) {
        Text(text = text, style = textStyle, color = color)
    }
}

/**
 * Press-animated keypad key shared by the fuel entry and odometer keypads.
 * Shows [icon] (optionally prefixed by a short [text] label) or, without an icon, [text] alone.
 */
@Composable
fun TactileKeypadButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = Color.White,
    backgroundColor: Color = CockpitTheme.colors.card,
    idleBorderColor: Color = CockpitTheme.colors.border,
    icon: ImageVector? = null,
    shape: Shape = CopilotShapes.R12,
    pressedFillAlpha: Float = 0.28f,
    showRipple: Boolean = false,
    textStyle: TextStyle = CockpitTheme.typography.headlineLarge,
    labelStyle: TextStyle = CockpitTheme.typography.labelMedium
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1.0f,
        animationSpec = tween(durationMillis = 90, easing = FastOutSlowInEasing),
        label = "KeypadScale"
    )
    val fill by animateColorAsState(
        targetValue = if (isPressed) accentColor.copy(alpha = pressedFillAlpha) else backgroundColor,
        animationSpec = tween(durationMillis = 90),
        label = "KeypadFill"
    )
    val outline by animateColorAsState(
        targetValue = if (isPressed) accentColor.copy(alpha = 0.85f) else idleBorderColor,
        animationSpec = tween(durationMillis = 90),
        label = "KeypadBorder"
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .cockpitSurface(fill, outline, shape, borderWidth = 1.2.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = if (showRipple) rememberRipple(color = accentColor) else null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (text.isNotBlank()) {
                    Text(text = text, style = labelStyle, color = accentColor)
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Icon(
                    imageVector = icon,
                    contentDescription = text.ifBlank { "Backspace" },
                    tint = accentColor,
                    modifier = Modifier.size(24.dp)
                )
            }
        } else {
            Text(text = text, style = textStyle, color = accentColor)
        }
    }
}
