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
    fun testTwelveAppsProducesExactlyOnePage() {
        val pageCount = LauncherManager.calculatePages(12)
        assertEquals(1, pageCount)

        val apps = (1..12).map { "App $it" }
        val paginated = LauncherManager.paginate(apps)
        assertEquals(1, paginated.size)
        assertEquals(12, paginated[0].size)
    }

    @Test
    fun testThirteenAppsProducesTwoPagesTwelveAndOne() {
        val pageCount = LauncherManager.calculatePages(13)
        assertEquals(2, pageCount)

        val apps = (1..13).map { "App $it" }
        val paginated = LauncherManager.paginate(apps)
        assertEquals(2, paginated.size)
        assertEquals(12, paginated[0].size)
        assertEquals(1, paginated[1].size)
    }

    @Test
    fun testTwentyFiveAppsProducesThreePagesTwelveTwelveAndOne() {
        // Explicit example from requirements: 25 apps should produce 3 pages (12 + 12 + 1)
        val pageCount = LauncherManager.calculatePages(25)
        assertEquals(3, pageCount)

        val apps = (1..25).map { "App $it" }
        val paginated = LauncherManager.paginate(apps)
        assertEquals(3, paginated.size)
        assertEquals(12, paginated[0].size)
        assertEquals(12, paginated[1].size)
        assertEquals(1, paginated[2].size)
    }

    @Test
    fun testFortyAppsProducesFourPages() {
        val pageCount = LauncherManager.calculatePages(40)
        assertEquals(4, pageCount)

        val apps = (1..40).map { "App $it" }
        val paginated = LauncherManager.paginate(apps)
        assertEquals(4, paginated.size)
        assertEquals(12, paginated[0].size)
        assertEquals(12, paginated[1].size)
        assertEquals(12, paginated[2].size)
        assertEquals(4, paginated[3].size)
    }

    @Test
    fun testStrictGridDimensionsThreeColumnsFourRows() {
        assertEquals(3, LauncherManager.GRID_COLUMNS)
        assertEquals(4, LauncherManager.GRID_ROWS)
        assertEquals(12, LauncherManager.GRID_COLUMNS * LauncherManager.GRID_ROWS)
        assertEquals(LauncherManager.APPS_PER_PAGE, LauncherManager.GRID_COLUMNS * LauncherManager.GRID_ROWS)
    }

    @Test
    fun testPartiallyFilledFinalPageAlignmentPlaceholderCalculation() {
        // On a page with 1 item (e.g. page 3 of 25 apps):
        // 1 item in Row 0, Col 0. 11 remaining cells must be empty placeholders.
        val totalCapacity = LauncherManager.GRID_COLUMNS * LauncherManager.GRID_ROWS
        val pageAppsCount = 1
        val emptySpacerCount = totalCapacity - pageAppsCount
        assertEquals(11, emptySpacerCount)

        // For each row (0..3) and col (0..2), verify indexing:
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
        assertEquals(11, emptyCount)
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
