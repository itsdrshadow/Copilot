package com.antigravity.copilot.feature.trips

import com.antigravity.copilot.core.designsystem.CockpitTheme
import com.antigravity.copilot.core.designsystem.tokens.StaticPalette
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.antigravity.copilot.core.database.TripPointEntity
import com.antigravity.copilot.core.designsystem.tokens.Palette
import com.antigravity.copilot.core.logic.LatLon
import com.antigravity.copilot.core.logic.PassColor
import com.antigravity.copilot.core.logic.TrailOverlap
import com.antigravity.copilot.core.map.StaticMapCamera
import com.antigravity.copilot.feature.maps.drawSnapshot
import com.antigravity.copilot.feature.maps.rememberStaticMapSnapshot

/**
 * Trip route over the offline basemap. The basemap is a one-shot MapLibre snapshot fitted to the
 * route bounds; the route, markers and playback puck are a Compose overlay projected with the
 * same Web-Mercator camera, so they line up exactly and playback redraws only the overlay.
 */
@Composable
fun TripPlaybackMapView(
    points: List<TripPointEntity>,
    currentIndex: Int,
    modifier: Modifier = Modifier
) {
    // Drop GPS sentinels (0,0), NaN and out-of-range fixes: one of them in the bounds would zoom the
    // fitted camera out to the whole planet and the route would collapse to a dot.
    val valid = remember(points) {
        points.withIndex().filter { StaticMapCamera.isValidCoordinate(it.value.latitude, it.value.longitude) }
    }
    if (valid.isEmpty()) {
        Box(modifier = modifier.background(CockpitTheme.colors.border), contentAlignment = Alignment.Center) {
            Text("No trip GPS trail recorded", color = CockpitTheme.colors.textSecondary)
        }
        return
    }

    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    val paddingPx = with(LocalDensity.current) { 36.dp.roundToPx() }

    val camera = remember(valid, sizePx, paddingPx) {
        var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
        for ((_, p) in valid) {
            if (p.latitude < minLat) minLat = p.latitude
            if (p.latitude > maxLat) maxLat = p.latitude
            if (p.longitude < minLon) minLon = p.longitude
            if (p.longitude > maxLon) maxLon = p.longitude
        }
        StaticMapCamera.fit(minLat, maxLat, minLon, maxLon, sizePx.width, sizePx.height, paddingPx)
    }
    val snapshot = rememberStaticMapSnapshot(camera)

    val overlapRuns = remember(valid) {
        TrailOverlap.runs(valid.map { LatLon(it.value.latitude, it.value.longitude) })
    }
    // Projection is done once per camera, not per playback frame.
    val runPaths = remember(overlapRuns, camera) {
        val cam = camera ?: return@remember emptyList()
        overlapRuns.mapNotNull { run ->
            if (run.points.size < 2) return@mapNotNull null
            val path = Path()
            run.points.forEachIndexed { k, p ->
                val xy = cam.project(p.lat, p.lon)
                if (k == 0) path.moveTo(xy[0], xy[1]) else path.lineTo(xy[0], xy[1])
            }
            path to Color(PassColor.forPasses(run.passes).argb.toInt()).copy(alpha = 0.85f)
        }
    }
    // Screen xy for every original index (NaN for dropped fixes) so the playback index maps 1:1.
    val projected = remember(points, valid, camera) {
        val cam = camera ?: return@remember FloatArray(0)
        FloatArray(points.size * 2) { Float.NaN }.also { arr ->
            for ((i, p) in valid) {
                val xy = cam.project(p.latitude, p.longitude)
                arr[2 * i] = xy[0]; arr[2 * i + 1] = xy[1]
            }
        }
    }
    val firstIdx = valid.first().index
    val lastIdx = valid.last().index

    Box(
        modifier = modifier
            .background(CockpitTheme.colors.cardElevated)
            .border(1.dp, CockpitTheme.colors.border)
            .onSizeChanged { sizePx = it }
    ) {
        Canvas(modifier = Modifier.fillMaxSize().graphicsLayer { clip = true }) {
            val cam = camera ?: return@Canvas
            if (projected.isEmpty()) return@Canvas
            snapshot?.let { drawSnapshot(it, cam) }

            fun pt(i: Int) = Offset(projected[2 * i], projected[2 * i + 1])

            // Full route coloured by overlap (~15 m grid): 1 pass blue, 2 orange, 3+ red
            for ((path, colour) in runPaths) {
                drawPath(path, StaticPalette.scrim, style = Stroke(width = 11f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(path, colour, style = Stroke(width = 7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }

            // Played-back portion
            val safeIdx = currentIndex.coerceIn(0, points.lastIndex)
            val active = Path()
            var started = false
            for (i in 0..safeIdx) {
                if (projected[2 * i].isNaN()) continue
                val p = pt(i)
                if (!started) { active.moveTo(p.x, p.y); started = true } else active.lineTo(p.x, p.y)
            }
            if (started) {
                drawPath(active, StaticPalette.Cyan, style = Stroke(width = 10f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }

            // Start (green) / end (red) markers
            drawCircle(StaticPalette.textPrimary, radius = 14f, center = pt(firstIdx))
            drawCircle(StaticPalette.GreenA400, radius = 11f, center = pt(firstIdx))
            drawCircle(StaticPalette.textPrimary, radius = 14f, center = pt(lastIdx))
            drawCircle(StaticPalette.critical, radius = 11f, center = pt(lastIdx))

            // Vehicle puck at the nearest valid fix at or before the playback index
            var puckIdx = safeIdx
            while (puckIdx > firstIdx && projected[2 * puckIdx].isNaN()) puckIdx--
            if (!projected[2 * puckIdx].isNaN()) {
                val c = pt(puckIdx)
                drawCircle(StaticPalette.Cyan.copy(alpha = 0.35f), radius = 24f, center = c)
                drawCircle(StaticPalette.textPrimary, radius = 10f, center = c)
                drawCircle(StaticPalette.Cyan, radius = 7f, center = c)
            }
        }
    }
}
