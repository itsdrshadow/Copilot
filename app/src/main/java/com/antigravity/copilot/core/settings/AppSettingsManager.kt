package com.antigravity.copilot.core.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.datastore.preferences.core.edit
import com.antigravity.copilot.core.designsystem.CockpitThemeMode
import com.antigravity.copilot.core.storage.SettingsDataStore
import com.antigravity.copilot.core.storage.appDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Enterprise Automotive Infotainment Settings Manager.
 * Persists app-wide configuration parameters and system preferences
 * across app restarts, vehicle reboots, and ROM updates using DataStore & SharedPreferences dual-backing.
 */
object AppSettingsManager {
    private const val TAG = "AppSettingsManager"
    private const val PREFS_NAME = "driving_report_settings_prefs"

    // General
    const val KEY_SCREEN_ALWAYS_ON = "screen_always_on"
    const val KEY_AUTO_MINIMIZE_DRAWER = "auto_minimize_drawer"
    const val KEY_LANGUAGE = "app_language"
    const val KEY_COCKPIT_THEME = "cockpit_theme"
    const val KEY_LAST_STABLE_THEME = "last_stable_cockpit_theme"
    const val KEY_THEME_APPEARANCE_MODE = "theme_appearance_mode"

    // Vehicle
    const val KEY_CAR_NAME = "car_name"
    const val KEY_FUEL_TYPE = "fuel_type"
    const val KEY_TANK_CAPACITY = "tank_capacity_liters"
    const val KEY_EXPECTED_MILEAGE = "expected_mileage_kmpl"

    // Tracking & Watchdog Thresholds
    const val KEY_AUTO_TRIP_DETECTION = "auto_trip_detection"
    const val KEY_DEFAULT_GEOFENCE_RADIUS = "default_geofence_radius"
    const val KEY_STATIONARY_SPEED_THRESHOLD = "stationary_speed_threshold"
    const val KEY_IDLE_DURATION_THRESHOLD = "idle_duration_threshold"
    const val KEY_ENGINE_OFF_DETECTION = "engine_off_detection"
    const val KEY_STATION_STOP_WATCHDOG = "station_stop_watchdog"
    const val KEY_STATION_DWELL_SECONDS = "station_dwell_seconds"
    const val KEY_MISSED_REFILL_THRESHOLD = "missed_refill_threshold"

    // Speed & Safety
    const val KEY_DYNAMIC_SPEED_LIMITS = "dynamic_speed_limits"
    const val KEY_OVERSPEED_ALERTS = "overspeed_alerts"
    const val KEY_NEAR_LIMIT_WARNING = "near_limit_warning"
    const val KEY_DRIVER_SCORING = "driver_scoring_enabled"

    // Maps
    const val KEY_MAP_SATELLITE_MODE = "map_satellite_mode"
    const val KEY_MAP_DARK_MODE = "map_dark_mode"
    const val KEY_MAP_BUILDING_FOOTPRINTS = "map_building_footprints"
    const val KEY_AUTO_DAY_NIGHT_SOLAR = "auto_day_night_solar"
    const val KEY_MEDIA_PILL_ENABLED = "media_pill_enabled"
    const val KEY_AUTO_FOREGROUND_ON_BOOT = "auto_foreground_on_boot"

    data class SettingsState(
        val screenAlwaysOn: Boolean = true,
        val autoMinimizeDrawer: Boolean = false,
        val language: String = "English (Automotive Standard)",
        val cockpitTheme: String = "apex",
        val themeAppearanceMode: String = "auto",
        val carName: String = "My Car",
        val fuelType: String = "Petrol",
        val tankCapacityLiters: Double = 48.0,
        val expectedMileageKmpl: Double = 18.0,
        val defaultGeofenceRadiusMeters: Float = 75.0f,
        val stationarySpeedThresholdKmh: Float = 2.5f,
        val idleDurationThresholdSeconds: Int = 30,
        val autoTripDetection: Boolean = true,
        val engineOffDetection: Boolean = true,
        val stationStopWatchdog: Boolean = true,
        val stationPromptDwellSeconds: Int = 30,
        val missedRefillThresholdLiters: Double = 3.0,
        val dynamicSpeedLimits: Boolean = true,
        val overspeedAlerts: Boolean = true,
        val nearLimitWarning: Boolean = true,
        val driverScoring: Boolean = true,
        val mapSatelliteMode: Boolean = true,
        val mapDarkMode: Boolean = true,
        val mapBuildingFootprints: Boolean = true,
        val autoDayNightSolar: Boolean = true,
        val mediaPillEnabled: Boolean = true,
        val autoForegroundOnBoot: Boolean = true
    ) {
        val cockpitThemeMode: CockpitThemeMode
            get() = CockpitThemeMode.fromId(cockpitTheme)

        val appearanceMode: com.antigravity.copilot.core.designsystem.ThemeAppearanceMode
            get() = com.antigravity.copilot.core.designsystem.ThemeAppearanceMode.fromId(themeAppearanceMode)
    }

    private val _settingsState = MutableStateFlow(SettingsState())
    val settingsState: StateFlow<SettingsState> = _settingsState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var dataStoreObservationJob: Job? = null
    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        reload(context)
        startDataStoreObservation(context)
        isInitialized = true
    }

    private fun startDataStoreObservation(context: Context) {
        dataStoreObservationJob?.cancel()
        dataStoreObservationJob = scope.launch {
            try {
                SettingsDataStore.getSettingsFlow(context).collect { newState ->
                    _settingsState.value = newState
                }
            } catch (e: Exception) {
                Log.w(TAG, "DataStore observation error: ${e.message}")
            }
        }
    }

    private fun SharedPreferences.getSafeFloat(key: String, defValue: Float): Float {
        return try {
            getFloat(key, defValue)
        } catch (_: ClassCastException) {
            try {
                getInt(key, defValue.toInt()).toFloat()
            } catch (_: ClassCastException) {
                try {
                    getString(key, null)?.toFloatOrNull() ?: defValue
                } catch (_: Throwable) {
                    defValue
                }
            }
        }
    }

    fun reload(context: Context) {
        val prefs = getPrefs(context)
        _settingsState.value = SettingsState(
            screenAlwaysOn = prefs.getBoolean(KEY_SCREEN_ALWAYS_ON, true),
            autoMinimizeDrawer = prefs.getBoolean(KEY_AUTO_MINIMIZE_DRAWER, false),
            language = prefs.getString(KEY_LANGUAGE, "English (Automotive Standard)") ?: "English",
            cockpitTheme = prefs.getString(KEY_COCKPIT_THEME, "apex")?.let { if (it.contains("glass", ignoreCase = true)) "apex" else it } ?: "apex",
            themeAppearanceMode = prefs.getString(KEY_THEME_APPEARANCE_MODE, "auto") ?: "auto",
            carName = prefs.getString(KEY_CAR_NAME, "My Car") ?: "My Car",
            fuelType = prefs.getString(KEY_FUEL_TYPE, "Petrol") ?: "Petrol",
            tankCapacityLiters = prefs.getSafeFloat(KEY_TANK_CAPACITY, 48.0f).toDouble(),
            expectedMileageKmpl = prefs.getSafeFloat(KEY_EXPECTED_MILEAGE, 18.0f).toDouble(),
            defaultGeofenceRadiusMeters = prefs.getSafeFloat(KEY_DEFAULT_GEOFENCE_RADIUS, 75.0f),
            stationarySpeedThresholdKmh = prefs.getSafeFloat(KEY_STATIONARY_SPEED_THRESHOLD, 2.5f),
            idleDurationThresholdSeconds = prefs.getInt(KEY_IDLE_DURATION_THRESHOLD, 30),
            autoTripDetection = prefs.getBoolean(KEY_AUTO_TRIP_DETECTION, true),
            engineOffDetection = prefs.getBoolean(KEY_ENGINE_OFF_DETECTION, true),
            stationStopWatchdog = prefs.getBoolean(KEY_STATION_STOP_WATCHDOG, true),
            stationPromptDwellSeconds = prefs.getInt(KEY_STATION_DWELL_SECONDS, 30),
            missedRefillThresholdLiters = prefs.getSafeFloat(KEY_MISSED_REFILL_THRESHOLD, 3.0f).toDouble(),
            dynamicSpeedLimits = prefs.getBoolean(KEY_DYNAMIC_SPEED_LIMITS, true),
            overspeedAlerts = prefs.getBoolean(KEY_OVERSPEED_ALERTS, true),
            nearLimitWarning = prefs.getBoolean(KEY_NEAR_LIMIT_WARNING, true),
            driverScoring = prefs.getBoolean(KEY_DRIVER_SCORING, true),
            mapSatelliteMode = prefs.getBoolean(KEY_MAP_SATELLITE_MODE, true),
            mapDarkMode = prefs.getBoolean(KEY_MAP_DARK_MODE, true),
            mapBuildingFootprints = prefs.getBoolean(KEY_MAP_BUILDING_FOOTPRINTS, true),
            autoDayNightSolar = prefs.getBoolean(KEY_AUTO_DAY_NIGHT_SOLAR, true),
            mediaPillEnabled = prefs.getBoolean(KEY_MEDIA_PILL_ENABLED, true),
            autoForegroundOnBoot = prefs.getBoolean(KEY_AUTO_FOREGROUND_ON_BOOT, true)
        )
        isInitialized = true
    }

    suspend fun migrateFromLegacyPreferences(context: Context) {
        val prefs = getPrefs(context)
        val fuelPrefs = context.getSharedPreferences("driving_report_fuel_prefs", Context.MODE_PRIVATE)

        val current = _settingsState.value
        val carName = if (prefs.contains(KEY_CAR_NAME)) prefs.getString(KEY_CAR_NAME, current.carName) ?: current.carName else current.carName
        val fuelType = if (prefs.contains(KEY_FUEL_TYPE)) prefs.getString(KEY_FUEL_TYPE, current.fuelType) ?: current.fuelType else current.fuelType
        val tankCap = if (prefs.contains(KEY_TANK_CAPACITY)) {
            prefs.getSafeFloat(KEY_TANK_CAPACITY, current.tankCapacityLiters.toFloat()).toDouble()
        } else if (fuelPrefs.contains("design_capacity")) {
            fuelPrefs.getSafeFloat("design_capacity", current.tankCapacityLiters.toFloat()).toDouble()
        } else current.tankCapacityLiters

        val expMil = if (prefs.contains(KEY_EXPECTED_MILEAGE)) {
            prefs.getSafeFloat(KEY_EXPECTED_MILEAGE, current.expectedMileageKmpl.toFloat()).toDouble()
        } else if (fuelPrefs.contains("baseline_vehicle_mileage")) {
            fuelPrefs.getSafeFloat("baseline_vehicle_mileage", current.expectedMileageKmpl.toFloat()).toDouble()
        } else current.expectedMileageKmpl

        val migratedState = current.copy(
            carName = carName,
            fuelType = fuelType,
            tankCapacityLiters = tankCap,
            expectedMileageKmpl = expMil
        )
        _settingsState.value = migratedState
        SettingsDataStore.saveAllSettings(context, migratedState)
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun updateScreenAlwaysOn(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SCREEN_ALWAYS_ON, enabled).apply()
        _settingsState.value = _settingsState.value.copy(screenAlwaysOn = enabled)
        scope.launch {
            context.appDataStore.edit { it[SettingsDataStore.KEY_SCREEN_ALWAYS_ON] = enabled }
        }
    }

    fun updateAutoMinimizeDrawer(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AUTO_MINIMIZE_DRAWER, enabled).apply()
        _settingsState.value = _settingsState.value.copy(autoMinimizeDrawer = enabled)
        scope.launch {
            context.appDataStore.edit { it[SettingsDataStore.KEY_AUTO_MINIMIZE_DRAWER] = enabled }
        }
    }

    fun updateVehiclePhysicalSpecs(
        context: Context,
        name: String,
        fuelType: String,
        tankCapacity: Double,
        expectedMileage: Double
    ) {
        val safeCapacity = tankCapacity.coerceIn(10.0, 200.0)
        val safeMileage = expectedMileage.coerceIn(4.0, 60.0)
        getPrefs(context).edit()
            .putString(KEY_CAR_NAME, name)
            .putString(KEY_FUEL_TYPE, fuelType)
            .putFloat(KEY_TANK_CAPACITY, safeCapacity.toFloat())
            .putFloat(KEY_EXPECTED_MILEAGE, safeMileage.toFloat())
            .apply()
        _settingsState.value = _settingsState.value.copy(
            carName = name,
            fuelType = fuelType,
            tankCapacityLiters = safeCapacity,
            expectedMileageKmpl = safeMileage
        )
        scope.launch {
            context.appDataStore.edit {
                it[SettingsDataStore.KEY_CAR_NAME] = name
                it[SettingsDataStore.KEY_FUEL_TYPE] = fuelType
                it[SettingsDataStore.KEY_TANK_CAPACITY] = safeCapacity
                it[SettingsDataStore.KEY_EXPECTED_MILEAGE] = safeMileage
            }
        }
    }

    fun updateTrackingThresholds(
        context: Context,
        geofenceRadius: Float,
        stationarySpeed: Float,
        idleDurationSeconds: Int
    ) {
        val safeRadius = geofenceRadius.coerceIn(50.0f, 2000.0f)
        val safeSpeed = stationarySpeed.coerceIn(1.0f, 10.0f)
        val safeIdle = idleDurationSeconds.coerceIn(10, 300)
        getPrefs(context).edit()
            .putFloat(KEY_DEFAULT_GEOFENCE_RADIUS, safeRadius)
            .putFloat(KEY_STATIONARY_SPEED_THRESHOLD, safeSpeed)
            .putInt(KEY_IDLE_DURATION_THRESHOLD, safeIdle)
            .apply()
        _settingsState.value = _settingsState.value.copy(
            defaultGeofenceRadiusMeters = safeRadius,
            stationarySpeedThresholdKmh = safeSpeed,
            idleDurationThresholdSeconds = safeIdle
        )
        scope.launch {
            context.appDataStore.edit {
                it[SettingsDataStore.KEY_DEFAULT_GEOFENCE_RADIUS] = safeRadius
                it[SettingsDataStore.KEY_STATIONARY_SPEED_THRESHOLD] = safeSpeed
                it[SettingsDataStore.KEY_IDLE_DURATION_THRESHOLD] = safeIdle
            }
        }
    }

    fun updateStationWatchdogParameters(
        context: Context,
        dwellSeconds: Int,
        missedRefillThreshold: Double
    ) {
        val safeDwell = dwellSeconds.coerceIn(15, 180)
        val safeMissed = missedRefillThreshold.coerceIn(1.0, 15.0)
        getPrefs(context).edit()
            .putInt(KEY_STATION_DWELL_SECONDS, safeDwell)
            .putFloat(KEY_MISSED_REFILL_THRESHOLD, safeMissed.toFloat())
            .apply()
        _settingsState.value = _settingsState.value.copy(
            stationPromptDwellSeconds = safeDwell,
            missedRefillThresholdLiters = safeMissed
        )
        scope.launch {
            context.appDataStore.edit {
                it[SettingsDataStore.KEY_STATION_DWELL_SECONDS] = safeDwell
                it[SettingsDataStore.KEY_MISSED_REFILL_THRESHOLD] = safeMissed
            }
        }
    }

    fun updateStationStopWatchdog(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_STATION_STOP_WATCHDOG, enabled).apply()
        _settingsState.value = _settingsState.value.copy(stationStopWatchdog = enabled)
        scope.launch {
            context.appDataStore.edit { it[SettingsDataStore.KEY_STATION_STOP_WATCHDOG] = enabled }
        }
    }

    fun updateAutoForegroundOnBoot(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AUTO_FOREGROUND_ON_BOOT, enabled).apply()
        _settingsState.value = _settingsState.value.copy(autoForegroundOnBoot = enabled)
        scope.launch {
            context.appDataStore.edit { it[SettingsDataStore.KEY_AUTO_FOREGROUND_ON_BOOT] = enabled }
        }
    }

    /**
     * Centralized theme switching.
     * Persists selection across reboots and ROM updates.
     */
    fun updateCockpitTheme(context: Context, mode: CockpitThemeMode) {
        getPrefs(context).edit()
            .putString(KEY_COCKPIT_THEME, mode.id)
            .putString(KEY_LAST_STABLE_THEME, mode.id)
            .apply()
        _settingsState.value = _settingsState.value.copy(cockpitTheme = mode.id)
        scope.launch {
            context.appDataStore.edit {
                it[SettingsDataStore.KEY_COCKPIT_THEME] = mode.id
                it[SettingsDataStore.KEY_LAST_STABLE_THEME] = mode.id
            }
        }
    }

    /**
     * Permanent Studio Theme Enforcement:
     * Finalizes and ensures Premium Cockpit is always active.
     */
    fun restorePremiumCockpit(context: Context) {
        getPrefs(context).edit()
            .putString(KEY_COCKPIT_THEME, CockpitThemeMode.PREMIUM.id)
            .putString(KEY_LAST_STABLE_THEME, CockpitThemeMode.PREMIUM.id)
            .apply()
        _settingsState.value = _settingsState.value.copy(cockpitTheme = CockpitThemeMode.PREMIUM.id)
        scope.launch {
            context.appDataStore.edit {
                it[SettingsDataStore.KEY_COCKPIT_THEME] = CockpitThemeMode.PREMIUM.id
                it[SettingsDataStore.KEY_LAST_STABLE_THEME] = CockpitThemeMode.PREMIUM.id
            }
        }
    }

    fun resetToDefaults(context: Context) {
        getPrefs(context).edit().clear().apply()
        _settingsState.value = SettingsState()
        scope.launch {
            SettingsDataStore.clear(context)
        }
    }
}
