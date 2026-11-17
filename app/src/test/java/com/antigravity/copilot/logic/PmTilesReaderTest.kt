package com.antigravity.copilot.logic

import com.antigravity.copilot.core.map.PmTilesReader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream

class PmTilesReaderTest {

    @Test
    fun `hilbert tile ids match the PMTiles spec`() {
        assertEquals(0L, PmTilesReader.zxyToTileId(0, 0, 0))
        assertEquals(1L, PmTilesReader.zxyToTileId(1, 0, 0))
        assertEquals(2L, PmTilesReader.zxyToTileId(1, 0, 1))
        assertEquals(3L, PmTilesReader.zxyToTileId(1, 1, 1))
        assertEquals(4L, PmTilesReader.zxyToTileId(1, 1, 0))
        assertEquals(5L, PmTilesReader.zxyToTileId(2, 0, 0))
        assertEquals(19_078_479L, PmTilesReader.zxyToTileId(12, 3423, 1763))
    }

    @Test
    fun `reads gzip tiles through root and leaf directories`() {
        val tiles = linkedMapOf(
            Triple(0, 0, 0) to "z0".toByteArray(),
            Triple(1, 0, 0) to "z1-a".toByteArray(),
            Triple(1, 1, 1) to "z1-b".toByteArray(),
            Triple(2, 3, 2) to "z2".toByteArray()
        )
        for (useLeaf in listOf(false, true)) {
            val f = File.createTempFile("test", ".pmtiles").apply { deleteOnExit() }
            f.writeBytes(buildArchive(tiles, useLeaf))
            PmTilesReader(f).use { r ->
                assertEquals(0, r.header.minZoom)
                assertEquals(2, r.header.maxZoom)
                for ((zxy, bytes) in tiles) assertArrayEquals(bytes, r.getTile(zxy.first, zxy.second, zxy.third))
                assertNull(r.getTile(1, 0, 1))  // not in archive
                assertNull(r.getTile(5, 0, 0))  // above max zoom
                // Raw bytes stay gzipped (pass-through form) and are now served from RAM
                assertEquals(tiles.size, r.tileCache.entryCount)
                val raw = r.getTileRaw(0, 0, 0)!!
                assertTrue(PmTilesReader.isGzip(raw))
                assertArrayEquals(tiles.getValue(Triple(0, 0, 0)), PmTilesReader.decompress(raw, r.header.tileCompression))
            }
        }
    }

    @Test
    fun `run length entries serve repeated tiles`() {
        val ocean = "ocean".toByteArray()
        val f = File.createTempFile("runlength", ".pmtiles").apply { deleteOnExit() }
        // Tile ids 1..4 (all of z1) share one blob via runLength = 4
        val blob = gzip(ocean)
        val dir = serializeDir(listOf(longArrayOf(1, 0, blob.size.toLong(), 4)))
        f.writeBytes(assemble(gzip(dir), ByteArray(0), blob, minZ = 1, maxZ = 1))
        PmTilesReader(f).use { r ->
            for ((x, y) in listOf(0 to 0, 0 to 1, 1 to 1, 1 to 0)) assertArrayEquals(ocean, r.getTile(1, x, y))
        }
    }

    @Test(expected = IOException::class)
    fun `non pmtiles files are rejected`() {
        val f = File.createTempFile("bad", ".pmtiles").apply { deleteOnExit(); writeBytes(ByteArray(200)) }
        PmTilesReader(f)
    }

    // ---------------- tiny PMTiles v3 writer for tests ----------------

    private fun gzip(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    private fun varint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v >= 0x80) { out.write(((v and 0x7F) or 0x80).toInt()); v = v ushr 7 }
        out.write(v.toInt())
    }

    /** entries: [tileId, offset, length, runLength] */
    private fun serializeDir(entries: List<LongArray>): ByteArray {
        val o = ByteArrayOutputStream()
        varint(o, entries.size.toLong())
        var last = 0L
        for (e in entries) { varint(o, e[0] - last); last = e[0] }
        for (e in entries) varint(o, e[3])
        for (e in entries) varint(o, e[2])
        for (e in entries) varint(o, e[1] + 1)
        return o.toByteArray()
    }

    private fun buildArchive(tiles: Map<Triple<Int, Int, Int>, ByteArray>, useLeaf: Boolean): ByteArray {
        val data = ByteArrayOutputStream()
        val entries = tiles.entries.map { (k, v) -> PmTilesReader.zxyToTileId(k.first, k.second, k.third) to gzip(v) }
            .sortedBy { it.first }
            .map { (id, blob) -> val off = data.size().toLong(); data.write(blob); longArrayOf(id, off, blob.size.toLong(), 1) }
        val leafDir = gzip(serializeDir(entries))
        val root = if (useLeaf) gzip(serializeDir(listOf(longArrayOf(entries.first()[0], 0, leafDir.size.toLong(), 0)))) else leafDir
        return assemble(root, if (useLeaf) leafDir else ByteArray(0), data.toByteArray(), 0, 2)
    }

    private fun assemble(root: ByteArray, leaves: ByteArray, data: ByteArray, minZ: Int, maxZ: Int): ByteArray {
        val h = ByteBuffer.allocate(PmTilesReader.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        h.put("PMTiles".toByteArray()); h.put(3)
        val rootOff = PmTilesReader.HEADER_SIZE.toLong()
        val leafOff = rootOff + root.size
        val dataOff = leafOff + leaves.size
        h.putLong(rootOff); h.putLong(root.size.toLong())
        h.putLong(leafOff); h.putLong(0)                 // metadata (empty)
        h.putLong(leafOff); h.putLong(leaves.size.toLong())
        h.putLong(dataOff); h.putLong(data.size.toLong())
        h.putLong(0); h.putLong(0); h.putLong(0)         // counts
        h.put(1); h.put(2); h.put(2); h.put(1)           // clustered, internal gzip, tile gzip, mvt
        h.put(minZ.toByte()); h.put(maxZ.toByte())
        assertTrue(h.position() == 102)
        return h.array() + root + leaves + data
    }
}
