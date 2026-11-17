package com.antigravity.copilot.feature.dashboard

import com.antigravity.copilot.core.designsystem.components.contentColorOn
import com.antigravity.copilot.core.designsystem.components.cockpitSurface
import com.antigravity.copilot.core.designsystem.components.TactileKeypadButton
import com.antigravity.copilot.core.designsystem.tokens.CopilotShapes
import com.antigravity.copilot.core.designsystem.components.Raahi
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
import com.antigravity.copilot.core.fuel.intelligence.BestMileageSpeedEngine
import com.antigravity.copilot.core.fuel.intelligence.BestMileageSpeedResult
import com.antigravity.copilot.core.fuel.intelligence.EfficiencyLearningStatus
import com.antigravity.copilot.core.location.RoadSurfaceCondition
import com.antigravity.copilot.core.location.SpeedLimitInfo
import com.antigravity.copilot.core.location.StoppingDistanceEngine
import com.antigravity.copilot.core.location.StoppingDistanceEstimate
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
    totalLifetimeKm: Double = 0.0
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
    var tickerTimeMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val now = System.currentTimeMillis()
    val isGpsPointFresh = latestPoint != null &&
            latestPoint.latitude != 0.0 &&
            latestPoint.longitude != 0.0 &&
            latestPoint.accuracy < 60f

    // Continuously latch latest genuine speed reading
    if (isGpsPointFresh && latestPoint != null && (now - latestPoint.timestamp) < 2000L && speedKmh >= 0f) {
        lastValidSpeedKmh = speedKmh
        lastValidSpeedTimeMs = latestPoint.timestamp
    }

    val gapMs = if (lastValidSpeedTimeMs > 0L) (now - lastValidSpeedTimeMs).coerceAtLeast(0L) else 0L
    val isGpsValid = isGpsPointFresh && gapMs <= 10000L

    val rawSpeed = calculateSpeedometerDecay(lastValidSpeedKmh, gapMs)

    // Decay Animation Driver: updates tickerTimeMs every 100ms while decaying to 0
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

    // Kept as State: the dial reads it in the draw phase (redraw only, no recomposition) and the
    // text reads a derived integer, so the card recomposes once per km/h, not on every frame.
    val animatedSpeedState = animateFloatAsState(
        targetValue = rawSpeed,
        animationSpec = tween(durationMillis = 200, easing = LinearEasing),
        label = "PremiumSpeedInterpolation"
    )

    // Interactive Dialog States
    var showStoppingDistanceDialog by remember { mutableStateOf(false) }
    var showBestEcoDialog by remember { mutableStateOf(false) }

    val roundedSpeedInt by remember { derivedStateOf { animatedSpeedState.value.roundToInt() } }
    val stoppingEstimate: StoppingDistanceEstimate = remember(roundedSpeedInt) {
        StoppingDistanceEngine.calculate(roundedSpeedInt.toFloat(), RoadSurfaceCondition.DRY)
    }

    val bestEcoResult: BestMileageSpeedResult = remember(allTrips) {
        BestMileageSpeedEngine.analyze(allTrips)
    }

    // Dynamic Multi-Stage Speed Status Colors
    val statusColor by animateColorAsState(
        targetValue = when {
            speedLimit > 0 && rawSpeed > speedLimit * 1.05f -> CockpitTheme.colors.critical
            speedLimit > 0 && rawSpeed > speedLimit * 0.92f -> CockpitTheme.colors.warning
            else -> CockpitTheme.colors.accent
        },
        animationSpec = tween(350),
        label = "PremiumSpeedStatusColor"
    )

    // GPS Acceleration & Speed Trend
    var prevSpeed by remember { mutableFloatStateOf(0f) }
    var prevTimestamp by remember { mutableLongStateOf(0L) }
    var smoothedAccelMss by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(latestPoint?.timestamp, rawSpeed) {
        val currentTimestamp = latestPoint?.timestamp ?: now
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

    if (!isGpsValid || rawSpeed < 1.0f) {
        smoothedAccelMss = 0f
    }

    val isAccelerating = smoothedAccelMss > 0.30f
    val isDecelerating = smoothedAccelMss < -0.30f

    val cardBg = CockpitTheme.colors.cardTopLightGradient
    val cardBorder = CockpitTheme.colors.border
    val cyanAccent = CockpitTheme.colors.accent
    val criticalColor = CockpitTheme.colors.critical
    val textPrimary = CockpitTheme.colors.textPrimary
    val textSecondary = CockpitTheme.colors.textSecondary

    // ==================== SPEEDOMETER CARD (240° dial) ====================
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(AutomotiveDesignTokens.Radii.card))
            .background(cardBg)
            .border(
                width = 1.25.dp,
                color = cardBorder,
                shape = RoundedCornerShape(AutomotiveDesignTokens.Radii.card)
            )
            .padding(14.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ==================== 1. SPEED LIMIT SIGN & CLUSTER HEADER ====================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CopilotShapes.R6)
                            .background(cyanAccent.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            tint = cyanAccent,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Text(
                        text = "SPEEDOMETER",
                        style = CockpitTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.1.sp
                        ),
                        color = textSecondary,
                        maxLines = 1
                    )
                }

                if (speedLimit > 0) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .cockpitSurface(Color.White, CockpitTheme.colors.critical, CircleShape, borderWidth = 2.5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "$speedLimit",
                            style = CockpitTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Black
                            ),
                            color = Color.Black
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.size(32.dp))
                }
            }

            // ==================== 2. 240° STUDIO DIAL CANVAS ====================
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 14.dp)
                    .offset(y = 6.dp),
                contentAlignment = Alignment.Center
            ) {
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
                    if (speedLimit in 10..160) {
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
                        style = CockpitTheme.typography.displayLarge,
                        color = textPrimary
                    )
                    Text(
                        text = "KM/H",
                        style = CockpitTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.0.sp
                        ),
                        color = statusColor
                    )
                    Spacer(Modifier.height(6.dp))

                    // Prominent Dynamic Speed Trend Readout
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .clip(CopilotShapes.R8)
                            .background(
                                when {
                                    !isGpsValid -> CockpitTheme.colors.cardElevated
                                    isAccelerating -> cyanAccent.copy(alpha = 0.15f)
                                    isDecelerating -> CockpitTheme.colors.warning.copy(alpha = 0.15f)
                                    else -> CockpitTheme.colors.cardElevated
                                }
                            )
                            .padding(
                                horizontal = 12.dp,
                                vertical = 4.dp
                            )
                    ) {
                        Icon(
                            imageVector = when {
                                !isGpsValid -> Icons.Default.GpsNotFixed
                                isAccelerating -> Icons.AutoMirrored.Filled.TrendingUp
                                isDecelerating -> Icons.AutoMirrored.Filled.TrendingDown
                                else -> Icons.Default.SwapHoriz
                            },
                            contentDescription = null,
                            tint = when {
                                !isGpsValid -> textSecondary
                                isAccelerating -> cyanAccent
                                isDecelerating -> CockpitTheme.colors.warning
                                else -> textSecondary
                            },
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = when {
                                !isGpsValid -> "-- GPS"
                                isAccelerating -> "+${String.format(Locale.US, "%.1f", smoothedAccelMss)} m/s² ACCEL"
                                isDecelerating -> "${String.format(Locale.US, "%.1f", smoothedAccelMss)} m/s² DECEL"
                                rawSpeed < 1.0f -> "0.0 m/s²"
                                else -> "STEADY"
                            },
                            style = CockpitTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.4.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            softWrap = false,
                            color = when {
                                !isGpsValid -> textSecondary
                                isAccelerating -> cyanAccent
                                isDecelerating -> CockpitTheme.colors.warning
                                else -> textPrimary
                            }
                        )
                    }
                }
            }
        }
    }

    // Modal Dialog 1: Stopping Distance Details
    if (showStoppingDistanceDialog) {
        Dialog(onDismissRequest = { showStoppingDistanceDialog = false }) {
            Box(
                modifier = Modifier
                    .width(360.dp)
                    .cockpitSurface(CockpitTheme.colors.card, CockpitTheme.colors.border, CopilotShapes.R18)
                    .padding(20.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Security, null, tint = cyanAccent, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Estimated Stopping Distance",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = textPrimary
                        )
                    }

                    Text(
                        text = "Physics-based estimate combining reaction time (1.0s) and dry asphalt braking deceleration (μ = 0.72).",
                        style = MaterialTheme.typography.bodySmall,
                        color = textSecondary
                    )

                    HorizontalDivider(color = CockpitTheme.colors.border)

                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        Text("Current Velocity:", style = MaterialTheme.typography.bodySmall, color = textSecondary)
                        Text("$roundedSpeedInt km/h", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = textPrimary)
                    }
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        Text("Reaction Distance:", style = MaterialTheme.typography.bodySmall, color = textSecondary)
                        Text("~${stoppingEstimate.reactionDistanceMeters.roundToInt()} m", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = textPrimary)
                    }
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        Text("Braking Distance:", style = MaterialTheme.typography.bodySmall, color = textSecondary)
                        Text("~${stoppingEstimate.brakingDistanceMeters.roundToInt()} m", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = textPrimary)
                    }

                    HorizontalDivider(color = CockpitTheme.colors.border)

                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        Text("Total Estimated Distance:", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = cyanAccent)
                        Text(stoppingEstimate.formattedTotalMeters, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black), color = cyanAccent)
                    }

                    Row(Modifier.fillMaxWidth(), Arrangement.End) {
                        Button(
                            onClick = { showStoppingDistanceDialog = false },
                            colors = ButtonDefaults.buttonColors(containerColor = cyanAccent)
                        ) {
                            Text("Close", color = contentColorOn(cyanAccent), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // Modal Dialog 2: Best Eco Speed Analysis
    if (showBestEcoDialog) {
        Dialog(onDismissRequest = { showBestEcoDialog = false }) {
            Box(
                modifier = Modifier
                    .width(360.dp)
                    .cockpitSurface(CockpitTheme.colors.card, CockpitTheme.colors.border, CopilotShapes.R18)
                    .padding(20.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Eco, null, tint = CockpitTheme.colors.accent, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Best Mileage Speed Analysis",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = textPrimary
                        )
                    }

                    Text(
                        text = "Learns the specific cruising speed range where this vehicle achieves maximum fuel economy using validated historical trips.",
                        style = MaterialTheme.typography.bodySmall,
                        color = textSecondary
                    )

                    HorizontalDivider(color = CockpitTheme.colors.border)

                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        Text("Learning Status:", style = MaterialTheme.typography.bodySmall, color = textSecondary)
                        Text(
                            bestEcoResult.status.name.replace('_', ' '),
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = if (bestEcoResult.status == EfficiencyLearningStatus.CONFIRMED) CockpitTheme.colors.accent else CockpitTheme.colors.warning
                        )
                    }

                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        Text("Best Cruising Range:", style = MaterialTheme.typography.bodySmall, color = textSecondary)
                        Text(
                            bestEcoResult.recommendedBand?.label ?: "Collecting more trips",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = textPrimary
                        )
                    }

                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        Text("Statistical Confidence:", style = MaterialTheme.typography.bodySmall, color = textSecondary)
                        Text(
                            "${bestEcoResult.confidencePercent}%",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = cyanAccent
                        )
                    }

                    Row(Modifier.fillMaxWidth(), Arrangement.End) {
                        Button(
                            onClick = { showBestEcoDialog = false },
                            colors = ButtonDefaults.buttonColors(containerColor = CockpitTheme.colors.accent)
                        ) {
                            Text("Done", color = contentColorOn(CockpitTheme.colors.accent), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

}

/**
 * Interactive dialog allowing the driver to calibrate and sync vehicle odometer
 * with physical dashboard cluster reading.
 */
@Composable
internal fun SpeedometerOdometerCalibrationDialog(
    currentOdometerKm: Double,
    onDismiss: () -> Unit,
    onConfirmCalibration: (Double) -> Unit
) {
    var textValue by remember { mutableStateOf(String.format(Locale.US, "%.1f", currentOdometerKm)) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

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
                                text = "Enter the reading shown on the car's instrument cluster",
                                style = CockpitTheme.typography.bodyMedium,
                                color = CockpitTheme.colors.textSecondary
                            )
                        }
                    }

                    // Delta Badge
                    val currentVal = textValue.toDoubleOrNull()
                    if (currentVal != null) {
                        val diff = currentVal - currentOdometerKm
                        val diffText = if (kotlin.math.abs(diff) < 0.05) "Exact Match" else String.format(Locale.US, "%+.1f km", diff)
                        val diffColor = if (kotlin.math.abs(diff) < 0.05) CockpitTheme.colors.positive else CockpitTheme.colors.warning
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
                                        text = textValue.ifEmpty { "0.0" },
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
                            text = "GPS distance is scaled to match this reading. The first calibration only sets the starting point and never counts as fuel used.",
                            style = CockpitTheme.typography.bodyMedium,
                            color = CockpitTheme.colors.textSecondary
                        )

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
                                    if (parsed == null || parsed < 0.0 || parsed > 2_000_000.0) {
                                        errorMessage = "Enter valid odometer (0 - 2,000,000 km)"
                                    } else {
                                        onConfirmCalibration(parsed)
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

