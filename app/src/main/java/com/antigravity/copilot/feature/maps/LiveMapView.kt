package com.antigravity.copilot.feature.maps

import com.antigravity.copilot.core.designsystem.tokens.CopilotShapes
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import com.antigravity.copilot.core.map.MapNetworkGuard
import com.antigravity.copilot.core.map.OfflineMapManager
import com.antigravity.copilot.core.model.TripPoint
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.maps.MapView
import com.mapbox.mapboxsdk.maps.MapboxMapOptions

const val MAP_MIN_ZOOM_LEVEL = 5.0
const val MAP_MAX_ZOOM_LEVEL = 19.5
const val MAP_DEFAULT_DRIVING_ZOOM = 17.5

/**
 * Applies the *effective* lifecycle to a single long-lived [MapView]:
 * effective = host (Activity) state, capped at CREATED while the panel is covered by a
 * full-screen overlay (Trips / Fuel / Apps / Settings). Covering pauses + stops the GL render
 * thread and tile fetching but never destroys the view, so the style, sources and camera survive
 * and uncovering is instant (no reload, no camera reset). [destroy] runs only when the panel
 * really leaves composition (activity teardown).
 */
private class MapViewLifecycleHolder {
    var mapView: MapView? = null
    var hostState: Lifecycle.State = Lifecycle.State.INITIALIZED
    var visible: Boolean = true
    private var started = false
    private var resumed = false
    private var destroyed = false

    fun sync() {
        val mv = mapView ?: return
        if (destroyed) return
        val wantStarted = visible && hostState.isAtLeast(Lifecycle.State.STARTED)
        val wantResumed = visible && hostState.isAtLeast(Lifecycle.State.RESUMED)
        if (wantStarted && !started) { mv.onStart(); started = true }
        if (wantResumed && !resumed) { mv.onResume(); resumed = true }
        if (!wantResumed && resumed) { mv.onPause(); resumed = false }
        if (!wantStarted && started) { mv.onStop(); started = false }
    }

    fun destroy() {
        val mv = mapView ?: return
        if (destroyed) return
        if (resumed) mv.onPause()
        if (started) mv.onStop()
        resumed = false
        started = false
        mv.onDestroy()
        destroyed = true
        mapView = null
    }
}

/**
 * LiveMapPanel:
 * MapLibre Android SDK v10.3.2 integrated with textureMode=true for rock-solid rendering
 * on 32-bit armeabi-v7a Android 11 car head units.
 *
 * The panel is meant to stay composed for the whole session (see MainAppScreen): pass
 * [isCovered] = true while an opaque full-screen overlay hides it instead of removing it.
 */
@Composable
fun LiveMapPanel(
    latestPoint: TripPoint?,
    trail: List<TripPoint>,
    modifier: Modifier = Modifier,
    speedLimitInfo: SpeedLimitInfo = SpeedLimitInfo(),
    isFullScreen: Boolean = false,
    isCovered: Boolean = false,
    onToggleFullScreen: () -> Unit = {}
) {
    if (isFullScreen && !isCovered) {
        BackHandler {
            onToggleFullScreen()
        }
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    var showOfflineManagerDialog by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    val lifecycleHolder = remember { MapViewLifecycleHolder() }
    val mapController = remember { CarMapController(context.applicationContext, coroutineScope) }

    val hasMapInstalled by mapController.hasMapInstalled.collectAsStateWithLifecycle()
    val activeRegionTitle by mapController.activeRegionTitle.collectAsStateWithLifecycle()
    val is3DActive by mapController.is3DTiltActive.collectAsStateWithLifecycle()
    val isHeadingUp by mapController.isHeadingUp.collectAsStateWithLifecycle()
    val isFollowing by mapController.isFollowing.collectAsStateWithLifecycle()
    val roadMileageVisible by mapController.roadMileageVisible.collectAsStateWithLifecycle()
    val selectedRoadCell by mapController.selectedRoadCell.collectAsStateWithLifecycle()

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

    // Live trail with overlap colouring (throttled + computed off the main thread)
    LaunchedEffect(trail) {
        mapController.updateTrail(trail)
    }

    // Pause rendering while an opaque overlay covers the map; never destroy on tab switches.
    SideEffect {
        if (lifecycleHolder.visible == isCovered) {
            lifecycleHolder.visible = !isCovered
            lifecycleHolder.sync()
        }
    }

    // Forward host lifecycle (start/resume/pause/stop) to the MapView; destroy only on teardown.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { source, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                mapController.detachMap()
                lifecycleHolder.destroy()
            } else {
                lifecycleHolder.hostState = source.lifecycle.currentState
                lifecycleHolder.sync()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        val memoryCallbacks = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                MapNetworkGuard.onTrimMemory(level)
                if (level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                    lifecycleHolder.mapView?.onLowMemory()
                }
            }
            override fun onConfigurationChanged(newConfig: Configuration) {}
            @Deprecated("Deprecated in Java")
            override fun onLowMemory() {
                MapNetworkGuard.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
                lifecycleHolder.mapView?.onLowMemory()
            }
        }
        context.applicationContext.registerComponentCallbacks(memoryCallbacks)
        onDispose {
            context.applicationContext.unregisterComponentCallbacks(memoryCallbacks)
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapController.detachMap()
            lifecycleHolder.destroy()
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
                    .pixelRatio(1.0f) // Cap map density at 1x for GLES2 head units
                    .compassEnabled(false)
                    .logoEnabled(false)
                    .attributionEnabled(false)
                    .minZoomPreference(MAP_MIN_ZOOM_LEVEL)
                    .maxZoomPreference(MAP_MAX_ZOOM_LEVEL)

                MapView(ctx, options).apply {
                    onCreate(null)
                    lifecycleHolder.mapView = this
                    // The lifecycle observer may already have replayed ON_START/ON_RESUME before this
                    // view existed (fresh launch): bring the MapView up to the current state, or it
                    // never renders until the app is backgrounded once.
                    lifecycleHolder.hostState = lifecycleOwner.lifecycle.currentState
                    lifecycleHolder.sync()
                    getMapAsync { map ->
                        map.uiSettings.isRotateGesturesEnabled = true
                        map.uiSettings.isTiltGesturesEnabled = true
                        map.uiSettings.isZoomGesturesEnabled = true
                        map.uiSettings.isDoubleTapGesturesEnabled = true
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
                    shape = CopilotShapes.R16,
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
                            text = "Import india.pmtiles (Import map file) or set a download URL — see MAP_SETUP.md",
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(18.dp))
                        Button(
                            onClick = { showOfflineManagerDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Cockpit_Cyan),
                            shape = CopilotShapes.R12,
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

        // Right-side driving controls: three large targets (Compass, Recenter, Menu); secondary
        // actions live in an expandable menu. Zoom is pinch / double-tap only.
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .zIndex(20f)
                .padding(end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AnimatedVisibility(visible = menuExpanded, enter = fadeIn(), exit = fadeOut()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MapMenuItem(
                        icon = if (is3DActive) Icons.Default.ViewInAr else Icons.Default.Layers,
                        label = if (is3DActive) "Auto 3D (moving)" else "2D only",
                        isActive = is3DActive,
                        onClick = { mapController.toggle3DTilt(); menuExpanded = false }
                    )
                    MapMenuItem(
                        icon = Icons.Default.LocalGasStation,
                        label = "Fuel overlay",
                        isActive = roadMileageVisible,
                        onClick = { mapController.toggleRoadMileage(); menuExpanded = false }
                    )
                    MapMenuItem(
                        icon = Icons.Default.CloudDownload,
                        label = "Offline maps",
                        isActive = false,
                        onClick = { showOfflineManagerDialog = true; menuExpanded = false }
                    )
                    MapMenuItem(
                        icon = if (isFullScreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                        label = if (isFullScreen) "Exit full screen" else "Full screen",
                        isActive = isFullScreen,
                        onClick = { onToggleFullScreen(); menuExpanded = false }
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Heading-Up / North-Up
                MapFloatingButton(
                    icon = if (isHeadingUp) Icons.Default.Explore else Icons.Default.Navigation,
                    contentDescription = if (isHeadingUp) "Heading up (tap for north up)" else "North up (tap for heading up)",
                    isActive = isHeadingUp,
                    onClick = { mapController.toggleHeadingMode() }
                )
                // Highlighted while follow / auto-zoom is suspended by a gesture: tap to resume.
                MapFloatingButton(
                    icon = if (isFollowing) Icons.Default.MyLocation else Icons.Default.LocationSearching,
                    contentDescription = if (isFollowing) "Recenter" else "Resume follow",
                    isActive = !isFollowing,
                    onClick = { mapController.recenter() }
                )
                MapFloatingButton(
                    icon = if (menuExpanded) Icons.Default.Close else Icons.Default.Menu,
                    contentDescription = if (menuExpanded) "Close map menu" else "Open map menu",
                    isActive = menuExpanded,
                    onClick = { menuExpanded = !menuExpanded }
                )
            }
        }
    }

    selectedRoadCell?.let { cell ->
        AlertDialog(
            onDismissRequest = { mapController.clearSelectedRoadCell() },
            title = { Text("Road mileage") },
            text = {
                Text(
                    "Avg mileage: ${"%.1f".format(cell.avgKmpl)} km/L\n" +
                        "Passes: ${cell.passes}\n" +
                        "Avg speed: ${"%.0f".format(cell.avgSpeedKmh)} km/h"
                )
            },
            confirmButton = { TextButton(onClick = { mapController.clearSelectedRoadCell() }) { Text("OK") } }
        )
    }

    if (showOfflineManagerDialog) {
        OfflineMapsManagerDialog(
            onDismissRequest = {
                showOfflineManagerDialog = false
                mapController.reloadStyleIfMapChanged()
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
            .size(68.dp)
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
            modifier = Modifier.size(32.dp)
        )
    }
}

@Composable
private fun MapMenuItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .height(56.dp)
            .clip(CircleShape)
            .background(if (isActive) Cockpit_Cyan else Color(0xFF141C28).copy(alpha = 0.95f))
            .border(1.dp, if (isActive) Cockpit_Cyan else Color(0xFF263346), CircleShape)
            .clickable { onClick() }
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val tint = if (isActive) Color.Black else Color.White
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Text(text = label, color = tint, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}
