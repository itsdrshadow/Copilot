package com.antigravity.copilot.data.repository

import android.content.Context
import com.antigravity.copilot.core.designsystem.CockpitThemeMode
import com.antigravity.copilot.core.settings.AppSettingsManager
import com.antigravity.copilot.core.settings.AppSettingsManager.SettingsState
import com.antigravity.copilot.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : SettingsRepository {

    init {
        AppSettingsManager.init(context)
    }

    override val settings: StateFlow<SettingsState> get() = AppSettingsManager.settingsState

    override fun updateVehicleSpecs(name: String, fuelType: String, tankCapacityLiters: Double, expectedMileageKmpl: Double) =
        AppSettingsManager.updateVehiclePhysicalSpecs(context, name, fuelType, tankCapacityLiters, expectedMileageKmpl)

    override fun updateCockpitTheme(mode: CockpitThemeMode) = AppSettingsManager.updateCockpitTheme(context, mode)
    override fun updateThemeAppearanceMode(modeId: String) = AppSettingsManager.updateThemeAppearanceMode(context, modeId)

    override fun setAutoForegroundOnBoot(enabled: Boolean) = AppSettingsManager.updateAutoForegroundOnBoot(context, enabled)

    override fun setScreenAlwaysOn(enabled: Boolean) = AppSettingsManager.updateScreenAlwaysOn(context, enabled)

    override fun setAutoMinimizeDrawer(enabled: Boolean) = AppSettingsManager.updateAutoMinimizeDrawer(context, enabled)

    override fun setStationStopPrompt(enabled: Boolean) = AppSettingsManager.updateStationStopWatchdog(context, enabled)

    override fun setStationPromptDwellSeconds(seconds: Int) {
        val s = settings.value
        AppSettingsManager.updateStationWatchdogParameters(context, seconds, s.missedRefillThresholdLiters)
    }

    override fun setStationarySpeedThreshold(kmh: Float) {
        val s = settings.value
        AppSettingsManager.updateTrackingThresholds(context, s.defaultGeofenceRadiusMeters, kmh, s.idleDurationThresholdSeconds)
    }

    override fun setDefaultGeofenceRadius(meters: Float) {
        val s = settings.value
        AppSettingsManager.updateTrackingThresholds(context, meters, s.stationarySpeedThresholdKmh, s.idleDurationThresholdSeconds)
    }
}
