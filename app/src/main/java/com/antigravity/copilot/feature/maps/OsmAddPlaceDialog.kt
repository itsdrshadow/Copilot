package com.antigravity.copilot.feature.maps

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddLocationAlt
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.antigravity.copilot.core.designsystem.CockpitTheme
import com.antigravity.copilot.core.designsystem.components.Raahi
import com.antigravity.copilot.core.designsystem.components.RaahiDialogHost
import com.antigravity.copilot.core.model.TripPoint
import com.antigravity.copilot.core.osm.OsmNoteStatus
import com.antigravity.copilot.core.osm.OsmPlaceNoteEntity
import com.antigravity.copilot.core.osm.OsmPlaceNotes
import kotlinx.coroutines.launch

/**
 * "Add place to OSM": name a place the map does not know at the car's current position. Saved
 * offline at once; sent to OpenStreetMap as a public note when internet is available. Shows the
 * last few entries with their state (waiting / sent / failed).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OsmAddPlaceDialog(position: TripPoint?, onDismiss: () -> Unit) {
    val c = CockpitTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recent by remember { OsmPlaceNotes.observeRecent(context) }.collectAsState(initial = emptyList())
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(OsmPlaceNotes.categories.first()) }
    var details by remember { mutableStateOf("") }
    val canSave = position != null && name.isNotBlank()

    RaahiDialogHost(onDismissRequest = onDismiss, maxWidth = 680.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(Raahi.CardShape)
                .background(c.cardElevated)
                .border(Raahi.Border, c.border, Raahi.CardShape)
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.AddLocationAlt, contentDescription = null, tint = c.accent, modifier = Modifier.size(28.dp))
                Column {
                    Text("Add place to OpenStreetMap", style = CockpitTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = c.textPrimary)
                    Text(
                        position?.let { "At your position · %.5f, %.5f".format(java.util.Locale.US, it.latitude, it.longitude) }
                            ?: "Waiting for GPS…",
                        style = CockpitTheme.typography.bodyMedium,
                        color = c.textSecondary
                    )
                }
            }

            val fieldColors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = c.textPrimary,
                unfocusedTextColor = c.textPrimary,
                focusedBorderColor = c.accent,
                unfocusedBorderColor = c.textSecondary.copy(alpha = 0.6f)
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(80) },
                label = { Text("Place name", color = c.textSecondary) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = CockpitTheme.typography.titleMedium.copy(color = c.textPrimary),
                colors = fieldColors
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OsmPlaceNotes.categories.forEach { cat ->
                    val sel = cat == category
                    Text(
                        text = cat,
                        style = CockpitTheme.typography.labelLarge,
                        color = if (sel) c.onAccent else c.textPrimary,
                        modifier = Modifier
                            .clip(Raahi.PillShape)
                            .background(if (sel) c.accent else c.card)
                            .border(Raahi.Border, if (sel) c.accent else c.border, Raahi.PillShape)
                            .clickable(role = Role.RadioButton) { category = cat }
                            .heightIn(min = 44.dp)
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }

            OutlinedTextField(
                value = details,
                onValueChange = { details = it.take(240) },
                label = { Text("Details (optional) · e.g. open 24 h, near the bridge", color = c.textSecondary) },
                modifier = Modifier.fillMaxWidth(),
                textStyle = CockpitTheme.typography.bodyLarge.copy(color = c.textPrimary),
                colors = fieldColors,
                maxLines = 3
            )

            Text(
                "Saved on this device now. When internet is available it is sent to OpenStreetMap as a public note; " +
                    "volunteer mappers then add it to the map (and to your offline map on the next update).",
                style = CockpitTheme.typography.bodySmall,
                color = c.textSecondary
            )

            if (recent.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    recent.take(4).forEach { RecentNoteRow(it) }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                    Text("Close", color = c.textPrimary)
                }
                Button(
                    onClick = {
                        val p = position ?: return@Button
                        scope.launch {
                            OsmPlaceNotes.add(context, p.latitude, p.longitude, name, category, details)
                            name = ""
                            details = ""
                        }
                    },
                    enabled = canSave,
                    colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
                    modifier = Modifier.weight(1.6f).heightIn(min = 52.dp)
                ) {
                    Text("Save place", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun RecentNoteRow(n: OsmPlaceNoteEntity) {
    val c = CockpitTheme.colors
    val status = runCatching { OsmNoteStatus.valueOf(n.status) }.getOrDefault(OsmNoteStatus.PENDING)
    val (icon, tint, label) = when (status) {
        OsmNoteStatus.PENDING -> Triple(Icons.Outlined.CloudUpload, c.warning, "Waiting for internet")
        OsmNoteStatus.SENT -> Triple(Icons.Outlined.CloudDone, c.positive, "Sent to OSM" + (n.osmNoteId?.let { " · note #$it" } ?: ""))
        OsmNoteStatus.FAILED -> Triple(Icons.Outlined.ErrorOutline, c.critical, "Not accepted" + (n.lastError?.let { " · $it" } ?: ""))
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text("${n.name} · ${n.category}", style = CockpitTheme.typography.bodyMedium, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.size(4.dp))
        Text(label, style = CockpitTheme.typography.bodySmall, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
