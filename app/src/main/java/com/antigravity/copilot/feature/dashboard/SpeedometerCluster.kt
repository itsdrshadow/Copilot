package com.antigravity.copilot.feature.dashboard

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

    val animatedSpeed by animateFloatAsState(
        targetValue = rawSpeed,
        animationSpec = tween(durationMillis = 200, easing = LinearEasing),
        label = "PremiumSpeedInterpolation"
    )

    // Interactive Dialog States
    var showStoppingDistanceDialog by remember { mutableStateOf(false) }
    var showBestEcoDialog by remember { mutableStateOf(false) }

    val roundedSpeedInt = animatedSpeed.roundToInt()
    val stoppingEstimate: StoppingDistanceEstimate = remember(roundedSpeedInt) {
        StoppingDistanceEngine.calculate(roundedSpeedInt.toFloat(), RoadSurfaceCondition.DRY)
    }

    val bestEcoResult: BestMileageSpeedResult = remember(allTrips) {
        BestMileageSpeedEngine.analyze(allTrips)
    }

    // Dynamic Multi-Stage Speed Status Colors
    val statusColor by animateColorAsState(
        targetValue = when {
            speedLimit > 0 && animatedSpeed > speedLimit * 1.05f -> CockpitTheme.colors.critical
            speedLimit > 0 && animatedSpeed > speedLimit * 0.92f -> CockpitTheme.colors.warning
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
                            .clip(RoundedCornerShape(6.dp))
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
                            .clip(CircleShape)
                            .background(Color.White)
                            .border(2.5.dp, Premium_Danger, CircleShape),
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
                    .fillMaxWidth(),
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
                        text = if (!isGpsValid && animatedSpeed < 1.0f) "0" else "${animatedSpeed.roundToInt()}",
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
                            .clip(RoundedCornerShape(8.dp))
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
                                rawSpeed < 1.0f -> "IDLE • 0.0 m/s²"
                                else -> "CRUISING • STEADY"
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

            // ==================== 3. BOTTOM TELEMETRY DOCK (ODOMETER + SINCE CAL) ====================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(CockpitTheme.colors.dockActionBrush)
                    .border(1.dp, CockpitTheme.colors.border, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Cell 1: ODOMETER (Tap to calibrate)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { showOdometerDialog = true },
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = String.format(Locale.US, "%,.1f km", currentOdometerKm),
                        style = CockpitTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Black,
                            fontFeatureSettings = "tnum"
                        ),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        color = textPrimary
                    )
                    Text(
                        text = "ODOMETER",
                        style = CockpitTheme.typography.labelSmall,
                        maxLines = 1,
                        softWrap = false,
                        color = textSecondary
                    )
                }

                Box(
                    modifier = Modifier
                        .height(24.dp)
                        .width(1.dp)
                        .background(CockpitTheme.colors.border.copy(alpha = 0.6f))
                )

                // Cell 2: SINCE CAL. (Delta since calibration, tap to sync)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { showOdometerDialog = true },
                    horizontalAlignment = Alignment.End
                ) {
                    val deltaText = String.format(Locale.US, "+%.1f km", deltaKmSinceCalibration.coerceAtLeast(0.0))
                    Text(
                        text = deltaText,
                        style = CockpitTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Black,
                            fontFeatureSettings = "tnum"
                        ),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        color = cyanAccent
                    )
                    Text(
                        text = "SINCE CAL.",
                        style = CockpitTheme.typography.labelSmall,
                        maxLines = 1,
                        softWrap = false,
                        color = textSecondary
                    )
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
                    .clip(RoundedCornerShape(18.dp))
                    .background(CockpitTheme.colors.card)
                    .border(1.dp, CockpitTheme.colors.border, RoundedCornerShape(18.dp))
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
                        Text("${animatedSpeed.roundToInt()} km/h", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = textPrimary)
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
                            Text("Close", color = CockpitTheme.colors.onAccent, fontWeight = FontWeight.Bold)
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
                    .clip(RoundedCornerShape(18.dp))
                    .background(CockpitTheme.colors.card)
                    .border(1.dp, CockpitTheme.colors.border, RoundedCornerShape(18.dp))
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
                            Text("Done", color = CockpitTheme.colors.onAccent, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // Modal Dialog 3: Vehicle Odometer Calibration
    if (showOdometerDialog) {
        SpeedometerOdometerCalibrationDialog(
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
private fun SpeedometerOdometerCalibrationDialog(
    currentOdometerKm: Double,
    onDismiss: () -> Unit,
    onConfirmCalibration: (Double) -> Unit
) {
    var textValue by remember { mutableStateOf(String.format(Locale.US, "%.1f", currentOdometerKm)) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(780.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(CockpitTheme.colors.card)
                .border(1.5.dp, CockpitTheme.colors.border, RoundedCornerShape(22.dp))
                .padding(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
                                .size(42.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(CockpitTheme.colors.accentSoft),
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
                                text = "Calibrate Vehicle Odometer",
                                style = CockpitTheme.typography.headlineMedium,
                                color = CockpitTheme.colors.textPrimary
                            )
                            Text(
                                text = "Sync with physical car dashboard cluster reading",
                                style = CockpitTheme.typography.bodySmall,
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
                                .clip(RoundedCornerShape(8.dp))
                                .background(diffColor.copy(alpha = 0.15f))
                                .border(1.2.dp, diffColor.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = diffText,
                                style = CockpitTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Black,
                                    fontFeatureSettings = "tnum"
                                ),
                                color = diffColor
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
                                .clip(RoundedCornerShape(12.dp))
                                .background(CockpitTheme.colors.cardElevated)
                                .border(1.5.dp, CockpitTheme.colors.accent.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "ODOMETER",
                                    style = CockpitTheme.typography.labelMedium.copy(
                                        letterSpacing = 0.5.sp
                                    ),
                                    color = CockpitTheme.colors.textSecondary
                                )
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(
                                        text = textValue.ifEmpty { "0.0" },
                                        style = CockpitTheme.typography.displaySmall.copy(
                                            fontFeatureSettings = "tnum"
                                        ),
                                        color = CockpitTheme.colors.textPrimary
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "KM",
                                        style = CockpitTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Black
                                        ),
                                        color = CockpitTheme.colors.accent,
                                        modifier = Modifier.padding(bottom = 3.dp)
                                    )
                                }
                            }
                        }

                        Text(
                            text = "Enter physical dashboard odometer reading. GPS trip tracking and lifetime distance will automatically calibrate.",
                            style = CockpitTheme.typography.bodySmall,
                            color = CockpitTheme.colors.textSecondary
                        )

                        if (errorMessage != null) {
                            Text(
                                text = errorMessage ?: "",
                                color = CockpitTheme.colors.critical,
                                style = CockpitTheme.typography.bodySmall.copy(
                                    fontWeight = FontWeight.Bold
                                )
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
                                    .height(50.dp),
                                shape = RoundedCornerShape(10.dp),
                                border = androidx.compose.foundation.BorderStroke(1.2.dp, CockpitTheme.colors.border)
                            ) {
                                Text(
                                    text = "Cancel",
                                    color = CockpitTheme.colors.textSecondary,
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
                                    .height(50.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = CockpitTheme.colors.accent)
                            ) {
                                Text(
                                    text = "CONFIRM",
                                    color = CockpitTheme.colors.onAccent,
                                    style = CockpitTheme.typography.labelLarge.copy(fontWeight = FontWeight.Black)
                                )
                            }
                        }
                    }

                    // Right Pane: Large Numpad like Add Fuel
                    Box(
                        modifier = Modifier
                            .weight(0.45f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(CockpitTheme.colors.cardElevated)
                            .border(1.2.dp, CockpitTheme.colors.border, RoundedCornerShape(14.dp))
                            .padding(8.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
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
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    row.forEach { key ->
                                        val isDel = key == "DEL"
                                        OdometerTactileKeypadButton(
                                            text = if (isDel) "" else key,
                                            icon = if (isDel) Icons.AutoMirrored.Filled.Backspace else null,
                                            accentColor = if (isDel) CockpitTheme.colors.critical else CockpitTheme.colors.textPrimary,
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(52.dp),
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
                                OdometerTactileKeypadButton(
                                    text = "CLR",
                                    accentColor = CockpitTheme.colors.critical,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp),
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

@Composable
private fun OdometerTactileKeypadButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = Color.White,
    icon: ImageVector? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1.0f,
        animationSpec = tween(90),
        label = "OdoKeyScale"
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isPressed) accentColor.copy(alpha = 0.25f) else CockpitTheme.colors.card
            )
            .border(
                1.2.dp,
                if (isPressed) accentColor.copy(alpha = 0.85f) else CockpitTheme.colors.border,
                RoundedCornerShape(10.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = rememberRipple(color = accentColor),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(24.dp)
            )
        } else {
            Text(
                text = text,
                style = CockpitTheme.typography.headlineLarge.copy(
                    fontFeatureSettings = "tnum"
                ),
                color = accentColor
            )
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

