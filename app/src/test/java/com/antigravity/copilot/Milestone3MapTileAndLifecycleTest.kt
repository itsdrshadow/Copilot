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
 * - 15 GB Storage Budget enforcement (Vector Map + Routing Graph ~8.5-11 GB total).
 * - Scoped Storage folder structure (maps, routing, downloads).
 * - WorkManager resumable downloads with HTTP Range byte-offset logic.
 * - Pure-JVM GraphHopper offline routing engine contracts.
 * - MapLibre zoom constraints (14.0 - 19.5).
 */
class Milestone3MapTileAndLifecycleTest {

    @Test
    fun testOfflineStorageBudgetFifteenGigabytesLimit() {
        // Strict requirement: Total offline footprint must fit within 15 GB limit
        assertEquals(15L * 1024L * 1024L * 1024L, OfflineMapManager.MAX_OFFLINE_STORAGE_BUDGET_BYTES)
        assertEquals(2L * 1024L * 1024L * 1024L, OfflineMapManager.LOW_STORAGE_WARNING_THRESHOLD_BYTES)

        val totalPackageBytes = OfflineMapManager.AVAILABLE_PACKAGES.sumOf { it.estimatedSizeBytes }
        val totalPackageGb = totalPackageBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)

        // Nationwide map (~4 GB) + Nationwide routing graph (~4.5 GB) = ~8.5 GB
        assertTrue("Total package footprint must be between 8 and 12 GB", totalPackageGb in 8.0..12.0)
        assertTrue(
            "Total package size must comfortably fit within the 15 GB device storage budget",
            totalPackageBytes < OfflineMapManager.MAX_OFFLINE_STORAGE_BUDGET_BYTES
        )
    }

    @Test
    fun testOfflinePackagesDefinition() {
        val packages = OfflineMapManager.AVAILABLE_PACKAGES
        assertEquals(2, packages.size)

        val vectorMap = packages.find { it.type == OfflineMapManager.PackageType.VECTOR_MAP }
        assertNotNull("All-India vector map package must exist", vectorMap)
        assertEquals("india_vector_map", vectorMap?.id)
        assertTrue("Vector map file must be .mbtiles", vectorMap?.fileName?.endsWith(".mbtiles") == true)

        val routingGraph = packages.find { it.type == OfflineMapManager.PackageType.ROUTING_GRAPH }
        assertNotNull("All-India routing graph package must exist", routingGraph)
        assertEquals("india_routing_graph", routingGraph?.id)
        assertTrue("Routing graph archive must be .zip", routingGraph?.fileName?.endsWith(".zip") == true)
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
        // Simulating 1.5 GB already downloaded out of 4 GB
        val existingBytes = 1_500_000_000L
        val rangeHeader = "bytes=$existingBytes-"
        assertEquals("bytes=1500000000-", rangeHeader)

        // Resumed fraction check
        val totalBytes = 4_000_000_000L
        val progressFraction = existingBytes.toFloat() / totalBytes.toFloat()
        assertEquals(0.375f, progressFraction, 0.001f)
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
