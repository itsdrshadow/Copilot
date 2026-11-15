package com.antigravity.copilot.feature.fuel

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.antigravity.copilot.core.designsystem.AutomotiveDesignTokens
import com.antigravity.copilot.core.designsystem.Cockpit_AmberWarning
import com.antigravity.copilot.core.designsystem.Cockpit_GreenEco
import com.antigravity.copilot.core.designsystem.Cockpit_RedAlert
import com.antigravity.copilot.core.designsystem.Premium_PrimaryAccent
import com.antigravity.copilot.core.fuel.StationPriceManager
import com.antigravity.copilot.core.location.LocationNameResolver
import com.antigravity.copilot.core.model.FuelState
import com.antigravity.copilot.core.model.TripPoint
import com.antigravity.copilot.core.util.NumberFormatUtils
import kotlinx.coroutines.launch

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
    onSaveRefuel: (liters: Double, midBar: Int, pricePerLiter: Double, totalCost: Double, odometerKm: Double, locationName: String, lat: Double, lon: Double) -> Unit,
    @Suppress("UNUSED_PARAMETER") onCalibrateMidBar: (midBar: Int) -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onCalibrateOdometer: (odometerKm: Double) -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

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
    var locationNameText by remember { mutableStateOf("Detecting fuel station...") }
    var isRememberedStationPrice by remember { mutableStateOf(false) }

    // Auto-resolve fuel station name and pre-fill remembered price from GPS location
    LaunchedEffect(currentLocationPoint) {
        val lat = currentLocationPoint?.latitude ?: 0.0
        val lon = currentLocationPoint?.longitude ?: 0.0
        coroutineScope.launch {
            val resolved = LocationNameResolver.resolveStationOrAddress(context, lat, lon)
            locationNameText = resolved

            val rememberedRate = StationPriceManager.getRememberedPrice(context, lat, lon, resolved)
            if (rememberedRate != null && rememberedRate > 0.0) {
                pricePerLiterText = NumberFormatUtils.formatPrecision(rememberedRate, 2)
                isRememberedStationPrice = true
            }
        }
    }

    var activeField by remember { mutableStateOf(ActiveFuelField.AMOUNT) }

    // Auto-calculated metrics
    val pricePerLiter = pricePerLiterText.toDoubleOrNull() ?: 96.50
    val totalAmount = totalAmountText.toDoubleOrNull() ?: 0.0
    val dispensedLiters = if (pricePerLiter > 0.0) totalAmount / pricePerLiter else 0.0

    val designCap = fuelState.designCapacityLiters.coerceAtLeast(30.0)
    val currentFuelLiters = fuelState.remainingLiters.coerceAtLeast(0.0)
    val predictedFinalTank = (currentFuelLiters + dispensedLiters).coerceAtLeast(0.0)
    val isOverfill = predictedFinalTank > designCap
    val clampedFinalTank = predictedFinalTank.coerceAtMost(designCap)
    val effectiveMileage = if (fuelState.currentMileageKmpl > 0.0) fuelState.currentMileageKmpl else 18.0

    val fuelRepo = remember { com.antigravity.copilot.core.fuel.FuelRepository.getInstance(context) }
    val gaugeCalibration = fuelRepo.gaugeCalibration
    val currentBar = if (fuelState.currentMidBar in 0..10) {
        fuelState.currentMidBar
    } else {
        gaugeCalibration.getBarForLiters(currentFuelLiters).coerceIn(0, 10)
    }

    val autoPostRefillBar = remember(currentFuelLiters, dispensedLiters, designCap) {
        if (dispensedLiters <= 0.001) {
            currentBar
        } else {
            gaugeCalibration.getBarForLiters(clampedFinalTank).coerceIn(currentBar, 10)
        }
    }

    var userSelectedMidBar by remember(autoPostRefillBar) { mutableIntStateOf(autoPostRefillBar) }
    val additionalBars = (userSelectedMidBar - currentBar).coerceAtLeast(0)

    // Odometer validation: Must be a positive number
    val enteredOdo = refuelOdometerText.toDoubleOrNull() ?: 0.0
    val isOdometerValid = if (refuelOdometerText.isNotEmpty()) enteredOdo > 0.0 else true
    val odoTripDelta = if (enteredOdo > currentBaselineOdometer && currentBaselineOdometer > 0) enteredOdo - currentBaselineOdometer else 0.0

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
            // Sized ~15% smaller than the screen for an elegant, focused centered cockpit modal
            val dialogMaxWidth = if (maxWidth > 1200.dp) 1000.dp else if (maxWidth > 950.dp) 910.dp else maxWidth * 0.86f
            val dialogMaxHeight = if (maxHeight > 700.dp) 590.dp else if (maxHeight > 550.dp) 540.dp else maxHeight * 0.86f

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
                        .clip(RoundedCornerShape(22.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color(0xFF0C1527),
                                    Color(0xFF070D18)
                                )
                            )
                        )
                        .border(1.5.dp, Color(0xFF1E2E44), RoundedCornerShape(22.dp))
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
                                        .background(Premium_PrimaryAccent.copy(alpha = 0.15f))
                                        .border(1.dp, Premium_PrimaryAccent.copy(alpha = 0.40f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.LocalGasStation,
                                        contentDescription = null,
                                        tint = Premium_PrimaryAccent,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                Column {
                                    Text(
                                        text = "LOG REFUEL & CALIBRATE",
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Black,
                                            fontSize = 18.5.sp,
                                            letterSpacing = 0.6.sp
                                        ),
                                        color = Color.White
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PinDrop,
                                            contentDescription = null,
                                            tint = Premium_PrimaryAccent,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        val cleanAddress = locationNameText
                                            .replace(Regex("(?i)\\b(HN\\s*\\d+|PO)\\b[, ]*"), "")
                                            .trim()
                                            .ifBlank { "Fuel Station, Auto-Detecting" }
                                        Text(
                                            text = cleanAddress.take(48),
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontSize = 15.sp, // Large font for automotive distance
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
                                    // Row 1: Amount (₹) & Price / L (₹/L) side-by-side (Compact height: 46dp)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(46.dp),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        // Field 1: Total Amount (₹)
                                        val isAmountActive = activeField == ActiveFuelField.AMOUNT
                                        Box(
                                            modifier = Modifier
                                                .weight(1.15f)
                                                .fillMaxHeight()
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(Color(0xFF0F172A))
                                                .border(
                                                    1.5.dp,
                                                    if (isAmountActive) Cockpit_AmberWarning else Color(0xFF1E293B),
                                                    RoundedCornerShape(10.dp)
                                                )
                                                .clickable { activeField = ActiveFuelField.AMOUNT }
                                                .padding(horizontal = 10.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxSize(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    Text(
                                                        text = "AMOUNT",
                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                            fontWeight = FontWeight.Black,
                                                            fontSize = 11.sp,
                                                            letterSpacing = 0.5.sp
                                                        ),
                                                        color = if (isAmountActive) Cockpit_AmberWarning else Color(0xFF94A3B8)
                                                    )
                                                    if (isAmountActive) {
                                                        Box(
                                                            modifier = Modifier
                                                                .size(5.dp)
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
                                                        style = MaterialTheme.typography.titleMedium.copy(
                                                            fontWeight = FontWeight.Black,
                                                            fontSize = 18.sp,
                                                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                                                        ),
                                                        color = Cockpit_AmberWarning
                                                    )
                                                    Text(
                                                        text = totalAmountText.ifEmpty { "0" },
                                                        style = MaterialTheme.typography.titleLarge.copy(
                                                            fontWeight = FontWeight.Black,
                                                            fontSize = 22.sp,
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
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(Color(0xFF0F172A))
                                                .border(
                                                    1.5.dp,
                                                    if (isRateActive) Color(0xFF38BDF8) else Color(0xFF1E293B),
                                                    RoundedCornerShape(10.dp)
                                                )
                                                .clickable { activeField = ActiveFuelField.RATE }
                                                .padding(horizontal = 10.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxSize(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "PRICE/L",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontWeight = FontWeight.Black,
                                                        fontSize = 11.sp,
                                                        letterSpacing = 0.5.sp
                                                    ),
                                                    color = if (isRateActive) Color(0xFF38BDF8) else Color(0xFF94A3B8)
                                                )
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                                ) {
                                                    Text(
                                                        text = "₹",
                                                        style = MaterialTheme.typography.titleSmall.copy(
                                                            fontWeight = FontWeight.Black,
                                                            fontSize = 15.sp
                                                        ),
                                                        color = Color(0xFF38BDF8)
                                                    )
                                                    Text(
                                                        text = pricePerLiterText.ifEmpty { "0" },
                                                        style = MaterialTheme.typography.titleLarge.copy(
                                                            fontWeight = FontWeight.Black,
                                                            fontSize = 20.sp,
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

                                    // Row 2: Odometer Reading (KM) directly underneath (Compact height: 46dp)
                                    val isOdoActive = activeField == ActiveFuelField.ODOMETER
                                    val odoBorderColor = when {
                                        !isOdometerValid && refuelOdometerText.isNotEmpty() -> Cockpit_RedAlert
                                        isOdoActive -> Color(0xFFC084FC)
                                        else -> Color(0xFF1E293B)
                                    }

                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(46.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Color(0xFF0F172A))
                                            .border(1.5.dp, odoBorderColor, RoundedCornerShape(10.dp))
                                            .clickable { activeField = ActiveFuelField.ODOMETER }
                                            .padding(horizontal = 10.dp)
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
                                                    text = "ODOMETER",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontWeight = FontWeight.Black,
                                                        fontSize = 11.sp,
                                                        letterSpacing = 0.5.sp
                                                    ),
                                                    color = if (!isOdometerValid && refuelOdometerText.isNotEmpty()) Cockpit_RedAlert else if (isOdoActive) Color(0xFFC084FC) else Color(0xFF94A3B8)
                                                )
                                                if (refuelOdometerText.isNotEmpty() && enteredOdo <= 0.0) {
                                                    Text(
                                                        text = "⚠️ Enter valid km",
                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                            fontWeight = FontWeight.Black,
                                                            fontSize = 9.5.sp,
                                                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                                                        ),
                                                        color = Cockpit_RedAlert
                                                    )
                                                } else if (odoTripDelta > 0.05) {
                                                    Text(
                                                        text = "+${NumberFormatUtils.formatDistance(odoTripDelta, 1)} km",
                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                            fontWeight = FontWeight.Black,
                                                            fontSize = 10.sp,
                                                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                                                        ),
                                                        color = Color(0xFFC084FC)
                                                    )
                                                }
                                            }

                                            Row(verticalAlignment = Alignment.Bottom) {
                                                Text(
                                                    text = refuelOdometerText.ifEmpty { "0" },
                                                    style = MaterialTheme.typography.titleLarge.copy(
                                                        fontWeight = FontWeight.Black,
                                                        fontSize = 22.sp,
                                                        fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum",
                                                        letterSpacing = (-0.5).sp,
                                                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                                                    ),
                                                    color = if (!isOdometerValid && refuelOdometerText.isNotEmpty()) Cockpit_RedAlert else Color.White
                                                )
                                                Spacer(Modifier.width(3.dp))
                                                Text(
                                                    text = "km",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 12.sp
                                                    ),
                                                    color = Color(0xFFC084FC),
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

                                // 3. BOTTOM SECTION: ACTIONS (CANCEL & CONFIRM REFUEL)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(44.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Button(
                                        onClick = onDismiss,
                                        modifier = Modifier
                                            .weight(0.35f)
                                            .fillMaxHeight(),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFF1E293B),
                                            contentColor = Color(0xFFCBD5E1)
                                        )
                                    ) {
                                        Text(
                                            text = "CANCEL",
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.5.sp
                                            )
                                        )
                                    }

                                    val isFormValid = totalAmount > 0.0 && (refuelOdometerText.isEmpty() || enteredOdo > 0.0)

                                    Button(
                                        onClick = {
                                            if (totalAmount <= 0.0) {
                                                Toast.makeText(context, "Please enter a valid refuel amount", Toast.LENGTH_SHORT).show()
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
                                                locationNameText,
                                                lat,
                                                lon
                                            )
                                            onDismiss()
                                        },
                                        enabled = isFormValid,
                                        modifier = Modifier
                                            .weight(0.65f)
                                            .fillMaxHeight(),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFF0284C7),
                                            contentColor = Color.White,
                                            disabledContainerColor = Color(0xFF1E293B),
                                            disabledContentColor = Color(0xFF64748B)
                                        )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(5.dp))
                                        Text(
                                            text = "CONFIRM REFUEL",
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontWeight = FontWeight.Black,
                                                fontSize = 13.5.sp,
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
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color(0xFF070D18))
                                    .border(1.2.dp, Color(0xFF1E2E44), RoundedCornerShape(16.dp))
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
                                                val bg = if (isBack) Color(0xFF261216) else Color(0xFF0F1A2D)

                                                TactileKeypadButton(
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
                                        TactileKeypadButton(
                                            text = "CLR",
                                            onClick = { onKeypadPress("C") },
                                            accentColor = Cockpit_RedAlert,
                                            backgroundColor = Color(0xFF261216),
                                            isSpecialAction = true,
                                            modifier = Modifier
                                                .weight(0.40f)
                                                .fillMaxHeight()
                                        )

                                        TactileKeypadButton(
                                            text = "NEXT",
                                            onClick = { nextField() },
                                            accentColor = Premium_PrimaryAccent,
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

/**
 * UnifiedFuelTelemetryAndGaugeCard:
 * Complete merged automotive fuel telemetry & calibration pod:
 * 1. Header with dynamic OEM spec & delta bar badge (+X BARS / X/10 BARS).
 * 2. Hero volume & tank fill readouts in commanding, large typography (34sp).
 * 3. Vibrant Colorful 10-Segment LED Fuel Gauge with red (reserve) -> orange (low/mid) -> green (full) progression.
 *    - Scale calibration markers: E (Reserve) <---> 1/4 <---> 1/2 (50%) <---> 3/4 <---> F (Full).
 *    - Interactive MID Bar touch calibration [1..10].
 * 4. 4 Large Telemetry Boxes with big readable text (22sp values):
 *    - FUEL ADDED (Liters + Tank % boost)
 *    - RANGE GAIN (+km added @ mileage)
 *    - RUNNING COST (₹/km rate)
 *    - TOTAL RANGE (km projected post-fill)
 * 5. Continuous dual progress bar: Current base level (Green) vs Refill boost (Cyan).
 */
@Composable
fun UnifiedFuelTelemetryAndGaugeCard(
    currentBar: Int,
    postRefillBar: Int,
    additionalBars: Int,
    currentFuelLiters: Double,
    dispensedLiters: Double,
    postRefillLiters: Double,
    designCap: Double,
    effectiveMileage: Double,
    pricePerLiter: Double,
    isOverfill: Boolean,
    onBarSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val tankPct = ((postRefillLiters / designCap) * 100).toInt().coerceIn(0, 100)
    val currentPct = ((currentFuelLiters / designCap) * 100).toInt().coerceIn(0, 100)
    val addedPct = ((dispensedLiters / designCap) * 100).toInt()
    val addedRangeKm = dispensedLiters * effectiveMileage
    val runningCostPerKm = if (effectiveMileage > 0.0) pricePerLiter / effectiveMileage else 5.36
    val totalRangeKm = postRefillLiters * effectiveMileage

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF090F1C))
            .border(1.2.dp, Color(0xFF1E2D44), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // ==================== 1. HEADER ROW ====================
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.LocalGasStation,
                        contentDescription = null,
                        tint = Color(0xFF00F5FF),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "FUEL LEVEL & TELEMETRIC PROJECTION",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Black,
                            fontSize = 12.sp,
                            letterSpacing = 0.8.sp
                        ),
                        color = Color(0xFF00F5FF)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Tank: ${designCap.toInt()} L",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                        ),
                        color = Color(0xFF94A3B8)
                    )

                    // Delta / Bar Badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (additionalBars > 0) Color(0xFF0284C7).copy(alpha = 0.25f) else Color(0xFF1E293B))
                            .border(
                                1.dp,
                                if (additionalBars > 0) Color(0xFF00B0FF).copy(alpha = 0.70f) else Color(0xFF334155),
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = if (additionalBars > 0) "+$additionalBars BARS" else "$postRefillBar/10 BARS",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Black,
                                fontSize = 10.sp,
                                fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                            ),
                            color = if (additionalBars > 0) Color(0xFF00B0FF) else Color(0xFF94A3B8)
                        )
                    }
                }
            }

            // ==================== 2. HERO VOLUME & TANK FILL % ROW (BADA TEXT) ====================
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Target Volume
                Column {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = NumberFormatUtils.formatFuelLiters(postRefillLiters, 1),
                            style = MaterialTheme.typography.headlineLarge.copy(
                                fontWeight = FontWeight.Black,
                                fontSize = 34.sp,
                                fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum",
                                letterSpacing = (-1).sp,
                                platformStyle = PlatformTextStyle(includeFontPadding = false)
                            ),
                            color = Color.White
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "L",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Black,
                                fontSize = 18.sp
                            ),
                            color = Color(0xFF00F5FF),
                            modifier = Modifier.padding(bottom = 3.dp)
                        )
                    }
                    Text(
                        text = if (dispensedLiters > 0.05) "Target Volume (+${NumberFormatUtils.formatFuelLiters(dispensedLiters, 1)}L refueled)" else "Current Tank Volume (${postRefillLiters.toInt()}L)",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = Color(0xFF94A3B8)
                    )
                }

                // Right: Tank Fill %
                Column(horizontalAlignment = Alignment.End) {
                    val pctColor = when {
                        tankPct <= 20 -> Cockpit_RedAlert
                        tankPct <= 45 -> Cockpit_AmberWarning
                        else -> Cockpit_GreenEco
                    }
                    Text(
                        text = "$tankPct%",
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontWeight = FontWeight.Black,
                            fontSize = 34.sp,
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum",
                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                        ),
                        color = pctColor
                    )
                    Text(
                        text = if (isOverfill) "OVERFILL LIMIT WARNING" else "MID Bar $postRefillBar of 10",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                        ),
                        color = if (isOverfill) Cockpit_AmberWarning else Color(0xFF64748B)
                    )
                }
            }

            // ==================== 3. COLORFUL 10-SEGMENT LED FUEL GAUGE ====================
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    (1..10).forEach { bar ->
                        val isAdded = bar in (currentBar + 1)..postRefillBar
                        val isFilled = bar <= postRefillBar
                        val isSelected = bar == postRefillBar

                        // Colorful spectrum: Red (1-2) -> Orange/Amber (3-5) -> Vivid Green (6-10)
                        val baseBarColor = when (bar) {
                            1, 2 -> Color(0xFFEF4444) // Red (Reserve/Empty)
                            3, 4, 5 -> Color(0xFFFF9100) // Orange/Amber (Low/Mid)
                            else -> Color(0xFF10B981) // Green (Upper/Full)
                        }

                        val segmentColor = when {
                            isFilled -> baseBarColor
                            else -> baseBarColor.copy(alpha = 0.12f) // Backlit lens tint
                        }

                        val borderColor = when {
                            isSelected -> Color.White
                            isAdded -> Color(0xFF00F5FF)
                            isFilled -> baseBarColor.copy(alpha = 0.85f)
                            else -> Color(0xFF1E2B40)
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(4.dp))
                                .background(segmentColor)
                                .border(if (isSelected) 1.8.dp else 1.dp, borderColor, RoundedCornerShape(4.dp))
                                .clickable { onBarSelected(bar) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(Color.White)
                                )
                            }
                        }
                    }
                }

                // Scale Calibration Labels: E (Reserve) <---> 1/4 <---> 1/2 <---> 3/4 <---> F
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "E (Reserve)",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Black,
                            fontSize = 10.5.sp,
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                        ),
                        color = Color(0xFFEF4444)
                    )
                    Text(
                        text = "1/4 (25%)",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                        ),
                        color = Color(0xFFFF9100)
                    )
                    Text(
                        text = "1/2 (50%)",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                        ),
                        color = Color(0xFFEAB308)
                    )
                    Text(
                        text = "3/4 (75%)",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                        ),
                        color = Color(0xFF22C55E)
                    )
                    Text(
                        text = "F (${designCap.toInt()}L)",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Black,
                            fontSize = 10.5.sp,
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                        ),
                        color = Color(0xFF10B981)
                    )
                }
            }

            // ==================== 4. 4 LARGE TELEMETRY BOXES (BADA TEXT) ====================
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Box 1: FUEL ADDED
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0F1A2D))
                        .border(1.dp, Color(0xFF1E2F4A), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "FUEL ADDED",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Black,
                                fontSize = 10.5.sp,
                                letterSpacing = 0.5.sp
                            ),
                            color = Color(0xFF94A3B8)
                        )
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = NumberFormatUtils.formatFuelLiters(dispensedLiters, 2),
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Black,
                                    fontSize = 22.sp,
                                    fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                                ),
                                color = Color.White
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                text = "L",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Black,
                                    fontSize = 12.sp
                                ),
                                color = Color(0xFF00F5FF),
                                modifier = Modifier.padding(bottom = 1.dp)
                            )
                        }
                        Text(
                            text = if (addedPct > 0) "+$addedPct% tank boost" else "0% added",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Bold),
                            color = if (addedPct > 0) Color(0xFF00F5FF) else Color(0xFF64748B),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Box 2: RANGE GAIN
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0F1A2D))
                        .border(1.dp, Color(0xFF1E2F4A), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "RANGE GAIN",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Black,
                                fontSize = 10.5.sp,
                                letterSpacing = 0.5.sp
                            ),
                            color = Color(0xFF94A3B8)
                        )
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = if (addedRangeKm > 0.1) "+${addedRangeKm.toInt()} km" else "+0 km",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Black,
                                    fontSize = 22.sp,
                                    fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                                ),
                                color = Color(0xFF10B981)
                            )
                        }
                        Text(
                            text = "@ ${NumberFormatUtils.formatMileage(effectiveMileage, 1)} km/L",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Bold),
                            color = Color(0xFF10B981).copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Box 3: RUNNING COST
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0F1A2D))
                        .border(1.dp, Color(0xFF1E2F4A), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "RUNNING COST",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Black,
                                fontSize = 10.5.sp,
                                letterSpacing = 0.5.sp
                            ),
                            color = Color(0xFF94A3B8)
                        )
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = "₹${NumberFormatUtils.formatPrecision(runningCostPerKm, 2)}",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Black,
                                    fontSize = 22.sp,
                                    fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                                ),
                                color = Color(0xFFF59E0B)
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                text = "/km",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Black,
                                    fontSize = 12.sp
                                ),
                                color = Color(0xFFF59E0B).copy(alpha = 0.85f),
                                modifier = Modifier.padding(bottom = 1.dp)
                            )
                        }
                        Text(
                            text = "Real-time cost rate",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Bold),
                            color = Color(0xFFF59E0B).copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Box 4: TOTAL RANGE
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0F1A2D))
                        .border(1.dp, Color(0xFF1E2F4A), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "TOTAL RANGE",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Black,
                                fontSize = 10.5.sp,
                                letterSpacing = 0.5.sp
                            ),
                            color = Color(0xFF94A3B8)
                        )
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = "${totalRangeKm.toInt()} km",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Black,
                                    fontSize = 22.sp,
                                    fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                                ),
                                color = Color(0xFF38BDF8)
                            )
                        }
                        Text(
                            text = "${postRefillLiters.toInt()}L post-refill",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Bold),
                            color = Color(0xFF38BDF8).copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // ==================== 5. DUAL PROGRESS BAR ====================
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(9.dp)
                        .clip(RoundedCornerShape(4.5.dp))
                        .background(Color(0xFF141F32))
                ) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        val currentFraction = (currentFuelLiters / designCap).toFloat().coerceIn(0f, 1f)
                        val addedFraction = (dispensedLiters / designCap).toFloat().coerceIn(0f, 1f - currentFraction)
                        if (currentFraction > 0.005f) {
                            Box(
                                modifier = Modifier
                                    .weight(currentFraction)
                                    .fillMaxHeight()
                                    .background(Color(0xFF10B981))
                            )
                        }
                        if (addedFraction > 0.005f) {
                            Box(
                                modifier = Modifier
                                    .weight(addedFraction)
                                    .fillMaxHeight()
                                    .background(Color(0xFF00F5FF))
                            )
                        }
                        val remainingFraction = (1f - currentFraction - addedFraction).coerceAtLeast(0f)
                        if (remainingFraction > 0.005f) {
                            Box(
                                modifier = Modifier
                                    .weight(remainingFraction)
                                    .fillMaxHeight()
                                    .background(Color(0xFF141F32))
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Current: ${currentPct}% (${currentFuelLiters.toInt()}L)",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                        ),
                        color = Color(0xFF94A3B8)
                    )
                    Text(
                        text = "Post-Refill: ${tankPct}% (${postRefillLiters.toInt()}L)",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Black,
                            fontSize = 11.sp,
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                        ),
                        color = if (isOverfill) Cockpit_AmberWarning else Color(0xFF00F5FF)
                    )
                }
            }
        }
    }
}

/**
 * AutoCalculatedTelemetryCard:
 * Prominent Automotive Refuel Telemetry Pod:
 * - 4 Key Calculated Metrics (2x2 Grid):
 *   1. FUEL ADDED (Liters + Tank % boost)
 *   2. RANGE GAIN (+km added based on vehicle efficiency)
 *   3. RUNNING COST (₹/km travel cost)
 *   4. TOTAL RANGE (km projected post-fill)
 * - Dual Progress Bar: Current level (Green) vs Refuel boost (Cyan)
 */
@Composable
fun AutoCalculatedTelemetryCard(
    dispensedLiters: Double,
    clampedFinalTank: Double,
    currentFuelLiters: Double,
    designCap: Double,
    effectiveMileage: Double,
    pricePerLiter: Double,
    isOverfill: Boolean,
    modifier: Modifier = Modifier
) {
    val addedRangeKm = dispensedLiters * effectiveMileage
    val currentPct = ((currentFuelLiters / designCap) * 100).toInt().coerceIn(0, 100)
    val finalPct = ((clampedFinalTank / designCap) * 100).toInt().coerceIn(0, 100)
    val addedPct = ((dispensedLiters / designCap) * 100).toInt()
    val runningCostPerKm = if (effectiveMileage > 0.0) pricePerLiter / effectiveMileage else 5.36
    val totalRangeKm = clampedFinalTank * effectiveMileage

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF09101F))
            .border(1.dp, Color(0xFF1B273A), RoundedCornerShape(14.dp))
            .padding(10.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header: Spark Icon + Title + Tank Capacity
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "AUTO-CALCULATED TELEMETRY",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Black,
                            fontSize = 11.5.sp,
                            letterSpacing = 0.5.sp
                        ),
                        color = Color(0xFF38BDF8)
                    )
                }

                Text(
                    text = "Tank: ${designCap.toInt()} L",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.5.sp,
                        fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                    ),
                    color = Color(0xFF94A3B8)
                )
            }

            // 4 Computed Metrics Grid (2x2): Fuel Added, Range Gain, Running Cost, Total Range
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Row 1: Fuel Added & Range Gain
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Tile 1: Fuel Added
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0F1A2D))
                            .border(1.dp, Color(0xFF1E2F4A), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Column {
                            Text(
                                text = "FUEL ADDED",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Black,
                                    fontSize = 10.sp,
                                    letterSpacing = 0.5.sp
                                ),
                                color = Color(0xFF94A3B8)
                            )
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = NumberFormatUtils.formatFuelLiters(dispensedLiters, 2),
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Black,
                                        fontSize = 18.sp,
                                        fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                                    ),
                                    color = Color.White
                                )
                                Spacer(Modifier.width(2.dp))
                                Text(
                                    text = "L",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Black,
                                        fontSize = 11.sp
                                    ),
                                    color = Color(0xFF38BDF8),
                                    modifier = Modifier.padding(bottom = 1.dp)
                                )
                            }
                            Text(
                                text = if (addedPct > 0) "+$addedPct% tank boost" else "0% added",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.5.sp, fontWeight = FontWeight.Bold),
                                color = if (addedPct > 0) Color(0xFF38BDF8) else Color(0xFF64748B),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Tile 2: Range Gain
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0F1A2D))
                            .border(1.dp, Color(0xFF1E2F4A), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Column {
                            Text(
                                text = "RANGE GAIN",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Black,
                                    fontSize = 10.sp,
                                    letterSpacing = 0.5.sp
                                ),
                                color = Color(0xFF94A3B8)
                            )
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = if (addedRangeKm > 0.1) "+${addedRangeKm.toInt()} km" else "+0 km",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Black,
                                        fontSize = 18.sp,
                                        fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                                    ),
                                    color = Cockpit_GreenEco
                                )
                            }
                            Text(
                                text = "@ ${NumberFormatUtils.formatMileage(effectiveMileage, 1)} km/L",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.5.sp, fontWeight = FontWeight.Bold),
                                color = Cockpit_GreenEco.copy(alpha = 0.85f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Row 2: Running Cost & Total Range
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Tile 3: Running Cost
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0F1A2D))
                            .border(1.dp, Color(0xFF1E2F4A), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Column {
                            Text(
                                text = "RUNNING COST",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Black,
                                    fontSize = 10.sp,
                                    letterSpacing = 0.5.sp
                                ),
                                color = Color(0xFF94A3B8)
                            )
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = "₹${NumberFormatUtils.formatPrecision(runningCostPerKm, 2)}",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Black,
                                        fontSize = 18.sp,
                                        fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                                    ),
                                    color = Color(0xFFF59E0B)
                                )
                                Spacer(Modifier.width(2.dp))
                                Text(
                                    text = "/km",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Black,
                                        fontSize = 11.sp
                                    ),
                                    color = Color(0xFFF59E0B).copy(alpha = 0.85f),
                                    modifier = Modifier.padding(bottom = 1.dp)
                                )
                            }
                            Text(
                                text = "Real-time travel cost",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.5.sp, fontWeight = FontWeight.Bold),
                                color = Color(0xFFF59E0B).copy(alpha = 0.85f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Tile 4: Total Range
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0F1A2D))
                            .border(1.dp, Color(0xFF1E2F4A), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Column {
                            Text(
                                text = "TOTAL RANGE",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Black,
                                    fontSize = 10.sp,
                                    letterSpacing = 0.5.sp
                                ),
                                color = Color(0xFF94A3B8)
                            )
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = "${totalRangeKm.toInt()} km",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Black,
                                        fontSize = 18.sp,
                                        fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                                    ),
                                    color = Color(0xFF00E5FF)
                                )
                            }
                            Text(
                                text = "${clampedFinalTank.toInt()}L post-fill",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.5.sp, fontWeight = FontWeight.Bold),
                                color = Color(0xFF00E5FF).copy(alpha = 0.85f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // Dual Progress Bar (Current + Added Fuel)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF141F32))
                ) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        val currentFraction = (currentFuelLiters / designCap).toFloat().coerceIn(0f, 1f)
                        val addedFraction = (dispensedLiters / designCap).toFloat().coerceIn(0f, 1f - currentFraction)
                        if (currentFraction > 0.005f) {
                            Box(
                                modifier = Modifier
                                    .weight(currentFraction)
                                    .fillMaxHeight()
                                    .background(Color(0xFF059669))
                            )
                        }
                        if (addedFraction > 0.005f) {
                            Box(
                                modifier = Modifier
                                    .weight(addedFraction)
                                    .fillMaxHeight()
                                    .background(Color(0xFF00B0FF))
                            )
                        }
                        val remainingFraction = (1f - currentFraction - addedFraction).coerceAtLeast(0f)
                        if (remainingFraction > 0.005f) {
                            Box(
                                modifier = Modifier
                                    .weight(remainingFraction)
                                    .fillMaxHeight()
                                    .background(Color(0xFF141F32))
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Current: ${currentPct}%",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                        ),
                        color = Color(0xFF94A3B8)
                    )
                    Text(
                        text = "Post-Refill: ${finalPct}%",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Black,
                            fontSize = 11.sp,
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                        ),
                        color = if (isOverfill) Cockpit_AmberWarning else Color(0xFF38BDF8)
                    )
                }
            }
        }
    }
}

/**
 * TactileKeypadButton:
 * Automotive numeric keypad button with animated touch feedback:
 * 1. Subtle scale-down on press (1.0f -> 0.92f).
 * 2. Temporary background highlight and glowing border in dashboard accent colors.
 */
@Composable
private fun TactileKeypadButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = Color.White,
    backgroundColor: Color = Color(0xFF0F172A),
    icon: ImageVector? = null,
    isSpecialAction: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val animatedScale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1.0f,
        animationSpec = tween(durationMillis = 90, easing = FastOutSlowInEasing),
        label = "KeypadScale"
    )

    val animatedBg by animateColorAsState(
        targetValue = if (isPressed) {
            accentColor.copy(alpha = 0.28f)
        } else {
            backgroundColor
        },
        animationSpec = tween(durationMillis = 90),
        label = "KeypadBg"
    )

    val animatedBorderColor by animateColorAsState(
        targetValue = if (isPressed) {
            accentColor.copy(alpha = 0.85f)
        } else {
            if (isSpecialAction) accentColor.copy(alpha = 0.35f) else Color(0xFF1E293B)
        },
        animationSpec = tween(durationMillis = 90),
        label = "KeypadBorder"
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
            }
            .clip(RoundedCornerShape(12.dp))
            .background(animatedBg)
            .border(1.2.dp, animatedBorderColor, RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (text.isNotBlank()) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Black,
                            fontSize = 14.sp,
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum",
                            letterSpacing = 0.5.sp
                        ),
                        color = accentColor
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Icon(
                    imageVector = icon,
                    contentDescription = if (text.isNotBlank()) text else "Backspace",
                    tint = accentColor,
                    modifier = Modifier.size(24.dp)
                )
            }
        } else {
            Text(
                text = text,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Black,
                    fontSize = 24.sp,
                    fontFamily = FontFamily.SansSerif
                ),
                color = accentColor
            )
        }
    }
}
