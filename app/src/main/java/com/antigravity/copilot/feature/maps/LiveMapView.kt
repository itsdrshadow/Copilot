package com.antigravity.copilot.feature.maps

import com.antigravity.copilot.core.designsystem.tokens.CopilotShapes
import com.antigravity.copilot.core.designsystem.components.cockpitSurface
import com.antigravity.copilot.core.designsystem.CockpitTheme
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
import androidx.compose.material.icons.outlined.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import com.antigravity.copilot.core.designsystem.components.Raahi
import com.antigravity.copilot.core.designsystem.components.RaahiButton
import com.antigravity.copilot.core.designsystem.components.raahiCard
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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
private const val MAP_MAX_FPS = 30

/**
 * Cap while the map is embedded on the Drive screen. The map is a TextureView, so every map frame
 * forces hwui to redraw the whole window (all dashboard cards). Frame-timing benchmarks showed the
 * Drive screen bound by RenderThread draw commands, not by MapLibre or Compose; a lower cap cuts
 * those full-window redraws. Full screen keeps [MAP_MAX_FPS].
 */
private const val MAP_EMBEDDED_FPS = 20

private fun mapFpsFor(isFullScreen: Boolean) = if (isFullScreen) MAP_MAX_FPS else MAP_EMBEDDED_FPS

@Composable
fun LiveMapPanel(
    latestPoint: () -> TripPoint?,
    trail: () -> List<TripPoint>,
    modifier: Modifier = Modifier,
    speedLimitInfo: SpeedLimitInfo = SpeedLimitInfo(),
    isFullScreen: Boolean = false,
    isCovered: Boolean = false,
    onToggleFullScreen: () -> Unit = {},
    /** Top-right overlay (the rotating trip stat); recomposes on its own, not with the map. */
    topOverlay: @Composable () -> Unit = {}
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
    val currentIsFullScreen by rememberUpdatedState(isFullScreen)
    LaunchedEffect(isFullScreen) {
        lifecycleHolder.mapView?.setMaximumFps(mapFpsFor(isFullScreen))
    }
    val mapController = remember { CarMapController(context.applicationContext, coroutineScope) }

    val hasMapInstalled by mapController.hasMapInstalled.collectAsStateWithLifecycle()
    val activeRegionTitle by mapController.activeRegionTitle.collectAsStateWithLifecycle()
    val is3DActive by mapController.is3DTiltActive.collectAsStateWithLifecycle()
    val isHeadingUp by mapController.isHeadingUp.collectAsStateWithLifecycle()
    val isFollowing by mapController.isFollowing.collectAsStateWithLifecycle()
    val roadMileageVisible by mapController.roadMileageVisible.collectAsStateWithLifecycle()
    val selectedRoadCell by mapController.selectedRoadCell.collectAsStateWithLifecycle()

    // Raahi Day / Night basemap colours follow the app theme.
    val mapPalette = CockpitTheme.map
    LaunchedEffect(mapController, mapPalette) { mapController.setMapPalette(mapPalette) }

    // Ensure MapLibre native engine is initialized with applicationContext
    remember {
        try {
            Mapbox.getInstance(context.applicationContext)
        } catch (_: Exception) {}
        OfflineMapManager.init(context.applicationContext)
    }

    // GPS ticks go straight to the controller (native camera ease + puck animation) through
    // snapshotFlow: a new fix never recomposes this panel or its overlay buttons.
    val currentLatestPoint by rememberUpdatedState(latestPoint)
    val currentTrail by rememberUpdatedState(trail)
    LaunchedEffect(mapController) {
        snapshotFlow { currentLatestPoint() }.collect { pt ->
            if (pt != null) {
                mapController.updateVehiclePosition(
                    lat = pt.latitude,
                    lon = pt.longitude,
                    bearing = pt.bearing,
                    speedKmh = pt.speedKmh
                )
            }
        }
    }

    // Enabled geofences as dashed circles with their names (display only).
    val geofences by remember { com.antigravity.copilot.core.trip.TripGeofenceManager.getInstance(context.applicationContext).geofences }
        .collectAsStateWithLifecycle()
    LaunchedEffect(mapController, geofences) {
        mapController.updateGeofences(
            geofences.filter { it.isEnabled }.map {
                CarMapController.GeofenceRing(it.name, it.latitude, it.longitude, it.radiusMeters.toDouble())
            }
        )
    }

    // Live trail with overlap colouring (throttled + computed off the main thread)
    LaunchedEffect(mapController) {
        snapshotFlow { currentTrail() }.collect { mapController.updateTrail(it) }
    }

    // Pause rendering while an opaque overlay covers the map; never destroy on tab switches.
    SideEffect {
        if (lifecycleHolder.visible == isCovered) {
            lifecycleHolder.visible = !isCovered
            lifecycleHolder.sync()
            mapController.setRenderingActive(!isCovered)
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

    // MapLibre paints this until its first frame is rendered (default is a beige #F0E9E1 flash).
    val mapLoadColor = CockpitTheme.colors.bg.toArgb()

    // Fade-in on first load. Also reveal if no offline map exists (nothing will ever render).
    var mapRevealed by remember { mutableStateOf(false) }
    LaunchedEffect(hasMapInstalled) {
        if (!hasMapInstalled) { kotlinx.coroutines.delay(1500); mapRevealed = true }
    }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(4000); mapRevealed = true } // safety net
    val coverAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (mapRevealed) 0f else 1f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 900),
        label = "mapReveal"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CockpitTheme.colors.bg)
    ) {
        // MapLibre MapView with textureMode(true) to avoid black-screen head unit GPU bugs
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val options = MapboxMapOptions.createFromAttributes(ctx)
                    .textureMode(true) // Crucial for older Mali / Adreno GPUs
                    .pixelRatio(1.0f) // Cap map density at 1x for GLES2 head units
                    .foregroundLoadColor(mapLoadColor)
                    .compassEnabled(false)
                    .logoEnabled(false)
                    .attributionEnabled(false)
                    .minZoomPreference(MAP_MIN_ZOOM_LEVEL)
                    .maxZoomPreference(MAP_MAX_ZOOM_LEVEL)

                MapView(ctx, options).apply {
                    onCreate(null)
                    // Follow mode re-renders continuously while driving: cap at 30 fps full screen
                    // and 20 fps embedded on Drive (see MAP_EMBEDDED_FPS).
                    setMaximumFps(mapFpsFor(currentIsFullScreen))
                    lifecycleHolder.mapView = this
                    // The lifecycle observer may already have replayed ON_START/ON_RESUME before this
                    // view existed (fresh launch): bring the MapView up to the current state, or it
                    // never renders until the app is backgrounded once.
                    lifecycleHolder.hostState = lifecycleOwner.lifecycle.currentState
                    lifecycleHolder.sync()
                    // Reveal the map (fade out the cover) once the first full render is done.
                    addOnDidFinishRenderingMapListener { fully -> if (fully) mapRevealed = true }
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

        // Cover that fades away so the map eases in instead of popping in on launch.
        if (coverAlpha > 0.01f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(5f)
                    .graphicsLayer { alpha = coverAlpha }
                    .background(CockpitTheme.colors.bg)
            )
        }

        // Missing offline map prompt
        if (!hasMapInstalled) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(15f)
                    .background(CockpitTheme.colors.scrim),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .widthIn(max = 460.dp)
                        .raahiCard()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Outlined.CloudDownload, contentDescription = null, tint = CockpitTheme.colors.accent, modifier = Modifier.size(40.dp))
                    Text(
                        text = if (activeRegionTitle != null) "No map for $activeRegionTitle" else "No offline map installed",
                        style = CockpitTheme.typography.titleLarge,
                        color = CockpitTheme.colors.textPrimary,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Import india.pmtiles or set a download URL in the offline maps manager (see MAP_SETUP.md).",
                        style = CockpitTheme.typography.bodyMedium,
                        color = CockpitTheme.colors.textSecondary,
                        textAlign = TextAlign.Center
                    )
                    RaahiButton(
                        text = "Open offline maps",
                        icon = Icons.Outlined.Map,
                        onClick = { showOfflineManagerDialog = true }
                    )
                }
            }
        }

        // Speed-limit sign: shown once, top-left of the map.
        if (speedLimitInfo.speedLimitKmh > 0) {
            SpeedLimitSign(
                limitKmh = speedLimitInfo.speedLimitKmh,
                modifier = Modifier.align(Alignment.TopStart).padding(14.dp)
            )
        }

        // Top-right: rotating trip stat, plus the mileage scale while that overlay is on.
        // On a narrow map (small phones) it sits under the limit sign, clear of the map controls.
        BoxWithConstraints(modifier = Modifier.matchParentSize()) {
            val narrow = maxWidth < 420.dp
            Column(
                modifier = Modifier
                    .align(if (narrow) Alignment.TopStart else Alignment.TopEnd)
                    // Narrow map: stacked under the speed-limit sign, clear of the controls.
                    .padding(start = 14.dp, end = 14.dp, top = if (narrow) 92.dp else 14.dp),
                horizontalAlignment = if (narrow) Alignment.Start else Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                topOverlay()
                if (roadMileageVisible) MileageLegendChip()
            }
        }

        // Map controls, bottom-right: secondary menu, heading, recenter (54 dp).
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .zIndex(20f)
                .padding(14.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AnimatedVisibility(visible = menuExpanded, enter = fadeIn(), exit = fadeOut()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
                    MapMenuItem(
                        icon = if (is3DActive) Icons.Outlined.ViewInAr else Icons.Outlined.Layers,
                        label = if (is3DActive) "Auto 3D (moving)" else "2D only",
                        isActive = is3DActive,
                        onClick = { mapController.toggle3DTilt(); menuExpanded = false }
                    )
                    MapMenuItem(
                        icon = Icons.Outlined.LocalGasStation,
                        label = "Mileage overlay",
                        isActive = roadMileageVisible,
                        onClick = { mapController.toggleRoadMileage(); menuExpanded = false }
                    )
                    MapMenuItem(
                        icon = Icons.Outlined.CloudDownload,
                        label = "Offline maps",
                        isActive = false,
                        onClick = { showOfflineManagerDialog = true; menuExpanded = false }
                    )
                    MapMenuItem(
                        icon = if (isFullScreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                        label = if (isFullScreen) "Exit full screen" else "Full screen",
                        isActive = isFullScreen,
                        onClick = { onToggleFullScreen(); menuExpanded = false }
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MapFloatingButton(
                    icon = if (menuExpanded) Icons.Outlined.Close else Icons.Outlined.MoreVert,
                    contentDescription = if (menuExpanded) "Close map menu" else "Map options",
                    isActive = menuExpanded,
                    onClick = { menuExpanded = !menuExpanded }
                )
                MapFloatingButton(
                    icon = if (isHeadingUp) Icons.Outlined.Explore else Icons.Outlined.Navigation,
                    contentDescription = if (isHeadingUp) "Heading up (tap for north up)" else "North up (tap for heading up)",
                    isActive = false,
                    onClick = { mapController.toggleHeadingMode() }
                )
                // Highlighted while follow / auto-zoom is suspended by a gesture: tap to resume.
                MapFloatingButton(
                    icon = if (isFollowing) Icons.Outlined.MyLocation else Icons.Outlined.LocationSearching,
                    contentDescription = if (isFollowing) "Recenter" else "Resume follow",
                    isActive = !isFollowing,
                    onClick = { mapController.recenter() }
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
            confirmButton = { TextButton(onClick = { mapController.clearSelectedRoadCell() }) { Text("OK", color = CockpitTheme.colors.textPrimary) } }
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

/** Map control surface: solid card, cyan fill when active (icon then on-accent). */
@Composable
private fun Modifier.mapControlSurface(isActive: Boolean, shape: androidx.compose.ui.graphics.Shape): Modifier {
    val c = CockpitTheme.colors
    return this
        .clip(shape)
        .background(if (isActive) c.accent else c.card)
        .border(1.dp, if (isActive) c.accent else c.border, shape)
}

@Composable
private fun MapFloatingButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val c = CockpitTheme.colors
    Box(
        modifier = Modifier
            .size(Raahi.RecenterSize)
            .mapControlSurface(isActive, RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (isActive) c.onAccent else c.textPrimary,
            modifier = Modifier.size(26.dp)
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
    val c = CockpitTheme.colors
    Row(
        modifier = Modifier
            .height(Raahi.RecenterSize)
            .mapControlSurface(isActive, Raahi.PillShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val tint = if (isActive) c.onAccent else c.textPrimary
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = label, color = tint, style = CockpitTheme.typography.labelLarge, maxLines = 1)
    }
}

/** Regulatory speed-limit sign (white disc, red ring); the sign colours are fixed by law, not theme. */
@Composable
fun SpeedLimitSign(limitKmh: Int, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 58.dp) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(SignWhite)
            .border(size * 0.09f, SignRed, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "$limitKmh",
            style = CockpitTheme.typography.headlineMedium,
            color = SignBlack,
            maxLines = 1
        )
    }
}

private val SignWhite = Color(0xFFFFFFFF)
private val SignRed = Color(0xFFD7262E)
private val SignBlack = Color(0xFF0B1220)

@Composable
private fun LegendDot(color: Color) {
    Box(Modifier.size(12.dp).clip(CircleShape).background(color))
}

@Composable
private fun LegendChipFrame(content: @Composable RowScope.() -> Unit) {
    val c = CockpitTheme.colors
    Row(
        modifier = Modifier
            .clip(Raahi.PillShape)
            .background(c.card)
            .border(1.dp, c.border, Raahi.PillShape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}

/** Mileage scale: red (low km/L) → amber → green (high km/L), relative to learned mileage. */
@Composable
fun MileageLegendChip() {
    val c = CockpitTheme.colors
    val label = CockpitTheme.typography.labelMedium
    LegendChipFrame {
        Text("km/L", style = label, color = c.textSecondary)
        LegendDot(c.critical); Text("Low", style = label, color = c.textPrimary)
        LegendDot(c.warning)
        LegendDot(c.positive); Text("High", style = label, color = c.textPrimary)
    }
}
