package com.antigravity.copilot.core.logic

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure rules for rendering the map with zero network access.
 *
 * The vector source is MapLibre's native PMTiles protocol pointed at the local archive
 * (`pmtiles://file:///…/india.pmtiles`): MapLibre reads header, directories and tiles straight
 * from flash with range reads. Style, sprites and glyphs are `asset://`. Nothing may be `http(s)`.
 */
object MapOfflinePolicy {

    const val MAP_URL_PLACEHOLDER = "{map_url}"
    const val PMTILES_FILE_PREFIX = "pmtiles://file://"

    /** PMTiles v3 fixed header size. */
    const val PMTILES_HEADER_SIZE = 127

    private const val COMPRESSION_NONE = 1
    private const val COMPRESSION_GZIP = 2
    private const val TILE_TYPE_MVT = 1

    private val LOCAL_PREFIXES = listOf("asset://", "file://", PMTILES_FILE_PREFIX)
    private val ANY_URL = Regex("""(?i)\b(?:https?|wss?)://[^\s"']+""")

    fun isLocalUrl(url: String): Boolean = LOCAL_PREFIXES.any { url.startsWith(it, ignoreCase = true) }

    /** Every network URL in a style JSON (should be empty). */
    fun remoteUrls(styleJson: String): List<String> =
        ANY_URL.findAll(styleJson).map { it.value }.filterNot { isLocalUrl(it) }.toList()

    /** Native MapLibre source URL for a PMTiles archive at [absolutePath] (`pmtiles://file:///…`). */
    fun pmtilesUrl(absolutePath: String): String =
        PMTILES_FILE_PREFIX + if (absolutePath.startsWith("/")) absolutePath else "/$absolutePath"

    /** Bind the installed map's source URL into the style template. */
    fun bindStyle(template: String, mapUrl: String): String = template.replace(MAP_URL_PLACEHOLDER, mapUrl)

    /** Stable id for a map file version (changes when the file is replaced). */
    fun mapId(sizeBytes: Long, lastModifiedMs: Long): String =
        java.lang.Long.toHexString(sizeBytes) + "-" + java.lang.Long.toHexString(lastModifiedMs)

    /** PMTiles v3 files start with the ASCII magic "PMTiles" followed by version byte 3. */
    fun isPmtilesHeader(header: ByteArray): Boolean =
        header.size >= 8 && String(header, 0, 7, Charsets.US_ASCII) == "PMTiles" && header[7].toInt() == 3

    /**
     * Why a PMTiles header cannot be rendered by MapLibre's native reader, or null if it can:
     * v3, gzip/none directory + tile compression, MVT (vector) tiles.
     */
    fun pmtilesHeaderProblem(header: ByteArray): String? {
        if (header.size < PMTILES_HEADER_SIZE || !isPmtilesHeader(header)) return "not a PMTiles v3 archive"
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val internal = b.get(97).toInt()
        val tile = b.get(98).toInt()
        val type = b.get(99).toInt()
        if (internal != COMPRESSION_NONE && internal != COMPRESSION_GZIP ||
            tile != COMPRESSION_NONE && tile != COMPRESSION_GZIP
        ) return "unsupported compression (internal=$internal, tile=$tile); rebuild with gzip"
        if (type != TILE_TYPE_MVT) return "tiles are not vector (MVT) tiles (type=$type)"
        return null
    }
}
