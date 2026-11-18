package com.antigravity.copilot.feature.maps

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.copilot.core.fuel.station.StationBubble
import com.antigravity.copilot.core.fuel.station.StationQuality

private val BubbleShape = RoundedCornerShape(14.dp)
private val TailShape = GenericShape { size, _ ->
    moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2f, size.height); close()
}

internal fun qualityColor(q: StationQuality): Color = when (q) {
    StationQuality.EXCELLENT -> Color(0xFF22C55E)
    StationQuality.AVERAGE -> Color(0xFFFACC15)
    StationQuality.POOR -> Color(0xFFEF4444)
    StationQuality.NOT_AVAILABLE -> Color(0xFF94A3B8)
}

/**
 * Liquid-glass bubble above the single closest saved station ahead. Positioned in the draw phase
 * from [anchor] (projected per camera frame by CarMapController), like [RoadCalloutBubble].
 * Place it in the map Box with `Modifier.matchParentSize()`.
 */
@Composable
fun FuelStationBubble(bubble: StationBubble?, anchor: CalloutAnchorState, modifier: Modifier = Modifier) {
    val last = remember { arrayOfNulls<StationBubble>(1) }
    if (bubble != null) last[0] = bubble
    val width = remember { mutableIntStateOf(0) }
    val height = remember { mutableIntStateOf(0) }

    Box(modifier) {
        AnimatedVisibility(
            visible = bubble != null,
            enter = fadeIn(tween(300)) + scaleIn(tween(300), initialScale = 0.85f, transformOrigin = TransformOrigin(0.5f, 1f)),
            exit = fadeOut(tween(400)) + scaleOut(tween(400), targetScale = 0.9f, transformOrigin = TransformOrigin(0.5f, 1f)),
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
            (bubble ?: last[0])?.let { BubbleCard(it) }
        }
    }
}

@Composable
private fun BubbleCard(b: StationBubble) {
    val tint = qualityColor(b.quality)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .shadow(10.dp, BubbleShape, ambientColor = tint, spotColor = tint)
                .clip(BubbleShape)
                .background(Brush.verticalGradient(listOf(Color(0xCC1E2533), Color(0xB30B1018))))
                .background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.14f), 0.5f to Color.Transparent))
                .border(1.5.dp, tint.copy(alpha = 0.85f), BubbleShape)
                .padding(horizontal = 12.dp, vertical = 7.dp)
        ) {
            Text(
                b.text,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box(Modifier.size(width = 14.dp, height = 8.dp).clip(TailShape).background(tint.copy(alpha = 0.85f)))
    }
}
