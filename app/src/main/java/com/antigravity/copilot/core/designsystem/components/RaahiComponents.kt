package com.antigravity.copilot.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.antigravity.copilot.core.designsystem.CockpitTheme

/** Raahi layout constants. */
object Raahi {
    val CardRadius: Dp = 20.dp
    val InnerRadius: Dp = 12.dp
    val PillRadius: Dp = 999.dp
    val Border: Dp = 1.dp
    val CardPadding: Dp = 16.dp
    val CardPaddingTight: Dp = 14.dp
    val Gap: Dp = 10.dp
    val RailWidth: Dp = 76.dp
    val RightPanelWidth: Dp = 372.dp
    val MinTouch: Dp = 48.dp
    val RecenterSize: Dp = 52.dp
    val CardShape = RoundedCornerShape(CardRadius)
    val InnerShape = RoundedCornerShape(InnerRadius)
    val PillShape = RoundedCornerShape(PillRadius)
}

/** Fuel level colour by percent: > 40 % green, 15–40 % amber, < 15 % red. */
@Composable
fun fuelLevelColor(percent: Double): Color = when {
    percent < 15.0 -> CockpitTheme.colors.critical
    percent <= 40.0 -> CockpitTheme.colors.warning
    else -> CockpitTheme.colors.positive
}

/** Flat solid card surface: 20 dp radius, 1 dp border, no blur, no gradient. */
@Composable
fun Modifier.raahiCard(raised: Boolean = false, borderColor: Color? = null): Modifier {
    val c = CockpitTheme.colors
    return this
        .clip(Raahi.CardShape)
        .background(if (raised) c.cardElevated else c.card)
        .border(Raahi.Border, borderColor ?: c.border, Raahi.CardShape)
}

@Composable
fun RaahiCard(
    modifier: Modifier = Modifier,
    raised: Boolean = false,
    padding: PaddingValues = PaddingValues(Raahi.CardPadding),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(8.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier.raahiCard(raised).padding(padding),
        verticalArrangement = verticalArrangement,
        content = content
    )
}

/** Small uppercase-free card caption in textSecondary. */
@Composable
fun RaahiLabel(text: String, modifier: Modifier = Modifier, color: Color = CockpitTheme.colors.textSecondary) {
    Text(
        text = text,
        style = CockpitTheme.typography.labelMedium,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

/** Rounded status chip with an optional leading dot or icon; colour carries the state. */
@Composable
fun RaahiPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    filled: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val c = CockpitTheme.colors
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 32.dp)
            .clip(Raahi.PillShape)
            .background(if (filled) color.copy(alpha = 0.16f) else c.cardElevated)
            .border(Raahi.Border, if (filled) color.copy(alpha = 0.55f) else c.border, Raahi.PillShape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        } else {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        }
        Text(
            text = text,
            style = CockpitTheme.typography.labelMedium,
            color = c.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 10-segment level bar. [fillPoints] is 0..[segments]; a partial top segment is drawn partially.
 */
@Composable
fun SegmentBar(
    fillPoints: Double,
    color: Color,
    modifier: Modifier = Modifier,
    segments: Int = 10,
    height: Dp = 12.dp
) {
    val track = CockpitTheme.colors.cardElevated
    val trackBorder = CockpitTheme.colors.border
    Row(
        modifier = modifier.fillMaxWidth().height(height),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        val fill = fillPoints.coerceIn(0.0, segments.toDouble())
        for (i in 1..segments) {
            val seg = (fill - (i - 1)).coerceIn(0.0, 1.0).toFloat()
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(track)
                    .border(Raahi.Border, trackBorder, RoundedCornerShape(3.dp))
            ) {
                if (seg > 0f) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(seg).background(color))
                }
            }
        }
    }
}

/** Label over value (+ unit) stat cell. */
@Composable
fun StatCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    valueColor: Color = CockpitTheme.colors.textPrimary,
    valueStyle: TextStyle = CockpitTheme.typography.headlineMedium,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start
) {
    Column(modifier = modifier, horizontalAlignment = horizontalAlignment) {
        RaahiLabel(label)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = valueStyle, color = valueColor, maxLines = 1, softWrap = false)
            if (unit != null) {
                Spacer(Modifier.width(4.dp))
                Text(
                    unit,
                    style = CockpitTheme.typography.labelMedium,
                    color = CockpitTheme.colors.textSecondary,
                    maxLines = 1,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
        }
    }
}

/** Stat card used by the 2-column grids (Trip summary). */
@Composable
fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    valueColor: Color = CockpitTheme.colors.textPrimary
) {
    Box(
        modifier = modifier
            .raahiCard()
            .padding(horizontal = Raahi.CardPaddingTight, vertical = 12.dp)
    ) {
        StatCell(label = label, value = value, unit = unit, valueColor = valueColor)
    }
}

enum class RaahiButtonKind { Primary, Secondary, Danger }

/** 48 dp+ button. Primary = cyan fill; Secondary = raised card with border. */
@Composable
fun RaahiButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    kind: RaahiButtonKind = RaahiButtonKind.Primary,
    enabled: Boolean = true
) {
    val c = CockpitTheme.colors
    val (bg, fg, bd) = when (kind) {
        RaahiButtonKind.Primary -> Triple(c.accent, c.onAccent, c.accent)
        RaahiButtonKind.Secondary -> Triple(c.cardElevated, c.textPrimary, c.border)
        RaahiButtonKind.Danger -> Triple(c.critical.copy(alpha = 0.14f), c.textPrimary, c.critical)
    }
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = Raahi.MinTouch)
            .clip(Raahi.InnerShape)
            .background(if (enabled) bg else c.cardElevated)
            .border(Raahi.Border, if (enabled) bd else c.border, Raahi.InnerShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val tint = if (enabled) fg else c.textDisabled
        if (icon != null) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(text, style = CockpitTheme.typography.labelLarge, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * One-line notice that is never cut off silently: the line ellipsizes, and an info button opens
 * the full text. [onDismiss], when given, adds a "Dismiss" action to the dialog.
 */
@Composable
fun InfoLine(
    text: String,
    modifier: Modifier = Modifier,
    title: String = "Details",
    color: Color = CockpitTheme.colors.textSecondary,
    onDismiss: (() -> Unit)? = null
) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Raahi.MinTouch)
            .clip(Raahi.InnerShape)
            .clickable(role = Role.Button, onClickLabel = "Show full text") { open = true }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Outlined.Info, contentDescription = "Show full text", tint = color, modifier = Modifier.size(20.dp))
        Text(
            text = text,
            style = CockpitTheme.typography.bodySmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title, style = CockpitTheme.typography.titleLarge) },
            text = {
                Text(
                    text,
                    style = CockpitTheme.typography.bodyLarge,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
            dismissButton = onDismiss?.let { d ->
                { TextButton(onClick = { open = false; d() }) { Text("Dismiss notice") } }
            }
        )
    }
}

/** Equal-width segmented toggle (e.g. Trail | Overlap | Mileage). */
@Composable
fun <T> SegmentedToggle(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = CockpitTheme.colors
    Row(
        modifier = modifier
            .clip(Raahi.PillShape)
            .background(c.card)
            .border(Raahi.Border, c.border, Raahi.PillShape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { (value, label) ->
            val isSel = value == selected
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = 72.dp, minHeight = 48.dp)
                    .clip(Raahi.PillShape)
                    .background(if (isSel) c.cardElevated else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(value) }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    style = CockpitTheme.typography.labelLarge,
                    color = if (isSel) c.accent else c.textSecondary,
                    maxLines = 1
                )
            }
        }
    }
}

/** Thin divider row helper for RowScope. */
@Composable
fun RowScope.VDivider(height: Dp = 28.dp) {
    Box(Modifier.width(1.dp).height(height).background(CockpitTheme.colors.border))
}
