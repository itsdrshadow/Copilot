package com.antigravity.copilot.core.map.tiles

import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Minimal offline vector-tile access (audit J1): a PMTiles v3 reader plus a Mapbox Vector Tile
 * decoder, pure Kotlin, no new dependency. Lets the tracking service read road class / ref /
 * maxspeed and POIs from the local map file without the map being on screen.
 */
object TileMath {
    /** PMTiles tile id: tiles of all lower zooms, then the Hilbert index of (x, y) at [z]. */
    fun zxyToTileId(z: Int, x: Int, y: Int): Long {
        require(z in 0..26) { "zoom out of range" }
        var acc = 0L
        for (a in 0 until z) acc += (1L shl a) * (1L shl a)
        val n = 1L shl z
        require(x in 0 until n && y in 0 until n) { "tile out of range" }
        var tx = x.toLong()
        var ty = y.toLong()
        var d = 0L
        var s = n / 2
        while (s > 0) {
            val rx = if ((tx and s) > 0) 1L else 0L
            val ry = if ((ty and s) > 0) 1L else 0L
            d += s * s * ((3 * rx) xor ry)
            if (ry == 0L) {
                if (rx == 1L) { tx = s - 1 - tx; ty = s - 1 - ty }
                val t = tx; tx = ty; ty = t
            }
            s /= 2
        }
        return acc + d
    }

    fun lonToTileX(lon: Double, z: Int): Int = floor((lon + 180.0) / 360.0 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)

    fun latToTileY(lat: Double, z: Int): Int {
        val r = Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878))
        return floor((1.0 - ln(tan(r) + 1.0 / kotlin.math.cos(r)) / PI) / 2.0 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)
    }

    /** Tile-local coordinate (0..extent) to lon / lat. */
    fun toLonLat(z: Int, tx: Int, ty: Int, px: Double, py: Double, extent: Int): DoubleArray {
        val n = (1 shl z).toDouble()
        val wx = (tx + px / extent) / n
        val wy = (ty + py / extent) / n
        val lon = wx * 360.0 - 180.0
        val lat = Math.toDegrees(atan(sinh(PI * (1 - 2 * wy))))
        return doubleArrayOf(lon, lat)
    }
}

/** One decoded feature: properties and geometry in lon / lat (parts = lines / rings / points). */
class VtFeature(val type: Int, val properties: Map<String, Any>, val parts: List<List<DoubleArray>>) {
    fun str(key: String): String? = (properties[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }
    fun num(key: String): Number? = properties[key] as? Number

    companion object {
        const val POINT = 1
        const val LINESTRING = 2
        const val POLYGON = 3
    }
}

/** Mapbox Vector Tile (protobuf) decoder. */
object MvtDecoder {

    private class Pb(private val buf: ByteArray, var pos: Int, private val end: Int) {
        fun hasMore() = pos < end
        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = buf[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw IllegalStateException("varint too long")
            }
        }
        fun fixed32(): Int = ByteBuffer.wrap(buf, pos, 4).order(ByteOrder.LITTLE_ENDIAN).int.also { pos += 4 }
        fun fixed64(): Long = ByteBuffer.wrap(buf, pos, 8).order(ByteOrder.LITTLE_ENDIAN).long.also { pos += 8 }
        fun bytes(): Pb { val len = varint().toInt(); val sub = Pb(buf, pos, pos + len); pos += len; return sub }
        fun string(): String { val len = varint().toInt(); val s = String(buf, pos, len, Charsets.UTF_8); pos += len; return s }
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                2 -> pos += varint().toInt()
                5 -> pos += 4
                else -> throw IllegalStateException("bad wire type $wire")
            }
        }
        fun packed(): LongArray {
            val sub = bytes()
            val out = ArrayList<Long>()
            while (sub.hasMore()) out += sub.varint()
            return out.toLongArray()
        }
    }

    private fun zigzag(n: Long): Long = (n ushr 1) xor -(n and 1)

    /** Decodes the [wantedLayers] of a raw (already decompressed) MVT tile at z / x / y. */
    fun decode(data: ByteArray, z: Int, x: Int, y: Int, wantedLayers: Set<String>): Map<String, List<VtFeature>> {
        val out = HashMap<String, List<VtFeature>>()
        val tile = Pb(data, 0, data.size)
        while (tile.hasMore()) {
            val key = tile.varint().toInt()
            if (key ushr 3 == 3 && key and 7 == 2) {
                decodeLayer(tile.bytes(), z, x, y, wantedLayers)?.let { (name, features) -> out[name] = features }
            } else tile.skip(key and 7)
        }
        return out
    }

    private class RawFeature(var type: Int = 0, var tags: LongArray = LongArray(0), var geometry: LongArray = LongArray(0))

    private fun decodeLayer(layer: Pb, z: Int, x: Int, y: Int, wanted: Set<String>): Pair<String, List<VtFeature>>? {
        var name = ""
        var extent = 4096
        val keys = ArrayList<String>()
        val values = ArrayList<Any>()
        val raws = ArrayList<RawFeature>()
        while (layer.hasMore()) {
            val key = layer.varint().toInt()
            when (key ushr 3) {
                1 -> name = layer.string()
                2 -> {
                    val f = layer.bytes()
                    val raw = RawFeature()
                    while (f.hasMore()) {
                        val fk = f.varint().toInt()
                        when (fk ushr 3) {
                            2 -> raw.tags = f.packed()
                            3 -> raw.type = f.varint().toInt()
                            4 -> raw.geometry = f.packed()
                            else -> f.skip(fk and 7)
                        }
                    }
                    raws += raw
                }
                3 -> keys += layer.string()
                4 -> values += decodeValue(layer.bytes())
                5 -> extent = layer.varint().toInt()
                else -> layer.skip(key and 7)
            }
        }
        if (name !in wanted) return null
        val features = raws.mapNotNull { raw ->
            val props = HashMap<String, Any>()
            var i = 0
            while (i + 1 < raw.tags.size) {
                val k = keys.getOrNull(raw.tags[i].toInt())
                val v = values.getOrNull(raw.tags[i + 1].toInt())
                if (k != null && v != null) props[k] = v
                i += 2
            }
            val parts = decodeGeometry(raw.geometry, z, x, y, extent)
            if (parts.isEmpty()) null else VtFeature(raw.type, props, parts)
        }
        return name to features
    }

    private fun decodeValue(v: Pb): Any {
        var result: Any = ""
        while (v.hasMore()) {
            val key = v.varint().toInt()
            result = when (key ushr 3) {
                1 -> v.string()
                2 -> java.lang.Float.intBitsToFloat(v.fixed32()).toDouble()
                3 -> java.lang.Double.longBitsToDouble(v.fixed64())
                4 -> v.varint()
                5 -> v.varint()
                6 -> zigzag(v.varint())
                7 -> v.varint() != 0L
                else -> { v.skip(key and 7); result }
            }
        }
        return result
    }

    private fun decodeGeometry(g: LongArray, z: Int, x: Int, y: Int, extent: Int): List<List<DoubleArray>> {
        val parts = ArrayList<List<DoubleArray>>()
        var current = ArrayList<DoubleArray>()
        var cx = 0L
        var cy = 0L
        var i = 0
        while (i < g.size) {
            val cmd = g[i++]
            val id = (cmd and 7).toInt()
            val count = (cmd ushr 3).toInt()
            when (id) {
                1, 2 -> repeat(count) {
                    if (i + 1 >= g.size) return parts.also { if (current.isNotEmpty()) it += current }
                    cx += zigzag(g[i++]); cy += zigzag(g[i++])
                    if (id == 1 && current.isNotEmpty()) { parts += current; current = ArrayList() }
                    current += TileMath.toLonLat(z, x, y, cx.toDouble(), cy.toDouble(), extent)
                }
                7 -> { if (current.isNotEmpty()) current += current.first() }
                else -> return parts
            }
        }
        if (current.isNotEmpty()) parts += current
        return parts
    }
}

/**
 * PMTiles v3 archive reader (gzip or uncompressed directories / tiles). Thread-safe; keeps the
 * root directory and a few leaf directories / tiles cached.
 */
class PmtilesReader(private val file: File) : AutoCloseable {

    class Header(
        val rootDirOffset: Long, val rootDirLength: Long,
        val leafDirOffset: Long, val tileDataOffset: Long,
        val internalCompression: Int, val tileCompression: Int, val tileType: Int,
        val minZoom: Int, val maxZoom: Int
    )

    private class Entry(val tileId: Long, val offset: Long, val length: Int, val runLength: Long)

    private val raf = RandomAccessFile(file, "r")
    val header: Header = readHeader()
    private val root: List<Entry> = readDirectory(header.rootDirOffset, header.rootDirLength.toInt())
    private val leafCache = object : LinkedHashMap<Long, List<Entry>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<Entry>>?) = size > 8
    }

    private fun readHeader(): Header {
        val b = ByteArray(127)
        synchronized(raf) { raf.seek(0); raf.readFully(b) }
        require(String(b, 0, 7, Charsets.US_ASCII) == "PMTiles" && b[7].toInt() == 3) { "not a PMTiles v3 archive" }
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        return Header(
            rootDirOffset = bb.getLong(8), rootDirLength = bb.getLong(16),
            leafDirOffset = bb.getLong(40), tileDataOffset = bb.getLong(56),
            internalCompression = b[97].toInt(), tileCompression = b[98].toInt(), tileType = b[99].toInt(),
            minZoom = b[100].toInt() and 0xFF, maxZoom = b[101].toInt() and 0xFF
        )
    }

    private fun readBytes(offset: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        synchronized(raf) { raf.seek(offset); raf.readFully(out) }
        return out
    }

    private fun decompress(data: ByteArray, compression: Int): ByteArray = when (compression) {
        COMPRESSION_GZIP -> GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
        COMPRESSION_NONE, 0 -> data
        else -> throw IllegalStateException("unsupported compression $compression")
    }

    private fun readDirectory(offset: Long, length: Int): List<Entry> =
        parseDirectory(decompress(readBytes(offset, length), header.internalCompression))

    /** Raw (decompressed) tile bytes, or null when the archive has no tile there. */
    fun tile(z: Int, x: Int, y: Int): ByteArray? {
        val id = TileMath.zxyToTileId(z, x, y)
        var dir = root
        repeat(4) { // depth limit (spec allows up to 3 leaf levels)
            val e = findEntry(dir, id) ?: return null
            if (e.runLength > 0) {
                return decompress(readBytes(header.tileDataOffset + e.offset, e.length), header.tileCompression)
            }
            val leafOffset = header.leafDirOffset + e.offset
            dir = synchronized(leafCache) { leafCache[leafOffset] } ?: readDirectory(leafOffset, e.length).also {
                synchronized(leafCache) { leafCache[leafOffset] = it }
            }
        }
        return null
    }

    override fun close() = raf.close()

    companion object {
        const val COMPRESSION_NONE = 1
        const val COMPRESSION_GZIP = 2

        private fun findEntry(entries: List<Entry>, tileId: Long): Entry? {
            var lo = 0
            var hi = entries.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val c = entries[mid].tileId.compareTo(tileId)
                when {
                    c < 0 -> lo = mid + 1
                    c > 0 -> hi = mid - 1
                    else -> return entries[mid]
                }
            }
            if (hi >= 0) {
                val e = entries[hi]
                if (e.runLength == 0L) return e // leaf directory covering this range
                if (tileId - e.tileId < e.runLength) return e
            }
            return null
        }

        private fun parseDirectory(data: ByteArray): List<Entry> {
            var pos = 0
            fun varint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    val b = data[pos++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                }
            }
            val n = varint().toInt()
            val ids = LongArray(n)
            var last = 0L
            for (i in 0 until n) { last += varint(); ids[i] = last }
            val runs = LongArray(n) { varint() }
            val lengths = IntArray(n) { varint().toInt() }
            val offsets = LongArray(n)
            for (i in 0 until n) {
                val v = varint()
                offsets[i] = if (v == 0L && i > 0) offsets[i - 1] + lengths[i - 1] else v - 1
            }
            return List(n) { Entry(ids[it], offsets[it], lengths[it], runs[it]) }
        }
    }
}
