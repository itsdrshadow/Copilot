package com.antigravity.copilot.domain.repository

import com.antigravity.copilot.core.designsystem.CockpitThemeMode
import com.antigravity.copilot.core.settings.AppSettingsManager.SettingsState
import kotlinx.coroutines.flow.StateFlow

/** User-facing launcher settings. */
interface SettingsRepository {
    val settings: StateFlow<SettingsState>
    fun updateVehicleSpecs(name: String, fuelType: String, tankCapacityLiters: Double, expectedMileageKmpl: Double)
    fun updateCockpitTheme(mode: CockpitThemeMode)
    fun updateAccentColor(accentId: String)
    fun setAutoForegroundOnBoot(enabled: Boolean)
    fun setScreenAlwaysOn(enabled: Boolean)
    fun setAutoMinimizeDrawer(enabled: Boolean)
    fun setStationStopPrompt(enabled: Boolean)
    fun setStationPromptDwellSeconds(seconds: Int)
    fun setStationarySpeedThreshold(kmh: Float)
    fun setDefaultGeofenceRadius(meters: Float)
}
