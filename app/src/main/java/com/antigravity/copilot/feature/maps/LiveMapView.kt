package com.antigravity.copilot.feature.maps

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import com.antigravity.copilot.core.designsystem.AutomotiveDesignTokens
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.antigravity.copilot.R
import com.antigravity.copilot.core.designsystem.*
import com.antigravity.copilot.core.location.SpeedLimitInfo
import com.antigravity.copilot.core.map.GpsLocationFilter
import com.antigravity.copilot.core.map.IndiaOfflineMapManager
import com.antigravity.copilot.core.map.OfflineMapManager
import com.antigravity.copilot.core.settings.AppSettingsManager
import kotlinx.coroutines.delay
import com.antigravity.copilot.core.model.TripPoint
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent

private const val MAP_MIN_ZOOM_LEVEL = 0.0
private const val MAP_MAX_ZOOM_LEVEL = 20.0
private const val MAP_DEFAULT_DRIVING_ZOOM = 17.5

/**
 * PulsingAuraOverlay:
 * Custom hardware-accelerated overlay that draws a continuous breathing radar pulse
 * perfectly centered on the blue GPS location indicator.
 */
class PulsingAuraOverlay : org.osmdroid.views.overlay.Overlay() {
    var geoPoint: GeoPoint? = null
    var isMoving: Boolean = false
    var isPaused: Boolean = false

    private val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#331A73E8")
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#8000E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    override fun onDetach(mapView: MapView?) {
        pauseAnimation()
        super.onDetach(mapView)
    }

    fun pauseAnimation() {
        isPaused = true
    }

    fun resumeAnimation(mapView: MapView? = null) {
        if (isPaused) {
            isPaused = false
            mapView?.postInvalidate()
        }
    }

    override fun draw(canvas: Canvas?, mapView: MapView?, shadow: Boolean) {
        if (shadow || canvas == null || mapView == null || isPaused) return
        val current = geoPoint ?: return

        val pt = android.graphics.Point()
        mapView.projection.toPixels(current, pt)

        val radius = if (isMoving) 28f else 24f
        auraPaint.alpha = 50
        ringPaint.alpha = 140

        canvas.drawCircle(pt.x.toFloat(), pt.y.toFloat(), radius, auraPaint)
        canvas.drawCircle(pt.x.toFloat(), pt.y.toFloat(), radius, ringPaint)
    }
}

/**
 * SmoothMapNavigator:
 * Senior-grade automotive camera controller & telemetry interpolator.
 * Handles continuous 60 FPS dead-reckoning, anti-jitter filtering,
 * landscape lookahead centering, and orientation smoothing.
 */
class SmoothMapNavigator(
    private val mapView: MapView,
    private val locationMarker: Marker?,
    private val auraOverlay: PulsingAuraOverlay? = null
) {
    private val gpsFilter = GpsLocationFilter()
    private var currentLat: Double = 0.0
    private var currentLon: Double = 0.0
    private var currentBearing: Float = 0f
    var currentAccuracyMeters: Float = 10f

    private var isInitialized = false
    private var isFlyoverActive = false

    var isHeadingUpMode: Boolean = true
    var currentSpeedKmh: Float = 0f

    private var movementAnimator: ValueAnimator? = null
    private var flyoverAnimator: ValueAnimator? = null
    private var orientationAnimator: ValueAnimator? = null

    // Multi-pass trail leg configuration (Ultra-bright high-visibility neon colors for satellite maps)
    private data class LegConfig(val colorHex: String, val strokeWidth: Float)
    private val LEG_CONFIGS = listOf(
        LegConfig("#00F5FF", 14f),  // Leg 0: Outbound / 1st pass (Ultra-Bright Electric Cyan 14f)
        LegConfig("#FFE600", 11f),  // Leg 1: Return / 2nd pass (High-Intensity Laser Yellow 11f)
        LegConfig("#FF1744", 9f),   // Leg 2: 3rd pass (Vivid Neon Hot Coral / Torch Red 9f)
        LegConfig("#FF00D4", 7.5f), // Leg 3: 4th pass (High-Intensity Fluorescent Magenta 7.5f)
        LegConfig("#FF6D00", 6.5f)  // Leg 4+: Subsequent passes (Ultra-Radiant Electric Amber-Orange 6.5f)
    )

    private val legPolylines = mutableListOf<Polyline>()
    private val segmentedLegPoints = mutableListOf<MutableList<GeoPoint>>()
    private var isTrailActive = false
    private var lastProcessedTrailSize = 0
    private var legStartBearing = 0f
    private var consecutiveReverseCount = 0

    fun onCompassHeading(heading: Float, isFollowMode: Boolean) {
        val smoothed = gpsFilter.fuseCompassHeading(heading)
        if (currentSpeedKmh < 3.0f && !isFlyoverActive) {
            currentBearing = smoothed
            locationMarker?.rotation = currentBearing
            if (isFollowMode && isHeadingUpMode) {
                mapView.mapOrientation = (360f - currentBearing) % 360f
            }
            mapView.postInvalidate()
        }
    }

    /**
     * Updates and color-codes active trail legs.
     * When vehicle turns around and drives back on the same road, starts a new distinct colored leg.
     * Tiered concentric widths ensure earlier passes remain visible underneath returning passes!
     * If trail is empty, removes all lines immediately.
     * Incremental processing avoids O(N) iteration and redundant GeoPoint allocations on the UI thread.
     */
    fun updateTrail(trail: List<TripPoint>) {
        if (trail.isEmpty() || trail.size < 2) {
            isTrailActive = false
            lastProcessedTrailSize = 0
            legStartBearing = 0f
            consecutiveReverseCount = 0
            segmentedLegPoints.clear()
            for (pl in legPolylines) {
                pl.setPoints(emptyList())
                mapView.overlays.remove(pl)
            }
            legPolylines.clear()
            mapView.postInvalidate()
            return
        }

        // Reset if trail was truncated, replaced, or reset
        val isNewTrip = trail.size < lastProcessedTrailSize ||
                (segmentedLegPoints.isNotEmpty() && segmentedLegPoints[0].isNotEmpty() &&
                        (segmentedLegPoints[0][0].latitude != trail[0].latitude ||
                         segmentedLegPoints[0][0].longitude != trail[0].longitude))

        if (isNewTrip) {
            lastProcessedTrailSize = 0
            legStartBearing = 0f
            consecutiveReverseCount = 0
            segmentedLegPoints.clear()
        }

        isTrailActive = true

        if (lastProcessedTrailSize == 0) {
            val initialLeg = mutableListOf<GeoPoint>()
            initialLeg.add(GeoPoint(trail[0].latitude, trail[0].longitude))
            segmentedLegPoints.add(initialLeg)
            legStartBearing = trail[0].bearing
            consecutiveReverseCount = 0
            lastProcessedTrailSize = 1
        }

        var newLegAdded = false
        var currentLeg = segmentedLegPoints.last()

        for (i in lastProcessedTrailSize until trail.size) {
            val pt = trail[i]
            val geo = GeoPoint(pt.latitude, pt.longitude)

            // Detect sustained directional reversal (> 125° for at least 3 points)
            if (pt.speedKmh > 2.0f && pt.bearing > 0f && legStartBearing > 0f) {
                val diff = kotlin.math.abs((pt.bearing - legStartBearing + 540f) % 360f - 180f)
                if (diff > 125f) {
                    consecutiveReverseCount++
                    if (consecutiveReverseCount >= 3) {
                        val lastTurnPoint = currentLeg.lastOrNull() ?: geo
                        currentLeg = mutableListOf()
                        currentLeg.add(lastTurnPoint)
                        currentLeg.add(geo)
                        segmentedLegPoints.add(currentLeg)
                        legStartBearing = pt.bearing
                        consecutiveReverseCount = 0
                        newLegAdded = true
                        continue
                    }
                } else {
                    consecutiveReverseCount = 0
                    legStartBearing = legStartBearing * 0.9f + pt.bearing * 0.1f
                }
            }
            currentLeg.add(geo)
        }

        lastProcessedTrailSize = trail.size

        syncLegPolylines(GeoPoint(currentLat, currentLon), forceFullSync = isNewTrip || newLegAdded)
    }

    private fun syncLegPolylines(currentGeo: GeoPoint, forceFullSync: Boolean = false) {
        if (!isTrailActive || segmentedLegPoints.isEmpty()) {
            for (pl in legPolylines) {
                pl.setPoints(emptyList())
                mapView.overlays.remove(pl)
            }
            legPolylines.clear()
            mapView.postInvalidate()
            return
        }

        val neededLegs = segmentedLegPoints.size

        while (legPolylines.size > neededLegs) {
            val lastPl = legPolylines.removeAt(legPolylines.size - 1)
            lastPl.setPoints(emptyList())
            mapView.overlays.remove(lastPl)
        }

        val previousCount = legPolylines.size
        while (legPolylines.size < neededLegs) {
            val legIdx = legPolylines.size
            val config = LEG_CONFIGS.getOrElse(legIdx) { LEG_CONFIGS.last() }
            val polyline = Polyline().apply {
                outlinePaint.color = android.graphics.Color.parseColor(config.colorHex)
                outlinePaint.strokeWidth = config.strokeWidth
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.strokeJoin = Paint.Join.ROUND
                outlinePaint.isAntiAlias = true
            }
            val insertIdx = if (auraOverlay != null) {
                val idx = mapView.overlays.indexOf(auraOverlay)
                if (idx >= 0) idx else mapView.overlays.size
            } else {
                mapView.overlays.size
            }
            mapView.overlays.add(insertIdx, polyline)
            legPolylines.add(polyline)
        }

        if (forceFullSync || previousCount < neededLegs) {
            // Update all prior frozen legs when a new leg is added or full sync requested
            for (i in 0 until neededLegs - 1) {
                legPolylines[i].setPoints(ArrayList(segmentedLegPoints[i]))
            }
        }

        // Active leg points update
        val lastIdx = neededLegs - 1
        val pts = ArrayList(segmentedLegPoints[lastIdx])
        if (currentLat != 0.0 && currentLon != 0.0) {
            pts.add(currentGeo)
        }
        legPolylines[lastIdx].setPoints(pts)
        mapView.postInvalidate()
    }

    private fun updateActiveLegHead(geo: GeoPoint) {
        if (!isTrailActive || legPolylines.isEmpty()) return
        val lastIdx = legPolylines.size - 1
        val basePts = segmentedLegPoints.getOrNull(lastIdx) ?: return
        val animatedPts = ArrayList(basePts)
        animatedPts.add(geo)
        legPolylines[lastIdx].setPoints(animatedPts)
    }

    /**
     * Initial Focus & Camera Positioning:
     * Immediately positions camera at current location and standard driving zoom (13.0)
     * without triggering jarring altitude flyover or zoom-in animations upon returning to homescreen.
     */
    fun performInitialFocus(targetPoint: TripPoint, isFollowMode: Boolean) {
        if (targetPoint.latitude == 0.0 && targetPoint.longitude == 0.0) return

        isFlyoverActive = false
        movementAnimator?.cancel()
        flyoverAnimator?.cancel()

        val filtered = gpsFilter.filter(
            rawLat = targetPoint.latitude,
            rawLon = targetPoint.longitude,
            rawBearing = targetPoint.bearing,
            rawSpeedKmh = targetPoint.speedKmh,
            rawAccuracyMeters = targetPoint.accuracy
        )

        currentLat = filtered.latitude
        currentLon = filtered.longitude
        currentBearing = filtered.bearing
        currentSpeedKmh = filtered.speedKmh
        currentAccuracyMeters = targetPoint.accuracy
        isInitialized = true

        val geo = GeoPoint(currentLat, currentLon)
        locationMarker?.position = geo
        locationMarker?.rotation = currentBearing
        auraOverlay?.geoPoint = geo
        auraOverlay?.isMoving = currentSpeedKmh > 2.0f

        if (isFollowMode) {
            applyLookaheadOffset(true)
            mapView.controller.setCenter(geo)
            mapView.setExpectedCenter(geo)
            if (isHeadingUpMode && currentBearing != 0f) {
                mapView.mapOrientation = (360f - currentBearing) % 360f
            } else {
                mapView.mapOrientation = 0f
            }
        }

        syncLegPolylines(geo)
        mapView.controller.setZoom(MAP_DEFAULT_DRIVING_ZOOM)
        mapView.postInvalidate()
    }

    /**
     * Real-Time Telemetry Update:
     * Runs through GpsLocationFilter to reject micro-jitter and multipath spikes,
     * then executes 60 FPS smooth dead-reckoning animation.
     */
    fun updateTarget(targetPoint: TripPoint, isFollowMode: Boolean) {
        if (targetPoint.latitude == 0.0 && targetPoint.longitude == 0.0) return

        if (!isInitialized) {
            performInitialFocus(targetPoint, isFollowMode)
            return
        }

        if (isFlyoverActive) {
            return
        }

        // Apply noise filter
        val filtered = gpsFilter.filter(
            rawLat = targetPoint.latitude,
            rawLon = targetPoint.longitude,
            rawBearing = targetPoint.bearing,
            rawSpeedKmh = targetPoint.speedKmh,
            rawAccuracyMeters = targetPoint.accuracy
        )

        val targetLat = filtered.latitude
        val targetLon = filtered.longitude
        val targetSpeed = filtered.speedKmh
        currentSpeedKmh = targetSpeed

        val startLat = currentLat
        val startLon = currentLon
        val startBearing = currentBearing

        // Shortest angular path
        val shortestDelta = (filtered.bearing - startBearing + 540f) % 360f - 180f
        val targetBearing = startBearing + shortestDelta

        val latDelta = kotlin.math.abs(targetLat - startLat)
        val lonDelta = kotlin.math.abs(targetLon - startLon)
        val bearingDelta = kotlin.math.abs(shortestDelta)

        // Stationary optimization: when parked or moving under 1.5 km/h with negligible coordinate shift,
        // cancel any running animator and update marker statically to save CPU cycles.
        if (targetSpeed < 1.5f && latDelta < 0.00008 && lonDelta < 0.00008 && bearingDelta < 2.5f) {
            movementAnimator?.cancel()
            if (latDelta > 0.000002 || lonDelta > 0.000002 || bearingDelta > 0.8f) {
                currentLat = targetLat
                currentLon = targetLon
                currentBearing = (targetBearing + 360f) % 360f
                val geo = GeoPoint(currentLat, currentLon)
                locationMarker?.position = geo
                locationMarker?.rotation = currentBearing
                auraOverlay?.geoPoint = geo
                auraOverlay?.isMoving = false
                if (isFollowMode) {
                    mapView.setExpectedCenter(geo)
                }
                mapView.postInvalidate()
            }
            return
        }

        movementAnimator?.cancel()

        // Dynamically scale animation duration to GPS update cadence (~850-950ms)
        val animDuration = when {
            targetSpeed > 60f -> 850L
            targetSpeed > 20f -> 920L
            else -> 960L
        }

        movementAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = animDuration
            interpolator = LinearInterpolator()
            addUpdateListener { va ->
                val fraction = va.animatedFraction
                currentLat = startLat + (targetLat - startLat) * fraction
                currentLon = startLon + (targetLon - startLon) * fraction
                currentBearing = (startBearing + (targetBearing - startBearing) * fraction + 360f) % 360f

                val geo = GeoPoint(currentLat, currentLon)
                locationMarker?.position = geo
                locationMarker?.rotation = currentBearing
                auraOverlay?.geoPoint = geo
                auraOverlay?.isMoving = targetSpeed > 2.0f

                // Update active polyline head during live motion
                if (isTrailActive) {
                    updateActiveLegHead(geo)
                }

                if (isFollowMode) {
                    applyLookaheadOffset(true)
                    mapView.setExpectedCenter(geo)
                    if (isHeadingUpMode) {
                        mapView.mapOrientation = (360f - currentBearing) % 360f
                    } else {
                        mapView.mapOrientation = 0f
                    }
                }
                mapView.postInvalidate()
            }
            start()
        }
    }

    /**
     * Applies lookahead camera offset in landscape mode:
     * Keeps car at 38% from bottom so driver sees 62% of road ahead.
     */
    fun applyLookaheadOffset(isFollowMode: Boolean) {
        if (isFollowMode && isHeadingUpMode) {
            val screenHeight = mapView.height
            if (screenHeight > 0) {
                val offsetY = (screenHeight * 0.18f).toInt()
                mapView.setMapCenterOffset(0, offsetY)
            }
        } else {
            mapView.setMapCenterOffset(0, 0)
        }
    }

    /**
     * Smoothly rotates map back to True North (0 degrees).
     */
    fun animateToNorthUp(onComplete: () -> Unit = {}) {
        orientationAnimator?.cancel()
        val currentOrientation = mapView.mapOrientation
        val shortestDelta = (0f - currentOrientation + 540f) % 360f - 180f
        val target = currentOrientation + shortestDelta

        orientationAnimator = ValueAnimator.ofFloat(currentOrientation, target).apply {
            duration = 400L
            interpolator = DecelerateInterpolator()
            addUpdateListener { va ->
                mapView.mapOrientation = (va.animatedValue as Float + 360f) % 360f
                mapView.postInvalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    mapView.mapOrientation = 0f
                    mapView.postInvalidate()
                    onComplete()
                }
            })
            start()
        }
    }

    /**
     * Smoothly glides camera back to vehicle from free-pan mode.
     */
    fun smoothRecenter(onComplete: () -> Unit = {}) {
        if (currentLat == 0.0 && currentLon == 0.0) return
        val currentCenter = mapView.mapCenter as? GeoPoint ?: GeoPoint(currentLat, currentLon)
        val targetGeo = GeoPoint(currentLat, currentLon)

        val startLat = currentCenter.latitude
        val startLon = currentCenter.longitude

        applyLookaheadOffset(true)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 500L
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener { va ->
                val f = va.animatedFraction
                val lat = startLat + (targetGeo.latitude - startLat) * f
                val lon = startLon + (targetGeo.longitude - startLon) * f
                mapView.controller.setCenter(GeoPoint(lat, lon))
                mapView.postInvalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    mapView.controller.setCenter(targetGeo)
                    mapView.setExpectedCenter(targetGeo)
                    mapView.postInvalidate()
                    onComplete()
                }
            })
            start()
        }
    }

    fun zoomIn() {
        val currentZoom = mapView.zoomLevelDouble
        if (currentZoom < mapView.maxZoomLevel) {
            val targetZoom = (currentZoom + 0.75).coerceAtMost(mapView.maxZoomLevel)
            mapView.controller.zoomTo(targetZoom, 300L)
        }
    }

    fun zoomOut() {
        val currentZoom = mapView.zoomLevelDouble
        if (currentZoom > mapView.minZoomLevel) {
            val targetZoom = (currentZoom - 0.75).coerceAtLeast(mapView.minZoomLevel)
            mapView.controller.zoomTo(targetZoom, 300L)
        }
    }

    fun pause() {
        movementAnimator?.pause()
        flyoverAnimator?.pause()
        orientationAnimator?.pause()
        auraOverlay?.pauseAnimation()
    }

    fun resume() {
        movementAnimator?.resume()
        flyoverAnimator?.resume()
        orientationAnimator?.resume()
        auraOverlay?.resumeAnimation(mapView)
    }

    fun release() {
        movementAnimator?.cancel()
        movementAnimator = null
        flyoverAnimator?.cancel()
        flyoverAnimator = null
        orientationAnimator?.cancel()
        orientationAnimator = null
        auraOverlay?.pauseAnimation()
        gpsFilter.reset()
        try {
            for (pl in legPolylines) {
                try {
                    pl.setPoints(emptyList())
                } catch (_: Exception) {}
                try {
                    mapView.overlays.remove(pl)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        legPolylines.clear()
        segmentedLegPoints.clear()
        isTrailActive = false
    }
}

/**
 * LiveMapPanel:
 * Senior-engineered, 100% Dark Mode Automotive Map Navigation Component.
 * Optimized for landscape 1280×800 car head-units and high-res mobile displays.
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

    var mapViewInstance by remember { mutableStateOf<MapView?>(null) }
    var navigator by remember { mutableStateOf<SmoothMapNavigator?>(null) }

    var isFollowMode by remember { mutableStateOf(true) }
    var isHeadingUpMode by remember { mutableStateOf(true) }
    var showOfflineDialog by remember { mutableStateOf(false) }
    var currentMapOrientation by remember { mutableStateOf(0f) }

    // Automatic 60-Second Recenter Timer State
    var lastMapInteractionTime by remember { mutableLongStateOf(0L) }

    LaunchedEffect(isFollowMode, lastMapInteractionTime) {
        if (!isFollowMode) {
            while (true) {
                val elapsed = System.currentTimeMillis() - lastMapInteractionTime
                val remaining = 60_000L - elapsed
                if (remaining <= 0L) {
                    isFollowMode = true
                    navigator?.smoothRecenter()
                    break
                }
                delay(1000L)
            }
        }
    }

    // Map Lighting Theme Observation (Dark Mode Cockpit vs Light Mode Daytime)
    val appSettings by AppSettingsManager.settingsState.collectAsState()
    val isMapDarkMode = appSettings.mapDarkMode

    LaunchedEffect(mapViewInstance, isMapDarkMode) {
        mapViewInstance?.let { mv ->
            mv.minZoomLevel = MAP_MIN_ZOOM_LEVEL
            mv.maxZoomLevel = MAP_MAX_ZOOM_LEVEL
            mv.setTileSource(OfflineMapManager.NORMAL_ROADMAP_SOURCE)
            mv.overlayManager.tilesOverlay.setColorFilter(
                if (isMapDarkMode) OfflineMapManager.DARK_MODE_FILTER else null
            )
            mv.invalidate()
        }
    }

    // Offline Download State Observation
    val offlineDownloadState by IndiaOfflineMapManager.downloadState.collectAsState()
    val isOfflineDownloading = offlineDownloadState == IndiaOfflineMapManager.DownloadState.DOWNLOADING ||
            offlineDownloadState == IndiaOfflineMapManager.DownloadState.UPDATING

    // GPS Status Tracking
    val hasLocationPermission = remember(context) {
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    val isGpsLocked = latestPoint != null && latestPoint.latitude != 0.0 && latestPoint.longitude != 0.0
    val isVehicleMoving = (latestPoint?.speedKmh ?: 0f) > 2.0f

    // Hardware Compass Sensor Listener
    DisposableEffect(context, isHeadingUpMode, isFollowMode) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        @Suppress("DEPRECATION")
        val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ORIENTATION)

        var lastHeading = 0f
        var lastUpdateTime = 0L

        val sensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (!isHeadingUpMode) return
                val now = System.currentTimeMillis()
                if (now - lastUpdateTime < 60L) return

                if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                    val rotationMatrix = FloatArray(9)
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

                    val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try { context.display } catch (e: Exception) { null }
                    } else {
                        @Suppress("DEPRECATION")
                        (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
                    }
                    val screenRotation = display?.rotation ?: Surface.ROTATION_0

                    val (axisX, axisY) = when (screenRotation) {
                        Surface.ROTATION_90 -> Pair(SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X)
                        Surface.ROTATION_180 -> Pair(SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y)
                        Surface.ROTATION_270 -> Pair(SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X)
                        else -> Pair(SensorManager.AXIS_X, SensorManager.AXIS_Y)
                    }

                    val remappedMatrix = FloatArray(9)
                    SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remappedMatrix)
                    val orientation = FloatArray(3)
                    SensorManager.getOrientation(remappedMatrix, orientation)
                    val azimuthDeg = Math.toDegrees(orientation[0].toDouble()).toFloat()
                    val compassHeading = (azimuthDeg + 360f) % 360f

                    val minDelta = if (isVehicleMoving) 2.0f else 5.0f
                    val minInterval = if (isVehicleMoving) 80L else 500L
                    if (kotlin.math.abs(compassHeading - lastHeading) > minDelta && (now - lastUpdateTime >= minInterval)) {
                        lastHeading = compassHeading
                        lastUpdateTime = now
                        navigator?.onCompassHeading(compassHeading, isFollowMode)
                        val newOrient = mapViewInstance?.mapOrientation ?: 0f
                        if (kotlin.math.abs(newOrient - currentMapOrientation) > 3.0f) {
                            currentMapOrientation = newOrient
                        }
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        if (isHeadingUpMode && rotationSensor != null) {
            sensorManager?.registerListener(sensorListener, rotationSensor, SensorManager.SENSOR_DELAY_NORMAL)
        }

        onDispose {
            sensorManager?.unregisterListener(sensorListener)
        }
    }

    // Lifecycle Synchronization
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    mapViewInstance?.onResume()
                    navigator?.resume()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    mapViewInstance?.onPause()
                    navigator?.pause()
                }
                Lifecycle.Event.ON_DESTROY -> {
                    navigator?.pause()
                    navigator?.release()
                    mapViewInstance?.onDetach()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            navigator?.pause()
            navigator?.release()
            mapViewInstance?.onDetach()
        }
    }

    // Initialize Offline Tile Configuration & India Offline Map System
    LaunchedEffect(Unit) {
        OfflineMapManager.initOfflineConfiguration(context)
        IndiaOfflineMapManager.init(context)
    }

    // Speed Limit Color Coding
    val speedLimitBorderColor by animateColorAsState(
        targetValue = when {
            speedLimitInfo.isOverspeed -> Cockpit_RedAlert
            speedLimitInfo.isNearLimit -> Cockpit_AmberWarning
            else -> Cockpit_CardBorder
        },
        animationSpec = tween(300),
        label = "SpeedSignBorder"
    )

    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(if (isFullScreen) 0.dp else Cockpit_CardCorner))
            .background(Color(0xFF070B14))
            .border(
                if (isFullScreen) 0.dp else 1.2.dp,
                Cockpit_CardBorder,
                RoundedCornerShape(if (isFullScreen) 0.dp else Cockpit_CardCorner)
            )
            .clipToBounds()
    ) {
        // ==================== 1. DARK MODE OPENSTREETMAP CANVAS ====================
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MapView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setLayerType(View.LAYER_TYPE_NONE, null)
                    setTileSource(OfflineMapManager.NORMAL_ROADMAP_SOURCE)
                    setMultiTouchControls(true)
                    zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
                    isTilesScaledToDpi = true
                    minZoomLevel = MAP_MIN_ZOOM_LEVEL
                    maxZoomLevel = MAP_MAX_ZOOM_LEVEL
                    controller.setZoom(MAP_DEFAULT_DRIVING_ZOOM)
                    overlayManager.tilesOverlay.setColorFilter(
                        if (isMapDarkMode) OfflineMapManager.DARK_MODE_FILTER else null
                    )

                    val (initialLat, initialLon) = if (latestPoint != null) {
                        Pair(latestPoint.latitude, latestPoint.longitude)
                    } else {
                        OfflineMapManager.getLastKnownPosition(ctx)
                    }
                    controller.setCenter(GeoPoint(initialLat, initialLon))

                    // Pulsing Radar Aura Overlay (Animated expanding blue wave around indicator)
                    val auraOverlay = PulsingAuraOverlay().apply {
                        geoPoint = GeoPoint(initialLat, initialLon)
                    }
                    overlays.add(auraOverlay)

                    // Blue Circular Precision GPS Location Indicator
                    val gpsDotDrawable = ContextCompat.getDrawable(ctx, R.drawable.ic_gps_location_dot)
                    val dotSizePx = 96
                    val dotBitmap = Bitmap.createBitmap(dotSizePx, dotSizePx, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(dotBitmap)
                    gpsDotDrawable?.setBounds(0, 0, dotSizePx, dotSizePx)
                    gpsDotDrawable?.draw(canvas)
                    val dotBitmapDrawable = BitmapDrawable(ctx.resources, dotBitmap)

                    val marker = Marker(this).apply {
                        icon = dotBitmapDrawable
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        infoWindow = null
                        isFlat = true
                        position = GeoPoint(initialLat, initialLon)
                        rotation = latestPoint?.bearing ?: 0f
                    }
                    overlays.add(marker)

                    // Rotation gesture overlay for 2-finger map rotation
                    val rotationGestureOverlay = RotationGestureOverlay(this).apply {
                        isEnabled = true
                    }
                    overlays.add(rotationGestureOverlay)

                    // Free-panning touch detector: transitions out of Follow Mode and resets 60s recenter timer
                    setOnTouchListener { _, event ->
                        when (event.action) {
                            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                                lastMapInteractionTime = System.currentTimeMillis()
                                if (isFollowMode) {
                                    isFollowMode = false
                                }
                            }
                        }
                        false
                    }

                    val nav = SmoothMapNavigator(this, marker, auraOverlay).apply {
                        this.isHeadingUpMode = isHeadingUpMode
                    }
                    navigator = nav
                    mapViewInstance = this
                }
            },
            update = { _ ->
                navigator?.updateTrail(trail)
                if (latestPoint != null) {
                    navigator?.updateTarget(latestPoint, isFollowMode)
                }
            }
        )

        // ==================== 2. TOP STATUS PILL (Road / Location / GPS Status) ====================
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xFF0B1324).copy(alpha = 0.92f))
                .border(1.2.dp, Cockpit_Cyan.copy(alpha = 0.45f), RoundedCornerShape(22.dp))
                .padding(horizontal = 16.dp, vertical = 7.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Status Beacon Dot
                StatusBeaconDot(
                    isGpsLocked = isGpsLocked
                )

                val statusText = when {
                    !hasLocationPermission -> "Location Permission Required"
                    !isGpsLocked -> "Searching GPS Satellites..."
                    latestPoint == null || latestPoint.accuracy > 40f -> "GPS Signal Weak"
                    isVehicleMoving -> speedLimitInfo.roadName.ifEmpty { "Moving" }
                    else -> "Stationary"
                }

                Text(
                    text = statusText,
                    style = CockpitTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = Color.White
                )

                if (isGpsLocked && latestPoint != null && latestPoint.accuracy > 0) {
                    Text(
                        text = "\u00B1${latestPoint.accuracy.toInt()}m",
                        style = CockpitTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Medium,
                            fontFeatureSettings = "tnum"
                        ),
                        color = Cockpit_Cyan
                    )
                }
            }
        }

        // ==================== 3. FLOATING "RE-CENTER" ACTION PILL ====================
        AnimatedVisibility(
            visible = !isFollowMode && isGpsLocked,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(200)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFF0F1A30).copy(alpha = 0.95f))
                    .border(1.5.dp, Cockpit_Cyan, RoundedCornerShape(24.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = rememberRipple(bounded = true, color = Cockpit_Cyan)
                    ) {
                        isFollowMode = true
                        navigator?.smoothRecenter()
                    }
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.MyLocation,
                        contentDescription = "Re-center",
                        tint = Cockpit_Cyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "RE-CENTER VEHICLE",
                        style = CockpitTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.5.sp
                        ),
                        color = Color.White
                    )
                }
            }
        }

        // ==================== 4. ERGONOMIC RIGHT CONTROLS STACK ====================
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Compass / Orientation Mode (Heading-Up vs North-Up)
            AutomotiveMapButton(
                icon = Icons.Default.Navigation,
                contentDescription = "Orientation",
                isActive = isHeadingUpMode,
                activeColor = Cockpit_Cyan,
                iconRotation = if (isHeadingUpMode) (mapViewInstance?.mapOrientation ?: currentMapOrientation) else 0f,
                onClick = {
                    isHeadingUpMode = !isHeadingUpMode
                    navigator?.isHeadingUpMode = isHeadingUpMode
                    if (!isHeadingUpMode) {
                        navigator?.animateToNorthUp {
                            currentMapOrientation = 0f
                        }
                    } else {
                        latestPoint?.let { navigator?.updateTarget(it, isFollowMode) }
                    }
                }
            )

            // Dark / Light Mode Map Toggle (Cockpit Dark vs Daytime Road)
            AutomotiveMapButton(
                icon = if (isMapDarkMode) Icons.Default.DarkMode else Icons.Default.LightMode,
                contentDescription = if (isMapDarkMode) "Switch to Light Mode" else "Switch to Dark Mode",
                isActive = isMapDarkMode,
                activeColor = Cockpit_Cyan,
                onClick = {
                    AppSettingsManager.updateMapDarkMode(context, !isMapDarkMode)
                }
            )

            // Zoom In [+]
            AutomotiveMapButton(
                icon = Icons.Default.Add,
                contentDescription = "Zoom In",
                onClick = { navigator?.zoomIn() }
            )

            // Zoom Out [-]
            AutomotiveMapButton(
                icon = Icons.Default.Remove,
                contentDescription = "Zoom Out",
                onClick = { navigator?.zoomOut() }
            )

            // India Offline Maps Storage Manager (with active download indicator)
            AutomotiveMapButton(
                icon = Icons.Default.CloudDownload,
                contentDescription = "India Offline Maps",
                isActive = isOfflineDownloading,
                activeColor = Cockpit_GreenEco,
                onClick = { showOfflineDialog = true }
            )

            // Fullscreen / Cockpit Toggle
            AutomotiveMapButton(
                icon = if (isFullScreen) Icons.Default.FullscreenExit else Icons.Default.CropSquare,
                contentDescription = "Toggle Fullscreen",
                onClick = onToggleFullScreen
            )
        }

        // ==================== 5. FULLSCREEN SPEED TELEMETRY (Only in Fullscreen mode to avoid duplication) ====================
        if (isFullScreen) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val currentSpeed = latestPoint?.speedKmh?.toInt() ?: 0
                Row(
                    modifier = Modifier
                        .height(52.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF091122).copy(alpha = 0.94f))
                        .border(1.2.dp, speedLimitBorderColor, RoundedCornerShape(14.dp))
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$currentSpeed",
                        style = CockpitTheme.typography.displaySmall.copy(
                            fontWeight = FontWeight.Black,
                            fontFeatureSettings = "tnum"
                        ),
                        color = if (speedLimitInfo.isOverspeed) Cockpit_RedAlert else Color.White
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "km/h",
                        style = CockpitTheme.typography.labelSmall,
                        color = Cockpit_TextSecondary
                    )
                }
            }
        }

        // ==================== 6. INDIA OFFLINE MAP STORAGE DIALOG ====================
        if (showOfflineDialog) {
            IndiaOfflineMapDialog(
                onDismiss = { showOfflineDialog = false }
            )
        }
    }
}

/**
 * AutomotiveMapButton:
 * Ergonomic 48dp tactile touch control engineered for moving vehicle displays.
 */
@Composable
private fun AutomotiveMapButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    activeColor: Color = Cockpit_Cyan,
    iconRotation: Float = 0f,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val borderColor = if (isActive) activeColor else Cockpit_CardBorder
    val bgColor = if (isActive) Color(0xFF0E233C).copy(alpha = 0.95f) else Color(0xFF0C1424).copy(alpha = 0.90f)

    Box(
        modifier = modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(bgColor)
            .border(1.2.dp, borderColor, CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = rememberRipple(bounded = true, color = activeColor),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (isActive) activeColor else Color.White,
            modifier = Modifier
                .size(26.dp)
                .rotate(iconRotation)
        )
    }
}

@Composable
private fun StatusBeaconDot(
    isGpsLocked: Boolean
) {
    val dotColor = if (isGpsLocked) Cockpit_GreenEco else Cockpit_AmberWarning
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(dotColor)
    )
}
