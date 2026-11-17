package com.antigravity.copilot.feature.splash

import com.antigravity.copilot.core.designsystem.tokens.BrandPalette
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.copilot.core.designsystem.CockpitTheme

@Composable
fun AutomotiveBrandLogo(
    modifier: Modifier = Modifier,
    glowIntensity: Float = 1.0f
) {
    val leftWing = remember { Path() }
    val rightWing = remember { Path() }
    val centerNeedle = remember { Path() }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Logo Emblem
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(160.dp)
        ) {
            Canvas(modifier = Modifier.size(160.dp)) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val r = size.width * 0.44f

                // 1. Ambient Radial Glow behind the crest
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0x3500F0FF),
                            Color(0x180A58CA),
                            Color.Transparent
                        ),
                        center = Offset(cx, cy),
                        radius = r * 1.3f
                    ),
                    center = Offset(cx, cy),
                    radius = r * 1.3f
                )

                // 2. Outer Telemetry Arc with Glow
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            BrandPalette.CyanNeon,
                            BrandPalette.Blue700,
                            BrandPalette.CyanBright,
                            BrandPalette.Sky400,
                            BrandPalette.CyanNeon
                        ),
                        center = Offset(cx, cy)
                    ),
                    startAngle = 140f,
                    sweepAngle = 260f,
                    useCenter = false,
                    style = Stroke(
                        width = 2.8.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                )

                // Secondary bottom accent arc
                drawArc(
                    color = BrandPalette.Blue700.copy(alpha = (0.7f * glowIntensity).coerceIn(0f, 1f)),
                    startAngle = 55f,
                    sweepAngle = 70f,
                    useCenter = false,
                    style = Stroke(
                        width = 2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                )

                // 3. Swept Aerodynamic Wing Blades
                val wingScale = size.width / 108f

                leftWing.reset()
                leftWing.moveTo(cx, cy - 20f * wingScale)
                leftWing.lineTo(cx - 22f * wingScale, cy - 4f * wingScale)
                leftWing.cubicTo(
                    cx - 23f * wingScale, cy + 2f * wingScale,
                    cx - 20f * wingScale, cy + 10f * wingScale,
                    cx - 12f * wingScale, cy + 11f * wingScale
                )
                leftWing.lineTo(cx - 4f * wingScale, cy + 2f * wingScale)
                leftWing.lineTo(cx, cy - 10f * wingScale)
                leftWing.close()

                drawPath(
                    path = leftWing,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            BrandPalette.CyanNeon,
                            BrandPalette.Sky600,
                            BrandPalette.Sky700
                        ),
                        start = Offset(cx - 22f * wingScale, cy - 20f * wingScale),
                        end = Offset(cx, cy + 11f * wingScale)
                    )
                )
                drawPath(
                    path = leftWing,
                    color = BrandPalette.Sky400.copy(alpha = (0.6f * glowIntensity).coerceIn(0f, 1f)),
                    style = Stroke(width = 1.2.dp.toPx(), join = StrokeJoin.Round)
                )

                rightWing.reset()
                rightWing.moveTo(cx, cy - 20f * wingScale)
                rightWing.lineTo(cx + 22f * wingScale, cy - 4f * wingScale)
                rightWing.cubicTo(
                    cx + 23f * wingScale, cy + 2f * wingScale,
                    cx + 20f * wingScale, cy + 10f * wingScale,
                    cx + 12f * wingScale, cy + 11f * wingScale
                )
                rightWing.lineTo(cx + 4f * wingScale, cy + 2f * wingScale)
                rightWing.lineTo(cx, cy - 10f * wingScale)
                rightWing.close()

                drawPath(
                    path = rightWing,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            BrandPalette.CyanBright,
                            BrandPalette.Sky600,
                            BrandPalette.Sky700
                        ),
                        start = Offset(cx + 22f * wingScale, cy - 20f * wingScale),
                        end = Offset(cx, cy + 11f * wingScale)
                    )
                )
                drawPath(
                    path = rightWing,
                    color = BrandPalette.Sky400.copy(alpha = (0.6f * glowIntensity).coerceIn(0f, 1f)),
                    style = Stroke(width = 1.2.dp.toPx(), join = StrokeJoin.Round)
                )

                // 4. Center Apex Needle / Diamond
                centerNeedle.reset()
                centerNeedle.moveTo(cx, cy - 30f * wingScale)
                centerNeedle.lineTo(cx + 6f * wingScale, cy - 6f * wingScale)
                centerNeedle.lineTo(cx, cy + 20f * wingScale)
                centerNeedle.lineTo(cx - 6f * wingScale, cy - 6f * wingScale)
                centerNeedle.close()

                drawPath(
                    path = centerNeedle,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFFFFFFFF),
                            BrandPalette.Sky300,
                            BrandPalette.Sky600
                        ),
                        startY = cy - 30f * wingScale,
                        endY = cy + 20f * wingScale
                    )
                )
                drawPath(
                    path = centerNeedle,
                    color = Color(0xFFFFFFFF).copy(alpha = (0.85f * glowIntensity).coerceIn(0f, 1f)),
                    style = Stroke(width = 1.5.dp.toPx(), join = StrokeJoin.Round)
                )

                // 5. Center Core Glow Point
                drawCircle(
                    color = Color.White,
                    radius = 3.5.dp.toPx(),
                    center = Offset(cx, cy - 6f * wingScale)
                )
                drawCircle(
                    color = BrandPalette.CyanNeon.copy(alpha = (0.5f * glowIntensity).coerceIn(0f, 1f)),
                    radius = 7.dp.toPx(),
                    center = Offset(cx, cy - 6f * wingScale)
                )

                // 6. Apex Tip Flare
                drawCircle(
                    color = BrandPalette.CyanNeon,
                    radius = 2.dp.toPx(),
                    center = Offset(cx, cy - 31f * wingScale)
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Brand Title
        Text(
            text = "COPILOT",
            style = CockpitTheme.typography.displaySmall.copy(
                letterSpacing = 14.sp,
                color = Color(0xFFF1F5F9),
                shadow = Shadow(
                    color = BrandPalette.CyanNeon.copy(alpha = (0.75f * glowIntensity).coerceIn(0f, 0.75f)),
                    blurRadius = 18f
                ),
                textAlign = TextAlign.Center
            )
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Brand Subtitle
        Text(
            text = "AUTOMOTIVE COCKPIT SYSTEM",
            style = CockpitTheme.typography.labelSmall.copy(
                letterSpacing = 6.sp,
                color = BrandPalette.Sky300,
                textAlign = TextAlign.Center
            )
        )
    }
}
