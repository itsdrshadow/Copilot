package com.antigravity.copilot.feature.maps

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.maplibre.android.maps.MapView

/**
 * Debug-only map performance probe: rendered map FPS (MapLibre frames, not Compose frames),
 * native heap, and style load time. Also logs a line every 5 s under tag [TAG]:
 * `adb logcat -s MapPerf`.
 *
 * FPS reads 0 while the map is idle — MapLibre only renders when something changes.
 */
internal class MapPerfStats {
    var fps by mutableIntStateOf(0)
        private set
    var renderMsAvg by mutableIntStateOf(0)
        private set
    var nativeHeapMb by mutableIntStateOf(0)
        private set
    var peakNativeHeapMb by mutableIntStateOf(0)
        private set
    var styleLoadMs by mutableLongStateOf(-1L)
        private set

    // MapLibre delivers these callbacks on the main thread.
    private var frames = 0
    private var renderMsSum = 0.0
    private var styleStartMs = 0L
    private var ticks = 0
    private var mapView: MapView? = null

    private val frameListener = MapView.OnDidFinishRenderingFrameListener { _, _, renderingMs ->
        frames++
        renderMsSum += renderingMs
    }
    private val styleStartListener = MapView.OnWillStartLoadingMapListener {
        styleStartMs = SystemClock.elapsedRealtime()
    }
    private val styleDoneListener = MapView.OnDidFinishLoadingStyleListener {
        if (styleStartMs > 0) {
            styleLoadMs = SystemClock.elapsedRealtime() - styleStartMs
            Log.i(TAG, "Style loaded in $styleLoadMs ms")
        }
    }

    fun attach(view: MapView) {
        detach()
        mapView = view
        view.addOnDidFinishRenderingFrameListener(frameListener)
        view.addOnWillStartLoadingMapListener(styleStartListener)
        view.addOnDidFinishLoadingStyleListener(styleDoneListener)
    }

    fun detach() {
        mapView?.apply {
            removeOnDidFinishRenderingFrameListener(frameListener)
            removeOnWillStartLoadingMapListener(styleStartListener)
            removeOnDidFinishLoadingStyleListener(styleDoneListener)
        }
        mapView = null
    }

    /** Called once per second. */
    fun tick() {
        fps = frames
        renderMsAvg = if (frames > 0) (renderMsSum / frames).toInt() else 0
        frames = 0
        renderMsSum = 0.0
        nativeHeapMb = (Debug.getNativeHeapAllocatedSize() / MB).toInt()
        if (nativeHeapMb > peakNativeHeapMb) peakNativeHeapMb = nativeHeapMb
        if (++ticks % 5 == 0) {
            Log.d(TAG, "fps=$fps renderMs=$renderMsAvg nativeHeap=${nativeHeapMb}MB peak=${peakNativeHeapMb}MB styleLoad=${styleLoadMs}ms")
        }
    }

    private companion object {
        const val TAG = "MapPerf"
        const val MB = 1024L * 1024L
    }
}

@Composable
internal fun MapPerfOverlay(stats: MapPerfStats, modifier: Modifier = Modifier) {
    LaunchedEffect(stats) {
        while (true) {
            delay(1000)
            stats.tick()
        }
    }
    val style = if (stats.styleLoadMs >= 0) "${stats.styleLoadMs} ms" else "…"
    Text(
        text = "FPS ${stats.fps} · ${stats.renderMsAvg} ms/frame\n" +
            "Native heap ${stats.nativeHeapMb} MB (peak ${stats.peakNativeHeapMb})\n" +
            "Style load $style",
        color = Color(0xFF7CFC9A),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        modifier = modifier
            .background(Color(0xB3000000), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}
