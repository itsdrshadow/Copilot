package com.antigravity.copilot.feature.trips

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

@Composable
internal fun WeeklyDrivingPatternCanvas(
    distribution: IntArray,
    modifier: Modifier = Modifier
) {
    val weekdayAccent = Palette.Sky400
    val weekendAccent = Palette.Purple500
    val gridColor = CockpitTheme.colors.border

    val dayLabelPaint = remember {
        Paint().apply {
            color = android.graphics.Color.parseColor("#94A3B8")
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
    }
    val countValuePaint = remember {
        Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 18f
            isFakeBoldText = true
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
    }
    val peakBadgePaint = remember {
        Paint().apply {
            color = android.graphics.Color.parseColor("#38BDF8")
            textSize = 13f
            isFakeBoldText = true
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
    }
    val zoneHeaderPaint = remember {
        Paint().apply {
            color = android.graphics.Color.parseColor("#64748B")
            textSize = 13f
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
    }

    val dayNames = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")

    Canvas(modifier = modifier) {
        val totalWidth = size.width
        val totalHeight = size.height

        val topPadding = 42f
        val bottomPadding = 32f
        val leftPadding = 24f
        val rightPadding = 24f

        val graphWidth = totalWidth - leftPadding - rightPadding
        val graphHeight = totalHeight - topPadding - bottomPadding

        val maxVal = (distribution.maxOrNull() ?: 0).coerceAtLeast(1)
        val peakDay = distribution.indices.maxByOrNull { distribution[it] } ?: -1
        val colWidth = graphWidth / 7f
        val barWidth = (colWidth * 0.46f).coerceIn(24f, 64f)

        // 1. Weekday vs Weekend Zone Backgrounds
        val weekdayWidth = colWidth * 5f
        val weekendWidth = colWidth * 2f

        // Weekday zone background (Mon-Fri)
        drawRoundRect(
            color = StaticPalette.Slate900.copy(alpha = 0.35f),
            topLeft = Offset(leftPadding, topPadding),
            size = Size(weekdayWidth, graphHeight),
            cornerRadius = CornerRadius(6f, 6f)
        )
        drawContext.canvas.nativeCanvas.drawText(
            "WEEKDAYS (MON - FRI)",
            leftPadding + weekdayWidth / 2f,
            topPadding - 16f,
            zoneHeaderPaint
        )

        // Weekend zone background (Sat-Sun)
        drawRoundRect(
            color = StaticPalette.overlapBlue.copy(alpha = 0.22f),
            topLeft = Offset(leftPadding + weekdayWidth, topPadding),
            size = Size(weekendWidth, graphHeight),
            cornerRadius = CornerRadius(6f, 6f)
        )
        drawContext.canvas.nativeCanvas.drawText(
            "WEEKEND (SAT - SUN)",
            leftPadding + weekdayWidth + weekendWidth / 2f,
            topPadding - 16f,
            zoneHeaderPaint
        )

        // Zone separator line
        drawLine(
            color = StaticPalette.Ink725,
            start = Offset(leftPadding + weekdayWidth, topPadding - 20f),
            end = Offset(leftPadding + weekdayWidth, topPadding + graphHeight),
            strokeWidth = 1.2f
        )

        // Horizontal baseline
        drawLine(
            color = gridColor,
            start = Offset(leftPadding, topPadding + graphHeight),
            end = Offset(totalWidth - rightPadding, topPadding + graphHeight),
            strokeWidth = 1f
        )

        // 2. Full-height Recessed Channel Tracks for all 7 days
        for (day in 0 until 7) {
            val centerX = leftPadding + colWidth * day + colWidth / 2f
            val barLeft = centerX - barWidth / 2f

            drawRoundRect(
                color = StaticPalette.border,
                topLeft = Offset(barLeft, topPadding),
                size = Size(barWidth, graphHeight),
                cornerRadius = CornerRadius(6f, 6f)
            )
            drawRoundRect(
                color = StaticPalette.accentSoft,
                topLeft = Offset(barLeft, topPadding),
                size = Size(barWidth, graphHeight),
                cornerRadius = CornerRadius(6f, 6f),
                style = Stroke(0.8f)
            )
        }

        // 3. Daily Bars
        for (day in 0 until 7) {
            val count = if (day in distribution.indices) distribution[day] else 0
            val centerX = leftPadding + colWidth * day + colWidth / 2f
            val barLeft = centerX - barWidth / 2f
            val isPeak = day == peakDay && count > 0
            val isWeekend = day >= 5
            val barAccent = if (isWeekend) weekendAccent else weekdayAccent

            if (count > 0) {
                val ratio = (count.toFloat() / maxVal).coerceIn(0f, 1f)
                val barH = (graphHeight * ratio).coerceAtLeast(10f)
                val barTop = topPadding + graphHeight - barH

                // Gradient bar
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = if (isPeak) {
                            listOf(barAccent, barAccent.copy(alpha = 0.40f))
                        } else {
                            listOf(barAccent.copy(alpha = 0.85f), barAccent.copy(alpha = 0.20f))
                        },
                        startY = barTop,
                        endY = topPadding + graphHeight
                    ),
                    topLeft = Offset(barLeft, barTop),
                    size = Size(barWidth, barH),
                    cornerRadius = CornerRadius(6f, 6f)
                )

                // Top highlight rim
                drawRoundRect(
                    color = if (isPeak) StaticPalette.textPrimary else barAccent,
                    topLeft = Offset(barLeft, barTop),
                    size = Size(barWidth, 4f),
                    cornerRadius = CornerRadius(2f, 2f)
                )

                // Peak beacon & tag
                if (isPeak) {
                    drawCircle(color = barAccent.copy(alpha = 0.35f), radius = 10f, center = Offset(centerX, barTop))
                    drawCircle(color = StaticPalette.textPrimary, radius = 4f, center = Offset(centerX, barTop))
                    peakBadgePaint.color = barAccent.toArgb()
                    drawContext.canvas.nativeCanvas.drawText("★ PEAK DAY", centerX, barTop - 24f, peakBadgePaint)
                }

                // Trip count text above bar
                drawContext.canvas.nativeCanvas.drawText(
                    "$count",
                    centerX,
                    barTop - 7f,
                    countValuePaint
                )
            } else {
                drawRoundRect(
                    color = StaticPalette.border,
                    topLeft = Offset(barLeft, topPadding + graphHeight - 4f),
                    size = Size(barWidth, 4f),
                    cornerRadius = CornerRadius(2f, 2f)
                )
            }

            // Draw day labels below baseline
            val dayLabel = dayNames[day]
            dayLabelPaint.color = when {
                isPeak -> barAccent.toArgb()
                count > 0 -> android.graphics.Color.WHITE
                isWeekend -> weekendAccent.copy(alpha = 0.7f).toArgb()
                else -> android.graphics.Color.parseColor("#94A3B8")
            }
            drawContext.canvas.nativeCanvas.drawText(
                dayLabel,
                centerX,
                totalHeight - 8f,
                dayLabelPaint
            )
        }
    }
}

@Composable
internal fun MonthlyDistanceCanvas(
    monthlyGroups: List<MonthlyTripGroup>,
    modifier: Modifier = Modifier
) {
    val accentColor = Palette.Amber500
    val gridColor = CockpitTheme.colors.border

    val axisTextPaint = remember {
        Paint().apply {
            color = android.graphics.Color.parseColor("#64748B")
            textSize = 15f
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
            textAlign = Paint.Align.RIGHT
        }
    }
    val monthTextPaint = remember {
        Paint().apply {
            color = android.graphics.Color.parseColor("#94A3B8")
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
    }
    val valPaint = remember {
        Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 19f
            isFakeBoldText = true
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
    }
    val peakBadgePaint = remember {
        Paint().apply {
            color = android.graphics.Color.parseColor("#F59E0B")
            textSize = 13f
            isFakeBoldText = true
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
    }
    val avgBadgeTextPaint = remember {
        Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 13f
            isFakeBoldText = true
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
            textAlign = Paint.Align.LEFT
        }
    }

    Canvas(modifier = modifier) {
        val totalWidth = size.width
        val totalHeight = size.height

        val bottomPadding = 32f
        val topPadding = 38f
        val leftPadding = 64f
        val rightPadding = 24f

        val graphWidth = totalWidth - leftPadding - rightPadding
        val graphHeight = totalHeight - topPadding - bottomPadding

        val maxKm = (monthlyGroups.maxOfOrNull { it.totalDistanceKm } ?: 0.0).coerceAtLeast(10.0)
        val effectiveMax = maxKm * 1.25
        val activeGroups = monthlyGroups.filter { it.totalDistanceKm > 0.0 }
        val avgKm = if (activeGroups.isNotEmpty()) activeGroups.map { it.totalDistanceKm }.average() else 0.0
        val maxKmIdx = monthlyGroups.indices.maxByOrNull { monthlyGroups[it].totalDistanceKm } ?: -1

        // 1. Draw 4 horizontal reference grid lines with Y-axis scale numbers
        val steps = 3
        for (i in 0..steps) {
            val y = topPadding + (graphHeight / steps) * i
            val scaleVal = effectiveMax * (1.0 - i.toDouble() / steps)

            drawLine(
                color = gridColor,
                start = Offset(leftPadding, y),
                end = Offset(totalWidth - rightPadding, y),
                strokeWidth = 1f
            )

            // Draw Y-axis scale value text on left
            val label = if (scaleVal >= 1000) "${NumberFormatUtils.formatPrecision(scaleVal / 1000.0, 1)}k" else "${scaleVal.toInt()}km"
            drawContext.canvas.nativeCanvas.drawText(
                label,
                leftPadding - 8f,
                y + 5f,
                axisTextPaint
            )
        }

        val colWidth = graphWidth / 12f
        val barWidth = (colWidth * 0.52f).coerceIn(14f, 36f)

        // 2. Full-height Background Channel Tracks for all 12 months
        for (m in 0 until 12) {
            val centerX = leftPadding + colWidth * m + colWidth / 2f
            val barLeft = centerX - barWidth / 2f
            drawRoundRect(
                color = StaticPalette.border,
                topLeft = Offset(barLeft, topPadding),
                size = Size(barWidth, graphHeight),
                cornerRadius = CornerRadius(5f, 5f)
            )
            drawRoundRect(
                color = StaticPalette.accentSoft,
                topLeft = Offset(barLeft, topPadding),
                size = Size(barWidth, graphHeight),
                cornerRadius = CornerRadius(5f, 5f),
                style = Stroke(0.8f)
            )
        }

        // 3. Yearly Average Distance Dashed Reference Line
        if (avgKm > 0.0) {
            val avgY = topPadding + graphHeight * (1f - (avgKm / effectiveMax).toFloat())
            val dashWidth = 10f
            val dashGap = 6f
            var curX = leftPadding
            while (curX < totalWidth - rightPadding) {
                val endX = (curX + dashWidth).coerceAtMost(totalWidth - rightPadding)
                drawLine(
                    color = accentColor.copy(alpha = 0.55f),
                    start = Offset(curX, avgY),
                    end = Offset(endX, avgY),
                    strokeWidth = 1.6f
                )
                curX += dashWidth + dashGap
            }

            // Draw AVG floating badge on right edge
            val avgText = "AVG ${avgKm.toInt()} km"
            val avgBadgeW = 90f
            val avgBadgeH = 20f
            val avgBadgeX = totalWidth - rightPadding - avgBadgeW
            val avgBadgeY = (avgY - avgBadgeH / 2f).coerceIn(topPadding, topPadding + graphHeight - avgBadgeH)
            drawRoundRect(
                color = StaticPalette.border,
                topLeft = Offset(avgBadgeX, avgBadgeY),
                size = Size(avgBadgeW, avgBadgeH),
                cornerRadius = CornerRadius(4f, 4f)
            )
            drawRoundRect(
                color = accentColor.copy(alpha = 0.65f),
                topLeft = Offset(avgBadgeX, avgBadgeY),
                size = Size(avgBadgeW, avgBadgeH),
                cornerRadius = CornerRadius(4f, 4f),
                style = Stroke(1f)
            )
            drawContext.canvas.nativeCanvas.drawText(
                avgText,
                avgBadgeX + 6f,
                avgBadgeY + 14f,
                avgBadgeTextPaint
            )
        }

        // 4. Monthly Distance Gradient Bars
        for (m in 0 until 12) {
            val group = monthlyGroups.getOrNull(m)
            val km = group?.totalDistanceKm ?: 0.0
            val centerX = leftPadding + colWidth * m + colWidth / 2f
            val barLeft = centerX - barWidth / 2f
            val isPeak = m == maxKmIdx && km > 0.0

            if (km > 0.0) {
                val ratio = (km / effectiveMax).coerceIn(0.0, 1.0).toFloat()
                val barH = (graphHeight * ratio).coerceAtLeast(8f)
                val barTop = topPadding + graphHeight - barH

                // Gradient bar
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = if (isPeak) {
                            listOf(accentColor, accentColor.copy(alpha = 0.40f))
                        } else {
                            listOf(accentColor.copy(alpha = 0.85f), accentColor.copy(alpha = 0.20f))
                        },
                        startY = barTop,
                        endY = topPadding + graphHeight
                    ),
                    topLeft = Offset(barLeft, barTop),
                    size = Size(barWidth, barH),
                    cornerRadius = CornerRadius(5f, 5f)
                )

                // Top highlight rim
                drawRoundRect(
                    color = if (isPeak) StaticPalette.textPrimary else accentColor,
                    topLeft = Offset(barLeft, barTop),
                    size = Size(barWidth, 3.5f),
                    cornerRadius = CornerRadius(2f, 2f)
                )

                // Peak beacon & tag
                if (isPeak) {
                    drawCircle(color = accentColor.copy(alpha = 0.35f), radius = 9f, center = Offset(centerX, barTop))
                    drawCircle(color = StaticPalette.textPrimary, radius = 3.5f, center = Offset(centerX, barTop))
                    drawContext.canvas.nativeCanvas.drawText("★ TOP", centerX, barTop - 24f, peakBadgePaint)
                }

                // Value above bar
                val valStr = if (km >= 1000) "${NumberFormatUtils.formatPrecision(km / 1000.0, 1)}k" else "${km.toInt()}"
                drawContext.canvas.nativeCanvas.drawText(
                    valStr,
                    centerX,
                    barTop - 8f,
                    valPaint
                )
            } else {
                drawRoundRect(
                    color = StaticPalette.border,
                    topLeft = Offset(barLeft, topPadding + graphHeight - 4f),
                    size = Size(barWidth, 4f),
                    cornerRadius = CornerRadius(2f, 2f)
                )
            }

            // Month Labels at bottom (refined 17f size, highlighted for peak/active)
            val monthLabel = group?.monthName ?: ""
            monthTextPaint.color = if (isPeak) {
                accentColor.toArgb()
            } else if (km > 0.0) {
                android.graphics.Color.WHITE
            } else {
                android.graphics.Color.parseColor("#64748B")
            }

            drawContext.canvas.nativeCanvas.drawText(
                monthLabel,
                centerX,
                totalHeight - 8f,
                monthTextPaint
            )
        }
    }
}
