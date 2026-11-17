package com.antigravity.copilot.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.antigravity.copilot.core.designsystem.CockpitTheme
import com.antigravity.copilot.core.designsystem.components.InfoLine
import com.antigravity.copilot.core.designsystem.components.Raahi
import com.antigravity.copilot.core.designsystem.components.RaahiButton
import com.antigravity.copilot.core.designsystem.components.RaahiButtonKind
import com.antigravity.copilot.core.designsystem.components.RaahiDialogHost
import com.antigravity.copilot.core.designsystem.components.RaahiLabel
import com.antigravity.copilot.core.designsystem.components.SegmentBar
import com.antigravity.copilot.core.designsystem.components.StatCell
import com.antigravity.copilot.core.designsystem.components.fuelLevelColor
import com.antigravity.copilot.core.designsystem.components.raahiCard
import com.antigravity.copilot.core.model.FuelState
import com.antigravity.copilot.core.model.MileageLearningState
import com.antigravity.copilot.core.util.NumberFormatUtils
import java.util.Locale

/** Text colour for a fuel percent (same thresholds as [fuelLevelColor], contrast-safe in Day). */
@Composable
fun fuelLevelTextColor(percent: Double): Color = when {
    percent < 15.0 -> CockpitTheme.colors.criticalText
    percent <= 40.0 -> CockpitTheme.colors.warningText
    else -> CockpitTheme.colors.positiveText
}

/** Learned (confirmed) or estimated km/L as shown everywhere, with its label. */
fun displayMileage(fuelState: FuelState): Pair<Double, String> {
    val confirmed = fuelState.mileageLearningState == MileageLearningState.LEARNED && fuelState.currentMileageKmpl > 0.0
    val value = when {
        fuelState.currentMileageKmpl > 0.0 -> fuelState.currentMileageKmpl
        fuelState.expectedMileageKmpl > 0.0 -> fuelState.expectedMileageKmpl
        else -> 18.0
    }
    return value to if (confirmed) "Learned km/L" else "Est. km/L"
}

/**
 * Drive screen Fuel card: litres, percent (green > 40 %, amber 15–40 %, red < 15 %), 10-segment
 * bar, estimated range, learned km/L and "Add fuel". Tapping the bar opens the gauge calibration
 * (the only place the gauge is calibrated from). Long notices are one line with an info button.
 */
@Composable
fun FuelTankCard(
    fuelState: FuelState,
    onClick: () -> Unit,
    onCalibrateGauge: (Double) -> Unit = {},
    onDismissNotice: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val c = CockpitTheme.colors
    val tankPct = fuelState.fuelPercentage.toDouble().coerceIn(0.0, 100.0)
    var showGaugeDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .raahiCard()
            .padding(Raahi.CardPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Header pinned to the top; the readouts sit at the bottom with free space between.
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RaahiLabel("Fuel", modifier = Modifier.weight(1f))
            RaahiButton(text = "Fuel", onClick = onClick, icon = Icons.Outlined.Add)
        }
        Spacer(Modifier.height(4.dp))
        Spacer(Modifier.weight(1f))

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(
                text = NumberFormatUtils.formatFuelLiters(fuelState.remainingLiters, 1),
                style = CockpitTheme.typography.displayMedium,
                color = c.textPrimary,
                maxLines = 1,
                softWrap = false
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "L",
                style = CockpitTheme.typography.titleLarge,
                color = c.textSecondary,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${tankPct.toInt()}%",
                style = CockpitTheme.typography.displaySmall,
                color = fuelLevelTextColor(tankPct),
                maxLines = 1
            )
        }

        // Continuous fill from estimated litres (the numeric gauge point is never shown).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Raahi.MinTouch)
                .clip(Raahi.InnerShape)
                .semantics { contentDescription = "Fuel gauge. Tap to calibrate." }
                .clickable(role = Role.Button) { showGaugeDialog = true },
            contentAlignment = Alignment.Center
        ) {
            SegmentBar(
                fillPoints = fuelState.gaugeFillPoints.coerceIn(0.0, 10.0),
                color = fuelLevelColor(tankPct),
                height = 22.dp
            )
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            val rangeKm = fuelState.remainingRangeKm
            StatCell(
                label = "Est. range",
                value = if (rangeKm > 0) "${rangeKm.toInt()}" else "--",
                unit = "km",
                valueStyle = CockpitTheme.typography.headlineLarge,
                modifier = Modifier.weight(1f)
            )
            val (kmpl, label) = displayMileage(fuelState)
            StatCell(
                label = label,
                value = String.format(Locale.US, "%.1f", kmpl),
                unit = "km/L",
                valueStyle = CockpitTheme.typography.headlineLarge,
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.End
            )
        }

        // Quiet fuel-model notice: one line, never cut off (info button opens the full text).
        fuelState.fuelNotice?.let { notice ->
            InfoLine(text = notice, title = "Fuel notice", onDismiss = onDismissNotice)
        }
    }

    if (showGaugeDialog) {
        GaugeCalibrationDialog(
            currentPoint = fuelState.gaugeFillPoints,
            capacityLiters = fuelState.designCapacityLiters,
            onDismiss = { showGaugeDialog = false },
            onApply = { point ->
                onCalibrateGauge(point)
                showGaugeDialog = false
            }
        )
    }
}

/** Next selection when segment [bar] is tapped: full → half → (bar 1 only) empty → full. */
fun nextGaugePoint(current: Double, bar: Int): Double = when (current) {
    bar.toDouble() -> bar - 0.5
    bar - 0.5 -> if (bar == 1) 0.0 else bar.toDouble()
    else -> bar.toDouble()
}

fun gaugePointLabel(point: Double): String =
    if (point % 1.0 == 0.0) "${point.toInt()}" else "${point.toInt()}½"

/**
 * Tappable 10-segment gauge, drawn like the home-screen gauge (one row of segments, partial fill
 * for a half point) but tall enough to tap. Segment numbers sit underneath.
 */
@Composable
fun TappableGauge(
    point: Double,
    onTapBar: (Int) -> Unit,
    modifier: Modifier = Modifier,
    segmentHeight: Dp = 64.dp,
    fillColor: Color = CockpitTheme.colors.accent
) {
    val c = CockpitTheme.colors
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (bar in 1..10) {
                val fill = (point - (bar - 1)).coerceIn(0.0, 1.0).toFloat()
                val shape = RoundedCornerShape(8.dp)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(segmentHeight)
                        .clip(shape)
                        .background(c.cardElevated)
                        .border(1.dp, if (fill > 0f) fillColor else c.border, shape)
                        .semantics { contentDescription = "Bar $bar" }
                        .clickable(role = Role.Button) { onTapBar(bar) }
                ) {
                    if (fill > 0f) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(fill).background(fillColor))
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (bar in 1..10) {
                Text(
                    text = "$bar",
                    style = CockpitTheme.typography.labelMedium,
                    color = c.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * "Calibrate gauge": the driver taps the bars the car's own fuel gauge shows (tap a lit top bar
 * again for a half bar). Opened from the home-screen fuel gauge only.
 */
@Composable
fun GaugeCalibrationDialog(
    currentPoint: Double,
    capacityLiters: Double,
    onDismiss: () -> Unit,
    onApply: (Double) -> Unit
) {
    val c = CockpitTheme.colors
    val initial = (Math.round(currentPoint * 2.0) / 2.0).coerceIn(0.0, 10.0)
    var point by remember { mutableDoubleStateOf(initial) }
    val estLiters = (point * capacityLiters / 10.0).coerceIn(0.0, capacityLiters)

    RaahiDialogHost(onDismissRequest = onDismiss, maxWidth = 900.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .raahiCard()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier.size(48.dp).clip(Raahi.InnerShape).background(c.cardElevated),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Tune, contentDescription = null, tint = c.accent, modifier = Modifier.size(24.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Calibrate fuel gauge", style = CockpitTheme.typography.titleLarge, color = c.textPrimary)
                    Text(
                        "Tap the bars your car's gauge shows. Tap the top bar again for half a bar.",
                        style = CockpitTheme.typography.bodyMedium,
                        color = c.textSecondary
                    )
                }
            }

            // Readout row: bars, percent and litres, each on its own line so nothing wraps.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(Raahi.InnerShape)
                    .background(c.cardElevated)
                    .border(1.dp, c.border, Raahi.InnerShape)
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatCell(label = "Bars", value = gaugePointLabel(point), unit = "of 10", modifier = Modifier.weight(1f))
                StatCell(label = "Level", value = "${(point * 10).toInt()}", unit = "%", modifier = Modifier.weight(1f))
                StatCell(
                    label = "Estimated fuel",
                    value = NumberFormatUtils.formatPrecision(estLiters, 1),
                    unit = "of ${capacityLiters.toInt()} L",
                    modifier = Modifier.weight(1.4f),
                    horizontalAlignment = Alignment.End
                )
            }

            TappableGauge(point = point, onTapBar = { bar -> point = nextGaugePoint(point, bar) })

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                RaahiButton(
                    text = "Cancel",
                    onClick = onDismiss,
                    kind = RaahiButtonKind.Secondary,
                    modifier = Modifier.weight(1f)
                )
                RaahiButton(
                    text = "Log reading (${gaugePointLabel(point)}/10)",
                    onClick = { onApply(point) },
                    modifier = Modifier.weight(1.6f)
                )
            }
        }
    }
}

/** Backward-compatibility alias for PremiumFuelTankCard. */
@Composable
fun PremiumFuelTankCard(
    fuelState: FuelState,
    onClick: () -> Unit,
    onCalibrateGauge: (Double) -> Unit = {},
    modifier: Modifier = Modifier
) = FuelTankCard(
    fuelState = fuelState,
    onClick = onClick,
    onCalibrateGauge = onCalibrateGauge,
    modifier = modifier
)
