package com.antigravity.copilot.feature.maps

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.antigravity.copilot.core.designsystem.CockpitTheme
import com.antigravity.copilot.core.map.CarMapController
import com.antigravity.copilot.core.map.intel.RoadCallout

/**
 * Screen position of the callout's tail tip, written by [CarMapController] on every camera frame.
 * Plain float snapshot state that is read only inside `graphicsLayer {}`: a move re-runs the
 * layer block (draw phase), never composition or layout.
 */
@Stable
class CalloutAnchorState : CarMapController.CalloutAnchorListener {
    internal val x = mutableFloatStateOf(Float.NaN)
    internal val y = mutableFloatStateOf(Float.NaN)

    override fun onAnchor(x: Float, y: Float) {
        this.x.floatValue = x
        this.y.floatValue = y
    }
}

private val TAIL_HEIGHT = 8.dp
private val TAIL_WIDTH = 16.dp
private val CORNER = 13.dp

/**
 * Road-anchored callout: rounded glass card with a downward tail whose tip sits on [anchor]
 * (on the road just ahead of the puck). Shows the current segment's average speed and km/L
 * ("~" while uncalibrated). Fades in/out with [callout]; values cross-fade when they change and
 * slide when the car enters a new segment.
 *
 * Place it in the map Box with `Modifier.matchParentSize()` (same origin as the MapView).
 */
@Composable
fun RoadCalloutBubble(callout: RoadCallout?, anchor: CalloutAnchorState, modifier: Modifier = Modifier) {
    // Keep the last content while fading out so the card doesn't empty mid-animation.
    val last = remember { arrayOfNulls<RoadCallout>(1) }
    if (callout != null) last[0] = callout
    val width = remember { mutableIntStateOf(0) }
    val height = remember { mutableIntStateOf(0) }

    Box(modifier) {
        AnimatedVisibility(
            visible = callout != null,
            enter = fadeIn(tween(350)) + scaleIn(tween(350), initialScale = 0.85f, transformOrigin = TransformOrigin(0.5f, 1f)),
            exit = fadeOut(tween(500)) + scaleOut(tween(500), targetScale = 0.9f, transformOrigin = TransformOrigin(0.5f, 1f)),
            modifier = Modifier
                .align(Alignment.TopStart)
                .onSizeChanged { width.intValue = it.width; height.intValue = it.height }
                .graphicsLayer {
                    val ax = anchor.x.floatValue
                    val ay = anchor.y.floatValue
                    if (ax.isNaN() || ay.isNaN() || width.intValue == 0) {
                        alpha = 0f
                    } else {
                        alpha = 1f
                        translationX = ax - width.intValue / 2f
                        translationY = ay - height.intValue
                    }
                }
        ) {
            (callout ?: last[0])?.let { com.antigravity.copilot.core.designsystem.PopupScaled { CalloutCard(it) } }
        }
    }
}

@Composable
private fun CalloutCard(c: RoadCallout) {
    // Segment change: the card slides; same segment: only the values cross-fade (CalloutLine).
    AnimatedContent(
        targetState = c,
        contentKey = { it.segmentKey },
        transitionSpec = {
            (fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 4 }) togetherWith
                (fadeOut(tween(200)) + slideOutVertically(tween(200)) { -it / 4 })
        },
        label = "roadCalloutSegment"
    ) { s -> CalloutBody(s) }
}

@Composable
private fun CalloutBody(c: RoadCallout) {
    val colors = CockpitTheme.colors
    val shape = remember { CalloutShape(CORNER, TAIL_WIDTH, TAIL_HEIGHT) }
    val speedText = "Avg ${c.avgSpeedKmh} km/h"
    val kmpl = "${c.mileageTenths / 10}.${c.mileageTenths % 10} km/l"
    val mileageText = if (c.calibrated) kmpl else "~$kmpl"
    // Accent "message bubble": the road's own stats read at a glance against the dark map.
    val bg = colors.accent
    val fg = colors.onAccent
    Column(
        modifier = Modifier
            .background(bg.copy(alpha = 0.94f), shape)
            .border(1.dp, Color.White.copy(alpha = 0.28f), shape)
            .padding(start = 10.dp, end = 12.dp, top = 7.dp, bottom = 7.dp + TAIL_HEIGHT)
            .semantics { contentDescription = "Road average $speedText, mileage $mileageText" },
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        CalloutLine(Icons.Outlined.Speed, fg, speedText, fg)
        CalloutLine(Icons.Outlined.LocalGasStation, fg, mileageText, if (c.calibrated) fg else fg.copy(alpha = 0.8f))
    }
}

@Composable
private fun CalloutLine(icon: ImageVector, iconTint: Color, text: String, textColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(15.dp))
        AnimatedContent(
            targetState = text,
            transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) },
            label = "roadCalloutValue"
        ) { t ->
            Text(
                text = t,
                style = CockpitTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = textColor,
                maxLines = 1
            )
        }
    }
}

/** Rounded rectangle with a centred downward tail, as one contour (so the border is continuous). */
private class CalloutShape(private val corner: Dp, private val tailWidth: Dp, private val tailHeight: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val th = with(density) { tailHeight.toPx() }
        val tw = with(density) { tailWidth.toPx() }
        val w = size.width
        val bodyH = (size.height - th).coerceAtLeast(1f)
        val r = with(density) { corner.toPx() }.coerceAtMost(minOf(w, bodyH) / 2f)
        val d = r * 2f
        val cx = w / 2f
        val path = Path().apply {
            moveTo(r, 0f)
            lineTo(w - r, 0f)
            arcTo(Rect(w - d, 0f, w, d), -90f, 90f, false)
            lineTo(w, bodyH - r)
            arcTo(Rect(w - d, bodyH - d, w, bodyH), 0f, 90f, false)
            lineTo(cx + tw / 2f, bodyH)
            lineTo(cx, size.height)
            lineTo(cx - tw / 2f, bodyH)
            lineTo(r, bodyH)
            arcTo(Rect(0f, bodyH - d, d, bodyH), 90f, 90f, false)
            lineTo(0f, r)
            arcTo(Rect(0f, 0f, d, d), 180f, 90f, false)
            close()
        }
        return Outline.Generic(path)
    }
}
