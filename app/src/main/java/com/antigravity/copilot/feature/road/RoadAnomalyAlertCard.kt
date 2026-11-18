package com.antigravity.copilot.feature.road

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.copilot.core.road.AnomalyAlert
import com.antigravity.copilot.core.road.AnomalySeverity
import kotlinx.coroutines.delay

private val AlertYellow = Color(0xFFFACC15)
private val AlertRed = Color(0xFFEF4444)
private val GlassShape = RoundedCornerShape(20.dp)

/** Rounded countdown: 10 m steps far out, 5 m steps inside 50 m. */
internal fun formatAlertDistance(m: Double): String {
    val step = if (m < 50.0) 5 else 10
    val r = ((m / step).toInt() * step).coerceAtLeast(0)
    return "${r}m"
}

/**
 * Top-centre flash card for a pothole/breaker 150–200 m ahead. Slides in when [alert] appears and
 * out when the tracker drops it (passed / turned away). Between 1 Hz GPS fixes the distance is
 * dead-reckoned from the last speed at 4 Hz, so the countdown keeps moving smoothly.
 */
@Composable
fun RoadAnomalyAlertCard(alert: AnomalyAlert?, modifier: Modifier = Modifier) {
    // Keep the last alert while the exit animation plays.
    val last = remember { arrayOfNulls<AnomalyAlert>(1) }
    if (alert != null) last[0] = alert

    AnimatedVisibility(
        visible = alert != null,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier
    ) {
        val a = alert ?: last[0] ?: return@AnimatedVisibility
        var distance by remember { mutableDoubleStateOf(a.distanceM) }
        LaunchedEffect(a) {
            while (true) {
                val dt = (SystemClock.elapsedRealtime() - a.atElapsedMs) / 1000.0
                distance = (a.distanceM - a.speedMps * dt).coerceAtLeast(0.0)
                delay(250)
            }
        }
        GlassAlertBody(severity = a.severity, distanceText = formatAlertDistance(distance))
    }
}

@Composable
private fun GlassAlertBody(severity: AnomalySeverity, distanceText: String) {
    val glow = if (severity == AnomalySeverity.RED) AlertRed else AlertYellow
    val title = if (severity == AnomalySeverity.RED) "Deep Pothole" else "Speed Breaker"
    val pulse by rememberInfiniteTransition(label = "anomalyGlow").animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(650, easing = LinearEasing), RepeatMode.Reverse),
        label = "anomalyGlowAlpha"
    )
    Row(
        modifier = Modifier
            .shadow(18.dp, GlassShape, ambientColor = glow, spotColor = glow)
            .clip(GlassShape)
            // Liquid glass: translucent smoked fill with a soft specular highlight on top.
            .background(Brush.verticalGradient(listOf(Color(0xCC1E2533), Color(0xB30B1018))))
            .background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.14f), 0.45f to Color.Transparent))
            .border(2.dp, glow.copy(alpha = pulse), GlassShape)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).background(glow.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = glow, modifier = Modifier.size(24.dp))
        }
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(" in ", color = Color.White.copy(alpha = 0.75f), fontSize = 20.sp)
                Text(distanceText, color = glow, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
            }
            Text(
                if (severity == AnomalySeverity.RED) "Slow down — harsh impact reported here" else "Ease off — bump reported here",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 14.sp
            )
        }
    }
}
