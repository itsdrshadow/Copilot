package com.antigravity.copilot.core.map

import android.content.Context
import android.util.Log
import com.antigravity.copilot.core.designsystem.colors.MapPalette
import com.antigravity.copilot.core.designsystem.colors.RaahiNight
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
    fun build(context: Context, mapId: String?, palette: MapPalette = RaahiNight.Map): String? = try {
        val template = template(context)
        val bound = if (mapId != null) MapOfflinePolicy.bindStyle(template, mapId) else backgroundOnly(template)
        val json = applyPalette(bound, palette)
        val remote = MapOfflinePolicy.remoteUrls(json)
        if (remote.isNotEmpty()) Log.e(TAG, "Style contains remote URLs (will be blocked): $remote")
        json
    } catch (e: Exception) {
        Log.e(TAG, "Error building offline style: ${e.message}", e)
        null
    }

    /**
     * Recolours the bundled template with the Raahi basemap palette (by layer id). Only paint
     * colours change; sources, filters, zooms and geometry are untouched.
     */
    fun applyPalette(styleJson: String, p: MapPalette): String = try {
        val json = org.json.JSONObject(styleJson)
        val layers = json.getJSONArray("layers")
        for (i in 0 until layers.length()) {
            val l = layers.getJSONObject(i)
            val paint = l.optJSONObject("paint") ?: continue
            fun set(key: String, value: String) { if (paint.has(key)) paint.put(key, value) }
            when (l.optString("id")) {
                "background" -> set("background-color", p.land)
                "landcover_green", "park", "landuse_green" -> set("fill-color", p.park)
                "water" -> set("fill-color", p.water)
                "waterway" -> set("line-color", p.water)
                "road_minor_casing", "road_major_casing", "road_highway_casing" -> set("line-color", p.roadCasing)
                "road_minor" -> set("line-color", p.roadMinor)
                "road_major" -> set("line-color", p.roadMajor)
                "road_highway" -> set("line-color", p.roadHighway)
                "building_3d" -> set("fill-extrusion-color", p.building)
                "water_name" -> { set("text-color", p.waterLabel); set("text-halo-color", p.labelHalo) }
                "road_name", "poi_key", "place_minor" -> { set("text-color", p.label); set("text-halo-color", p.labelHalo) }
                "place_major" -> { set("text-color", p.labelMajor); set("text-halo-color", p.labelHalo) }
                "road_shield" -> set("text-color", p.labelHalo)
            }
        }
        json.toString()
    } catch (e: Exception) {
        Log.w(TAG, "Palette not applied: ${e.message}")
        styleJson
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
        """{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#0B1220"}}]}"""
    }
}
