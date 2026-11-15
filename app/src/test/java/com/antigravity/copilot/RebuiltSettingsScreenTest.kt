package com.antigravity.copilot

import com.antigravity.copilot.core.settings.AppSettingsManager
import com.antigravity.copilot.feature.settings.SettingsTab
import org.junit.Assert.*
import org.junit.Test

class RebuiltSettingsScreenTest {

    @Test
    fun testSettingsTabsCountAndMembers() {
        val tabs = SettingsTab.values()
        assertEquals("Only 5 active tabs should remain in settings", 5, tabs.size)

        val tabNames = tabs.map { it.name }
        assertTrue(tabNames.contains("GENERAL"))
        assertTrue(tabNames.contains("VEHICLE"))
        assertTrue(tabNames.contains("GEOFENCES"))
        assertTrue(tabNames.contains("DATA_STORAGE"))
        assertTrue(tabNames.contains("ABOUT"))

        // Removed tabs
        assertFalse(tabNames.contains("APPEARANCE"))
        assertFalse(tabNames.contains("TRACKING"))
        assertFalse(tabNames.contains("MAPS"))
        assertFalse(tabNames.contains("SPEED_SAFETY"))
    }

    @Test
    fun testBackgroundDefaultsAlwaysActive() {
        val defaultState = AppSettingsManager.SettingsState()

        // Tracking defaults: always on
        assertTrue(defaultState.autoTripDetection)
        assertTrue(defaultState.engineOffDetection)
        assertTrue(defaultState.stationStopWatchdog)

        // Maps defaults: always on
        assertTrue(defaultState.mapSatelliteMode)
        assertTrue(defaultState.mapDarkMode)
        assertTrue(defaultState.mapBuildingFootprints)

        // Speed & safety defaults: always on
        assertTrue(defaultState.dynamicSpeedLimits)
        assertTrue(defaultState.overspeedAlerts)
        assertTrue(defaultState.nearLimitWarning)
        assertTrue(defaultState.driverScoring)

        // Screen display: always on
        assertTrue(defaultState.screenAlwaysOn)
    }
}
