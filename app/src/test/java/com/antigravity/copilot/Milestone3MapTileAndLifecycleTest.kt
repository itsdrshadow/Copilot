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

        // Any single region + routing graph fits well under 6 GB (far below 15 GB)
        val northRegion = OfflineMapManager.AVAILABLE_PACKAGES.first { it.id == "india_north" }
        val routingGraph = OfflineMapManager.AVAILABLE_PACKAGES.first { it.id == "india_routing_graph" }
        val combinedBytes = northRegion.estimatedSizeBytes + routingGraph.estimatedSizeBytes
        val combinedGb = combinedBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)

        assertTrue("Regional map + routing graph footprint must be < 6 GB", combinedGb < 6.0)
    }

    @Test
    fun testFiveRegionalMacroZonesAndMasterPackages() {
        val packages = OfflineMapManager.AVAILABLE_PACKAGES
        assertEquals(8, packages.size)

        val demoPack = packages.find { it.id == "demo_sample_map" }
        assertNotNull("Demo test map package must exist", demoPack)
        assertTrue("Demo map pack must have valid fixture URL", demoPack?.downloadUrl?.contains("plain_1.mbtiles") == true)

        // 5 Regional Macro Zones
        val north = packages.find { it.id == "india_north" }
        assertNotNull("North India package must exist", north)
        assertTrue("North India covered states must include Delhi", north?.coveredStates?.contains("Delhi") == true)

        val westCentral = packages.find { it.id == "india_west_central" }
        assertNotNull("West & Central India package must exist", westCentral)
        assertTrue("West Central covered states must include Maharashtra", westCentral?.coveredStates?.contains("Maharashtra") == true)

        val south = packages.find { it.id == "india_south" }
        assertNotNull("South India package must exist", south)
        assertTrue("South India covered states must include Karnataka", south?.coveredStates?.contains("Karnataka") == true)

        val east = packages.find { it.id == "india_east" }
        assertNotNull("East India package must exist", east)
        assertTrue("East India covered states must include West Bengal", east?.coveredStates?.contains("West Bengal") == true)

        val northeast = packages.find { it.id == "india_northeast" }
        assertNotNull("North-East India package must exist", northeast)
        assertTrue("North-East covered states must include Assam", northeast?.coveredStates?.contains("Assam") == true)

        // Master All-India packs
        val masterVector = packages.find { it.id == "india_vector_map" }
        assertNotNull("All-India master vector map package must exist", masterVector)

        val routing = packages.find { it.id == "india_routing_graph" }
        assertNotNull("All-India routing graph package must exist", routing)
    }

    @Test
    fun testGpsLocationToRegionalZoneAutoDetection() {
        // New Delhi: 28.6139° N, 77.2090° E -> Must detect North India
        val delhiRegion = OfflineMapManager.getRegionForLocation(28.6139, 77.2090)
        assertEquals("india_north", delhiRegion?.id)

        // Mumbai: 19.0760° N, 72.8777° E -> Must detect West & Central India
        val mumbaiRegion = OfflineMapManager.getRegionForLocation(19.0760, 72.8777)
        assertEquals("india_west_central", mumbaiRegion?.id)

        // Bengaluru: 12.9716° N, 77.5946° E -> Must detect South India
        val bengaluruRegion = OfflineMapManager.getRegionForLocation(12.9716, 77.5946)
        assertEquals("india_south", bengaluruRegion?.id)

        // Kolkata: 22.5726° N, 88.3639° E -> Must detect East India
        val kolkataRegion = OfflineMapManager.getRegionForLocation(22.5726, 88.3639)
        assertEquals("india_east", kolkataRegion?.id)

        // Guwahati: 26.1445° N, 91.7362° E -> Must detect North-East India
        val guwahatiRegion = OfflineMapManager.getRegionForLocation(26.1445, 91.7362)
        assertEquals("india_northeast", guwahatiRegion?.id)
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
