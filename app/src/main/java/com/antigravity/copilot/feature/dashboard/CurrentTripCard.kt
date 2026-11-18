package com.antigravity.copilot.feature.dashboard

import com.antigravity.copilot.core.designsystem.components.RaahiConfirmDialog

import com.antigravity.copilot.core.designsystem.tokens.CopilotShapes
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalContext
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
    val accentColor: Color,
    /** 0f (bad/red) to 1f (good/green); null = plain accent colouring. */
    val healthScore: Float? = null
)

/**
 * Trip metrics shown by the rotating trip stat (map ticker / trip card). Shared so both read the
 * same values and formatting.
 */
@Composable
internal fun rememberTripMetrics(currentTrip: TripSummary, baselineMileage: Double): List<PremiumRotatingMetric> {
    val c = CockpitTheme.colors
    val cyanAccent = c.accent
    val positiveAccent = c.accent
    val warningAccent = c.warning
    val dangerAccent = c.critical
    val isTripActive = currentTrip.isActive()
    val isTripCompleted = currentTrip.isCompleted()
    val hasTripMoved = (isTripActive || isTripCompleted) && currentTrip.distanceMeters >= 50.0
    return remember(currentTrip, baselineMileage, hasTripMoved, isTripActive, isTripCompleted, c) {
        listOf(
            PremiumRotatingMetric(
                id = "trip_distance",
                title = "Trip distance",
                value = NumberFormatUtils.formatDistance(currentTrip.distanceMeters / 1000.0),
                unit = "km",
                subtitle = if (isTripActive) "So far on this trip" else if (isTripCompleted) "Whole trip" else "Ready to drive",
                icon = Icons.Default.Route,
                accentColor = cyanAccent
            ),
            PremiumRotatingMetric(
                id = "avg_speed",
                title = "Average speed",
                value = if (hasTripMoved && currentTrip.avgSpeedKmh > 0.0) "${currentTrip.avgSpeedKmh.toInt()}" else "--",
                unit = "km/h",
                subtitle = "While moving",
                icon = Icons.Default.Speed,
                accentColor = cyanAccent
            ),
            PremiumRotatingMetric(
                id = "max_speed",
                title = "Top speed",
                value = if (currentTrip.maxSpeedKmh > 0.0) "${currentTrip.maxSpeedKmh.toInt()}" else "--",
                unit = "km/h",
                subtitle = if (isTripActive) "Fastest so far" else "Fastest on this trip",
                icon = Icons.Default.TrendingUp,
                accentColor = cyanAccent
            ),
            PremiumRotatingMetric(
                id = "trip_mileage",
                title = "Trip mileage",
                value = if (hasTripMoved) {
                    val effMileage = if (currentTrip.calculatedMileageKmpl > 0.0) currentTrip.calculatedMileageKmpl else baselineMileage
                    if (effMileage > 0.0) NumberFormatUtils.formatMileage(effMileage) else "--"
                } else {
                    "--"
                },
                unit = "km/L",
                subtitle = if (hasTripMoved) {
                    val eff = if (currentTrip.calculatedMileageKmpl > 0.0) currentTrip.calculatedMileageKmpl else baselineMileage
                    if (baselineMileage > 0.0 && eff > 0.0) {
                        val pct = ((eff / baselineMileage - 1.0) * 100).toInt()
                        (if (pct >= 0) "\u25B2 +$pct%" else "\u25BC $pct%") + " vs your average"
                    } else "Estimated efficiency for this trip"
                } else "Available once trip starts",
                icon = Icons.Default.Eco,
                accentColor = positiveAccent,
                healthScore = if (hasTripMoved) {
                    val eff = if (currentTrip.calculatedMileageKmpl > 0.0) currentTrip.calculatedMileageKmpl else baselineMileage
                    MetricHealth.mileageScore(eff, baselineMileage)
                } else null
            ),
            PremiumRotatingMetric(
                id = "fuel_consumed",
                title = "Fuel used",
                value = if (hasTripMoved && currentTrip.estimatedFuelConsumedLiters > 0.0) {
                    NumberFormatUtils.formatFuelLiters(currentTrip.estimatedFuelConsumedLiters)
                } else if (hasTripMoved) {
                    "0"
                } else {
                    "--"
                },
                unit = "L",
                subtitle = if (hasTripMoved) "Estimated for this trip" else "Starts with the trip",
                icon = Icons.Default.LocalGasStation,
                accentColor = warningAccent
            ),
            PremiumRotatingMetric(
                id = "trip_cost",
                title = "Fuel cost",
                value = if (hasTripMoved && currentTrip.estimatedTripCost > 0.0) {
                    NumberFormatUtils.formatCurrency(currentTrip.estimatedTripCost)
                } else if (hasTripMoved) {
                    "0"
                } else {
                    "--"
                },
                unit = "₹",
                subtitle = "Estimated for this trip",
                icon = Icons.Default.Payments,
                accentColor = warningAccent
            ),
            PremiumRotatingMetric(
                id = "driver_score",
                title = "Drive score",
                value = if (hasTripMoved) "${currentTrip.driverScore}" else "--",
                unit = "/100",
                subtitle = if (hasTripMoved && (currentTrip.harshBrakeCount + currentTrip.rapidAccelCount > 0)) {
                    "${currentTrip.harshBrakeCount} hard brakes · ${currentTrip.rapidAccelCount} quick starts"
                } else if (hasTripMoved) {
                    "Smooth driving"
                } else {
                    "How smoothly you drive"
                },
                icon = Icons.Default.Security,
                accentColor = if (currentTrip.driverScore >= 85) positiveAccent else if (currentTrip.driverScore >= 70) warningAccent else dangerAccent,
                healthScore = if (hasTripMoved) MetricHealth.driveScore(currentTrip.driverScore) else null
            ),
            PremiumRotatingMetric(
                id = "peak_braking",
                title = "Braking",
                value = "${currentTrip.harshBrakeCount}",
                unit = if (currentTrip.harshBrakeCount == 1) "hard brake" else "hard brakes",
                subtitle = when {
                    currentTrip.maxDecelMss < -4.5f -> "One very hard stop"
                    currentTrip.harshBrakeCount > 0 -> "Try braking earlier"
                    else -> "Gentle braking"
                },
                icon = Icons.Default.Warning,
                accentColor = if (currentTrip.maxDecelMss < -4.5f) dangerAccent else warningAccent,
                healthScore = if (currentTrip.maxDecelMss < -0.1f) MetricHealth.brakingScore(currentTrip.maxDecelMss) else null
            )
        )
    }
}

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



    // Trip lifecycle flash animations (Start / End)
    val elapsedSinceEventMs = (liveWallTimeMs - tripVisualState.eventTimestamp).coerceAtLeast(0L)
    val isFlashingGreen = tripVisualState.event == TripVisualLifecycleEvent.STARTED && elapsedSinceEventMs < 10_000L
    val isFlashingRed = tripVisualState.event == TripVisualLifecycleEvent.COMPLETED && elapsedSinceEventMs < 10_000L

    val isFlashing = isFlashingGreen || isFlashingRed
    val flashPulseAlpha = if (isFlashing) {
        val flashPulseTransition = rememberInfiniteTransition(label = "FlashPulse")
        flashPulseTransition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(500, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "PulseAlpha"
        ).value
    } else {
        1.0f
    }

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

    // Manual override (spec Phase 1): long-press the card header to start / end a trip by hand.
    val context = LocalContext.current
    var showManualTripDialog by remember { mutableStateOf(false) }
    if (showManualTripDialog) {
        RaahiConfirmDialog(
            title = if (isTripActive) "End this trip now?" else "Start a trip now?",
            message = if (isTripActive) "The trip normally ends by itself when you reach a saved place."
            else "Trips normally start by themselves when you leave a saved place.",
            confirmText = if (isTripActive) "End trip" else "Start trip",
            icon = if (isTripActive) Icons.Default.Flag else Icons.Default.PlayArrow,
            onDismiss = { showManualTripDialog = false },
            onConfirm = {
                showManualTripDialog = false
                if (isTripActive) {
                    com.antigravity.copilot.core.location.DrivingTrackingService.manualEndTrip(context)
                } else {
                    com.antigravity.copilot.core.location.DrivingTrackingService.manualStartTrip(context)
                }
            }
        )
    }

    Box(
        modifier = modifier
            .dashCardSurface(borderColor = if (flashColor != Color.Transparent) flashColor else cardBorder)
            .padding(DashCard.Padding)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // ==================== 1. CARD HEADER (long-press: manual start / end) ====================
            DashCardHeader(
                icon = Icons.Default.Timer,
                title = if (isTripCompleted) "Last trip" else if (isTripActive) "This trip" else "Trip",
                accent = statusColor,
                modifier = Modifier.pointerInput(isTripActive) {
                    detectTapGestures(onLongPress = { showManualTripDialog = true })
                }
            ) {
                // Status telltale (live / completed / parked)
                Row(
                    modifier = Modifier
                        .height(32.dp)
                        .defaultMinSize(minWidth = 32.dp)
                        .clip(CopilotShapes.R8)
                        .background(statusColor.copy(alpha = 0.14f))
                        .border(1.dp, statusColor.copy(alpha = 0.45f), CopilotShapes.R8)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    if (isTripActive) {
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

            // ==================== 2. FIXED 2x2 TRIP GRID ====================
            // Four facts that never rotate, so the driver always finds them in the same place.
            val hasMoved = (isTripActive || isTripCompleted) && currentTrip.distanceMeters >= 50.0
            val tripMileage = if (currentTrip.calculatedMileageKmpl > 0.0) currentTrip.calculatedMileageKmpl else baselineMileage
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TripGridCell(
                        icon = Icons.Default.Route,
                        label = "Distance",
                        value = if (isTripActive || isTripCompleted) NumberFormatUtils.formatDistance(currentTrip.distanceMeters / 1000.0) else "--",
                        unit = "km",
                        modifier = Modifier.weight(1f)
                    )
                    TripGridCell(
                        icon = Icons.Default.Timer,
                        label = "Time",
                        value = if (elapsedDurationMs > 0L) com.antigravity.copilot.core.trip.TimeFormatUtils.formatFriendlyDuration(elapsedDurationMs) else "--",
                        unit = null,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TripGridCell(
                        icon = Icons.Default.Speed,
                        label = "Avg speed",
                        value = if (hasMoved && currentTrip.avgSpeedKmh > 0.0) NumberFormatUtils.formatSpeed(currentTrip.avgSpeedKmh) else "--",
                        unit = "km/h",
                        modifier = Modifier.weight(1f)
                    )
                    TripGridCell(
                        icon = Icons.Default.Eco,
                        label = "Mileage",
                        value = if (hasMoved && tripMileage > 0.0) NumberFormatUtils.formatMileage(tripMileage) else "--",
                        unit = "km/L",
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // ==================== 3. BOTTOM DOCK: STARTED AT + MOVING TIME ====================
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
            val dockBackgroundModifier = if (isFlashingGreen || isFlashingRed) {
                Modifier.background(dockBg)
            } else {
                Modifier.background(CockpitTheme.colors.dockActionBrush)
            }
            val leftValue = when {
                isFlashingGreen -> "Trip started"
                isFlashingRed -> "Trip ended"
                tripStartTimeFormatted.isNotEmpty() && (isTripActive || isTripCompleted) -> tripStartTimeFormatted
                else -> "Parked"
            }
            val leftLabel = when {
                isFlashingGreen || isFlashingRed -> "Just now"
                isTripActive -> "Started at"
                isTripCompleted -> "Started at"
                else -> "Ready to drive"
            }
            val drivingMs = currentTrip.drivingDurationMs

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(DashCard.StripHeight)
                    .clip(CopilotShapes.R10)
                    .then(dockBackgroundModifier)
                    .border(dockBorderWidth, dockBorderColor, CopilotShapes.R10)
                    .padding(horizontal = 14.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(leftLabel, style = CockpitTheme.typography.labelMedium, color = textSecondary, maxLines = 1)
                    Text(
                        text = leftValue,
                        style = CockpitTheme.typography.headlineMedium,
                        color = if (isFlashingGreen) positiveAccent else if (isFlashingRed) dangerAccent else textPrimary,
                        maxLines = 1,
                        softWrap = false
                    )
                }
                Box(
                    modifier = Modifier
                        .height(28.dp)
                        .width(1.dp)
                        .background(CockpitTheme.colors.border)
                )
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text("Moving", style = CockpitTheme.typography.labelMedium, color = textSecondary, maxLines = 1)
                    Text(
                        text = if (drivingMs > 0L) com.antigravity.copilot.core.trip.TimeFormatUtils.formatFriendlyDuration(drivingMs) else "--",
                        style = CockpitTheme.typography.headlineMedium,
                        color = textPrimary,
                        maxLines = 1,
                        softWrap = false
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

/** One cell of the trip card's fixed 2x2 grid: icon + label over a 28 sp value. */
@Composable
private fun TripGridCell(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    unit: String?,
    modifier: Modifier = Modifier
) {
    val c = CockpitTheme.colors
    Column(
        modifier = modifier
            .clip(CopilotShapes.R10)
            .background(c.cardElevated.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(18.dp))
            Text(label, style = CockpitTheme.typography.labelMedium, color = c.textSecondary, maxLines = 1)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                style = CockpitTheme.typography.headlineLarge,
                color = c.textPrimary,
                maxLines = 1,
                softWrap = false
            )
            if (unit != null && value != "--") {
                Spacer(Modifier.width(4.dp))
                Text(unit, style = CockpitTheme.typography.labelMedium, color = c.textSecondary, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }
}
