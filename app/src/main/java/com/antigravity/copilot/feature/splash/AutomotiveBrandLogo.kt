package com.antigravity.copilot.feature.splash

import com.antigravity.copilot.core.designsystem.tokens.BrandPalette
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.copilot.core.designsystem.CockpitTheme

/**
 * Static brand crest + wordmark. Paths, brushes and strokes are built once per size
 * (drawWithCache) and the text styles are constant, so the splash can fade / scale this as a
 * cached layer without re-recording it every frame.
 */
@Composable
fun AutomotiveBrandLogo(modifier: Modifier = Modifier) {
    val titleBase = CockpitTheme.typography.displaySmall
    val titleStyle = remember(titleBase) {
        titleBase.copy(
            letterSpacing = 14.sp,
            color = Color(0xFFF1F5F9),
            shadow = Shadow(color = BrandPalette.CyanNeon.copy(alpha = 0.75f), blurRadius = 18f),
            textAlign = TextAlign.Center
        )
    }
    val subtitleBase = CockpitTheme.typography.labelLarge
    val subtitleStyle = remember(subtitleBase) {
        subtitleBase.copy(letterSpacing = 3.sp, color = BrandPalette.Sky300, textAlign = TextAlign.Center)
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(modifier = Modifier.size(160.dp).drawWithCache { buildCrest() })

        Spacer(modifier = Modifier.height(18.dp))
        Text(text = "COPILOT", style = titleStyle)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = "AUTOMOTIVE COCKPIT SYSTEM", style = subtitleStyle)
    }
}

private fun androidx.compose.ui.draw.CacheDrawScope.buildCrest(): androidx.compose.ui.draw.DrawResult {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val r = size.width * 0.44f
    val center = Offset(cx, cy)
    val s = size.width / 108f

    val ambient = Brush.radialGradient(
        colors = listOf(Color(0x3500F0FF), Color(0x180A58CA), Color.Transparent),
        center = center,
        radius = r * 1.3f
    )
    val outerArc = Brush.sweepGradient(
        colors = listOf(
            BrandPalette.CyanNeon,
            BrandPalette.Blue700,
            BrandPalette.CyanBright,
            BrandPalette.Sky400,
            BrandPalette.CyanNeon
        ),
        center = center
    )
    val outerStroke = Stroke(width = 2.8.dp.toPx(), cap = StrokeCap.Round)
    val accentStroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
    val wingStroke = Stroke(width = 1.2.dp.toPx(), join = StrokeJoin.Round)
    val needleStroke = Stroke(width = 1.5.dp.toPx(), join = StrokeJoin.Round)

    fun wing(dir: Float) = Path().apply {
        moveTo(cx, cy - 20f * s)
        lineTo(cx + dir * 22f * s, cy - 4f * s)
        cubicTo(
            cx + dir * 23f * s, cy + 2f * s,
            cx + dir * 20f * s, cy + 10f * s,
            cx + dir * 12f * s, cy + 11f * s
        )
        lineTo(cx + dir * 4f * s, cy + 2f * s)
        lineTo(cx, cy - 10f * s)
        close()
    }
    val leftWing = wing(-1f)
    val rightWing = wing(1f)
    val leftBrush = Brush.linearGradient(
        colors = listOf(BrandPalette.CyanNeon, BrandPalette.Sky600, BrandPalette.Sky700),
        start = Offset(cx - 22f * s, cy - 20f * s),
        end = Offset(cx, cy + 11f * s)
    )
    val rightBrush = Brush.linearGradient(
        colors = listOf(BrandPalette.CyanBright, BrandPalette.Sky600, BrandPalette.Sky700),
        start = Offset(cx + 22f * s, cy - 20f * s),
        end = Offset(cx, cy + 11f * s)
    )
    val needle = Path().apply {
        moveTo(cx, cy - 30f * s)
        lineTo(cx + 6f * s, cy - 6f * s)
        lineTo(cx, cy + 20f * s)
        lineTo(cx - 6f * s, cy - 6f * s)
        close()
    }
    val needleBrush = Brush.verticalGradient(
        colors = listOf(Color.White, BrandPalette.Sky300, BrandPalette.Sky600),
        startY = cy - 30f * s,
        endY = cy + 20f * s
    )
    val wingEdge = BrandPalette.Sky400.copy(alpha = 0.6f)
    val core = Offset(cx, cy - 6f * s)
    val coreR = 3.5.dp.toPx()
    val coreGlowR = 7.dp.toPx()
    val coreGlow = BrandPalette.CyanNeon.copy(alpha = 0.5f)
    val flare = Offset(cx, cy - 31f * s)
    val flareR = 2.dp.toPx()
    val accentArc = BrandPalette.Blue700.copy(alpha = 0.7f)

    return onDrawBehind {
        drawCircle(brush = ambient, center = center, radius = r * 1.3f)
        drawArc(brush = outerArc, startAngle = 140f, sweepAngle = 260f, useCenter = false, style = outerStroke)
        drawArc(color = accentArc, startAngle = 55f, sweepAngle = 70f, useCenter = false, style = accentStroke)
        drawPath(path = leftWing, brush = leftBrush)
        drawPath(path = leftWing, color = wingEdge, style = wingStroke)
        drawPath(path = rightWing, brush = rightBrush)
        drawPath(path = rightWing, color = wingEdge, style = wingStroke)
        drawPath(path = needle, brush = needleBrush)
        drawPath(path = needle, color = Color.White.copy(alpha = 0.85f), style = needleStroke)
        drawCircle(color = Color.White, radius = coreR, center = core)
        drawCircle(color = coreGlow, radius = coreGlowR, center = core)
        drawCircle(color = BrandPalette.CyanNeon, radius = flareR, center = flare)
    }
}
