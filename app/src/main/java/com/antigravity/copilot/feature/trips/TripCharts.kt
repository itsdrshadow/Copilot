package com.antigravity.copilot.feature.trips

import kotlin.math.roundToLong
import com.antigravity.copilot.core.designsystem.tokens.StaticPalette
import com.antigravity.copilot.domain.trip.*
import com.antigravity.copilot.core.designsystem.components.cockpitSurface
import com.antigravity.copilot.core.designsystem.tokens.Palette
import com.antigravity.copilot.core.designsystem.tokens.CopilotShapes
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.copilot.core.designsystem.AutomotiveDesignTokens
import com.antigravity.copilot.core.database.TripEntity
import com.antigravity.copilot.core.util.NumberFormatUtils
import com.antigravity.copilot.core.database.TripPointEntity
import com.antigravity.copilot.core.designsystem.*
import androidx.compose.foundation.Canvas
import java.util.*
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
internal fun SpeedTimeCanvas(
    speeds: List<Double>,
    maxSpeed: Double,
    modifier: Modifier = Modifier
) {
    val accentColor = Palette.Sky400
    val gridColor = CockpitTheme.colors.border

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Horizontal guide lines
        for (i in 0..2) {
            val y = (h / 2) * i
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 1f
            )
        }

        if (speeds.size >= 2) {
            val safeMax = if (maxSpeed > 0) maxSpeed else 60.0
            val stepX = w / (speeds.size - 1).toFloat()

            val path = Path()
            val fillPath = Path()

            val firstY = h * (1f - (speeds.first() / safeMax).coerceIn(0.0, 1.0).toFloat())
            path.moveTo(0f, firstY)
            fillPath.moveTo(0f, h)
            fillPath.lineTo(0f, firstY)

            for (i in 1 until speeds.size) {
                val x = i * stepX
                val y = h * (1f - (speeds[i] / safeMax).coerceIn(0.0, 1.0).toFloat())
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }

            fillPath.lineTo(w, h)
            fillPath.close()

            // Area fill
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(accentColor.copy(alpha = 0.35f), Color.Transparent),
                    startY = 0f,
                    endY = h
                )
            )

            // Outline stroke
            drawPath(
                path = path,
                color = accentColor,
                style = Stroke(width = 2.5f, cap = StrokeCap.Round)
            )
        }
    }
}

/**
 * Trips per day of the week. One colour, no background tracks or badges: the tallest bar already
 * shows the busiest day. Text sizes are in sp so they scale with the panel density.
 */
@Composable
internal fun WeeklyDrivingPatternCanvas(
    distribution: IntArray,
    modifier: Modifier = Modifier
) {
    val barColor = CockpitTheme.colors.accent
    val gridColor = CockpitTheme.colors.border
    val labelColor = CockpitTheme.colors.textSecondary.toArgb()
    val valueColor = CockpitTheme.colors.textPrimary.toArgb()
    val labelPaint = remember { Paint().apply { isAntiAlias = true; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD } }
    val valuePaint = remember { Paint().apply { isAntiAlias = true; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD } }
    val dayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    Canvas(modifier = modifier) {
        labelPaint.textSize = 16.sp.toPx(); labelPaint.color = labelColor
        valuePaint.textSize = 17.sp.toPx(); valuePaint.color = valueColor
        val topPadding = valuePaint.textSize + 10f
        val bottomPadding = labelPaint.textSize + 14f
        val side = 8f
        val graphWidth = size.width - side * 2
        val graphHeight = size.height - topPadding - bottomPadding
        val maxVal = (distribution.maxOrNull() ?: 0).coerceAtLeast(1)
        val colWidth = graphWidth / 7f
        val barWidth = (colWidth * 0.5f).coerceIn(18f, 56f)
        val baseY = topPadding + graphHeight

        drawLine(gridColor, Offset(side, baseY), Offset(size.width - side, baseY), strokeWidth = 1.dp.toPx())

        for (day in 0 until 7) {
            val count = distribution.getOrElse(day) { 0 }
            val centerX = side + colWidth * day + colWidth / 2f
            if (count > 0) {
                val barH = (graphHeight * count.toFloat() / maxVal).coerceAtLeast(8f)
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(centerX - barWidth / 2f, baseY - barH),
                    size = Size(barWidth, barH),
                    cornerRadius = CornerRadius(6f, 6f)
                )
                drawContext.canvas.nativeCanvas.drawText("$count", centerX, baseY - barH - 8f, valuePaint)
            }
            drawContext.canvas.nativeCanvas.drawText(dayNames[day], centerX, size.height - 6f, labelPaint)
        }
    }
}

/**
 * Distance per month. One colour; empty months have no bar at all (no ghost tracks), and the
 * yearly average is a dashed line whose value is shown in the card title, so labels never collide.
 */
@Composable
internal fun MonthlyDistanceCanvas(
    monthlyGroups: List<MonthlyTripGroup>,
    modifier: Modifier = Modifier
) {
    val barColor = CockpitTheme.colors.accent
    val avgColor = CockpitTheme.colors.textSecondary
    val gridColor = CockpitTheme.colors.border
    val labelColor = CockpitTheme.colors.textSecondary.toArgb()
    val valueColor = CockpitTheme.colors.textPrimary.toArgb()
    val labelPaint = remember { Paint().apply { isAntiAlias = true; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD } }
    val valuePaint = remember { Paint().apply { isAntiAlias = true; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD } }

    Canvas(modifier = modifier) {
        labelPaint.textSize = 16.sp.toPx(); labelPaint.color = labelColor
        valuePaint.textSize = 16.sp.toPx(); valuePaint.color = valueColor
        val topPadding = valuePaint.textSize + 10f
        val bottomPadding = labelPaint.textSize + 14f
        val side = 8f
        val graphWidth = size.width - side * 2
        val graphHeight = size.height - topPadding - bottomPadding
        val baseY = topPadding + graphHeight
        val maxKm = (monthlyGroups.maxOfOrNull { it.totalDistanceKm } ?: 0.0).coerceAtLeast(1.0)
        val active = monthlyGroups.filter { it.totalDistanceKm > 0.0 }
        val avgKm = if (active.isNotEmpty()) active.map { it.totalDistanceKm }.average() else 0.0
        val colWidth = graphWidth / 12f
        val barWidth = (colWidth * 0.55f).coerceIn(12f, 40f)

        drawLine(gridColor, Offset(side, baseY), Offset(size.width - side, baseY), strokeWidth = 1.dp.toPx())

        if (avgKm > 0.0) {
            val avgY = baseY - graphHeight * (avgKm / maxKm).toFloat()
            var x = side
            while (x < size.width - side) {
                drawLine(avgColor.copy(alpha = 0.6f), Offset(x, avgY), Offset((x + 10f).coerceAtMost(size.width - side), avgY), strokeWidth = 1.5.dp.toPx())
                x += 16f
            }
        }

        for (m in 0 until 12) {
            val group = monthlyGroups.getOrNull(m)
            val km = group?.totalDistanceKm ?: 0.0
            val centerX = side + colWidth * m + colWidth / 2f
            if (km > 0.0) {
                val barH = (graphHeight * (km / maxKm).toFloat()).coerceAtLeast(6f)
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(centerX - barWidth / 2f, baseY - barH),
                    size = Size(barWidth, barH),
                    cornerRadius = CornerRadius(5f, 5f)
                )
                val valStr = if (km >= 1000) "${NumberFormatUtils.formatPrecision(km / 1000.0, 1)}k" else "${km.roundToLong()}"
                drawContext.canvas.nativeCanvas.drawText(valStr, centerX, baseY - barH - 8f, valuePaint)
            }
            drawContext.canvas.nativeCanvas.drawText(group?.monthName?.take(3) ?: "", centerX, size.height - 6f, labelPaint)
        }
    }
}
