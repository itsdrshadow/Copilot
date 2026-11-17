package com.antigravity.copilot.feature.trips

import com.antigravity.copilot.core.designsystem.tokens.StaticPalette
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.antigravity.copilot.core.database.TripEntity
import com.antigravity.copilot.core.util.NumberFormatUtils
import com.antigravity.copilot.core.designsystem.*
import java.util.*

internal enum class TripDetailTab(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    MAP_REPLAY("Route Replay", Icons.Default.PlayCircleOutline),
    TIMELINE("Timeline", Icons.Default.Schedule),
    CHARTS("Trip Charts", Icons.AutoMirrored.Filled.ShowChart)
}

internal enum class PlaybackStatus {
    STOPPED, PLAYING, PAUSED, COMPLETED
}

internal sealed class TimelineEvent(
    val timestamp: Long,
    val title: String,
    val description: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val accentColor: Color,
    val speedKmh: Float = 0f
) {
    class TripStart(time: Long, loc: String) : TimelineEvent(
        time, "Trip Started", loc.ifBlank { "Vehicle powered on" },
        Icons.Default.PlayArrow, StaticPalette.Emerald500
    )
    class MotionStarted(time: Long, speed: Float) : TimelineEvent(
        time, "Vehicle in Motion", "Accelerated to ${NumberFormatUtils.formatSpeedWithUnit(speed, 2)}",
        Icons.Default.DirectionsCar, StaticPalette.Sky400, speed
    )
    class VehicleIdle(time: Long, durationMs: Long) : TimelineEvent(
        time, "Vehicle Stationary / Idle", "Standstill for ${formatDurationShort(durationMs)}",
        Icons.Default.PauseCircle, StaticPalette.Amber500
    )
    class MotionResumed(time: Long, speed: Float) : TimelineEvent(
        time, "Movement Resumed", "Cruising at ${NumberFormatUtils.formatSpeedWithUnit(speed, 2)}",
        Icons.Default.FastForward, StaticPalette.Sky400, speed
    )
    class PeakSpeed(time: Long, speed: Float) : TimelineEvent(
        time, "Peak Speed Recorded", "Maximum speed of ${NumberFormatUtils.formatSpeedWithUnit(speed, 2)}",
        Icons.Default.Speed, StaticPalette.Purple500, speed
    )
    class RefuelRecorded(time: Long, liters: Double, cost: Double, station: String) : TimelineEvent(
        time, "Fuel Added",
        "${NumberFormatUtils.formatFuelLitersWithUnit(liters, 2)} refueled" +
            (if (cost > 0) " for ${NumberFormatUtils.formatCurrencyWithSymbol(cost, 2)}" else "") +
            (if (station.isNotBlank()) " at $station" else ""),
        Icons.Default.LocalGasStation, StaticPalette.accent
    )
    class EnginePowerEvent(time: Long, title: String, detail: String) : TimelineEvent(
        time, title, detail,
        Icons.Default.PowerSettingsNew, StaticPalette.Slate400
    )
    class TripEnd(time: Long, loc: String, totalKm: Double) : TimelineEvent(
        time, "Trip Completed", "${loc.ifBlank { "Destination reached" }} • ${NumberFormatUtils.formatDistanceWithUnit(totalKm, 2)}",
        Icons.Default.Flag, StaticPalette.Red500
    )
}

internal data class SpeedBand(val label: String, val percent: Int, val color: Color)
