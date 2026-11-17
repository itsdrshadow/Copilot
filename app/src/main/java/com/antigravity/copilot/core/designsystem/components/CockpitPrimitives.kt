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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.isSpecified
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

/**
 * Full-screen backdrop. Liquid Glass layers its ambient colour pools over the base gradient;
 * brushes are built once per size in drawWithCache, so redraws allocate nothing.
 */
@Composable
@ReadOnlyComposable
fun Modifier.cockpitBackground(): Modifier {
    val colors = LocalCockpitColors.current
    val glows = colors.glass?.ambientGlows.orEmpty()
    val base = this.background(colors.backgroundBrush)
    if (glows.isEmpty()) return base
    return base.drawWithCache {
        val longest = maxOf(size.width, size.height)
        val brushes = glows.map { g ->
            Brush.radialGradient(
                colors = listOf(g.color, g.color.copy(alpha = 0f)),
                center = Offset(size.width * g.centerX, size.height * g.centerY),
                radius = (longest * g.radius).coerceAtLeast(1f)
            )
        }
        onDrawBehind { brushes.forEach { drawRect(it) } }
    }
}

private fun Modifier.glassShadow(enabled: Boolean, glass: GlassStyle, shape: Shape): Modifier =
    if (enabled && glass.shadowElevationDp > 0f) shadow(glass.shadowElevationDp.dp, shape, clip = false) else this

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

// ==================== LIQUID GLASS ====================

/**
 * Apple-style Liquid Glass panel for dashboard cards, docks and floating chrome.
 *
 * Under Liquid Glass: a translucent vertical sheen (white 16% → 5% → slate 55%) lets the ambient
 * orbs behind the panel tint through it, a 1.25dp gradient rim catches "light" on the top-left,
 * and a 1dp inner highlight line runs along the top edge. Optional [tintColor] washes the glass
 * with a faint colour. Pure brush fills + one line: no blur, no RenderEffect, no offscreen layer,
 * and brushes are rebuilt only when the size changes (drawWithCache) — safe on GLES 2.0.
 *
 * Under Apex it reproduces the classic opaque card exactly (gradient fill + 1.25dp [borderColor]).
 */
@Composable
@ReadOnlyComposable
fun Modifier.liquidGlass(
    cornerRadius: Dp,
    tintColor: Color = Color.Unspecified,
    borderColor: Color = Color.Unspecified
): Modifier = liquidGlass(RoundedCornerShape(cornerRadius), tintColor, borderColor)

/** Shape overload of [liquidGlass] (e.g. CircleShape capsules). */
@Composable
@ReadOnlyComposable
fun Modifier.liquidGlass(
    shape: Shape,
    tintColor: Color = Color.Unspecified,
    borderColor: Color = Color.Unspecified
): Modifier {
    val colors = LocalCockpitColors.current
    val glass = colors.glass ?: return this
        .clip(shape)
        .background(colors.cardTopLightGradient)
        .border(1.25.dp, if (borderColor.isSpecified) borderColor else colors.border, shape)
    return this
        .clip(shape)
        .then(if (tintColor.isSpecified) Modifier.background(tintColor.copy(alpha = 0.08f)) else Modifier)
        .background(glass.panelFill)
        .glassTopHighlight(glass.topHighlight)
        .then(
            if (borderColor.isSpecified) Modifier.border(1.25.dp, borderColor, shape)
            else Modifier.border(1.25.dp, glass.rim, shape)
        )
}

/**
 * Luminous glass capsule for primary controls (ADD FUEL, map buttons, active dock tab).
 *
 * Glass: a [glowColor]-tinted translucent body, a soft inner glow rising from the bottom edge,
 * a glossy upper half-sheen and a rim that fades from white into [glowColor]. When [active] is
 * false the capsule is plain [liquidGlass] with a dimmer glow. Apex: solid [glowColor] when active,
 * otherwise the standard card surface.
 */
@Composable
@ReadOnlyComposable
fun Modifier.liquidGlassPill(
    shape: Shape,
    glowColor: Color,
    active: Boolean = true
): Modifier {
    val colors = LocalCockpitColors.current
    val glass = colors.glass ?: return if (active) {
        this.clip(shape).background(glowColor)
    } else {
        this.clip(shape).background(colors.card).border(1.dp, colors.border, shape)
    }
    val glowAlpha = if (active) 0.42f else 0.16f
    val bodyAlpha = if (active) 0.22f else 0.0f
    return this
        .clip(shape)
        .background(glass.panelFill)
        .drawWithCache {
            val body = Brush.verticalGradient(
                listOf(glowColor.copy(alpha = bodyAlpha + 0.08f), glowColor.copy(alpha = bodyAlpha))
            )
            val innerGlow = Brush.radialGradient(
                colors = listOf(glowColor.copy(alpha = glowAlpha), glowColor.copy(alpha = 0f)),
                center = Offset(size.width / 2f, size.height * 1.05f),
                radius = (maxOf(size.width, size.height) * 0.75f).coerceAtLeast(1f)
            )
            val gloss = Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.24f),
                0.5f to Color.White.copy(alpha = 0.04f),
                0.5001f to Color.Transparent,
                startY = 0f,
                endY = size.height
            )
            onDrawBehind {
                drawRect(body)
                drawRect(innerGlow)
                drawRect(gloss)
            }
        }
        .glassTopHighlight(glass.topHighlight)
        .border(
            1.25.dp,
            Brush.linearGradient(
                listOf(Color(0xB3FFFFFF), glowColor.copy(alpha = if (active) 0.75f else 0.35f), Color(0x18FFFFFF))
            ),
            shape
        )
}

/**
 * Static coloured light pool painted behind a composable (deliberately unclipped, so it spills
 * past the bounds and refracts through the translucent glass on top). No-op outside Liquid Glass
 * or when [color] is transparent.
 */
@Composable
@ReadOnlyComposable
fun Modifier.glassAmbientOrb(color: Color = LocalCockpitColors.current.glass?.fuelGlow ?: Color.Transparent): Modifier {
    if (LocalCockpitColors.current.glass == null || color.alpha == 0f) return this
    return drawWithCache {
        val r = (maxOf(size.width, size.height) * 0.9f).coerceAtLeast(1f)
        val c = Offset(size.width / 2f, size.height / 2f)
        val brush = Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), center = c, radius = r)
        onDrawBehind { drawCircle(brush, radius = r, center = c) }
    }
}

/** 1dp inner highlight just inside the top rim; horizontal fade so it never hits the corners. */
private fun Modifier.glassTopHighlight(color: Color): Modifier = drawWithCache {
    val stroke = 1.dp.toPx()
    val y = 1.25.dp.toPx() + stroke / 2f
    val brush = Brush.horizontalGradient(
        0f to Color.Transparent,
        0.3f to color,
        0.7f to color,
        1f to Color.Transparent,
        startX = 0f,
        endX = size.width
    )
    onDrawBehind { drawLine(brush, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke) }
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
