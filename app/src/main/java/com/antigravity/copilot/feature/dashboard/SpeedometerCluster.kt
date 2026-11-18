package com.antigravity.copilot.feature.dashboard

import android.os.SystemClock
import com.antigravity.copilot.core.designsystem.components.contentColorOn
import com.antigravity.copilot.core.designsystem.components.cockpitSurface
import com.antigravity.copilot.core.designsystem.components.TactileKeypadButton
import com.antigravity.copilot.core.designsystem.tokens.CopilotShapes
import com.antigravity.copilot.core.designsystem.components.Raahi
import androidx.compose.ui.semantics.Role
import com.antigravity.copilot.core.util.NumberFormatUtils
import com.antigravity.copilot.core.designsystem.components.VDivider
import com.antigravity.copilot.core.designsystem.components.StatCell
import com.antigravity.copilot.core.designsystem.components.raahiCard
import com.antigravity.copilot.core.designsystem.components.RaahiDialogHost
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import com.antigravity.copilot.core.database.TripEntity
import com.antigravity.copilot.core.designsystem.*
import com.antigravity.copilot.core.location.SpeedLimitInfo
import com.antigravity.copilot.core.model.TripPoint
import com.antigravity.copilot.core.odometer.OdometerManager
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Computes multi-stage speedometer speed decay during GPS signal loss.
 *
 * Stage 1 (0..2000ms): Holds last valid speed (absorbs nominal GPS jitter).
 * Stage 2 (2001..5000ms): Linear aerodynamic coasting deceleration at -2.0 km/h per second.
 * Stage 3 (5001..10000ms): Smoothstep Hermite polynomial decay to 0 km/h:
 *                          f(p) = (1 - p)^2 * (1 + 2p) = 1 - 3p^2 + 2p^3
 *                          where p = (gapMs - 5000) / 5000.
 *                          Guarantees C1 continuity (f'(0) = 0, delta <= 0.01 km/h at 5000ms)
 *                          and strict monotonicity (f'(p) = -6p(1-p) <= 0 for all p in [0, 1]).
 * Stage 4 (> 10000ms): 0 km/h.
 */
fun calculateSpeedometerDecay(lastValidSpeedKmh: Float, gapMs: Long): Float {
    if (lastValidSpeedKmh <= 0f) return 0f
    return when {
        gapMs <= 2000L -> lastValidSpeedKmh
        gapMs in 2001L..5000L -> {
            val coastingSec = (gapMs - 2000L) / 1000f
            (lastValidSpeedKmh - coastingSec * 2.0f).coerceAtLeast(0f)
        }
        gapMs in 5001L..10000L -> {
            val baseCoastingSpeed = (lastValidSpeedKmh - 6.0f).coerceAtLeast(0f)
            if (baseCoastingSpeed <= 0f) return 0f
            val progress = (gapMs - 5000L) / 5000f // 0.0f..1.0f
            val decayFactor = (1f - progress) * (1f - progress) * (1f + 2f * progress)
            baseCoastingSpeed * decayFactor
        }
        else -> 0f
    }
}

/**
 * Speedometer Instrument Cluster:
 * Studio-grade dark navy surface, 240° precision dial, tabular speed digits, stopping distance, and best mileage speed.
 */
@Composable
fun SpeedometerCluster(
    speedKmh: Float,
    speedLimitInfo: SpeedLimitInfo,
    latestPoint: TripPoint?,
    modifier: Modifier = Modifier,
    allTrips: List<TripEntity> = emptyList(),
    totalLifetimeKm: Double = 0.0,
    /** Current / last trip distance; null shows "--" (no trip yet). */
    tripDistanceKm: Double? = null
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        OdometerManager.init(context)
    }

    val odoConfig by OdometerManager.odometerConfig.collectAsStateWithLifecycle()
    val currentOdometerKm = remember(odoConfig.baselineOdometerKm, totalLifetimeKm, odoConfig.baselineLifetimeKmAtUpdate) {
        OdometerManager.calculateLiveOdometer(totalLifetimeKm)
    }
    val deltaKmSinceCalibration = remember(odoConfig.baselineLifetimeKmAtUpdate, totalLifetimeKm) {
        OdometerManager.calculateDeltaKm(totalLifetimeKm)
    }

    var showOdometerDialog by remember { mutableStateOf(false) }

    var lastValidSpeedKmh by remember { mutableFloatStateOf(0f) }
    var lastValidSpeedTimeMs by remember { mutableLongStateOf(0L) }
    var tickerTimeMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    // Freshness on the monotonic clock: GPS time vs the head-unit RTC is meaningless when the RTC
    // is wrong. Points from the service carry elapsedMs; older producers fall back to receive time.
    val pointElapsedMs = remember(latestPoint) {
        latestPoint?.elapsedMs?.takeIf { it > 0L } ?: SystemClock.elapsedRealtime()
    }
    val now = SystemClock.elapsedRealtime()
    val isGpsPointFresh = latestPoint != null &&
            latestPoint.latitude != 0.0 &&
            latestPoint.longitude != 0.0 &&
            latestPoint.accuracy < 60f

    // Latch the latest genuine speed reading outside composition (no state writes while composing).
    LaunchedEffect(latestPoint, speedKmh) {
        val fresh = latestPoint != null && latestPoint.latitude != 0.0 && latestPoint.longitude != 0.0 &&
            latestPoint.accuracy < 60f && SystemClock.elapsedRealtime() - pointElapsedMs < 2000L
        if (fresh && speedKmh >= 0f) {
            lastValidSpeedKmh = speedKmh
            lastValidSpeedTimeMs = pointElapsedMs
        }
    }

    val gapMs = if (lastValidSpeedTimeMs > 0L) (now - lastValidSpeedTimeMs).coerceAtLeast(0L) else 0L
    val isGpsValid = isGpsPointFresh && gapMs <= 10000L

    val rawSpeed = calculateSpeedometerDecay(lastValidSpeedKmh, gapMs)

    // Decay Animation Driver: updates tickerTimeMs every 100ms while decaying to 0
    LaunchedEffect(gapMs > 2000L && rawSpeed > 0f) {
        if (gapMs > 2000L && rawSpeed > 0f) {
            while (true) {
                kotlinx.coroutines.delay(100L)
                tickerTimeMs = SystemClock.elapsedRealtime()
                val currentGap = (tickerTimeMs - lastValidSpeedTimeMs).coerceAtLeast(0L)
                if (currentGap > 10000L) break
            }
        }
    }

    val speedLimit = speedLimitInfo.speedLimitKmh

    // Kept as State: the dial reads it in the draw phase (redraw only, no recomposition) and the
    // text reads a derived integer, so the card recomposes once per km/h, not on every frame.
    val animatedSpeedState = animateFloatAsState(
        targetValue = rawSpeed,
        animationSpec = tween(durationMillis = 200, easing = LinearEasing),
        label = "PremiumSpeedInterpolation"
    )

    val roundedSpeedInt by remember { derivedStateOf { animatedSpeedState.value.roundToInt() } }

    // Dynamic Multi-Stage Speed Status Colors
    val statusColor by animateColorAsState(
        targetValue = when {
            // Debounced by SpeedLimitEngine: red only after 2 readings above limit + 3 km/h.
            speedLimitInfo.isOverspeed -> CockpitTheme.colors.critical
            speedLimitInfo.isNearLimit -> CockpitTheme.colors.warning
            else -> CockpitTheme.colors.accent
        },
        animationSpec = tween(350),
        label = "PremiumSpeedStatusColor"
    )

    // GPS Acceleration & Speed Trend
    var prevSpeed by remember { mutableFloatStateOf(0f) }
    var prevTimestamp by remember { mutableLongStateOf(0L) }
    var smoothedAccelMss by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(pointElapsedMs, rawSpeed) {
        val currentTimestamp = pointElapsedMs
        if (prevTimestamp > 0L && currentTimestamp > prevTimestamp) {
            val dtSec = (currentTimestamp - prevTimestamp) / 1000f
            if (dtSec in 0.1f..3.0f) {
                val dvMs = (rawSpeed - prevSpeed) * (1000f / 3600f)
                val rawAccel = dvMs / dtSec
                smoothedAccelMss = (smoothedAccelMss * 0.70f) + (rawAccel * 0.30f)
            }
        }
        prevSpeed = rawSpeed
        prevTimestamp = currentTimestamp
    }

    // Reset outside composition; reads below simply treat an invalid fix as zero acceleration.
    val accelValid = isGpsValid && rawSpeed >= 1.0f
    LaunchedEffect(accelValid) {
        if (!accelValid) smoothedAccelMss = 0f
    }
    val effectiveAccelMss = if (accelValid) smoothedAccelMss else 0f

    val isAccelerating = effectiveAccelMss > 0.30f
    val isDecelerating = effectiveAccelMss < -0.30f
    // Same 2.5 m/s² cut-off the trip charts count as a hard brake, so the chip and the trip agree.
    val isHardBraking = effectiveAccelMss < -2.5f

    val cardBg = CockpitTheme.colors.cardTopLightGradient
    val cardBorder = CockpitTheme.colors.border
    val cyanAccent = CockpitTheme.colors.accent
    val criticalColor = CockpitTheme.colors.critical
    val textPrimary = CockpitTheme.colors.textPrimary
    val textSecondary = CockpitTheme.colors.textSecondary

    // ==================== SPEEDOMETER CARD (240° dial) ====================
    Box(
        modifier = modifier
            .dashCardSurface()
            .padding(DashCard.Padding)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ==================== 1. CLUSTER HEADER (limit sign lives on the map) ====================
            DashCardHeader(icon = Icons.Default.Speed, title = "Speed")

            // ==================== 2. 240° STUDIO DIAL CANVAS ====================
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .offset(y = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                // The dial shrinks when the Fuel card grows (e.g. a fuel notice): step the readout
                // down so the number never collides with the arc.
                val compactDial = maxHeight < 200.dp
                Canvas(modifier = Modifier.fillMaxSize().graphicsLayer {}) { // own layer: dial redraws stay isolated
                    val w = size.width
                    val h = size.height
                    val dialRadius = (minOf(w, h) / 2f) * 0.88f
                    val center = Offset(w / 2f, h / 2f + dialRadius * 0.08f)

                    val startAngle = 150f
                    val sweepRange = 240f
                    val maxDisplaySpeed = 160f
                    val speedProgress = (animatedSpeedState.value / maxDisplaySpeed).coerceIn(0f, 1f)

                    // 1. Background Arc Track
                    drawArc(
                        color = Color.White.copy(alpha = 0.08f),
                        startAngle = startAngle,
                        sweepAngle = sweepRange,
                        useCenter = false,
                        topLeft = Offset(center.x - dialRadius, center.y - dialRadius),
                        size = Size(dialRadius * 2, dialRadius * 2),
                        style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
                    )

                    // 2. Active Glowing Speed Arc
                    val activeSweep = sweepRange * speedProgress
                    if (activeSweep > 0.5f) {
                        drawArc(
                            color = statusColor,
                            startAngle = startAngle,
                            sweepAngle = activeSweep,
                            useCenter = false,
                            topLeft = Offset(center.x - dialRadius, center.y - dialRadius),
                            size = Size(dialRadius * 2, dialRadius * 2),
                            style = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round)
                        )

                        // Head pip
                        val headAngleRad = Math.toRadians((startAngle + activeSweep).toDouble())
                        val pipCenter = Offset(
                            (center.x + dialRadius * cos(headAngleRad)).toFloat(),
                            (center.y + dialRadius * sin(headAngleRad)).toFloat()
                        )
                        drawCircle(
                            color = Color.White,
                            radius = 6.dp.toPx(),
                            center = pipCenter
                        )
                        drawCircle(
                            color = statusColor,
                            radius = 3.dp.toPx(),
                            center = pipCenter
                        )
                    }

                    // 3. Speed Limit Notch
                    if (speedLimitInfo.isApplicable && speedLimit in 10..160) {
                        val limitAngle = startAngle + sweepRange * (speedLimit / maxDisplaySpeed)
                        val limitRad = Math.toRadians(limitAngle.toDouble())
                        val notchStart = Offset(
                            (center.x + (dialRadius - 6.dp.toPx()) * cos(limitRad)).toFloat(),
                            (center.y + (dialRadius - 6.dp.toPx()) * sin(limitRad)).toFloat()
                        )
                        val notchEnd = Offset(
                            (center.x + (dialRadius + 6.dp.toPx()) * cos(limitRad)).toFloat(),
                            (center.y + (dialRadius + 6.dp.toPx()) * sin(limitRad)).toFloat()
                        )
                        drawLine(
                            color = criticalColor,
                            start = notchStart,
                            end = notchEnd,
                            strokeWidth = 3.5.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }
                }

                // Center Speed Readout
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = if (!isGpsValid && roundedSpeedInt < 1) "0" else "$roundedSpeedInt",
                        // Hero readout: 92 sp is ~45 arc-minutes at the driver's seat, readable from the back.
                        style = (if (compactDial) CockpitTheme.typography.displayLarge else CockpitTheme.typography.displayLarge.copy(fontSize = 92.sp, lineHeight = 92.sp))
                            .copy(shadow = com.antigravity.copilot.core.designsystem.numberGlow(textPrimary)),
                        color = textPrimary,
                        maxLines = 1
                    )
                    Text(
                        text = "km/h",
                        style = CockpitTheme.typography.labelLarge,
                        color = statusColor
                    )
                    Spacer(Modifier.height(if (compactDial) 4.dp else 8.dp))

                    // Prominent Dynamic Speed Trend Readout
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .clip(CopilotShapes.R8)
                            .background(
                                when {
                                    !isGpsValid -> CockpitTheme.colors.cardElevated
                                    isHardBraking -> criticalColor.copy(alpha = 0.15f)
                                    isAccelerating -> cyanAccent.copy(alpha = 0.15f)
                                    isDecelerating -> CockpitTheme.colors.warning.copy(alpha = 0.15f)
                                    else -> CockpitTheme.colors.cardElevated
                                }
                            )
                            .padding(
                                horizontal = 14.dp,
                                vertical = 6.dp
                            )
                    ) {
                        Icon(
                            imageVector = when {
                                !isGpsValid -> Icons.Default.GpsNotFixed
                                isHardBraking -> Icons.Default.Warning
                                isAccelerating -> Icons.AutoMirrored.Filled.TrendingUp
                                isDecelerating -> Icons.AutoMirrored.Filled.TrendingDown
                                else -> Icons.Default.SwapHoriz
                            },
                            contentDescription = null,
                            tint = when {
                                !isGpsValid -> textSecondary
                                isHardBraking -> criticalColor
                                isAccelerating -> cyanAccent
                                isDecelerating -> CockpitTheme.colors.warning
                                else -> textSecondary
                            },
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            // Plain words instead of m/s²: what the driver is doing, not the physics.
                            text = when {
                                !isGpsValid -> "No GPS"
                                isHardBraking -> "Hard braking"
                                isAccelerating -> "Speeding up"
                                isDecelerating -> "Slowing down"
                                rawSpeed < 1.0f -> "Stopped"
                                else -> "Steady"
                            },
                            style = CockpitTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            softWrap = false,
                            color = when {
                                !isGpsValid -> textSecondary
                                isHardBraking -> criticalColor
                                isAccelerating -> cyanAccent
                                isDecelerating -> CockpitTheme.colors.warning
                                else -> textPrimary
                            }
                        )
                    }
                }
            }

            // ==================== 3. TRIP DISTANCE + ODOMETER (tap odometer to calibrate) ====================
            DashStatStrip {
                // Km driven since the odometer was last calibrated (tap Odometer to recalibrate).
                StatCell(
                    label = "Since last sync",
                    value = if (deltaKmSinceCalibration > 0.05) String.format(Locale.US, "+%.1f", deltaKmSinceCalibration) else "0.0",
                    unit = "km",
                    valueStyle = CockpitTheme.typography.headlineMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp)
                )
                VDivider()
                StatCell(
                    label = "Odometer",
                    value = String.format(Locale.US, "%,.0f", currentOdometerKm),
                    unit = "km",
                    valueStyle = CockpitTheme.typography.headlineMedium,
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier
                        .weight(1f)
                        .clip(CopilotShapes.R6)
                        .clickable(role = Role.Button, onClickLabel = "Calibrate odometer") { showOdometerDialog = true }
                        .fillMaxHeight()
                        .wrapContentHeight(Alignment.CenterVertically)
                        .padding(horizontal = 10.dp)
                )
            }
        }
    }

    if (showOdometerDialog) {
        SpeedometerOdometerCalibrationDialog(
            currentOdometerKm = currentOdometerKm,
            lastSyncedKm = odoConfig.baselineOdometerKm,
            kmSinceSync = deltaKmSinceCalibration,
            onDismiss = { showOdometerDialog = false },
            onConfirmCalibration = { newKm ->
                // Through FuelRepository so the first calibration re-bases the fuel model.
                com.antigravity.copilot.core.fuel.FuelRepository
                    .getInstance(context)
                    .calibrateOdometer(newKm, totalLifetimeKm)
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
internal fun SpeedometerOdometerCalibrationDialog(
    currentOdometerKm: Double,
    lastSyncedKm: Double = 0.0,
    kmSinceSync: Double = 0.0,
    onDismiss: () -> Unit,
    onConfirmCalibration: (Double) -> Unit
) {
    var textValue by remember { mutableStateOf(String.format(Locale.US, "%.0f", currentOdometerKm)) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    /** A > 2,000 km jump needs a second tap (typo guard, audit G4). */
    var confirmedJumpFor by remember { mutableStateOf<Double?>(null) }

    RaahiDialogHost(onDismissRequest = onDismiss, maxWidth = 960.dp) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .raahiCard()
                .padding(24.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CopilotShapes.R12)
                                .background(CockpitTheme.colors.cardElevated),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = null,
                                tint = CockpitTheme.colors.accent,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Calibrate odometer",
                                style = CockpitTheme.typography.titleLarge,
                                color = CockpitTheme.colors.textPrimary
                            )
                            Text(
                                text = "Type the km shown on your car's meter",
                                style = CockpitTheme.typography.bodyMedium,
                                color = CockpitTheme.colors.textSecondary
                            )
                        }
                    }

                    // Delta Badge
                    val currentVal = textValue.toDoubleOrNull()
                    if (currentVal != null) {
                        val diff = currentVal - currentOdometerKm
                        val diffText = if (kotlin.math.abs(diff) < 0.5) "Matches your car ✓" else String.format(Locale.US, "%+,.0f km", diff)
                        val diffColor = if (kotlin.math.abs(diff) < 0.5) CockpitTheme.colors.positive else CockpitTheme.colors.warning
                        Box(
                            modifier = Modifier
                                .clip(Raahi.PillShape)
                                .background(diffColor.copy(alpha = 0.15f))
                                .border(1.dp, diffColor.copy(alpha = 0.6f), Raahi.PillShape)
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = diffText,
                                style = CockpitTheme.typography.headlineSmall,
                                color = CockpitTheme.colors.textPrimary
                            )
                        }
                    }
                }

                // Two-Pane Content: Left (Display, info, actions), Right (Large Numpad)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Left Pane: Readout, Info, Action Buttons
                    Column(
                        modifier = Modifier
                            .weight(0.55f),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // Odometer readout chamber
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .cockpitSurface(CockpitTheme.colors.cardElevated, CockpitTheme.colors.accent, CopilotShapes.R14, borderWidth = 1.dp)
                                .padding(horizontal = 18.dp, vertical = 18.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Odometer",
                                    style = CockpitTheme.typography.labelLarge,
                                    color = CockpitTheme.colors.textSecondary
                                )
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(
                                        text = textValue.ifEmpty { "0" },
                                        style = CockpitTheme.typography.displayMedium,
                                        maxLines = 1,
                                        softWrap = false,
                                        color = CockpitTheme.colors.textPrimary
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "km",
                                        style = CockpitTheme.typography.titleMedium,
                                        color = CockpitTheme.colors.textSecondary,
                                        modifier = Modifier.padding(bottom = 3.dp)
                                    )
                                }
                            }
                        }

                        Text(
                            text = "The app will match your car's meter from now on. Your fuel history does not change.",
                            style = CockpitTheme.typography.bodyMedium,
                            color = CockpitTheme.colors.textSecondary
                        )

                        // Fills the left column with what the driver needs to decide: last sync and km since.
                        if (lastSyncedKm > 0.0) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(CopilotShapes.R12)
                                    .background(CockpitTheme.colors.cardElevated)
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text("Last synced at", style = CockpitTheme.typography.labelMedium, color = CockpitTheme.colors.textSecondary)
                                    Text(String.format(Locale.US, "%,.0f km", lastSyncedKm), style = CockpitTheme.typography.headlineMedium, color = CockpitTheme.colors.textPrimary)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("Driven since", style = CockpitTheme.typography.labelMedium, color = CockpitTheme.colors.textSecondary)
                                    Text(String.format(Locale.US, "%,.0f km", kmSinceSync.coerceAtLeast(0.0)), style = CockpitTheme.typography.headlineMedium, color = CockpitTheme.colors.textPrimary)
                                }
                            }
                        }

                        if (errorMessage != null) {
                            Text(
                                text = errorMessage ?: "",
                                color = CockpitTheme.colors.criticalText,
                                style = CockpitTheme.typography.bodyMedium
                            )
                        }

                        Spacer(Modifier.weight(1f, fill = false))

                        // Bottom Actions
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .weight(0.40f)
                                    .height(56.dp),
                                shape = CopilotShapes.R12,
                                border = androidx.compose.foundation.BorderStroke(1.dp, CockpitTheme.colors.border)
                            ) {
                                Text(
                                    text = "Cancel",
                                    color = CockpitTheme.colors.textPrimary,
                                    style = CockpitTheme.typography.labelLarge
                                )
                            }

                            Button(
                                onClick = {
                                    val parsed = textValue.toDoubleOrNull()
                                    val check = if (parsed == null || parsed > 2_000_000.0) {
                                        com.antigravity.copilot.core.logic.OdometerReadingValidator.Result.INVALID
                                    } else {
                                        com.antigravity.copilot.core.logic.OdometerReadingValidator.validate(
                                            parsed, currentOdometerKm, isFirstReading = lastSyncedKm <= 0.0
                                        )
                                    }
                                    when {
                                        parsed == null || check == com.antigravity.copilot.core.logic.OdometerReadingValidator.Result.INVALID ->
                                            errorMessage = "Enter valid odometer (0 - 2,000,000 km)"
                                        check == com.antigravity.copilot.core.logic.OdometerReadingValidator.Result.TOO_LOW ->
                                            errorMessage = String.format(Locale.US, "Lower than the current %,.0f km. Check for a typo.", currentOdometerKm)
                                        check == com.antigravity.copilot.core.logic.OdometerReadingValidator.Result.NEEDS_CONFIRMATION && confirmedJumpFor != parsed -> {
                                            confirmedJumpFor = parsed
                                            errorMessage = String.format(Locale.US, "That is %,.0f km more than expected. Tap Save again to confirm.", parsed - currentOdometerKm)
                                        }
                                        else -> onConfirmCalibration(parsed)
                                    }
                                },
                                modifier = Modifier
                                    .weight(0.60f)
                                    .height(56.dp),
                                shape = CopilotShapes.R12,
                                colors = ButtonDefaults.buttonColors(containerColor = CockpitTheme.colors.accent)
                            ) {
                                Text(
                                    text = "Save reading",
                                    color = contentColorOn(CockpitTheme.colors.accent),
                                    style = CockpitTheme.typography.labelLarge
                                )
                            }
                        }
                    }

                    // Right Pane: Large Numpad like Add Fuel
                    Box(
                        modifier = Modifier
                            .weight(0.45f)
                            .cockpitSurface(CockpitTheme.colors.cardElevated, CockpitTheme.colors.border, CopilotShapes.R16, borderWidth = 1.dp)
                            .padding(10.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
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
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    row.forEach { key ->
                                        val isDel = key == "DEL"
                                        TactileKeypadButton(
                                            shape = CopilotShapes.R10,
                                            pressedFillAlpha = 0.25f,
                                            showRipple = true,
                                            textStyle = CockpitTheme.typography.headlineLarge.copy(fontFeatureSettings = "tnum"),
                                            text = if (isDel) "" else key,
                                            icon = if (isDel) Icons.AutoMirrored.Filled.Backspace else null,
                                            accentColor = CockpitTheme.colors.textPrimary,
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(60.dp),
                                            onClick = {
                                                errorMessage = null
                                                when (key) {
                                                    "DEL" -> if (textValue.isNotEmpty()) textValue = textValue.dropLast(1)
                                                    "." -> if (!textValue.contains(".")) textValue += "."
                                                    else -> textValue += key
                                                }
                                            }
                                        )
                                    }
                                }
                            }

                            // Bottom Row: CLR button
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                TactileKeypadButton(
                                    shape = CopilotShapes.R10,
                                    pressedFillAlpha = 0.25f,
                                    showRipple = true,
                                    textStyle = CockpitTheme.typography.headlineLarge.copy(fontFeatureSettings = "tnum"),
                                    text = "Clear",
                                    accentColor = CockpitTheme.colors.textPrimary,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp),
                                    onClick = {
                                        errorMessage = null
                                        textValue = ""
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Backward-compatibility alias for PremiumSpeedometerCluster.
 */
@Composable
fun PremiumSpeedometerCluster(
    speedKmh: Float,
    speedLimitInfo: SpeedLimitInfo,
    latestPoint: TripPoint?,
    modifier: Modifier = Modifier,
    allTrips: List<TripEntity> = emptyList()
) = SpeedometerCluster(
    speedKmh = speedKmh,
    speedLimitInfo = speedLimitInfo,
    latestPoint = latestPoint,
    modifier = modifier,
    allTrips = allTrips
)

