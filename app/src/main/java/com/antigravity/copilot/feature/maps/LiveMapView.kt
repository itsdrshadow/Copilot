package com.antigravity.copilot.feature.maps

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.antigravity.copilot.core.designsystem.colors.*
import com.antigravity.copilot.core.location.SpeedLimitInfo
import com.antigravity.copilot.core.map.CarMapController
import com.antigravity.copilot.core.map.OfflineMapManager
import com.antigravity.copilot.core.model.TripPoint
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.maps.MapView
import com.mapbox.mapboxsdk.maps.MapboxMapOptions

const val MAP_MIN_ZOOM_LEVEL = 14.0
const val MAP_MAX_ZOOM_LEVEL = 19.5
const val MAP_DEFAULT_DRIVING_ZOOM = 17.5

/**
 * LiveMapPanel:
 * MapLibre Android SDK v10.3.2 integrated with textureMode=true for rock-solid 60fps rendering
 * on 32-bit armeabi-v7a Android 11 car head units.
 */
@Composable
fun LiveMapPanel(
    latestPoint: TripPoint?,
    trail: List<TripPoint>,
    modifier: Modifier = Modifier,
    speedLimitInfo: SpeedLimitInfo = SpeedLimitInfo(),
    isFullScreen: Boolean = false,
    onToggleFullScreen: () -> Unit = {}
) {
    if (isFullScreen) {
        BackHandler {
            onToggleFullScreen()
        }
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    var showOfflineManagerDialog by remember { mutableStateOf(false) }
    var mapViewInstance by remember { mutableStateOf<MapView?>(null) }
    val mapController = remember { CarMapController(context.applicationContext, coroutineScope) }

    val hasMapInstalled by mapController.hasMapInstalled.collectAsStateWithLifecycle()
    val activeRegionTitle by mapController.activeRegionTitle.collectAsStateWithLifecycle()
    val is3DActive by mapController.is3DTiltActive.collectAsStateWithLifecycle()
    val isHeadingUp by mapController.isHeadingUp.collectAsStateWithLifecycle()
    val activeInstruction by mapController.activeInstruction.collectAsStateWithLifecycle()
    val remainingDistance by mapController.routeDistanceMeters.collectAsStateWithLifecycle()

    // Ensure MapLibre native engine is initialized with applicationContext
    remember {
        try {
            Mapbox.getInstance(context.applicationContext)
        } catch (_: Exception) {}
        OfflineMapManager.init(context.applicationContext)
    }

    // Pass vehicle position updates to controller
    LaunchedEffect(latestPoint) {
        latestPoint?.let { pt ->
            mapController.updateVehiclePosition(
                lat = pt.latitude,
                lon = pt.longitude,
                bearing = pt.bearing,
                speedKmh = pt.speedKmh
            )
        }
    }

    // Lifecycle observer to forward Android lifecycle events to MapView
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapViewInstance?.onStart()
                Lifecycle.Event.ON_RESUME -> {
                    mapViewInstance?.onResume()
                    mapController.loadLocalOfflineStyle()
                }
                Lifecycle.Event.ON_PAUSE -> mapViewInstance?.onPause()
                Lifecycle.Event.ON_STOP -> mapViewInstance?.onStop()
                Lifecycle.Event.ON_DESTROY -> {
                    mapController.detachMap()
                    mapViewInstance?.onDestroy()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapController.detachMap()
            mapViewInstance?.onDestroy()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
    ) {
        // MapLibre MapView with textureMode(true) to avoid black-screen head unit GPU bugs
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val options = MapboxMapOptions.createFromAttributes(ctx)
                    .textureMode(true) // Crucial for older Mali / Adreno GPUs
                    .compassEnabled(false)
                    .logoEnabled(false)
                    .attributionEnabled(false)
                    .minZoomPreference(MAP_MIN_ZOOM_LEVEL)
                    .maxZoomPreference(MAP_MAX_ZOOM_LEVEL)

                MapView(ctx, options).apply {
                    onCreate(Bundle())
                    mapViewInstance = this
                    getMapAsync { map ->
                        map.uiSettings.isRotateGesturesEnabled = true
                        map.uiSettings.isTiltGesturesEnabled = true
                        map.uiSettings.isZoomGesturesEnabled = true
                        mapController.attachMap(map)
                    }
                }
            }
        )

        // Missing Offline Map Prompt Overlay (if no .mbtiles file found)
        if (!hasMapInstalled) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(15f)
                    .background(Color.Black.copy(alpha = 0.75f)),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    color = Color(0xFF131C28),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Cockpit_Cyan.copy(alpha = 0.6f)),
                    modifier = Modifier.padding(24.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = null,
                            tint = Cockpit_Cyan,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (activeRegionTitle != null) "NO MAP FOR ${activeRegionTitle?.uppercase()}" else "NO OFFLINE MAP INSTALLED",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (activeRegionTitle != null) "Download $activeRegionTitle pack (~1.2 GB) or copy via USB" else "Download regional map pack or copy .mbtiles via USB",
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(18.dp))
                        Button(
                            onClick = { showOfflineManagerDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Cockpit_Cyan),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.height(52.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Map, contentDescription = null, tint = Color.Black)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "OPEN OFFLINE MAPS MANAGER",
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                        }
                    }
                }
            }
        }

        // Top Navigation Instruction Banner (when route is active)
        AnimatedVisibility(
            visible = activeInstruction != null,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(16.dp),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Surface(
                color = Color(0xFF101926).copy(alpha = 0.95f),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Cockpit_Cyan),
                modifier = Modifier.fillMaxWidth(0.85f)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Navigation,
                        contentDescription = null,
                        tint = Cockpit_Cyan,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = activeInstruction ?: "",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        if (remainingDistance != null) {
                            Text(
                                text = "Remaining: ${String.format("%.1f km", (remainingDistance ?: 0.0) / 1000.0)}",
                                fontSize = 12.sp,
                                color = Cockpit_Cyan
                            )
                        }
                    }
                    IconButton(onClick = { mapController.clearRoute() }) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Cancel Route", tint = Color.White)
                    }
                }
            }
        }

        // Left Speed Limit Badge
        if (speedLimitInfo.speedLimitKmh > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
                    .size(54.dp)
                    .background(Color.White, CircleShape)
                    .border(4.dp, Color.Red, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${speedLimitInfo.speedLimitKmh}",
                    fontWeight = FontWeight.Black,
                    fontSize = 20.sp,
                    color = Color.Black
                )
            }
        }

        // Right Quick Action Controls (Min 56dp Touch Targets for Driving)
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .zIndex(20f)
                .padding(end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Heading-Up / North-Up Toggle
            MapFloatingButton(
                icon = if (isHeadingUp) Icons.Default.Explore else Icons.Default.Navigation,
                contentDescription = "Toggle Heading Up",
                isActive = isHeadingUp,
                onClick = { mapController.toggleHeadingMode() }
            )

            // 2D / 3D Driving Tilt Toggle (45° Tilt)
            MapFloatingButton(
                icon = if (is3DActive) Icons.Default.ViewInAr else Icons.Default.Layers,
                contentDescription = "Toggle 3D Tilt",
                isActive = is3DActive,
                onClick = { mapController.toggle3DTilt() }
            )

            // Recenter Vehicle
            MapFloatingButton(
                icon = Icons.Default.MyLocation,
                contentDescription = "Recenter",
                isActive = false,
                onClick = { mapController.recenter() }
            )

            // Open Offline Maps Manager Screen
            MapFloatingButton(
                icon = Icons.Default.CloudDownload,
                contentDescription = "Offline Maps",
                isActive = false,
                onClick = { showOfflineManagerDialog = true }
            )

            // Fullscreen Toggle
            MapFloatingButton(
                icon = if (isFullScreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                contentDescription = "Toggle Fullscreen",
                isActive = isFullScreen,
                onClick = onToggleFullScreen
            )
        }
    }

    if (showOfflineManagerDialog) {
        OfflineMapsManagerDialog(
            onDismissRequest = {
                showOfflineManagerDialog = false
                mapController.loadLocalOfflineStyle()
            }
        )
    }
}

@Composable
private fun MapFloatingButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(if (isActive) Cockpit_Cyan else Color(0xFF141C28).copy(alpha = 0.9f))
            .border(1.dp, if (isActive) Cockpit_Cyan else Color(0xFF263346), CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (isActive) Color.Black else Color.White,
            modifier = Modifier.size(26.dp)
        )
    }
}
