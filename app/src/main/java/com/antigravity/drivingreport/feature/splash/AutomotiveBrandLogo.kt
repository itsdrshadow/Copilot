package com.antigravity.drivingreport.feature.splash

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
                            Color(0xFF00F0FF),
                            Color(0xFF0A58CA),
                            Color(0xFF00D2FF),
                            Color(0xFF38BDF8),
                            Color(0xFF00F0FF)
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
                    color = Color(0xFF0A58CA).copy(alpha = (0.7f * glowIntensity).coerceIn(0f, 1f)),
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
                            Color(0xFF00F0FF),
                            Color(0xFF0284C7),
                            Color(0xFF0369A1)
                        ),
                        start = Offset(cx - 22f * wingScale, cy - 20f * wingScale),
                        end = Offset(cx, cy + 11f * wingScale)
                    )
                )
                drawPath(
                    path = leftWing,
                    color = Color(0xFF38BDF8).copy(alpha = (0.6f * glowIntensity).coerceIn(0f, 1f)),
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
                            Color(0xFF00D2FF),
                            Color(0xFF0284C7),
                            Color(0xFF0369A1)
                        ),
                        start = Offset(cx + 22f * wingScale, cy - 20f * wingScale),
                        end = Offset(cx, cy + 11f * wingScale)
                    )
                )
                drawPath(
                    path = rightWing,
                    color = Color(0xFF38BDF8).copy(alpha = (0.6f * glowIntensity).coerceIn(0f, 1f)),
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
                            Color(0xFF7DD3FC),
                            Color(0xFF0284C7)
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
                    color = Color(0xFF00F0FF).copy(alpha = (0.5f * glowIntensity).coerceIn(0f, 1f)),
                    radius = 7.dp.toPx(),
                    center = Offset(cx, cy - 6f * wingScale)
                )

                // 6. Apex Tip Flare
                drawCircle(
                    color = Color(0xFF00F0FF),
                    radius = 2.dp.toPx(),
                    center = Offset(cx, cy - 31f * wingScale)
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Brand Title
        Text(
            text = "DRIVING REPORT",
            style = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Black,
                fontSize = 28.sp,
                letterSpacing = 12.sp,
                color = Color(0xFFF1F5F9),
                shadow = Shadow(
                    color = Color(0xFF00F0FF).copy(alpha = (0.75f * glowIntensity).coerceIn(0f, 0.75f)),
                    blurRadius = 18f
                ),
                textAlign = TextAlign.Center
            )
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Brand Subtitle
        Text(
            text = "AUTOMOTIVE COCKPIT SYSTEM",
            style = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                letterSpacing = 6.sp,
                color = Color(0xFF7DD3FC),
                textAlign = TextAlign.Center
            )
        )
    }
}
