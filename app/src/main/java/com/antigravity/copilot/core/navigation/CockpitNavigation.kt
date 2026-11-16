package com.antigravity.copilot.core.navigation

/**
 * Standardized Automotive Cockpit Navigation Contract.
 * Defines top-level navigation tabs, dialog destinations, and screen routing.
 */
enum class CockpitTab(val index: Int, val title: String) {
    COCKPIT(0, "Cockpit"),
    FULL_MAP(1, "Map"),
    TRIP_HISTORY(2, "Trips"),
    FUEL_HISTORY(3, "Fuels");

    companion object {
        fun fromIndex(index: Int): CockpitTab = entries.find { it.index == index } ?: COCKPIT
    }
}

sealed interface CockpitNavEvent {
    data class SelectTab(val tab: CockpitTab) : CockpitNavEvent
    data class OpenAppDrawer(val open: Boolean) : CockpitNavEvent
    data class OpenFuelEntryDialog(val open: Boolean) : CockpitNavEvent
    data class OpenSettingsDialog(val open: Boolean) : CockpitNavEvent
    object LaunchNavigationApp : CockpitNavEvent
}
