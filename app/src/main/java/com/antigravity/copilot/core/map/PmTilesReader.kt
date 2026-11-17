package com.antigravity.copilot.core.map

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/**
 * Minimal, dependency-free PMTiles v3 reader (https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md).
 *
 * MapLibre Native 10.3.2 (kept because 11.x needs OpenGL ES 3.0, which GLES2-only head units and
 * the emulator lack) cannot open local MBTiles/PMTiles itself, so tiles are served from this
 * reader in-process via [MapNetworkGuard] — no socket is ever opened.
 * Supports internal/tile compression "none" and "gzip" (Planetiler and Protomaps defaults).
 */
class PmTilesReader(private val file: File) : Closeable {

    data class Header(
        val rootDirOffset: Long,
        val rootDirLength: Long,
        val metadataOffset: Long,
        val metadataLength: Long,
        val leafDirsOffset: Long,
        val leafDirsLength: Long,
        val tileDataOffset: Long,
        val tileDataLength: Long,
        val internalCompression: Int,
        val tileCompression: Int,
        val tileType: Int,
        val minZoom: Int,
        val maxZoom: Int
    )

    data class Entry(val tileId: Long, val offset: Long, val length: Int, val runLength: Int)

    private val raf = RandomAccessFile(file, "r")
    val header: Header = readHeader()
    private val dirCache = object : LinkedHashMap<Long, List<Entry>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<Entry>>?) = size > 64
    }

    init {
        if (header.internalCompression !in SUPPORTED_COMPRESSION || header.tileCompression !in SUPPORTED_COMPRESSION) {
            raf.close()
            throw IOException("Unsupported PMTiles compression (internal=${header.internalCompression}, tile=${header.tileCompression}); rebuild with gzip")
        }
    }

    /** Uncompressed tile bytes (e.g. MVT protobuf) or null if the tile is not in the archive. */
    @Synchronized
    fun getTile(z: Int, x: Int, y: Int): ByteArray? {
        if (z < header.minZoom || z > header.maxZoom) return null
        val n = 1 shl z
        if (x !in 0 until n || y !in 0 until n) return null
        val tileId = zxyToTileId(z, x, y)
        var dirOffset = header.rootDirOffset
        var dirLength = header.rootDirLength
        repeat(4) {
            val entry = findTile(directory(dirOffset, dirLength), tileId) ?: return null
            if (entry.runLength > 0) {
                val bytes = read(header.tileDataOffset + entry.offset, entry.length)
                return decompress(bytes, header.tileCompression)
            }
            dirOffset = header.leafDirsOffset + entry.offset
            dirLength = entry.length.toLong()
        }
        return null
    }

    private fun directory(offset: Long, length: Long): List<Entry> =
        dirCache.getOrPut(offset) { deserializeDirectory(decompress(read(offset, length.toInt()), header.internalCompression)) }

    private fun read(offset: Long, length: Int): ByteArray {
        val buf = ByteArray(length)
        raf.seek(offset)
        raf.readFully(buf)
        return buf
    }

    private fun readHeader(): Header {
        val b = read(0, HEADER_SIZE)
        if (String(b, 0, 7, Charsets.US_ASCII) != "PMTiles" || b[7].toInt() != 3) {
            raf.close()
            throw IOException("${file.name} is not a PMTiles v3 archive")
        }
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        return Header(
            rootDirOffset = bb.getLong(8), rootDirLength = bb.getLong(16),
            metadataOffset = bb.getLong(24), metadataLength = bb.getLong(32),
            leafDirsOffset = bb.getLong(40), leafDirsLength = bb.getLong(48),
            tileDataOffset = bb.getLong(56), tileDataLength = bb.getLong(64),
            internalCompression = b[97].toInt() and 0xFF,
            tileCompression = b[98].toInt() and 0xFF,
            tileType = b[99].toInt() and 0xFF,
            minZoom = b[100].toInt() and 0xFF,
            maxZoom = b[101].toInt() and 0xFF
        )
    }

    override fun close() = raf.close()

    companion object {
        const val HEADER_SIZE = 127
        const val COMPRESSION_NONE = 1
        const val COMPRESSION_GZIP = 2
        private val SUPPORTED_COMPRESSION = setOf(0, COMPRESSION_NONE, COMPRESSION_GZIP)

        /** Hilbert-curve tile id (PMTiles v3). */
        fun zxyToTileId(z: Int, x: Int, y: Int): Long {
            require(z in 0..26) { "zoom out of range" }
            var acc = 0L
            for (i in 0 until z) acc += 1L shl (2 * i)
            var tx = x.toLong()
            var ty = y.toLong()
            var d = 0L
            var s = (1L shl z) / 2
            while (s > 0) {
                val rx = if ((tx and s) > 0) 1L else 0L
                val ry = if ((ty and s) > 0) 1L else 0L
                d += s * s * ((3 * rx) xor ry)
                if (ry == 0L) {
                    if (rx == 1L) {
                        tx = s - 1 - tx
                        ty = s - 1 - ty
                    }
                    val t = tx; tx = ty; ty = t
                }
                s /= 2
            }
            return acc + d
        }

        fun deserializeDirectory(bytes: ByteArray): List<Entry> {
            var pos = 0
            fun varint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    val b = bytes[pos++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                }
            }
            val n = varint().toInt()
            val ids = LongArray(n)
            var last = 0L
            for (i in 0 until n) { last += varint(); ids[i] = last }
            val runs = IntArray(n) { varint().toInt() }
            val lens = IntArray(n) { varint().toInt() }
            val offs = LongArray(n)
            for (i in 0 until n) {
                val v = varint()
                offs[i] = if (v == 0L && i > 0) offs[i - 1] + lens[i - 1] else v - 1
            }
            return List(n) { Entry(ids[it], offs[it], lens[it], runs[it]) }
        }

        fun findTile(entries: List<Entry>, tileId: Long): Entry? {
            var lo = 0
            var hi = entries.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val c = tileId.compareTo(entries[mid].tileId)
                when {
                    c > 0 -> lo = mid + 1
                    c < 0 -> hi = mid - 1
                    else -> return entries[mid]
                }
            }
            if (hi >= 0) {
                val e = entries[hi]
                if (e.runLength == 0) return e
                if (tileId - e.tileId < e.runLength) return e
            }
            return null
        }

        fun decompress(bytes: ByteArray, compression: Int): ByteArray =
            if (compression == COMPRESSION_GZIP || (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte())) {
                GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
                    val out = ByteArrayOutputStream(bytes.size * 3)
                    input.copyTo(out)
                    out.toByteArray()
                }
            } else bytes
    }
}
