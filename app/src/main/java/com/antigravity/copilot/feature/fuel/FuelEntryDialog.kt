package com.antigravity.copilot.feature.fuel

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.antigravity.copilot.core.designsystem.CockpitTheme
import com.antigravity.copilot.core.designsystem.components.Raahi
import com.antigravity.copilot.core.designsystem.components.RaahiButton
import com.antigravity.copilot.core.designsystem.components.RaahiButtonKind
import com.antigravity.copilot.core.designsystem.components.RaahiDialogHost
import com.antigravity.copilot.core.designsystem.components.TactileKeypadButton
import com.antigravity.copilot.core.designsystem.components.contentColorOn
import com.antigravity.copilot.core.designsystem.components.raahiCard
import com.antigravity.copilot.core.model.FuelState
import com.antigravity.copilot.core.model.TripPoint
import com.antigravity.copilot.core.util.NumberFormatUtils

private const val STATION_LOOKUP_PLACEHOLDER = "Detecting fuel station..."

/** Upper sanity bound for a fuel rate (₹/L); anything above is a keypad typo. */
private const val MAX_PRICE_PER_LITER = 1000.0

/** Gauge level passed with a refuel when the driver did not read the car's gauge. */
const val GAUGE_NOT_OBSERVED = -1

enum class ActiveFuelField {
    AMOUNT,
    RATE,
    ODOMETER
}

/**
 * Log refuel (Raahi). Amount, price per litre and (optionally) the real odometer are typed on the
 * keypad; litres = amount / price. The projected gauge is display-only: no gauge reading is
 * recorded from this dialog (the gauge is calibrated from the home-screen fuel card), so the
 * refuel never feeds the model its own estimate back as an "observation".
 *
 * The odometer field starts with the app's current reading as a hint; it is sent to the fuel
 * system only if the driver actually types a reading.
 */
@Composable
fun FuelEntryDialog(
    fuelState: FuelState,
    recordedLifetimeKm: Double,
    currentLocationPoint: TripPoint?,
    onDismiss: () -> Unit,
    onSaveRefuel: (liters: Double, midBar: Int, pricePerLiter: Double, totalCost: Double, odometerKm: Double, locationName: String, lat: Double, lon: Double, fullTank: Boolean) -> Unit,
    viewModel: FuelEntryViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val c = CockpitTheme.colors

    val currentBaselineOdometer = fuelState.odometerKm.coerceAtLeast(recordedLifetimeKm)

    var refuelOdometerText by remember {
        mutableStateOf(if (currentBaselineOdometer > 0) NumberFormatUtils.formatOdometer(currentBaselineOdometer, 0) else "")
    }
    // True once the driver types into the odometer field: only then is it a real reading.
    var odometerEdited by remember { mutableStateOf(false) }
    var totalAmountText by remember { mutableStateOf("") }
    var pricePerLiterText by remember {
        mutableStateOf(
            if (fuelState.fuelPricePerLiter > 0) NumberFormatUtils.formatPrecision(fuelState.fuelPricePerLiter, 2) else "96.50"
        )
    }
    var locationNameText by remember { mutableStateOf(STATION_LOOKUP_PLACEHOLDER) }
    var isRememberedStationPrice by remember { mutableStateOf(false) }

    // Resolve the fuel station once per dialog (and once more when a GPS fix first arrives).
    val hasLocationFix = currentLocationPoint != null
    LaunchedEffect(hasLocationFix) {
        val lookup = viewModel.lookupStation(
            lat = currentLocationPoint?.latitude ?: 0.0,
            lon = currentLocationPoint?.longitude ?: 0.0
        )
        locationNameText = lookup.name
        lookup.rememberedPricePerLiter?.let { rate ->
            pricePerLiterText = NumberFormatUtils.formatPrecision(rate, 2)
            isRememberedStationPrice = true
        }
    }

    var activeField by remember { mutableStateOf(ActiveFuelField.AMOUNT) }

    // ---- Calculations ----
    // An empty / zero rate is invalid; never silently substitute a default price.
    val pricePerLiter = pricePerLiterText.toDoubleOrNull() ?: 0.0
    val totalAmount = totalAmountText.toDoubleOrNull() ?: 0.0
    val dispensedLiters = if (pricePerLiter > 0.0) totalAmount / pricePerLiter else 0.0

    val designCap = fuelState.designCapacityLiters.coerceAtLeast(30.0)
    // The tank the fuel model really uses (learned usable capacity), the same one the home card's
    // percentage and bars are based on. The design capacity only bounds the keypad sanity check.
    val tankCap = fuelState.actualCapacityLiters.coerceAtLeast(1.0)
    val currentFuelLiters = fuelState.remainingLiters.coerceAtLeast(0.0)
    val predictedFinalTank = currentFuelLiters + dispensedLiters
    val isOverfill = predictedFinalTank > tankCap + 0.05
    val clampedFinalTank = predictedFinalTank.coerceAtMost(maxOf(tankCap, dispensedLiters))
    val effectiveMileage = if (fuelState.currentMileageKmpl > 0.0) fuelState.currentMileageKmpl else 18.0

    // Full-tank flag: defaults on when the refuel is projected to fill the tank; the driver decides.
    val projectedFull = dispensedLiters > 0.0 && predictedFinalTank >= tankCap * 0.98
    var fullTankOverride by remember { mutableStateOf<Boolean?>(null) }
    val isFullTank = fullTankOverride ?: projectedFull

    // Indicator only: current fill from the home gauge, plus this refuel's share of the tank.
    val currentPoints = fuelState.gaugeFillPoints.coerceIn(0.0, 10.0)
    // Bars after the refuel come from the learned gauge curve (same one the home gauge uses), so a
    // litre does not move the bars differently here than on the home screen.
    val postRefillLiters = if (isFullTank) tankCap else clampedFinalTank
    val afterPoints = if (isFullTank) 10.0 else (fuelState.gaugeCurve?.pointFor(postRefillLiters)
        ?: (currentPoints + dispensedLiters / tankCap * 10.0)).coerceIn(currentPoints, 10.0)

    val enteredOdo = refuelOdometerText.toDoubleOrNull() ?: 0.0
    val isOdometerValid = !odometerEdited || refuelOdometerText.isEmpty() || enteredOdo > 0.0
    val odoTripDelta = if (odometerEdited && enteredOdo > currentBaselineOdometer && currentBaselineOdometer > 0) enteredOdo - currentBaselineOdometer else 0.0

    val isPriceValid = pricePerLiter > 0.0 && pricePerLiter <= MAX_PRICE_PER_LITER
    // A dispensed volume the tank can physically hold (5 % slack for the filler neck).
    val exceedsTankCapacity = dispensedLiters > designCap * 1.05
    val validationError = when {
        totalAmount > 0.0 && !isPriceValid -> "Enter a valid fuel rate (₹/L)"
        exceedsTankCapacity ->
            "${NumberFormatUtils.formatPrecision(dispensedLiters, 1)} L is more than the ${designCap.toInt()} L tank. Check the amount and rate."
        !isOdometerValid -> "Enter a valid odometer reading"
        else -> null
    }
    val isFormValid = totalAmount > 0.0 && validationError == null

    fun edit(field: ActiveFuelField, f: (String) -> String) {
        when (field) {
            ActiveFuelField.AMOUNT -> totalAmountText = f(totalAmountText)
            ActiveFuelField.RATE -> pricePerLiterText = f(pricePerLiterText)
            ActiveFuelField.ODOMETER -> {
                // First key replaces the pre-filled hint rather than appending to it.
                val base = if (odometerEdited) refuelOdometerText else ""
                odometerEdited = true
                refuelOdometerText = f(base)
            }
        }
    }

    fun onKeypadPress(key: String) {
        when (key) {
            "⌫" -> edit(activeField) { it.dropLast(1) }
            "C" -> edit(activeField) { "" }
            "." -> edit(activeField) { if (it.contains(".")) it else if (it.isEmpty()) "0." else "$it." }
            else -> edit(activeField) { if (it == "0") key else it + key }
        }
    }

    fun nextField() {
        activeField = when (activeField) {
            ActiveFuelField.AMOUNT -> ActiveFuelField.RATE
            ActiveFuelField.RATE -> ActiveFuelField.ODOMETER
            ActiveFuelField.ODOMETER -> ActiveFuelField.AMOUNT
        }
    }

    val amountColor = c.positive
    val rateColor = c.warning
    val odoColor = c.accent

    RaahiDialogHost(onDismissRequest = onDismiss, maxWidth = 1160.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .raahiCard()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ---- Header: amber pump tile, big title, station chip, close ----
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(Raahi.InnerShape)
                        .background(
                            Brush.linearGradient(listOf(c.warning.copy(alpha = 0.35f), c.critical.copy(alpha = 0.20f)))
                        )
                        .border(1.dp, c.warning.copy(alpha = 0.55f), Raahi.InnerShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.LocalGasStation, contentDescription = null, tint = c.warning, modifier = Modifier.size(30.dp))
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Log Fuel",
                        style = CockpitTheme.typography.titleLarge.copy(fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
                        color = c.textPrimary
                    )
                    val cleanAddress = locationNameText
                        .replace(Regex("(?i)\\b(HN\\s*\\d+|PO)\\b[, ]*"), "")
                        .trim()
                        .ifBlank { "Fuel station" }
                    Row(
                        modifier = Modifier
                            .clip(Raahi.PillShape)
                            .background(c.accent.copy(alpha = 0.12f))
                            .border(1.dp, c.accent.copy(alpha = 0.35f), Raahi.PillShape)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Outlined.Place, contentDescription = null, tint = c.accent, modifier = Modifier.size(18.dp))
                        Text(
                            cleanAddress,
                            style = CockpitTheme.typography.labelLarge,
                            color = c.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(Raahi.MinTouch)
                        .clip(Raahi.PillShape)
                        .background(c.cardElevated)
                        .border(1.dp, c.border, Raahi.PillShape)
                        .clickable(role = Role.Button, onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close", tint = c.textPrimary, modifier = Modifier.size(26.dp))
                }
            }

            Row(modifier = Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // ---- Left: fields, projection, full tank, actions ----
                Column(modifier = Modifier.weight(1.3f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FuelField(
                            label = "Amount paid",
                            icon = Icons.Outlined.Payments,
                            accent = amountColor,
                            prefix = "₹",
                            value = totalAmountText.ifEmpty { "0" },
                            active = activeField == ActiveFuelField.AMOUNT,
                            onClick = { activeField = ActiveFuelField.AMOUNT },
                            modifier = Modifier.weight(1.1f)
                        )
                        FuelField(
                            label = if (isRememberedStationPrice) "Price / L (saved)" else "Price / L",
                            icon = Icons.Outlined.Sell,
                            accent = rateColor,
                            prefix = "₹",
                            value = pricePerLiterText.ifEmpty { "0" },
                            active = activeField == ActiveFuelField.RATE,
                            onClick = { activeField = ActiveFuelField.RATE },
                            modifier = Modifier.weight(0.9f)
                        )
                    }
                    FuelField(
                        label = when {
                            !odometerEdited -> "Odometer (optional, tap to enter)"
                            odoTripDelta > 0.05 -> "Odometer  +${NumberFormatUtils.formatDistance(odoTripDelta, 1)} km"
                            else -> "Odometer"
                        },
                        icon = Icons.Outlined.Speed,
                        accent = odoColor,
                        suffix = "km",
                        value = refuelOdometerText.ifEmpty { "0" },
                        dimValue = !odometerEdited,
                        error = !isOdometerValid,
                        active = activeField == ActiveFuelField.ODOMETER,
                        onClick = { activeField = ActiveFuelField.ODOMETER },
                        modifier = Modifier.fillMaxWidth()
                    )

                    RefuelProjectionCard(
                        currentPoints = currentPoints,
                        afterPoints = afterPoints,
                        currentFuelLiters = currentFuelLiters,
                        dispensedLiters = dispensedLiters,
                        // A full fill sets the tank to capacity in the fuel model; show the same.
                        postRefillLiters = postRefillLiters,
                        designCap = tankCap,
                        effectiveMileage = effectiveMileage,
                        pricePerLiter = pricePerLiter,
                        isOverfill = isOverfill,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Full-to-full intervals are the most reliable mileage source.
                    FullTankToggle(
                        checked = isFullTank,
                        onToggle = { fullTankOverride = !isFullTank }
                    )

                    validationError?.let {
                        Text(it, style = CockpitTheme.typography.bodyLarge, color = c.criticalText)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        RaahiButton(
                            text = "Cancel",
                            onClick = onDismiss,
                            kind = RaahiButtonKind.Secondary,
                            modifier = Modifier.weight(1f).height(60.dp)
                        )
                        SaveFuelButton(
                            enabled = isFormValid,
                            onClick = {
                                if (!isFormValid) {
                                    Toast.makeText(context, validationError ?: "Enter the refuel amount", Toast.LENGTH_SHORT).show()
                                    return@SaveFuelButton
                                }
                                onSaveRefuel(
                                    dispensedLiters,
                                    GAUGE_NOT_OBSERVED,
                                    pricePerLiter,
                                    totalAmount,
                                    if (odometerEdited && enteredOdo > 0.0) enteredOdo else 0.0,
                                    // Never persist the lookup placeholder as a station name.
                                    locationNameText.takeUnless { it == STATION_LOOKUP_PLACEHOLDER }.orEmpty(),
                                    currentLocationPoint?.latitude ?: 0.0,
                                    currentLocationPoint?.longitude ?: 0.0,
                                    isFullTank
                                )
                                onDismiss()
                            },
                            modifier = Modifier.weight(1.6f)
                        )
                    }
                }

                // ---- Right: keypad (tinted by the field being typed) ----
                val keyAccent = when (activeField) {
                    ActiveFuelField.AMOUNT -> amountColor
                    ActiveFuelField.RATE -> rateColor
                    ActiveFuelField.ODOMETER -> odoColor
                }
                Column(
                    modifier = Modifier
                        .weight(0.7f)
                        .fillMaxHeight()
                        .clip(Raahi.CardShape)
                        .background(c.cardElevated)
                        .border(1.dp, keyAccent.copy(alpha = 0.45f), Raahi.CardShape)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val keyStyle = CockpitTheme.typography.displaySmall
                    listOf(
                        listOf("1", "2", "3"),
                        listOf("4", "5", "6"),
                        listOf("7", "8", "9"),
                        listOf(".", "0", "⌫")
                    ).forEach { row ->
                        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { k ->
                                val isBack = k == "⌫"
                                TactileKeypadButton(
                                    text = if (isBack) "" else k,
                                    icon = if (isBack) Icons.AutoMirrored.Outlined.Backspace else null,
                                    accentColor = if (isBack) c.warning else c.textPrimary,
                                    backgroundColor = if (isBack) c.warning.copy(alpha = 0.12f) else c.card,
                                    idleBorderColor = if (isBack) c.warning.copy(alpha = 0.45f) else c.border,
                                    textStyle = keyStyle,
                                    shape = Raahi.InnerShape,
                                    showRipple = true,
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                    onClick = { onKeypadPress(k) }
                                )
                            }
                        }
                    }
                    Row(modifier = Modifier.weight(0.85f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TactileKeypadButton(
                            text = "Clear",
                            accentColor = c.criticalText,
                            backgroundColor = c.critical.copy(alpha = 0.12f),
                            idleBorderColor = c.critical.copy(alpha = 0.45f),
                            textStyle = CockpitTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            shape = Raahi.InnerShape,
                            showRipple = true,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            onClick = { onKeypadPress("C") }
                        )
                        TactileKeypadButton(
                            text = "Next",
                            accentColor = c.accentText,
                            backgroundColor = c.accent.copy(alpha = 0.14f),
                            idleBorderColor = c.accent.copy(alpha = 0.5f),
                            textStyle = CockpitTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            shape = Raahi.InnerShape,
                            showRipple = true,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            onClick = { nextField() }
                        )
                    }
                }
            }
        }
    }
}

/** Keypad-driven field: coloured icon + label on top, big value; active = 2 dp border in its colour. */
@Composable
private fun FuelField(
    label: String,
    value: String,
    active: Boolean,
    onClick: () -> Unit,
    accent: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    prefix: String? = null,
    suffix: String? = null,
    dimValue: Boolean = false,
    error: Boolean = false
) {
    val c = CockpitTheme.colors
    val edge = if (error) c.critical else accent
    Column(
        modifier = modifier
            .heightIn(min = 82.dp)
            .clip(Raahi.InnerShape)
            .background(edge.copy(alpha = if (active) 0.16f else 0.07f))
            .border(if (active) 2.dp else 1.dp, if (active || error) edge else edge.copy(alpha = 0.35f), Raahi.InnerShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = edge, modifier = Modifier.size(18.dp))
            Text(
                label,
                style = CockpitTheme.typography.labelLarge,
                color = if (error) c.criticalText else c.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(verticalAlignment = Alignment.Bottom) {
            if (prefix != null) {
                Text(prefix, style = CockpitTheme.typography.headlineSmall, color = edge, modifier = Modifier.padding(end = 4.dp, bottom = 3.dp))
            }
            Text(
                value,
                style = CockpitTheme.typography.displaySmall,
                color = if (dimValue) c.textSecondary else c.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (suffix != null) {
                Text(suffix, style = CockpitTheme.typography.titleMedium, color = edge, modifier = Modifier.padding(start = 6.dp, bottom = 4.dp))
            }
        }
    }
}

/**
 * "Full tank fill" toggle (was "Filled to full"): a tappable card with a switch. Green when on.
 * Full-to-full intervals give the most accurate mileage, so the subtitle says why it matters.
 */
@Composable
private fun FullTankToggle(checked: Boolean, onToggle: () -> Unit) {
    val c = CockpitTheme.colors
    val tint = if (checked) c.positive else c.textSecondary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .clip(Raahi.InnerShape)
            .background(if (checked) c.positive.copy(alpha = 0.14f) else c.cardElevated)
            .border(if (checked) 2.dp else 1.dp, if (checked) c.positive else c.border, Raahi.InnerShape)
            .clickable(role = Role.Switch, onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(Raahi.InnerShape)
                .background(tint.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (checked) Icons.Outlined.TaskAlt else Icons.Outlined.LocalGasStation,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(24.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Full tank fill",
                style = CockpitTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = c.textPrimary
            )
            Text(
                "Pump stopped on its own: best for accurate mileage",
                style = CockpitTheme.typography.bodyMedium,
                color = c.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = c.positive,
                uncheckedThumbColor = c.textSecondary,
                uncheckedTrackColor = c.card,
                uncheckedBorderColor = c.border
            )
        )
    }
}

/** Big green "Save refuel" action. */
@Composable
private fun SaveFuelButton(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = CockpitTheme.colors
    val bg = if (enabled) c.positive else c.cardElevated
    val fg = if (enabled) contentColorOn(c.positive) else c.textDisabled
    Row(
        modifier = modifier
            .height(60.dp)
            .clip(Raahi.InnerShape)
            .background(bg)
            .border(1.dp, if (enabled) c.positive else c.border, Raahi.InnerShape)
            .clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Check, contentDescription = null, tint = fg, modifier = Modifier.size(26.dp))
        Text("Save refuel", style = CockpitTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = fg)
    }
}
