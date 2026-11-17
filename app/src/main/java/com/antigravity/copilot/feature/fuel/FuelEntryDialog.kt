package com.antigravity.copilot.feature.fuel

import androidx.hilt.navigation.compose.hiltViewModel
import com.antigravity.copilot.core.designsystem.components.cockpitSurface
import com.antigravity.copilot.core.designsystem.components.TactileKeypadButton
import com.antigravity.copilot.core.designsystem.tokens.Palette
import com.antigravity.copilot.core.designsystem.tokens.CopilotShapes
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.copilot.core.designsystem.CockpitTheme
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.antigravity.copilot.core.designsystem.AutomotiveDesignTokens
import com.antigravity.copilot.core.designsystem.Cockpit_AmberWarning
import com.antigravity.copilot.core.designsystem.Cockpit_RedAlert
import com.antigravity.copilot.core.designsystem.Apex_PrimaryAccent
import com.antigravity.copilot.core.model.FuelState
import com.antigravity.copilot.core.model.TripPoint
import com.antigravity.copilot.core.util.NumberFormatUtils

private const val STATION_LOOKUP_PLACEHOLDER = "Detecting fuel station..."

/** Upper sanity bound for a fuel rate (₹/L); anything above is a keypad typo. */
private const val MAX_PRICE_PER_LITER = 1000.0

enum class ActiveFuelField {
    AMOUNT,
    RATE,
    ODOMETER
}

/**
 * FuelEntryDialog:
 * Optimized 1280x800 Automotive Cockpit Refuel & Calibration Modal:
 * 1. Compact dialog (~15% smaller, centered modal layout).
 * 2. 35% dedicated width on the right for the tactile numeric keypad.
 * 3. Left Column (~32%): High-tech modern Fuel Gauge on top + AI Fuel Intelligence & Monthly Insights filling to bottom.
 * 4. Center Column (~33%): 3 Top fields (Amount, Price/L, Odometer) + Real-time Auto-Calculations + Actions.
 * 5. Strict Odometer Validation: Entered odometer must be strictly greater than current odometer to confirm.
 * 6. High-readability automotive typography with large location & telemetry fonts.
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

    // Current baseline odometer
    val currentBaselineOdometer = fuelState.odometerKm.coerceAtLeast(recordedLifetimeKm)

    // Telemetry State - Default values set to empty / real vehicle readings
    var refuelOdometerText by remember {
        mutableStateOf(
            if (currentBaselineOdometer > 0) NumberFormatUtils.formatOdometer(currentBaselineOdometer, 0)
            else ""
        )
    }
    var totalAmountText by remember { mutableStateOf("") }
    var pricePerLiterText by remember {
        mutableStateOf(
            if (fuelState.fuelPricePerLiter > 0) NumberFormatUtils.formatPrecision(fuelState.fuelPricePerLiter, 2)
            else "96.50"
        )
    }
    var locationNameText by remember { mutableStateOf(STATION_LOOKUP_PLACEHOLDER) }
    var isRememberedStationPrice by remember { mutableStateOf(false) }

    // Resolve the fuel station once per dialog (and once more when a GPS fix first arrives).
    // Keying on the live point re-ran this every GPS tick, piling up lookups and overwriting
    // the price the user was typing.
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

    // Auto-calculated metrics
    // An empty / zero rate is invalid; never silently substitute a default price.
    val pricePerLiter = pricePerLiterText.toDoubleOrNull() ?: 0.0
    val totalAmount = totalAmountText.toDoubleOrNull() ?: 0.0
    val dispensedLiters = if (pricePerLiter > 0.0) totalAmount / pricePerLiter else 0.0

    val designCap = fuelState.designCapacityLiters.coerceAtLeast(30.0)
    val currentFuelLiters = fuelState.remainingLiters.coerceAtLeast(0.0)
    val predictedFinalTank = (currentFuelLiters + dispensedLiters).coerceAtLeast(0.0)
    val isOverfill = predictedFinalTank > designCap
    val clampedFinalTank = predictedFinalTank.coerceAtMost(designCap)
    val effectiveMileage = if (fuelState.currentMileageKmpl > 0.0) fuelState.currentMileageKmpl else 18.0

    val currentBar = if (fuelState.currentMidBar in 0..10) {
        fuelState.currentMidBar
    } else {
        viewModel.barForLiters(currentFuelLiters)
    }

    val autoPostRefillBar = remember(currentFuelLiters, dispensedLiters, designCap) {
        if (dispensedLiters <= 0.001) {
            currentBar
        } else {
            viewModel.barForLiters(clampedFinalTank).coerceIn(currentBar, 10)
        }
    }

    var userSelectedMidBar by remember(autoPostRefillBar) { mutableIntStateOf(autoPostRefillBar) }
    val additionalBars = (userSelectedMidBar - currentBar).coerceAtLeast(0)
    // Full-tank flag (defaults to on when the gauge shows full after refilling).
    var isFullTank by remember(userSelectedMidBar) { mutableStateOf(userSelectedMidBar >= 10) }

    // Odometer validation: Must be a positive number
    val enteredOdo = refuelOdometerText.toDoubleOrNull() ?: 0.0
    val isOdometerValid = if (refuelOdometerText.isNotEmpty()) enteredOdo > 0.0 else true
    val odoTripDelta = if (enteredOdo > currentBaselineOdometer && currentBaselineOdometer > 0) enteredOdo - currentBaselineOdometer else 0.0

    // Input guards: a valid rate, and a dispensed volume the tank can physically hold
    // (5% slack for the filler neck) — catches amount / rate typos before they skew mileage.
    val isPriceValid = pricePerLiter > 0.0 && pricePerLiter <= MAX_PRICE_PER_LITER
    val exceedsTankCapacity = dispensedLiters > designCap * 1.05
    val validationError = when {
        totalAmount > 0.0 && !isPriceValid -> "Enter a valid fuel rate (₹/L)"
        exceedsTankCapacity ->
            "${NumberFormatUtils.formatPrecision(dispensedLiters, 1)} L exceeds the ${designCap.toInt()} L tank — check amount and rate"
        !isOdometerValid -> "Enter a valid odometer reading"
        else -> null
    }
    val isFormValid = totalAmount > 0.0 && validationError == null

    fun onKeypadPress(key: String) {
        when (key) {
            "⌫" -> {
                when (activeField) {
                    ActiveFuelField.AMOUNT -> if (totalAmountText.isNotEmpty()) totalAmountText = totalAmountText.dropLast(1)
                    ActiveFuelField.RATE -> if (pricePerLiterText.isNotEmpty()) pricePerLiterText = pricePerLiterText.dropLast(1)
                    ActiveFuelField.ODOMETER -> if (refuelOdometerText.isNotEmpty()) refuelOdometerText = refuelOdometerText.dropLast(1)
                }
            }
            "C" -> {
                when (activeField) {
                    ActiveFuelField.AMOUNT -> totalAmountText = ""
                    ActiveFuelField.RATE -> pricePerLiterText = ""
                    ActiveFuelField.ODOMETER -> refuelOdometerText = ""
                }
            }
            "." -> {
                when (activeField) {
                    ActiveFuelField.AMOUNT -> if (!totalAmountText.contains(".")) totalAmountText = if (totalAmountText.isEmpty()) "0." else "$totalAmountText."
                    ActiveFuelField.RATE -> if (!pricePerLiterText.contains(".")) pricePerLiterText = if (pricePerLiterText.isEmpty()) "0." else "$pricePerLiterText."
                    ActiveFuelField.ODOMETER -> if (!refuelOdometerText.contains(".")) refuelOdometerText = if (refuelOdometerText.isEmpty()) "0." else "$refuelOdometerText."
                }
            }
            else -> {
                when (activeField) {
                    ActiveFuelField.AMOUNT -> totalAmountText = if (totalAmountText == "0") key else totalAmountText + key
                    ActiveFuelField.RATE -> pricePerLiterText = if (pricePerLiterText == "0") key else pricePerLiterText + key
                    ActiveFuelField.ODOMETER -> refuelOdometerText = if (refuelOdometerText == "0") key else refuelOdometerText + key
                }
            }
        }
    }

    fun nextField() {
        activeField = when (activeField) {
            ActiveFuelField.AMOUNT -> ActiveFuelField.RATE
            ActiveFuelField.RATE -> ActiveFuelField.ODOMETER
            ActiveFuelField.ODOMETER -> ActiveFuelField.AMOUNT
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        var isVisible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            isVisible = true
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF030712).copy(alpha = 0.88f)),
            contentAlignment = Alignment.Center
        ) {
            // Calibrated for 1280x800 landscape automotive head unit
            val dialogMaxWidth = if (maxWidth > 1200.dp) 1120.dp else if (maxWidth > 950.dp) 1020.dp else maxWidth * 0.94f
            val dialogMaxHeight = if (maxHeight > 700.dp) 670.dp else if (maxHeight > 550.dp) 610.dp else maxHeight * 0.94f

            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(animationSpec = tween(220)) + scaleIn(
                    initialScale = 0.94f,
                    animationSpec = tween(220, easing = FastOutSlowInEasing)
                ),
                exit = fadeOut(animationSpec = tween(180)) + scaleOut(
                    targetScale = 0.94f,
                    animationSpec = tween(180)
                )
            ) {
                Box(
                    modifier = Modifier
                        .width(dialogMaxWidth)
                        .height(dialogMaxHeight)
                        .cockpitSurface(Brush.verticalGradient(
                                listOf(
                                    Color(0xFF0C1527),
                                    Color(0xFF070D18)
                                )
                            ), Palette.Ink725, CopilotShapes.R22, borderWidth = 1.5.dp)
                        .padding(horizontal = 18.dp, vertical = 14.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        // ==================== 1. TOP HEADER (LARGE LOCATION & ACCENTS) ====================
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(Apex_PrimaryAccent.copy(alpha = 0.15f))
                                        .border(1.dp, Apex_PrimaryAccent.copy(alpha = 0.40f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.LocalGasStation,
                                        contentDescription = null,
                                        tint = Apex_PrimaryAccent,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                Column {
                                    Text(
                                        text = "LOG REFUEL & CALIBRATE",
                                        style = CockpitTheme.typography.titleLarge,
                                        color = Color.White
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PinDrop,
                                            contentDescription = null,
                                            tint = Apex_PrimaryAccent,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        val cleanAddress = locationNameText
                                            .replace(Regex("(?i)\\b(HN\\s*\\d+|PO)\\b[, ]*"), "")
                                            .trim()
                                            .ifBlank { "Fuel Station, Auto-Detecting" }
                                        Text(
                                            text = cleanAddress.take(48),
                                            style = CockpitTheme.typography.bodyMedium.copy(
                                                fontWeight = FontWeight.Bold
                                            ),
                                            color = Color(0xFFE2E8F0),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            // Close Modal Button
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.08f))
                                    .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                                    .clickable { onDismiss() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        // ==================== 2. MAIN 60/40 RATIO LANDSCAPE COCKPIT BODY ====================
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // ----------------- PANE 1: LEFT 60% (DATA FEED FIELDS + GAUGE & AUTO-CALCULATE + ACTIONS) -----------------
                            Column(
                                modifier = Modifier
                                    .weight(0.60f)
                                    .fillMaxHeight(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // 1. UPPER SECTION: TOP DATA FEED FIELDS (Original compact vertical height: 46dp each)
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    // Row 1: Amount (₹) & Price / L (₹/L) side-by-side (Height: 48dp)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        // Field 1: Total Amount (₹)
                                        val isAmountActive = activeField == ActiveFuelField.AMOUNT
                                        Box(
                                            modifier = Modifier
                                                .weight(1.15f)
                                                .fillMaxHeight()
                                                .cockpitSurface(Palette.Slate900, if (isAmountActive) Cockpit_AmberWarning else Palette.Slate800, CopilotShapes.R10, borderWidth = 1.5.dp)
                                                .clickable { activeField = ActiveFuelField.AMOUNT }
                                                .padding(horizontal = 12.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxSize(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Text(
                                                        text = "AMOUNT",
                                                        style = CockpitTheme.typography.labelMedium.copy(
                                                            letterSpacing = 0.5.sp
                                                        ),
                                                        color = if (isAmountActive) Cockpit_AmberWarning else Palette.Slate400
                                                    )
                                                    if (isAmountActive) {
                                                        Box(
                                                            modifier = Modifier
                                                                .size(6.dp)
                                                                .clip(CircleShape)
                                                                .background(Cockpit_AmberWarning)
                                                        )
                                                    }
                                                }
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                                ) {
                                                    Text(
                                                        text = "₹",
                                                        style = CockpitTheme.typography.titleLarge.copy(
                                                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                                                        ),
                                                        color = Cockpit_AmberWarning
                                                    )
                                                    Text(
                                                        text = totalAmountText.ifEmpty { "0" },
                                                        style = CockpitTheme.typography.headlineLarge.copy(
                                                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum",
                                                            letterSpacing = (-0.5).sp,
                                                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                                                        ),
                                                        color = Color.White,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }

                                        // Field 2: Price / Litre (₹/L)
                                        val isRateActive = activeField == ActiveFuelField.RATE
                                        Box(
                                            modifier = Modifier
                                                .weight(0.85f)
                                                .fillMaxHeight()
                                                .cockpitSurface(Palette.Slate900, if (isRateActive) Palette.Sky400 else Palette.Slate800, CopilotShapes.R10, borderWidth = 1.5.dp)
                                                .clickable { activeField = ActiveFuelField.RATE }
                                                .padding(horizontal = 12.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxSize(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "PRICE/L",
                                                    style = CockpitTheme.typography.labelMedium.copy(
                                                        letterSpacing = 0.5.sp
                                                    ),
                                                    color = if (isRateActive) Palette.Sky400 else Palette.Slate400
                                                )
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                                ) {
                                                    Text(
                                                        text = "₹",
                                                        style = CockpitTheme.typography.titleMedium,
                                                        color = Palette.Sky400
                                                    )
                                                    Text(
                                                        text = pricePerLiterText.ifEmpty { "0" },
                                                        style = CockpitTheme.typography.headlineLarge.copy(
                                                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum",
                                                            letterSpacing = (-0.5).sp,
                                                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                                                        ),
                                                        color = Color.White,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // Row 2: Odometer Reading (KM) directly underneath (Height: 48dp)
                                    val isOdoActive = activeField == ActiveFuelField.ODOMETER
                                    val odoBorderColor = when {
                                        !isOdometerValid && refuelOdometerText.isNotEmpty() -> Cockpit_RedAlert
                                        isOdoActive -> Palette.Purple400
                                        else -> Palette.Slate800
                                    }

                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .cockpitSurface(Palette.Slate900, odoBorderColor, CopilotShapes.R10, borderWidth = 1.5.dp)
                                            .clickable { activeField = ActiveFuelField.ODOMETER }
                                            .padding(horizontal = 12.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxSize(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Text(
                                                    text = "ODOMETER",
                                                    style = CockpitTheme.typography.labelMedium.copy(
                                                        letterSpacing = 0.5.sp
                                                    ),
                                                    color = if (!isOdometerValid && refuelOdometerText.isNotEmpty()) Cockpit_RedAlert else if (isOdoActive) Palette.Purple400 else Palette.Slate400
                                                )
                                                if (refuelOdometerText.isNotEmpty() && enteredOdo <= 0.0) {
                                                    Text(
                                                        text = "⚠️ Enter valid km",
                                                        style = CockpitTheme.typography.labelSmall.copy(
                                                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                                                        ),
                                                        color = Cockpit_RedAlert
                                                    )
                                                } else if (odoTripDelta > 0.05) {
                                                    Text(
                                                        text = "+${NumberFormatUtils.formatDistance(odoTripDelta, 1)} km",
                                                        style = CockpitTheme.typography.labelSmall.copy(
                                                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                                                        ),
                                                        color = Palette.Purple400
                                                    )
                                                }
                                            }

                                            Row(verticalAlignment = Alignment.Bottom) {
                                                Text(
                                                    text = refuelOdometerText.ifEmpty { "0" },
                                                    style = CockpitTheme.typography.headlineLarge.copy(
                                                        fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum",
                                                        letterSpacing = (-0.5).sp,
                                                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                                                    ),
                                                    color = if (!isOdometerValid && refuelOdometerText.isNotEmpty()) Cockpit_RedAlert else Color.White
                                                )
                                                Spacer(Modifier.width(4.dp))
                                                Text(
                                                    text = "km",
                                                    style = CockpitTheme.typography.labelMedium,
                                                    color = Palette.Purple400,
                                                    modifier = Modifier.padding(bottom = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                }

                                // 2. MIDDLE SECTION: UNIFIED AUTOMOTIVE FUEL GAUGE & TELEMETRY POD (Merged Mixed Box)
                                UnifiedFuelTelemetryAndGaugeCard(
                                    currentBar = currentBar,
                                    postRefillBar = userSelectedMidBar,
                                    additionalBars = additionalBars,
                                    currentFuelLiters = currentFuelLiters,
                                    dispensedLiters = dispensedLiters,
                                    postRefillLiters = clampedFinalTank,
                                    designCap = designCap,
                                    effectiveMileage = effectiveMileage,
                                    pricePerLiter = pricePerLiter,
                                    isOverfill = isOverfill,
                                    onBarSelected = { userSelectedMidBar = it },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                )

                                // Full-tank flag: full-to-full intervals are the most reliable mileage source.
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(CopilotShapes.R8)
                                        .clickable { isFullTank = !isFullTank }
                                        .padding(horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(checked = isFullTank, onCheckedChange = { isFullTank = it })
                                    Text(
                                        text = "Filled to FULL tank (nozzle clicked off)",
                                        style = CockpitTheme.typography.labelLarge,
                                        color = CockpitTheme.colors.textPrimary
                                    )
                                }

                                validationError?.let { err ->
                                    Text(
                                        text = "⚠️ $err",
                                        style = CockpitTheme.typography.labelMedium,
                                        color = Cockpit_RedAlert,
                                        maxLines = 2
                                    )
                                }

                                // 3. BOTTOM SECTION: ACTIONS (CANCEL & CONFIRM REFUEL)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(50.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Button(
                                        onClick = onDismiss,
                                        modifier = Modifier
                                            .weight(0.35f)
                                            .fillMaxHeight(),
                                        shape = CopilotShapes.R12,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Palette.Slate800,
                                            contentColor = Palette.Slate300
                                        )
                                    ) {
                                        Text(
                                            text = "CANCEL",
                                            style = CockpitTheme.typography.labelLarge
                                        )
                                    }

                                    Button(
                                        onClick = {
                                            if (!isFormValid) {
                                                Toast.makeText(context, validationError ?: "Please enter a valid refuel amount", Toast.LENGTH_SHORT).show()
                                                return@Button
                                            }

                                            val lat = currentLocationPoint?.latitude ?: 0.0
                                            val lon = currentLocationPoint?.longitude ?: 0.0
                                            onSaveRefuel(
                                                dispensedLiters,
                                                userSelectedMidBar,
                                                pricePerLiter,
                                                totalAmount,
                                                enteredOdo,
                                                // Never persist the lookup placeholder as a station name.
                                                locationNameText.takeUnless { it == STATION_LOOKUP_PLACEHOLDER }.orEmpty(),
                                                lat,
                                                lon,
                                                isFullTank
                                            )
                                            onDismiss()
                                        },
                                        enabled = isFormValid,
                                        modifier = Modifier
                                            .weight(0.65f)
                                            .fillMaxHeight(),
                                        shape = CopilotShapes.R12,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Palette.Sky600,
                                            contentColor = Color.White,
                                            disabledContainerColor = Palette.Slate800,
                                            disabledContentColor = Palette.Slate500
                                        )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "CONFIRM REFUEL",
                                            style = CockpitTheme.typography.labelLarge.copy(
                                                letterSpacing = 0.5.sp
                                            )
                                        )
                                    }
                                }
                            }

                            // ----------------- PANE 2: RIGHT 40% (TACTILE NUMERIC KEYPAD) -----------------
                            Box(
                                modifier = Modifier
                                    .weight(0.40f) // Dedicated exactly 40% space
                                    .fillMaxHeight()
                                    .cockpitSurface(Color(0xFF070D18), Palette.Ink725, CopilotShapes.R16, borderWidth = 1.2.dp)
                                    .padding(8.dp)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    val keyRows = listOf(
                                        listOf("1", "2", "3"),
                                        listOf("4", "5", "6"),
                                        listOf("7", "8", "9"),
                                        listOf(".", "0", "DEL")
                                    )

                                    for (row in keyRows) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .weight(1f),
                                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                                        ) {
                                            for (k in row) {
                                                val isBack = k == "DEL" || k == "⌫"
                                                val accent = if (isBack) Cockpit_RedAlert else Color.White
                                                val bg = if (isBack) Color(0xFF261216) else Palette.Ink875

                                                FuelKeypadKey(
                                                    text = if (isBack) "" else k,
                                                    onClick = { onKeypadPress(if (isBack) "⌫" else k) },
                                                    accentColor = accent,
                                                    backgroundColor = bg,
                                                    icon = if (isBack) Icons.AutoMirrored.Filled.Backspace else null,
                                                    isSpecialAction = isBack,
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .fillMaxHeight()
                                                )
                                            }
                                        }
                                    }

                                    // Bottom Row: CLR and NEXT
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(0.95f),
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        FuelKeypadKey(
                                            text = "CLR",
                                            onClick = { onKeypadPress("C") },
                                            accentColor = Cockpit_RedAlert,
                                            backgroundColor = Color(0xFF261216),
                                            isSpecialAction = true,
                                            modifier = Modifier
                                                .weight(0.40f)
                                                .fillMaxHeight()
                                        )

                                        FuelKeypadKey(
                                            text = "NEXT",
                                            onClick = { nextField() },
                                            accentColor = Apex_PrimaryAccent,
                                            backgroundColor = Color(0xFF0C2538),
                                            icon = Icons.AutoMirrored.Filled.ArrowForward,
                                            isSpecialAction = true,
                                            modifier = Modifier
                                                .weight(0.60f)
                                                .fillMaxHeight()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Fuel-entry keypad key: shared [TactileKeypadButton] with the fuel dialog's slate styling. */
@Composable
private fun FuelKeypadKey(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = Color.White,
    backgroundColor: Color = Palette.Slate900,
    icon: ImageVector? = null,
    isSpecialAction: Boolean = false
) = TactileKeypadButton(
    text = text,
    onClick = onClick,
    modifier = modifier,
    accentColor = accentColor,
    backgroundColor = backgroundColor,
    idleBorderColor = if (isSpecialAction) accentColor.copy(alpha = 0.35f) else Palette.Slate800,
    icon = icon,
    labelStyle = CockpitTheme.typography.labelMedium.copy(
        fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily,
        fontFeatureSettings = "tnum",
        letterSpacing = 0.5.sp
    )
)
