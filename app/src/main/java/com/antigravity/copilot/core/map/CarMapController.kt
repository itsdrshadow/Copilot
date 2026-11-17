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
        private const val TRAIL_SOURCE_ID = "copilot_trail_source"
        private const val TRAIL_LAYER_ID = "copilot_trail_layer"
        private const val VEHICLE_SOURCE_ID = "copilot_vehicle_source"
        private const val VEHICLE_LAYER_ID = "copilot_vehicle_layer"
        private const val TRAIL_MIN_INTERVAL_MS = 2_000L
        private const val ROAD_SOURCE_ID = "copilot_road_mileage_source"
        private const val ROAD_LAYER_ID = "copilot_road_mileage_layer"

        private const val DEFAULT_DRIVING_TILT = 45.0

        // Deadband: ignore sub-3 m GPS wander while (nearly) stationary.
        const val JITTER_SPEED_KMH = 3f
        const val JITTER_DISTANCE_M = 3.0

        // Camera transition length tracks the GPS interval (≈1 Hz) so motion never pauses.
        private const val MIN_EASE_MS = 800L
        private const val MAX_EASE_MS = 1_200L
        private const val RECENTER_EASE_MS = 800L

        // Speed EMA time constant and minimum zoom delta worth animating.
        private const val SPEED_EMA_TAU_MS = 2_500.0
        private const val ZOOM_EPSILON = 0.04

        @Volatile private var styleTemplateCache: String? = null

        /**
         * Speed → follow zoom. Piecewise-linear so the camera never steps:
         * 0–5 km/h 17.5 · 5–30 → 16.0 · 30–60 → 14.8 · 60–100 → 13.5 · >100 13.5.
         */
        fun autoZoomForSpeed(speedKmh: Double): Double {
            val v = speedKmh.coerceAtLeast(0.0)
            fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t.coerceIn(0.0, 1.0)
            return when {
                v <= 5.0 -> 17.5
                v <= 30.0 -> lerp(17.5, 16.0, (v - 5.0) / 25.0)
                v <= 60.0 -> lerp(16.0, 14.8, (v - 30.0) / 30.0)
                v <= 100.0 -> lerp(14.8, 13.5, (v - 60.0) / 40.0)
                else -> 13.5
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

    private val _is3DTiltActive = MutableStateFlow(true)
    val is3DTiltActive: StateFlow<Boolean> = _is3DTiltActive.asStateFlow()

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
        map.moveCamera(CameraUpdateFactory.newCameraPosition(followCameraPosition(currentFollowZoom())))
        lastRegionId = OfflineMapManager.getRegionForLocation(lastLocation.latitude, lastLocation.longitude)?.id
        requestStyle(force = true)
    }

    fun detachMap() {
        styleJob?.cancel()
        styleJob = null
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

    /** Backward-compatible alias. */
    fun loadLocalOfflineStyle() = reloadStyleIfMapChanged()

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
                setupRoadMileageLayer(style)
                setupTrailLayers(style)
                setupVehicleLayer(style)
                isStyleLoaded = true
                loadedMapId = resolved.mapId
                Log.i(TAG, "MapLibre style loaded. Active pack: ${resolved.fileName ?: "None"}, Region: ${resolved.regionTitle}")
            }
        }
    }

    private fun buildStyleJson(mapId: String?): String? = try {
        val template = styleTemplateCache ?: context.assets.open("offline_style/style.json")
            .bufferedReader().use { it.readText() }
            .also { styleTemplateCache = it }
        // Local PMTiles only; with no (valid) map installed render the background-only style
        // so nothing ever points at a missing or remote source.
        val resolvedJson = if (mapId != null) {
            com.antigravity.copilot.core.logic.MapOfflinePolicy.bindStyle(template, mapId)
        } else {
            backgroundOnlyStyle(template)
        }
        val remote = com.antigravity.copilot.core.logic.MapOfflinePolicy.remoteUrls(resolvedJson)
        if (remote.isNotEmpty()) Log.e(TAG, "Style contains remote URLs (will be blocked): $remote")
        resolvedJson
    } catch (e: Exception) {
        Log.e(TAG, "Error building offline style: ${e.message}", e)
        null
    }

    // ---------------- Live trail with overlap colouring (Phase 6) ----------------
    @Volatile private var lastTrailCollection: FeatureCollection = FeatureCollection.fromFeatures(arrayOf())
    private var lastTrailComputeMs = 0L
    private var lastTrailSize = -1
    private var trailJob: Job? = null

    private fun setupTrailLayers(style: Style) {
        try {
            if (style.getSource(TRAIL_SOURCE_ID) == null) {
                style.addSource(GeoJsonSource(TRAIL_SOURCE_ID, lastTrailCollection))
            }
            if (style.getLayer(TRAIL_LAYER_ID) == null) {
                // 1 pass blue, 2 orange, 3+ red (PassColor)
                val colour = Expression.step(
                    Expression.get("passes"),
                    Expression.color(Color.parseColor("#2F80ED")),
                    Expression.stop(2, Expression.color(Color.parseColor("#F2994A"))),
                    Expression.stop(3, Expression.color(Color.parseColor("#EB5757")))
                )
                style.addLayer(
                    LineLayer(TRAIL_LAYER_ID, TRAIL_SOURCE_ID).apply {
                        setProperties(
                            lineColor(colour),
                            lineWidth(5.0f),
                            lineOpacity(0.9f),
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
            if (style.getLayer(VEHICLE_LAYER_ID) == null) {
                style.addLayer(
                    CircleLayer(VEHICLE_LAYER_ID, VEHICLE_SOURCE_ID).apply {
                        setProperties(
                            circleRadius(8.0f),
                            circleColor(Color.parseColor("#00E5FF")),
                            circleStrokeColor(Color.WHITE),
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
        if (size > 2 && lastTrailSize > 2 && now - lastTrailComputeMs < TRAIL_MIN_INTERVAL_MS) return
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
                    Expression.stop(0.0, Expression.color(Color.parseColor("#E53935"))),
                    Expression.stop(0.5, Expression.color(Color.parseColor("#FDD835"))),
                    Expression.stop(1.0, Expression.color(Color.parseColor("#43A047")))
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

    private fun backgroundOnlyStyle(template: String): String = try {
        val json = org.json.JSONObject(template)
        json.put("sources", org.json.JSONObject())
        val layers = json.getJSONArray("layers")
        val kept = org.json.JSONArray()
        for (i in 0 until layers.length()) {
            val l = layers.getJSONObject(i)
            if (l.optString("type") == "background") kept.put(l)
        }
        json.put("layers", kept)
        json.toString()
    } catch (e: Exception) {
        """{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#0B0E14"}}]}"""
    }

    // ---------------- Vehicle tracking / follow camera ----------------

    /**
     * GPS fix → puck + follow camera.
     * - Deadband: below [JITTER_SPEED_KMH], moves under [JITTER_DISTANCE_M] are ignored and the
     *   (meaningless) GPS bearing is not applied, so a parked car's map stays perfectly still.
     * - Speed is EMA-smoothed before mapping to zoom, so zoom glides instead of stepping.
     * - Camera and puck animate linearly over the GPS interval (800–1200 ms): continuous motion.
     * - Only the pure region lookup runs per fix; disk work happens only when the region changes.
     */
    fun updateVehiclePosition(lat: Double, lon: Double, bearing: Float, speedKmh: Float) {
        val now = SystemClock.elapsedRealtime()
        val dtMs = if (lastFixElapsedMs == 0L) 1_000L else (now - lastFixElapsedMs).coerceIn(100L, 5_000L)
        lastFixElapsedMs = now

        val speed = speedKmh.coerceAtLeast(0f).toDouble()
        smoothedSpeedKmh = if (!hasFix) speed else smoothedSpeedKmh + emaAlpha(dtMs) * (speed - smoothedSpeedKmh)

        val newLocation = LatLng(lat, lon)
        val jitter = hasFix && speedKmh < JITTER_SPEED_KMH && lastLocation.distanceTo(newLocation) < JITTER_DISTANCE_M
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
        val easeMs = dtMs.coerceIn(MIN_EASE_MS, MAX_EASE_MS)
        if (!jitter) animatePuckTo(lastLocation, easeMs)
        if (!_isFollowing.value) return

        val zoom = currentFollowZoom()
        val zoomChanged = abs(zoom - lastEasedZoom) > ZOOM_EPSILON
        if (jitter && !zoomChanged) return
        lastEasedZoom = zoom
        map.easeCamera(CameraUpdateFactory.newCameraPosition(followCameraPosition(zoom)), easeMs.toInt(), false)
    }

    private fun currentFollowZoom(): Double = manualZoom ?: autoZoomForSpeed(smoothedSpeedKmh)

    private fun followCameraPosition(zoom: Double): CameraPosition = CameraPosition.Builder()
        .target(lastLocation)
        .zoom(zoom)
        .tilt(if (_is3DTiltActive.value) DEFAULT_DRIVING_TILT else 0.0)
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
            addUpdateListener { a ->
                val t = a.animatedFraction.toDouble()
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
