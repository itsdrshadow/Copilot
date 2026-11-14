package com.antigravity.copilot.feature.trips

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.antigravity.copilot.core.database.TripPointEntity
import com.antigravity.copilot.core.map.OfflineMapManager
import com.antigravity.copilot.core.trip.TripStopInfo
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

@Composable
fun SmartTripMapView(
    points: List<TripPointEntity>,
    stops: List<TripStopInfo> = emptyList(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var mapViewInstance by remember { mutableStateOf<MapView?>(null) }
    var routePolyline by remember { mutableStateOf<Polyline?>(null) }
    var startMarker by remember { mutableStateOf<Marker?>(null) }
    var endMarker by remember { mutableStateOf<Marker?>(null) }
    val stopMarkers = remember { mutableStateListOf<Marker>() }

    val cachedGeoPoints = remember(points) {
        points.map { GeoPoint(it.latitude, it.longitude) }
    }

    DisposableEffect(Unit) {
        onDispose {
            mapViewInstance?.onPause()
            mapViewInstance?.onDetach()
            mapViewInstance = null
        }
    }

    LaunchedEffect(points, mapViewInstance) {
        val mv = mapViewInstance ?: return@LaunchedEffect
        if (points.isNotEmpty()) {
            var minLat = Double.MAX_VALUE
            var maxLat = -Double.MAX_VALUE
            var minLon = Double.MAX_VALUE
            var maxLon = -Double.MAX_VALUE

            points.forEach { pt ->
                if (pt.latitude < minLat) minLat = pt.latitude
                if (pt.latitude > maxLat) maxLat = pt.latitude
                if (pt.longitude < minLon) minLon = pt.longitude
                if (pt.longitude > maxLon) maxLon = pt.longitude
            }

            val latSpan = (maxLat - minLat).coerceAtLeast(0.005)
            val lonSpan = (maxLon - minLon).coerceAtLeast(0.005)
            val box = BoundingBox(
                maxLat + latSpan * 0.25,
                maxLon + lonSpan * 0.25,
                minLat - latSpan * 0.25,
                minLon - lonSpan * 0.25
            )

            routePolyline?.setPoints(cachedGeoPoints)

            val startGeo = cachedGeoPoints.firstOrNull() ?: GeoPoint(points.first().latitude, points.first().longitude)
            startMarker?.apply {
                position = startGeo
                title = "Trip Start"
                icon = createFlagDrawable(context, "START", Color.parseColor("#00E676"))
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                setVisible(true)
            }

            val endGeo = cachedGeoPoints.lastOrNull() ?: GeoPoint(points.last().latitude, points.last().longitude)
            endMarker?.apply {
                position = endGeo
                title = "Trip End"
                icon = createFlagDrawable(context, "FINISH", Color.parseColor("#FF5252"))
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                setVisible(true)
            }

            mv.post {
                mv.zoomToBoundingBox(box, false)
                mv.invalidate()
            }
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(OfflineMapManager.SATELLITE_SOURCE)
                    setMultiTouchControls(true)
                    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                    isTilesScaledToDpi = true

                    val polyline = Polyline().apply {
                        outlinePaint.color = Color.parseColor("#00E5FF")
                        outlinePaint.strokeWidth = 9f
                        outlinePaint.strokeCap = Paint.Cap.ROUND
                        outlinePaint.strokeJoin = Paint.Join.ROUND
                        outlinePaint.isAntiAlias = true
                    }
                    routePolyline = polyline
                    overlays.add(polyline)

                    val startM = Marker(this)
                    startMarker = startM
                    overlays.add(startM)

                    val endM = Marker(this)
                    endMarker = endM
                    overlays.add(endM)

                    // Add markers for intermediate stops
                    for (stop in stops) {
                        val sm = Marker(this).apply {
                            position = GeoPoint(stop.latitude, stop.longitude)
                            title = "Stop (${stop.timeFormatted})"
                            icon = createStopPinDrawable(ctx)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        }
                        stopMarkers.add(sm)
                        overlays.add(sm)
                    }

                    mapViewInstance = this
                }
            },
            update = { mv ->
                mv.invalidate()
            }
        )

        // Floating Automotive Map Controls: Re-center, Zoom In, Zoom Out
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = {
                    val mv = mapViewInstance ?: return@IconButton
                    if (points.isNotEmpty()) {
                        var minLat = Double.MAX_VALUE
                        var maxLat = -Double.MAX_VALUE
                        var minLon = Double.MAX_VALUE
                        var maxLon = -Double.MAX_VALUE
                        points.forEach { pt ->
                            if (pt.latitude < minLat) minLat = pt.latitude
                            if (pt.latitude > maxLat) maxLat = pt.latitude
                            if (pt.longitude < minLon) minLon = pt.longitude
                            if (pt.longitude > maxLon) maxLon = pt.longitude
                        }
                        val latSpan = (maxLat - minLat).coerceAtLeast(0.005)
                        val lonSpan = (maxLon - minLon).coerceAtLeast(0.005)
                        val box = BoundingBox(
                            maxLat + latSpan * 0.25,
                            maxLon + lonSpan * 0.25,
                            minLat - latSpan * 0.25,
                            minLon - lonSpan * 0.25
                        )
                        mv.zoomToBoundingBox(box, true)
                    }
                },
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(androidx.compose.ui.graphics.Color(0xFF0F172A).copy(alpha = 0.85f))
                    .border(1.dp, androidx.compose.ui.graphics.Color(0xFF334155), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.GpsFixed,
                    contentDescription = "Fit Route",
                    tint = androidx.compose.ui.graphics.Color(0xFF38BDF8),
                    modifier = Modifier.size(20.dp)
                )
            }

            IconButton(
                onClick = { mapViewInstance?.controller?.zoomIn() },
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(androidx.compose.ui.graphics.Color(0xFF0F172A).copy(alpha = 0.85f))
                    .border(1.dp, androidx.compose.ui.graphics.Color(0xFF334155), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Zoom In",
                    tint = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            IconButton(
                onClick = { mapViewInstance?.controller?.zoomOut() },
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(androidx.compose.ui.graphics.Color(0xFF0F172A).copy(alpha = 0.85f))
                    .border(1.dp, androidx.compose.ui.graphics.Color(0xFF334155), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Remove,
                    contentDescription = "Zoom Out",
                    tint = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

private fun createFlagDrawable(context: Context, text: String, bgColor: Int): Drawable {
    val density = context.resources.displayMetrics.density
    val width = (48 * density).toInt()
    val height = (24 * density).toInt()
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = bgColor
        style = Paint.Style.FILL
    }
    val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
    canvas.drawRoundRect(rect, 6 * density, 6 * density, paint)

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 10 * density
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    val textY = (height / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
    canvas.drawText(text, width / 2f, textY, textPaint)

    return BitmapDrawable(context.resources, bitmap)
}

private fun createStopPinDrawable(context: Context): Drawable {
    val density = context.resources.displayMetrics.density
    val size = (22 * density).toInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF9100")
        style = Paint.Style.FILL
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2, paint)

    val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 4f, innerPaint)

    return BitmapDrawable(context.resources, bitmap)
}
