package com.antigravity.copilot.feature.fuel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.antigravity.copilot.core.designsystem.CockpitTheme
import com.antigravity.copilot.core.designsystem.components.Raahi
import com.antigravity.copilot.core.designsystem.components.RaahiLabel
import com.antigravity.copilot.core.designsystem.components.StatCell
import com.antigravity.copilot.core.designsystem.components.fuelLevelColor
import com.antigravity.copilot.core.designsystem.components.raahiCard
import com.antigravity.copilot.core.util.NumberFormatUtils
import com.antigravity.copilot.feature.dashboard.fuelLevelTextColor

/**
 * Refuel projection inside "Log refuel". The gauge here is an **indicator only**: it shows the
 * current fill and the portion this refuel adds, and never reacts to touch. The gauge is
 * calibrated only from the home-screen fuel card.
 *
 * Numbers: tank after refuel (L, %), fuel added, range gain, running cost per km, total range.
 */
@Composable
internal fun RefuelProjectionCard(
    currentPoints: Double,
    afterPoints: Double,
    currentFuelLiters: Double,
    dispensedLiters: Double,
    postRefillLiters: Double,
    designCap: Double,
    effectiveMileage: Double,
    pricePerLiter: Double,
    isOverfill: Boolean,
    modifier: Modifier = Modifier
) {
    val c = CockpitTheme.colors
    val tankPct = ((postRefillLiters / designCap) * 100).coerceIn(0.0, 100.0)
    val currentPct = ((currentFuelLiters / designCap) * 100).toInt().coerceIn(0, 100)
    val addedRangeKm = dispensedLiters * effectiveMileage
    val runningCostPerKm = if (effectiveMileage > 0.0) pricePerLiter / effectiveMileage else 0.0
    val totalRangeKm = postRefillLiters * effectiveMileage

    Column(
        modifier = modifier
            .raahiCard(raised = true)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RaahiLabel("Tank after refuel", modifier = Modifier.weight(1f))
            Text(
                text = "Tank ${designCap.toInt()} L",
                style = CockpitTheme.typography.labelMedium,
                color = c.textSecondary
            )
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                NumberFormatUtils.formatFuelLiters(postRefillLiters, 1),
                style = CockpitTheme.typography.displaySmall,
                color = c.textPrimary,
                maxLines = 1
            )
            Spacer(Modifier.width(4.dp))
            Text("L", style = CockpitTheme.typography.titleMedium, color = c.textSecondary, modifier = Modifier.padding(bottom = 4.dp))
            Spacer(Modifier.weight(1f))
            Text(
                "${tankPct.toInt()}%",
                style = CockpitTheme.typography.headlineLarge,
                color = fuelLevelTextColor(tankPct),
                maxLines = 1
            )
        }

        // Display-only gauge in the home-card zone colours (red / yellow / green):
        // solid = fuel already in the tank, lighter = this refuel.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
                .semantics { contentDescription = "Projected fuel gauge, ${tankPct.toInt()} percent" },
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            for (i in 1..10) {
                val base = (currentPoints - (i - 1)).coerceIn(0.0, 1.0).toFloat()
                val total = (afterPoints - (i - 1)).coerceIn(0.0, 1.0).toFloat()
                val shape = RoundedCornerShape(6.dp)
                val zone = when {
                    i <= 2 -> c.critical
                    i <= 4 -> c.warning
                    else -> c.positive
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(shape)
                        .background(zone.copy(alpha = 0.14f))
                        .border(1.dp, zone.copy(alpha = 0.35f), shape)
                ) {
                    if (total > 0f) Box(Modifier.fillMaxHeight().fillMaxWidth(total).clip(shape).background(zone.copy(alpha = 0.5f)))
                    if (base > 0f) Box(Modifier.fillMaxHeight().fillMaxWidth(base).clip(shape).background(zone))
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                "Now $currentPct% (${NumberFormatUtils.formatFuelLiters(currentFuelLiters, 1)} L)",
                style = CockpitTheme.typography.bodySmall,
                color = c.textSecondary,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            if (isOverfill) {
                Text("More than the tank holds", style = CockpitTheme.typography.bodySmall, color = c.criticalText, maxLines = 1)
            }
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            StatCell(
                "Fuel added",
                NumberFormatUtils.formatFuelLiters(dispensedLiters, 2),
                unit = "L",
                valueStyle = CockpitTheme.typography.headlineMedium,
                valueColor = c.positiveText,
                modifier = Modifier.weight(1f)
            )
            StatCell(
                "Range gain",
                "+${kotlin.math.round(addedRangeKm).toInt()}",
                unit = "km",
                valueStyle = CockpitTheme.typography.headlineMedium,
                valueColor = c.accentText,
                modifier = Modifier.weight(1f)
            )
            StatCell(
                "Cost / km",
                "₹${NumberFormatUtils.formatPrecision(runningCostPerKm, 2)}",
                valueStyle = CockpitTheme.typography.headlineMedium,
                valueColor = c.warningText,
                modifier = Modifier.weight(1f)
            )
            StatCell(
                "Total range",
                "${kotlin.math.round(totalRangeKm).toInt()}",
                unit = "km",
                valueStyle = CockpitTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.End
            )
        }
        Text(
            "Range at ${NumberFormatUtils.formatMileage(effectiveMileage, 1)} km/L",
            style = CockpitTheme.typography.bodySmall,
            color = c.textSecondary
        )
    }
}
