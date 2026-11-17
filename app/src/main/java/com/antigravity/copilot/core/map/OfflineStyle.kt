package com.antigravity.copilot.core.map

import android.content.Context
import android.util.Log
import com.antigravity.copilot.core.logic.MapOfflinePolicy

/**
 * Builds the offline style JSON (bundled template + the active PMTiles map id). Shared by the live
 * map and the static snapshot maps (Trips, Geofence) so they render the identical basemap.
 */
object OfflineStyle {
    private const val TAG = "OfflineStyle"
    private const val TEMPLATE_ASSET = "offline_style/style.json"

    @Volatile private var templateCache: String? = null

    private fun template(context: Context): String =
        templateCache ?: context.assets.open(TEMPLATE_ASSET).bufferedReader().use { it.readText() }
            .also { templateCache = it }

    /**
     * Style JSON for [mapId]; with no map installed (null) a background-only style, so nothing ever
     * points at a missing or remote source. Returns null if the bundled template cannot be read.
     */
    fun build(context: Context, mapId: String?): String? = try {
        val template = template(context)
        val json = if (mapId != null) MapOfflinePolicy.bindStyle(template, mapId) else backgroundOnly(template)
        val remote = MapOfflinePolicy.remoteUrls(json)
        if (remote.isNotEmpty()) Log.e(TAG, "Style contains remote URLs (will be blocked): $remote")
        json
    } catch (e: Exception) {
        Log.e(TAG, "Error building offline style: ${e.message}", e)
        null
    }

    private fun backgroundOnly(template: String): String = try {
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
}
