package com.antigravity.copilot.feature.trips

import com.antigravity.copilot.core.designsystem.tokens.Palette
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
import kotlin.math.roundToInt

internal fun generateTripTimeline(
    trip: TripEntity,
    points: List<TripPointEntity>,
    transitions: List<VehicleStateTransitionEntity> = emptyList(),
    fuelEvents: List<FuelLedgerEntity> = emptyList()
): List<TimelineEvent> {
    val events = mutableListOf<TimelineEvent>()
    val startTime = trip.startTime
    val endTime = trip.endTime.coerceAtLeast(startTime)

    events.add(TimelineEvent.TripStart(startTime, trip.startLocationName))

    // Motion started
    val motionPt = points.firstOrNull { it.speedKmh >= 5.0f }
    if (motionPt != null && motionPt.timestamp > startTime) {
        events.add(TimelineEvent.MotionStarted(motionPt.timestamp, motionPt.speedKmh))
    }

    // Detect idle/stopped intervals (>= 30 seconds below 3 km/h)
    var idleStart: TripPointEntity? = null
    for (i in 1 until points.size) {
        val pt = points[i]
        if (pt.speedKmh < 3.0f) {
            if (idleStart == null) idleStart = pt
        } else {
            if (idleStart != null) {
                val idleDur = pt.timestamp - idleStart.timestamp
                if (idleDur >= 30_000L) {
                    events.add(TimelineEvent.VehicleIdle(idleStart.timestamp, idleDur))
                    events.add(TimelineEvent.MotionResumed(pt.timestamp, pt.speedKmh))
                }
                idleStart = null
            }
        }
    }

    // Refuel events recorded during this trip
    for (fe in fuelEvents) {
        if (fe.eventType == "REFUEL" || fe.eventType == "INITIAL_FUEL_BASELINE") {
            events.add(TimelineEvent.RefuelRecorded(fe.timestamp, fe.fuelQuantityLiters, fe.totalAmount, fe.locationName))
        }
    }

    // Verified Engine Power transitions
    for (tr in transitions) {
        if (tr.toState == "ENGINE_OFF") {
            events.add(TimelineEvent.EnginePowerEvent(tr.timestamp, "Engine Powered Off", "Ignition severed (${tr.triggerReason})"))
        } else if (tr.fromState == "ENGINE_OFF" && tr.toState == "IDLE") {
            events.add(TimelineEvent.EnginePowerEvent(tr.timestamp, "Engine Ignition Restored", "Electrical system active"))
        }
    }

    // Peak speed event
    val maxPt = points.maxByOrNull { it.speedKmh }
    if (maxPt != null && maxPt.speedKmh >= 20.0f) {
        events.add(TimelineEvent.PeakSpeed(maxPt.timestamp, maxPt.speedKmh))
    }

    // Trip Completed event
    val endPtTime = if (points.isNotEmpty()) points.last().timestamp else endTime
    events.add(TimelineEvent.TripEnd(endPtTime, trip.endLocationName, trip.distanceMeters / 1000.0))

    return events.sortedBy { it.timestamp }
}

internal fun computeSpeedBands(points: List<TripPointEntity>): List<SpeedBand> {
    if (points.isEmpty()) {
        return listOf(
            SpeedBand("0-20", 0, Palette.Amber500),
            SpeedBand("20-40", 0, Palette.Sky400),
            SpeedBand("40-60", 0, Palette.Emerald500),
            SpeedBand("60+", 0, Palette.Purple500)
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
        SpeedBand("0-20", ((b1 / n) * 100).roundToInt(), Palette.Amber500),
        SpeedBand("20-40", ((b2 / n) * 100).roundToInt(), Palette.Sky400),
        SpeedBand("40-60", ((b3 / n) * 100).roundToInt(), Palette.Emerald500),
        SpeedBand("60+", ((b4 / n) * 100).roundToInt(), Palette.Purple500)
    )
}

internal fun formatDurationHoursMinutes(durationMs: Long): String {
    return com.antigravity.copilot.core.trip.TimeFormatUtils.formatAdaptiveDuration(durationMs)
}

internal fun formatDurationShort(durationMs: Long): String {
    val totalSec = durationMs / 1000L
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}
