package com.antigravity.copilot.core.map

import android.util.Log
import com.antigravity.copilot.core.logic.MapOfflinePolicy
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import okhttp3.Dispatcher
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

    /**
     * Hand gzipped tiles to MapLibre untouched with `Content-Encoding: gzip`.
     * Off by default: OkHttp's transparent gunzip only runs in its BridgeInterceptor, which our
     * application interceptor short-circuits, and MapLibre Android passes response bytes
     * straight to native MVT parsing. An emulator trial with this on drew no roads, but that run
     * was confounded by an out-of-region GPS fix — verify on a head unit before enabling.
     * Meanwhile tiles stay gzipped in the RAM cache and are inflated per request (~<1 ms/tile).
     */
    private const val GZIP_PASS_THROUGH = false

    @Volatile private var reader: PmTilesReader? = null
    @Volatile private var readerPath: String? = null
    @Volatile private var currentMapId: String? = null

    @Volatile
    var blockedRequestCount: Int = 0
        private set

    /** Id of the map file currently served, or null if none is open. */
    fun activeMapId(): String? = currentMapId

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
            reader = PmTilesReader(file, PmTilesReader.recommendedTileCacheBytes())
            readerPath = file.absolutePath
            currentMapId = MapOfflinePolicy.mapId(file.length(), file.lastModified())
            currentMapId
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open map file ${file.name}: ${e.message}")
            null
        }
    }

    /** Drop the RAM tile cache under memory pressure (tiles are re-read from flash on demand). */
    fun onTrimMemory(level: Int) {
        // Foreground pressure (RUNNING_LOW/CRITICAL) or being a background kill candidate;
        // UI_HIDDEN alone (user switched to another app briefly) keeps the cache.
        if (level != android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN &&
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
        ) {
            reader?.tileCache?.clear()
        }
    }

    fun install(failLoudly: Boolean) {
        try {
            MapLibre.setConnected(true)
            // MapLibre fans out many tile requests at once; OkHttp's default of 5 per host would
            // serialise them behind our single local host. Reads are lock-free (positional
            // FileChannel + RAM cache), so let the dispatcher's worker pool run them in parallel.
            val dispatcher = Dispatcher().apply {
                maxRequests = 32
                maxRequestsPerHost = 16
            }
            val client = OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .addInterceptor(Interceptor { chain -> intercept(chain, failLoudly) })
                .build()
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
            val r = reader
            val raw = try {
                if (tile.mapId == currentMapId) r?.getTileRaw(tile.z, tile.x, tile.y) else null
            } catch (e: Exception) {
                Log.w(TAG, "Tile read failed $url: ${e.message}"); null
            }
            val builder = Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(if (raw != null) 200 else 204)
                .message(if (raw != null) "OK" else "No Content")
            val body = when {
                raw == null -> ByteArray(0)
                GZIP_PASS_THROUGH && PmTilesReader.isGzip(raw) -> {
                    builder.header("Content-Encoding", "gzip")
                    raw
                }
                else -> try {
                    PmTilesReader.decompress(raw, r?.header?.tileCompression ?: PmTilesReader.COMPRESSION_NONE)
                } catch (e: Exception) {
                    Log.w(TAG, "Tile decode failed $url: ${e.message}"); ByteArray(0)
                }
            }
            return builder.body(body.toResponseBody(PBF)).build()
        }
        blockedRequestCount++
        Log.e(TAG, "BLOCKED map network request (map must be 100% offline): $url")
        if (failLoudly) throw IllegalStateException("Map attempted a network request: $url")
        throw IOException("Map network access disabled: $url")
    }
}
