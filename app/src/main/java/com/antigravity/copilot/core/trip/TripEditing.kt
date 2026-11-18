package com.antigravity.copilot.core.trip

import com.antigravity.copilot.core.database.TripEntity
import com.antigravity.copilot.core.database.TripPointEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Merge two consecutive trips (audit D6): [first] keeps its id; points are re-parented by the caller. */
object TripMerge {
    fun merge(first: TripEntity, second: TripEntity): TripEntity {
        val a = if (first.startTime <= second.startTime) first else second
        val b = if (a === first) second else first
        val distance = a.distanceMeters + b.distanceMeters
        val driving = a.drivingDurationMs + b.drivingDurationMs
        val fuel = a.estimatedFuelLiters + b.estimatedFuelLiters
        val drivingHours = driving / 3_600_000.0
        val avg = if (drivingHours > 0.002) (distance / 1000.0) / drivingHours else maxOf(a.avgSpeedKmh, b.avgSpeedKmh)
        val gapMs = (b.startTime - maxOf(a.endTime, a.startTime)).coerceAtLeast(0L)
        val score = if (distance > 0) ((a.driverScore * a.distanceMeters + b.driverScore * b.distanceMeters) / distance).toInt()
        else minOf(a.driverScore, b.driverScore)
        return a.copy(
            id = first.id,
            endTime = maxOf(a.endTime, b.endTime),
            distanceMeters = distance,
            drivingDurationMs = driving,
            // The pause between the two parts becomes idle time of the merged trip.
            stationaryDurationMs = a.stationaryDurationMs + b.stationaryDurationMs + gapMs,
            avgSpeedKmh = avg,
            maxSpeedKmh = maxOf(a.maxSpeedKmh, b.maxSpeedKmh),
            estimatedFuelLiters = fuel,
            calculatedMileageKmpl = if (fuel > 1e-6) (distance / 1000.0) / fuel else a.calculatedMileageKmpl,
            driverScore = score.coerceIn(0, 100),
            brakeCount = a.brakeCount + b.brakeCount,
            accelCount = a.accelCount + b.accelCount,
            bestMileageKmpl = maxOf(a.bestMileageKmpl, b.bestMileageKmpl),
            endLocationName = b.endLocationName,
            status = "COMPLETED",
            isQualifying = distance >= 1000.0
        )
    }
}

/** GPX 1.1 export of a trip track (audit J3). */
object GpxWriter {
    private fun iso(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun write(trip: TripEntity, points: List<TripPointEntity>): String {
        val name = esc("${trip.startLocationName} → ${trip.endLocationName}".trim())
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"Copilot\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <metadata><name>").append(name).append("</name><time>").append(iso(trip.startTime)).append("</time></metadata>\n")
        sb.append("  <trk><name>").append(name).append("</name><trkseg>\n")
        for (p in points.sortedBy { it.timestamp }) {
            if (p.latitude == 0.0 && p.longitude == 0.0) continue
            sb.append(String.format(Locale.US, "    <trkpt lat=\"%.7f\" lon=\"%.7f\">", p.latitude, p.longitude))
            if (p.altitude != 0.0) sb.append(String.format(Locale.US, "<ele>%.1f</ele>", p.altitude))
            sb.append("<time>").append(iso(p.timestamp)).append("</time>")
            sb.append(String.format(Locale.US, "<extensions><speed>%.2f</speed></extensions>", p.speedKmh / 3.6))
            sb.append("</trkpt>\n")
        }
        sb.append("  </trkseg></trk>\n</gpx>\n")
        return sb.toString()
    }

    fun fileName(trip: TripEntity): String =
        "trip_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(trip.startTime)) + ".gpx"
}
