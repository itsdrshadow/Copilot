package com.antigravity.copilot.feature.fuel

import androidx.compose.material.icons.outlined.Tune

import androidx.compose.material.icons.outlined.LocalGasStation

import androidx.compose.foundation.layout.Column

import com.antigravity.copilot.core.designsystem.components.VDivider

import com.antigravity.copilot.feature.dashboard.dashCardSurface

import com.antigravity.copilot.feature.dashboard.DashStatStrip

import com.antigravity.copilot.feature.dashboard.DashCardHeader

import com.antigravity.copilot.feature.dashboard.DashCard

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.antigravity.copilot.core.designsystem.CockpitTheme
import com.antigravity.copilot.core.designsystem.components.InfoLine
import com.antigravity.copilot.core.designsystem.components.RaahiButton
import com.antigravity.copilot.core.designsystem.components.RaahiButtonKind
import com.antigravity.copilot.core.designsystem.components.RaahiCard
import com.antigravity.copilot.core.designsystem.components.RaahiLabel
import com.antigravity.copilot.core.designsystem.components.SegmentBar
import com.antigravity.copilot.core.designsystem.components.StatCell
import com.antigravity.copilot.core.designsystem.components.fuelLevelColor
import com.antigravity.copilot.core.model.FuelState
import com.antigravity.copilot.core.util.NumberFormatUtils
import com.antigravity.copilot.feature.dashboard.displayMileage
import com.antigravity.copilot.feature.dashboard.fuelLevelTextColor
import com.antigravity.copilot.feature.dashboard.gaugePointLabel
import java.util.Locale

/** Fuel screen › Tank: litres, percent, 10 segments, OEM vs learned (actual) capacity, km/L. */
@Composable
fun FuelTankSummaryCard(
    fuelState: FuelState,
    modifier: Modifier = Modifier,
) {
    val c = CockpitTheme.colors
    val pct = fuelState.fuelPercentage.toDouble().coerceIn(0.0, 100.0)
    Column(
        modifier = modifier.dashCardSurface().padding(DashCard.Padding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DashCardHeader(icon = Icons.Outlined.LocalGasStation, title = "TANK")
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                NumberFormatUtils.formatFuelLiters(fuelState.remainingLiters, 1),
                style = CockpitTheme.typography.displaySmall,
                color = c.textPrimary,
            )
            Spacer(Modifier.width(4.dp))
            Text("L", style = CockpitTheme.typography.titleMedium, color = c.textSecondary, modifier = Modifier.padding(bottom = 4.dp))
            Spacer(Modifier.weight(1f))
            Text("${pct.toInt()}%", style = CockpitTheme.typography.headlineLarge, color = fuelLevelTextColor(pct))
        }
        SegmentBar(
            fillPoints = fuelState.gaugeFillPoints,
            color = fuelLevelColor(pct),
            height = 18.dp,
            segmentColor = { bar ->
                when {
                    bar <= 2 -> c.critical
                    bar <= 4 -> c.warning
                    else -> c.positive
                }
            },
        )
        DashStatStrip {
            StatCell("OEM capacity", "${fuelState.designCapacityLiters.toInt()}", unit = "L", modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
            VDivider()
            val actual = fuelState.actualCapacityLiters.takeIf { it > 0.0 } ?: fuelState.maxCapacityLiters
            StatCell(
                "Learned",
                String.format(Locale.US, "%.1f", actual),
                unit = "L",
                horizontalAlignment = Alignment.End,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            )
        }
        DashStatStrip {
            val (kmpl, label) = displayMileage(fuelState)
            StatCell(label, String.format(Locale.US, "%.1f", kmpl), unit = "km/L", modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
            VDivider()
            StatCell(
                "Est. range",
                if (fuelState.remainingRangeKm > 0) "${fuelState.remainingRangeKm.toInt()}" else "--",
                unit = "km",
                horizontalAlignment = Alignment.End,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            )
        }
        fuelState.fuelNotice?.let { InfoLine(it, title = "Fuel notice") }
    }
}

/**
 * Fuel screen › Gauge. Read-only: the gauge is calibrated only by tapping the fuel gauge on the
 * Drive screen. Shows where the app currently reads the gauge.
 */
@Composable
fun GaugeStatusCard(
    fuelState: FuelState,
    onOpenDrive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = CockpitTheme.colors
    Column(
        modifier = modifier.dashCardSurface().padding(DashCard.Padding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DashCardHeader(icon = Icons.Outlined.Tune, title = "FUEL GAUGE")
        val point = (Math.round(fuelState.gaugeFillPoints * 2.0) / 2.0).coerceIn(0.0, 10.0)
        StatCell("App reads the gauge at", gaugePointLabel(point), unit = "of 10 bars", valueStyle = CockpitTheme.typography.headlineLarge)
        Text(
            "To log a gauge reading, tap the fuel gauge on the Drive screen and pick the bars your car shows.",
            style = CockpitTheme.typography.bodyMedium,
            color = c.textSecondary,
        )
        Spacer(Modifier.weight(1f))
        RaahiButton(
            text = "Go to Drive",
            icon = Icons.Outlined.DirectionsCar,
            kind = RaahiButtonKind.Secondary,
            onClick = onOpenDrive,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Fuel screen › Add fuel: opens the Log refuel form (amount, price, odometer, live litres). */
@Composable
fun AddFuelCard(
    fuelState: FuelState,
    onAddFuel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = CockpitTheme.colors
    RaahiCard(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                RaahiLabel("Add fuel")
                Text(
                    "Amount, price per litre and odometer; litres are worked out for you.",
                    style = CockpitTheme.typography.bodyMedium,
                    color = c.textSecondary,
                )
            }
            Spacer(Modifier.width(12.dp))
            RaahiButton(text = "Add fuel", icon = Icons.Outlined.Add, onClick = onAddFuel)
        }
        if (fuelState.fuelPricePerLiter > 0) {
            Text(
                "Last price ₹${NumberFormatUtils.formatPrecision(fuelState.fuelPricePerLiter, 2)} / L",
                style = CockpitTheme.typography.bodySmall,
                color = c.textSecondary,
            )
        }
    }
}
