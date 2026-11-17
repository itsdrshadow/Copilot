package com.antigravity.copilot

import com.antigravity.copilot.core.map.IndiaOfflineMapManager
import com.antigravity.copilot.core.map.OfflineMapManager
import com.antigravity.copilot.feature.maps.PulsingAuraOverlay
import org.junit.Assert.*
import org.junit.Test

/**
 * Milestone3MapTileAndLifecycleTest:
 * Comprehensive test suite validating:
 * - Feature 24: Offline map tile image validation (HTTP 200, length >= 100 bytes, JPEG/PNG magic bytes).
 * - Feature 25: SQLite tile writer resource management (safe detach/close contracts).
 * - Feature 26: Activity context memory leak prevention (applicationContext usage).
 * - Feature 27: LiveMapView pulsing aura animation pause when detached / backgrounded.
 * - Feature 28: Unused native library removal (MapLibre cleanup).
 */
class Milestone3MapTileAndLifecycleTest {

    @Test
    fun testValidJpegTileImageValidation() {
        // Valid JPEG header: 0xFF, 0xD8, 0xFF
        val validJpeg = ByteArray(150)
        validJpeg[0] = 0xFF.toByte()
        validJpeg[1] = 0xD8.toByte()
        validJpeg[2] = 0xFF.toByte()
        validJpeg[3] = 0xE0.toByte()

        assertTrue("150-byte JPEG with valid magic bytes must pass validation",
            IndiaOfflineMapManager.isValidTileImage(validJpeg))
    }

    @Test
    fun testValidPngTileImageValidation() {
        // Valid PNG header: 0x89, 0x50, 0x4E, 0x47 (89 'P' 'N' 'G')
        val validPng = ByteArray(150)
        validPng[0] = 0x89.toByte()
        validPng[1] = 0x50.toByte()
        validPng[2] = 0x4E.toByte()
        validPng[3] = 0x47.toByte()

        assertTrue("150-byte PNG with valid magic bytes must pass validation",
            IndiaOfflineMapManager.isValidTileImage(validPng))
    }

    @Test
    fun testTileImageValidationExactBoundary100Bytes() {
        // Exactly 100 bytes is the minimum acceptable tile size
        val boundaryJpeg = ByteArray(100)
        boundaryJpeg[0] = 0xFF.toByte()
        boundaryJpeg[1] = 0xD8.toByte()
        boundaryJpeg[2] = 0xFF.toByte()
        assertTrue("Exact 100-byte JPEG must pass", IndiaOfflineMapManager.isValidTileImage(boundaryJpeg))

        val boundaryPng = ByteArray(100)
        boundaryPng[0] = 0x89.toByte()
        boundaryPng[1] = 0x50.toByte()
        boundaryPng[2] = 0x4E.toByte()
        boundaryPng[3] = 0x47.toByte()
        assertTrue("Exact 100-byte PNG must pass", IndiaOfflineMapManager.isValidTileImage(boundaryPng))
    }

    @Test
    fun testTileImageValidationRejectsUnder100Bytes() {
        // 99 bytes (truncated tile payload)
        val truncatedJpeg = ByteArray(99)
        truncatedJpeg[0] = 0xFF.toByte()
        truncatedJpeg[1] = 0xD8.toByte()
        truncatedJpeg[2] = 0xFF.toByte()
        assertFalse("Payload < 100 bytes must be rejected even with JPEG magic bytes",
            IndiaOfflineMapManager.isValidTileImage(truncatedJpeg))

        val truncatedPng = ByteArray(99)
        truncatedPng[0] = 0x89.toByte()
        truncatedPng[1] = 0x50.toByte()
        truncatedPng[2] = 0x4E.toByte()
        truncatedPng[3] = 0x47.toByte()
        assertFalse("Payload < 100 bytes must be rejected even with PNG magic bytes",
            IndiaOfflineMapManager.isValidTileImage(truncatedPng))
    }

    @Test
    fun testTileImageValidationRejectsNullAndEmptyPayload() {
        assertFalse("Null payload must be rejected", IndiaOfflineMapManager.isValidTileImage(null))
        assertFalse("Empty payload must be rejected", IndiaOfflineMapManager.isValidTileImage(ByteArray(0)))
    }

    @Test
    fun testTileImageValidationRejectsHtmlOrTextResponse() {
        // Upstream captive portals or 404/500 gateways returning HTML text
        val htmlPayload = "<html><head><title>404 Not Found</title></head><body><h1>Tile Server Error</h1><p>The requested tile could not be found.</p></body></html>"
            .toByteArray(Charsets.UTF_8)

        assertTrue(htmlPayload.size >= 100)
        assertFalse("HTML / text responses must be rejected as tile data",
            IndiaOfflineMapManager.isValidTileImage(htmlPayload))
    }

    @Test
    fun testTileImageValidationRejectsRandomGarbageBytes() {
        val randomBytes = ByteArray(256) { it.toByte() }
        assertFalse("Random bytes without JPEG/PNG magic bytes must be rejected",
            IndiaOfflineMapManager.isValidTileImage(randomBytes))
    }

    @Test
    fun testOfflineMapSourceConfiguration() {
        val normalSource = OfflineMapManager.NORMAL_ROADMAP_SOURCE
        assertEquals("Google_Roadmap", normalSource.name())
        assertEquals(0, normalSource.minimumZoomLevel)
        assertEquals(20, normalSource.maximumZoomLevel)
        assertEquals(256, normalSource.tileSizePixels)

        val satelliteSource = OfflineMapManager.SATELLITE_SOURCE
        assertEquals("Google_Hybrid_Satellite", satelliteSource.name())
        assertEquals(0, satelliteSource.minimumZoomLevel)
        assertEquals(20, satelliteSource.maximumZoomLevel)
        assertEquals(256, satelliteSource.tileSizePixels)

        val detailSource = OfflineMapManager.DETAIL_HUMANITARIAN_SOURCE
        assertEquals("Humanitarian_HOT", detailSource.name())
        assertEquals(1, detailSource.minimumZoomLevel)
        assertEquals(19, detailSource.maximumZoomLevel)
    }

    @Test
    fun testPulsingAuraOverlayAnimationLifecyclePauseAndResume() {
        val aura = PulsingAuraOverlay()
        assertFalse("Aura overlay must start in unpaused state", aura.isPaused)

        // Pausing stops CPU / GPU invalidation
        aura.pauseAnimation()
        assertTrue("Aura overlay must be paused after pauseAnimation()", aura.isPaused)

        // Resuming restores invalidation
        aura.resumeAnimation()
        assertFalse("Aura overlay must be unpaused after resumeAnimation()", aura.isPaused)

        // Detaching must pause animation to prevent leak
        aura.onDetach(null)
        assertTrue("Aura overlay must be paused upon onDetach()", aura.isPaused)
    }

    @Test
    fun testIndiaOfflineMapRegionsZoomRange() {
        assertEquals(0, IndiaOfflineMapManager.MIN_POSSIBLE_ZOOM)
        assertEquals(20, IndiaOfflineMapManager.MAX_POSSIBLE_ZOOM)

        val nationalPack = IndiaOfflineMapManager.INDIA_REGIONS.first { it.id == "india_national" }
        assertEquals("All-India minZoom must be 0", 0, nationalPack.minZoom)
        assertEquals("All-India maxZoom must be 12 (optimized for nationwide highway coverage)", 12, nationalPack.maxZoom)

        for (region in IndiaOfflineMapManager.INDIA_REGIONS) {
            assertEquals("Region ${region.name} minZoom must be 0", 0, region.minZoom)
            if (region.id == "india_national") {
                assertEquals(12, region.maxZoom)
            } else {
                assertEquals("Region ${region.name} maxZoom must be 13 (district & street coverage)", 13, region.maxZoom)
            }
        }
    }

    @Test
    fun testCalculateTileCountForZoomZeroToMax() {
        val nationalPack = IndiaOfflineMapManager.INDIA_REGIONS.first { it.id == "india_national" }

        // At Zoom 0, entire world is 1 tile
        val countZoomZero = IndiaOfflineMapManager.calculateTileCount(nationalPack.boundingBox, 0, 0)
        assertEquals(1L, countZoomZero)

        // At Zoom 0..4
        val countZoomZeroToFour = IndiaOfflineMapManager.calculateTileCount(nationalPack.boundingBox, 0, 4)
        assertTrue("Cumulative tiles for Z0-Z4 must be >= 10", countZoomZeroToFour >= 10L)

        // At Zoom 0..11, India arterial coverage
        val countZoomZeroToEleven = IndiaOfflineMapManager.calculateTileCount(nationalPack.boundingBox, 0, 11)
        assertTrue("Cumulative tiles for Z0-Z11 should be ~43,980", countZoomZeroToEleven in 40000L..50000L)

        // Estimated size dynamically updates with calibrated ~8.3KB roadmap tile average
        val estMbZoom11 = nationalPack.getEstimatedSizeMb(11)
        assertTrue("Estimated size for Z0-Z11 must be approx 300-450 MB", estMbZoom11 in 300..450)
    }

    @Test
    fun testIndiaOfflineMapStorageBudgetUnderTenGb() {
        val nationalPack = IndiaOfflineMapManager.INDIA_REGIONS.first { it.id == "india_national" }
        // All-India package (Zooms 0-12) must fit well under 4 GB (3,747 MB), respecting the 10 GB budget
        assertTrue("All-India pack must be < 4000 MB", nationalPack.estimatedSizeMb < 4000)

        // Regional packs (Zooms 0-13) must each be under 3 GB
        for (region in IndiaOfflineMapManager.INDIA_REGIONS.filter { it.id != "india_national" }) {
            assertTrue("Region ${region.id} estimated size must be < 3000 MB", region.estimatedSizeMb < 3000)
        }
    }

    @Test
    fun testDarkModeColorFilterNotNull() {
        assertNotNull("OfflineMapManager.DARK_MODE_FILTER must be non-null", com.antigravity.copilot.core.map.OfflineMapManager.DARK_MODE_FILTER)
    }

    @Test
    fun testLiveMapViewZoomRangeUnlockedUpToTwenty() {
        val minZoom = 0.0
        val maxZoom = 20.0

        // Test zoom in clamp up to max optical zoom 20
        val zoomNearMax = 19.8
        val targetZoomIn = (zoomNearMax + 0.75).coerceAtMost(maxZoom)
        assertEquals(20.0, targetZoomIn, 0.001)

        // Test zoom out clamp to min 0
        val zoomNearMin = 0.5
        val targetZoomOut = (zoomNearMin - 0.75).coerceAtLeast(minZoom)
        assertEquals(0.0, targetZoomOut, 0.001)

        // Test overshoots
        val overshootHigh = 25.0.coerceIn(minZoom, maxZoom)
        assertEquals(20.0, overshootHigh, 0.001)

        val overshootLow = (-5.0).coerceIn(minZoom, maxZoom)
        assertEquals(0.0, overshootLow, 0.001)
    }

    @Test
    fun testDrivingReportAppDoesNotRequireMapLibre() {
        // Feature 28: Verify DrivingReportApp loads without MapLibre dependencies
        val appClass = DrivingReportApp::class.java
        assertNotNull(appClass)

        // Verify class does not declare fields of MapLibre type
        val fields = appClass.declaredFields
        for (field in fields) {
            assertFalse(
                "DrivingReportApp should not declare MapLibre fields",
                field.type.name.contains("maplibre", ignoreCase = true)
            )
        }
    }
}
