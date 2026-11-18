package com.antigravity.copilot.core.map

import android.util.Log
import org.maplibre.android.module.http.HttpRequestUtil
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * Hard block on map network access. The map is 100% offline: tiles come from the local PMTiles
 * archive via MapLibre's native `pmtiles://file://` source and style/sprites/glyphs from
 * `asset://`, none of which go through HTTP. Any HTTP request MapLibre still makes is therefore
 * a bug: debug builds crash with the URL so it is caught immediately; release builds log it and
 * fail the request.
 *
 * The in-app map *download* (MapDownloadWorker) uses its own OkHttp client and is unaffected.
 */
object MapNetworkGuard {
    private const val TAG = "MapNetworkGuard"

    @Volatile
    var blockedRequestCount: Int = 0
        private set

    fun install(failLoudly: Boolean) {
        try {
            val client = OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain -> block(chain.request().url.toString(), failLoudly) })
                .build()
            HttpRequestUtil.setOkHttpClient(client)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install map network guard: ${e.message}", e)
        }
    }

    private fun block(url: String, failLoudly: Boolean): Nothing {
        blockedRequestCount++
        Log.e(TAG, "BLOCKED map network request (map must be 100% offline): $url")
        if (failLoudly) throw IllegalStateException("Map attempted a network request: $url")
        throw IOException("Map network access disabled: $url")
    }
}
