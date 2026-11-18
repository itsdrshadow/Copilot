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
    val currentPct = Math.round((currentFuelLiters / designCap) * 100).toInt().coerceIn(0, 100)
    val addedRangeKm = dispensedLiters * effectiveMileage
    val runningCostPerKm = if (effectiveMileage > 0.0) pricePerLiter / effectiveMileage else 0.0
    val totalRangeKm = postRefillLiters * effectiveMileage

    Column(
        modifier = modifier
            .raahiCard(raised = true)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Two answers a driver cares about: how full the tank will be, and how far that goes.
        Row(verticalAlignment = Alignment.Bottom) {
            Column(modifier = Modifier.weight(1f)) {
                RaahiLabel("Tank after this fill")
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "${Math.round(tankPct).toInt()}%",
                        style = CockpitTheme.typography.displaySmall,
                        color = c.textPrimary,
                        maxLines = 1
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${NumberFormatUtils.formatFuelLiters(postRefillLiters)} L",
                        style = CockpitTheme.typography.titleMedium,
                        color = c.textSecondary,
                        modifier = Modifier.padding(bottom = 5.dp)
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                RaahiLabel("You can drive about")
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "${kotlin.math.round(totalRangeKm).toInt()}",
                        style = CockpitTheme.typography.displaySmall,
                        color = c.textPrimary,
                        maxLines = 1
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("km", style = CockpitTheme.typography.titleMedium, color = c.textSecondary, modifier = Modifier.padding(bottom = 5.dp))
                }
            }
        }

        // Display-only gauge: solid = fuel already in the tank, lighter = this fill. One colour.
        val fill = c.accent
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp)
                .semantics { contentDescription = "Projected fuel gauge, ${Math.round(tankPct).toInt()} percent" },
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            for (i in 1..10) {
                val base = (currentPoints - (i - 1)).coerceIn(0.0, 1.0).toFloat()
                val total = (afterPoints - (i - 1)).coerceIn(0.0, 1.0).toFloat()
                val shape = RoundedCornerShape(6.dp)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(shape)
                        .background(c.card)
                        .border(1.dp, c.border, shape)
                ) {
                    if (total > 0f) Box(Modifier.fillMaxHeight().fillMaxWidth(total).clip(shape).background(fill.copy(alpha = 0.45f)))
                    if (base > 0f) Box(Modifier.fillMaxHeight().fillMaxWidth(base).clip(shape).background(fill))
                }
            }
        }
        Text(
            text = when {
                isOverfill -> "That is more than the tank holds"
                dispensedLiters > 0.0 -> "Now $currentPct%  ·  adds ${NumberFormatUtils.formatFuelLiters(dispensedLiters)} L (+${kotlin.math.round(addedRangeKm).toInt()} km)  ·  ₹${NumberFormatUtils.formatPrecision(runningCostPerKm, 1)} per km"
                else -> "Now $currentPct% (${NumberFormatUtils.formatFuelLiters(currentFuelLiters)} L)"
            },
            style = CockpitTheme.typography.bodyMedium,
            color = if (isOverfill) c.criticalText else c.textSecondary,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}
