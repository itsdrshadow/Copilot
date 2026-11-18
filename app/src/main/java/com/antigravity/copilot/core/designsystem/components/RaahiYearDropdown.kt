package com.antigravity.copilot.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.antigravity.copilot.core.designsystem.CockpitTheme

private val ItemHeight = 56.dp
private const val VISIBLE_ITEMS = 3

/**
 * Year picker for history screens: one compact button, and a popup that shows three years at a
 * time and scrolls for the rest, so it stays the same size however many years of data exist.
 */
@Composable
fun RaahiYearDropdown(
    years: List<Int>,
    selectedYear: Int,
    onYearSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = CockpitTheme.colors
    var open by remember { mutableStateOf(false) }
    val canOpen = years.size > 1

    Box(modifier) {
        Row(
            modifier = Modifier
                .height(Raahi.MinTouch)
                .clip(Raahi.InnerShape)
                .background(c.cardElevated)
                .border(Raahi.Border, if (open) c.accent else c.border, Raahi.InnerShape)
                .clickable(enabled = canOpen) { open = true }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "$selectedYear",
                style = CockpitTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontFeatureSettings = "tnum"
                ),
                color = c.textPrimary
            )
            if (canOpen) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = "Choose year",
                    tint = c.textSecondary,
                    modifier = Modifier.size(26.dp).rotate(if (open) 180f else 0f)
                )
            }
        }

        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            // DropdownMenu content already scrolls; capping the height shows three rows.
            modifier = Modifier
                .background(c.cardElevated)
                .widthIn(min = 140.dp)
                .heightIn(max = ItemHeight * VISIBLE_ITEMS + 16.dp)
        ) {
            years.forEach { year ->
                val selected = year == selectedYear
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "$year",
                            style = CockpitTheme.typography.titleMedium.copy(
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                fontFeatureSettings = "tnum"
                            ),
                            color = if (selected) c.accent else c.textPrimary
                        )
                    },
                    trailingIcon = if (selected) {
                        { Icon(Icons.Default.Check, contentDescription = null, tint = c.accent) }
                    } else null,
                    onClick = { open = false; onYearSelected(year) },
                    modifier = Modifier.height(ItemHeight)
                )
            }
        }
    }
}

/** "year-monthIndex" (0-based month) key for today, matching the history screens' month keys. */
fun currentMonthKey(): String {
    val cal = java.util.Calendar.getInstance()
    return "${cal.get(java.util.Calendar.YEAR)}-${cal.get(java.util.Calendar.MONTH)}"
}
