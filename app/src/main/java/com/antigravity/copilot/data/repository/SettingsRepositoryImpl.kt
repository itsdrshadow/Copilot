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

    override fun setAutoForegroundOnBoot(enabled: Boolean) = AppSettingsManager.updateAutoForegroundOnBoot(context, enabled)
}
