package com.antigravity.copilot.feature.settings

import com.antigravity.copilot.core.designsystem.components.contentColorOn
import com.antigravity.copilot.core.designsystem.tokens.StaticPalette
import androidx.hilt.navigation.compose.hiltViewModel
import com.antigravity.copilot.core.designsystem.components.cockpitSurface
import com.antigravity.copilot.core.designsystem.tokens.Palette
import com.antigravity.copilot.core.designsystem.tokens.CopilotShapes
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.copilot.core.designsystem.CockpitTheme
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.antigravity.copilot.core.database.TripGeofenceEntity
import com.antigravity.copilot.core.designsystem.AutomotiveDesignTokens
import com.antigravity.copilot.core.util.NumberFormatUtils
import kotlinx.coroutines.launch
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import java.util.Locale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import com.antigravity.copilot.core.map.StaticMapCamera
import com.antigravity.copilot.feature.maps.drawSnapshot
import com.antigravity.copilot.feature.maps.rememberStaticMapSnapshot

@Composable
fun GeofenceSettingsContent(
    viewModel: GeofenceSettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val geofences by viewModel.geofences.collectAsStateWithLifecycle()
    val (currentLat, currentLon) = viewModel.lastKnownPosition

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { msg ->
            Toast.makeText(context, msg, if (msg.length > 40) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }

    var isAddEditDialogOpen by remember { mutableStateOf(false) }
    var selectedGeofenceForEdit by remember { mutableStateOf<TripGeofenceEntity?>(null) }
    var geofenceToDelete by remember { mutableStateOf<TripGeofenceEntity?>(null) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Header Overview & Add Action Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Palette.Slate900),
            shape = CopilotShapes.R14,
            border = androidx.compose.foundation.BorderStroke(1.dp, Palette.Slate800)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Place,
                            contentDescription = null,
                            tint = Palette.Sky400,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Saved places",
                            style = CockpitTheme.typography.titleMedium,
                            color = CockpitTheme.colors.textPrimary
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "A trip starts when you leave a saved place and ends when you reach one.",
                        style = CockpitTheme.typography.bodyMedium,
                        color = Palette.Slate400
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Button(
                    onClick = {
                        selectedGeofenceForEdit = null
                        isAddEditDialogOpen = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Sky600),
                    shape = CopilotShapes.R12,
                    modifier = Modifier.height(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AddLocationAlt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Add place", style = CockpitTheme.typography.labelLarge, color = contentColorOn(Palette.Sky600))
                }
            }
        }

        // 2. Geofences List
        if (geofences.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Palette.Ink925),
                shape = CopilotShapes.R14,
                border = androidx.compose.foundation.BorderStroke(1.dp, Palette.Ink750)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Place,
                        contentDescription = null,
                        tint = Palette.Slate600,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No saved places yet",
                        style = CockpitTheme.typography.titleSmall,
                        color = CockpitTheme.colors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Add Home or Office so trips start and end by themselves.",
                        color = Palette.Steel400,
                        style = CockpitTheme.typography.bodySmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            geofences.forEach { gf ->
                val distanceMeters = viewModel.distanceMeters(gf)
                val isInside = distanceMeters <= gf.radiusMeters

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (gf.isEnabled) Palette.Ink925 else CockpitTheme.colors.cardElevated
                    ),
                    shape = CopilotShapes.R14,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isInside && gf.isEnabled) Palette.Emerald500 else Palette.Ink750
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left: Icon & Details
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (gf.isEnabled) Palette.Sky600.copy(alpha = 0.2f)
                                        else Palette.Slate700.copy(alpha = 0.2f)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (gf.name.contains("Home", ignoreCase = true)) Icons.Default.Home
                                    else if (gf.name.contains("Office", ignoreCase = true) || gf.name.contains("Work", ignoreCase = true)) Icons.Default.Business
                                    else Icons.Default.Place,
                                    contentDescription = null,
                                    tint = if (gf.isEnabled) Palette.Sky400 else Palette.Slate500,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = gf.name,
                                        style = CockpitTheme.typography.titleMedium,
                                        color = if (gf.isEnabled) CockpitTheme.colors.textPrimary else Palette.Slate500
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    // Radius pill
                                    Box(
                                        modifier = Modifier
                                            .clip(CopilotShapes.R8)
                                            .background(Palette.Sky700.copy(alpha = 0.25f))
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = "${gf.radiusMeters.toInt()} m around",
                                            style = CockpitTheme.typography.labelMedium.copy(
                                                fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily,
                                                fontFeatureSettings = "tnum"
                                            ),
                                            color = Palette.Sky400
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(6.dp))

                                    // Distance / Inside pill
                                    if (isInside && gf.isEnabled) {
                                        Box(
                                            modifier = Modifier
                                                .clip(CopilotShapes.R6)
                                                .background(Palette.Emerald500.copy(alpha = 0.25f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "You are here",
                                                style = CockpitTheme.typography.labelMedium,
                                                color = CockpitTheme.colors.positive
                                            )
                                        }
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .clip(CopilotShapes.R6)
                                                .background(Palette.Slate700.copy(alpha = 0.35f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            val distText = if (distanceMeters >= 1000f) {
                                                "${NumberFormatUtils.formatDistance(distanceMeters / 1000.0)} km away"
                                            } else {
                                                "${distanceMeters.toInt()}m away"
                                            }
                                            Text(
                                                text = distText,
                                                style = CockpitTheme.typography.labelMedium.copy(
                                                    fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily,
                                                    fontFeatureSettings = "tnum"
                                                ),
                                                color = Palette.Slate400
                                            )
                                        }
                                    }
                                }


                            }
                        }

                        // Right: Controls (Enable Switch, Edit, Delete)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = gf.isEnabled,
                                onCheckedChange = { checked -> viewModel.toggle(gf.id, checked) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = androidx.compose.ui.graphics.Color.White,
                                    checkedTrackColor = CockpitTheme.colors.accent,
                                    checkedBorderColor = CockpitTheme.colors.accent,
                                    uncheckedThumbColor = CockpitTheme.colors.textSecondary,
                                    uncheckedTrackColor = CockpitTheme.colors.cardElevated,
                                    uncheckedBorderColor = CockpitTheme.colors.border
                                )
                            )

                            Spacer(modifier = Modifier.width(10.dp))

                            IconButton(
                                onClick = {
                                    selectedGeofenceForEdit = gf
                                    isAddEditDialogOpen = true
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .cockpitSurface(CockpitTheme.colors.accentSoft, Palette.Sky400.copy(alpha = 0.55f), CircleShape, borderWidth = 1.2.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Edit Geofence",
                                    tint = Palette.Sky400,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            IconButton(
                                onClick = { geofenceToDelete = gf },
                                modifier = Modifier
                                    .size(48.dp)
                                    .cockpitSurface(CockpitTheme.colors.critical.copy(alpha = 0.14f), Palette.Rose500.copy(alpha = 0.55f), CircleShape, borderWidth = 1.2.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "Delete Geofence",
                                    tint = Palette.Rose500,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Delete Confirmation Dialog
    geofenceToDelete?.let { gf ->
        com.antigravity.copilot.core.designsystem.components.RaahiConfirmDialog(
            title = "Delete '${gf.name}'?",
            message = "Trips will no longer start or end here by themselves.",
            confirmText = "Delete",
            destructive = true,
            icon = Icons.Default.Delete,
            onConfirm = {
                viewModel.delete(gf.id)
                geofenceToDelete = null
            },
            onDismiss = { geofenceToDelete = null }
        )
    }

    // Add / Edit Geofence Dialog
    if (isAddEditDialogOpen) {
        AddEditGeofenceDialog(
            existingGeofence = selectedGeofenceForEdit,
            currentDeviceLat = currentLat,
            currentDeviceLon = currentLon,
            onDismiss = { isAddEditDialogOpen = false },
            onSave = { name, lat, lon, radius ->
                viewModel.save(selectedGeofenceForEdit, name, lat, lon, radius) {
                    isAddEditDialogOpen = false
                }
            }
        )
    }
}

@Composable
fun AddEditGeofenceDialog(
    existingGeofence: TripGeofenceEntity?,
    currentDeviceLat: Double,
    currentDeviceLon: Double,
    onDismiss: () -> Unit,
    onSave: (name: String, lat: Double, lon: Double, radius: Float) -> Unit
) {
    var name by remember { mutableStateOf(existingGeofence?.name ?: "") }
    var latText by remember { mutableStateOf(String.format(Locale.US, "%.5f", existingGeofence?.latitude ?: currentDeviceLat)) }
    var lonText by remember { mutableStateOf(String.format(Locale.US, "%.5f", existingGeofence?.longitude ?: currentDeviceLon)) }
    // S5: new places start at the size chosen in Settings › General.
    val defaultRadius = com.antigravity.copilot.core.settings.AppSettingsManager.settingsState.value.defaultGeofenceRadiusMeters
        .coerceIn(50f, 150f).let { (Math.round(it / 25f) * 25f) }
    var radiusMeters by remember { mutableFloatStateOf(existingGeofence?.radiusMeters?.coerceIn(50f, 150f) ?: defaultRadius) }
    var showCoordinates by remember { mutableStateOf(false) }

    val parsedLat = latText.toDoubleOrNull() ?: currentDeviceLat
    val parsedLon = lonText.toDoubleOrNull() ?: currentDeviceLon

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .wrapContentHeight()
                .heightIn(max = 660.dp)
                .cockpitSurface(Palette.Slate900, Palette.Slate800, CopilotShapes.R18, borderWidth = 1.5.dp)
                .padding(16.dp)
        ) {
            val dialogContext = LocalContext.current
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Dialog Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (existingGeofence != null) Icons.Default.EditLocation else Icons.Default.AddLocationAlt,
                            contentDescription = null,
                            tint = Palette.Sky400,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (existingGeofence != null) "Edit place" else "Add a place",
                            style = CockpitTheme.typography.titleMedium,
                            color = CockpitTheme.colors.textPrimary
                        )
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Palette.Steel400
                        )
                    }
                }

                // Two columns: Left (Inputs), Right (Map Preview)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                    // Left Inputs
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Name
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Place name", color = Palette.Slate400) },
                            placeholder = { Text("e.g. Home, Office", color = Palette.Slate500) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Palette.Sky400,
                                unfocusedBorderColor = Palette.Slate700,
                                focusedTextColor = CockpitTheme.colors.textPrimary,
                                unfocusedTextColor = CockpitTheme.colors.textPrimary
                            )
                        )

                        // Quick GPS Fill
                        Button(
                            onClick = {
                                latText = currentDeviceLat.toString()
                                lonText = currentDeviceLon.toString()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Palette.Slate800),
                            shape = CopilotShapes.R8,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.MyLocation,
                                contentDescription = null,
                                tint = contentColorOn(Palette.Slate800),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Use where I am now",
                                style = CockpitTheme.typography.labelLarge,
                                color = contentColorOn(Palette.Slate800))
                        }

                        // Coordinates: advanced, for typing a place by hand (5 decimals is ~1 m).
                        Text(
                            text = if (showCoordinates) "Hide coordinates" else "Advanced: type coordinates",
                            style = CockpitTheme.typography.labelLarge,
                            color = CockpitTheme.colors.accent,
                            modifier = Modifier
                                .clip(CopilotShapes.R8)
                                .clickable { showCoordinates = !showCoordinates }
                                .padding(vertical = 10.dp)
                        )
                        if (showCoordinates) Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = latText,
                                onValueChange = { latText = it },
                                label = { Text("Latitude", color = Palette.Slate400) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Palette.Sky400,
                                    unfocusedBorderColor = Palette.Slate700,
                                    focusedTextColor = CockpitTheme.colors.textPrimary,
                                    unfocusedTextColor = CockpitTheme.colors.textPrimary
                                )
                            )
                            OutlinedTextField(
                                value = lonText,
                                onValueChange = { lonText = it },
                                label = { Text("Longitude", color = Palette.Slate400) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Palette.Sky400,
                                    unfocusedBorderColor = Palette.Slate700,
                                    focusedTextColor = CockpitTheme.colors.textPrimary,
                                    unfocusedTextColor = CockpitTheme.colors.textPrimary
                                )
                            )
                        }

                        // Radius Slider & Quick Presets (Focused 50m - 150m)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Size: ${radiusMeters.toInt()} m around the point",
                                    style = CockpitTheme.typography.titleSmall.copy(
                                        fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily,
                                        fontFeatureSettings = "tnum"
                                    ),
                                    color = Palette.Sky400
                                )
                                if (radiusMeters == defaultRadius) {
                                    Text(
                                        text = "Default",
                                        style = CockpitTheme.typography.labelMedium,
                                        color = Palette.Emerald500
                                    )
                                }
                            }

                            Slider(
                                value = radiusMeters,
                                onValueChange = { radiusMeters = it },
                                valueRange = 50f..150f,
                                steps = 19,
                                colors = SliderDefaults.colors(
                                    thumbColor = Palette.Sky400,
                                    activeTrackColor = Palette.Sky600,
                                    inactiveTrackColor = Palette.Slate800
                                )
                            )

                            // Preset Pills: 50m, 75m, 100m, 125m, 150m
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(50f, 75f, 100f, 125f, 150f).forEach { preset ->
                                    val isSelected = radiusMeters == preset
                                    Box(
                                        modifier = Modifier
                                            .clip(CopilotShapes.R6)
                                            .background(
                                                if (isSelected) Palette.Sky600 else Palette.Slate800
                                            )
                                            .clickable { radiusMeters = preset }
                                            .heightIn(min = 48.dp)
                                            .padding(horizontal = 14.dp, vertical = 12.dp)
                                    ) {
                                        Text(
                                            text = "${preset.toInt()} m",
                                            style = CockpitTheme.typography.labelLarge.copy(
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                            ),
                                            color = if (isSelected) CockpitTheme.colors.textPrimary else Palette.Slate400
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Right: Large Interactive Map Preview
                    Column(
                        modifier = Modifier.weight(1.35f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Tap the map to move the place",
                            style = CockpitTheme.typography.labelSmall,
                            color = Palette.Slate400
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(290.dp)
                                .clip(CopilotShapes.R12)
                                .border(1.dp, Palette.Slate700, CopilotShapes.R12)
                        ) {
                            GeofenceMiniMapView(
                                centerLat = parsedLat,
                                centerLon = parsedLon,
                                radiusMeters = radiusMeters,
                                onLocationPicked = { lat, lon ->
                                    latText = String.format(Locale.US, "%.6f", lat)
                                    lonText = String.format(Locale.US, "%.6f", lon)
                                }
                            )
                        }
                    }
                }

                } // End Box middle

                // Footer Buttons (Always visible and clickable)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = CopilotShapes.R10,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.Slate400)
                    ) {
                        Text("Cancel", color = CockpitTheme.colors.textPrimary)
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Button(
                        onClick = {
                            val trimmedName = name.trim()
                            val finalLat = latText.toDoubleOrNull()
                            val finalLon = lonText.toDoubleOrNull()
                            if (trimmedName.isBlank()) {
                                Toast.makeText(dialogContext, "Please enter a geofence name", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            if (finalLat == null || finalLon == null) {
                                Toast.makeText(dialogContext, "Please enter valid coordinates", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            onSave(trimmedName, finalLat, finalLon, radiusMeters)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.Sky600),
                        shape = CopilotShapes.R10
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save place", fontWeight = FontWeight.Bold, color = contentColorOn(Palette.Sky600))
                    }
                }
            }
        }
    }
}

/**
 * Geofence editor map: the offline basemap (one-shot MapLibre snapshot, no second live MapView)
 * with the fence circle drawn at true ground scale on top. Tapping moves the centre to the tapped
 * coordinate (exact inverse projection). The circle follows edits immediately; the basemap
 * re-renders after a short debounce and is re-projected meanwhile, so it never jumps.
 */
@Composable
fun GeofenceMiniMapView(
    centerLat: Double,
    centerLon: Double,
    radiusMeters: Float,
    onLocationPicked: (Double, Double) -> Unit
) {
    var sizePx by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val camera = remember(centerLat, centerLon, radiusMeters, sizePx) {
        StaticMapCamera.forCircle(centerLat, centerLon, radiusMeters.toDouble(), sizePx.width, sizePx.height)
    }
    val snapshot = rememberStaticMapSnapshot(camera)
    val currentOnPicked by rememberUpdatedState(onLocationPicked)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CockpitTheme.colors.cardElevated)
            .onSizeChanged { sizePx = it }
            .pointerInput(camera) {
                val cam = camera ?: return@pointerInput
                detectTapGestures { offset ->
                    val (lat, lon) = cam.unproject(offset.x, offset.y)
                    if (StaticMapCamera.isValidCoordinate(lat, lon)) currentOnPicked(lat, lon)
                }
            }
    ) {
        if (camera == null) {
            Text(
                text = "Enter valid coordinates to see the place",
                fontSize = 16.sp,
                color = CockpitTheme.colors.textSecondary,
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize().graphicsLayer { clip = true }) {
                snapshot?.let { drawSnapshot(it, camera) }
                val xy = camera.project(centerLat, centerLon)
                val center = Offset(xy[0], xy[1])
                val radiusPx = (radiusMeters / camera.metersPerPixel(centerLat)).toFloat()

                drawCircle(color = StaticPalette.Cyan.copy(alpha = 0.18f), radius = radiusPx, center = center)
                drawCircle(color = StaticPalette.Cyan, radius = radiusPx, center = center, style = Stroke(width = 2.5f))

                drawLine(
                    color = StaticPalette.Cyan.copy(alpha = 0.6f),
                    start = Offset(center.x - 20f, center.y),
                    end = Offset(center.x + 20f, center.y),
                    strokeWidth = 2f
                )
                drawLine(
                    color = StaticPalette.Cyan.copy(alpha = 0.6f),
                    start = Offset(center.x, center.y - 20f),
                    end = Offset(center.x, center.y + 20f),
                    strokeWidth = 2f
                )
                drawCircle(color = StaticPalette.textPrimary, radius = 6f, center = center)
                drawCircle(color = StaticPalette.Cyan, radius = 4f, center = center)
            }
        }

        Text(
            text = "${radiusMeters.toInt()} m around the point",
            fontSize = 16.sp,
            color = CockpitTheme.colors.textPrimary,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
                .background(CockpitTheme.colors.scrim, CopilotShapes.R6)
                .padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}
