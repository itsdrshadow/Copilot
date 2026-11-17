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
        private const val ROUTE_SOURCE_ID = "copilot_route_source"
        private const val ROUTE_LAYER_CASING_ID = "copilot_route_layer_casing"
        private const val ROUTE_LAYER_LINE_ID = "copilot_route_layer_line"

        private const val DEFAULT_DRIVING_TILT = 45.0
        private const val DEFAULT_DRIVING_ZOOM = 16.5
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

    private val _activeInstruction = MutableStateFlow<String?>(null)
    val activeInstruction: StateFlow<String?> = _activeInstruction.asStateFlow()

    private val _routeDistanceMeters = MutableStateFlow<Double?>(null)
    val routeDistanceMeters: StateFlow<Double?> = _routeDistanceMeters.asStateFlow()

    private var lastLocation = LatLng(28.6139, 77.2090)
    private var lastBearing = 0.0

    /**
     * Attaches MapLibre map and binds local vector MBTiles to bundled offline style.json.
     */
    fun attachMap(map: MapboxMap, onReady: () -> Unit = {}) {
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
     * Loads the bundled offline style.json and dynamically binds the best regional/national .mbtiles file URI.
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

            val resolvedJson = if (hasMap && mbtilesFile != null) {
                // Dynamically bind local mbtiles file path into the vector tile source
                styleTemplate.replace("mbtiles://{mbtiles_path}", "mbtiles://${mbtilesFile.absolutePath}")
            } else {
                styleTemplate.replace("mbtiles://{mbtiles_path}", "")
            }

            map.setStyle(Style.Builder().fromJson(resolvedJson)) { style ->
                setupRouteLayers(style)
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

    private fun setupRouteLayers(style: Style) {
        try {
            if (style.getSource(ROUTE_SOURCE_ID) == null) {
                val routeSource = GeoJsonSource(ROUTE_SOURCE_ID, FeatureCollection.fromFeatures(arrayOf()))
                style.addSource(routeSource)
            }

            if (style.getLayer(ROUTE_LAYER_CASING_ID) == null) {
                val casingLayer = LineLayer(ROUTE_LAYER_CASING_ID, ROUTE_SOURCE_ID).apply {
                    setProperties(
                        lineColor(Color.parseColor("#00334D")),
                        lineWidth(9.0f),
                        lineCap(Property.LINE_CAP_ROUND),
                        lineJoin(Property.LINE_JOIN_ROUND)
                    )
                }
                style.addLayer(casingLayer)
            }

            if (style.getLayer(ROUTE_LAYER_LINE_ID) == null) {
                val lineLayer = LineLayer(ROUTE_LAYER_LINE_ID, ROUTE_SOURCE_ID).apply {
                    setProperties(
                        lineColor(Color.parseColor("#00E5FF")), // Cockpit Cyan
                        lineWidth(6.0f),
                        lineCap(Property.LINE_CAP_ROUND),
                        lineJoin(Property.LINE_JOIN_ROUND)
                    )
                }
                style.addLayer(lineLayer)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error setting up route layers: ${e.message}")
        }
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
        val currentTilt = if (_is3DTiltActive.value) DEFAULT_DRIVING_TILT else 0.0
        val targetBearing = if (_isHeadingUp.value) bearing.toDouble() else 0.0

        val cameraPos = CameraPosition.Builder()
            .target(lastLocation)
            .zoom(DEFAULT_DRIVING_ZOOM)
            .tilt(currentTilt)
            .bearing(targetBearing)
            .build()

        map.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPos), 300)
    }

    /**
     * Calculates an offline route using the pure-JVM GraphHopper routing engine
     * and renders the route polyline on the MapLibre map.
     */
    fun routeToDestination(destLat: Double, destLon: Double) {
        coroutineScope.launch {
            _activeInstruction.value = "Calculating offline route..."
            val result = OfflineRoutingEngine.calculateRoute(
                startLat = lastLocation.latitude,
                startLon = lastLocation.longitude,
                destLat = destLat,
                destLon = destLon
            )

            if (result.isSuccess && result.points.isNotEmpty()) {
                _routeDistanceMeters.value = result.distanceMeters
                _activeInstruction.value = result.instructions.firstOrNull() ?: "Follow route"
                renderRoutePolyline(result.points)
            } else {
                _activeInstruction.value = result.errorMessage ?: "Route not available"
            }
        }
    }

    private fun renderRoutePolyline(points: List<Pair<Double, Double>>) {
        val map = mapboxMap ?: return
        val style = map.style ?: return

        try {
            val coordinates = points.map { Point.fromLngLat(it.second, it.first) }
            val lineString = LineString.fromLngLats(coordinates)
            val feature = Feature.fromGeometry(lineString)

            val source = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID)
            source?.setGeoJson(FeatureCollection.fromFeature(feature))
            Log.i(TAG, "Rendered offline route polyline with ${points.size} points")
        } catch (e: Exception) {
            Log.e(TAG, "Error rendering route polyline: ${e.message}", e)
        }
    }

    fun clearRoute() {
        _activeInstruction.value = null
        _routeDistanceMeters.value = null
        val map = mapboxMap ?: return
        val style = map.style ?: return
        try {
            val source = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID)
            source?.setGeoJson(FeatureCollection.fromFeatures(arrayOf()))
        } catch (_: Exception) {}
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
            .zoom(DEFAULT_DRIVING_ZOOM)
            .tilt(currentTilt)
            .bearing(targetBearing)
            .build()

        map.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPos), 400)
    }
}
