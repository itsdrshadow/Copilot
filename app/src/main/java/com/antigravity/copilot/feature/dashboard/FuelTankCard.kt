package com.antigravity.copilot.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WarningAmber
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
import com.antigravity.copilot.core.designsystem.tokens.CopilotShapes
import com.antigravity.copilot.core.designsystem.AutomotiveDesignTokens
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.outlined.LocalGasStation
import com.antigravity.copilot.core.designsystem.components.InfoLine
import com.antigravity.copilot.core.designsystem.components.Raahi
import com.antigravity.copilot.core.designsystem.components.RaahiButton
import com.antigravity.copilot.core.designsystem.components.RaahiButtonKind
import com.antigravity.copilot.core.designsystem.components.RaahiDialogHost
import com.antigravity.copilot.core.designsystem.components.RaahiLabel
import com.antigravity.copilot.core.designsystem.components.SegmentBar
import com.antigravity.copilot.core.designsystem.components.StatCell
import com.antigravity.copilot.core.designsystem.components.VDivider
import com.antigravity.copilot.core.designsystem.components.fuelLevelColor
import com.antigravity.copilot.core.designsystem.components.raahiCard
import com.antigravity.copilot.core.model.FuelState
import com.antigravity.copilot.core.model.MileageLearningState
import com.antigravity.copilot.core.util.NumberFormatUtils
import java.util.Locale

/** Text colour for a fuel percent (same thresholds as [fuelLevelColor], contrast-safe in Day). */
@Composable
fun fuelLevelTextColor(percent: Double): Color =
    animatedHealthColor(MetricHealth.fuelPercentScore(percent))

/** Learned (confirmed) or estimated km/L as shown everywhere, with its label. */
fun displayMileage(fuelState: FuelState): Pair<Double, String> {
    val confirmed = fuelState.mileageLearningState == MileageLearningState.LEARNED && fuelState.currentMileageKmpl > 0.0
    val value = when {
        fuelState.currentMileageKmpl > 0.0 -> fuelState.currentMileageKmpl
        fuelState.expectedMileageKmpl > 0.0 -> fuelState.expectedMileageKmpl
        else -> 18.0
    }
    return value to "Mileage"
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
    reserveMarkerOdometer: Double? = null,
    onToggleReserveLight: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val c = CockpitTheme.colors
    val tankPct = fuelState.fuelPercentage.toDouble().coerceIn(0.0, 100.0)
    var showGaugeDialog by remember { mutableStateOf(false) }

    // Same card anatomy as the other Drive cards (header, hero, gauge, stat strip).
    Column(
        modifier = modifier
            .dashCardSurface()
            .padding(DashCard.Padding),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        DashCardHeader(icon = Icons.Outlined.LocalGasStation, title = "Fuel") {
            AddFuelButton(onClick = onClick)
        }
        // Centre the litres + gauge block in the space between the header and the stat strip.
        Spacer(Modifier.weight(0.45f))

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(
                // Fresh install: no refill / gauge yet, so no fake "50 %" (audit E7).
                text = if (fuelState.isTankUnknown) "--" else NumberFormatUtils.formatFuelLiters(fuelState.remainingLiters, 1),
                style = CockpitTheme.typography.displayLarge.copy(fontSize = 60.sp, lineHeight = 62.sp, shadow = com.antigravity.copilot.core.designsystem.numberGlow(c.textPrimary)),
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
                text = if (fuelState.isTankUnknown) "" else "${tankPct.toInt()}%",
                style = CockpitTheme.typography.displayMedium.copy(shadow = com.antigravity.copilot.core.designsystem.numberGlow(fuelLevelTextColor(tankPct))),
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
            // One colour for the whole fill, picked by level (green / amber / red); empty bars stay grey.
            SegmentBar(
                fillPoints = fuelState.gaugeFillPoints.coerceIn(0.0, 10.0),
                color = fuelLevelColor(tankPct),
                height = 30.dp
            )
        }

        // Low fuel: say it in words with the range, not only a red number.
        // Reserve-light slot (the header has no room left): locked reading; below
        // RESERVE_OFFER_LITERS the low-fuel strip plus the "Reserve light ON" button (the lamp can
        // only be on that close to empty); otherwise just the low-fuel strip when low.
        when {
            fuelState.isTankUnknown -> Text(
                text = "Tank level unknown. Tap the gauge to set it, or add a refill.",
                style = CockpitTheme.typography.bodyMedium,
                color = c.warning
            )
            reserveMarkerOdometer != null ->
                ReserveLockedStrip(odometerKm = reserveMarkerOdometer, onUndo = onToggleReserveLight)
            fuelState.remainingLiters < RESERVE_OFFER_LITERS -> Row(
                // Button matches the strip's height so the card's stat row never gets pushed out.
                modifier = Modifier.height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f)) { LowFuelStrip(rangeKm = fuelState.remainingRangeKm) }
                ReserveLightButton(onClick = onToggleReserveLight)
            }
            tankPct < LOW_FUEL_PERCENT -> LowFuelStrip(rangeKm = fuelState.remainingRangeKm)
        }

        Spacer(Modifier.weight(0.55f))

        DashStatStrip {
            val rangeKm = fuelState.remainingRangeKm
            StatCell(
                label = "Range",
                value = if (rangeKm > 0) "${rangeKm.toInt()}" else "--",
                unit = "km",
                valueStyle = CockpitTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp)
            )
            VDivider()
            val (kmpl, label) = displayMileage(fuelState)
            StatCell(
                label = label,
                value = String.format(Locale.US, "%.1f", kmpl),
                unit = "km/L",
                valueStyle = CockpitTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
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
            capacityLiters = fuelState.actualCapacityLiters,
            curve = fuelState.gaugeCurve,
            currentLiters = fuelState.remainingLiters,
            onDismiss = { showGaugeDialog = false },
            onApply = { point ->
                onCalibrateGauge(point)
                showGaugeDialog = false
            }
        )
    }
}

fun gaugePointLabel(point: Double): String =
    if (point % 1.0 == 0.0) "${point.toInt()}" else "${point.toInt()}½"

/**
 * Tappable 10-segment gauge drawn like a car's fuel gauge: bars grow taller towards Full, with
 * E and F at the ends. Bar numbers sit underneath.
 */
@Composable
fun TappableGauge(
    point: Double,
    onTapBar: (Int) -> Unit,
    modifier: Modifier = Modifier,
    segmentHeight: Dp = 72.dp,
    fillColor: Color = CockpitTheme.colors.accent
) {
    val c = CockpitTheme.colors
    Row(modifier = modifier, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GaugeEndLabel("E", c.critical)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().height(segmentHeight),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                for (bar in 1..10) {
                    val fill = (point - (bar - 1)).coerceIn(0.0, 1.0).toFloat()
                    val shape = RoundedCornerShape(8.dp)
                    // 55 % tall at E rising to full height at F, like the cluster's wedge gauge.
                    val heightFraction = 0.55f + 0.45f * (bar - 1) / 9f
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(heightFraction)
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
        GaugeEndLabel("F", c.positive)
    }
}

@Composable
private fun GaugeEndLabel(letter: String, color: Color) {
    Text(
        text = letter,
        style = CockpitTheme.typography.headlineLarge,
        color = color,
        modifier = Modifier.padding(bottom = 30.dp)
    )
}

/**
 * "Update fuel gauge": the driver taps the bars the car's own fuel gauge shows, and can mark the
 * top bar as half lit with a button. Opens on the app's current estimate; Save is enabled once the
 * driver picks a reading.
 */
@Composable
fun GaugeCalibrationDialog(
    currentPoint: Double,
    capacityLiters: Double,
    onDismiss: () -> Unit,
    onApply: (Double) -> Unit,
    curve: com.antigravity.copilot.core.logic.fuel.GaugeCurve? = null,
    /** Real current litres: shown as-is until the driver picks a different bar. */
    currentLiters: Double? = null
) {
    val c = CockpitTheme.colors
    val initial = (Math.round(currentPoint * 2.0) / 2.0).coerceIn(0.0, 10.0)
    var point by remember { mutableDoubleStateOf(initial) }
    var touched by remember { mutableStateOf(false) }
    // Same litres/percent the home card and Add Fuel show for this many bars: the learned gauge
    // curve and the actual (learned) capacity, not a straight bars x design-capacity split.
    val estLiters = (
        if (currentLiters != null && !touched) currentLiters
        else curve?.litresAt(point) ?: (point * capacityLiters / 10.0)
    ).coerceIn(0.0, capacityLiters)
    val levelPct = if (capacityLiters > 0.0) (estLiters / capacityLiters * 100.0).toInt().coerceIn(0, 100) else 0
    val isHalf = point % 1.0 != 0.0

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
                    Icon(Icons.Outlined.LocalGasStation, contentDescription = null, tint = c.accent, modifier = Modifier.size(26.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Update fuel gauge", style = CockpitTheme.typography.titleLarge, color = c.textPrimary)
                    Text(
                        "Tap the last bar that is lit on your car's fuel gauge.",
                        style = CockpitTheme.typography.bodyMedium,
                        color = c.textSecondary
                    )
                }
            }

            // Readout row: bars, percent and litres. Before the driver taps, this is the app's estimate.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(Raahi.InnerShape)
                    .background(c.cardElevated)
                    .border(1.dp, c.border, Raahi.InnerShape)
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatCell(label = if (touched) "Your gauge" else "App estimate", value = gaugePointLabel(point), unit = "of 10 bars", modifier = Modifier.weight(1.2f))
                StatCell(label = "Tank", value = "$levelPct", unit = "%", modifier = Modifier.weight(1f))
                StatCell(
                    label = "Fuel",
                    value = NumberFormatUtils.formatFuelLiters(estLiters),
                    unit = "of ${capacityLiters.toInt()} L",
                    modifier = Modifier.weight(1.2f),
                    horizontalAlignment = Alignment.End
                )
            }

            TappableGauge(point = point, onTapBar = { bar ->
                // A tap selects the whole bar; the half-bar button handles a half-lit top bar.
                // Tapping bar 1 again empties the gauge.
                point = if (bar == 1 && point == 1.0) 0.0 else bar.toDouble()
                touched = true
            })

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RaahiButton(
                    text = if (isHalf) "Top bar fully lit" else "Top bar half lit",
                    onClick = {
                        point = when {
                            isHalf -> point + 0.5
                            point >= 1.0 -> point - 0.5
                            else -> 0.5
                        }
                        touched = true
                    },
                    kind = RaahiButtonKind.Secondary,
                    modifier = Modifier.weight(1.2f)
                )
                RaahiButton(
                    text = "Cancel",
                    onClick = onDismiss,
                    kind = RaahiButtonKind.Secondary,
                    modifier = Modifier.weight(0.8f)
                )
                RaahiButton(
                    text = if (touched) "Save ${gaugePointLabel(point)} bars" else "Pick a bar",
                    onClick = { onApply(point) },
                    enabled = touched,
                    modifier = Modifier.weight(1.2f)
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

/** Below this percent the card shows the "Fuel low" strip. */
const val LOW_FUEL_PERCENT = 15.0

/** The "Reserve light ON" button is offered only below this estimated fuel (the lamp cannot be on above it). */
const val RESERVE_OFFER_LITERS = 5.0

@Composable
private fun LowFuelStrip(rangeKm: Double) {
    val c = CockpitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Raahi.InnerShape)
            .background(c.critical.copy(alpha = 0.14f))
            .border(1.dp, c.critical.copy(alpha = 0.5f), Raahi.InnerShape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Outlined.LocalGasStation, contentDescription = null, tint = c.critical, modifier = Modifier.size(22.dp))
        Text(
            text = if (rangeKm > 0) "Fuel low · ${rangeKm.toInt()} km left" else "Fuel low · refuel soon",
            style = CockpitTheme.typography.titleSmall,
            color = c.textPrimary,
            maxLines = 1
        )
    }
}

/**
 * One-tap "Reserve light ON": locks the odometer the moment the low-fuel lamp comes on, for
 * reserve-to-reserve mileage. Compact form, used beside the low-fuel strip.
 */
@Composable
private fun ReserveLightButton(onClick: () -> Unit) {
    val c = CockpitTheme.colors
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(52.dp)
            .clip(Raahi.InnerShape)
            .background(c.warning.copy(alpha = 0.12f))
            .border(1.dp, c.warning.copy(alpha = 0.6f), Raahi.InnerShape)
            .clickable(role = Role.Button, onClickLabel = "Reserve light is on", onClick = onClick)
            .semantics { contentDescription = "Reserve light ON" },
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = c.warning, modifier = Modifier.size(24.dp))
    }
}

/** Shown while a reserve-light odometer is locked; tap to undo a mistaken tap. */
@Composable
private fun ReserveLockedStrip(odometerKm: Double, onUndo: () -> Unit) {
    val c = CockpitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Raahi.InnerShape)
            .background(c.warning.copy(alpha = 0.14f))
            .border(1.dp, c.warning.copy(alpha = 0.5f), Raahi.InnerShape)
            .clickable(role = Role.Button, onClickLabel = "Undo reserve light", onClick = onUndo)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = c.warning, modifier = Modifier.size(22.dp))
        Text(
            text = "Reserve @ ${NumberFormatUtils.formatOdometer(odometerKm, 0)} km",
            style = CockpitTheme.typography.titleSmall,
            color = c.textPrimary,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

/**
 * "+ Add Fuel" action for the card header: tonal (tinted, outlined) so the speed stays the hero
 * of the Drive screen while this remains the card's obvious action.
 */
@Composable
private fun AddFuelButton(onClick: () -> Unit) {
    val c = CockpitTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .height(40.dp)
            .clip(shape)
            .background(c.accent.copy(alpha = 0.14f))
            .border(1.dp, c.accent.copy(alpha = 0.55f), shape)
            .clickable(role = Role.Button, onClickLabel = "Add fuel", onClick = onClick)
            .padding(start = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Outlined.Add, contentDescription = null, tint = c.accent, modifier = Modifier.size(20.dp))
        Text(
            "Add Fuel",
            style = CockpitTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = c.accent,
            maxLines = 1,
            softWrap = false
        )
    }
}
