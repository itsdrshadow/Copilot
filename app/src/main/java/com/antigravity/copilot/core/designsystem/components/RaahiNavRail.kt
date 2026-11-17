package com.antigravity.copilot.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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

data class RailItem<K>(val key: K, val label: String, val icon: ImageVector)

/**
 * Left navigation rail (76 dp). Active item: cardRaised background with cyan icon and label;
 * inactive: textSecondary. [bottomItems] are pinned to the bottom (Settings).
 */
@Composable
fun <K> RaahiNavRail(
    items: List<RailItem<K>>,
    bottomItems: List<RailItem<K>>,
    selected: K?,
    onSelect: (K) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = CockpitTheme.colors
    Column(
        modifier = modifier
            .width(Raahi.RailWidth)
            .fillMaxHeight()
            .background(c.rail)
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            items.forEach { RailButton(it, it.key == selected) { onSelect(it.key) } }
        }
        Spacer(Modifier.size(6.dp))
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            bottomItems.forEach { RailButton(it, it.key == selected) { onSelect(it.key) } }
        }
    }
}

@Composable
private fun <K> RailButton(item: RailItem<K>, isActive: Boolean, onClick: () -> Unit) {
    val c = CockpitTheme.colors
    val shape = RoundedCornerShape(16.dp)
    val tint = if (isActive) c.accent else c.textSecondary
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(shape)
            .background(if (isActive) c.cardElevated else Color.Transparent)
            .border(1.dp, if (isActive) c.border else Color.Transparent, shape)
            .semantics { selected = isActive }
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        }
        Text(
            text = item.label,
            style = CockpitTheme.typography.labelSmall,
            color = if (isActive) c.accentText else c.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}
