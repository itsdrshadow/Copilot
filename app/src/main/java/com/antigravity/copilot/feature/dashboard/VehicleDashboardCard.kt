package com.antigravity.copilot.feature.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.antigravity.copilot.core.database.TripEntity
import com.antigravity.copilot.core.designsystem.CockpitTheme
import com.antigravity.copilot.core.fuel.intelligence.BestMileageSpeedEngine
import com.antigravity.copilot.core.fuel.intelligence.BestMileageSpeedResult
import com.antigravity.copilot.core.location.RoadSurfaceCondition
import com.antigravity.copilot.core.location.SpeedLimitInfo
import com.antigravity.copilot.core.location.StoppingDistanceEngine
import com.antigravity.copilot.core.location.StoppingDistanceEstimate
import com.antigravity.copilot.core.model.FuelState
import com.antigravity.copilot.core.model.TripPoint
import com.antigravity.copilot.core.odometer.OdometerManager
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * VehicleDashboardCard:
 * Unified Automotive Vehicle Information Card combining:
 * 1. Speedometer (Primary Visual Hero Element)
 * 2. Fuel System (Fuel Level, Tank Capacity, Remaining DTE Range)
 * 3. Odometer (Live & Lifetime Total Distance, Delta, Calibration)
 */
@Composable
fun VehicleDashboardCard(
    speedKmh: Float,
    speedLimitInfo: SpeedLimitInfo,
    latestPoint: TripPoint?,
    fuelState: FuelState,
    allTrips: List<TripEntity>,
    totalLifetimeKm: Double,
    onOpenFuelDialog: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onCalibrateGauge: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
    isVeryCompact: Boolean = false
) {
    val context = LocalContext.current
    val scaling = CockpitTheme.scaling

    // Initialize Odometer Manager
    LaunchedEffect(Unit) {
        OdometerManager.init(context)
    }

    val odoConfig by OdometerManager.odometerConfig.collectAsState()
    val currentOdometerKm = remember(odoConfig.baselineOdometerKm, totalLifetimeKm, odoConfig.baselineLifetimeKmAtUpdate) {
        OdometerManager.calculateLiveOdometer(totalLifetimeKm)
    }
    val deltaKmSinceCalibration = remember(odoConfig.baselineLifetimeKmAtUpdate, totalLifetimeKm) {
        OdometerManager.calculateDeltaKm(totalLifetimeKm)
    }

    var showOdometerDialog by remember { mutableStateOf(false) }

    // ==================== 1. SPEEDOMETER TELEMETRY ENGINE ====================
    var lastValidSpeedKmh by remember { mutableFloatStateOf(0f) }
    var lastValidSpeedTimeMs by remember { mutableLongStateOf(0L) }
    var tickerTimeMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val now = tickerTimeMs
    val isGpsPointFresh = latestPoint != null &&
            latestPoint.latitude != 0.0 &&
            latestPoint.longitude != 0.0 &&
            latestPoint.accuracy < 60f

    if (isGpsPointFresh && latestPoint != null && (now - latestPoint.timestamp) < 2000L && speedKmh >= 0f) {
        lastValidSpeedKmh = speedKmh
        lastValidSpeedTimeMs = latestPoint.timestamp
    }

    val gapMs = if (lastValidSpeedTimeMs > 0L) (now - lastValidSpeedTimeMs).coerceAtLeast(0L) else 0L
    val isGpsValid = isGpsPointFresh && gapMs <= 10000L
    val rawSpeed = calculateSpeedometerDecay(lastValidSpeedKmh, gapMs)

    LaunchedEffect(gapMs > 2000L && rawSpeed > 0f) {
        if (gapMs > 2000L && rawSpeed > 0f) {
            while (true) {
                kotlinx.coroutines.delay(100L)
                tickerTimeMs = System.currentTimeMillis()
                val currentGap = (tickerTimeMs - lastValidSpeedTimeMs).coerceAtLeast(0L)
                if (currentGap > 10000L) break
            }
        }
    }

    val speedLimit = speedLimitInfo.speedLimitKmh
    val animatedSpeed by animateFloatAsState(
        targetValue = rawSpeed,
        animationSpec = tween(durationMillis = 200, easing = LinearEasing),
        label = "UnifiedSpeedInterpolation"
    )

    val roundedSpeedInt = animatedSpeed.roundToInt()
    val stoppingEstimate: StoppingDistanceEstimate = remember(roundedSpeedInt) {
        StoppingDistanceEngine.calculate(roundedSpeedInt.toFloat(), RoadSurfaceCondition.DRY)
    }

    val bestEcoResult: BestMileageSpeedResult = remember(allTrips) {
        BestMileageSpeedEngine.analyze(allTrips)
    }

    val statusColor by animateColorAsState(
        targetValue = when {
            speedLimit > 0 && animatedSpeed > speedLimit * 1.05f -> CockpitTheme.colors.critical
            speedLimit > 0 && animatedSpeed > speedLimit * 0.92f -> CockpitTheme.colors.warning
            else -> CockpitTheme.colors.accent
        },
        animationSpec = tween(350),
        label = "UnifiedSpeedStatusColor"
    )

    // ==================== 2. FUEL TELEMETRY CALCULATIONS ====================
    val currentLiters = fuelState.remainingLiters
    val capacity = fuelState.designCapacityLiters
    val tankPct = fuelState.fuelPercentage.toDouble().coerceIn(0.0, 100.0)
    val fuelDteKm = fuelState.remainingRangeKm

    val fuelStatusColor = when {
        tankPct <= 15.0 -> CockpitTheme.colors.critical
        tankPct <= 30.0 -> CockpitTheme.colors.warning
        else -> CockpitTheme.colors.accent
    }

    // Card Surface
    val cardBg = CockpitTheme.colors.cardTopLightGradient
    val cardBorder = CockpitTheme.colors.border
    val cornerRadius = if (isVeryCompact) 16.dp else 20.dp
    val padding = if (isVeryCompact) 8.dp else 12.dp

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(cardBg)
            .border(1.2.dp, cardBorder, RoundedCornerShape(cornerRadius))
            .padding(padding)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(if (isVeryCompact) 8.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // =========================================================================
            // SECTION 1: SPEEDOMETER (HERO ELEMENT, LEFT ~48%)
            // =========================================================================
            Box(
                modifier = Modifier
                    .weight(1.15f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                // Header (Speedometer icon + Speed Limit)
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Speed,
                                contentDescription = null,
                                tint = CockpitTheme.colors.accent,
                                modifier = Modifier.size(if (isVeryCompact) 13.dp else 16.dp)
                            )
                            Text(
                                text = "SPEED",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = scaling.scaleFont(10.sp),
                                    letterSpacing = 1.sp
                                ),
                                color = CockpitTheme.colors.textSecondary
                            )
                        }

                        // Speed limit sign
                        if (speedLimit > 0) {
                            Box(
                                modifier = Modifier
                                    .size(if (isVeryCompact) 22.dp else 26.dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                                    .border(2.dp, CockpitTheme.colors.critical, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "$speedLimit",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Black,
                                        fontSize = if (isVeryCompact) 10.sp else 12.sp
                                    ),
                                    color = Color.Black
                                )
                            }
                        }
                    }

                    // 240° Precision Speed Dial & Large Speed Display
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val w = size.width
                            val h = size.height
                            val dialRadius = (minOf(w, h) / 2f) * 0.88f
                            val center = Offset(w / 2f, h / 2f + dialRadius * 0.08f)

                            val startAngle = 150f
                            val sweepRange = 240f
                            val maxDisplaySpeed = 160f
                            val speedProgress = (animatedSpeed / maxDisplaySpeed).coerceIn(0f, 1f)

                            // Background Arc Track
                            drawArc(
                                color = Color.White.copy(alpha = 0.08f),
                                startAngle = startAngle,
                                sweepAngle = sweepRange,
                                useCenter = false,
                                topLeft = Offset(center.x - dialRadius, center.y - dialRadius),
                                size = Size(dialRadius * 2, dialRadius * 2),
                                style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                            )

                            // Active Glowing Speed Arc
                            val activeSweep = sweepRange * speedProgress
                            if (activeSweep > 0.5f) {
                                drawArc(
                                    color = statusColor,
                                    startAngle = startAngle,
                                    sweepAngle = activeSweep,
                                    useCenter = false,
                                    topLeft = Offset(center.x - dialRadius, center.y - dialRadius),
                                    size = Size(dialRadius * 2, dialRadius * 2),
                                    style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round)
                                )

                                val headAngleRad = Math.toRadians((startAngle + activeSweep).toDouble())
                                val pipCenter = Offset(
                                    (center.x + dialRadius * cos(headAngleRad)).toFloat(),
                                    (center.y + dialRadius * sin(headAngleRad)).toFloat()
                                )
                                drawCircle(color = Color.White, radius = 4.dp.toPx(), center = pipCenter)
                                drawCircle(color = statusColor, radius = 2.dp.toPx(), center = pipCenter)
                            }
                        }

                        // Center Speed Readout
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = if (!isGpsValid && animatedSpeed < 1.0f) "0" else "${animatedSpeed.roundToInt()}",
                                style = MaterialTheme.typography.displayLarge.copy(
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.SansSerif,
                                    fontFeatureSettings = "tnum",
                                    fontSize = scaling.scaleFont(if (isVeryCompact) 38.sp else 54.sp)
                                ),
                                color = CockpitTheme.colors.textPrimary
                            )
                            Text(
                                text = "KM/H",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = scaling.scaleFont(if (isVeryCompact) 9.sp else 11.sp),
                                    letterSpacing = 1.sp
                                ),
                                color = CockpitTheme.colors.accent
                            )
                        }
                    }

                    // Bottom Speed Telemetry Badge (Stopping Distance / Eco)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (stoppingEstimate.totalDistanceMeters > 0) {
                            Text(
                                text = "Stop: ${stoppingEstimate.totalDistanceMeters.roundToInt()}m",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = scaling.scaleFont(9.sp),
                                    fontWeight = FontWeight.Medium
                                ),
                                color = CockpitTheme.colors.textSecondary
                            )
                        } else if (bestEcoResult.recommendedBand != null) {
                            Text(
                                text = "Eco: ${bestEcoResult.recommendedBand.compactLabel}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = scaling.scaleFont(9.sp),
                                    fontWeight = FontWeight.Medium
                                ),
                                color = CockpitTheme.colors.accent
                            )
                        } else {
                            Text(
                                text = if (isGpsValid) "GPS Locked" else "GPS Searching",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = scaling.scaleFont(9.sp),
                                    fontWeight = FontWeight.Medium
                                ),
                                color = if (isGpsValid) CockpitTheme.colors.accent else CockpitTheme.colors.warning
                            )
                        }
                    }
                }
            }

            // Sleek Divider Line
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(1.dp)
                    .background(CockpitTheme.colors.border.copy(alpha = 0.5f))
            )

            // =========================================================================
            // SECTION 2 & 3: FUEL SYSTEM (TOP) & ODOMETER (BOTTOM) (RIGHT ~52%)
            // =========================================================================
            Column(
                modifier = Modifier
                    .weight(1.0f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // ==================== FUEL SYSTEM SUB-PANEL ====================
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onOpenFuelDialog)
                        .padding(vertical = 2.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Header: Fuel Icon, Label, Remaining Liters
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocalGasStation,
                                contentDescription = null,
                                tint = fuelStatusColor,
                                modifier = Modifier.size(if (isVeryCompact) 13.dp else 16.dp)
                            )
                            Text(
                                text = "FUEL",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = scaling.scaleFont(10.sp),
                                    letterSpacing = 1.sp
                                ),
                                color = CockpitTheme.colors.textSecondary
                            )
                        }

                        // Liters / Capacity
                        val litersDisplay = if (capacity > 0.0) {
                            String.format(Locale.US, "%.1f/%.0fL", currentLiters, capacity)
                        } else {
                            "Unavailable"
                        }
                        Text(
                            text = litersDisplay,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = scaling.scaleFont(if (isVeryCompact) 10.sp else 11.5.sp)
                            ),
                            color = CockpitTheme.colors.textPrimary
                        )
                    }

                    // Fuel Gauge Bar
                    LinearProgressIndicator(
                        progress = { (tankPct / 100.0).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (isVeryCompact) 4.dp else 5.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = fuelStatusColor,
                        trackColor = CockpitTheme.colors.cardElevated
                    )

                    // Fuel Percentage & DTE Range
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${tankPct.roundToInt()}% Level",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = scaling.scaleFont(if (isVeryCompact) 10.sp else 11.5.sp)
                            ),
                            color = fuelStatusColor
                        )

                        val dteDisplay = if (fuelDteKm > 0.0) {
                            String.format(Locale.US, "%.0f km DTE", fuelDteKm)
                        } else {
                            "-- km Range"
                        }
                        Text(
                            text = dteDisplay,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = scaling.scaleFont(if (isVeryCompact) 10.sp else 11.5.sp)
                            ),
                            color = CockpitTheme.colors.accent
                        )
                    }
                }

                // Subtle Horizontal Sub-divider
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(CockpitTheme.colors.border.copy(alpha = 0.4f))
                )

                // ==================== ODOMETER SUB-PANEL ====================
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showOdometerDialog = true }
                        .padding(vertical = 2.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Header: Odometer Icon, Label, Edit Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DirectionsCar,
                                contentDescription = null,
                                tint = CockpitTheme.colors.accent,
                                modifier = Modifier.size(if (isVeryCompact) 13.dp else 16.dp)
                            )
                            Text(
                                text = "ODOMETER",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = scaling.scaleFont(10.sp),
                                    letterSpacing = 1.sp
                                ),
                                color = CockpitTheme.colors.textSecondary
                            )
                        }

                        // Calibration status badge
                        val isCalibrated = odoConfig.isManuallyVerified
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isCalibrated) CockpitTheme.colors.accentSoft else CockpitTheme.colors.cardElevated)
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (isCalibrated) "CALIBRATED" else "GPS",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = scaling.scaleFont(8.5.sp)
                                ),
                                color = if (isCalibrated) CockpitTheme.colors.accent else CockpitTheme.colors.textSecondary
                            )
                        }
                    }

                    // Main Odometer Value
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Text(
                            text = String.format(Locale.US, "%,.1f", currentOdometerKm),
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Black,
                                fontSize = scaling.scaleFont(if (isVeryCompact) 16.sp else 20.sp)
                            ),
                            color = CockpitTheme.colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "KM",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = scaling.scaleFont(10.sp)
                            ),
                            color = CockpitTheme.colors.accent
                        )
                    }

                    // Delta since calibration + Edit Hint
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (deltaKmSinceCalibration > 0.05) {
                                String.format(Locale.US, "+%.1f km", deltaKmSinceCalibration)
                            } else {
                                "Synced"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = scaling.scaleFont(9.5.sp),
                                fontWeight = FontWeight.Medium
                            ),
                            color = CockpitTheme.colors.accent
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Calibrate",
                                tint = CockpitTheme.colors.textSecondary,
                                modifier = Modifier.size(10.dp)
                            )
                            Text(
                                text = "Calibrate",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = scaling.scaleFont(9.5.sp)
                                ),
                                color = CockpitTheme.colors.textSecondary
                            )
                        }
                    }
                }
            }
        }
    }

    // ==================== ODOMETER CALIBRATION DIALOG ====================
    if (showOdometerDialog) {
        VehicleOdometerCalibrationDialog(
            currentOdometerKm = currentOdometerKm,
            onDismiss = { showOdometerDialog = false },
            onConfirmCalibration = { newKm ->
                OdometerManager.setBaselineOdometer(context, newKm, totalLifetimeKm)
                showOdometerDialog = false
            }
        )
    }
}

/**
 * Interactive dialog allowing the driver to calibrate and sync vehicle odometer
 * with physical dashboard cluster reading.
 */
@Composable
private fun VehicleOdometerCalibrationDialog(
    currentOdometerKm: Double,
    onDismiss: () -> Unit,
    onConfirmCalibration: (Double) -> Unit
) {
    var textValue by remember { mutableStateOf(String.format(Locale.US, "%.1f", currentOdometerKm)) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun adjustBy(delta: Double) {
        val current = textValue.toDoubleOrNull() ?: currentOdometerKm
        val newOdo = (current + delta).coerceAtLeast(0.0)
        textValue = String.format(Locale.US, "%.1f", newOdo)
        errorMessage = null
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(520.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(CockpitTheme.colors.card)
                .border(1.2.dp, CockpitTheme.colors.border, RoundedCornerShape(20.dp))
                .padding(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(CockpitTheme.colors.accentSoft),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = null,
                                tint = CockpitTheme.colors.accent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Calibrate Vehicle Odometer",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = CockpitTheme.colors.textPrimary
                            )
                            Text(
                                text = "Sync with physical car dashboard cluster reading",
                                style = MaterialTheme.typography.bodySmall,
                                color = CockpitTheme.colors.textSecondary
                            )
                        }
                    }

                    // Delta Badge
                    val currentVal = textValue.toDoubleOrNull()
                    if (currentVal != null) {
                        val diff = currentVal - currentOdometerKm
                        val diffText = if (kotlin.math.abs(diff) < 0.05) "Exact Match" else String.format(Locale.US, "%+.1f km", diff)
                        val diffColor = if (kotlin.math.abs(diff) < 0.05) CockpitTheme.colors.accent else CockpitTheme.colors.warning
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(diffColor.copy(alpha = 0.15f))
                                .border(1.dp, diffColor.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = diffText,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = diffColor
                            )
                        }
                    }
                }

                // Odometer readout chamber
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(CockpitTheme.colors.cardElevated)
                        .border(1.dp, CockpitTheme.colors.accent.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ODOMETER",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = CockpitTheme.colors.textSecondary
                        )
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = textValue.ifEmpty { "0.0" },
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 28.sp
                                ),
                                color = CockpitTheme.colors.textPrimary
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "KM",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = CockpitTheme.colors.accent,
                                modifier = Modifier.padding(bottom = 2.dp)
                            )
                        }
                    }
                }

                if (errorMessage != null) {
                    Text(
                        text = errorMessage ?: "",
                        color = CockpitTheme.colors.critical,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Quick Step Adjustment Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(-1000.0, -100.0, -10.0, 10.0, 100.0, 1000.0).forEach { delta ->
                        val sign = if (delta > 0) "+${delta.toInt()}" else "${delta.toInt()}"
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(CockpitTheme.colors.cardElevated)
                                .border(1.dp, CockpitTheme.colors.border, RoundedCornerShape(6.dp))
                                .clickable { adjustBy(delta) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = sign,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = if (delta > 0) CockpitTheme.colors.accent else CockpitTheme.colors.critical
                            )
                        }
                    }
                }

                // Keypad
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val keyRows = listOf(
                        listOf("1", "2", "3"),
                        listOf("4", "5", "6"),
                        listOf("7", "8", "9"),
                        listOf(".", "0", "DEL")
                    )

                    keyRows.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            row.forEach { key ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(38.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(CockpitTheme.colors.cardElevated)
                                        .border(1.dp, CockpitTheme.colors.border, RoundedCornerShape(6.dp))
                                        .clickable {
                                            errorMessage = null
                                            when (key) {
                                                "DEL" -> if (textValue.isNotEmpty()) textValue = textValue.dropLast(1)
                                                "." -> if (!textValue.contains(".")) textValue += "."
                                                else -> textValue += key
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = key,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        ),
                                        color = if (key == "DEL") CockpitTheme.colors.critical else CockpitTheme.colors.textPrimary
                                    )
                                }
                            }
                        }
                    }
                }

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { textValue = ""; errorMessage = null }) {
                        Text("Clear", color = CockpitTheme.colors.critical)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDismiss) {
                            Text("Cancel", color = CockpitTheme.colors.textSecondary)
                        }
                        Button(
                            onClick = {
                                val parsed = textValue.toDoubleOrNull()
                                if (parsed == null || parsed < 0.0 || parsed > 2_000_000.0) {
                                    errorMessage = "Enter valid odometer (0 - 2,000,000 km)"
                                } else {
                                    onConfirmCalibration(parsed)
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CockpitTheme.colors.accent)
                        ) {
                            Text("Confirm Calibration", color = CockpitTheme.colors.onAccent, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
