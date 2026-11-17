package com.antigravity.copilot.feature.settings

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
import com.antigravity.copilot.core.map.OfflineMapManager
import com.antigravity.copilot.core.trip.TripGeofenceManager
import com.antigravity.copilot.core.util.NumberFormatUtils
import kotlinx.coroutines.launch
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import java.util.Locale

@Composable
fun GeofenceSettingsContent() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val geofenceManager = remember { TripGeofenceManager.getInstance(context) }
    val geofences by geofenceManager.geofences.collectAsStateWithLifecycle()

    val (currentLat, currentLon) = remember { OfflineMapManager.getLastKnownPosition(context) }

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
                            text = "AUTOMATIC TRIP GEOFENCES",
                            style = CockpitTheme.typography.titleMedium,
                            color = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Departing an enabled geofence starts a journey (including departure distance). Entering an enabled geofence ends the trip. Journeys persist across days and power cycles.",
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
                    Text("Add Geofence", style = CockpitTheme.typography.labelLarge)
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
                        text = "No Geofences Configured",
                        style = CockpitTheme.typography.titleSmall,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Add your Home, Office, or Farm to enable fully automated departure and arrival tracking.",
                        color = Palette.Steel400,
                        style = CockpitTheme.typography.bodySmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            geofences.forEach { gf ->
                val distanceMeters = geofenceManager.calculateDistance(gf, currentLat, currentLon)
                val isInside = distanceMeters <= gf.radiusMeters

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (gf.isEnabled) Palette.Ink925 else Color(0xFF080C14)
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
                                        color = if (gf.isEnabled) Color.White else Palette.Slate500
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
                                            text = "${gf.radiusMeters.toInt()}m radius",
                                            style = CockpitTheme.typography.labelSmall.copy(
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
                                                text = "INSIDE GEOFENCE",
                                                style = CockpitTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.Black
                                                ),
                                                color = Color(0xFF34D399)
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
                                                "${NumberFormatUtils.formatDistance(distanceMeters / 1000.0, 2)} km away"
                                            } else {
                                                "${distanceMeters.toInt()}m away"
                                            }
                                            Text(
                                                text = distText,
                                                style = CockpitTheme.typography.labelSmall.copy(
                                                    fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily,
                                                    fontFeatureSettings = "tnum"
                                                ),
                                                color = Palette.Slate400
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                Text(
                                    text = String.format(Locale.US, "GPS: %.5f, %.5f", gf.latitude, gf.longitude),
                                    style = CockpitTheme.typography.bodySmall.copy(
                                        fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily,
                                        fontFeatureSettings = "tnum"
                                    ),
                                    color = Palette.Slate500
                                )
                            }
                        }

                        // Right: Controls (Enable Switch, Edit, Delete)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = gf.isEnabled,
                                onCheckedChange = { checked ->
                                    coroutineScope.launch {
                                        geofenceManager.toggleGeofence(gf.id, checked)
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Palette.Sky400,
                                    checkedTrackColor = Palette.Sky700,
                                    uncheckedThumbColor = Palette.Slate500,
                                    uncheckedTrackColor = Palette.Slate800
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
                                    .cockpitSurface(Color(0xFF0F2642), Palette.Sky400.copy(alpha = 0.55f), CircleShape, borderWidth = 1.2.dp)
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
                                    .cockpitSurface(Color(0xFF3B151E), Palette.Rose500.copy(alpha = 0.55f), CircleShape, borderWidth = 1.2.dp)
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
        AlertDialog(
            onDismissRequest = { geofenceToDelete = null },
            title = {
                Text(
                    text = "Delete Geofence?",
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to delete '${gf.name}'? Automatic trip start/end will no longer trigger at this location.",
                    color = Palette.Slate400
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            geofenceManager.deleteGeofence(gf.id)
                            geofenceToDelete = null
                            Toast.makeText(context, "Geofence deleted", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                ) {
                    Text("Delete", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { geofenceToDelete = null }) {
                    Text("Cancel", color = Palette.Slate400)
                }
            },
            containerColor = Palette.Slate900,
            shape = CopilotShapes.R14
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
                coroutineScope.launch {
                    val toEdit = selectedGeofenceForEdit
                    val res = if (toEdit != null) {
                        geofenceManager.updateGeofence(
                            toEdit.copy(
                                name = name,
                                latitude = lat,
                                longitude = lon,
                                radiusMeters = radius
                            )
                        )
                    } else {
                        geofenceManager.addGeofence(
                            name = name,
                            latitude = lat,
                            longitude = lon,
                            radiusMeters = radius
                        ).map { Unit }
                    }

                    if (res.isSuccess) {
                        Toast.makeText(context, "Geofence saved successfully!", Toast.LENGTH_SHORT).show()
                        isAddEditDialogOpen = false
                    } else {
                        Toast.makeText(context, res.exceptionOrNull()?.message ?: "Failed to save geofence", Toast.LENGTH_LONG).show()
                    }
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
    var latText by remember { mutableStateOf(existingGeofence?.latitude?.toString() ?: currentDeviceLat.toString()) }
    var lonText by remember { mutableStateOf(existingGeofence?.longitude?.toString() ?: currentDeviceLon.toString()) }
    var radiusMeters by remember { mutableFloatStateOf(existingGeofence?.radiusMeters?.coerceIn(50f, 150f) ?: 100f) }

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
                            text = if (existingGeofence != null) "EDIT TRIP GEOFENCE" else "ADD TRIP GEOFENCE",
                            style = CockpitTheme.typography.titleMedium,
                            color = Color.White
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
                            label = { Text("Geofence Name", color = Palette.Slate400) },
                            placeholder = { Text("e.g. Home, Office, Farmhouse", color = Palette.Slate500) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Palette.Sky400,
                                unfocusedBorderColor = Palette.Slate700,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
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
                                tint = Palette.Sky400,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Use Current GPS Location",
                                style = CockpitTheme.typography.labelSmall,
                                color = Color.White
                            )
                        }

                        // Coordinates Row
                        Row(
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
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
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
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
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
                                    text = "Radius: ${radiusMeters.toInt()} meters",
                                    style = CockpitTheme.typography.labelSmall.copy(
                                        fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily,
                                        fontFeatureSettings = "tnum"
                                    ),
                                    color = Palette.Sky400
                                )
                                if (radiusMeters == 100f) {
                                    Text(
                                        text = "(Recommended Default)",
                                        style = CockpitTheme.typography.labelSmall,
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
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
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
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = if (preset == 100f) "100m (Def)" else "${preset.toInt()}m",
                                            style = CockpitTheme.typography.labelSmall.copy(
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                            ),
                                            color = if (isSelected) Color.White else Palette.Slate400
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
                            text = "Geofence Map Preview (Tap to reposition center)",
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
                        Text("Cancel")
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
                        Text("Save Geofence", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun GeofenceMiniMapView(
    centerLat: Double,
    centerLon: Double,
    radiusMeters: Float,
    onLocationPicked: (Double, Double) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B1017))
            .pointerInput(centerLat, centerLon, radiusMeters) {
                detectTapGestures { offset ->
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val dx = (offset.x - w / 2f) / (w / 2f)
                    val dy = (h / 2f - offset.y) / (h / 2f)
                    // Convert screen offset to lat/lon delta based on radius scale
                    val maxMeterScale = (radiusMeters * 2.2f).coerceAtLeast(100f)
                    val latDelta = (dy * maxMeterScale) / 111320.0
                    val lonDelta = (dx * maxMeterScale) / (111320.0 * Math.cos(Math.toRadians(centerLat)))
                    onLocationPicked(centerLat + latDelta, centerLon + lonDelta)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val maxRadiusPx = minOf(size.width, size.height) / 2f - 16f
            val geofenceRadiusPx = maxRadiusPx * 0.70f

            // Radar background rings
            drawCircle(
                color = Color(0xFF16202E),
                radius = maxRadiusPx,
                center = center,
                style = Stroke(width = 1.5f)
            )
            drawCircle(
                color = Color(0xFF1C2B3E),
                radius = maxRadiusPx * 0.45f,
                center = center,
                style = Stroke(width = 1.5f)
            )

            // Geofence Circle Boundary Fill & Outline
            drawCircle(
                color = Palette.Cyan.copy(alpha = 0.15f),
                radius = geofenceRadiusPx,
                center = center
            )
            drawCircle(
                color = Palette.Cyan,
                radius = geofenceRadiusPx,
                center = center,
                style = Stroke(width = 2.5f)
            )

            // Center Target Crosshairs
            drawLine(
                color = Palette.Cyan.copy(alpha = 0.4f),
                start = Offset(center.x - 20f, center.y),
                end = Offset(center.x + 20f, center.y),
                strokeWidth = 2f
            )
            drawLine(
                color = Palette.Cyan.copy(alpha = 0.4f),
                start = Offset(center.x, center.y - 20f),
                end = Offset(center.x, center.y + 20f),
                strokeWidth = 2f
            )

            // Center Pin Point
            drawCircle(
                color = Color.White,
                radius = 6f,
                center = center
            )
            drawCircle(
                color = Palette.Cyan,
                radius = 4f,
                center = center
            )
        }

        Text(
            text = "Tap to move center • Radius: ${radiusMeters.toInt()}m",
            fontSize = 11.sp,
            color = Color.White.copy(alpha = 0.6f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
        )
    }
}
