package com.antigravity.copilot.core.logic

/**
 * Pure rules for rendering the map with zero network access.
 *
 * Vector tiles are requested from [LOCAL_TILE_HOST], a reserved `.invalid` host (RFC 2606, never
 * resolvable). Those requests are answered in-process from the local PMTiles file by
 * `MapNetworkGuard`; every other request is blocked. Style, sprites and glyphs are `asset://`.
 */
object MapOfflinePolicy {

    const val LOCAL_TILE_HOST = "tiles.copilot.invalid"
    const val MAP_ID_PLACEHOLDER = "{map_id}"
    const val LOCAL_TILE_TEMPLATE = "http://$LOCAL_TILE_HOST/$MAP_ID_PLACEHOLDER/{z}/{x}/{y}.pbf"

    private val LOCAL_PREFIXES = listOf("asset://", "file://", "http://$LOCAL_TILE_HOST/")
    private val ANY_URL = Regex("""(?i)\b(?:https?|wss?)://[^\s"']+""")
    private val TILE_PATH = Regex("""^http://${Regex.escape(LOCAL_TILE_HOST)}/([^/]+)/(\d+)/(\d+)/(\d+)\.pbf$""")

    data class LocalTile(val mapId: String, val z: Int, val x: Int, val y: Int)

    fun isLocalUrl(url: String): Boolean = LOCAL_PREFIXES.any { url.startsWith(it, ignoreCase = true) }

    /** Every network URL in a style JSON that is NOT served in-process (should be empty). */
    fun remoteUrls(styleJson: String): List<String> =
        ANY_URL.findAll(styleJson).map { it.value }.filterNot { isLocalUrl(it) }.toList()

    /** Bind the installed map (identified by [mapId], which changes when the file changes) into the style. */
    fun bindStyle(template: String, mapId: String): String = template.replace(MAP_ID_PLACEHOLDER, mapId)

    /** Stable id for a map file version so MapLibre's tile cache never mixes two files. */
    fun mapId(sizeBytes: Long, lastModifiedMs: Long): String =
        java.lang.Long.toHexString(sizeBytes) + "-" + java.lang.Long.toHexString(lastModifiedMs)

    fun parseLocalTileUrl(url: String): LocalTile? {
        val m = TILE_PATH.matchEntire(url) ?: return null
        return LocalTile(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[4].toInt())
    }

    /** PMTiles v3 files start with the ASCII magic "PMTiles" followed by version byte 3. */
    fun isPmtilesHeader(header: ByteArray): Boolean =
        header.size >= 8 && String(header, 0, 7, Charsets.US_ASCII) == "PMTiles" && header[7].toInt() == 3
}
