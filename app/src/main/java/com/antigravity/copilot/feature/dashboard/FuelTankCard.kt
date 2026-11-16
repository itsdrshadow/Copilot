package com.antigravity.copilot.feature.dashboard

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.antigravity.copilot.core.designsystem.*
import com.antigravity.copilot.core.model.FuelState
import com.antigravity.copilot.core.util.NumberFormatUtils
import java.util.Locale

/**
 * Fuel System Instrument Card:
 * Navy cockpit aesthetic, 10-bar LED MID visualizer, tabular fuel metrics, and direct Add Fuel action.
 */
@Composable
fun FuelTankCard(
    fuelState: FuelState,
    onClick: () -> Unit,
    onCalibrateGauge: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val cardBg = CockpitTheme.colors.cardTopLightGradient
    val cardBorder = CockpitTheme.colors.border
    val cyanAccent = CockpitTheme.colors.accent
    val positiveAccent = CockpitTheme.colors.accent
    val warningAccent = CockpitTheme.colors.warning
    val dangerAccent = CockpitTheme.colors.critical
    val textPrimary = CockpitTheme.colors.textPrimary
    val textSecondary = CockpitTheme.colors.textSecondary

    val currentLiters = fuelState.remainingLiters
    val capacity = fuelState.designCapacityLiters
    val tankPct = fuelState.fuelPercentage.toDouble().coerceIn(0.0, 100.0)

    var showGaugeDialog by remember { mutableStateOf(false) }
    val initialMidBar = if (fuelState.currentMidBar in 1..10) fuelState.currentMidBar else ((tankPct / 10.0).toInt()).coerceIn(1, 10)
    var tempSelectedBar by remember(initialMidBar, showGaugeDialog) {
        mutableIntStateOf(initialMidBar)
    }

    val addButtonInteraction = remember { MutableInteractionSource() }
    val isAddPressed by addButtonInteraction.collectIsPressedAsState()
    val addScale by animateFloatAsState(
        targetValue = if (isAddPressed) 0.92f else 1.0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "PremiumAddFuelScale"
    )

    val primaryStatusColor = when {
        tankPct <= 15.0 -> dangerAccent
        tankPct <= 30.0 -> warningAccent
        else -> cyanAccent
    }

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
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // ==================== 1. CARD HEADER ====================
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
                            .background(primaryStatusColor.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocalGasStation,
                            contentDescription = null,
                            tint = primaryStatusColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Text(
                        text = "FUEL TANK",
                        style = CockpitTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.1.sp
                        ),
                        color = textSecondary,
                        maxLines = 1
                    )
                }

                // Dedicated Tactile "+ ADD FUEL" Button
                Box(
                    modifier = Modifier
                        .defaultMinSize(minWidth = 108.dp, minHeight = 44.dp)
                        .graphicsLayer {
                            scaleX = addScale
                            scaleY = addScale
                        }
                        .clip(RoundedCornerShape(10.dp))
                        .background(cyanAccent)
                        .clickable(
                            interactionSource = addButtonInteraction,
                            indication = rememberRipple(bounded = true, color = Color.White),
                            role = Role.Button
                        ) { onClick() }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "ADD FUEL",
                            style = CockpitTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.5.sp
                            ),
                            color = Color.Black
                        )
                    }
                }
            }

            // ==================== 2. PRIMARY FUEL TELEMETRY ROW ====================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Fuel Quantity
                Column(modifier = Modifier.weight(1.1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = NumberFormatUtils.formatFuelLiters(currentLiters, 1),
                            style = CockpitTheme.typography.displayMedium.copy(
                                fontFeatureSettings = "tnum"
                            ),
                            color = textPrimary
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = "L",
                            style = CockpitTheme.typography.titleLarge,
                            color = primaryStatusColor
                        )
                    }
                    Text(
                        text = "OEM Capacity ${capacity.toInt()} L",
                        style = CockpitTheme.typography.bodySmall,
                        color = textSecondary
                    )
                }

                // Right: Percentage
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.weight(0.9f)
                ) {
                    Text(
                        text = "${tankPct.toInt()}%",
                        style = CockpitTheme.typography.displayMedium.copy(
                            fontFeatureSettings = "tnum"
                        ),
                        color = primaryStatusColor
                    )
                    Text(
                        text = "TANK LEVEL",
                        style = CockpitTheme.typography.labelSmall.copy(
                            letterSpacing = 0.5.sp
                        ),
                        color = textSecondary
                    )
                }
            }

            // ==================== 3. 10-SEGMENT PRECISION LED GAUGE ====================
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = rememberRipple(bounded = true, color = cyanAccent)
                    ) { showGaugeDialog = true }
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(15.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val activeBars = if (fuelState.currentMidBar in 1..10) fuelState.currentMidBar else ((tankPct / 10.0).toInt()).coerceIn(0, 10)
                    for (i in 1..10) {
                        val isSegmentActive = i <= activeBars
                        val segmentColor = when {
                            !isSegmentActive -> CockpitTheme.colors.border
                            i <= 1 -> dangerAccent
                            i <= 3 -> warningAccent
                            else -> cyanAccent
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(2.5.dp))
                                .background(segmentColor)
                                .then(
                                    if (!isSegmentActive) Modifier.border(0.5.dp, CockpitTheme.colors.border, RoundedCornerShape(2.5.dp))
                                    else Modifier
                                )
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "E",
                        style = CockpitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black),
                        color = if (tankPct <= 15.0) dangerAccent else textSecondary
                    )
                    Text(
                        text = "F",
                        style = CockpitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black),
                        color = if (tankPct >= 90.0) positiveAccent else textSecondary
                    )
                }
            }

            // ==================== 4. BOTTOM TELEMETRY DOCK (RANGE + MILEAGE) ====================
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
                // Range
                Column(modifier = Modifier.weight(1f)) {
                    val rangeKm = fuelState.remainingRangeKm
                    Text(
                        text = if (rangeKm > 0) "${rangeKm.toInt()} km" else "--- km",
                        style = CockpitTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Black,
                            fontFeatureSettings = "tnum"
                        ),
                        maxLines = 1,
                        softWrap = false,
                        color = textPrimary
                    )
                    Text(
                        text = "EST. RANGE",
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

                // Mileage
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.End
                ) {
                    val isConfirmed = fuelState.mileageLearningState == com.antigravity.copilot.core.model.MileageLearningState.LEARNED && fuelState.currentMileageKmpl > 0.0
                    val isEstimated = (fuelState.mileageLearningState == com.antigravity.copilot.core.model.MileageLearningState.LEARNING || fuelState.isRealMileageDerived) && fuelState.currentMileageKmpl > 0.0

                    val displayMileage = when {
                        fuelState.currentMileageKmpl > 0.0 -> fuelState.currentMileageKmpl
                        fuelState.expectedMileageKmpl > 0.0 -> fuelState.expectedMileageKmpl
                        else -> 18.0
                    }
                    val mileageText = String.format(Locale.US, "%.1f km/L", displayMileage)
                    val mileageColor = when {
                        isConfirmed -> positiveAccent
                        isEstimated -> cyanAccent
                        else -> cyanAccent
                    }
                    val mileageLabel = when {
                        isConfirmed -> "CURRENT MILEAGE"
                        isEstimated -> "EST. MILEAGE"
                        else -> "EST. MILEAGE"
                    }

                    Text(
                        text = mileageText,
                        style = CockpitTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Black,
                            fontFeatureSettings = "tnum"
                        ),
                        maxLines = 1,
                        softWrap = false,
                        color = mileageColor
                    )
                    Text(
                        text = mileageLabel,
                        style = CockpitTheme.typography.labelSmall,
                        maxLines = 1,
                        softWrap = false,
                        color = textSecondary
                    )
                }
            }
        }
    }

    // Mid-bar Calibration Dialog
    if (showGaugeDialog) {
        Dialog(onDismissRequest = { showGaugeDialog = false }) {
            Box(
                modifier = Modifier
                    .width(740.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(CockpitTheme.colors.card)
                    .border(1.5.dp, CockpitTheme.colors.border, RoundedCornerShape(22.dp))
                    .padding(22.dp)
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
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(CockpitTheme.colors.accentSoft),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = null,
                                    tint = CockpitTheme.colors.accent,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "Calibrate Instrument Gauge",
                                    style = CockpitTheme.typography.headlineLarge,
                                    color = CockpitTheme.colors.textPrimary
                                )
                                Text(
                                    text = "Match active bars with physical 10-bar cluster display",
                                    style = CockpitTheme.typography.bodyMedium,
                                    color = CockpitTheme.colors.textSecondary
                                )
                            }
                        }

                        // Live Lit Summary Badge
                        val activeColor = when (tempSelectedBar) {
                            1, 2 -> CockpitTheme.colors.critical
                            3, 4 -> CockpitTheme.colors.warning
                            else -> CockpitTheme.colors.accent
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(activeColor.copy(alpha = 0.15f))
                                .border(1.2.dp, activeColor.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 14.dp, vertical = 7.dp)
                        ) {
                            Text(
                                text = "$tempSelectedBar / 10 BARS • ${tempSelectedBar * 10}%",
                                style = CockpitTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Black,
                                    fontFeatureSettings = "tnum"
                                ),
                                color = activeColor
                            )
                        }
                    }

                    // Live Lit Fuel Volume Readout Chamber
                    val estLiters = (tempSelectedBar * capacity / 10.0).coerceIn(0.0, capacity)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(CockpitTheme.colors.cardElevated)
                            .border(1.2.dp, CockpitTheme.colors.border, RoundedCornerShape(12.dp))
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ESTIMATED FUEL LEVEL",
                            style = CockpitTheme.typography.labelMedium.copy(
                                letterSpacing = 0.5.sp
                            ),
                            color = CockpitTheme.colors.textSecondary
                        )
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = NumberFormatUtils.formatPrecision(estLiters, 1),
                                style = CockpitTheme.typography.displaySmall.copy(
                                    fontFeatureSettings = "tnum"
                                ),
                                color = CockpitTheme.colors.textPrimary
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "L / ${capacity.toInt()} L",
                                style = CockpitTheme.typography.labelMedium,
                                color = CockpitTheme.colors.textSecondary,
                                modifier = Modifier.padding(bottom = 3.dp)
                            )
                        }
                    }

                    // Large 10-Segment Color-Coded Tactile Bars
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        for (bar in 1..10) {
                            val isSelected = bar <= tempSelectedBar
                            val zoneColor = when (bar) {
                                1, 2 -> CockpitTheme.colors.critical   // Reserve / Low
                                3, 4 -> CockpitTheme.colors.warning    // Quarter / Low
                                else -> CockpitTheme.colors.accent     // Normal / High
                            }

                            Box(
                                modifier = Modifier
                                    .width(56.dp)
                                    .height(88.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (isSelected) zoneColor else CockpitTheme.colors.cardElevated
                                    )
                                    .border(
                                        width = if (isSelected) 1.5.dp else 1.dp,
                                        color = if (isSelected) zoneColor else CockpitTheme.colors.border,
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = rememberRipple(color = zoneColor),
                                        role = Role.Button,
                                        onClick = { tempSelectedBar = bar }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = "$bar",
                                        style = CockpitTheme.typography.headlineMedium.copy(
                                            fontWeight = FontWeight.Black,
                                            fontFeatureSettings = "tnum"
                                        ),
                                        color = if (isSelected) CockpitTheme.colors.onAccent else CockpitTheme.colors.textSecondary
                                    )
                                    Text(
                                        text = "${bar * 10}%",
                                        style = CockpitTheme.typography.labelSmall,
                                        color = if (isSelected) CockpitTheme.colors.onAccent.copy(alpha = 0.9f) else CockpitTheme.colors.textDisabled
                                    )
                                }
                            }
                        }
                    }

                    // Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { showGaugeDialog = false },
                            modifier = Modifier.height(52.dp)
                        ) {
                            Text(
                                text = "Cancel",
                                color = CockpitTheme.colors.textSecondary,
                                style = CockpitTheme.typography.labelLarge
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Button(
                            onClick = {
                                onCalibrateGauge(tempSelectedBar)
                                showGaugeDialog = false
                            },
                            modifier = Modifier.height(52.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CockpitTheme.colors.accent)
                        ) {
                            Text(
                                text = "Apply Gauge Bar ($tempSelectedBar/10)",
                                color = CockpitTheme.colors.onAccent,
                                style = CockpitTheme.typography.labelLarge.copy(fontWeight = FontWeight.Black)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Backward-compatibility alias for PremiumFuelTankCard.
 */
@Composable
fun PremiumFuelTankCard(
    fuelState: FuelState,
    onClick: () -> Unit,
    onCalibrateGauge: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) = FuelTankCard(
    fuelState = fuelState,
    onClick = onClick,
    onCalibrateGauge = onCalibrateGauge,
    modifier = modifier
)

