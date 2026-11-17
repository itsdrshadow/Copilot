package com.antigravity.copilot.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.copilot.core.designsystem.CockpitThemeMode
import com.antigravity.copilot.core.settings.AppSettingsManager.SettingsState
import com.antigravity.copilot.domain.repository.BackupRepository
import com.antigravity.copilot.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val backupRepository: BackupRepository
) : ViewModel() {

    val settings: StateFlow<SettingsState> = settingsRepository.settings

    private val _isBackupInProgress = MutableStateFlow(false)
    val isBackupInProgress: StateFlow<Boolean> = _isBackupInProgress.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)

    /** One-shot user messages, shown as toasts. */
    val messages: Flow<String> = _messages.receiveAsFlow()

    fun saveVehicleSpecs(name: String, fuelType: String, tankCapacityLiters: Double, expectedMileageKmpl: Double) {
        settingsRepository.updateVehicleSpecs(name, fuelType, tankCapacityLiters, expectedMileageKmpl)
        _messages.trySend("Vehicle configuration saved!")
    }

    fun selectTheme(mode: CockpitThemeMode) = settingsRepository.updateCockpitTheme(mode)

    fun selectAppearance(mode: com.antigravity.copilot.core.designsystem.ThemeAppearanceMode) =
        settingsRepository.updateThemeAppearanceMode(mode.id)

    fun setAutoForegroundOnBoot(enabled: Boolean) = settingsRepository.setAutoForegroundOnBoot(enabled)

    fun setScreenAlwaysOn(enabled: Boolean) = settingsRepository.setScreenAlwaysOn(enabled)

    fun setAutoMinimizeDrawer(enabled: Boolean) = settingsRepository.setAutoMinimizeDrawer(enabled)

    fun setStationStopPrompt(enabled: Boolean) = settingsRepository.setStationStopPrompt(enabled)

    /** Values are clamped by the settings store (15–180 s). */
    fun setStationPromptDwellSeconds(seconds: Int) = settingsRepository.setStationPromptDwellSeconds(seconds)

    /** Values are clamped by the settings store (1–10 km/h). */
    fun setStationarySpeedThreshold(kmh: Float) = settingsRepository.setStationarySpeedThreshold(kmh)

    /** Values are clamped by the settings store (50–2000 m). */
    fun setDefaultGeofenceRadius(meters: Float) = settingsRepository.setDefaultGeofenceRadius(meters)

    /** Runs in [viewModelScope], so closing the settings dialog mid-backup no longer cancels the backup. */
    fun backupNow() {
        if (_isBackupInProgress.value) return
        _isBackupInProgress.value = true
        viewModelScope.launch {
            try {
                val res = backupRepository.createFullBackup()
                val file = res.backupFile
                _messages.send(
                    if (res.isSuccess && file != null) {
                        "Backup saved: ${file.name} (${res.manifest?.tripCount} trips, ${res.manifest?.fuelEventCount} fuel refills)"
                    } else {
                        "Backup failed: ${res.errorMessage}"
                    }
                )
            } finally {
                _isBackupInProgress.value = false
            }
        }
    }
}
