package com.antigravity.drivingreport.feature.dashboard

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.antigravity.drivingreport.core.designsystem.*
import com.antigravity.drivingreport.core.odometer.OdometerManager
import com.antigravity.drivingreport.core.util.NumberFormatUtils
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Odometer & Vehicle Lifetime Telemetry Card:
 * Studio-grade dark navy aesthetics, tabular numerals, interactive calibration, and rotating metrics.
 */
@Composable
fun OdometerCard(
    totalLifetimeKm: Double,
    totalLifetimeFuelLiters: Double,
    totalTripsCount: Int,
    totalHours: Int,
    totalRemainingMin: Int,
    lifetimeAvgSpeed: Double,
    lifetimeMileage: Double,
    modifier: Modifier = Modifier,
    isVeryCompact: Boolean = false
) {
    val context = LocalContext.current
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

    // Premium Navy Palette Tokens
    val cardBg = CockpitTheme.colors.cardTopLightGradient
    val cardBorder = CockpitTheme.colors.border
    val cyanAccent = CockpitTheme.colors.accent
    val positiveAccent = CockpitTheme.colors.accent
    val warningAccent = CockpitTheme.colors.warning
    val textPrimary = CockpitTheme.colors.textPrimary
    val textSecondary = CockpitTheme.colors.textSecondary

    // Lifetime Rotating Metrics Carousel (Completed Trips, Driving Time, Avg Speed, Lifetime Fuel, Lifetime Mileage)
    val metrics = remember(
        totalLifetimeKm,
        totalTripsCount,
        totalHours,
        totalRemainingMin,
        lifetimeAvgSpeed,
        totalLifetimeFuelLiters,
        lifetimeMileage,
        cyanAccent,
        positiveAccent,
        warningAccent
    ) {
        listOf(
            PremiumRotatingMetric(
                id = "trips",
                title = "COMPLETED TRIPS",
                value = "$totalTripsCount",
                unit = if (totalTripsCount == 1) "TRIP" else "TRIPS",
                subtitle = "Logged journeys in system",
                icon = Icons.Default.DirectionsCar,
                accentColor = cyanAccent
            ),
            PremiumRotatingMetric(
                id = "time",
                title = "TOTAL DRIVING TIME",
                value = "${totalHours}h ${totalRemainingMin}m",
                unit = "",
                subtitle = "Cumulative hours behind the wheel",
                icon = Icons.Default.Schedule,
                accentColor = positiveAccent
            ),
            PremiumRotatingMetric(
                id = "speed",
                title = "LIFETIME AVG SPEED",
                value = String.format(Locale.US, "%.1f", lifetimeAvgSpeed),
                unit = "KM/H",
                subtitle = "All-time trip weighted velocity",
                icon = Icons.Default.Speed,
                accentColor = cyanAccent
            ),
            PremiumRotatingMetric(
                id = "fuel",
                title = "LIFETIME FUEL USED",
                value = String.format(Locale.US, "%.1f", totalLifetimeFuelLiters),
                unit = "L",
                subtitle = "Total fuel burned across all trips",
                icon = Icons.Default.LocalGasStation,
                accentColor = warningAccent
            ),
            PremiumRotatingMetric(
                id = "mileage",
                title = "LIFETIME AVG MILEAGE",
                value = String.format(Locale.US, "%.1f", lifetimeMileage),
                unit = "KM/L",
                subtitle = "Fleet efficiency baseline",
                icon = Icons.Default.Eco,
                accentColor = positiveAccent
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

    // Card press feedback
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.985f else 1.0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "PremiumOdoPressScale"
    )

    // Dedicated Calibrate Button interaction
    val calibButtonInteraction = remember { MutableInteractionSource() }
    val isCalibPressed by calibButtonInteraction.collectIsPressedAsState()
    val calibScale by animateFloatAsState(
        targetValue = if (isCalibPressed) 0.95f else 1.0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "CalibButtonScale"
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(RoundedCornerShape(if (isVeryCompact) 16.dp else 20.dp))
            .background(cardBg)
            .border(
                width = 1.25.dp,
                color = cardBorder,
                shape = RoundedCornerShape(if (isVeryCompact) 16.dp else 20.dp)
            )
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { isUserInteracting = true },
                    onDragEnd = { isUserInteracting = false },
                    onDragCancel = { isUserInteracting = false },
                    onHorizontalDrag = { _, dragAmount ->
                        if (dragAmount > 25) {
                            activeMetricIndex = if (activeMetricIndex == 0) metrics.size - 1 else activeMetricIndex - 1
                        } else if (dragAmount < -25) {
                            activeMetricIndex = (activeMetricIndex + 1) % metrics.size
                        }
                    }
                )
            }
            .padding(if (isVeryCompact) 10.dp else 14.dp)
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
                            .size(if (isVeryCompact) 22.dp else 28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(cyanAccent.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.DirectionsCar,
                            contentDescription = null,
                            tint = cyanAccent,
                            modifier = Modifier.size(if (isVeryCompact) 13.dp else 18.dp)
                        )
                    }
                    Text(
                        text = "ODOMETER",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.1.sp,
                            fontSize = if (isVeryCompact) 9.5.sp else 14.5.sp
                        ),
                        color = textSecondary
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Dedicated Calibration Chip Button (Symmetric with ADD FUEL)
                    Box(
                        modifier = Modifier
                            .height(if (isVeryCompact) 26.dp else 32.dp)
                            .graphicsLayer {
                                scaleX = calibScale
                                scaleY = calibScale
                            }
                            .clip(RoundedCornerShape(8.dp))
                            .background(cyanAccent)
                            .clickable(interactionSource = calibButtonInteraction, indication = null) { showOdometerDialog = true }
                            .padding(horizontal = if (isVeryCompact) 9.dp else 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Calibrate",
                                tint = Color.Black,
                                modifier = Modifier.size(if (isVeryCompact) 11.dp else 14.dp)
                            )
                            Text(
                                text = "CALIBRATE",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Black,
                                    fontSize = if (isVeryCompact) 9.sp else 12.sp,
                                    letterSpacing = 0.5.sp
                                ),
                                color = Color.Black
                            )
                        }
                    }
                }
            }

            // ==================== 2. ROTATING LIFETIME METRIC HERO ====================
            AnimatedContent(
                targetState = activeMetricIndex,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(220)) + slideInHorizontally { width -> width / 3 })
                        .togetherWith(fadeOut(animationSpec = tween(200)) + slideOutHorizontally { width -> -width / 3 })
                },
                label = "PremiumRotatingMetricHero",
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
                            modifier = Modifier.size(if (isVeryCompact) 13.dp else 17.dp)
                        )
                        Text(
                            text = target.title,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp,
                                fontSize = if (isVeryCompact) 9.sp else 13.5.sp
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
                        preferredFontSize = if (isVeryCompact) 28.sp else 44.sp,
                        minFontSize = if (isVeryCompact) 18.sp else 24.sp,
                        valueColor = textPrimary,
                        unitColor = target.accentColor,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(2.dp))

                    Text(
                        text = target.subtitle,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = if (isVeryCompact) 8.5.sp else 13.sp
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
                        label = "PremiumDotWidth"
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

            // ==================== 4. BOTTOM TELEMETRY DOCK (TOTAL ODOMETER) ====================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isVeryCompact) 42.dp else 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.25f))
                    .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
                    .clickable { showOdometerDialog = true }
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        text = "TOTAL ODOMETER",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                            fontSize = if (isVeryCompact) 8.sp else 10.5.sp
                        ),
                        color = textSecondary
                    )
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = NumberFormatUtils.formatOdometer(currentOdometerKm, useGrouping = true),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                fontSize = if (isVeryCompact) 14.sp else 19.sp,
                                letterSpacing = (-0.5).sp,
                                platformStyle = PlatformTextStyle(includeFontPadding = false)
                            ),
                            color = textPrimary
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "KM",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Black,
                                fontSize = if (isVeryCompact) 10.sp else 12.sp,
                                platformStyle = PlatformTextStyle(includeFontPadding = false)
                            ),
                            color = cyanAccent,
                            modifier = Modifier.padding(bottom = 1.dp)
                        )
                    }
                }

                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(cyanAccent.copy(alpha = 0.12f))
                            .border(1.dp, cyanAccent.copy(alpha = 0.25f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Straighten,
                            contentDescription = null,
                            tint = cyanAccent,
                            modifier = Modifier.size(if (isVeryCompact) 10.dp else 12.dp)
                        )
                        Text(
                            text = "+${NumberFormatUtils.formatDistance(deltaKmSinceCalibration, 1)} KM",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                fontSize = if (isVeryCompact) 9.sp else 12.sp,
                                platformStyle = PlatformTextStyle(includeFontPadding = false)
                            ),
                            maxLines = 1,
                            softWrap = false,
                            color = cyanAccent
                        )
                    }
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = "SINCE CAL",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = if (isVeryCompact) 7.sp else 9.5.sp,
                            letterSpacing = 0.6.sp
                        ),
                        maxLines = 1,
                        softWrap = false,
                        color = textSecondary
                    )
                }
            }
        }
    }

    // Interactive Odometer Calibration Dialog
    if (showOdometerDialog) {
        PremiumOdometerCalibrationDialog(
            currentOdometerKm = currentOdometerKm,
            onDismiss = { showOdometerDialog = false },
            onConfirmCalibration = { newOdometerKm ->
                com.antigravity.drivingreport.core.fuel.FuelRepository.getInstance(context).calibrateOdometer(newOdometerKm, totalLifetimeKm)
                showOdometerDialog = false
            }
        )
    }

    }

data class PremiumRotatingMetric(
    val id: String,
    val title: String,
    val value: String,
    val unit: String,
    val subtitle: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val accentColor: Color
)

@Composable
private fun PremiumOdometerCalibrationDialog(
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
                .width(560.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(CockpitTheme.colors.card)
                .border(1.2.dp, CockpitTheme.colors.border, RoundedCornerShape(22.dp))
                .padding(22.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(CockpitTheme.colors.accentSoft),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = null,
                                tint = CockpitTheme.colors.accent,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Calibrate Vehicle Odometer",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                ),
                                color = CockpitTheme.colors.textPrimary
                            )
                            Text(
                                text = "Sync with physical car dashboard cluster reading",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
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
                                .border(1.dp, diffColor.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = diffText,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp
                                ),
                                color = diffColor
                            )
                        }
                    }
                }

                // Large Monospace Readout Chamber
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(CockpitTheme.colors.cardElevated)
                        .border(1.5.dp, CockpitTheme.colors.accent.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 18.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ODOMETER",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                fontSize = 12.sp
                            ),
                            color = CockpitTheme.colors.textSecondary
                        )
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = textValue.ifEmpty { "0.0" },
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 32.sp
                                ),
                                color = CockpitTheme.colors.textPrimary
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "KM",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Black,
                                    fontSize = 16.sp
                                ),
                                color = CockpitTheme.colors.accent,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                    }
                }

                if (errorMessage != null) {
                    Text(
                        text = errorMessage ?: "",
                        color = CockpitTheme.colors.critical,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        fontWeight = FontWeight.Bold
                    )
                }

                // Quick Adjustment Step Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(-1000.0, -100.0, -10.0, 10.0, 100.0, 1000.0).forEach { delta ->
                        val sign = if (delta > 0) "+${delta.toInt()}" else "${delta.toInt()}"
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(CockpitTheme.colors.cardElevated)
                                .border(1.dp, CockpitTheme.colors.border, RoundedCornerShape(8.dp))
                                .clickable { adjustBy(delta) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = sign,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.5.sp
                                ),
                                color = if (delta > 0) CockpitTheme.colors.accent else CockpitTheme.colors.critical
                            )
                        }
                    }
                }

                // Tactile Automotive Numeric Keypad
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val keyRows = listOf(
                        listOf("1", "2", "3"),
                        listOf("4", "5", "6"),
                        listOf("7", "8", "9"),
                        listOf(".", "0", "⌫")
                    )

                    keyRows.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            row.forEach { key ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(CockpitTheme.colors.cardElevated)
                                        .border(1.dp, CockpitTheme.colors.border, RoundedCornerShape(8.dp))
                                        .clickable {
                                            errorMessage = null
                                            when (key) {
                                                "⌫" -> {
                                                    if (textValue.isNotEmpty()) {
                                                        textValue = textValue.dropLast(1)
                                                    }
                                                }
                                                "." -> {
                                                    if (!textValue.contains(".")) {
                                                        textValue += "."
                                                    }
                                                }
                                                else -> {
                                                    textValue += key
                                                }
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = key,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 18.sp
                                        ),
                                        color = if (key == "⌫") CockpitTheme.colors.critical else CockpitTheme.colors.textPrimary
                                    )
                                }
                            }
                        }
                    }
                }

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            textValue = ""
                            errorMessage = null
                        }
                    ) {
                        Text("Clear", color = CockpitTheme.colors.critical, fontSize = 14.sp)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text(
                                text = "Cancel",
                                color = CockpitTheme.colors.textSecondary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Button(
                            onClick = {
                                val parsed = textValue.toDoubleOrNull()
                                if (parsed == null || parsed < 0.0 || parsed > 2_000_000.0) {
                                    errorMessage = "Enter valid odometer (0 – 2,000,000 km)"
                                } else {
                                    onConfirmCalibration(parsed)
                                }
                            },
                            modifier = Modifier.height(48.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CockpitTheme.colors.accent)
                        ) {
                            Text(
                                text = "Confirm Calibration",
                                color = CockpitTheme.colors.onAccent,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Backward-compatibility alias for PremiumOdometerCard.
 */
@Composable
fun PremiumOdometerCard(
    totalLifetimeKm: Double,
    totalLifetimeFuelLiters: Double,
    totalTripsCount: Int,
    totalHours: Int,
    totalRemainingMin: Int,
    lifetimeAvgSpeed: Double,
    lifetimeMileage: Double,
    modifier: Modifier = Modifier,
    isVeryCompact: Boolean = false
) = OdometerCard(
    totalLifetimeKm = totalLifetimeKm,
    totalLifetimeFuelLiters = totalLifetimeFuelLiters,
    totalTripsCount = totalTripsCount,
    totalHours = totalHours,
    totalRemainingMin = totalRemainingMin,
    lifetimeAvgSpeed = lifetimeAvgSpeed,
    lifetimeMileage = lifetimeMileage,
    modifier = modifier,
    isVeryCompact = isVeryCompact
)

