package com.antigravity.copilot.feature.maps

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import com.antigravity.copilot.core.map.MapNetworkGuard
import com.antigravity.copilot.core.map.OfflineMapManager
import com.antigravity.copilot.core.map.OfflineStyle
import com.antigravity.copilot.core.map.StaticMapCamera
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshot
import org.maplibre.android.snapshotter.MapSnapshotter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.pow

/** A rendered basemap image together with the exact camera it was rendered at. */
class StaticMapSnapshot(val camera: StaticMapCamera, val image: ImageBitmap)

private const val TAG = "StaticMapSnapshot"
private const val SNAPSHOT_DEBOUNCE_MS = 150L

/**
 * Offline basemap for preview maps (Trips playback, Geofence editor) rendered once with MapLibre's
 * off-screen [MapSnapshotter] instead of a second live MapView: no extra GL surface competing with
 * the dashboard map, no style-load race, and the overlay on top is plain Compose drawing.
 *
 * Returns the latest finished snapshot (possibly for an older camera — draw it with
 * [drawSnapshot], which re-projects it) or null while none is available / no map is installed.
 */
@Composable
fun rememberStaticMapSnapshot(camera: StaticMapCamera?): StaticMapSnapshot? {
    val context = LocalContext.current.applicationContext
    var result by remember { mutableStateOf<StaticMapSnapshot?>(null) }
    val palette = com.antigravity.copilot.core.designsystem.CockpitTheme.map
    LaunchedEffect(camera, palette) {
        val cam = camera ?: return@LaunchedEffect
        delay(SNAPSHOT_DEBOUNCE_MS) // taps / radius chips / resizes settle first
        val json = withContext(Dispatchers.IO) {
            OfflineStyle.build(context, resolveMapId(context, cam), palette)
        } ?: return@LaunchedEffect
        val bitmap = takeSnapshot(context, cam, json) ?: return@LaunchedEffect
        result = StaticMapSnapshot(cam, bitmap.asImageBitmap())
    }
    return result
}

/**
 * Map id to render: the file the live map already serves (never switch it under the dashboard
 * map); only when nothing is open yet, open the best file for this location.
 */
private fun resolveMapId(context: Context, cam: StaticMapCamera): String? =
    MapNetworkGuard.activeMapId() ?: try {
        OfflineMapManager.init(context)
        MapNetworkGuard.setMapFile(OfflineMapManager.getBestMapFileForLocation(context, cam.centerLat, cam.centerLon))
    } catch (e: Exception) {
        Log.w(TAG, "No offline map for snapshot: ${e.message}")
        null
    }

private suspend fun takeSnapshot(context: Context, cam: StaticMapCamera, styleJson: String): Bitmap? =
    suspendCancellableCoroutine { cont ->
        val options = MapSnapshotter.Options(cam.widthPx, cam.heightPx)
            .withStyleBuilder(Style.Builder().fromJson(styleJson))
            .withCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(cam.centerLat, cam.centerLon))
                    .zoom(cam.zoom)
                    .tilt(0.0)
                    .bearing(0.0)
                    .build()
            )
            .withPixelRatio(1f)
            .withLogo(false)
        val snapshotter = try {
            MapSnapshotter(context, options)
        } catch (e: Exception) {
            Log.w(TAG, "Snapshotter unavailable: ${e.message}")
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        snapshotter.start(
            object : MapSnapshotter.SnapshotReadyCallback {
                override fun onSnapshotReady(snapshot: MapSnapshot) {
                    if (cont.isActive) cont.resume(snapshot.bitmap)
                }
            },
            object : MapSnapshotter.ErrorHandler {
                override fun onError(error: String) {
                    Log.w(TAG, "Snapshot failed: $error")
                    if (cont.isActive) cont.resume(null)
                }
            }
        )
        // The snapshotter is main-thread only.
        cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { snapshotter.cancel() } }
    }

/**
 * Draws [snapshot] aligned to [camera]: when the snapshot was taken at a different camera (a new
 * one is still rendering) it is translated/scaled so the basemap stays under the overlay.
 */
fun DrawScope.drawSnapshot(snapshot: StaticMapSnapshot, camera: StaticMapCamera) {
    val s = snapshot.camera
    val (tlLat, tlLon) = s.unproject(0f, 0f)
    val topLeft = camera.project(tlLat, tlLon)
    val factor = 2.0.pow(camera.zoom - s.zoom).toFloat()
    translate(topLeft[0], topLeft[1]) {
        scale(factor, factor, pivot = Offset.Zero) {
            drawImage(snapshot.image)
        }
    }
}
