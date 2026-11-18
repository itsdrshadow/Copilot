package com.antigravity.copilot.core.map

import android.util.Log
import com.antigravity.copilot.core.logic.MapOfflinePolicy
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * The PMTiles archive the maps render. MapLibre opens it natively via [Map.url]
 * (`pmtiles://file:///…`); this only validates the header and remembers which file is active so
 * the live map and the static snapshots share one basemap.
 */
object ActiveMapFile {
    private const val TAG = "ActiveMapFile"

    /** [url] goes into the style; [version] changes when the file at that path is replaced. */
    data class Map(val url: String, val version: String)

    @Volatile private var current: Map? = null

    fun current(): Map? = current

    /** Switch to [file] (disk I/O: call off the main thread). Null or unusable clears the map. */
    @Synchronized
    fun set(file: File?): Map? {
        if (file == null || !file.isFile || file.length() == 0L) {
            current = null
            return null
        }
        val version = MapOfflinePolicy.mapId(file.length(), file.lastModified())
        val url = MapOfflinePolicy.pmtilesUrl(file.absolutePath)
        current?.let { if (it.url == url && it.version == version) return it }
        val problem = try { headerProblem(file) } catch (e: IOException) { e.message ?: "unreadable" }
        if (problem != null) {
            Log.e(TAG, "Cannot use map file ${file.name}: $problem")
            current = null
            return null
        }
        return Map(url, version).also { current = it }
    }

    /** Header check shared with map import; null when MapLibre can render [file]. */
    fun headerProblem(file: File): String? {
        val header = ByteArray(MapOfflinePolicy.PMTILES_HEADER_SIZE)
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < header.size) return "file too small to be a PMTiles archive"
            raf.readFully(header)
        }
        return MapOfflinePolicy.pmtilesHeaderProblem(header)
    }
}
