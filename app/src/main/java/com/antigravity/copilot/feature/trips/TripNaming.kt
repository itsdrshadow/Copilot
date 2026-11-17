package com.antigravity.copilot.feature.trips

import androidx.compose.runtime.compositionLocalOf
import com.antigravity.copilot.core.database.TripEntity
import java.util.Calendar

/**
 * Trip titles. Geofences make almost every trip start and end in the same zone ("Home → Home"),
 * so a round trip is named after where it actually went: the farthest point from the start
 * ("Home ⇄ Sarwani"). Very short loops, or trips whose turnaround is not resolved yet, are named
 * by time of day ("Home · Evening loop").
 */
internal data class TripTitle(
    val title: String,
    val isRoundTrip: Boolean,
    /** Short tag for the trip kind: "Round trip", "Local loop" or null for A → B trips. */
    val tag: String?,
)

/**
 * Turnaround place per trip id, resolved once in [TripHistoryViewModel] and cached on disk.
 * "" means the trip never went far from its start (a local loop).
 */
internal val LocalTripViaNames = compositionLocalOf<Map<Long, String>> { emptyMap() }

internal fun isRoundTrip(trip: TripEntity): Boolean {
    val from = trip.startLocationName.trim()
    val to = trip.endLocationName.trim()
    return from.isNotEmpty() && from.equals(to, ignoreCase = true)
}

internal fun tripTitle(trip: TripEntity, via: String?): TripTitle {
    val from = trip.startLocationName.trim().ifBlank { "Start" }
    val to = trip.endLocationName.trim().ifBlank { "Destination" }
    if (!isRoundTrip(trip)) return TripTitle("$from → $to", isRoundTrip = false, tag = null)
    return when {
        !via.isNullOrBlank() -> TripTitle("$from ⇄ $via", isRoundTrip = true, tag = "Round trip")
        via == "" -> TripTitle("$from · ${partOfDay(trip.startTime)} loop", isRoundTrip = true, tag = "Local loop")
        else -> TripTitle("$from · ${partOfDay(trip.startTime)} drive", isRoundTrip = true, tag = "Round trip")
    }
}

internal fun partOfDay(timeMs: Long): String {
    val h = Calendar.getInstance().apply { timeInMillis = timeMs }.get(Calendar.HOUR_OF_DAY)
    return when (h) {
        in 5..11 -> "Morning"
        in 12..16 -> "Afternoon"
        in 17..20 -> "Evening"
        else -> "Night"
    }
}

/**
 * "Unnamed Road, Sarwani" → "Sarwani"; "MG Road, Raigarh" → "Raigarh". The locality reads better
 * in a title than a road name.
 */
internal fun shortPlaceName(full: String): String {
    val parts = full.split(',').map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("Unnamed", ignoreCase = true) }
    return parts.lastOrNull() ?: full.trim()
}
