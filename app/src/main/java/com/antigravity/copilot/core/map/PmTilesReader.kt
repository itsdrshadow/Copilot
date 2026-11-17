package com.antigravity.copilot.core.map

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.GZIPInputStream

/**
 * Minimal, dependency-free PMTiles v3 reader (https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md).
 *
 * MapLibre Native 10.3.2 (kept because 11.x needs OpenGL ES 3.0, which GLES2-only head units and
 * the emulator lack) cannot open local MBTiles/PMTiles itself, so tiles are served from this
 * reader in-process via [MapNetworkGuard] — no socket is ever opened.
 * Supports internal/tile compression "none" and "gzip" (Planetiler and Protomaps defaults).
 *
 * Low-RAM / flash-friendly pipeline:
 * - One persistent [FileChannel] for the archive's lifetime. Reads are positional
 *   ([FileChannel.read] with an explicit offset), so concurrent tile requests never share a file
 *   pointer and disk I/O never runs under a global lock.
 * - Root + leaf directories are decoded once and kept in a small in-memory LRU.
 * - A byte-bounded RAM LRU (default 50 MB) holds the *raw* (still gzipped) tile bytes, so panning
 *   back over recently seen areas never touches flash. Keeping the compressed form fits ~3x more
 *   tiles in the same budget than decoded MVT would.
 */
class PmTilesReader(
    private val file: File,
    tileCacheBytes: Long = DEFAULT_TILE_CACHE_BYTES
) : Closeable {

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
    private val channel: FileChannel = raf.channel

    private val dirCache = object : LinkedHashMap<Long, List<Entry>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<Entry>>?) = size > MAX_CACHED_DIRECTORIES
    }

    /** Raw tile bytes keyed by PMTiles tile id (one reader == one archive, so the id is unique). */
    val tileCache = ByteLruCache(tileCacheBytes)

    val header: Header = readHeader()

    init {
        if (header.internalCompression !in SUPPORTED_COMPRESSION || header.tileCompression !in SUPPORTED_COMPRESSION) {
            close()
            throw IOException("Unsupported PMTiles compression (internal=${header.internalCompression}, tile=${header.tileCompression}); rebuild with gzip")
        }
    }

    /** Uncompressed tile bytes (e.g. MVT protobuf) or null if the tile is not in the archive. */
    fun getTile(z: Int, x: Int, y: Int): ByteArray? =
        getTileRaw(z, x, y)?.let { decompress(it, header.tileCompression) }

    /**
     * Tile bytes exactly as stored in the archive (gzip for Planetiler/Protomaps builds), served
     * from the RAM cache when possible. Thread-safe; disk reads run without holding any lock.
     */
    fun getTileRaw(z: Int, x: Int, y: Int): ByteArray? {
        if (z < header.minZoom || z > header.maxZoom) return null
        val n = 1 shl z
        if (x !in 0 until n || y !in 0 until n) return null
        val tileId = zxyToTileId(z, x, y)
        tileCache.get(tileId)?.let { return it }

        var dirOffset = header.rootDirOffset
        var dirLength = header.rootDirLength
        repeat(4) {
            val entry = findTile(directory(dirOffset, dirLength), tileId) ?: return null
            if (entry.runLength > 0) {
                val bytes = read(header.tileDataOffset + entry.offset, entry.length)
                tileCache.put(tileId, bytes)
                return bytes
            }
            dirOffset = header.leafDirsOffset + entry.offset
            dirLength = entry.length.toLong()
        }
        return null
    }

    private fun directory(offset: Long, length: Long): List<Entry> {
        synchronized(dirCache) { dirCache[offset] }?.let { return it }
        // Decode outside the lock; a rare duplicate decode by two threads is harmless.
        val decoded = deserializeDirectory(decompress(read(offset, length.toInt()), header.internalCompression))
        synchronized(dirCache) { dirCache[offset] = decoded }
        return decoded
    }

    private fun read(offset: Long, length: Int): ByteArray {
        val buf = ByteBuffer.allocate(length)
        var pos = offset
        while (buf.hasRemaining()) {
            val n = channel.read(buf, pos)
            if (n < 0) throw EOFException("Unexpected end of ${file.name} at $pos")
            pos += n
        }
        return buf.array()
    }

    private fun readHeader(): Header {
        val b = try {
            read(0, HEADER_SIZE)
        } catch (e: IOException) {
            close()
            throw IOException("${file.name} is not a PMTiles v3 archive", e)
        }
        if (String(b, 0, 7, Charsets.US_ASCII) != "PMTiles" || b[7].toInt() != 3) {
            close()
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

    override fun close() {
        tileCache.clear()
        synchronized(dirCache) { dirCache.clear() }
        try { channel.close() } catch (_: IOException) {}
        try { raf.close() } catch (_: IOException) {}
    }

    /** Thread-safe LRU bounded by total value bytes (pure JVM, so it also runs in unit tests). */
    class ByteLruCache(val maxBytes: Long) {
        private val map = LinkedHashMap<Long, ByteArray>(256, 0.75f, true)
        private var bytes = 0L

        val sizeBytes: Long @Synchronized get() = bytes
        val entryCount: Int @Synchronized get() = map.size

        @Synchronized
        fun get(key: Long): ByteArray? = map[key]

        @Synchronized
        fun put(key: Long, value: ByteArray) {
            if (maxBytes <= 0 || value.size > maxBytes) return
            map.put(key, value)?.let { bytes -= it.size }
            bytes += value.size
            val it = map.entries.iterator()
            while (bytes > maxBytes && it.hasNext()) {
                bytes -= it.next().value.size
                it.remove()
            }
        }

        @Synchronized
        fun clear() {
            map.clear()
            bytes = 0L
        }
    }

    companion object {
        const val HEADER_SIZE = 127
        const val COMPRESSION_NONE = 1
        const val COMPRESSION_GZIP = 2
        const val DEFAULT_TILE_CACHE_BYTES = 50L * 1024 * 1024
        private const val MAX_CACHED_DIRECTORIES = 256
        private val SUPPORTED_COMPRESSION = setOf(0, COMPRESSION_NONE, COMPRESSION_GZIP)

        /** 50 MB, but never more than 1/8 of the heap on very low-RAM units. */
        fun recommendedTileCacheBytes(): Long =
            minOf(DEFAULT_TILE_CACHE_BYTES, Runtime.getRuntime().maxMemory() / 8)

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

        fun isGzip(bytes: ByteArray): Boolean =
            bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()

        fun decompress(bytes: ByteArray, compression: Int): ByteArray =
            if (compression == COMPRESSION_GZIP || isGzip(bytes)) {
                GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
                    val out = ByteArrayOutputStream(bytes.size * 3)
                    input.copyTo(out)
                    out.toByteArray()
                }
            } else bytes
    }
}
