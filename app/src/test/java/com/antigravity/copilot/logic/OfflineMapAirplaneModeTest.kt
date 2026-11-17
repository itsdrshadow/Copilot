package com.antigravity.copilot.logic

import com.antigravity.copilot.core.logic.MapOfflinePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "Airplane-mode launch" at the JVM level: everything the renderer is given on a fresh launch
 * must be local (asset:// or the in-process tile host). The on-device check is MapNetworkGuard,
 * which crashes debug builds on any other map request; see NOTES.md for the emulator run.
 */
class OfflineMapAirplaneModeTest {

    private val assets = File("src/main/assets/offline_style")
    private val template get() = File(assets, "style.json").readText()

    @Test
    fun `bundled style only references bundled assets and the in-process tile host`() {
        assertTrue(template.contains(MapOfflinePolicy.LOCAL_TILE_TEMPLATE))
        assertTrue("template must not reference the network: ${MapOfflinePolicy.remoteUrls(template)}", MapOfflinePolicy.remoteUrls(template).isEmpty())

        val bound = MapOfflinePolicy.bindStyle(template, MapOfflinePolicy.mapId(5_000_000_000L, 1_700_000_000_000L))
        assertTrue(MapOfflinePolicy.remoteUrls(bound).isEmpty())
        assertFalse(bound.contains(MapOfflinePolicy.MAP_ID_PLACEHOLDER))
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
        assertNull(MapOfflinePolicy.parseLocalTileUrl("http://evil.example/abc/1/2/3.pbf"))
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
}
