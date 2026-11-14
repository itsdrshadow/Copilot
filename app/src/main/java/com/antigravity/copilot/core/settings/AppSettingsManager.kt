package com.antigravity.copilot.core.settings

import android.content.Context
import android.content.SharedPreferences
import com.antigravity.copilot.core.designsystem.CockpitThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Enterprise Automotive Infotainment Settings Manager.
 * Persists app-wide configuration parameters and system preferences
 * across app restarts, vehicle reboots, and ROM updates.
 */
object AppSettingsManager {
    private const val PREFS_NAME = "driving_report_settings_prefs"

    // General
    private const val KEY_SCREEN_ALWAYS_ON = "screen_always_on"
    private const val KEY_AUTO_MINIMIZE_DRAWER = "auto_minimize_drawer"
    private const val KEY_LANGUAGE = "app_language"
    private const val KEY_COCKPIT_THEME = "cockpit_theme"
    private const val KEY_LAST_STABLE_THEME = "last_stable_cockpit_theme"
    private const val KEY_THEME_APPEARANCE_MODE = "theme_appearance_mode"

    // Vehicle
    private const val KEY_CAR_NAME = "car_name"
    private const val KEY_FUEL_TYPE = "fuel_type"
    private const val KEY_TANK_CAPACITY = "tank_capacity_liters"
    private const val KEY_EXPECTED_MILEAGE = "expected_mileage_kmpl"

    // Tracking & Watchdog Thresholds
    private const val KEY_AUTO_TRIP_DETECTION = "auto_trip_detection"
    private const val KEY_DEFAULT_GEOFENCE_RADIUS = "default_geofence_radius"
    private const val KEY_STATIONARY_SPEED_THRESHOLD = "stationary_speed_threshold"
    private const val KEY_IDLE_DURATION_THRESHOLD = "idle_duration_threshold"
    private const val KEY_ENGINE_OFF_DETECTION = "engine_off_detection"
    private const val KEY_STATION_STOP_WATCHDOG = "station_stop_watchdog"
    private const val KEY_STATION_DWELL_SECONDS = "station_dwell_seconds"
    private const val KEY_MISSED_REFILL_THRESHOLD = "missed_refill_threshold"

    // Speed & Safety
    private const val KEY_DYNAMIC_SPEED_LIMITS = "dynamic_speed_limits"
    private const val KEY_OVERSPEED_ALERTS = "overspeed_alerts"
    private const val KEY_NEAR_LIMIT_WARNING = "near_limit_warning"
    private const val KEY_DRIVER_SCORING = "driver_scoring_enabled"

    // Maps
    private const val KEY_MAP_SATELLITE_MODE = "map_satellite_mode"
    private const val KEY_MAP_DARK_MODE = "map_dark_mode"
    private const val KEY_MAP_BUILDING_FOOTPRINTS = "map_building_footprints"
    private const val KEY_AUTO_DAY_NIGHT_SOLAR = "auto_day_night_solar"
    private const val KEY_VOICE_GREETINGS = "voice_greetings_enabled"
    private const val KEY_TTS_VOICE_ID = "tts_voice_id"
    private const val KEY_MEDIA_PILL_ENABLED = "media_pill_enabled"

    data class SettingsState(
        val screenAlwaysOn: Boolean = true,
        val autoMinimizeDrawer: Boolean = false,
        val language: String = "English (Automotive Standard)",
        val cockpitTheme: String = "premium",
        val themeAppearanceMode: String = "auto",
        val carName: String = "My Car",
        val fuelType: String = "Petrol",
        val tankCapacityLiters: Double = 48.0,
        val expectedMileageKmpl: Double = 18.0,
        val defaultGeofenceRadiusMeters: Float = 200.0f,
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
        val voiceGreetingsEnabled: Boolean = true,
        val ttsVoiceId: String = "en_in_natural_female",
        val mediaPillEnabled: Boolean = true
    ) {
        val cockpitThemeMode: CockpitThemeMode
            get() = CockpitThemeMode.fromId(cockpitTheme)

        val appearanceMode: com.antigravity.copilot.core.designsystem.ThemeAppearanceMode
            get() = com.antigravity.copilot.core.designsystem.ThemeAppearanceMode.fromId(themeAppearanceMode)
    }

    private val _settingsState = MutableStateFlow(SettingsState())
    val settingsState: StateFlow<SettingsState> = _settingsState.asStateFlow()

    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        reload(context)
    }

    fun reload(context: Context) {
        val prefs = getPrefs(context)
        _settingsState.value = SettingsState(
            screenAlwaysOn = prefs.getBoolean(KEY_SCREEN_ALWAYS_ON, true),
            autoMinimizeDrawer = prefs.getBoolean(KEY_AUTO_MINIMIZE_DRAWER, false),
            language = prefs.getString(KEY_LANGUAGE, "English (Automotive Standard)") ?: "English",
            cockpitTheme = prefs.getString(KEY_COCKPIT_THEME, "premium") ?: "premium",
            themeAppearanceMode = prefs.getString(KEY_THEME_APPEARANCE_MODE, "auto") ?: "auto",
            carName = prefs.getString(KEY_CAR_NAME, "My Car") ?: "My Car",
            fuelType = prefs.getString(KEY_FUEL_TYPE, "Petrol") ?: "Petrol",
            tankCapacityLiters = prefs.getFloat(KEY_TANK_CAPACITY, 48.0f).toDouble(),
            expectedMileageKmpl = prefs.getFloat(KEY_EXPECTED_MILEAGE, 18.0f).toDouble(),
            defaultGeofenceRadiusMeters = prefs.getFloat(KEY_DEFAULT_GEOFENCE_RADIUS, 200.0f),
            stationarySpeedThresholdKmh = prefs.getFloat(KEY_STATIONARY_SPEED_THRESHOLD, 2.5f),
            idleDurationThresholdSeconds = prefs.getInt(KEY_IDLE_DURATION_THRESHOLD, 30),
            autoTripDetection = prefs.getBoolean(KEY_AUTO_TRIP_DETECTION, true),
            engineOffDetection = prefs.getBoolean(KEY_ENGINE_OFF_DETECTION, true),
            stationStopWatchdog = prefs.getBoolean(KEY_STATION_STOP_WATCHDOG, true),
            stationPromptDwellSeconds = prefs.getInt(KEY_STATION_DWELL_SECONDS, 30),
            missedRefillThresholdLiters = prefs.getFloat(KEY_MISSED_REFILL_THRESHOLD, 3.0f).toDouble(),
            dynamicSpeedLimits = prefs.getBoolean(KEY_DYNAMIC_SPEED_LIMITS, true),
            overspeedAlerts = prefs.getBoolean(KEY_OVERSPEED_ALERTS, true),
            nearLimitWarning = prefs.getBoolean(KEY_NEAR_LIMIT_WARNING, true),
            driverScoring = prefs.getBoolean(KEY_DRIVER_SCORING, true),
            mapSatelliteMode = prefs.getBoolean(KEY_MAP_SATELLITE_MODE, true),
            mapDarkMode = prefs.getBoolean(KEY_MAP_DARK_MODE, true),
            mapBuildingFootprints = prefs.getBoolean(KEY_MAP_BUILDING_FOOTPRINTS, true),
            autoDayNightSolar = prefs.getBoolean(KEY_AUTO_DAY_NIGHT_SOLAR, true),
            voiceGreetingsEnabled = prefs.getBoolean(KEY_VOICE_GREETINGS, true),
            ttsVoiceId = prefs.getString(KEY_TTS_VOICE_ID, "en_in_natural_female") ?: "en_in_natural_female",
            mediaPillEnabled = prefs.getBoolean(KEY_MEDIA_PILL_ENABLED, true)
        )
        isInitialized = true
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun updateScreenAlwaysOn(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SCREEN_ALWAYS_ON, enabled).apply()
        _settingsState.value = _settingsState.value.copy(screenAlwaysOn = enabled)
    }

    fun updateAutoMinimizeDrawer(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AUTO_MINIMIZE_DRAWER, enabled).apply()
        _settingsState.value = _settingsState.value.copy(autoMinimizeDrawer = enabled)
    }

    fun updateVehicleNameAndFuel(context: Context, name: String, fuelType: String) {
        getPrefs(context).edit()
            .putString(KEY_CAR_NAME, name)
            .putString(KEY_FUEL_TYPE, fuelType)
            .apply()
        _settingsState.value = _settingsState.value.copy(carName = name, fuelType = fuelType)
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
    }

    fun updateAutoTripDetection(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AUTO_TRIP_DETECTION, enabled).apply()
        _settingsState.value = _settingsState.value.copy(autoTripDetection = enabled)
    }

    fun updateEngineOffDetection(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ENGINE_OFF_DETECTION, enabled).apply()
        _settingsState.value = _settingsState.value.copy(engineOffDetection = enabled)
    }

    fun updateStationStopWatchdog(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_STATION_STOP_WATCHDOG, enabled).apply()
        _settingsState.value = _settingsState.value.copy(stationStopWatchdog = enabled)
    }

    fun updateDynamicSpeedLimits(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_DYNAMIC_SPEED_LIMITS, enabled).apply()
        _settingsState.value = _settingsState.value.copy(dynamicSpeedLimits = enabled)
    }

    fun updateOverspeedAlerts(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_OVERSPEED_ALERTS, enabled).apply()
        _settingsState.value = _settingsState.value.copy(overspeedAlerts = enabled)
    }

    fun updateNearLimitWarning(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_NEAR_LIMIT_WARNING, enabled).apply()
        _settingsState.value = _settingsState.value.copy(nearLimitWarning = enabled)
    }

    fun updateDriverScoring(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_DRIVER_SCORING, enabled).apply()
        _settingsState.value = _settingsState.value.copy(driverScoring = enabled)
    }

    fun updateMapSatelliteMode(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_MAP_SATELLITE_MODE, enabled).apply()
        _settingsState.value = _settingsState.value.copy(mapSatelliteMode = enabled)
    }

    fun updateMapDarkMode(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_MAP_DARK_MODE, enabled).apply()
        _settingsState.value = _settingsState.value.copy(mapDarkMode = enabled)
    }

    fun updateMapBuildingFootprints(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_MAP_BUILDING_FOOTPRINTS, enabled).apply()
        _settingsState.value = _settingsState.value.copy(mapBuildingFootprints = enabled)
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
    }

    fun updateThemeAppearanceMode(context: Context, mode: com.antigravity.copilot.core.designsystem.ThemeAppearanceMode) {
        getPrefs(context).edit()
            .putString(KEY_THEME_APPEARANCE_MODE, mode.id)
            .apply()
        _settingsState.value = _settingsState.value.copy(themeAppearanceMode = mode.id)
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
    }

    fun restoreClassicCockpit(context: Context) {
        restorePremiumCockpit(context)
    }

    fun recoverToSafeClassicCockpit(context: Context) {
        restorePremiumCockpit(context)
    }

    fun updateAutoDayNightSolar(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AUTO_DAY_NIGHT_SOLAR, enabled).apply()
        _settingsState.value = _settingsState.value.copy(autoDayNightSolar = enabled)
    }

    fun updateVoiceGreetings(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_VOICE_GREETINGS, enabled).apply()
        _settingsState.value = _settingsState.value.copy(voiceGreetingsEnabled = enabled)
    }

    fun updateTtsVoiceId(context: Context, voiceId: String) {
        getPrefs(context).edit().putString(KEY_TTS_VOICE_ID, voiceId).apply()
        _settingsState.value = _settingsState.value.copy(ttsVoiceId = voiceId)
        com.antigravity.copilot.core.tts.AutomotiveVoiceAssistant.getInstance(context).reconfigureVoice()
    }

    fun updateMediaPillEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_MEDIA_PILL_ENABLED, enabled).apply()
        _settingsState.value = _settingsState.value.copy(mediaPillEnabled = enabled)
    }

    fun resetToDefaults(context: Context) {
        getPrefs(context).edit().clear().apply()
        _settingsState.value = SettingsState()
    }
}
