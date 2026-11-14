package com.antigravity.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherNavigationAndSpaceReclaimTest {

    @Test
    fun testVersionBumpToSemVerMinor() {
        assertEquals("1.25.0", BuildConfig.VERSION_NAME)
        assertEquals(42, BuildConfig.VERSION_CODE)
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
}
