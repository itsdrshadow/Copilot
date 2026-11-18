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

internal enum class TripDetailTab(
    val title: String,
    val subtitle: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    MAP_REPLAY("Route replay", "Map and animated playback", Icons.Default.PlayCircleOutline),
    TIMELINE("Timeline", "Every event, start to finish", Icons.Default.Schedule),
    CHARTS("Charts", "Speed, braking and efficiency", Icons.AutoMirrored.Filled.ShowChart)
}

internal enum class PlaybackStatus {
    STOPPED, PLAYING, PAUSED, COMPLETED
}

/** What happened at a timeline point; drives the node colour and the ribbon marker. */
internal enum class TimelineKind {
    START, ZONE_EXIT, MOTION, MILESTONE, STOP, RESUME, HARD_BRAKE, HARD_ACCEL,
    PEAK_SPEED, TURNAROUND, REFUEL, ENGINE, ZONE_ENTER, END
}

/**
 * One row of the trip timeline. [distanceKm] is the odometer of the trip at that moment (-1 when
 * unknown); [chips] are short facts shown under the title ("54 km/h", "2 min").
 */
internal data class TimelineEvent(
    val timestamp: Long,
    val kind: TimelineKind,
    val title: String,
    val description: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val accentColor: Color,
    val speedKmh: Float = 0f,
    val distanceKm: Double = -1.0,
    val chips: List<String> = emptyList()
)

internal data class SpeedBand(val label: String, val percent: Int, val color: Color)
