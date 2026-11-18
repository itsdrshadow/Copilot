package com.antigravity.copilot.feature.fuel

import androidx.compose.material.icons.outlined.Insights
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
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

/** Fuel screen › Tank: litres, percent, gauge, tank size, mileage and range. */
@Composable
fun FuelTankSummaryCard(
    fuelState: FuelState,
    modifier: Modifier = Modifier,
) {
    val c = CockpitTheme.colors
    val pct = fuelState.fuelPercentage.toDouble().coerceIn(0.0, 100.0)
    Column(
        modifier = modifier.dashCardSurface().padding(DashCard.Padding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DashCardHeader(icon = Icons.Outlined.LocalGasStation, title = "In the tank")
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                NumberFormatUtils.formatFuelLiters(fuelState.remainingLiters),
                style = CockpitTheme.typography.displayMedium,
                color = c.textPrimary,
            )
            Spacer(Modifier.width(4.dp))
            Text("L", style = CockpitTheme.typography.titleMedium, color = c.textSecondary, modifier = Modifier.padding(bottom = 6.dp))
            Spacer(Modifier.weight(1f))
            Text("${pct.toInt()}%", style = CockpitTheme.typography.displaySmall, color = fuelLevelTextColor(pct))
        }
        SegmentBar(
            fillPoints = fuelState.gaugeFillPoints,
            color = fuelLevelColor(pct),
            height = 22.dp,
        )
        DashStatStrip {
            val (kmpl, _) = displayMileage(fuelState)
            StatCell("Mileage", NumberFormatUtils.formatMileage(kmpl), unit = "km/L", modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
            VDivider()
            StatCell(
                "Range",
                if (fuelState.remainingRangeKm > 0) "${fuelState.remainingRangeKm.toInt()}" else "--",
                unit = "km",
                horizontalAlignment = Alignment.End,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            )
        }
        // F5: one tank size; the learned value only when it really differs from the car's spec.
        val design = fuelState.designCapacityLiters
        val actual = fuelState.actualCapacityLiters.takeIf { it > 0.0 } ?: fuelState.maxCapacityLiters
        Text(
            text = if (actual > 0.0 && kotlin.math.abs(actual - design) >= 1.0)
                "Tank size ${design.toInt()} L · fills up to about ${actual.toInt()} L"
            else "Tank size ${design.toInt()} L",
            style = CockpitTheme.typography.bodyMedium,
            color = c.textSecondary,
        )
        fuelState.fuelNotice?.let { InfoLine(it, title = "Fuel notice") }
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

/**
 * Fuel screen › Insights (F-N1..3): this month's spend against last month, a mileage trend line
 * over the last fill-ups, and the most used petrol pump. Hidden until there is a fill-up to show.
 */
@Composable
fun FuelInsightCard(
    events: List<com.antigravity.copilot.core.database.FuelEventEntity>,
    modifier: Modifier = Modifier,
) {
    val c = CockpitTheme.colors
    val fills = remember(events) { events.filter { it.liters > 0.0 && it.totalCost > 0.0 }.sortedBy { it.timestamp } }
    if (fills.isEmpty()) return
    val insight = remember(fills) {
        val cal = java.util.Calendar.getInstance()
        fun key(ts: Long): Int { cal.timeInMillis = ts; return cal.get(java.util.Calendar.YEAR) * 12 + cal.get(java.util.Calendar.MONTH) }
        val thisKey = key(System.currentTimeMillis())
        val thisMonth = fills.filter { key(it.timestamp) == thisKey }.sumOf { it.totalCost }
        val lastMonth = fills.filter { key(it.timestamp) == thisKey - 1 }.sumOf { it.totalCost }
        val trend = fills.map { it.calculatedMileageKmpl }.filter { it > 0.0 }.takeLast(6)
        val topPump = fills.map { it.locationName }.filter { it.isNotBlank() }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }
        Triple(thisMonth to lastMonth, trend, topPump)
    }
    val (spend, trend, topPump) = insight
    Column(
        modifier = modifier.dashCardSurface().padding(DashCard.Padding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DashCardHeader(icon = Icons.Outlined.Insights, title = "At a glance")
        val (thisMonth, lastMonth) = spend
        Text("This month", style = CockpitTheme.typography.labelMedium, color = c.textSecondary)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(NumberFormatUtils.formatCurrencyWithSymbol(thisMonth), style = CockpitTheme.typography.headlineLarge, color = c.textPrimary)
            if (lastMonth > 0.0) {
                val pct = ((thisMonth / lastMonth - 1.0) * 100).toInt()
                Text(
                    if (pct <= 0) "${-pct}% less than last month" else "$pct% more than last month",
                    style = CockpitTheme.typography.bodyMedium,
                    color = if (pct <= 0) c.positiveText else c.warningText,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
        }
        if (trend.size >= 2) {
            Text("Mileage, last ${trend.size} fill-ups", style = CockpitTheme.typography.labelMedium, color = c.textSecondary)
            MileageSparkline(trend, Modifier.fillMaxWidth().height(44.dp))
        }
        topPump?.let { (name, count) ->
            Text("Most used pump", style = CockpitTheme.typography.labelMedium, color = c.textSecondary)
            Text("$name ($count ${if (count == 1) "time" else "times"})", style = CockpitTheme.typography.bodyLarge, color = c.textPrimary, maxLines = 1)
        }
    }
}

@Composable
private fun MileageSparkline(values: List<Double>, modifier: Modifier) {
    val line = CockpitTheme.colors.accent
    androidx.compose.foundation.Canvas(modifier) {
        val lo = values.min(); val hi = maxOf(values.max(), lo + 0.5)
        val pad = 6.dp.toPx()
        val pts = values.mapIndexed { i, v ->
            androidx.compose.ui.geometry.Offset(
                pad + i * (size.width - 2 * pad) / (values.size - 1),
                pad + (1f - ((v - lo) / (hi - lo)).toFloat()) * (size.height - 2 * pad)
            )
        }
        for (i in 1 until pts.size) drawLine(line, pts[i - 1], pts[i], strokeWidth = 2.5.dp.toPx())
        drawCircle(line, radius = 4.dp.toPx(), center = pts.last())
    }
}
