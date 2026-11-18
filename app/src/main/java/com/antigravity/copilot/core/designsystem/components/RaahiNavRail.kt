package com.antigravity.copilot.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.antigravity.copilot.core.designsystem.CockpitTheme

data class RailItem<K>(
    val key: K,
    val label: String,
    val icon: ImageVector,
)

/**
 * Left navigation rail (80 dp). Active item: cardRaised background with cyan icon and label;
 * inactive: textSecondary. [bottomItems] are pinned to the bottom (Settings).
 */
@Composable
fun <K> RaahiNavRail(
    items: List<RailItem<K>>,
    bottomItems: List<RailItem<K>>,
    selected: K?,
    onSelect: (K) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = CockpitTheme.colors
    Column(
        modifier =
            modifier
                .width(Raahi.RailWidth)
                .fillMaxHeight()
                .background(c.rail)
                .padding(vertical = 8.dp, horizontal = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items.forEach { RailButton(it, it.key == selected) { onSelect(it.key) } }
        }
        Spacer(Modifier.size(6.dp))
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            bottomItems.forEach { RailButton(it, it.key == selected) { onSelect(it.key) } }
        }
    }
}

/**
 * Compact-width (< 600 dp) replacement for [RaahiNavRail]: the same items in one bottom row, so a
 * phone keeps its full width for the map. Each tab is ≥ 64 dp tall and shares the width equally.
 */
@Composable
fun <K> RaahiBottomBar(
    items: List<RailItem<K>>,
    bottomItems: List<RailItem<K>>,
    selected: K?,
    onSelect: (K) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(CockpitTheme.colors.rail)
                .padding(horizontal = 5.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        (items + bottomItems).forEach {
            RailButton(it, it.key == selected, Modifier.weight(1f)) { onSelect(it.key) }
        }
    }
}

@Composable
private fun <K> RailButton(
    item: RailItem<K>,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = CockpitTheme.colors
    val shape = RoundedCornerShape(16.dp)
    val tint = if (isActive) c.accent else c.textSecondary
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clip(shape)
                .background(if (isActive) c.cardElevated else Color.Transparent)
                .border(1.dp, if (isActive) c.border else Color.Transparent, shape)
                .semantics { selected = isActive }
                .clickable(role = Role.Tab, onClick = onClick)
                .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
        }
        // Rail labels follow the system font scale up to 1.15x (never below 14 sp), so every
        // label still fits the fixed 80 dp rail at 1.3x instead of being cut off.
        val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
        val railScale = minOf(fontScale, 1.15f) / fontScale
        val base = CockpitTheme.typography.labelSmall
        Text(
            text = item.label,
            style = base.copy(fontSize = base.fontSize * railScale, lineHeight = base.lineHeight * railScale),
            softWrap = false,
            color = if (isActive) c.accentText else c.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
