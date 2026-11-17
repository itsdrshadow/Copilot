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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    val currentFuelLiters = fuelState.remainingLiters.coerceAtLeast(0.0)
    val predictedFinalTank = currentFuelLiters + dispensedLiters
    val isOverfill = predictedFinalTank > designCap
    val clampedFinalTank = predictedFinalTank.coerceAtMost(designCap)
    val effectiveMileage = if (fuelState.currentMileageKmpl > 0.0) fuelState.currentMileageKmpl else 18.0

    // Full-tank flag: defaults on when the refuel is projected to fill the tank; the driver decides.
    val projectedFull = dispensedLiters > 0.0 && predictedFinalTank >= designCap * 0.98
    var fullTankOverride by remember { mutableStateOf<Boolean?>(null) }
    val isFullTank = fullTankOverride ?: projectedFull

    // Indicator only: current fill from the home gauge, plus this refuel's share of the tank.
    val currentPoints = fuelState.gaugeFillPoints.coerceIn(0.0, 10.0)
    val afterPoints = if (isFullTank) 10.0 else (currentPoints + dispensedLiters / designCap * 10.0).coerceIn(0.0, 10.0)

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

    RaahiDialogHost(onDismissRequest = onDismiss, maxWidth = 1120.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .raahiCard()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ---- Header ----
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier.size(48.dp).clip(Raahi.InnerShape).background(c.cardElevated),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.LocalGasStation, contentDescription = null, tint = c.warning, modifier = Modifier.size(24.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Log refuel", style = CockpitTheme.typography.titleLarge, color = c.textPrimary)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Outlined.Place, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(16.dp))
                        val cleanAddress = locationNameText
                            .replace(Regex("(?i)\\b(HN\\s*\\d+|PO)\\b[, ]*"), "")
                            .trim()
                            .ifBlank { "Fuel station" }
                        Text(
                            cleanAddress,
                            style = CockpitTheme.typography.bodyMedium,
                            color = c.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(Raahi.PillShape)
                        .background(c.cardElevated)
                        .border(1.dp, c.border, Raahi.PillShape)
                        .clickable(role = Role.Button, onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close", tint = c.textPrimary, modifier = Modifier.size(22.dp))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // ---- Left: fields, projection, full tank, actions ----
                Column(modifier = Modifier.weight(1.25f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FuelField(
                            label = "Amount",
                            prefix = "₹",
                            value = totalAmountText.ifEmpty { "0" },
                            active = activeField == ActiveFuelField.AMOUNT,
                            onClick = { activeField = ActiveFuelField.AMOUNT },
                            modifier = Modifier.weight(1.1f)
                        )
                        FuelField(
                            label = if (isRememberedStationPrice) "Price / L (saved)" else "Price / L",
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
                        postRefillLiters = if (isFullTank) designCap else clampedFinalTank,
                        designCap = designCap,
                        effectiveMileage = effectiveMileage,
                        pricePerLiter = pricePerLiter,
                        isOverfill = isOverfill,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Full-to-full intervals are the most reliable mileage source.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Raahi.MinTouch)
                            .clip(Raahi.InnerShape)
                            .clickable(role = Role.Checkbox) { fullTankOverride = !isFullTank },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isFullTank,
                            onCheckedChange = { fullTankOverride = it },
                            colors = CheckboxDefaults.colors(checkedColor = c.accent, checkmarkColor = c.onAccent, uncheckedColor = c.textSecondary)
                        )
                        Text(
                            "Filled to full (nozzle clicked off)",
                            style = CockpitTheme.typography.bodyLarge,
                            color = c.textPrimary
                        )
                    }

                    validationError?.let {
                        Text(it, style = CockpitTheme.typography.bodyMedium, color = c.criticalText)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        RaahiButton(
                            text = "Cancel",
                            onClick = onDismiss,
                            kind = RaahiButtonKind.Secondary,
                            modifier = Modifier.weight(1f)
                        )
                        RaahiButton(
                            text = "Save refuel",
                            enabled = isFormValid,
                            onClick = {
                                if (!isFormValid) {
                                    Toast.makeText(context, validationError ?: "Enter the refuel amount", Toast.LENGTH_SHORT).show()
                                    return@RaahiButton
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

                // ---- Right: keypad ----
                Column(
                    modifier = Modifier
                        .weight(0.75f)
                        .raahiCard(raised = true)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val keyStyle = CockpitTheme.typography.headlineLarge
                    listOf(
                        listOf("1", "2", "3"),
                        listOf("4", "5", "6"),
                        listOf("7", "8", "9"),
                        listOf(".", "0", "⌫")
                    ).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { k ->
                                val isBack = k == "⌫"
                                TactileKeypadButton(
                                    text = if (isBack) "" else k,
                                    icon = if (isBack) Icons.AutoMirrored.Outlined.Backspace else null,
                                    accentColor = c.textPrimary,
                                    textStyle = keyStyle,
                                    shape = Raahi.InnerShape,
                                    showRipple = true,
                                    modifier = Modifier.weight(1f).height(60.dp),
                                    onClick = { onKeypadPress(k) }
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TactileKeypadButton(
                            text = "Clear",
                            accentColor = c.textPrimary,
                            textStyle = CockpitTheme.typography.labelLarge,
                            shape = Raahi.InnerShape,
                            showRipple = true,
                            modifier = Modifier.weight(1f).height(56.dp),
                            onClick = { onKeypadPress("C") }
                        )
                        TactileKeypadButton(
                            text = "Next",
                            accentColor = c.textPrimary,
                            textStyle = CockpitTheme.typography.labelLarge,
                            shape = Raahi.InnerShape,
                            showRipple = true,
                            modifier = Modifier.weight(1f).height(56.dp),
                            onClick = { nextField() }
                        )
                    }
                }
            }
        }
    }
}

/** Keypad-driven field: label on top, value large; active = cyan border. */
@Composable
private fun FuelField(
    label: String,
    value: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    prefix: String? = null,
    suffix: String? = null,
    dimValue: Boolean = false,
    error: Boolean = false
) {
    val c = CockpitTheme.colors
    Column(
        modifier = modifier
            .heightIn(min = 64.dp)
            .clip(Raahi.InnerShape)
            .background(c.cardElevated)
            .border(if (active) 2.dp else 1.dp, if (error) c.critical else if (active) c.accent else c.border, Raahi.InnerShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            style = CockpitTheme.typography.labelMedium,
            color = if (error) c.criticalText else c.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(verticalAlignment = Alignment.Bottom) {
            if (prefix != null) {
                Text(prefix, style = CockpitTheme.typography.titleMedium, color = c.textSecondary, modifier = Modifier.padding(end = 4.dp, bottom = 2.dp))
            }
            Text(
                value,
                style = CockpitTheme.typography.headlineLarge,
                color = if (dimValue) c.textSecondary else c.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (suffix != null) {
                Text(suffix, style = CockpitTheme.typography.titleMedium, color = c.textSecondary, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
            }
        }
    }
}
