package com.antigravity.copilot.feature.trips

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.antigravity.copilot.core.database.TripEntity
import com.antigravity.copilot.core.database.TripPointEntity
import com.antigravity.copilot.core.designsystem.CockpitTheme
import com.antigravity.copilot.core.designsystem.tokens.CopilotShapes
import com.antigravity.copilot.core.designsystem.tokens.StaticPalette
import com.antigravity.copilot.core.logic.GeoMath
import com.antigravity.copilot.core.util.NumberFormatUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ============================ Data ============================

/** Per-point series derived once per trip: seconds from start, speed, distance, accel, altitude. */
internal class TripSeries(
    val tSec: FloatArray,
    val speed: FloatArray,
    val cumKm: FloatArray,
    val accel: FloatArray,
    val alt: FloatArray,
) {
    val size get() = tSec.size
    val durationSec get() = if (size > 0) tSec.last() else 0f
    val hasAltitude: Boolean by lazy {
        val valid = alt.filter { it != 0f }
        valid.size > size / 2 && (valid.maxOrNull() ?: 0f) - (valid.minOrNull() ?: 0f) >= 5f
    }
}

internal fun buildTripSeries(points: List<TripPointEntity>): TripSeries {
    val pts = points.filter { it.latitude != 0.0 || it.longitude != 0.0 }
    val n = pts.size
    val t = FloatArray(n); val v = FloatArray(n); val d = FloatArray(n); val a = FloatArray(n); val h = FloatArray(n)
    if (n == 0) return TripSeries(t, v, d, a, h)
    val t0 = pts.first().timestamp
    for (i in 0 until n) {
        val p = pts[i]
        t[i] = (p.timestamp - t0) / 1000f
        v[i] = p.speedKmh
        h[i] = p.altitude.toFloat()
        if (i > 0) {
            val q = pts[i - 1]
            d[i] = d[i - 1] + (GeoMath.haversineM(q.latitude, q.longitude, p.latitude, p.longitude) / 1000.0).toFloat()
            val dt = t[i] - t[i - 1]
            // Same rules as the timeline: plausible GPS gap, and only above walking pace.
            a[i] = if (dt in 0.8f..6f && maxOf(p.speedKmh, q.speedKmh) >= 12f) ((p.speedKmh - q.speedKmh) / 3.6f) / dt else 0f
        }
    }
    return TripSeries(t, v, d, a, h)
}

private data class Band(val label: String, val lo: Float, val hi: Float, val color: Color)

private val speedBands = listOf(
    Band("0–20", 0f, 20f, StaticPalette.Amber500),
    Band("20–40", 20f, 40f, StaticPalette.Sky400),
    Band("40–60", 40f, 60f, StaticPalette.Emerald500),
    Band("60–80", 60f, 80f, StaticPalette.Purple500),
    Band("80+", 80f, Float.MAX_VALUE, StaticPalette.Red500),
)

/** Harsh events, counting spikes within 15 s of each other once (matches the timeline). */
internal fun countJolts(s: TripSeries, braking: Boolean): Int {
    var count = 0
    var last = -1e9f
    for (i in 0 until s.size) {
        val hit = if (braking) s.accel[i] <= -2.5f else s.accel[i] >= 2.2f
        if (hit && s.tSec[i] - last >= 15f) { count++; last = s.tSec[i] }
    }
    return count
}

/** Seconds spent in each speed band, time-weighted (gaps capped at 10 s). */
private fun bandSeconds(s: TripSeries): FloatArray {
    val out = FloatArray(speedBands.size)
    for (i in 1 until s.size) {
        val dt = (s.tSec[i] - s.tSec[i - 1]).coerceIn(0f, 10f)
        val sp = s.speed[i - 1]
        val b = speedBands.indexOfFirst { sp >= it.lo && sp < it.hi }.coerceAtLeast(0)
        out[b] += dt
    }
    return out
}

/** Averages [values] into at most [buckets] buckets (keeps charts cheap on long trips). */
private fun downsample(values: FloatArray, buckets: Int): FloatArray {
    if (values.size <= buckets) return values
    val out = FloatArray(buckets)
    val per = values.size.toFloat() / buckets
    for (b in 0 until buckets) {
        val from = (b * per).toInt(); val to = ((b + 1) * per).toInt().coerceAtMost(values.size).coerceAtLeast(from + 1)
        var sum = 0f
        for (i in from until to) sum += values[i]
        out[b] = sum / (to - from)
    }
    return out
}

// ============================ Screen ============================

/**
 * Trip charts: KPI tiles, speed profile (avg line, top speed, hard braking / acceleration ticks),
 * time in speed bands, acceleration profile, moving / idle split, elevation (or distance
 * progress when there is no altitude) and a driving-behaviour card.
 */
@Composable
internal fun TripChartsView(
    trip: TripEntity,
    points: List<TripPointEntity>,
    fuelPricePerLiter: Double = 96.5,
    modifier: Modifier = Modifier
) {
    val c = CockpitTheme.colors
    val series = remember(points) { buildTripSeries(points) }
    if (series.size < 2) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("Not enough GPS data for charts.", style = CockpitTheme.typography.bodyLarge, color = c.textSecondary)
        }
        return
    }
    val price = if (fuelPricePerLiter > 0) fuelPricePerLiter else 96.5
    val movingMs = trip.drivingDurationMs
    val idleMs = trip.stationaryDurationMs
    val wallMs = if (trip.endTime > trip.startTime) trip.endTime - trip.startTime else movingMs + idleMs
    val parkedMs = (wallMs - movingMs - idleMs).coerceAtLeast(0L)
    val totalMs = (movingMs + idleMs + parkedMs).coerceAtLeast(1L)
    val bands = remember(series) { bandSeconds(series) }
    // Same counts as Route replay and Timeline: the trip's saved record.
    val brakeCount = trip.brakeCount
    val accelCount = trip.accelCount
    val ecoShare = remember(bands) {
        val tot = bands.sum().coerceAtLeast(1f)
        ((bands[1] + bands[2]) / tot * 100).roundToInt()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---- KPI tiles ----
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val m = Modifier.weight(1f)
                KpiTile("Avg speed", NumberFormatUtils.formatSpeed(trip.avgSpeedKmh), "km/h", c.textPrimary, m)
                KpiTile("Top speed", NumberFormatUtils.formatSpeed(trip.maxSpeedKmh), "km/h", c.textPrimary, m)
                KpiTile("Moving", "${(movingMs * 100 / totalMs)}", "%", c.textPrimary, m)
                KpiTile("Fuel used", if (trip.estimatedFuelLiters > 0) NumberFormatUtils.formatFuelLiters(trip.estimatedFuelLiters) else "--", "L", c.textPrimary, m)
                KpiTile("Fuel cost", if (trip.estimatedFuelLiters > 0) NumberFormatUtils.formatCurrencyWithSymbol(trip.estimatedFuelLiters * price) else "--", "", c.textPrimary, m)
                KpiTile("Mileage", if (trip.calculatedMileageKmpl > 0) NumberFormatUtils.formatMileage(trip.calculatedMileageKmpl) else "--", "km/L", c.textPrimary, m)
            }
        }
        // ---- Speed profile + speed bands ----
        item {
            Row(modifier = Modifier.height(250.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ChartCard("Speed profile", c.accent, "avg ${trip.avgSpeedKmh.roundToInt()} · top ${trip.maxSpeedKmh.roundToInt()} km/h", Modifier.weight(1.7f).fillMaxHeight()) {
                    SpeedProfileChart(series, trip.startTime, trip.avgSpeedKmh.toFloat())
                }
                ChartCard("Time in speed bands", StaticPalette.Purple500, null, Modifier.weight(1.3f).fillMaxHeight()) {
                    SpeedBandHistogram(bands)
                }
            }
        }
        // ---- Acceleration + time split ----
        item {
            Row(modifier = Modifier.height(230.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ChartCard("Acceleration & braking", c.warning, "$accelCount quick starts · $brakeCount hard brakes", Modifier.weight(2f).fillMaxHeight()) {
                    AccelerationChart(series)
                }
                ChartCard("Time split", c.positive, null, Modifier.weight(1f).fillMaxHeight()) {
                    TimeSplitDonut(movingMs, idleMs, parkedMs)
                }
            }
        }
        // ---- Elevation / distance + behaviour ----
        item {
            Row(modifier = Modifier.height(230.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (series.hasAltitude) {
                    val valid = series.alt.filter { it != 0f }
                    ChartCard(
                        "Elevation", StaticPalette.Emerald500,
                        "${valid.minOrNull()?.roundToInt() ?: 0}–${valid.maxOrNull()?.roundToInt() ?: 0} m",
                        Modifier.weight(1f).fillMaxHeight()
                    ) { LineAreaChart(series.alt, StaticPalette.Emerald500, unit = "m", skipZeros = true) }
                } else {
                    ChartCard(
                        "Distance progress", c.accent,
                        NumberFormatUtils.formatDistanceWithUnit(series.cumKm.last().toDouble()),
                        Modifier.weight(1f).fillMaxHeight()
                    ) { LineAreaChart(series.cumKm, c.accent, unit = "km", skipZeros = false) }
                }
                ChartCard("Driving behaviour", StaticPalette.Sky400, null, Modifier.weight(1f).fillMaxHeight()) {
                    BehaviourCard(trip, brakeCount, accelCount, ecoShare)
                }
            }
        }
    }
}

// ============================ Building blocks ============================

@Composable
private fun KpiTile(label: String, value: String, unit: String, color: Color, modifier: Modifier) {
    val c = CockpitTheme.colors
    Column(
        modifier = modifier
            .clip(CopilotShapes.R12)
            .background(c.card)
            .border(1.dp, c.border, CopilotShapes.R12)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(label, style = CockpitTheme.typography.labelMedium, color = c.textSecondary, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = CockpitTheme.typography.headlineMedium, color = color, maxLines = 1)
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(3.dp))
                Text(unit, style = CockpitTheme.typography.labelMedium, color = c.textSecondary, maxLines = 1, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }
}

@Composable
private fun ChartCard(
    title: String,
    accent: Color,
    trailing: String?,
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val c = CockpitTheme.colors
    Column(
        modifier = modifier
            .clip(CopilotShapes.R12)
            .background(c.card)
            .border(1.dp, c.border, CopilotShapes.R12)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(accent))
            Spacer(Modifier.width(8.dp))
            Text(title, style = CockpitTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = c.textPrimary, modifier = Modifier.weight(1f), maxLines = 1)
            if (trailing != null) Text(trailing, style = CockpitTheme.typography.labelMedium, color = c.textSecondary, maxLines = 1)
        }
        content()
    }
}

/** Y-axis labels on the left, x labels under; [plot] fills the remaining box. */
@Composable
private fun ColumnScope.AxisFrame(
    yLabels: List<String>,
    xLabels: List<String>,
    plot: @Composable BoxScope.() -> Unit
) {
    val c = CockpitTheme.colors
    Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
        Column(
            modifier = Modifier.width(34.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End
        ) {
            yLabels.forEach { Text(it, style = CockpitTheme.typography.labelMedium, color = c.textSecondary, maxLines = 1) }
        }
        Spacer(Modifier.width(6.dp))
        Box(modifier = Modifier.weight(1f).fillMaxHeight(), content = plot)
    }
    Row(modifier = Modifier.fillMaxWidth().padding(start = 40.dp)) {
        xLabels.forEachIndexed { i, l ->
            Text(
                l,
                style = CockpitTheme.typography.labelMedium,
                color = c.textSecondary,
                maxLines = 1,
                textAlign = when (i) {
                    0 -> TextAlign.Start
                    xLabels.lastIndex -> TextAlign.End
                    else -> TextAlign.Center
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ColumnScope.SpeedProfileChart(series: TripSeries, startTime: Long, avgKmh: Float) {
    val c = CockpitTheme.colors
    val accent = c.accent
    val grid = c.border
    val avgColor = c.warning
    val brakeColor = c.critical
    val accelColor = StaticPalette.Purple500
    val maxV = remember(series) { max(40f, ceil((series.speed.maxOrNull() ?: 0f) / 20f) * 20f) }
    val fmt = remember { SimpleDateFormat("h:mm", Locale.getDefault()) }
    val dur = series.durationSec
    val xLabels = listOf(0f, 0.5f, 1f).map { fmt.format(Date(startTime + (dur * it * 1000).toLong())) }
    val yLabels = listOf(maxV, maxV / 2, 0f).map { it.roundToInt().toString() }
    AxisFrame(yLabels, xLabels) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            for (k in 0..4) {
                val y = h * k / 4f
                drawLine(grid, Offset(0f, y), Offset(w, y), strokeWidth = 1.dp.toPx())
            }
            fun x(t: Float) = if (dur > 0) t / dur * w else 0f
            fun y(v: Float) = h - (v / maxV).coerceIn(0f, 1f) * h
            val step = max(1, series.size / 400)
            val line = Path(); val area = Path()
            var first = true
            var i = 0
            while (i < series.size) {
                val px = x(series.tSec[i]); val py = y(series.speed[i])
                if (first) { line.moveTo(px, py); area.moveTo(px, h); area.lineTo(px, py); first = false } else { line.lineTo(px, py); area.lineTo(px, py) }
                i += step
            }
            area.lineTo(x(series.tSec.last()), h); area.close()
            drawPath(area, Brush.verticalGradient(listOf(accent.copy(alpha = 0.35f), accent.copy(alpha = 0.02f))))
            drawPath(line, accent, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            // Average speed
            if (avgKmh > 0) {
                val ay = y(avgKmh)
                drawLine(avgColor, Offset(0f, ay), Offset(w, ay), strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            }
            // Hard braking / quick acceleration ticks along the bottom
            for (j in 0 until series.size) {
                val a = series.accel[j]
                val col = when {
                    a <= -2.5f -> brakeColor
                    a >= 2.2f -> accelColor
                    else -> null
                } ?: continue
                val px = x(series.tSec[j])
                drawLine(col, Offset(px, h), Offset(px, h - 10.dp.toPx()), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            }
            // Top speed marker
            val mi = series.speed.indices.maxByOrNull { series.speed[it] } ?: 0
            val mp = Offset(x(series.tSec[mi]), y(series.speed[mi]))
            drawCircle(Color.White, radius = 6.dp.toPx(), center = mp)
            drawCircle(accelColor, radius = 4.dp.toPx(), center = mp)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Legend(c.accent, "Speed")
        Legend(c.warning, "Average")
        Legend(c.critical, "Hard brake")
        Legend(StaticPalette.Purple500, "Quick start / top speed")
    }
}

@Composable
private fun ColumnScope.SpeedBandHistogram(seconds: FloatArray) {
    val c = CockpitTheme.colors
    val maxS = (seconds.maxOrNull() ?: 0f).coerceAtLeast(1f)
    val total = seconds.sum().coerceAtLeast(1f)
    Row(
        modifier = Modifier.fillMaxWidth().weight(1f),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        speedBands.forEachIndexed { i, b ->
            val sec = seconds[i]
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                Text("${(sec / total * 100).roundToInt()}%", style = CockpitTheme.typography.labelLarge, color = c.textPrimary, maxLines = 1)
                Text(minutesLabel(sec), style = CockpitTheme.typography.labelMedium, color = c.textSecondary, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight((sec / maxS * 0.62f).coerceAtLeast(0.02f))
                        .clip(CopilotShapes.R6)
                        .background(Brush.verticalGradient(listOf(b.color, b.color.copy(alpha = 0.55f))))
                )
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        speedBands.forEach {
            Text(it.label, style = CockpitTheme.typography.labelMedium, color = c.textSecondary, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ColumnScope.AccelerationChart(series: TripSeries) {
    val c = CockpitTheme.colors
    val pos = c.positive
    val neg = c.critical
    val grid = c.border
    val warn = c.warning
    val bars = remember(series) {
        // Keep the strongest value per bucket so spikes are not averaged away.
        val n = 90
        if (series.size <= n) series.accel
        else FloatArray(n) { b ->
            val per = series.size.toFloat() / n
            val from = (b * per).toInt(); val to = ((b + 1) * per).toInt().coerceAtMost(series.size).coerceAtLeast(from + 1)
            var best = 0f
            for (i in from until to) if (abs(series.accel[i]) > abs(best)) best = series.accel[i]
            best
        }
    }
    val lim = 4f
    AxisFrame(listOf("+4", "0", "−4"), listOf("start", "", "end")) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height; val mid = h / 2f
            drawLine(grid, Offset(0f, mid), Offset(w, mid), strokeWidth = 1.5.dp.toPx())
            val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
            listOf(2.2f, -2.5f).forEach { th ->
                val y = mid - th / lim * mid
                drawLine(warn.copy(alpha = 0.6f), Offset(0f, y), Offset(w, y), strokeWidth = 1.dp.toPx(), pathEffect = dash)
            }
            val bw = w / bars.size
            bars.forEachIndexed { i, a ->
                val hgt = (a / lim).coerceIn(-1f, 1f) * mid
                val col = if (a >= 0) pos else neg
                val strong = a >= 2.2f || a <= -2.5f
                drawRect(
                    color = col.copy(alpha = if (strong) 1f else 0.55f),
                    topLeft = Offset(i * bw + bw * 0.15f, if (hgt >= 0) mid - hgt else mid),
                    size = Size(bw * 0.7f, abs(hgt).coerceAtLeast(1f))
                )
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Legend(c.positive, "Speeding up")
        Legend(c.critical, "Slowing down")
        Legend(c.warning, "Harsh limits (m/s²)")
    }
}

@Composable
private fun ColumnScope.TimeSplitDonut(movingMs: Long, idleMs: Long, parkedMs: Long) {
    val c = CockpitTheme.colors
    val parts = listOf(
        Triple("Moving", movingMs, c.positive),
        Triple("Idle", idleMs, c.warning),
        Triple("Parked", parkedMs, StaticPalette.Slate400),
    ).filter { it.second > 0 }
    val total = parts.sumOf { it.second }.coerceAtLeast(1L)
    val track = c.cardElevated
    Row(modifier = Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val d = minOf(size.width, size.height) * 0.9f
                val stroke = d * 0.16f
                val tl = Offset((size.width - d) / 2f + stroke / 2, (size.height - d) / 2f + stroke / 2)
                val sz = Size(d - stroke, d - stroke)
                drawArc(track, 0f, 360f, false, tl, sz, style = Stroke(stroke))
                var start = -90f
                parts.forEach { (_, ms, col) ->
                    val sweep = ms.toFloat() / total * 360f
                    drawArc(col, start, (sweep - 2f).coerceAtLeast(0.5f), false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Butt))
                    start += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${movingMs * 100 / total}%", style = CockpitTheme.typography.headlineMedium, color = c.textPrimary)
                Text("moving", style = CockpitTheme.typography.labelMedium, color = c.textSecondary)
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            parts.forEach { (label, ms, col) ->
                Column {
                    Legend(col, label)
                    Text(formatDurationHoursMinutes(ms), style = CockpitTheme.typography.headlineSmall, color = c.textPrimary, modifier = Modifier.padding(start = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.LineAreaChart(values: FloatArray, color: Color, unit: String, skipZeros: Boolean) {
    val c = CockpitTheme.colors
    val grid = c.border
    val data = remember(values) {
        val src = if (skipZeros) values.filter { it != 0f }.toFloatArray() else values
        downsample(src, 240)
    }
    val lo = if (skipZeros) (data.minOrNull() ?: 0f) else 0f
    val hi = max((data.maxOrNull() ?: 1f), lo + 1f)
    fun fmt(v: Float) = if (hi - lo < 10 && hi < 100f) String.format(Locale.US, "%.1f", v) else v.roundToInt().toString()
    AxisFrame(listOf(fmt(hi), fmt((hi + lo) / 2), fmt(lo)), listOf("start", "", "end")) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            for (k in 0..2) drawLine(grid, Offset(0f, h * k / 2f), Offset(w, h * k / 2f), strokeWidth = 1.dp.toPx())
            if (data.size < 2) return@Canvas
            val line = Path(); val area = Path()
            data.forEachIndexed { i, v ->
                val x = i / (data.size - 1f) * w
                val y = h - (v - lo) / (hi - lo) * h
                if (i == 0) { line.moveTo(x, y); area.moveTo(x, h); area.lineTo(x, y) } else { line.lineTo(x, y); area.lineTo(x, y) }
            }
            area.lineTo(w, h); area.close()
            drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), color.copy(alpha = 0.02f))))
            drawPath(line, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
    Text("Values in $unit", style = CockpitTheme.typography.labelMedium, color = c.textSecondary)
}

@Composable
private fun ColumnScope.BehaviourCard(trip: TripEntity, brakes: Int, accels: Int, ecoShare: Int) {
    val c = CockpitTheme.colors
    val score = trip.driverScore.coerceIn(0, 100)
    val scoreColor = when {
        score >= 80 -> c.positive
        score >= 60 -> c.warning
        else -> c.critical
    }
    val track = c.cardElevated
    Row(modifier = Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.weight(0.9f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val d = minOf(size.width, size.height) * 0.9f
                val stroke = d * 0.12f
                val tl = Offset((size.width - d) / 2f + stroke / 2, (size.height - d) / 2f + stroke / 2)
                val sz = Size(d - stroke, d - stroke)
                drawArc(track, 135f, 270f, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
                drawArc(scoreColor, 135f, 270f * score / 100f, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$score", style = CockpitTheme.typography.displaySmall, color = c.textPrimary)
                Text("score", style = CockpitTheme.typography.labelMedium, color = c.textSecondary)
            }
        }
        Column(modifier = Modifier.weight(1.1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BehaviourRow("Hard braking", "$brakes", if (brakes == 0) c.positiveText else c.criticalText)
            BehaviourRow("Quick starts", "$accels", if (accels == 0) c.positiveText else c.warningText)
            BehaviourRow("Eco speed (20–60)", "$ecoShare%", if (ecoShare >= 60) c.positiveText else c.warningText)
            BehaviourRow(
                "Best km/L",
                if (trip.bestMileageKmpl > 0) NumberFormatUtils.formatPrecision(trip.bestMileageKmpl, 1) else "--",
                c.accentText
            )
        }
    }
}

@Composable
private fun BehaviourRow(label: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = CockpitTheme.typography.labelLarge, color = CockpitTheme.colors.textSecondary, modifier = Modifier.weight(1f), maxLines = 1)
        Text(value, style = CockpitTheme.typography.headlineSmall, color = color, maxLines = 1)
    }
}

@Composable
private fun Legend(color: Color, label: String, dot: Dp = 10.dp) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(dot).clip(CircleShape).background(color))
        Text(label, style = CockpitTheme.typography.labelMedium, color = CockpitTheme.colors.textSecondary, maxLines = 1)
    }
}

private fun minutesLabel(sec: Float): String {
    val m = (sec / 60f)
    return if (m >= 1f) "${m.roundToInt()} min" else "${sec.roundToInt()} s"
}

@Suppress("unused")
private fun stdDev(v: FloatArray): Float {
    if (v.isEmpty()) return 0f
    val mean = v.average().toFloat()
    return sqrt(v.fold(0f) { acc, x -> acc + (x - mean) * (x - mean) } / v.size)
}
