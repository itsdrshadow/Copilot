package com.antigravity.copilot.feature.trips

import com.antigravity.copilot.core.designsystem.tokens.Palette
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.antigravity.copilot.core.database.TripPointEntity
import com.antigravity.copilot.core.designsystem.*
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.StrokeJoin
import java.util.*

@Composable
fun TripPlaybackMapView(
    points: List<TripPointEntity>,
    currentIndex: Int,
    modifier: Modifier = Modifier
) {
    if (points.isEmpty()) {
        Box(
            modifier = modifier.background(Color(0xFF0F151F)),
            contentAlignment = Alignment.Center
        ) {
            Text("No trip GPS trail recorded", color = Color.White.copy(alpha = 0.5f))
        }
        return
    }

    // Calculate normalized bounding box for Canvas projection
    val bounds = remember(points) {
        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE
        points.forEach { pt ->
            if (pt.latitude < minLat) minLat = pt.latitude
            if (pt.latitude > maxLat) maxLat = pt.latitude
            if (pt.longitude < minLon) minLon = pt.longitude
            if (pt.longitude > maxLon) maxLon = pt.longitude
        }
        val latSpan = (maxLat - minLat).coerceAtLeast(0.001)
        val lonSpan = (maxLon - minLon).coerceAtLeast(0.001)
        object {
            val minLa = minLat - latSpan * 0.1
            val maxLa = maxLat + latSpan * 0.1
            val minLo = minLon - lonSpan * 0.1
            val maxLo = maxLon + lonSpan * 0.1
            val spanLat = (maxLa - minLa)
            val spanLon = (maxLo - minLo)
        }
    }

    val overlapRuns = remember(points) {
        com.antigravity.copilot.core.logic.TrailOverlap.runs(
            points.map { com.antigravity.copilot.core.logic.LatLon(it.latitude, it.longitude) }
        )
    }

    Box(
        modifier = modifier
            .background(Color(0xFF0A0E17))
            .border(1.dp, Color(0xFF1B2433))
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            val w = size.width
            val h = size.height

            fun project(lat: Double, lon: Double): Offset {
                val x = ((lon - bounds.minLo) / bounds.spanLon * w).toFloat()
                val y = (h - ((lat - bounds.minLa) / bounds.spanLat * h)).toFloat()
                return Offset(x, y)
            }

            // Full trip path coloured by overlap (~15 m grid): 1 pass blue, 2 orange, 3+ red
            if (points.size >= 2) {
                val firstPt = project(points.first().latitude, points.first().longitude)
                for (run in overlapRuns) {
                    val runPath = Path()
                    val s0 = project(run.points.first().lat, run.points.first().lon)
                    runPath.moveTo(s0.x, s0.y)
                    for (k in 1 until run.points.size) {
                        val p = project(run.points[k].lat, run.points[k].lon)
                        runPath.lineTo(p.x, p.y)
                    }
                    drawPath(
                        path = runPath,
                        color = Color(com.antigravity.copilot.core.logic.PassColor.forPasses(run.passes).argb.toInt()).copy(alpha = 0.75f),
                        style = Stroke(width = 8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }

                // Draw playback active trail
                val safeIdx = currentIndex.coerceIn(0, points.lastIndex)
                if (safeIdx >= 1) {
                    val activePath = Path()
                    activePath.moveTo(firstPt.x, firstPt.y)
                    for (i in 1..safeIdx) {
                        val p = project(points[i].latitude, points[i].longitude)
                        activePath.lineTo(p.x, p.y)
                    }
                    drawPath(
                        path = activePath,
                        color = Palette.Cyan,
                        style = Stroke(width = 12f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }

                // Draw Start Marker (Green)
                drawCircle(
                    color = Palette.GreenA400,
                    radius = 12f,
                    center = firstPt
                )

                // Draw End Marker (Red)
                val lastPt = project(points.last().latitude, points.last().longitude)
                drawCircle(
                    color = Color(0xFFFF5252),
                    radius = 12f,
                    center = lastPt
                )

                // Draw Current Vehicle Position Puck
                val currentPt = project(points[safeIdx].latitude, points[safeIdx].longitude)
                drawCircle(
                    color = Palette.Cyan.copy(alpha = 0.35f),
                    radius = 24f,
                    center = currentPt
                )
                drawCircle(
                    color = Color.White,
                    radius = 10f,
                    center = currentPt
                )
                drawCircle(
                    color = Palette.Cyan,
                    radius = 7f,
                    center = currentPt
                )
            }
        }
    }
}

