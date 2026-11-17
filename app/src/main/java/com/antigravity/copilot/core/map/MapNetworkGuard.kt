package com.antigravity.copilot.core.map

import android.util.Log
import com.antigravity.copilot.core.logic.MapOfflinePolicy
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.module.http.HttpRequestUtil
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.io.IOException

/**
 * The map's only "network": an in-process tile server plus a hard block on everything else.
 *
 * - Vector tiles requested from [MapOfflinePolicy.LOCAL_TILE_HOST] are answered from the local
 *   PMTiles file ([PmTilesReader]) inside an OkHttp interceptor — no socket is ever opened, so it
 *   works in airplane mode on a fresh launch.
 * - Any other map request is refused. Debug builds fail loudly (crash with the URL) so an
 *   accidental remote reference is caught immediately; release builds log and fail the request.
 * - MapLibre is told it is "connected" so it keeps asking (our interceptor) for tiles even when
 *   Android reports no connectivity.
 *
 * The in-app map *download* (MapDownloadWorker) uses its own OkHttp client and is unaffected.
 */
object MapNetworkGuard {
    private const val TAG = "MapNetworkGuard"
    private val PBF = "application/x-protobuf".toMediaType()

    @Volatile private var reader: PmTilesReader? = null
    @Volatile private var readerPath: String? = null
    @Volatile private var currentMapId: String? = null

    @Volatile
    var blockedRequestCount: Int = 0
        private set

    /** Open (or switch to) [file]; returns the map id to bind into the style, or null if unusable. */
    @Synchronized
    fun setMapFile(file: File?): String? {
        if (file != null && file.absolutePath == readerPath && reader != null &&
            currentMapId == MapOfflinePolicy.mapId(file.length(), file.lastModified())
        ) return currentMapId
        try { reader?.close() } catch (_: Exception) {}
        reader = null; readerPath = null; currentMapId = null
        if (file == null || !file.isFile || file.length() == 0L) return null
        return try {
            reader = PmTilesReader(file)
            readerPath = file.absolutePath
            currentMapId = MapOfflinePolicy.mapId(file.length(), file.lastModified())
            currentMapId
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open map file ${file.name}: ${e.message}")
            null
        }
    }

    fun install(failLoudly: Boolean) {
        try {
            Mapbox.setConnected(true)
            val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain -> intercept(chain, failLoudly) }).build()
            HttpRequestUtil.setOkHttpClient(client)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install map network guard: ${e.message}", e)
        }
    }

    private fun intercept(chain: Interceptor.Chain, failLoudly: Boolean): Response {
        val request = chain.request()
        val url = request.url.toString()
        val tile = MapOfflinePolicy.parseLocalTileUrl(url)
        if (tile != null) {
            val bytes = try {
                if (tile.mapId == currentMapId) reader?.getTile(tile.z, tile.x, tile.y) else null
            } catch (e: Exception) {
                Log.w(TAG, "Tile read failed $url: ${e.message}"); null
            }
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(if (bytes != null) 200 else 204)
                .message(if (bytes != null) "OK" else "No Content")
                .body((bytes ?: ByteArray(0)).toResponseBody(PBF))
                .build()
        }
        blockedRequestCount++
        Log.e(TAG, "BLOCKED map network request (map must be 100% offline): $url")
        if (failLoudly) throw IllegalStateException("Map attempted a network request: $url")
        throw IOException("Map network access disabled: $url")
    }
}
