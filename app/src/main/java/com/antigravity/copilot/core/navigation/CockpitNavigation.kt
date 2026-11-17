package com.antigravity.copilot.core.navigation

/**
 * Standardized Automotive Cockpit Navigation Contract.
 * Defines the top-level navigation tabs.
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
