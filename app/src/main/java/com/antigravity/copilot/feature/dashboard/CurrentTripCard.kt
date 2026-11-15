package com.antigravity.copilot.feature.dashboard

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.copilot.core.designsystem.*
import com.antigravity.copilot.core.model.TripStatus
import com.antigravity.copilot.core.model.TripSummary
import com.antigravity.copilot.core.trip.TripVisualLifecycleEvent
import com.antigravity.copilot.core.trip.TripVisualState
import com.antigravity.copilot.core.util.NumberFormatUtils
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PremiumRotatingMetric(
    val id: String,
    val title: String,
    val value: String,
    val unit: String,
    val subtitle: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val accentColor: Color
)

/**
 * Current Trip Instrument Card:
 * Features live dynamic trip duration timer, rotating metrics, peak braking deceleration, and tactile status indicators.
 */
@Composable
fun CurrentTripCard(
    currentTrip: TripSummary,
    tripVisualState: TripVisualState = TripVisualState(),
    modifier: Modifier = Modifier,
    baselineMileage: Double = 18.0
) {
    val cardBg = CockpitTheme.colors.cardTopLightGradient
    val cardBorder = CockpitTheme.colors.border
    val cyanAccent = CockpitTheme.colors.accent
    val positiveAccent = CockpitTheme.colors.accent
    val warningAccent = CockpitTheme.colors.warning
    val dangerAccent = CockpitTheme.colors.critical
    val textPrimary = CockpitTheme.colors.textPrimary
    val textSecondary = CockpitTheme.colors.textSecondary

    val isTripActive = currentTrip.status == TripStatus.ACTIVE_TRIP ||
            currentTrip.status == TripStatus.MOVEMENT_CANDIDATE ||
            currentTrip.status == TripStatus.TEMPORARY_STOP

    val isTripCompleted = !isTripActive && (
            currentTrip.status == TripStatus.COMPLETED ||
            (currentTrip.endTime > 0L && currentTrip.endTime >= currentTrip.startTime)
    )

    val isVisualFlashing = (tripVisualState.event == TripVisualLifecycleEvent.STARTED ||
            tripVisualState.event == TripVisualLifecycleEvent.COMPLETED) &&
            (System.currentTimeMillis() - tripVisualState.eventTimestamp < 10_000L)

    // Live 1-second elapsed trip timer & visual lifecycle dismiss ticker
    var liveWallTimeMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(isTripActive, tripVisualState.eventTimestamp) {
        if (isTripActive || isVisualFlashing) {
            while (isTripActive || (System.currentTimeMillis() - tripVisualState.eventTimestamp < 10_000L)) {
                delay(1000L)
                liveWallTimeMs = System.currentTimeMillis()
            }
        }
    }

    val totalDurationMs = (currentTrip.drivingDurationMs + currentTrip.stationaryDurationMs)
    val elapsedDurationMs = when {
        isTripCompleted -> {
            if (currentTrip.endTime > currentTrip.startTime && currentTrip.startTime > 0L) {
                (currentTrip.endTime - currentTrip.startTime).coerceAtLeast(0L)
            } else {
                totalDurationMs.coerceAtLeast(0L)
            }
        }
        isTripActive && currentTrip.startTime > 0L -> {
            (liveWallTimeMs - currentTrip.startTime).coerceAtLeast(0L)
        }
        else -> 0L
    }

    val liveTripTimerText = remember(elapsedDurationMs) {
        com.antigravity.copilot.core.trip.TimeFormatUtils.formatLiveTripTimer(elapsedDurationMs)
    }

    // Dynamic Rotating Trip Metrics Carousel
    val metrics = remember(currentTrip, baselineMileage) {
        listOf(
            PremiumRotatingMetric(
                id = "trip_distance",
                title = "TRIP DISTANCE",
                value = NumberFormatUtils.formatDistance(currentTrip.distanceMeters / 1000.0),
                unit = "KM",
                subtitle = "Distance covered in current trip",
                icon = Icons.Default.Route,
                accentColor = cyanAccent
            ),
            PremiumRotatingMetric(
                id = "max_speed",
                title = "MAX SPEED",
                value = "${currentTrip.maxSpeedKmh.toInt()}",
                unit = "KM/H",
                subtitle = "Peak velocity during current journey",
                icon = Icons.Default.Speed,
                accentColor = cyanAccent
            ),
            PremiumRotatingMetric(
                id = "trip_mileage",
                title = "TRIP ECONOMY",
                value = NumberFormatUtils.formatMileage(
                    if (currentTrip.calculatedMileageKmpl > 0.0) currentTrip.calculatedMileageKmpl else baselineMileage,
                    2
                ),
                unit = "KM/L",
                subtitle = "Estimated efficiency for this trip",
                icon = Icons.Default.Eco,
                accentColor = positiveAccent
            ),
            PremiumRotatingMetric(
                id = "peak_braking",
                title = "PEAK BRAKING",
                value = if (currentTrip.maxDecelMss < -0.1f) NumberFormatUtils.formatPrecision(currentTrip.maxDecelMss, 2) else "0",
                unit = "M/S²",
                subtitle = "Maximum deceleration force",
                icon = Icons.Default.Warning,
                accentColor = if (currentTrip.maxDecelMss < -4.5f) dangerAccent else warningAccent
            )
        )
    }

    var activeMetricIndex by remember { mutableIntStateOf(0) }
    var isUserInteracting by remember { mutableStateOf(false) }

    LaunchedEffect(isUserInteracting) {
        if (!isUserInteracting) {
            while (true) {
                delay(7000L)
                activeMetricIndex = (activeMetricIndex + 1) % metrics.size
            }
        }
    }

    // Trip lifecycle flash animations (Start / End)
    val elapsedSinceEventMs = (liveWallTimeMs - tripVisualState.eventTimestamp).coerceAtLeast(0L)
    val isFlashingGreen = tripVisualState.event == TripVisualLifecycleEvent.STARTED && elapsedSinceEventMs < 10_000L
    val isFlashingRed = tripVisualState.event == TripVisualLifecycleEvent.COMPLETED && elapsedSinceEventMs < 10_000L

    val flashPulseTransition = rememberInfiniteTransition(label = "FlashPulse")
    val flashPulseAlpha by flashPulseTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseAlpha"
    )

    val flashColor by animateColorAsState(
        targetValue = when {
            isFlashingGreen -> positiveAccent.copy(alpha = 0.35f)
            isFlashingRed -> dangerAccent.copy(alpha = 0.35f)
            else -> Color.Transparent
        },
        animationSpec = tween(400),
        label = "PremiumTripFlash"
    )

    val tripStartTimeFormatted = remember(currentTrip.startTime) {
        if (currentTrip.startTime > 0L) {
            SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(currentTrip.startTime))
        } else ""
    }

    val (statusLabel, statusColor, statusIcon) = when {
        isFlashingGreen -> Triple("TRIP STARTED", positiveAccent, Icons.Default.PlayArrow)
        isFlashingRed -> Triple("TRIP ENDED", dangerAccent, Icons.Default.Flag)
        isTripActive -> {
            val label = if (tripStartTimeFormatted.isNotEmpty()) "LIVE • $tripStartTimeFormatted" else "LIVE TRIP"
            Triple(label, positiveAccent, Icons.Default.Navigation)
        }
        currentTrip.status == TripStatus.TEMPORARY_STOP -> Triple("PAUSED", warningAccent, Icons.Default.Pause)
        isTripCompleted -> Triple("COMPLETED", positiveAccent, Icons.Default.CheckCircle)
        else -> Triple("PARKED", textSecondary, Icons.Default.LocalParking)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(AutomotiveDesignTokens.Radii.card))
            .background(cardBg)
            .border(
                width = 1.25.dp,
                color = if (flashColor != Color.Transparent) flashColor else cardBorder,
                shape = RoundedCornerShape(AutomotiveDesignTokens.Radii.card)
            )
            .padding(14.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // ==================== 1. CARD HEADER ====================
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                            .background(statusColor.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Text(
                        text = "CURRENT TRIP",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.1.sp,
                            fontSize = 14.5.sp
                        ),
                        color = textSecondary,
                        maxLines = 1
                    )
                }

                // Automotive Status Icon Telltale (Active vs Completed vs Parked)
                Box(
                    modifier = Modifier
                        .height(32.dp)
                        .defaultMinSize(minWidth = 32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(statusColor.copy(alpha = 0.14f))
                        .border(1.dp, statusColor.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        if (isTripActive) {
                            // Pulsing live green beacon dot
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(positiveAccent)
                            )
                        }
                        Icon(
                            imageVector = statusIcon,
                            contentDescription = statusLabel,
                            tint = statusColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // ==================== 2. ROTATING TRIP METRIC HERO ====================
            AnimatedContent(
                targetState = activeMetricIndex,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(220)) + slideInHorizontally { width -> width / 3 })
                        .togetherWith(fadeOut(animationSpec = tween(200)) + slideOutHorizontally { width -> -width / 3 })
                },
                label = "PremiumTripMetricHero",
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 2.dp)
            ) { targetIndex ->
                val target = metrics.getOrElse(targetIndex) { metrics[0] }
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = target.icon,
                            contentDescription = null,
                            tint = target.accentColor,
                            modifier = Modifier.size(17.dp)
                        )
                        Text(
                            text = target.title,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp,
                                fontSize = 13.5.sp
                            ),
                            color = textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(Modifier.height(2.dp))

                    AdaptiveMetricValueText(
                        value = target.value,
                        unit = target.unit,
                        preferredFontSize = AutomotiveDesignTokens.Typography.heroMetric,
                        minFontSize = 24.sp,
                        valueColor = textPrimary,
                        unitColor = target.accentColor,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(2.dp))

                    Text(
                        text = target.subtitle,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 13.sp
                        ),
                        color = textSecondary.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // ==================== 3. CAROUSEL DOTS INDICATOR ====================
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                metrics.forEachIndexed { index, _ ->
                    val isSelected = index == activeMetricIndex
                    val dotWidth by animateDpAsState(
                        targetValue = if (isSelected) 14.dp else 4.dp,
                        animationSpec = tween(220),
                        label = "PremiumTripDotWidth"
                    )
                    Box(
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .clickable {
                                isUserInteracting = true
                                activeMetricIndex = index
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 2.dp)
                                .height(4.dp)
                                .width(dotWidth)
                                .clip(CircleShape)
                                .background(if (isSelected) cyanAccent else Color.White.copy(alpha = 0.18f))
                        )
                    }
                }
            }

            // ==================== 4. BOTTOM TELEMETRY DOCK (TRIP TIMER & STATUS) ====================
            val dockBg = when {
                isFlashingGreen -> positiveAccent.copy(alpha = 0.22f * flashPulseAlpha)
                isFlashingRed -> dangerAccent.copy(alpha = 0.22f * flashPulseAlpha)
                else -> CockpitTheme.colors.cardElevated
            }
            val dockBorderColor = when {
                isFlashingGreen -> positiveAccent.copy(alpha = flashPulseAlpha)
                isFlashingRed -> dangerAccent.copy(alpha = flashPulseAlpha)
                else -> CockpitTheme.colors.border
            }
            val dockBorderWidth = if (isFlashingGreen || isFlashingRed) 1.8.dp else 1.dp

            val timerTextColor = when {
                isFlashingGreen -> positiveAccent
                isFlashingRed -> dangerAccent
                isTripActive -> positiveAccent
                else -> textPrimary
            }
            val isMultiDayElapsed = elapsedDurationMs >= 86_400_000L
            val timerLabelText = when {
                isFlashingGreen -> "TRIP STARTED"
                isFlashingRed -> "TRIP COMPLETED"
                isTripCompleted -> if (isMultiDayElapsed) "JOURNEY ELAPSED" else "TRIP DURATION"
                isTripActive -> if (isMultiDayElapsed) "JOURNEY ELAPSED" else "ELAPSED TIME"
                else -> "TRIP DURATION"
            }
            val timerLabelColor = when {
                isFlashingGreen -> positiveAccent
                isFlashingRed -> dangerAccent
                isTripCompleted -> positiveAccent
                else -> textSecondary
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(dockBg)
                    .border(dockBorderWidth, dockBorderColor, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Cell 1: Elapsed Timer with Visual Flash
                Column(modifier = Modifier.weight(1.2f)) {
                    Text(
                        text = liveTripTimerText,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Black,
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily,
                            fontFeatureSettings = "tnum",
                            fontSize = 20.sp
                        ),
                        maxLines = 1,
                        softWrap = false,
                        color = timerTextColor
                    )
                    Text(
                        text = timerLabelText,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        ),
                        maxLines = 1,
                        softWrap = false,
                        color = timerLabelColor
                    )
                }

                Box(
                    modifier = Modifier
                        .height(20.dp)
                        .width(1.dp)
                        .background(Color.White.copy(alpha = 0.12f))
                )

                // Cell 2: Active / Moving Time
                Column(
                    modifier = Modifier.weight(1.0f),
                    horizontalAlignment = Alignment.End
                ) {
                    val drivingMs = currentTrip.drivingDurationMs
                    val movingText = if (drivingMs > 0L) {
                        com.antigravity.copilot.core.trip.TimeFormatUtils.formatAdaptiveDuration(drivingMs)
                    } else if (isTripActive) {
                        "ACTIVE"
                    } else {
                        "0m"
                    }
                    Text(
                        text = movingText,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Black,
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily,
                            fontFeatureSettings = "tnum",
                            fontSize = 17.sp
                        ),
                        maxLines = 1,
                        softWrap = false,
                        color = if (isTripActive) positiveAccent else cyanAccent
                    )
                    Text(
                        text = "DRIVING TIME",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        ),
                        maxLines = 1,
                        softWrap = false,
                        color = textSecondary
                    )
                }
            }
        }
    }
}

/**
 * Backward-compatibility alias for PremiumCurrentTripCard.
 */
@Composable
fun PremiumCurrentTripCard(
    currentTrip: TripSummary,
    tripVisualState: TripVisualState = TripVisualState(),
    modifier: Modifier = Modifier,
    baselineMileage: Double = 18.0
) = CurrentTripCard(
    currentTrip = currentTrip,
    tripVisualState = tripVisualState,
    modifier = modifier,
    baselineMileage = baselineMileage
)

