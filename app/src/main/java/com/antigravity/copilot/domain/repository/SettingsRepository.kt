package com.antigravity.copilot.domain.repository

import com.antigravity.copilot.core.designsystem.CockpitThemeMode
import com.antigravity.copilot.core.settings.AppSettingsManager.SettingsState
import kotlinx.coroutines.flow.StateFlow

/** User-facing launcher settings. */
interface SettingsRepository {
    val settings: StateFlow<SettingsState>
    fun updateVehicleSpecs(name: String, fuelType: String, tankCapacityLiters: Double, expectedMileageKmpl: Double)
    fun updateCockpitTheme(mode: CockpitThemeMode)
    fun setAutoForegroundOnBoot(enabled: Boolean)
}
