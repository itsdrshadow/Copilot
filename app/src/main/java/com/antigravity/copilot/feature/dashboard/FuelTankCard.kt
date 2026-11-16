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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.1.sp,
                            fontSize = 14.5.sp
                        ),
                        color = textSecondary
                    )
                }

                // Dedicated Tactile "+ ADD FUEL" Button
                Box(
                    modifier = Modifier
                        .height(32.dp)
                        .graphicsLayer {
                            scaleX = addScale
                            scaleY = addScale
                        }
                        .clip(RoundedCornerShape(8.dp))
                        .background(cyanAccent)
                        .clickable(interactionSource = addButtonInteraction, indication = null) { onClick() }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "ADD FUEL",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.5.sp,
                                fontSize = 12.sp
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
                            style = MaterialTheme.typography.displayMedium.copy(
                                fontWeight = FontWeight.Black,
                                fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily,
                                fontFeatureSettings = "tnum",
                                fontSize = 38.sp
                            ),
                            color = textPrimary
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = "L",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp
                            ),
                            color = primaryStatusColor
                        )
                    }
                    Text(
                        text = "OEM Capacity ${capacity.toInt()} L",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 13.sp
                        ),
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
                        style = MaterialTheme.typography.displayMedium.copy(
                            fontWeight = FontWeight.Black,
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily,
                            fontFeatureSettings = "tnum",
                            fontSize = 36.sp
                        ),
                        color = primaryStatusColor
                    )
                    Text(
                        text = "TANK LEVEL",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            fontSize = 12.sp
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
                    .clickable { showGaugeDialog = true }
                    .padding(vertical = 2.dp),
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
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black, fontSize = 13.sp),
                        color = if (tankPct <= 15.0) dangerAccent else textSecondary
                    )
                    Text(
                        text = "F",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black, fontSize = 13.sp),
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
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Black,
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily,
                            fontFeatureSettings = "tnum",
                            fontSize = 20.sp
                        ),
                        maxLines = 1,
                        softWrap = false,
                        color = textPrimary
                    )
                    Text(
                        text = "EST. RANGE",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        ),
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
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Black,
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily,
                            fontFeatureSettings = "tnum",
                            fontSize = 20.sp
                        ),
                        maxLines = 1,
                        softWrap = false,
                        color = mileageColor
                    )
                    Text(
                        text = mileageLabel,
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

    // Mid-bar Calibration Dialog
    if (showGaugeDialog) {
        Dialog(onDismissRequest = { showGaugeDialog = false }) {
            Box(
                modifier = Modifier
                    .width(580.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(CockpitTheme.colors.card)
                    .border(1.2.dp, CockpitTheme.colors.border, RoundedCornerShape(20.dp))
                    .padding(22.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = null,
                                    tint = CockpitTheme.colors.accent,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Calibrate Instrument Gauge",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp
                                    ),
                                    color = CockpitTheme.colors.textPrimary
                                )
                                Text(
                                    text = "Match active bars with physical 10-bar cluster display",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
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
                                .border(1.dp, activeColor.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "$tempSelectedBar / 10 BARS • ${tempSelectedBar * 10}%",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Black,
                                    fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily,
                                    fontFeatureSettings = "tnum",
                                    fontSize = 13.5.sp
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
                            .clip(RoundedCornerShape(10.dp))
                            .background(CockpitTheme.colors.cardElevated)
                            .border(1.dp, CockpitTheme.colors.border, RoundedCornerShape(10.dp))
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ESTIMATED FUEL LEVEL",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                letterSpacing = 0.5.sp
                            ),
                            color = CockpitTheme.colors.textSecondary
                        )
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = NumberFormatUtils.formatPrecision(estLiters, 1),
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Black,
                                    fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily,
                                    fontFeatureSettings = "tnum",
                                    fontSize = 24.sp
                                ),
                                color = CockpitTheme.colors.textPrimary
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "L / ${capacity.toInt()} L",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                ),
                                color = CockpitTheme.colors.textSecondary,
                                modifier = Modifier.padding(bottom = 2.dp)
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
                                    .width(46.dp)
                                    .height(76.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isSelected) zoneColor else CockpitTheme.colors.cardElevated
                                    )
                                    .border(
                                        width = if (isSelected) 1.5.dp else 1.dp,
                                        color = if (isSelected) zoneColor else CockpitTheme.colors.border,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable { tempSelectedBar = bar },
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = "$bar",
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Black,
                                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily,
                                            fontFeatureSettings = "tnum",
                                            fontSize = 17.sp
                                        ),
                                        color = if (isSelected) CockpitTheme.colors.onAccent else CockpitTheme.colors.textSecondary
                                    )
                                    Text(
                                        text = "${bar * 10}%",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.5.sp
                                        ),
                                        color = if (isSelected) CockpitTheme.colors.onAccent.copy(alpha = 0.8f) else CockpitTheme.colors.textDisabled
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
                            modifier = Modifier.height(46.dp)
                        ) {
                            Text(
                                text = "Cancel",
                                color = CockpitTheme.colors.textSecondary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Button(
                            onClick = {
                                onCalibrateGauge(tempSelectedBar)
                                showGaugeDialog = false
                            },
                            modifier = Modifier.height(46.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CockpitTheme.colors.accent)
                        ) {
                            Text(
                                text = "Apply Gauge Bar ($tempSelectedBar/10)",
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

