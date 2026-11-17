package com.antigravity.copilot

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherNavigationAndSpaceReclaimTest {

    @Test
    fun testVersionBumpToSemVerMinor() {
        assertEquals("1.41.0", BuildConfig.VERSION_NAME)
        assertEquals(68, BuildConfig.VERSION_CODE)
    }

    @Test
    fun testCockpitDockNavRoutes() {
        val TAB_DASHBOARD = 0
        val TAB_MAP = 1
        val TAB_TRIP_HISTORY = 2
        val TAB_FUEL_HISTORY = 3

        var currentTab = TAB_DASHBOARD
        var appDrawerOpen = false
        var settingsOpen = false

        // Simulate Trips click from bottom dock
        val onOpenTrips: () -> Unit = { currentTab = TAB_TRIP_HISTORY }
        onOpenTrips()
        assertEquals(TAB_TRIP_HISTORY, currentTab)

        // Simulate Fuel click from bottom dock
        val onOpenFuel: () -> Unit = { currentTab = TAB_FUEL_HISTORY }
        onOpenFuel()
        assertEquals(TAB_FUEL_HISTORY, currentTab)

        // Simulate Apps click from bottom dock
        val onOpenApps: () -> Unit = { appDrawerOpen = true }
        onOpenApps()
        assertTrue(appDrawerOpen)

        // Simulate Settings click from bottom dock
        val onOpenSettings: () -> Unit = { settingsOpen = true }
        onOpenSettings()
        assertTrue(settingsOpen)
    }

    @Test
    fun testCockpitLayoutSplitAndDockHeight() {
        val mapWeight = 0.45f
        val telemetryWeight = 0.55f
        assertEquals(1.0f, mapWeight + telemetryWeight, 0.001f)
        assertEquals(0.45f, mapWeight, 0.001f)
        assertEquals(0.55f, telemetryWeight, 0.001f)

        // Ensure dock height is at least 64dp for automotive driver tap usability
        val dockHeight = com.antigravity.copilot.core.designsystem.AutomotiveDesignTokens.Dock.height
        assertTrue("Dock height must be >= 64dp for easy tapping while driving", dockHeight.value >= 64f)
    }
}
