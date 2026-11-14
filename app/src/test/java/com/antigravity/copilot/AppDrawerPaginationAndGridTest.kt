package com.antigravity.copilot

import com.antigravity.copilot.core.model.AppDrawerItem
import com.antigravity.copilot.feature.launcher.LauncherManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDrawerPaginationAndGridTest {

    @Test
    fun testZeroAppsCalculatesOneEmptyPage() {
        val pageCount = LauncherManager.calculatePages(0)
        assertEquals(1, pageCount)

        val paginated = LauncherManager.paginate<String>(emptyList())
        assertTrue(paginated.isEmpty())
    }

    @Test
    fun testOneAppProducesOnePage() {
        val pageCount = LauncherManager.calculatePages(1)
        assertEquals(1, pageCount)

        val apps = listOf("App 1")
        val paginated = LauncherManager.paginate(apps)
        assertEquals(1, paginated.size)
        assertEquals(1, paginated[0].size)
    }

    @Test
    fun testThirtyAppsProducesExactlyOnePage() {
        val pageCount = LauncherManager.calculatePages(30)
        assertEquals(1, pageCount)

        val apps = (1..30).map { "App $it" }
        val paginated = LauncherManager.paginate(apps)
        assertEquals(1, paginated.size)
        assertEquals(30, paginated[0].size)
    }

    @Test
    fun testThirtyOneAppsProducesTwoPagesThirtyAndOne() {
        val pageCount = LauncherManager.calculatePages(31)
        assertEquals(2, pageCount)

        val apps = (1..31).map { "App $it" }
        val paginated = LauncherManager.paginate(apps)
        assertEquals(2, paginated.size)
        assertEquals(30, paginated[0].size)
        assertEquals(1, paginated[1].size)
    }

    @Test
    fun testSixtyFiveAppsProducesThreePagesThirtyThirtyAndFive() {
        // 65 apps should produce 3 pages (30 + 30 + 5) in 6x5 grid
        val pageCount = LauncherManager.calculatePages(65)
        assertEquals(3, pageCount)

        val apps = (1..65).map { "App $it" }
        val paginated = LauncherManager.paginate(apps)
        assertEquals(3, paginated.size)
        assertEquals(30, paginated[0].size)
        assertEquals(30, paginated[1].size)
        assertEquals(5, paginated[2].size)
    }

    @Test
    fun testOneHundredAppsProducesFourPages() {
        val pageCount = LauncherManager.calculatePages(100)
        assertEquals(4, pageCount)

        val apps = (1..100).map { "App $it" }
        val paginated = LauncherManager.paginate(apps)
        assertEquals(4, paginated.size)
        assertEquals(30, paginated[0].size)
        assertEquals(30, paginated[1].size)
        assertEquals(30, paginated[2].size)
        assertEquals(10, paginated[3].size)
    }

    @Test
    fun testStrictGridDimensionsSixColumnsFiveRows() {
        assertEquals(6, LauncherManager.GRID_COLUMNS)
        assertEquals(5, LauncherManager.GRID_ROWS)
        assertEquals(30, LauncherManager.GRID_COLUMNS * LauncherManager.GRID_ROWS)
        assertEquals(LauncherManager.APPS_PER_PAGE, LauncherManager.GRID_COLUMNS * LauncherManager.GRID_ROWS)
    }

    @Test
    fun testPartiallyFilledFinalPageAlignmentPlaceholderCalculation() {
        // On a page with 1 item (e.g. page 3 of 65 apps):
        // 1 item in Row 0, Col 0. 29 remaining cells must be empty placeholders.
        val totalCapacity = LauncherManager.GRID_COLUMNS * LauncherManager.GRID_ROWS
        val pageAppsCount = 1
        val emptySpacerCount = totalCapacity - pageAppsCount
        assertEquals(29, emptySpacerCount)

        // For each row (0..4) and col (0..5), verify indexing:
        var filledCount = 0
        var emptyCount = 0
        for (r in 0 until LauncherManager.GRID_ROWS) {
            for (c in 0 until LauncherManager.GRID_COLUMNS) {
                val index = r * LauncherManager.GRID_COLUMNS + c
                if (index < pageAppsCount) {
                    filledCount++
                } else {
                    emptyCount++
                }
            }
        }
        assertEquals(1, filledCount)
        assertEquals(29, emptyCount)
    }

    @Test
    fun testAppDrawerDeduplicationEliminatesDuplicates() {
        val rawApps = listOf(
            AppDrawerItem("com.google.maps", ".MapsActivity", "Google Maps"),
            AppDrawerItem("com.google.maps", ".MapsActivity", "Google Maps"), // Duplicate
            AppDrawerItem("com.spotify.music", ".MainActivity", "Spotify"),
            AppDrawerItem("com.android.settings", ".Settings", "Settings"),
            AppDrawerItem("com.spotify.music", ".MainActivity", "Spotify")  // Duplicate
        )

        val distinctApps = rawApps.distinctBy { "${it.packageName}/${it.activityName}" }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })

        assertEquals(3, distinctApps.size)
        assertEquals("Google Maps", distinctApps[0].label)
        assertEquals("Settings", distinctApps[1].label)
        assertEquals("Spotify", distinctApps[2].label)
    }

    @Test
    fun testSearchFilteringAndPagination() {
        val apps = listOf(
            AppDrawerItem("com.google.maps", ".MapsActivity", "Google Maps"),
            AppDrawerItem("com.waze", ".MainActivity", "Waze Navigation"),
            AppDrawerItem("com.spotify.music", ".MainActivity", "Spotify"),
            AppDrawerItem("com.google.android.youtube", ".HomeActivity", "YouTube")
        )

        val query = "nav"
        val filtered = apps.filter { it.label.lowercase().contains(query) || it.packageName.lowercase().contains(query) }
        assertEquals(1, filtered.size)
        assertEquals("Waze Navigation", filtered[0].label)

        val queryMap = "map"
        val filteredMap = apps.filter { it.label.lowercase().contains(queryMap) || it.packageName.lowercase().contains(queryMap) }
        assertEquals(1, filteredMap.size)
        assertEquals("Google Maps", filteredMap[0].label)
    }
}
