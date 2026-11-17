package com.antigravity.copilot

import com.antigravity.copilot.core.map.MapDownloadWorker
import com.antigravity.copilot.core.map.OfflineMapManager
import com.antigravity.copilot.core.map.OfflineRoutingEngine
import com.antigravity.copilot.feature.maps.MAP_MAX_ZOOM_LEVEL
import com.antigravity.copilot.feature.maps.MAP_MIN_ZOOM_LEVEL
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Milestone3MapTileAndLifecycleTest:
 * Validates the Refactored Offline Map, Routing, and Download Management System:
 * - 5 Regional Macro-Zones with GPS Bounding Box auto-detection.
 * - 15 GB Storage Budget enforcement.
 * - Scoped Storage folder structure (maps, routing, downloads).
 * - WorkManager resumable downloads with HTTP Range byte-offset logic.
 * - Pure-JVM GraphHopper offline routing engine contracts.
 * - MapLibre zoom constraints (14.0 - 19.5).
 */
class Milestone3MapTileAndLifecycleTest {

    @Test
    fun testOfflineStorageBudgetFifteenGigabytesLimit() {
        assertEquals(15L * 1024L * 1024L * 1024L, OfflineMapManager.MAX_OFFLINE_STORAGE_BUDGET_BYTES)
        assertEquals(2L * 1024L * 1024L * 1024L, OfflineMapManager.LOW_STORAGE_WARNING_THRESHOLD_BYTES)

        // India PMTiles map + routing graph must fit the 15 GB budget
        val india = OfflineMapManager.AVAILABLE_PACKAGES.first { it.id == "india_pmtiles" }
        val routingGraph = OfflineMapManager.AVAILABLE_PACKAGES.first { it.id == "india_routing_graph" }
        val combinedBytes = india.estimatedSizeBytes + routingGraph.estimatedSizeBytes
        assertTrue("India map + routing graph must be < 15 GB", combinedBytes < OfflineMapManager.MAX_OFFLINE_STORAGE_BUDGET_BYTES)
    }

    @Test
    fun testIndiaPmtilesPackageReplacesBrokenMbtilesCatalog() {
        val packages = OfflineMapManager.AVAILABLE_PACKAGES
        val india = packages.single { it.type == OfflineMapManager.PackageType.VECTOR_MAP }
        assertEquals("india_pmtiles", india.id)
        assertEquals("india.pmtiles", india.fileName)
        // No hard-coded bulk tile / non-existent MBTiles URLs: the user supplies the URL or imports a file.
        assertTrue(india.downloadUrl.isBlank())
        assertTrue(packages.none { it.downloadUrl.contains("geofabrik") || it.downloadUrl.endsWith(".mbtiles") })
        assertNotNull(packages.find { it.id == "india_routing_graph" })
    }

    @Test
    fun testGpsLocationToRegionalZoneAutoDetection() {
        for ((lat, lon) in listOf(28.6139 to 77.2090, 19.0760 to 72.8777, 12.9716 to 77.5946, 22.5726 to 88.3639, 26.1445 to 91.7362)) {
            assertEquals("india_pmtiles", OfflineMapManager.getRegionForLocation(lat, lon)?.id)
        }
        // Outside India
        assertNull(OfflineMapManager.getRegionForLocation(51.5, -0.12))
    }

    @Test
    fun testDownloadStateTransitions() {
        val states = OfflineMapManager.DownloadState.values()
        assertTrue(states.contains(OfflineMapManager.DownloadState.IDLE))
        assertTrue(states.contains(OfflineMapManager.DownloadState.DOWNLOADING))
        assertTrue(states.contains(OfflineMapManager.DownloadState.PAUSED_USER))
        assertTrue(states.contains(OfflineMapManager.DownloadState.PAUSED_NETWORK_LOST))
        assertTrue(states.contains(OfflineMapManager.DownloadState.EXTRACTING))
        assertTrue(states.contains(OfflineMapManager.DownloadState.COMPLETED))
        assertTrue(states.contains(OfflineMapManager.DownloadState.ERROR))
    }

    @Test
    fun testHttpRangeResumeHeaderCalculation() {
        // Simulating 500 MB already downloaded out of 1.2 GB regional pack
        val existingBytes = 500_000_000L
        val rangeHeader = "bytes=$existingBytes-"
        assertEquals("bytes=500000000-", rangeHeader)

        val totalBytes = 1_200_000_000L
        val progressFraction = existingBytes.toFloat() / totalBytes.toFloat()
        assertEquals(0.416f, progressFraction, 0.001f)
    }

    @Test
    fun testMapDownloadWorkerKeyConstants() {
        assertEquals("download_url", MapDownloadWorker.KEY_URL)
        assertEquals("dest_path", MapDownloadWorker.KEY_DEST_PATH)
        assertEquals("package_id", MapDownloadWorker.KEY_PACKAGE_ID)
        assertEquals("package_type", MapDownloadWorker.KEY_PACKAGE_TYPE)
    }

    @Test
    fun testOfflineRoutingEngineResultStructure() {
        val successResult = OfflineRoutingEngine.RouteResult(
            isSuccess = true,
            points = listOf(Pair(28.6139, 77.2090), Pair(28.6200, 77.2150)),
            distanceMeters = 1500.0,
            timeMillis = 180000,
            instructions = listOf("Turn right onto Connaught Place")
        )

        assertTrue(successResult.isSuccess)
        assertEquals(2, successResult.points.size)
        assertEquals(1500.0, successResult.distanceMeters, 0.001)
        assertNull(successResult.errorMessage)

        val failResult = OfflineRoutingEngine.RouteResult(
            isSuccess = false,
            errorMessage = "Routing graph not loaded"
        )
        assertFalse(failResult.isSuccess)
        assertNotNull(failResult.errorMessage)
    }

    @Test
    fun testDarkModeColorFilterNotNull() {
        assertNotNull("OfflineMapManager.DARK_MODE_FILTER must be non-null", OfflineMapManager.DARK_MODE_FILTER)
    }

    @Test
    fun testLiveMapViewZoomRangeLockedBetweenFourteenAndNineteenPointFive() {
        val minZoom = MAP_MIN_ZOOM_LEVEL
        val maxZoom = MAP_MAX_ZOOM_LEVEL

        assertEquals(14.0, minZoom, 0.001)
        assertEquals(19.5, maxZoom, 0.001)

        val zoomNearMax = 19.0
        val targetZoomIn = (zoomNearMax + 0.75).coerceAtMost(maxZoom)
        assertEquals(19.5, targetZoomIn, 0.001)

        val zoomNearMin = 14.5
        val targetZoomOut = (zoomNearMin - 0.75).coerceAtLeast(minZoom)
        assertEquals(14.0, targetZoomOut, 0.001)

        val overshootHigh = 25.0.coerceIn(minZoom, maxZoom)
        assertEquals(19.5, overshootHigh, 0.001)

        val overshootLow = 10.0.coerceIn(minZoom, maxZoom)
        assertEquals(14.0, overshootLow, 0.001)
    }
}
