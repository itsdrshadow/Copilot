package com.antigravity.copilot.feature.trips

import com.antigravity.copilot.core.designsystem.tokens.StaticPalette
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.antigravity.copilot.core.database.TripEntity
import com.antigravity.copilot.core.database.VehicleStateTransitionEntity
import com.antigravity.copilot.core.database.FuelLedgerEntity
import com.antigravity.copilot.core.database.TripPointEntity
import com.antigravity.copilot.core.designsystem.*
import java.util.*
import com.antigravity.copilot.core.util.NumberFormatUtils
import androidx.compose.material.icons.Icons
import kotlin.math.roundToInt

/**
 * Builds the trip timeline from GPS points, state transitions and fuel events:
 * start, leaving / entering a geofence zone, first movement, distance milestones, stops and
 * resumes, hard braking / acceleration, peak speed, the turnaround of a round trip, refuels,
 * engine on/off and the finish.
 *
 * [zoneAt] names the geofence containing a point (null outside every zone); [turnaroundName] is
 * the resolved farthest place of a round trip.
 */
internal fun generateTripTimeline(
    trip: TripEntity,
    points: List<TripPointEntity>,
    transitions: List<VehicleStateTransitionEntity> = emptyList(),
    fuelEvents: List<FuelLedgerEntity> = emptyList(),
    zoneAt: ((Double, Double) -> String?)? = null,
    turnaroundName: String? = null
): List<TimelineEvent> {
    val events = mutableListOf<TimelineEvent>()
    val startTime = trip.startTime
    val endTime = trip.endTime.coerceAtLeast(startTime)
    val pts = points.filter { it.latitude != 0.0 || it.longitude != 0.0 }

    // Cumulative trip distance (km) at every point.
    val cumKm = DoubleArray(pts.size)
    for (i in 1 until pts.size) {
        val a = pts[i - 1]; val b = pts[i]
        cumKm[i] = cumKm[i - 1] + com.antigravity.copilot.core.logic.GeoMath.haversineM(a.latitude, a.longitude, b.latitude, b.longitude) / 1000.0
    }
    fun kmAtTime(t: Long): Double {
        if (pts.isEmpty()) return -1.0
        val i = pts.indexOfLast { it.timestamp <= t }.coerceAtLeast(0)
        return cumKm[i]
    }
    fun spd(v: Float) = NumberFormatUtils.formatSpeedWithUnit(v, 0)
    fun km(v: Double) = NumberFormatUtils.formatDistanceWithUnit(v, if (v < 10) 2 else 1)

    // 1. Start
    val startZone = pts.firstOrNull()?.let { zoneAt?.invoke(it.latitude, it.longitude) }
    events += TimelineEvent(
        startTime, TimelineKind.START, "Trip started",
        "From ${trip.startLocationName.ifBlank { startZone ?: "start point" }}",
        Icons.Default.PlayArrow, StaticPalette.Emerald500, distanceKm = 0.0
    )

    // 2. Left the start zone
    if (startZone != null && zoneAt != null) {
        val exit = pts.indexOfFirst { zoneAt(it.latitude, it.longitude) != startZone }
        if (exit > 0) {
            val p = pts[exit]
            events += TimelineEvent(
                p.timestamp, TimelineKind.ZONE_EXIT, "Left $startZone zone",
                "Crossed the geofence boundary", Icons.Default.Logout, StaticPalette.Sky400,
                speedKmh = p.speedKmh, distanceKm = cumKm[exit], chips = listOf(spd(p.speedKmh))
            )
        }
    }

    // 3. First movement
    val motionIdx = pts.indexOfFirst { it.speedKmh >= 5.0f }
    if (motionIdx >= 0 && pts[motionIdx].timestamp > startTime) {
        val p = pts[motionIdx]
        events += TimelineEvent(
            p.timestamp, TimelineKind.MOTION, "Started moving",
            "Pulled away after ${formatDurationHoursMinutes(p.timestamp - startTime)}",
            Icons.Default.DirectionsCar, StaticPalette.Sky400, speedKmh = p.speedKmh, distanceKm = cumKm[motionIdx]
        )
    }

    // 4. Distance milestones (1 km steps on short trips, wider on long ones)
    val totalKm = if (pts.isNotEmpty()) cumKm.last() else trip.distanceMeters / 1000.0
    val step = when {
        totalKm <= 6 -> 1.0
        totalKm <= 25 -> 5.0
        totalKm <= 120 -> 10.0
        else -> 25.0
    }
    var next = step
    for (i in pts.indices) {
        if (next >= totalKm - step * 0.25) break
        if (cumKm[i] >= next) {
            val p = pts[i]
            events += TimelineEvent(
                p.timestamp, TimelineKind.MILESTONE, "${km(next).replace(".00", "")} covered",
                "After ${formatDurationHoursMinutes(p.timestamp - startTime)} on the road",
                Icons.Default.Straighten, StaticPalette.Slate400, speedKmh = p.speedKmh, distanceKm = cumKm[i],
                chips = listOf(spd(p.speedKmh))
            )
            next += step
        }
    }

    // 5. Stops (>= 30 s below 3 km/h) and resumes
    var idleStartIdx = -1
    for (i in 1 until pts.size) {
        val p = pts[i]
        if (p.speedKmh < 3.0f) {
            if (idleStartIdx < 0) idleStartIdx = i
        } else if (idleStartIdx >= 0) {
            val s0 = pts[idleStartIdx]
            val dur = p.timestamp - s0.timestamp
            if (dur >= 30_000L) {
                events += TimelineEvent(
                    s0.timestamp, TimelineKind.STOP, "Stopped",
                    "Standing still for ${formatDurationHoursMinutes(dur)}",
                    Icons.Default.PauseCircle, StaticPalette.Amber500, distanceKm = cumKm[idleStartIdx],
                    chips = listOf(formatDurationHoursMinutes(dur))
                )
                events += TimelineEvent(
                    p.timestamp, TimelineKind.RESUME, "Moving again",
                    "Back on the road", Icons.Default.FastForward, StaticPalette.Sky400,
                    speedKmh = p.speedKmh, distanceKm = cumKm[i]
                )
            }
            idleStartIdx = -1
        }
    }

    // 6. Hard braking / acceleration from GPS speed changes (strongest few, 15 s apart)
    data class Jolt(val idx: Int, val a: Double)
    val brakes = mutableListOf<Jolt>()
    val accels = mutableListOf<Jolt>()
    for (i in 1 until pts.size) {
        val dt = (pts[i].timestamp - pts[i - 1].timestamp) / 1000.0
        if (dt < 0.8 || dt > 6.0) continue
        val v0 = pts[i - 1].speedKmh / 3.6; val v1 = pts[i].speedKmh / 3.6
        if (maxOf(v0, v1) * 3.6 < 12.0) continue
        val a = (v1 - v0) / dt
        if (a <= -HARD_BRAKE_MSS) brakes += Jolt(i, a)
        if (a >= HARD_ACCEL_MSS) accels += Jolt(i, a)
    }
    fun pick(list: List<Jolt>, strongestFirst: Comparator<Jolt>): List<Jolt> {
        val chosen = mutableListOf<Jolt>()
        for (j in list.sortedWith(strongestFirst)) {
            if (chosen.size >= 8) break
            if (chosen.none { kotlin.math.abs(pts[it.idx].timestamp - pts[j.idx].timestamp) < 15_000L }) chosen += j
        }
        return chosen
    }
    pick(brakes, compareBy { it.a }).forEach { j ->
        val p = pts[j.idx]; val from = pts[j.idx - 1]
        events += TimelineEvent(
            p.timestamp, TimelineKind.HARD_BRAKE, "Hard braking",
            "${spd(from.speedKmh)} → ${spd(p.speedKmh)}",
            Icons.Default.Warning, StaticPalette.Red500, speedKmh = p.speedKmh, distanceKm = cumKm[j.idx],
            chips = listOf(if (j.a <= -4.5) "Very hard" else "Hard")
        )
    }
    pick(accels, compareByDescending { it.a }).forEach { j ->
        val p = pts[j.idx]; val from = pts[j.idx - 1]
        events += TimelineEvent(
            p.timestamp, TimelineKind.HARD_ACCEL, "Quick acceleration",
            "${spd(from.speedKmh)} → ${spd(p.speedKmh)}",
            Icons.Default.Bolt, StaticPalette.Purple500, speedKmh = p.speedKmh, distanceKm = cumKm[j.idx],
            chips = listOf(if (j.a >= 3.5) "Very quick" else "Quick")
        )
    }

    // 7. Peak speed
    val maxIdx = pts.indices.maxByOrNull { pts[it].speedKmh } ?: -1
    if (maxIdx >= 0 && pts[maxIdx].speedKmh >= 20.0f) {
        val p = pts[maxIdx]
        events += TimelineEvent(
            p.timestamp, TimelineKind.PEAK_SPEED, "Top speed",
            "Fastest moment of the trip", Icons.Default.Speed, StaticPalette.Purple500,
            speedKmh = p.speedKmh, distanceKm = cumKm[maxIdx], chips = listOf(spd(p.speedKmh))
        )
    }

    // 8. Turnaround of a round trip (farthest point from the start)
    if (isRoundTrip(trip) && pts.size >= 2) {
        val o = pts.first()
        val farIdx = pts.indices.maxByOrNull {
            com.antigravity.copilot.core.logic.GeoMath.haversineM(o.latitude, o.longitude, pts[it].latitude, pts[it].longitude)
        } ?: -1
        if (farIdx > 0) {
            val p = pts[farIdx]
            val reach = com.antigravity.copilot.core.logic.GeoMath.haversineM(o.latitude, o.longitude, p.latitude, p.longitude) / 1000.0
            if (reach >= 0.3) {
                val place = turnaroundName?.takeIf { it.isNotBlank() } ?: zoneAt?.invoke(p.latitude, p.longitude)
                events += TimelineEvent(
                    p.timestamp, TimelineKind.TURNAROUND,
                    if (place != null) "Turned back at $place" else "Turnaround point",
                    "Farthest point, ${km(reach)} from the start",
                    Icons.Default.UTurnLeft, StaticPalette.Amber500, speedKmh = p.speedKmh, distanceKm = cumKm[farIdx]
                )
            }
        }
    }

    // 9. Refuels
    for (fe in fuelEvents) {
        if (fe.eventType == "REFUEL" || fe.eventType == "INITIAL_FUEL_BASELINE") {
            events += TimelineEvent(
                fe.timestamp, TimelineKind.REFUEL, "Fuel added",
                buildString {
                    append(NumberFormatUtils.formatFuelLitersWithUnit(fe.fuelQuantityLiters, 2))
                    if (fe.totalAmount > 0) append(" for ${NumberFormatUtils.formatCurrencyWithSymbol(fe.totalAmount, 0)}")
                    if (fe.locationName.isNotBlank()) append(" at ${fe.locationName}")
                },
                Icons.Default.LocalGasStation, StaticPalette.accent, distanceKm = kmAtTime(fe.timestamp)
            )
        }
    }

    // 10. Engine power transitions
    for (tr in transitions) {
        if (tr.toState == "ENGINE_OFF") {
            events += TimelineEvent(tr.timestamp, TimelineKind.ENGINE, "Engine off", "Ignition switched off", Icons.Default.PowerSettingsNew, StaticPalette.Slate400, distanceKm = kmAtTime(tr.timestamp))
        } else if (tr.fromState == "ENGINE_OFF" && tr.toState == "IDLE") {
            events += TimelineEvent(tr.timestamp, TimelineKind.ENGINE, "Engine on", "Ignition switched on", Icons.Default.PowerSettingsNew, StaticPalette.Emerald500, distanceKm = kmAtTime(tr.timestamp))
        }
    }

    // 11. Entered the end zone
    val endZone = pts.lastOrNull()?.let { zoneAt?.invoke(it.latitude, it.longitude) }
    if (endZone != null && zoneAt != null) {
        val lastOutside = pts.indexOfLast { zoneAt(it.latitude, it.longitude) != endZone }
        if (lastOutside >= 0 && lastOutside + 1 < pts.size) {
            val p = pts[lastOutside + 1]
            events += TimelineEvent(
                p.timestamp, TimelineKind.ZONE_ENTER, "Arrived in $endZone zone",
                "Crossed into the geofence", Icons.Default.Login, StaticPalette.Emerald500,
                speedKmh = p.speedKmh, distanceKm = cumKm[lastOutside + 1], chips = listOf(spd(p.speedKmh))
            )
        }
    }

    // 12. Finish
    val endPtTime = maxOf(if (pts.isNotEmpty()) pts.last().timestamp else endTime, events.maxOf { it.timestamp })
    events += TimelineEvent(
        endPtTime, TimelineKind.END, "Trip completed",
        "At ${trip.endLocationName.ifBlank { endZone ?: "destination" }}",
        Icons.Default.Flag, StaticPalette.Red500, distanceKm = totalKm,
        chips = listOf(km(totalKm), formatDurationHoursMinutes((endPtTime - startTime).coerceAtLeast(0L)))
    )

    return events.sortedWith(compareBy<TimelineEvent> { it.timestamp }.thenBy { it.kind.ordinal })
}

/** GPS-derived thresholds for the timeline (stricter than the live classifier, GPS is noisy). */
private const val HARD_BRAKE_MSS = 2.5
private const val HARD_ACCEL_MSS = 2.2

internal fun computeSpeedBands(points: List<TripPointEntity>): List<SpeedBand> {
    if (points.isEmpty()) {
        return listOf(
            SpeedBand("0-20", 0, StaticPalette.Amber500),
            SpeedBand("20-40", 0, StaticPalette.Sky400),
            SpeedBand("40-60", 0, StaticPalette.Emerald500),
            SpeedBand("60+", 0, StaticPalette.Purple500)
        )
    }
    var b1 = 0
    var b2 = 0
    var b3 = 0
    var b4 = 0
    points.forEach { pt ->
        val s = pt.speedKmh
        when {
            s < 20f -> b1++
            s < 40f -> b2++
            s < 60f -> b3++
            else -> b4++
        }
    }
    val n = points.size.toFloat()
    return listOf(
        SpeedBand("0-20", ((b1 / n) * 100).roundToInt(), StaticPalette.Amber500),
        SpeedBand("20-40", ((b2 / n) * 100).roundToInt(), StaticPalette.Sky400),
        SpeedBand("40-60", ((b3 / n) * 100).roundToInt(), StaticPalette.Emerald500),
        SpeedBand("60+", ((b4 / n) * 100).roundToInt(), StaticPalette.Purple500)
    )
}

internal fun formatDurationHoursMinutes(durationMs: Long): String {
    if (durationMs <= 0L) return "--"
    return com.antigravity.copilot.core.trip.TimeFormatUtils.formatFriendlyDuration(durationMs)
}

internal fun formatDurationShort(durationMs: Long): String {
    val totalSec = durationMs / 1000L
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}
