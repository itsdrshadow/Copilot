package com.antigravity.copilot.feature.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.antigravity.copilot.core.database.TripGeofenceEntity
import com.antigravity.copilot.core.designsystem.AutomotiveDesignTokens
import com.antigravity.copilot.core.map.OfflineMapManager
import com.antigravity.copilot.core.trip.TripGeofenceManager
import com.antigravity.copilot.core.util.NumberFormatUtils
import kotlinx.coroutines.launch
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.util.Locale

@Composable
fun GeofenceSettingsContent() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val geofenceManager = remember { TripGeofenceManager.getInstance(context) }
    val geofences by geofenceManager.geofences.collectAsState()

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
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B))
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
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "AUTOMATIC TRIP GEOFENCES",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            ),
                            color = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Departing an enabled geofence starts a journey (including departure distance). Entering an enabled geofence ends the trip. Journeys persist across days and power cycles.",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                        color = Color(0xFF94A3B8)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Button(
                    onClick = {
                        selectedGeofenceForEdit = null
                        isAddEditDialogOpen = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.height(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AddLocationAlt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Add Geofence", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }

        // 2. Geofences List
        if (geofences.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0C1322)),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E2838))
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
                        tint = Color(0xFF475569),
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No Geofences Configured",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Add your Home, Office, or Farm to enable fully automated departure and arrival tracking.",
                        color = Color(0xFF87929A),
                        fontSize = 12.sp,
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
                        containerColor = if (gf.isEnabled) Color(0xFF0C1322) else Color(0xFF080C14)
                    ),
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isInside && gf.isEnabled) Color(0xFF10B981) else Color(0xFF1E2838)
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
                                        if (gf.isEnabled) Color(0xFF0284C7).copy(alpha = 0.2f)
                                        else Color(0xFF334155).copy(alpha = 0.2f)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (gf.name.contains("Home", ignoreCase = true)) Icons.Default.Home
                                    else if (gf.name.contains("Office", ignoreCase = true) || gf.name.contains("Work", ignoreCase = true)) Icons.Default.Business
                                    else Icons.Default.Place,
                                    contentDescription = null,
                                    tint = if (gf.isEnabled) Color(0xFF38BDF8) else Color(0xFF64748B),
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = gf.name,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 18.sp
                                        ),
                                        color = if (gf.isEnabled) Color.White else Color(0xFF64748B)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    // Radius pill
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF0369A1).copy(alpha = 0.25f))
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = "${gf.radiusMeters.toInt()}m radius",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = 13.5.sp,
                                                fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily,
                                                fontFeatureSettings = "tnum",
                                                fontWeight = FontWeight.Bold
                                            ),
                                            color = Color(0xFF38BDF8)
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(6.dp))

                                    // Distance / Inside pill
                                    if (isInside && gf.isEnabled) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Color(0xFF10B981).copy(alpha = 0.25f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "INSIDE GEOFENCE",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Black,
                                                color = Color(0xFF34D399)
                                            )
                                        }
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Color(0xFF334155).copy(alpha = 0.35f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            val distText = if (distanceMeters >= 1000f) {
                                                "${NumberFormatUtils.formatDistance(distanceMeters / 1000.0, 2)} km away"
                                            } else {
                                                "${distanceMeters.toInt()}m away"
                                            }
                                            Text(
                                                text = distText,
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontSize = 11.5.sp,
                                                    fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily,
                                                    fontFeatureSettings = "tnum"
                                                ),
                                                color = Color(0xFF94A3B8)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(3.dp))

                                Text(
                                    text = String.format(Locale.US, "GPS: %.5f, %.5f", gf.latitude, gf.longitude),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 11.5.sp,
                                        fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily,
                                        fontFeatureSettings = "tnum"
                                    ),
                                    color = Color(0xFF64748B)
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
                                    checkedThumbColor = Color(0xFF38BDF8),
                                    checkedTrackColor = Color(0xFF0369A1),
                                    uncheckedThumbColor = Color(0xFF64748B),
                                    uncheckedTrackColor = Color(0xFF1E293B)
                                )
                            )

                            Spacer(modifier = Modifier.width(8.dp))

                            IconButton(
                                onClick = {
                                    selectedGeofenceForEdit = gf
                                    isAddEditDialogOpen = true
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Edit Geofence",
                                    tint = Color(0xFF38BDF8),
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            IconButton(
                                onClick = { geofenceToDelete = gf },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "Delete Geofence",
                                    tint = Color(0xFFF43F5E),
                                    modifier = Modifier.size(18.dp)
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
                    color = Color(0xFF94A3B8)
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
                    Text("Cancel", color = Color(0xFF94A3B8))
                }
            },
            containerColor = Color(0xFF0F172A),
            shape = RoundedCornerShape(14.dp)
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
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF0F172A))
                .border(1.5.dp, Color(0xFF1E293B), RoundedCornerShape(18.dp))
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
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (existingGeofence != null) "EDIT TRIP GEOFENCE" else "ADD TRIP GEOFENCE",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            ),
                            color = Color.White
                        )
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color(0xFF87929A)
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
                            label = { Text("Geofence Name", color = Color(0xFF94A3B8)) },
                            placeholder = { Text("e.g. Home, Office, Farmhouse", color = Color(0xFF64748B)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF38BDF8),
                                unfocusedBorderColor = Color(0xFF334155),
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
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.MyLocation,
                                contentDescription = null,
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Use Current GPS Location",
                                fontSize = 11.sp,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
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
                                label = { Text("Latitude", color = Color(0xFF94A3B8)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFF38BDF8),
                                    unfocusedBorderColor = Color(0xFF334155),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )
                            OutlinedTextField(
                                value = lonText,
                                onValueChange = { lonText = it },
                                label = { Text("Longitude", color = Color(0xFF94A3B8)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFF38BDF8),
                                    unfocusedBorderColor = Color(0xFF334155),
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
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = Color(0xFF38BDF8)
                                )
                                if (radiusMeters == 100f) {
                                    Text(
                                        text = "(Recommended Default)",
                                        fontSize = 10.sp,
                                        color = Color(0xFF10B981),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Slider(
                                value = radiusMeters,
                                onValueChange = { radiusMeters = it },
                                valueRange = 50f..150f,
                                steps = 19,
                                colors = SliderDefaults.colors(
                                    thumbColor = Color(0xFF38BDF8),
                                    activeTrackColor = Color(0xFF0284C7),
                                    inactiveTrackColor = Color(0xFF1E293B)
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
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(
                                                if (isSelected) Color(0xFF0284C7) else Color(0xFF1E293B)
                                            )
                                            .clickable { radiusMeters = preset }
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = if (preset == 100f) "100m (Def)" else "${preset.toInt()}m",
                                            fontSize = 10.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) Color.White else Color(0xFF94A3B8)
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
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8),
                            fontWeight = FontWeight.Bold
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(290.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, Color(0xFF334155), RoundedCornerShape(12.dp))
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
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF94A3B8))
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
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        shape = RoundedCornerShape(10.dp)
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
    var mapViewInstance by remember { mutableStateOf<MapView?>(null) }
    var centerMarker by remember { mutableStateOf<Marker?>(null) }
    var radiusPolygon by remember { mutableStateOf<Polygon?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            mapViewInstance?.onPause()
            mapViewInstance?.onDetach()
            mapViewInstance = null
        }
    }

    LaunchedEffect(centerLat, centerLon, radiusMeters, mapViewInstance) {
        val mv = mapViewInstance ?: return@LaunchedEffect
        val centerGeo = GeoPoint(centerLat, centerLon)

        val zoomLevel = if (radiusMeters <= 75f) 17.8 else if (radiusMeters <= 120f) 17.2 else 16.8
        mv.controller.setCenter(centerGeo)
        mv.controller.setZoom(zoomLevel)

        // Update Center Marker
        if (centerMarker == null) {
            centerMarker = Marker(mv).apply {
                position = centerGeo
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = "Geofence Center"
                mv.overlays.add(this)
            }
        } else {
            centerMarker?.position = centerGeo
        }

        // Update Radius Polygon Circle
        if (radiusPolygon == null) {
            radiusPolygon = Polygon(mv).apply {
                fillPaint.color = android.graphics.Color.parseColor("#330284C7") // Semi-transparent sky
                outlinePaint.color = android.graphics.Color.parseColor("#FF38BDF8")
                outlinePaint.strokeWidth = 3f
                points = Polygon.pointsAsCircle(centerGeo, radiusMeters.toDouble())
                mv.overlays.add(0, this)
            }
        } else {
            radiusPolygon?.points = Polygon.pointsAsCircle(centerGeo, radiusMeters.toDouble())
        }

        mv.invalidate()
    }

    AndroidView(
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(OfflineMapManager.NORMAL_ROADMAP_SOURCE)
                setMultiTouchControls(true)
                minZoomLevel = 0.0
                maxZoomLevel = 20.0
                val initialZoom = 17.5
                controller.setZoom(initialZoom)
                controller.setCenter(GeoPoint(centerLat, centerLon))
                overlayManager.tilesOverlay.setColorFilter(null)

                // Tap overlay to pick coordinate
                val tapOverlay = MapEventsOverlay(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                        p?.let {
                            onLocationPicked(it.latitude, it.longitude)
                        }
                        return true
                    }
                    override fun longPressHelper(p: GeoPoint?): Boolean = false
                })
                overlays.add(tapOverlay)

                mapViewInstance = this
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}
