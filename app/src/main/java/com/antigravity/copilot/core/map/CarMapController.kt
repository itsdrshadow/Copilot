package com.antigravity.copilot.core.map

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import android.view.animation.LinearInterpolator
import com.antigravity.copilot.core.logic.LatLon
import com.antigravity.copilot.core.model.TripPoint
import com.mapbox.android.gestures.MoveGestureDetector
import com.mapbox.android.gestures.RotateGestureDetector
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.mapboxsdk.camera.CameraPosition
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.maps.Style
import com.mapbox.mapboxsdk.style.expressions.Expression
import com.mapbox.mapboxsdk.style.layers.CircleLayer
import com.mapbox.mapboxsdk.style.layers.LineLayer
import com.mapbox.mapboxsdk.style.layers.Property
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.*
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.exp

/**
 * CarMapController:
 * High-performance controller for MapLibre Native on automotive Android 11 head units.
 *
 * - The style is built and the PMTiles file opened off the main thread, and only when the
 *   resolved map file actually changes (first attach, import, region switch) — never on resume.
 * - Follow mode: speed-adaptive zoom (EMA-smoothed speed), deadband against stationary GPS
 *   jitter, and linear `easeCamera` transitions spanning the GPS interval so motion is continuous.
 *   Any pan/rotate gesture suspends follow, pinch/double-tap suspends auto-zoom; Recenter resumes.
 */
class CarMapController(
    private val context: Context,
    private val coroutineScope: CoroutineScope
) {
    companion object {
        private const val TAG = "CarMapController"

        private val _firstStyleLoaded = MutableStateFlow(false)
        /** True once any map has finished loading its style this process (startup splash waits on it). */
        val firstStyleLoaded: StateFlow<Boolean> = _firstStyleLoaded.asStateFlow()
        private const val TRAIL_SOURCE_ID = "copilot_trail_source"
        private const val TRAIL_LAYER_ID = "copilot_trail_layer"
        private const val VEHICLE_SOURCE_ID = "copilot_vehicle_source"
        private const val VEHICLE_LAYER_ID = "copilot_vehicle_layer"
        private const val VEHICLE_RING_LAYER_ID = "copilot_vehicle_ring_layer"
        private const val GEOFENCE_SOURCE_ID = "copilot_geofence_source"
        private const val GEOFENCE_LINE_LAYER_ID = "copilot_geofence_line_layer"
        private const val GEOFENCE_LABEL_LAYER_ID = "copilot_geofence_label_layer"
        private const val TRAIL_MIN_INTERVAL_MS = 2_000L
        private const val ROAD_SOURCE_ID = "copilot_road_mileage_source"
        private const val ROAD_LAYER_ID = "copilot_road_mileage_layer"

        private const val DEFAULT_DRIVING_TILT = 45.0

        // Deadband: ignore sub-3 m GPS wander while (nearly) stationary.
        const val JITTER_SPEED_KMH = 3f

        /** Auto 3D: tilt only while moving at/above this speed; flat 2D below it (parked, crawling). */
        const val AUTO_3D_SPEED_KMH = 3.0
        /** The speed condition must hold this long before the tilt flips, so it never flickers. */
        private const val TILT_HOLD_MS = 1_500L
        private const val TILT_EASE_MS = 900L
        /** A fix farther than this from the camera is a relocation (first real fix, re-anchor): snap, don't pan. */
        private const val SNAP_DISTANCE_M = 2_000.0
        /** No fix for this long = parked (GNSS goes quiet / fixes are de-duplicated when stopped). */
        private const val STALE_FIX_MS = 3_000L
        private const val TILT_SPEED_TAU_MS = 1_000.0
        const val JITTER_DISTANCE_M = 3.0

        // Camera transition length tracks the *measured* fix interval (fused GPS delivers 0.5–1 s),
        // stretched slightly so each ease ends just as the next fix arrives: the camera keeps a
        // constant velocity instead of being cut off mid-ease and restarting (visible stutter).
        private const val MIN_EASE_MS = 300L
        private const val MAX_EASE_MS = 1_200L
        private const val EASE_STRETCH = 1.1
        private const val FIX_INTERVAL_ALPHA = 0.3
        private const val RECENTER_EASE_MS = 800L

        // Speed EMA time constant and minimum zoom delta worth animating.
        private const val SPEED_EMA_TAU_MS = 4_000.0
        private const val ZOOM_EPSILON = 0.08
        /** Puck GeoJSON updates are capped (each one re-serialises and re-tiles the source). */
        private const val PUCK_FRAME_MS = 33L

        /**
         * Speed → follow zoom. Piecewise-linear so the camera never steps. Kept close in at town
         * speeds (the old curve was already at 16.0 by 30 km/h, which read as "zoomed out" in
         * slow traffic):
         * 0–15 km/h 17.5 · 15–40 → 17.0 · 40–70 → 16.2 · 70–100 → 15.2 · 100–130 → 14.8 · >130 14.8.
         */
        fun autoZoomForSpeed(speedKmh: Double): Double {
            val v = speedKmh.coerceAtLeast(0.0)
            fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t.coerceIn(0.0, 1.0)
            return when {
                v <= 15.0 -> 17.5
                v <= 40.0 -> lerp(17.5, 17.0, (v - 15.0) / 25.0)
                v <= 70.0 -> lerp(17.0, 16.2, (v - 40.0) / 30.0)
                v <= 100.0 -> lerp(16.2, 15.2, (v - 70.0) / 30.0)
                v <= 130.0 -> lerp(15.2, 14.8, (v - 100.0) / 30.0)
                else -> 14.8
            }
        }

        /** EMA factor for an irregular sample interval [dtMs]. */
        fun emaAlpha(dtMs: Long, tauMs: Double = SPEED_EMA_TAU_MS): Double = 1.0 - exp(-dtMs / tauMs)
    }

    private var mapboxMap: MapboxMap? = null
    private var isStyleLoaded = false
    /** Map id baked into the loaded style (null = background-only style). */
    private var loadedMapId: String? = null
    private var styleJob: Job? = null
    private var lastRegionId: String? = null

    private val _hasMapInstalled = MutableStateFlow(true)
    val hasMapInstalled: StateFlow<Boolean> = _hasMapInstalled.asStateFlow()

    private val _activeRegionTitle = MutableStateFlow<String?>(null)
    val activeRegionTitle: StateFlow<String?> = _activeRegionTitle.asStateFlow()

    /** User preference: allow the automatic 3D tilt. When off the map is always flat. */
    private val _is3DTiltActive = MutableStateFlow(true)
    val is3DTiltActive: StateFlow<Boolean> = _is3DTiltActive.asStateFlow()

    /** Speed-driven tilt state (see [AUTO_3D_SPEED_KMH]); starts flat so load never animates a tilt. */
    private var autoTiltOn = false
    private var tiltCandidateSinceMs = 0L
    private var staleFixJob: Job? = null
    /** Fast speed EMA for the tilt decision: averages out duplicate 0 km/h fixes, still reacts within ~1 s. */
    private var tiltSpeedKmh = 0.0

    private val _isHeadingUp = MutableStateFlow(true)
    val isHeadingUp: StateFlow<Boolean> = _isHeadingUp.asStateFlow()

    /** Camera follows the vehicle. Cleared by pan/rotate gestures; restored by [recenter]. */
    private val _isFollowing = MutableStateFlow(true)
    val isFollowing: StateFlow<Boolean> = _isFollowing.asStateFlow()

    private var lastLocation = LatLng(28.6139, 77.2090)
    private var lastBearing = 0.0
    private var hasFix = false

    // Speed-adaptive zoom state
    private var smoothedSpeedKmh = 0.0
    private var lastFixElapsedMs = 0L
    /** EMA of the GPS fix interval; drives the camera / puck ease length. */
    private var fixIntervalMs = 1_000.0
    private var lastEasedZoom = autoZoomForSpeed(0.0)
    /** Set by pinch / double-tap: follow keeps tracking position but at the user's zoom. */
    private var manualZoom: Double? = null
    private var userGestureInProgress = false

    // Vehicle puck animation (kept in lock-step with the camera ease)
    private var puckPosition: LatLng? = null
    private var puckAnimator: ValueAnimator? = null

    private val moveListener = object : MapboxMap.OnMoveListener {
        override fun onMoveBegin(detector: MoveGestureDetector) = suspendFollow()
        override fun onMove(detector: MoveGestureDetector) {}
        override fun onMoveEnd(detector: MoveGestureDetector) {}
    }
    private val rotateListener = object : MapboxMap.OnRotateListener {
        override fun onRotateBegin(detector: RotateGestureDetector) = suspendFollow()
        override fun onRotate(detector: RotateGestureDetector) {}
        override fun onRotateEnd(detector: RotateGestureDetector) {}
    }
    private val cameraMoveStartedListener = MapboxMap.OnCameraMoveStartedListener { reason ->
        if (reason == MapboxMap.OnCameraMoveStartedListener.REASON_API_GESTURE) userGestureInProgress = true
    }
    private val cameraIdleListener = MapboxMap.OnCameraIdleListener {
        if (userGestureInProgress) {
            userGestureInProgress = false
            // Pinch / double-tap: keep the user's zoom until Recenter.
            mapboxMap?.let { manualZoom = it.cameraPosition.zoom; lastEasedZoom = it.cameraPosition.zoom }
        }
    }
    private val mapClickListener = MapboxMap.OnMapClickListener { latLng -> onMapTapped(latLng) }

    /**
     * Attaches the MapLibre map: restores the camera at the last vehicle position immediately
     * (no fly-in) and loads the offline style once.
     */
    fun attachMap(map: MapboxMap) {
        if (mapboxMap === map) return
        map.addOnMapClickListener(mapClickListener)
        map.addOnCameraMoveStartedListener(cameraMoveStartedListener)
        map.addOnCameraIdleListener(cameraIdleListener)
        map.addOnMoveListener(moveListener)
        map.addOnRotateListener(rotateListener)
        this.mapboxMap = map
        // Start where the car last was (not a hard-coded city) so the first fix needs no camera pan.
        if (!hasFix) {
            val (savedLat, savedLon) = OfflineMapManager.getLastKnownPosition(context)
            if (savedLat.isFinite() && savedLon.isFinite() && savedLat in -90.0..90.0 && savedLon in -180.0..180.0 &&
                !(savedLat == 0.0 && savedLon == 0.0)
            ) {
                lastLocation = LatLng(savedLat, savedLon)
            }
        }
        map.moveCamera(CameraUpdateFactory.newCameraPosition(followCameraPosition(currentFollowZoom())))
        lastRegionId = OfflineMapManager.getRegionForLocation(lastLocation.latitude, lastLocation.longitude)?.id
        requestStyle(force = true)
    }

    fun detachMap() {
        styleJob?.cancel()
        styleJob = null
        staleFixJob?.cancel()
        staleFixJob = null
        trailJob?.cancel()
        puckAnimator?.cancel()
        puckAnimator = null
        mapboxMap?.let { map ->
            map.removeOnMapClickListener(mapClickListener)
            map.removeOnCameraMoveStartedListener(cameraMoveStartedListener)
            map.removeOnCameraIdleListener(cameraIdleListener)
            map.removeOnMoveListener(moveListener)
            map.removeOnRotateListener(rotateListener)
        }
        mapboxMap = null
        isStyleLoaded = false
        loadedMapId = null
    }

    /**
     * Re-resolves the best local map file (e.g. after an import in the Offline Maps manager) and
     * reloads the style only if the file actually changed. Cheap no-op otherwise.
     */
    fun reloadStyleIfMapChanged() = requestStyle(force = false)

    /** Raahi basemap palette (Day / Night). A change reloads the style; map data is unchanged. */
    @Volatile private var mapPalette: com.antigravity.copilot.core.designsystem.colors.MapPalette =
        com.antigravity.copilot.core.designsystem.colors.RaahiNight.Map

    fun setMapPalette(palette: com.antigravity.copilot.core.designsystem.colors.MapPalette) {
        if (palette == mapPalette) return
        mapPalette = palette
        if (isStyleLoaded) requestStyle(force = true)
    }

    private data class ResolvedMap(val mapId: String?, val fileName: String?, val regionTitle: String?)

    private fun requestStyle(force: Boolean) {
        val map = mapboxMap ?: return
        val lat = lastLocation.latitude
        val lon = lastLocation.longitude
        styleJob?.cancel()
        styleJob = coroutineScope.launch {
            // File stat / open / header read are disk I/O: keep them off the main thread.
            val resolved = withContext(Dispatchers.IO) {
                val file = OfflineMapManager.getBestMapFileForLocation(context, lat, lon)
                val usable = file != null && file.isFile && file.length() > 0
                ResolvedMap(
                    mapId = MapNetworkGuard.setMapFile(if (usable) file else null),
                    fileName = file?.name,
                    regionTitle = OfflineMapManager.getRegionForLocation(lat, lon)?.title
                )
            }
            _hasMapInstalled.value = resolved.mapId != null
            _activeRegionTitle.value = resolved.regionTitle
            if (!force && isStyleLoaded && resolved.mapId == loadedMapId) return@launch

            val json = withContext(Dispatchers.Default) { buildStyleJson(resolved.mapId) } ?: return@launch
            if (mapboxMap !== map) return@launch
            isStyleLoaded = false
            map.setStyle(Style.Builder().fromJson(json)) { style ->
                if (mapboxMap !== map) return@setStyle
                setupGeofenceLayers(style)
                setupRoadMileageLayer(style)
                setupTrailLayers(style)
                setupVehicleLayer(style)
                isStyleLoaded = true
                loadedMapId = resolved.mapId
                _firstStyleLoaded.value = true
                Log.i(TAG, "MapLibre style loaded. Active pack: ${resolved.fileName ?: "None"}, Region: ${resolved.regionTitle}")
            }
        }
    }

    private fun buildStyleJson(mapId: String?): String? = OfflineStyle.build(context, mapId, mapPalette)

    // ---------------- Live trail with overlap colouring (Phase 6) ----------------
    @Volatile private var lastTrailCollection: FeatureCollection = FeatureCollection.fromFeatures(arrayOf())
    private var lastTrailComputeMs = 0L
    private var lastTrailSize = -1
    private var trailJob: Job? = null
    private var trailRetryJob: Job? = null

    private fun setupTrailLayers(style: Style) {
        try {
            if (style.getSource(TRAIL_SOURCE_ID) == null) {
                style.addSource(GeoJsonSource(TRAIL_SOURCE_ID, lastTrailCollection))
            }
            if (style.getLayer(TRAIL_LAYER_ID) == null) {
                // Raahi: single pass = trail cyan; overlaps 2 = orange, 3+ = red (pass classes
                // from TrailOverlap / PassColor are unchanged).
                val pal = mapPalette
                val colour = Expression.step(
                    Expression.get("passes"),
                    Expression.color(Color.parseColor(pal.trail)),
                    Expression.stop(2, Expression.color(Color.parseColor(pal.overlap2))),
                    Expression.stop(3, Expression.color(Color.parseColor(pal.overlap3)))
                )
                style.addLayer(
                    LineLayer(TRAIL_LAYER_ID, TRAIL_SOURCE_ID).apply {
                        setProperties(
                            lineColor(colour),
                            lineWidth(6.0f),
                            lineOpacity(0.95f),
                            lineCap(Property.LINE_CAP_ROUND),
                            lineJoin(Property.LINE_JOIN_ROUND)
                        )
                    }
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error setting up trail layers: ${e.message}")
        }
    }

    private fun setupVehicleLayer(style: Style) {
        try {
            val pos = puckPosition ?: lastLocation
            if (style.getSource(VEHICLE_SOURCE_ID) == null) {
                style.addSource(GeoJsonSource(VEHICLE_SOURCE_ID, Point.fromLngLat(pos.longitude, pos.latitude)))
            }
            val pal = mapPalette
            if (style.getLayer(VEHICLE_RING_LAYER_ID) == null) {
                // Soft cyan ring around the position dot.
                style.addLayer(
                    CircleLayer(VEHICLE_RING_LAYER_ID, VEHICLE_SOURCE_ID).apply {
                        setProperties(
                            circleRadius(20.0f),
                            circleColor(Color.parseColor(pal.trail)),
                            circleOpacity(0.18f),
                            circleStrokeColor(Color.parseColor(pal.trail)),
                            circleStrokeWidth(1.5f),
                            circleStrokeOpacity(0.55f),
                            circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP)
                        )
                    }
                )
            }
            if (style.getLayer(VEHICLE_LAYER_ID) == null) {
                style.addLayer(
                    CircleLayer(VEHICLE_LAYER_ID, VEHICLE_SOURCE_ID).apply {
                        setProperties(
                            circleRadius(8.0f),
                            circleColor(Color.parseColor(pal.trail)),
                            circleStrokeColor(Color.parseColor(pal.puckRing)),
                            circleStrokeWidth(3.0f),
                            // Keep the puck flat on the road in 3D tilt.
                            circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP)
                        )
                    }
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error setting up vehicle layer: ${e.message}")
        }
    }

    /**
     * Live trail polyline: grid pass counts (~15 m) colour each run, Douglas–Peucker simplified and
     * vertex-capped ([com.antigravity.copilot.core.logic.Config.TRAIL_MAX_RENDER_POINTS]).
     * All per-point work (including the snapshot copy) runs off the main thread and is throttled.
     */
    fun updateTrail(points: List<TripPoint>) {
        val now = SystemClock.elapsedRealtime()
        val size = points.size
        if (size == lastTrailSize) return
        if (size > 2 && lastTrailSize > 2 && now - lastTrailComputeMs < TRAIL_MIN_INTERVAL_MS) {
            // Throttled: run one trailing update when the interval ends, so the last points are
            // drawn even if no further fix arrives (previously the trail stopped short of the car).
            trailRetryJob?.cancel()
            val wait = TRAIL_MIN_INTERVAL_MS - (now - lastTrailComputeMs)
            trailRetryJob = coroutineScope.launch {
                delay(wait)
                updateTrail(points)
            }
            return
        }
        trailRetryJob?.cancel()
        lastTrailComputeMs = now
        lastTrailSize = size
        trailJob?.cancel()
        trailJob = coroutineScope.launch(Dispatchers.Default) {
            val snapshot = try {
                List(size) { i -> points[i].let { LatLon(it.latitude, it.longitude) } }
            } catch (_: IndexOutOfBoundsException) {
                return@launch // trip ended while copying; the next emission resets the trail
            }
            val runs = com.antigravity.copilot.core.logic.TrailOverlap.runs(snapshot)
            val features = runs.map { run ->
                Feature.fromGeometry(LineString.fromLngLats(run.points.map { Point.fromLngLat(it.lon, it.lat) })).apply {
                    addNumberProperty("passes", run.passes)
                }
            }
            val fc = FeatureCollection.fromFeatures(features)
            withContext(Dispatchers.Main) {
                lastTrailCollection = fc
                mapboxMap?.style?.getSourceAs<GeoJsonSource>(TRAIL_SOURCE_ID)?.setGeoJson(fc)
            }
        }
    }

    // ---------------- Geofences (display only) ----------------
    data class GeofenceRing(val name: String, val lat: Double, val lon: Double, val radiusM: Double)

    @Volatile private var geofenceCollection: FeatureCollection = FeatureCollection.fromFeatures(arrayOf())

    /** Enabled geofences drawn as dashed cyan circles with their name. Purely visual. */
    fun updateGeofences(rings: List<GeofenceRing>) {
        coroutineScope.launch(Dispatchers.Default) {
            val features = rings.flatMap { g ->
                val circle = Feature.fromGeometry(LineString.fromLngLats(circlePoints(g.lat, g.lon, g.radiusM)))
                // Label on the top edge of the circle so the vehicle dot at the centre never hides it.
                val label = Feature.fromGeometry(Point.fromLngLat(g.lon, g.lat + g.radiusM / 111_320.0)).apply { addStringProperty("name", g.name) }
                listOf(circle, label)
            }
            val fc = FeatureCollection.fromFeatures(features)
            withContext(Dispatchers.Main) {
                geofenceCollection = fc
                mapboxMap?.style?.getSourceAs<GeoJsonSource>(GEOFENCE_SOURCE_ID)?.setGeoJson(fc)
            }
        }
    }

    private fun circlePoints(lat: Double, lon: Double, radiusM: Double, steps: Int = 64): List<Point> {
        val dLat = radiusM / 111_320.0
        val dLon = radiusM / (111_320.0 * kotlin.math.cos(Math.toRadians(lat)).coerceAtLeast(0.01))
        return (0..steps).map { i ->
            val a = 2.0 * Math.PI * i / steps
            Point.fromLngLat(lon + dLon * kotlin.math.cos(a), lat + dLat * kotlin.math.sin(a))
        }
    }

    private fun setupGeofenceLayers(style: Style) {
        try {
            if (style.getSource(GEOFENCE_SOURCE_ID) == null) style.addSource(GeoJsonSource(GEOFENCE_SOURCE_ID, geofenceCollection))
            val pal = mapPalette
            if (style.getLayer(GEOFENCE_LINE_LAYER_ID) == null) {
                style.addLayer(
                    LineLayer(GEOFENCE_LINE_LAYER_ID, GEOFENCE_SOURCE_ID).apply {
                        setFilter(Expression.eq(Expression.geometryType(), Expression.literal("LineString")))
                        setProperties(
                            lineColor(Color.parseColor(pal.geofence)),
                            lineWidth(2.5f),
                            lineOpacity(0.9f),
                            lineDasharray(arrayOf(2f, 2f))
                        )
                    }
                )
            }
            if (style.getLayer(GEOFENCE_LABEL_LAYER_ID) == null) {
                style.addLayer(
                    com.mapbox.mapboxsdk.style.layers.SymbolLayer(GEOFENCE_LABEL_LAYER_ID, GEOFENCE_SOURCE_ID).apply {
                        setFilter(Expression.eq(Expression.geometryType(), Expression.literal("Point")))
                        setProperties(
                            textField(Expression.get("name")),
                            textFont(arrayOf("Noto Sans Medium")),
                            textSize(13f),
                            textColor(Color.parseColor(pal.geofence)),
                            textHaloColor(Color.parseColor(pal.labelHalo)),
                            textHaloWidth(1.5f),
                            textAllowOverlap(true)
                        )
                    }
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error setting up geofence layers: ${e.message}")
        }
    }

    // ---------------- Per-road mileage layer (Phase 7) ----------------
    data class RoadCellInfo(val avgKmpl: Double, val passes: Int, val avgSpeedKmh: Double)

    private val _roadMileageVisible = MutableStateFlow(false)
    val roadMileageVisible: StateFlow<Boolean> = _roadMileageVisible.asStateFlow()
    private val _selectedRoadCell = MutableStateFlow<RoadCellInfo?>(null)
    val selectedRoadCell: StateFlow<RoadCellInfo?> = _selectedRoadCell.asStateFlow()
    @Volatile private var roadCollection: FeatureCollection = FeatureCollection.fromFeatures(arrayOf())
    var learnedKmplProvider: () -> Double = {
        try {
            com.antigravity.copilot.core.fuel.FuelRepository.getInstance(context).getFuelTelemetrySnapshot().mileageKmpl
        } catch (_: Exception) {
            com.antigravity.copilot.core.logic.Config.DEFAULT_MILEAGE_KMPL
        }
    }

    fun toggleRoadMileage() {
        val show = !_roadMileageVisible.value
        _roadMileageVisible.value = show
        _selectedRoadCell.value = null
        if (show) refreshRoadMileage()
        mapboxMap?.style?.getLayer(ROAD_LAYER_ID)?.setProperties(visibility(if (show) Property.VISIBLE else Property.NONE))
    }

    fun clearSelectedRoadCell() {
        _selectedRoadCell.value = null
    }

    fun refreshRoadMileage() {
        coroutineScope.launch(Dispatchers.IO) {
            val cells = try {
                com.antigravity.copilot.core.database.AppDatabase.getInstance(context).roadCellDao().getAll()
            } catch (e: Exception) {
                Log.w(TAG, "Road cells unavailable: ${e.message}"); emptyList()
            }
            val learned = learnedKmplProvider()
            val features = cells.filter { it.litres > 0 && it.km >= com.antigravity.copilot.core.logic.Config.ROAD_MIN_CELL_KM }.map { c ->
                val kmpl = c.km / c.litres
                Feature.fromGeometry(Point.fromLngLat(c.longitude, c.latitude)).apply {
                    addNumberProperty("kmpl", kmpl)
                    addNumberProperty("passes", c.passes)
                    addNumberProperty("speed", if (c.km > 0) c.speedKm / c.km else 0.0)
                    addNumberProperty("frac", com.antigravity.copilot.core.logic.RoadMileage.colourFraction(kmpl, learned))
                }
            }
            val fc = FeatureCollection.fromFeatures(features)
            withContext(Dispatchers.Main) {
                roadCollection = fc
                mapboxMap?.style?.getSourceAs<GeoJsonSource>(ROAD_SOURCE_ID)?.setGeoJson(fc)
            }
        }
    }

    private fun setupRoadMileageLayer(style: Style) {
        try {
            if (style.getSource(ROAD_SOURCE_ID) == null) style.addSource(GeoJsonSource(ROAD_SOURCE_ID, roadCollection))
            if (style.getLayer(ROAD_LAYER_ID) == null) {
                // red (low km/L) -> yellow -> green (high km/L), relative to learned mileage
                val colour = Expression.interpolate(
                    Expression.linear(), Expression.get("frac"),
                    Expression.stop(0.0, Expression.color(Color.parseColor(mapPalette.mileageLow))),
                    Expression.stop(0.5, Expression.color(Color.parseColor(mapPalette.mileageMid))),
                    Expression.stop(1.0, Expression.color(Color.parseColor(mapPalette.mileageHigh)))
                )
                style.addLayer(
                    CircleLayer(ROAD_LAYER_ID, ROAD_SOURCE_ID).apply {
                        setProperties(
                            circleColor(colour),
                            circleRadius(5.0f),
                            circleOpacity(0.85f),
                            visibility(if (_roadMileageVisible.value) Property.VISIBLE else Property.NONE)
                        )
                    }
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error setting up road mileage layer: ${e.message}")
        }
    }

    private fun onMapTapped(latLng: LatLng): Boolean {
        if (!_roadMileageVisible.value) return false
        val map = mapboxMap ?: return false
        val screen = map.projection.toScreenLocation(latLng)
        val box = android.graphics.RectF(screen.x - 24f, screen.y - 24f, screen.x + 24f, screen.y + 24f)
        val f = map.queryRenderedFeatures(box, ROAD_LAYER_ID).firstOrNull()
        _selectedRoadCell.value = f?.let {
            RoadCellInfo(
                avgKmpl = it.getNumberProperty("kmpl")?.toDouble() ?: 0.0,
                passes = it.getNumberProperty("passes")?.toInt() ?: 0,
                avgSpeedKmh = it.getNumberProperty("speed")?.toDouble() ?: 0.0
            )
        }
        return f != null
    }

    // ---------------- Vehicle tracking / follow camera ----------------

    /**
     * GPS fix → puck + follow camera.
     * - Deadband: below [JITTER_SPEED_KMH], moves under [JITTER_DISTANCE_M] are ignored and the
     *   (meaningless) GPS bearing is not applied, so a parked car's map stays perfectly still.
     * - Speed is EMA-smoothed before mapping to zoom, so zoom glides instead of stepping.
     * - Camera and puck animate linearly over the smoothed GPS interval (300–1200 ms), so each ease
 *   ends as the next fix lands: continuous motion with no mid-ease restarts.
     * - Only the pure region lookup runs per fix; disk work happens only when the region changes.
     */
    fun updateVehiclePosition(lat: Double, lon: Double, bearing: Float, speedKmh: Float) {
        // LatLng throws on NaN / out-of-range latitude, and (0,0) is the "no fix" sentinel some
        // head-unit GNSS stacks emit: drop such fixes instead of crashing or jumping to null island.
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return
        if (lat == 0.0 && lon == 0.0) return
        // A NaN speed would poison the EMA (and the auto-zoom it drives) for the rest of the session.
        @Suppress("NAME_SHADOWING")
        val speedKmh = if (speedKmh.isFinite()) speedKmh else 0f
        @Suppress("NAME_SHADOWING")
        val bearing = if (bearing.isFinite()) bearing else lastBearing.toFloat()

        val now = SystemClock.elapsedRealtime()
        val dtMs = if (lastFixElapsedMs == 0L) 1_000L else (now - lastFixElapsedMs).coerceIn(100L, 5_000L)
        lastFixElapsedMs = now
        fixIntervalMs += FIX_INTERVAL_ALPHA * (dtMs.coerceAtMost(MAX_EASE_MS) - fixIntervalMs)

        val newLocation = LatLng(lat, lon)
        // First fix, or a jump of kilometres (GNSS re-anchor after a bad cached / network fix):
        // place camera and puck instantly instead of panning across the map, which is what made
        // the map jitter and fly around on load.
        val snap = !hasFix || lastLocation.distanceTo(newLocation) > SNAP_DISTANCE_M

        val speed = speedKmh.coerceAtLeast(0f).toDouble()
        smoothedSpeedKmh = if (snap) speed else smoothedSpeedKmh + emaAlpha(dtMs) * (speed - smoothedSpeedKmh)
        tiltSpeedKmh = if (snap) speed else tiltSpeedKmh + emaAlpha(dtMs, TILT_SPEED_TAU_MS) * (speed - tiltSpeedKmh)
        val tiltChanged = updateAutoTilt(now, snap, tiltSpeedKmh)
        staleFixJob?.cancel()
        staleFixJob = coroutineScope.launch {
            delay(STALE_FIX_MS)
            onFixesStalled()
        }

        val jitter = !snap && speedKmh < JITTER_SPEED_KMH && lastLocation.distanceTo(newLocation) < JITTER_DISTANCE_M
        if (!jitter) {
            lastLocation = newLocation
            if (speedKmh >= JITTER_SPEED_KMH || !hasFix) lastBearing = bearing.toDouble()
        }
        hasFix = true

        OfflineMapManager.updateGpsLocation(lat, lon)
        val regionId = OfflineMapManager.getRegionForLocation(lat, lon)?.id
        if (regionId != lastRegionId) {
            lastRegionId = regionId
            Log.i(TAG, "GPS region changed to $regionId; re-resolving map file")
            reloadStyleIfMapChanged()
        }

        val map = mapboxMap ?: return
        if (!renderingActive) {
            // Hidden under an overlay (GL thread paused): don't run 60 Hz puck/camera animations
            // against a paused map, they only steal main-thread time from the visible screen.
            puckAnimator?.cancel()
            needsResync = true
            return
        }
        if (snap) {
            puckAnimator?.cancel()
            setPuck(lastLocation)
            if (_isFollowing.value) {
                val zoom = currentFollowZoom()
                lastEasedZoom = zoom
                map.moveCamera(CameraUpdateFactory.newCameraPosition(followCameraPosition(zoom)))
            }
            return
        }

        val easeMs = (fixIntervalMs * EASE_STRETCH).toLong().coerceIn(MIN_EASE_MS, MAX_EASE_MS)
        if (!jitter) animatePuckTo(lastLocation, easeMs)
        if (!_isFollowing.value) return

        val zoom = currentFollowZoom()
        val zoomChanged = abs(zoom - lastEasedZoom) > ZOOM_EPSILON
        if (jitter && !zoomChanged && !tiltChanged) return
        lastEasedZoom = zoom
        val duration = if (tiltChanged) maxOf(easeMs, TILT_EASE_MS) else easeMs
        map.easeCamera(CameraUpdateFactory.newCameraPosition(followCameraPosition(zoom)), duration.toInt(), false)
    }

    /** Fixes stopped arriving: the car is parked. Drop to flat 2D right away. */
    private fun onFixesStalled() {
        smoothedSpeedKmh = 0.0
        tiltSpeedKmh = 0.0
        tiltCandidateSinceMs = 0L
        if (!autoTiltOn) return
        autoTiltOn = false
        Log.d(TAG, "Auto 3D off (no fix for ${STALE_FIX_MS} ms)")
        val map = mapboxMap ?: return
        if (!_isFollowing.value) return
        val zoom = currentFollowZoom()
        lastEasedZoom = zoom
        map.easeCamera(CameraUpdateFactory.newCameraPosition(followCameraPosition(zoom)), TILT_EASE_MS.toInt(), false)
    }

    /**
     * Auto 3D: tilt on at/above [AUTO_3D_SPEED_KMH], flat below it, judged on the filtered fix speed
     * (not the slow zoom EMA, so stopping flattens promptly). The new state must persist for
     * [TILT_HOLD_MS] before it applies, so stop-and-go traffic doesn't flicker.
     * @return true when the tilt state flipped on this fix.
     */
    private fun updateAutoTilt(nowMs: Long, immediate: Boolean, speedKmh: Double): Boolean {
        val want = speedKmh >= AUTO_3D_SPEED_KMH
        if (want == autoTiltOn) {
            tiltCandidateSinceMs = 0L
            return false
        }
        if (immediate) {
            autoTiltOn = want
            tiltCandidateSinceMs = 0L
            return true
        }
        if (tiltCandidateSinceMs == 0L) {
            tiltCandidateSinceMs = nowMs
            return false
        }
        if (nowMs - tiltCandidateSinceMs < TILT_HOLD_MS) return false
        autoTiltOn = want
        tiltCandidateSinceMs = 0L
        Log.d(TAG, "Auto 3D ${if (want) "on" else "off"} at ${"%.1f".format(speedKmh)} km/h")
        return true
    }

    private fun currentFollowZoom(): Double = manualZoom ?: autoZoomForSpeed(smoothedSpeedKmh)

    private fun followCameraPosition(zoom: Double): CameraPosition = CameraPosition.Builder()
        .target(lastLocation)
        .zoom(zoom)
        .tilt(if (_is3DTiltActive.value && autoTiltOn) DEFAULT_DRIVING_TILT else 0.0)
        .bearing(if (_isHeadingUp.value) lastBearing else 0.0)
        .build()

    /** Linear puck glide matching the camera ease, so the puck stays pinned under the camera. */
    private fun animatePuckTo(target: LatLng, durationMs: Long) {
        val start = puckPosition
        puckAnimator?.cancel()
        if (start == null || mapboxMap == null) {
            setPuck(target)
            return
        }
        val from = LatLng(start.latitude, start.longitude)
        puckAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = LinearInterpolator()
            var lastFrameMs = 0L
            addUpdateListener { a ->
                val t = a.animatedFraction.toDouble()
                val nowMs = SystemClock.uptimeMillis()
                if (t < 1.0 && nowMs - lastFrameMs < PUCK_FRAME_MS) return@addUpdateListener
                lastFrameMs = nowMs
                setPuck(
                    LatLng(
                        from.latitude + (target.latitude - from.latitude) * t,
                        from.longitude + (target.longitude - from.longitude) * t
                    )
                )
            }
            start()
        }
    }

    private fun setPuck(pos: LatLng) {
        puckPosition = pos
        mapboxMap?.style?.getSourceAs<GeoJsonSource>(VEHICLE_SOURCE_ID)
            ?.setGeoJson(Point.fromLngLat(pos.longitude, pos.latitude))
    }

    private var renderingActive = true
    private var needsResync = false

    /**
     * Called when the map panel is covered/uncovered. While inactive, fixes only update state;
     * on reactivation the puck and camera jump straight to the latest position (no long pan).
     */
    fun setRenderingActive(active: Boolean) {
        if (renderingActive == active) return
        renderingActive = active
        if (!active || !needsResync) return
        needsResync = false
        val map = mapboxMap ?: return
        setPuck(lastLocation)
        if (_isFollowing.value) {
            val zoom = currentFollowZoom()
            lastEasedZoom = zoom
            map.moveCamera(CameraUpdateFactory.newCameraPosition(followCameraPosition(zoom)))
        }
    }

    private fun suspendFollow() {
        if (_isFollowing.value) _isFollowing.value = false
    }

    fun toggle3DTilt() {
        _is3DTiltActive.value = !_is3DTiltActive.value
        recenter()
    }

    fun toggleHeadingMode() {
        _isHeadingUp.value = !_isHeadingUp.value
        recenter()
    }

    /** Resume follow + speed-adaptive auto-zoom and glide back to the vehicle. */
    fun recenter() {
        manualZoom = null
        _isFollowing.value = true
        val map = mapboxMap ?: return
        val zoom = currentFollowZoom()
        lastEasedZoom = zoom
        map.easeCamera(CameraUpdateFactory.newCameraPosition(followCameraPosition(zoom)), RECENTER_EASE_MS.toInt())
    }
}
