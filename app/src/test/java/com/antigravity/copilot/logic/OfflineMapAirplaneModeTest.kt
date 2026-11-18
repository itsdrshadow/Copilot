package com.antigravity.copilot.logic

import com.antigravity.copilot.core.logic.MapOfflinePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "Airplane-mode launch" at the JVM level: everything the renderer is given on a fresh launch
 * must be local (asset:// or the native pmtiles://file:// source). The on-device check is MapNetworkGuard,
 * which crashes debug builds on any other map request; see NOTES.md for the emulator run.
 */
class OfflineMapAirplaneModeTest {

    private val assets = File("src/main/assets/offline_style")
    private val template get() = File(assets, "style.json").readText()

    @Test
    fun `bundled style only references bundled assets and the native pmtiles source`() {
        assertTrue(template.contains("\"url\": \"${MapOfflinePolicy.MAP_URL_PLACEHOLDER}\""))
        assertTrue("template must not reference the network: ${MapOfflinePolicy.remoteUrls(template)}", MapOfflinePolicy.remoteUrls(template).isEmpty())

        val bound = MapOfflinePolicy.bindStyle(template, MapOfflinePolicy.pmtilesUrl("/data/maps/india.pmtiles"))
        assertTrue(MapOfflinePolicy.remoteUrls(bound).isEmpty())
        assertFalse(bound.contains(MapOfflinePolicy.MAP_URL_PLACEHOLDER))
        assertTrue(bound.contains("\"url\": \"pmtiles://file:///data/maps/india.pmtiles\""))
        assertFalse("no in-process tile host left", bound.contains("tiles.copilot.invalid"))
        assertTrue(bound.contains("\"glyphs\": \"asset://fonts/{fontstack}/{range}.pbf\""))
        assertTrue(bound.contains("\"sprite\": \"asset://offline_style/sprites/sprite\""))
        for (f in listOf("sprite.json", "sprite.png", "sprite@2x.json", "sprite@2x.png")) {
            assertTrue("missing bundled sprite file $f", File(assets, "sprites/$f").isFile)
        }
    }

    @Test
    fun `remote urls are detected, local tile urls are not`() {
        val bad = """{"sources":{"osm":{"tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"]}}}"""
        assertEquals(listOf("https://tile.openstreetmap.org/{z}/{x}/{y}.png"), MapOfflinePolicy.remoteUrls(bad))
        assertFalse(MapOfflinePolicy.isLocalUrl("https://tile.openstreetmap.org/1/1/1.png"))
        assertTrue(MapOfflinePolicy.isLocalUrl("asset://offline_style/style.json"))
        assertTrue(MapOfflinePolicy.isLocalUrl("pmtiles://file:///data/maps/india.pmtiles"))
        assertFalse(MapOfflinePolicy.isLocalUrl("pmtiles://https://example.com/india.pmtiles"))
    }

    @Test
    fun `every text-font and icon-image used by the style is bundled`() {
        val fontsDir = File(assets.parentFile, "fonts")
        Regex("\"text-font\":\\s*\\[([^\\]]*)]").findAll(template).flatMap { m ->
            Regex("\"([^\"]+)\"").findAll(m.groupValues[1]).map { it.groupValues[1] }
        }.toSet().forEach { font ->
            assertTrue("missing Latin glyphs for $font", File(fontsDir, "$font/0-255.pbf").isFile)
        }
        val sprite = File(assets, "sprites/sprite.json").readText()
        for (icon in listOf("shield", "poi_fuel", "poi_hospital", "poi_toll")) {
            assertTrue("style uses $icon", template.contains("\"$icon\""))
            assertTrue("sprite is missing $icon", sprite.contains("\"$icon\""))
        }
    }

    @Test
    fun `pmtiles header check`() {
        assertTrue(MapOfflinePolicy.isPmtilesHeader("PMTiles".toByteArray() + byteArrayOf(3)))
        assertFalse(MapOfflinePolicy.isPmtilesHeader("SQLite f".toByteArray()))
        assertFalse(MapOfflinePolicy.isPmtilesHeader("PMTiles".toByteArray() + byteArrayOf(2)))
    }

    @Test
    fun `pmtiles header must be gzip or uncompressed vector tiles`() {
        fun header(internal: Int, tile: Int, type: Int) = ByteArray(MapOfflinePolicy.PMTILES_HEADER_SIZE).also {
            "PMTiles".toByteArray().copyInto(it); it[7] = 3; it[97] = internal.toByte(); it[98] = tile.toByte(); it[99] = type.toByte()
        }
        assertNull(MapOfflinePolicy.pmtilesHeaderProblem(header(internal = 2, tile = 2, type = 1)))
        assertNull(MapOfflinePolicy.pmtilesHeaderProblem(header(internal = 1, tile = 1, type = 1)))
        assertNotNull(MapOfflinePolicy.pmtilesHeaderProblem(header(internal = 3, tile = 2, type = 1))) // brotli
        assertNotNull(MapOfflinePolicy.pmtilesHeaderProblem(header(internal = 2, tile = 2, type = 2))) // png raster
        assertNotNull(MapOfflinePolicy.pmtilesHeaderProblem("PMTiles".toByteArray() + byteArrayOf(3)))  // truncated
    }
}
