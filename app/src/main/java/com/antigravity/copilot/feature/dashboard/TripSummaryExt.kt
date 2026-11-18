package com.antigravity.copilot.feature.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.antigravity.copilot.core.model.TripStatus
import com.antigravity.copilot.core.model.TripSummary
import kotlinx.coroutines.delay

internal fun TripSummary.isActive(): Boolean =
    status == TripStatus.ACTIVE_TRIP || status == TripStatus.MOVEMENT_CANDIDATE || status == TripStatus.TEMPORARY_STOP

internal fun TripSummary.isCompleted(): Boolean = !isActive() && (status == TripStatus.COMPLETED || (endTime > 0L && endTime >= startTime))

/** Elapsed trip wall-clock time (live while active, frozen once completed), ticking each second. */
@Composable
internal fun rememberTripElapsedMs(trip: TripSummary): Long {
    val active = trip.isActive()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) {
        while (active) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    return when {
        trip.isCompleted() -> {
            if (trip.endTime > trip.startTime && trip.startTime > 0L) {
                trip.endTime - trip.startTime
            } else {
                (trip.drivingDurationMs + trip.stationaryDurationMs).coerceAtLeast(0L)
            }
        }

        active && trip.startTime > 0L -> {
            (now - trip.startTime).coerceAtLeast(0L)
        }

        else -> {
            0L
        }
    }
}
