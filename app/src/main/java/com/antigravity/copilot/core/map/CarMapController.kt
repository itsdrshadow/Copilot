package com.antigravity.copilot.core.map

import android.content.Context
import android.graphics.Color
import android.util.Log
import com.antigravity.copilot.core.designsystem.colors.Cockpit_Cyan
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
import java.io.File

/**
 * CarMapController:
 * High-performance controller for MapLibre Native on automotive Android 11 head units.
 * Supports GPS-based auto-detection of 5 regional zones and dynamic local MBTiles binding.
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
        private const val DEFAULT_DRIVING_ZOOM = 16.5
        private const val ZOOM_STEP = 1.0
    }

    private var mapboxMap: MapboxMap? = null
    private var isStyleLoaded = false
    private var currentLoadedMbtilesPath: String? = null

    private val _hasMapInstalled = MutableStateFlow(false)
    val hasMapInstalled: StateFlow<Boolean> = _hasMapInstalled.asStateFlow()

    private val _activeRegionTitle = MutableStateFlow<String?>(null)
    val activeRegionTitle: StateFlow<String?> = _activeRegionTitle.asStateFlow()

    private val _is3DTiltActive = MutableStateFlow(true)
    val is3DTiltActive: StateFlow<Boolean> = _is3DTiltActive.asStateFlow()

    private val _isHeadingUp = MutableStateFlow(true)
    val isHeadingUp: StateFlow<Boolean> = _isHeadingUp.asStateFlow()

    private var lastLocation = LatLng(28.6139, 77.2090)
    private var lastBearing = 0.0

    /** Zoom the follow camera uses; changed by the +/- buttons and by pinch gestures. */
    private var followZoom = DEFAULT_DRIVING_ZOOM
    private var userGestureInProgress = false

    /**
     * Attaches MapLibre map and binds local vector MBTiles to bundled offline style.json.
     */
    fun attachMap(map: MapboxMap, onReady: () -> Unit = {}) {
        map.addOnMapClickListener { latLng -> onMapTapped(latLng) }
        map.addOnCameraMoveStartedListener { reason ->
            if (reason == MapboxMap.OnCameraMoveStartedListener.REASON_API_GESTURE) userGestureInProgress = true
        }
        map.addOnCameraIdleListener {
            if (userGestureInProgress) {
                userGestureInProgress = false
                followZoom = map.cameraPosition.zoom
            }
        }
        this.mapboxMap = map
        loadLocalOfflineStyle {
            onReady()
        }
    }

    fun detachMap() {
        this.mapboxMap = null
        this.isStyleLoaded = false
        this.currentLoadedMbtilesPath = null
    }

    /**
     * Loads the bundled offline style.json and binds the installed local PMTiles map (served in-process).
     */
    fun loadLocalOfflineStyle(
        lat: Double = lastLocation.latitude,
        lon: Double = lastLocation.longitude,
        onCompleted: () -> Unit = {}
    ) {
        val map = mapboxMap ?: return
        val mbtilesFile = OfflineMapManager.getBestMapFileForLocation(context, lat, lon)
        val hasMap = mbtilesFile != null && mbtilesFile.exists() && mbtilesFile.length() > 0
        _hasMapInstalled.value = hasMap

        val detectedRegion = OfflineMapManager.getRegionForLocation(lat, lon)
        _activeRegionTitle.value = detectedRegion?.title

        val targetPath = mbtilesFile?.absolutePath
        if (isStyleLoaded && targetPath == currentLoadedMbtilesPath) {
            onCompleted()
            return
        }

        try {
            val styleTemplate = context.assets.open("offline_style/style.json")
                .bufferedReader()
                .use { it.readText() }

            // Local PMTiles only; with no (valid) map installed render the background-only style
            // so nothing ever points at a missing or remote source.
            // Tiles are served in-process from the local PMTiles file (MapNetworkGuard).
            val mapId = if (hasMap) MapNetworkGuard.setMapFile(mbtilesFile) else MapNetworkGuard.setMapFile(null)
            val resolvedJson = if (mapId != null) {
                com.antigravity.copilot.core.logic.MapOfflinePolicy.bindStyle(styleTemplate, mapId)
            } else {
                backgroundOnlyStyle(styleTemplate)
            }
            val remote = com.antigravity.copilot.core.logic.MapOfflinePolicy.remoteUrls(resolvedJson)
            if (remote.isNotEmpty()) Log.e(TAG, "Style contains remote URLs (will be blocked): $remote")

            map.setStyle(Style.Builder().fromJson(resolvedJson)) { style ->
                setupRoadMileageLayer(style)
                setupTrailLayers(style)
                setupVehicleLayer(style)
                isStyleLoaded = true
                currentLoadedMbtilesPath = targetPath
                Log.i(TAG, "MapLibre style loaded. Active pack: ${mbtilesFile?.name ?: "None"}, Region: ${detectedRegion?.title}")
                onCompleted()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading offline style: ${e.message}", e)
            onCompleted()
        }
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
            if (style.getSource(VEHICLE_SOURCE_ID) == null) {
                style.addSource(GeoJsonSource(VEHICLE_SOURCE_ID, Point.fromLngLat(lastLocation.longitude, lastLocation.latitude)))
            }
            if (style.getLayer(VEHICLE_LAYER_ID) == null) {
                style.addLayer(
                    CircleLayer(VEHICLE_LAYER_ID, VEHICLE_SOURCE_ID).apply {
                        setProperties(
                            circleRadius(8.0f),
                            circleColor(Color.parseColor("#00E5FF")),
                            circleStrokeColor(Color.WHITE),
                            circleStrokeWidth(3.0f)
                        )
                    }
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error setting up vehicle layer: ${e.message}")
        }
    }

    /**
     * Live trail polyline: grid pass counts (~15 m) colour each run, Douglas–Peucker simplified.
     * Heavy work runs off the main thread and is throttled.
     */
    fun updateTrail(points: List<com.antigravity.copilot.core.model.TripPoint>) {
        val now = System.currentTimeMillis()
        if (points.size == lastTrailSize) return
        if (points.size > 2 && lastTrailSize > 2 && now - lastTrailComputeMs < TRAIL_MIN_INTERVAL_MS) return
        lastTrailComputeMs = now
        lastTrailSize = points.size
        val snapshot = points.map { com.antigravity.copilot.core.logic.LatLon(it.latitude, it.longitude) }
        trailJob?.cancel()
        trailJob = coroutineScope.launch(Dispatchers.Default) {
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

    /**
     * Smooth 60fps GPS location puck tracking with bearing rotation, 45° driving tilt,
     * and automatic regional map file switching when crossing zones.
     */
    fun updateVehiclePosition(lat: Double, lon: Double, bearing: Float, speedKmh: Float) {
        lastLocation = LatLng(lat, lon)
        lastBearing = bearing.toDouble()

        // Inform OfflineMapManager of location to update detected GPS region
        OfflineMapManager.updateGpsLocation(lat, lon)

        // Check if regional map file should switch based on new GPS location
        val bestMap = OfflineMapManager.getBestMapFileForLocation(context, lat, lon)
        if (bestMap != null && bestMap.absolutePath != currentLoadedMbtilesPath) {
            Log.i(TAG, "Auto-switching map file to match GPS region: ${bestMap.name}")
            loadLocalOfflineStyle(lat, lon)
        }

        val map = mapboxMap ?: return
        map.style?.getSourceAs<GeoJsonSource>(VEHICLE_SOURCE_ID)?.setGeoJson(Point.fromLngLat(lon, lat))
        val currentTilt = if (_is3DTiltActive.value) DEFAULT_DRIVING_TILT else 0.0
        val targetBearing = if (_isHeadingUp.value) bearing.toDouble() else 0.0

        val cameraPos = CameraPosition.Builder()
            .target(lastLocation)
            .zoom(followZoom)
            .tilt(currentTilt)
            .bearing(targetBearing)
            .build()

        map.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPos), 300)
    }

    fun toggle3DTilt() {
        _is3DTiltActive.value = !_is3DTiltActive.value
        recenter()
    }

    fun toggleHeadingMode() {
        _isHeadingUp.value = !_isHeadingUp.value
        recenter()
    }

    fun recenter() {
        val map = mapboxMap ?: return
        val currentTilt = if (_is3DTiltActive.value) DEFAULT_DRIVING_TILT else 0.0
        val targetBearing = if (_isHeadingUp.value) lastBearing else 0.0

        val cameraPos = CameraPosition.Builder()
            .target(lastLocation)
            .zoom(followZoom)
            .tilt(currentTilt)
            .bearing(targetBearing)
            .build()

        map.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPos), 400)
    }
    fun zoomIn() = zoomBy(ZOOM_STEP)

    fun zoomOut() = zoomBy(-ZOOM_STEP)

    private fun zoomBy(delta: Double) {
        val map = mapboxMap ?: return
        followZoom = (map.cameraPosition.zoom + delta).coerceIn(map.minZoomLevel, map.maxZoomLevel)
        map.animateCamera(CameraUpdateFactory.zoomTo(followZoom), 250)
    }
}
