package com.antigravity.copilot.feature.fuel

import com.antigravity.copilot.core.designsystem.components.cockpitSurface
import com.antigravity.copilot.core.designsystem.tokens.Palette
import com.antigravity.copilot.core.designsystem.tokens.CopilotShapes
import androidx.compose.animation.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.copilot.core.designsystem.CockpitTheme
import com.antigravity.copilot.core.designsystem.AutomotiveDesignTokens
import com.antigravity.copilot.core.util.NumberFormatUtils

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
internal fun UnifiedFuelTelemetryAndGaugeCard(
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
            .cockpitSurface(Color(0xFF090F1C), Color(0xFF1E2D44), CopilotShapes.R14, borderWidth = 1.2.dp)
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
                        tint = Palette.CyanElectric,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "FUEL LEVEL & TELEMETRIC PROJECTION",
                        style = CockpitTheme.typography.labelSmall.copy(
                            letterSpacing = 0.8.sp
                        ),
                        color = Palette.CyanElectric
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Tank: ${designCap.toInt()} L",
                        style = CockpitTheme.typography.labelSmall.copy(
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                        ),
                        color = Palette.Slate400
                    )

                    // Delta / Bar Badge
                    Box(
                        modifier = Modifier
                            .clip(CopilotShapes.R6)
                            .background(if (additionalBars > 0) Palette.Sky600.copy(alpha = 0.25f) else Palette.Slate800)
                            .border(
                                1.dp,
                                if (additionalBars > 0) Palette.LightBlueA400.copy(alpha = 0.70f) else Palette.Slate700,
                                CopilotShapes.R6
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (additionalBars > 0) "+$additionalBars BARS" else "$postRefillBar/10 BARS",
                            style = CockpitTheme.typography.labelSmall.copy(
                                fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                            ),
                            color = if (additionalBars > 0) Palette.LightBlueA400 else Palette.Slate400
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
                            style = CockpitTheme.typography.displaySmall.copy(
                                fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum",
                                letterSpacing = (-1).sp,
                                platformStyle = PlatformTextStyle(includeFontPadding = false)
                            ),
                            color = Color.White
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "L",
                            style = CockpitTheme.typography.titleLarge,
                            color = Palette.CyanElectric,
                            modifier = Modifier.padding(bottom = 3.dp)
                        )
                    }
                    Text(
                        text = if (dispensedLiters > 0.05) "Target Volume (+${NumberFormatUtils.formatFuelLiters(dispensedLiters, 1)}L refueled)" else "Current Tank Volume (${postRefillLiters.toInt()}L)",
                        style = CockpitTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = Palette.Slate400
                    )
                }

                // Right: Tank Fill %
                Column(horizontalAlignment = Alignment.End) {
                    val pctColor = when {
                        tankPct <= 20 -> CockpitTheme.colors.critical
                        tankPct <= 45 -> CockpitTheme.colors.warning
                        else -> CockpitTheme.colors.positive
                    }
                    Text(
                        text = "$tankPct%",
                        style = CockpitTheme.typography.displaySmall.copy(
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum",
                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                        ),
                        color = pctColor
                    )
                    Text(
                        text = if (isOverfill) "OVERFILL LIMIT WARNING" else "MID Bar $postRefillBar of 10",
                        style = CockpitTheme.typography.labelSmall.copy(
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                        ),
                        color = if (isOverfill) CockpitTheme.colors.warning else Palette.Slate500
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
                            1, 2 -> Palette.Red500 // Red (Reserve/Empty)
                            3, 4, 5 -> Palette.OrangeA400 // Orange/Amber (Low/Mid)
                            else -> Palette.Emerald500 // Green (Upper/Full)
                        }

                        val segmentColor = when {
                            isFilled -> baseBarColor
                            else -> baseBarColor.copy(alpha = 0.12f) // Backlit lens tint
                        }

                        val borderColor = when {
                            isSelected -> Color.White
                            isAdded -> Palette.CyanElectric
                            isFilled -> baseBarColor.copy(alpha = 0.85f)
                            else -> Color(0xFF1E2B40)
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .cockpitSurface(segmentColor, borderColor, CopilotShapes.R4, borderWidth = if (isSelected) 1.8.dp else 1.dp)
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
                        style = CockpitTheme.typography.labelSmall.copy(
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                        ),
                        color = Palette.Red500
                    )
                    Text(
                        text = "1/4 (25%)",
                        style = CockpitTheme.typography.labelSmall.copy(
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                        ),
                        color = Palette.OrangeA400
                    )
                    Text(
                        text = "1/2 (50%)",
                        style = CockpitTheme.typography.labelSmall.copy(
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                        ),
                        color = Color(0xFFEAB308)
                    )
                    Text(
                        text = "3/4 (75%)",
                        style = CockpitTheme.typography.labelSmall.copy(
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                        ),
                        color = Color(0xFF22C55E)
                    )
                    Text(
                        text = "F (${designCap.toInt()}L)",
                        style = CockpitTheme.typography.labelSmall.copy(
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                        ),
                        color = Palette.Emerald500
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
                        .cockpitSurface(Palette.Ink875, Palette.Ink675, CopilotShapes.R8)
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "FUEL ADDED",
                            style = CockpitTheme.typography.labelSmall.copy(
                                letterSpacing = 0.5.sp
                            ),
                            color = Palette.Slate400
                        )
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = NumberFormatUtils.formatFuelLiters(dispensedLiters, 2),
                                style = CockpitTheme.typography.headlineMedium.copy(
                                    fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                                ),
                                color = Color.White
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                text = "L",
                                style = CockpitTheme.typography.labelSmall,
                                color = Palette.CyanElectric,
                                modifier = Modifier.padding(bottom = 1.dp)
                            )
                        }
                        Text(
                            text = if (addedPct > 0) "+$addedPct% tank boost" else "0% added",
                            style = CockpitTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = if (addedPct > 0) Palette.CyanElectric else Palette.Slate500,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Box 2: RANGE GAIN
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .cockpitSurface(Palette.Ink875, Palette.Ink675, CopilotShapes.R8)
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "RANGE GAIN",
                            style = CockpitTheme.typography.labelSmall.copy(
                                letterSpacing = 0.5.sp
                            ),
                            color = Palette.Slate400
                        )
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = if (addedRangeKm > 0.1) "+${addedRangeKm.toInt()} km" else "+0 km",
                                style = CockpitTheme.typography.headlineMedium.copy(
                                    fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                                ),
                                color = Palette.Emerald500
                            )
                        }
                        Text(
                            text = "@ ${NumberFormatUtils.formatMileage(effectiveMileage, 1)} km/L",
                            style = CockpitTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = Palette.Emerald500.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Box 3: RUNNING COST
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .cockpitSurface(Palette.Ink875, Palette.Ink675, CopilotShapes.R8)
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "RUNNING COST",
                            style = CockpitTheme.typography.labelSmall.copy(
                                letterSpacing = 0.5.sp
                            ),
                            color = Palette.Slate400
                        )
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = "₹${NumberFormatUtils.formatPrecision(runningCostPerKm, 2)}",
                                style = CockpitTheme.typography.headlineMedium.copy(
                                    fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                                ),
                                color = Palette.Amber500
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                text = "/km",
                                style = CockpitTheme.typography.labelSmall,
                                color = Palette.Amber500.copy(alpha = 0.85f),
                                modifier = Modifier.padding(bottom = 1.dp)
                            )
                        }
                        Text(
                            text = "Real-time cost rate",
                            style = CockpitTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = Palette.Amber500.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Box 4: TOTAL RANGE
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .cockpitSurface(Palette.Ink875, Palette.Ink675, CopilotShapes.R8)
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "TOTAL RANGE",
                            style = CockpitTheme.typography.labelSmall.copy(
                                letterSpacing = 0.5.sp
                            ),
                            color = Palette.Slate400
                        )
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = "${totalRangeKm.toInt()} km",
                                style = CockpitTheme.typography.headlineMedium.copy(
                                    fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                                ),
                                color = Palette.Sky400
                            )
                        }
                        Text(
                            text = "${postRefillLiters.toInt()}L post-refill",
                            style = CockpitTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = Palette.Sky400.copy(alpha = 0.85f),
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
                        .background(Palette.Ink825)
                ) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        val currentFraction = (currentFuelLiters / designCap).toFloat().coerceIn(0f, 1f)
                        val addedFraction = (dispensedLiters / designCap).toFloat().coerceIn(0f, 1f - currentFraction)
                        if (currentFraction > 0.005f) {
                            Box(
                                modifier = Modifier
                                    .weight(currentFraction)
                                    .fillMaxHeight()
                                    .background(Palette.Emerald500)
                            )
                        }
                        if (addedFraction > 0.005f) {
                            Box(
                                modifier = Modifier
                                    .weight(addedFraction)
                                    .fillMaxHeight()
                                    .background(Palette.CyanElectric)
                            )
                        }
                        val remainingFraction = (1f - currentFraction - addedFraction).coerceAtLeast(0f)
                        if (remainingFraction > 0.005f) {
                            Box(
                                modifier = Modifier
                                    .weight(remainingFraction)
                                    .fillMaxHeight()
                                    .background(Palette.Ink825)
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
                        style = CockpitTheme.typography.labelMedium.copy(
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                        ),
                        color = Palette.Slate400
                    )
                    Text(
                        text = "Post-Refill: ${tankPct}% (${postRefillLiters.toInt()}L)",
                        style = CockpitTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Black,
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                        ),
                        color = if (isOverfill) CockpitTheme.colors.warning else Palette.CyanElectric
                    )
                }
            }
        }
    }
}
