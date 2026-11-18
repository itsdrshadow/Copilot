package com.antigravity.copilot.core.designsystem

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.waterfall
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Width buckets (Material window size classes), measured on the window the app actually has,
 * so split-screen / freeform on a head unit or tablet is classed correctly too.
 * - [Compact] (< 600 dp): phones in portrait, narrow split-screen. Bottom bar + full-bleed map.
 * - [Medium] (600–840 dp): small tablets, foldables, half-screen split. Rail + 60/40 map/telemetry.
 * - [Expanded] (≥ 840 dp): 10.1" head units (1024x600, 1280x720/800) and large tablets. Rail +
 *   the 50/50 cockpit (map | 2x2 card grid).
 */
@Immutable
enum class CopilotWindowClass {
    Compact,
    Medium,
    Expanded;

    val usesBottomBar: Boolean get() = this == Compact

    companion object {
        val MediumMinWidth: Dp = 600.dp
        val ExpandedMinWidth: Dp = 840.dp

        fun fromWidth(width: Dp): CopilotWindowClass = when {
            width < MediumMinWidth -> Compact
            width < ExpandedMinWidth -> Medium
            else -> Expanded
        }
    }
}

/**
 * Text scale for the window: every `sp` style shrinks with the window's shorter side, so smaller
 * screens get proportionally smaller text instead of clipped / ellipsised labels.
 * 1.0 at ≥ 600 dp (the 10.1" head-unit design reference), linear down to [MinTextScale].
 * Applied on top of the user's system font scale, never instead of it.
 */
fun copilotTextScale(width: Dp, height: Dp): Float =
    (minOf(width, height) / TextScaleReference).coerceIn(MinTextScale, 1f)

private val TextScaleReference: Dp = 600.dp
private const val MinTextScale = 0.8f

/** The current [CopilotWindowClass]; changes only on window resize, so a static local is cheapest. */
val LocalCopilotWindowClass = staticCompositionLocalOf { CopilotWindowClass.Expanded }

/**
 * App shell: classes the window, provides [LocalCopilotWindowClass] and places [navigation] as a
 * left rail (Medium/Expanded) or a bottom bar (Compact) beside [content].
 *
 * Both slots are children of one custom [Layout] in a fixed order, so a class change (rotation,
 * split-screen resize) only re-places them — [content] and the MapView inside it are never
 * recreated, unlike swapping a Row for a Column.
 *
 * Insets: the app runs immersive (system bars hidden, shown transiently by swipe), so padding for
 * `safeDrawing` would shift the whole cockpit every time a transient bar appears. Only the
 * permanent hazards are padded: display cutouts and waterfall / overscan edges.
 */
@Composable
fun AdaptiveCopilotLayout(
    navigation: @Composable (CopilotWindowClass) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val windowClass = CopilotWindowClass.fromWidth(maxWidth)
        val density = LocalDensity.current
        val textScale = copilotTextScale(maxWidth, maxHeight)
        val scaledDensity = remember(density, textScale) {
            Density(density.density, density.fontScale * textScale)
        }
        CompositionLocalProvider(
            LocalCopilotWindowClass provides windowClass,
            LocalDensity provides scaledDensity,
        ) {
            Layout(
                contents = listOf({ navigation(windowClass) }, content),
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.waterfall)),
            ) { (navMeasurables, contentMeasurables), constraints ->
                val width = constraints.maxWidth
                val height = constraints.maxHeight
                if (windowClass.usesBottomBar) {
                    val nav = navMeasurables.map {
                        it.measure(Constraints(minWidth = width, maxWidth = width, maxHeight = height))
                    }
                    val navHeight = nav.maxOfOrNull { it.height } ?: 0
                    val contentHeight = (height - navHeight).coerceAtLeast(0)
                    val body = contentMeasurables.map { it.measure(Constraints.fixed(width, contentHeight)) }
                    layout(width, height) {
                        body.forEach { it.place(0, 0) }
                        nav.forEach { it.place(0, contentHeight) }
                    }
                } else {
                    val nav = navMeasurables.map {
                        it.measure(Constraints(maxWidth = width, minHeight = height, maxHeight = height))
                    }
                    val navWidth = nav.maxOfOrNull { it.width } ?: 0
                    val contentWidth = (width - navWidth).coerceAtLeast(0)
                    val body = contentMeasurables.map { it.measure(Constraints.fixed(contentWidth, height)) }
                    layout(width, height) {
                        nav.forEach { it.place(0, 0) }
                        body.forEach { it.place(navWidth, 0) }
                    }
                }
            }
        }
    }
}
