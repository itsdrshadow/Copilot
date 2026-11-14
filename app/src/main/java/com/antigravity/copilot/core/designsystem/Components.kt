package com.antigravity.copilot.core.designsystem

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.copilot.core.util.NumberFormatUtils
import kotlin.math.cos
import kotlin.math.sin

val Cockpit_CardCorner = 24.dp
val Cockpit_InnerTileCorner = 18.dp

@Composable
fun CockpitCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = Cockpit_CardSurface.copy(alpha = 0.95f),
    borderColor: Color = Cockpit_CardBorder,
    cornerRadius: Dp = Cockpit_CardCorner,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(backgroundColor)
            .border(1.2.dp, borderColor, RoundedCornerShape(cornerRadius))
            .padding(contentPadding)
    ) {
        Column(content = content)
    }
}

@Composable
fun DashboardCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = Cockpit_CardSurface,
    borderColor: Color = Cockpit_CardBorder,
    cornerRadius: Dp = Cockpit_CardCorner,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    CockpitCard(
        modifier = modifier,
        backgroundColor = backgroundColor,
        borderColor = borderColor,
        cornerRadius = cornerRadius,
        contentPadding = contentPadding,
        content = content
    )
}

@Composable
fun MetricTile(
    label: String,
    value: String,
    unit: String = "",
    icon: ImageVector? = null,
    accentColor: Color = Cockpit_BlueAccent,
    subValue: String? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Cockpit_InnerTileCorner))
            .background(Cockpit_CardSurfaceElevated)
            .border(1.dp, Cockpit_CardBorder, RoundedCornerShape(Cockpit_InnerTileCorner))
            .padding(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
                color = Cockpit_TextTertiary
            )
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = accentColor,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp),
                color = Cockpit_TextPrimary
            )
            if (unit.isNotEmpty()) {
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = unit,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
                    color = Cockpit_TextSecondary,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
        }
        if (subValue != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subValue,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = Cockpit_TextSecondary
            )
        }
    }
}

@Composable
fun MidFuelBarVisualizer(
    totalBars: Int = 10,
    currentBar: Int = 10,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        for (i in 1..totalBars) {
            val isFilled = i <= currentBar
            val barColor = when {
                !isFilled -> CockpitTheme.colors.border
                i <= 2 -> CockpitTheme.colors.critical
                i <= 4 -> CockpitTheme.colors.warning
                else -> CockpitTheme.colors.accent
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(16.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(barColor)
            )
        }
    }
}

// Master Semi-Circular Speedometer exactly matching user image
@Composable
fun CockpitMasterSpeedometer(
    speedKmh: Float,
    maxSpeed: Float = 160f,
    driverScore: Int = 94,
    speedLimit: Int = 80,
    modifier: Modifier = Modifier
) {
    val animatedSpeed by animateFloatAsState(
        targetValue = speedKmh.coerceIn(0f, maxSpeed),
        animationSpec = spring(
            dampingRatio = 0.82f,
            stiffness = Spring.StiffnessLow
        ),
        label = "MasterSpeedSpring"
    )

    Box(
        modifier = modifier
            .width(280.dp)
            .height(200.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 14.dp.toPx()
            val startAngle = 150f
            val totalSweep = 240f
            val progressFraction = (animatedSpeed / maxSpeed).coerceIn(0f, 1f)
            val currentProgressSweep = progressFraction * totalSweep

            val arcSize = Size(size.width - strokeWidth * 2, size.width - strokeWidth * 2)
            val topLeft = Offset(strokeWidth, strokeWidth)

            // Outer Neon Glow Arc Track
            drawArc(
                color = Cockpit_CardSurfaceElevated,
                startAngle = startAngle,
                sweepAngle = totalSweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Glowing Active Sweep (Cyan -> Electric Blue -> Neon Purple)
            if (currentProgressSweep > 0.5f) {
                val activeBrush = Brush.sweepGradient(
                    listOf(Cockpit_Cyan, Cockpit_BlueAccent, Cockpit_PurpleGlow, Cockpit_PurpleGlow),
                    center = Offset(size.width / 2, size.width / 2)
                )

                drawArc(
                    brush = activeBrush,
                    startAngle = startAngle,
                    sweepAngle = currentProgressSweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }

            // Draw Speed Ticks & Numerical Labels (0, 20, 40, 60, 80, 100, 120, 140, 160)
            val tickValues = listOf(0, 20, 40, 60, 80, 100, 120, 140, 160)
            val radius = size.width / 2 - strokeWidth - 10.dp.toPx()
            val center = Offset(size.width / 2, size.width / 2)

            tickValues.forEachIndexed { index, _ ->
                val fraction = index.toFloat() / (tickValues.size - 1)
                val angleDeg = startAngle + fraction * totalSweep
                val angleRad = Math.toRadians(angleDeg.toDouble())

                val startOffset = Offset(
                    (center.x + (radius - 6.dp.toPx()) * cos(angleRad)).toFloat(),
                    (center.y + (radius - 6.dp.toPx()) * sin(angleRad)).toFloat()
                )
                val endOffset = Offset(
                    (center.x + radius * cos(angleRad)).toFloat(),
                    (center.y + radius * sin(angleRad)).toFloat()
                )

                drawLine(
                    color = if (fraction <= progressFraction) Cockpit_Cyan else Cockpit_TextTertiary.copy(alpha = 0.6f),
                    start = startOffset,
                    end = endOffset,
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }

        // Center Readout & Bottom Status Capsule
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 40.dp)
        ) {
            // Speed Numbers (0, 20, 40, 80, 100, 120, 140, 160 labels hint)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("40", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = Cockpit_TextSecondary)
                Text("80", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = Cockpit_TextSecondary)
                Text("120", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = Cockpit_TextSecondary)
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Giant Center Speed Display
            Text(
                text = animatedSpeed.toInt().toString(),
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = 58.sp,
                    fontWeight = FontWeight.Black
                ),
                color = Cockpit_TextPrimary
            )
            Text(
                text = "km/h",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                ),
                color = Cockpit_TextSecondary
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Bottom Floating Capsule: [80 Speed Limit] | [Steering Wheel Driver Score 94]
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Cockpit_CardSurfaceElevated)
                    .border(1.dp, Cockpit_CardBorder, RoundedCornerShape(16.dp))
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            ) {
                // European/Indian Standard Speed Limit Sign (Red ring with white circle)
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(2.5.dp, Cockpit_RedAlert, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "$speedLimit",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Black,
                            fontSize = 10.sp,
                            color = Color.Black
                        )
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                // Vertical Divider
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(18.dp)
                        .background(Cockpit_CardBorder)
                )

                Spacer(modifier = Modifier.width(10.dp))

                // Green Driver Score Icon & Label
                Icon(
                    imageVector = Icons.Default.DirectionsCar,
                    contentDescription = null,
                    tint = Cockpit_GreenEco,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = "Driver Score",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = Cockpit_TextTertiary
                    )
                    Text(
                        text = "$driverScore",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        ),
                        color = Cockpit_TextPrimary
                    )
                }
            }
        }
    }
}

// Master Circular Fuel Gauge matching user image
@Composable
fun CockpitCircularFuelGauge(
    remainingLiters: Double,
    percentage: Int,
    rangeKm: Double,
    mileageKmpl: Double,
    modifier: Modifier = Modifier
) {
    val progress = (percentage / 100f).coerceIn(0f, 1f)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left Radial Ring with fuel liters & percentage inside
            Box(
                modifier = Modifier.size(105.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                    val stroke = 8.dp.toPx()
                    // Track
                    drawArc(
                        color = Cockpit_CardSurfaceElevated,
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = Stroke(width = stroke)
                    )
                    // Active Green Arc
                    drawArc(
                        color = Cockpit_GreenEco,
                        startAngle = -90f,
                        sweepAngle = progress * 360f,
                        useCenter = false,
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.LocalGasStation,
                        contentDescription = null,
                        tint = Cockpit_GreenEco,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = NumberFormatUtils.formatFuelLitersWithUnit(remainingLiters, 2),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        ),
                        color = Cockpit_TextPrimary
                    )
                    Text(
                        text = "≈ $percentage%",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = Cockpit_TextSecondary
                    )
                }
            }

            // Right Telemetry Metrics (Range + Mileage)
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(start = 12.dp)
            ) {
                // Range
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Cockpit_BlueAccent.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Route,
                            contentDescription = null,
                            tint = Cockpit_BlueAccent,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("Range", style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp), color = Cockpit_TextTertiary)
                        Text(
                            "${rangeKm.toInt()} km",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp),
                            color = Cockpit_TextPrimary
                        )
                    }
                }

                // Mileage
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Cockpit_Cyan.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.LocalGasStation,
                            contentDescription = null,
                            tint = Cockpit_Cyan,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("Mileage", style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp), color = Cockpit_TextTertiary)
                        Text(
                            NumberFormatUtils.formatMileageWithUnit(mileageKmpl, 2),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp),
                            color = Cockpit_TextPrimary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Bottom E ----- F Horizontal Fuel Slider Bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("E", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = Cockpit_TextSecondary)
            Spacer(modifier = Modifier.width(8.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(7.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Cockpit_CardSurfaceElevated)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = progress)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(Cockpit_GreenEco.copy(alpha = 0.5f), Cockpit_GreenEco)
                            )
                        )
                )
            }

            Spacer(modifier = Modifier.width(8.dp))
            Text("F", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = Cockpit_TextSecondary)
        }
    }
}

// Master Circular Driving Score Widget matching user image
@Composable
fun CockpitCircularScoreGauge(
    score: Int = 94,
    smoothness: Int = 96,
    braking: Int = 92,
    acceleration: Int = 95,
    modifier: Modifier = Modifier
) {
    val progress = (score / 100f).coerceIn(0f, 1f)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left Radial Score Ring with 94 / 100 inside
            Box(
                modifier = Modifier.size(95.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize().padding(6.dp)) {
                    val stroke = 8.dp.toPx()
                    drawArc(
                        color = Cockpit_CardSurfaceElevated,
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = Stroke(width = stroke)
                    )
                    drawArc(
                        color = Cockpit_GreenEco,
                        startAngle = -90f,
                        sweepAngle = progress * 360f,
                        useCenter = false,
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "$score",
                        style = MaterialTheme.typography.displayMedium.copy(
                            fontWeight = FontWeight.Black,
                            fontSize = 28.sp
                        ),
                        color = Cockpit_TextPrimary
                    )
                    Text(
                        text = "/100",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = Cockpit_TextSecondary
                    )
                }
            }

            // Right Telemetry Checklist
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(start = 12.dp)
            ) {
                ScoreChecklistItem(label = "Smoothness", percent = smoothness)
                ScoreChecklistItem(label = "Braking", percent = braking)
                ScoreChecklistItem(label = "Acceleration", percent = acceleration)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Bottom Banner Pill: [Thumbs Up] Excellent Driving! Keep it up!
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Cockpit_GreenEco.copy(alpha = 0.15f))
                .border(1.dp, Cockpit_GreenEco.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.ThumbUp,
                contentDescription = null,
                tint = Cockpit_GreenEco,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (score >= 90) "Excellent Driving! Keep it up!" else "Good Driving Pace",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                ),
                color = Cockpit_GreenEco
            )
        }
    }
}

@Composable
private fun ScoreChecklistItem(label: String, percent: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            tint = Cockpit_GreenEco,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = Cockpit_TextTertiary
            )
            Text(
                text = "$percent%",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                ),
                color = Cockpit_TextPrimary
            )
        }
    }
}

// ==================== COOLWALK 2026 SPECIALIZED COMPONENTS ====================

@Composable
fun CoolwalkSpeedArcGauge(
    speedKmh: Float,
    speedLimit: Float = 50f,
    maxSpeed: Float = 160f,
    modifier: Modifier = Modifier
) {
    val animatedSpeed by animateFloatAsState(
        targetValue = speedKmh.coerceIn(0f, maxSpeed),
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow),
        label = "ArcSpeed"
    )

    val isOverLimit = speedKmh > speedLimit
    val isNearLimit = speedKmh >= (speedLimit * 0.88f) && !isOverLimit

    val arcGradient = when {
        isOverLimit -> listOf(CockpitTheme.colors.critical, CockpitTheme.colors.critical)
        isNearLimit -> listOf(CockpitTheme.colors.warning, CockpitTheme.colors.warning)
        else -> listOf(CockpitTheme.colors.accent, CockpitTheme.colors.accent)
    }

    val trackColor = CockpitTheme.colors.border

    Canvas(modifier = modifier) {
        val strokeWidth = 10.dp.toPx()
        val diameter = size.minDimension - strokeWidth * 2
        val arcTopLeft = Offset(
            (size.width - diameter) / 2,
            (size.height - diameter) / 2
        )
        val arcSize = Size(diameter, diameter)

        // Subtle background track arc
        drawArc(
            color = trackColor,
            startAngle = 135f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )

        // Active dynamic warning progress arc
        val sweepAngle = (animatedSpeed / maxSpeed).coerceIn(0f, 1f) * 270f
        if (sweepAngle > 1f) {
            drawArc(
                brush = Brush.sweepGradient(
                    arcGradient,
                    center = Offset(size.width / 2, size.height / 2)
                ),
                startAngle = 135f,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
fun WaveformAudioProgressBar(
    progress: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val width = size.width
        val midY = size.height / 2
        val totalWaves = 28
        val waveWidth = width / totalWaves

        val playedX = width * progress.coerceIn(0f, 1f)

        // Draw played wavy section
        val playedPath = androidx.compose.ui.graphics.Path()
        playedPath.moveTo(0f, midY)
        var x = 0f
        var i = 0
        while (x < playedX) {
            val amplitude = if (i % 2 == 0) 6.dp.toPx() else -6.dp.toPx()
            val nextX = (x + waveWidth).coerceAtMost(playedX)
            playedPath.quadraticBezierTo(
                x + (nextX - x) / 2,
                midY + amplitude,
                nextX,
                midY
            )
            x = nextX
            i++
        }

        drawPath(
            path = playedPath,
            color = Color.White,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        )

        // Playhead circle dot
        drawCircle(
            color = Color.White,
            radius = 4.dp.toPx(),
            center = Offset(playedX, midY)
        )

        // Draw unplayed remaining flat/subtle line
        if (playedX < width) {
            drawLine(
                color = Color.White.copy(alpha = 0.3f),
                start = Offset(playedX, midY),
                end = Offset(width, midY),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
fun ScallopedRingPattern(
    modifier: Modifier = Modifier,
    color: Color = Color.White.copy(alpha = 0.08f)
) {
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val petals = 12
        val baseRadius = size.minDimension * 0.36f
        val scallopRadius = size.minDimension * 0.10f

        for (i in 0 until petals) {
            val angle = Math.toRadians((i * (360.0 / petals)).toDouble())
            val cx = (center.x + baseRadius * cos(angle)).toFloat()
            val cy = (center.y + baseRadius * sin(angle)).toFloat()
            drawCircle(
                color = color,
                radius = scallopRadius,
                center = Offset(cx, cy)
            )
        }
    }
}

