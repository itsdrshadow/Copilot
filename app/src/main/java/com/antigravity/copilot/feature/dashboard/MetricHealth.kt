package com.antigravity.copilot.feature.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.antigravity.copilot.core.designsystem.CockpitTheme

/**
 * Continuous "health" colouring for metrics: score 0f (bad) → red, 0.5f → yellow, 1f (good) → green.
 * The colour is interpolated, never stepped, and [animatedHealthColor] glides between values so a
 * live change slides smoothly from one hue to the next instead of jumping.
 */
object MetricHealth {
    /** Mileage vs the vehicle's baseline km/L: ≤70 % of baseline is red, 100 % is yellow-green, ≥115 % is green. */
    fun mileageScore(kmpl: Double, baselineKmpl: Double): Float {
        if (kmpl <= 0.0 || baselineKmpl <= 0.0) return 0.5f
        return (((kmpl / baselineKmpl) - 0.70) / (1.15 - 0.70)).toFloat().coerceIn(0f, 1f)
    }

    /** Drive score 50 → red … 90+ → green. */
    fun driveScore(points: Int): Float = ((points - 50) / 40f).coerceIn(0f, 1f)

    /** Peak braking (m/s², negative): -1 is gentle (green), -6 is harsh (red). */
    fun brakingScore(decelMss: Float): Float = (1f - ((-decelMss - 1f) / 5f)).coerceIn(0f, 1f)

    /** Fuel level percent: 10 % → red … 60 %+ → green. */
    fun fuelPercentScore(percent: Double): Float = ((percent - 10.0) / 50.0).toFloat().coerceIn(0f, 1f)

    @Composable
    fun colorFor(score: Float): Color {
        val c = CockpitTheme.colors
        return if (score < 0.5f) lerp(c.critical, c.warning, score * 2f)
        else lerp(c.warning, c.positiveText, (score - 0.5f) * 2f)
    }
}

/** Score → colour that eases (≈0.9 s) towards the new hue whenever the score changes. */
@Composable
fun animatedHealthColor(score: Float): Color =
    animateColorAsState(
        targetValue = MetricHealth.colorFor(score),
        animationSpec = tween(900, easing = FastOutSlowInEasing),
        label = "HealthColor"
    ).value

/** Red→yellow→green track with a marker that slides to the score. */
@Composable
fun HealthMeterBar(score: Float, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val c = CockpitTheme.colors
    val animScore by animateFloatAsState(score, tween(900, easing = FastOutSlowInEasing), label = "HealthScore")
    val color = animatedHealthColor(score)
    BoxWithConstraints(modifier = modifier.fillMaxWidth().height(height + 6.dp), contentAlignment = Alignment.CenterStart) {
        Box(
            Modifier.fillMaxWidth().height(height).clip(CircleShape)
                .background(Brush.horizontalGradient(listOf(c.critical, c.warning, c.positiveText)).let { it }, alpha = 0.35f)
        )
        Box(
            Modifier.fillMaxWidth(animScore.coerceAtLeast(0.04f)).height(height).clip(CircleShape)
                .background(Brush.horizontalGradient(listOf(c.critical, c.warning, c.positiveText)))
        )
        val markerSize = height + 6.dp
        Box(
            Modifier.offset(x = (maxWidth - markerSize) * animScore).size(markerSize)
                .clip(CircleShape).background(color)
        )
    }
}
