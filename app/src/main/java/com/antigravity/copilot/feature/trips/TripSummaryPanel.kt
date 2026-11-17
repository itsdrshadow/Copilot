package com.antigravity.copilot.feature.trips

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.antigravity.copilot.core.database.TripEntity
import com.antigravity.copilot.core.designsystem.CockpitTheme
import com.antigravity.copilot.core.designsystem.components.RaahiLabel
import com.antigravity.copilot.core.designsystem.components.RaahiPill
import com.antigravity.copilot.core.designsystem.components.StatCard
import com.antigravity.copilot.core.util.NumberFormatUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Map layers on the trip map. MILEAGE is defined but hidden: there is no per-trip mileage data. */
enum class TripMapLayer(
    val label: String,
) {
    TRAIL("Trail"),
    OVERLAP("Overlap"),
    MILEAGE("Mileage"),
}

/** Per-trip mileage colouring does not exist in the app yet, so the Mileage toggle stays hidden. */
const val TRIP_MILEAGE_LAYER_AVAILABLE = false

val visibleTripLayers: List<TripMapLayer>
    get() = TripMapLayer.entries.filter { it != TripMapLayer.MILEAGE || TRIP_MILEAGE_LAYER_AVAILABLE }

private fun minutesText(ms: Long): String {
    val m = ms / 60_000L
    return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
}

/**
 * Trip summary (Raahi): date + route header, 2-column stat cards, driving events, and a footnote
 * that fuel figures are estimates.
 */
@Composable
fun TripSummaryPanel(
    trip: TripEntity,
    modifier: Modifier = Modifier,
) {
    val c = CockpitTheme.colors
    val totalMs =
        if (trip.endTime > trip.startTime) {
            trip.endTime - trip.startTime
        } else {
            trip.drivingDurationMs + trip.stationaryDurationMs
        }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {

        @Composable
        fun pair(
            a: @Composable (Modifier) -> Unit,
            b: @Composable (Modifier) -> Unit,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                a(Modifier.weight(1f))
                b(Modifier.weight(1f))
            }
        }

        pair(
            { StatCard("Distance", NumberFormatUtils.formatDistance(trip.distanceMeters / 1000.0), unit = "km", modifier = it) },
            { StatCard("Trip time", minutesText(totalMs), modifier = it) },
        )
        pair(
            { StatCard("Moving", minutesText(trip.drivingDurationMs), modifier = it) },
            { StatCard("Idle", minutesText(trip.stationaryDurationMs), modifier = it) },
        )
        pair(
            { StatCard("Max speed", "${trip.maxSpeedKmh.toInt()}", unit = "km/h", modifier = it) },
            { StatCard("Avg speed", "${trip.avgSpeedKmh.toInt()}", unit = "km/h", modifier = it) },
        )
        pair(
            { StatCard("Est. fuel", NumberFormatUtils.formatFuelLiters(trip.estimatedFuelLiters, 2), unit = "L", modifier = it) },
            {
                StatCard(
                    "Trip mileage",
                    if (trip.calculatedMileageKmpl > 0) NumberFormatUtils.formatMileage(trip.calculatedMileageKmpl, 1) else "--",
                    unit = "km/L",
                    modifier = it,
                )
            },
        )
        pair(
            {
                StatCard(
                    "Best mileage",
                    if (trip.bestMileageKmpl > 0) NumberFormatUtils.formatMileage(trip.bestMileageKmpl, 1) else "--",
                    unit = "km/L",
                    modifier = it,
                )
            },
            { StatCard("Drive score", "${trip.driverScore}", modifier = it) },
        )

        RaahiLabel("Driving events")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RaahiPill(
                "${trip.brakeCount} braking",
                color =
                    if (trip.brakeCount >
                        0
                    ) {
                        c.critical
                    } else {
                        c.textSecondary
                    },
                filled = trip.brakeCount > 0,
            )
            RaahiPill(
                "${trip.accelCount} hard accel.",
                color =
                    if (trip.accelCount >
                        0
                    ) {
                        c.accent
                    } else {
                        c.textSecondary
                    },
                filled = trip.accelCount > 0,
            )
        }

        Text(
            "Fuel and mileage for a trip are estimates from distance, speed and your learned km/L. They never change the tank level.",
            style = CockpitTheme.typography.bodySmall,
            color = c.textSecondary,
        )
    }
}
