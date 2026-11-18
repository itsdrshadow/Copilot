package com.antigravity.copilot.core.map

import android.os.Debug
import android.util.Log
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

/**
 * Keeps MapLibre's native memory inside a safe budget on the 2 GB RK3566 head unit.
 *
 * Polled every couple of seconds while the map is visible:
 * - above [SOFT_LIMIT_MB] native heap: `MapView.onLowMemory()` drops cached (off-screen) tiles;
 * - above [HARD_LIMIT_MB]: the tile cache is switched off, so only visible tiles stay resident;
 * - back below [RESUME_LIMIT_MB]: the cache is switched on again (smooth pan-back returns).
 *
 * Android's own trim-memory callbacks also call `onLowMemory()` (see LiveMapView).
 */
class MapMemoryGuard {

    enum class Action { NONE, TRIM, DISABLE_CACHE, ENABLE_CACHE }

    var mapView: MapView? = null
    var map: MapLibreMap? = null

    var tileCacheEnabled = true
        private set

    private var lastTrimMs = 0L

    fun poll(nowMs: Long) {
        val mapView = mapView ?: return
        val heapMb = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
        val action = decide(heapMb, tileCacheEnabled, nowMs - lastTrimMs)
        when (action) {
            Action.NONE -> Unit
            Action.TRIM -> {
                lastTrimMs = nowMs
                mapView.onLowMemory()
                Log.w(TAG, "Native heap ${heapMb} MB > $SOFT_LIMIT_MB MB: dropped cached tiles")
            }
            Action.DISABLE_CACHE -> {
                lastTrimMs = nowMs
                map?.setTileCacheEnabled(false)
                mapView.onLowMemory()
                tileCacheEnabled = false
                Log.w(TAG, "Native heap ${heapMb} MB > $HARD_LIMIT_MB MB: tile cache off")
            }
            Action.ENABLE_CACHE -> {
                map?.setTileCacheEnabled(true)
                tileCacheEnabled = true
                Log.i(TAG, "Native heap ${heapMb} MB < $RESUME_LIMIT_MB MB: tile cache on")
            }
        }
    }

    fun release() {
        mapView = null
        map = null
    }

    companion object {
        private const val TAG = "MapMemoryGuard"
        const val SOFT_LIMIT_MB = 192L
        const val HARD_LIMIT_MB = 256L
        const val RESUME_LIMIT_MB = 160L
        /** Don't trim more often than this: each trim makes the next pan re-parse tiles. */
        const val TRIM_COOLDOWN_MS = 10_000L
        const val POLL_INTERVAL_MS = 2_000L

        fun decide(nativeHeapMb: Long, cacheEnabled: Boolean, msSinceLastTrim: Long): Action = when {
            cacheEnabled && nativeHeapMb > HARD_LIMIT_MB -> Action.DISABLE_CACHE
            cacheEnabled && nativeHeapMb > SOFT_LIMIT_MB && msSinceLastTrim >= TRIM_COOLDOWN_MS -> Action.TRIM
            !cacheEnabled && nativeHeapMb < RESUME_LIMIT_MB -> Action.ENABLE_CACHE
            else -> Action.NONE
        }
    }
}
