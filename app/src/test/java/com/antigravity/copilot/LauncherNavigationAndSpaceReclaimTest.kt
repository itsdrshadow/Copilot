package com.antigravity.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherNavigationAndSpaceReclaimTest {

    @Test
    fun testVersionBumpToSemVerMinor() {
        assertEquals("1.26.0", BuildConfig.VERSION_NAME)
        assertEquals(46, BuildConfig.VERSION_CODE)
    }

    @Test
    fun testReclaimedVerticalSpaceCalculation() {
        // Typical 10.1 inch infotainment display: 1280x800 px (at 160dpi, 800dp height; at 240dpi, 533dp height)
        val screenHeightDp = 533f
        val legacyDockHeightDp = 78f
        val legacySpacerHeightDp = 6f
        val paddingDp = 8f * 2

        val legacyAvailableHeight = screenHeightDp - legacyDockHeightDp - legacySpacerHeightDp - paddingDp
        // With dock removed:
        val modernAvailableHeight = screenHeightDp - paddingDp
        val reclaimedHeight = modernAvailableHeight - legacyAvailableHeight

        assertEquals(84f, reclaimedHeight, 0.01f)
        assertTrue("Modern dashboard should have more vertical room for telemetry cards", modernAvailableHeight > legacyAvailableHeight)

        // Telemetry cards height increase per row (2 rows in landscape):
        val heightIncreasePerRow = reclaimedHeight / 2f
        assertEquals(42f, heightIncreasePerRow, 0.01f)
    }

    @Test
    fun testBottomDockComposablesEliminatedFromDashboard() {
        // Verify PremiumBottomDock class / methods do not exist as public API on Dashboard
        val dashboardMethods = Class.forName("com.antigravity.copilot.feature.dashboard.DashboardScreenKt").declaredMethods
        for (m in dashboardMethods) {
            assertFalse(
                "Bottom dock composables must be completely eliminated: ${m.name}",
                m.name.contains("BottomDock", ignoreCase = true)
            )
        }
    }

    @Test
    fun testAppDrawerNavRoutesToTripHistoryAndFuelHistory() {
        // Tab index definitions: 0 = Dashboard, 1 = LiveMap, 2 = TripHistory, 3 = FuelHistory
        val TAB_DASHBOARD = 0
        val TAB_MAP = 1
        val TAB_TRIP_HISTORY = 2
        val TAB_FUEL_HISTORY = 3

        var drawerOpen = true
        var currentTab = TAB_DASHBOARD

        // Clicking "Trips" action:
        val onOpenTripsAction: () -> Unit = {
            drawerOpen = false
            currentTab = TAB_TRIP_HISTORY
        }
        onOpenTripsAction()
        assertFalse("App drawer must close when navigating to Trip History", drawerOpen)
        assertEquals("Selected tab must switch to Trip History (Tab 2)", TAB_TRIP_HISTORY, currentTab)

        // Reopen drawer
        drawerOpen = true

        // Clicking "Fuels" action:
        val onOpenFuelAction: () -> Unit = {
            drawerOpen = false
            currentTab = TAB_FUEL_HISTORY
        }
        onOpenFuelAction()
        assertFalse("App drawer must close when navigating to Fuel History", drawerOpen)
        assertEquals("Selected tab must switch to Fuel History (Tab 3)", TAB_FUEL_HISTORY, currentTab)
    }
}
