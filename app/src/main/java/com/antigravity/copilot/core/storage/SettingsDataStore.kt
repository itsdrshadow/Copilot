package com.antigravity.copilot.core.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.antigravity.copilot.core.settings.AppSettingsManager.SettingsState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "copilot_settings_store")

object SettingsDataStore {
    val KEY_SCREEN_ALWAYS_ON = booleanPreferencesKey("screen_always_on")
    val KEY_AUTO_MINIMIZE_DRAWER = booleanPreferencesKey("auto_minimize_drawer")
    val KEY_LANGUAGE = stringPreferencesKey("app_language")
    val KEY_COCKPIT_THEME = stringPreferencesKey("cockpit_theme")
    val KEY_LAST_STABLE_THEME = stringPreferencesKey("last_stable_cockpit_theme")
    val KEY_THEME_APPEARANCE_MODE = stringPreferencesKey("theme_appearance_mode")
    val KEY_ACCENT_COLOR = stringPreferencesKey("accent_color")

    val KEY_CAR_NAME = stringPreferencesKey("car_name")
    val KEY_FUEL_TYPE = stringPreferencesKey("fuel_type")
    val KEY_TANK_CAPACITY = doublePreferencesKey("tank_capacity_liters")
    val KEY_EXPECTED_MILEAGE = doublePreferencesKey("expected_mileage_kmpl")

    val KEY_DEFAULT_GEOFENCE_RADIUS = floatPreferencesKey("default_geofence_radius")
    val KEY_STATIONARY_SPEED_THRESHOLD = floatPreferencesKey("stationary_speed_threshold")
    val KEY_IDLE_DURATION_THRESHOLD = intPreferencesKey("idle_duration_threshold")
    val KEY_AUTO_TRIP_DETECTION = booleanPreferencesKey("auto_trip_detection")
    val KEY_ENGINE_OFF_DETECTION = booleanPreferencesKey("engine_off_detection")
    val KEY_STATION_STOP_WATCHDOG = booleanPreferencesKey("station_stop_watchdog")
    val KEY_STATION_DWELL_SECONDS = intPreferencesKey("station_dwell_seconds")
    val KEY_MISSED_REFILL_THRESHOLD = doublePreferencesKey("missed_refill_threshold")

    val KEY_DYNAMIC_SPEED_LIMITS = booleanPreferencesKey("dynamic_speed_limits")
    val KEY_OVERSPEED_ALERTS = booleanPreferencesKey("overspeed_alerts")
    val KEY_NEAR_LIMIT_WARNING = booleanPreferencesKey("near_limit_warning")
    val KEY_DRIVER_SCORING = booleanPreferencesKey("driver_scoring_enabled")

    val KEY_MAP_SATELLITE_MODE = booleanPreferencesKey("map_satellite_mode")
    val KEY_MAP_DARK_MODE = booleanPreferencesKey("map_dark_mode")
    val KEY_MAP_BUILDING_FOOTPRINTS = booleanPreferencesKey("map_building_footprints")
    val KEY_AUTO_DAY_NIGHT_SOLAR = booleanPreferencesKey("auto_day_night_solar")
    val KEY_MEDIA_PILL_ENABLED = booleanPreferencesKey("media_pill_enabled")
    val KEY_AUTO_FOREGROUND_ON_BOOT = booleanPreferencesKey("auto_foreground_on_boot")
    val KEY_MAP_RENDER_FPS = stringPreferencesKey("map_render_fps")

    val KEY_STORAGE_SCHEMA_VERSION = intPreferencesKey("storage_schema_version")
    val KEY_LAST_MIGRATION_TIMESTAMP = longPreferencesKey("last_migration_timestamp")

    fun getSettingsFlow(context: Context): Flow<SettingsState> {
        return context.appDataStore.data
            .catch { exception ->
                if (exception is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw exception
                }
            }
            .map { prefs ->
                mapPreferencesToSettingsState(prefs)
            }
    }

    fun mapPreferencesToSettingsState(prefs: Preferences, fallback: SettingsState = SettingsState()): SettingsState {
        return SettingsState(
            screenAlwaysOn = prefs[KEY_SCREEN_ALWAYS_ON] ?: fallback.screenAlwaysOn,
            autoMinimizeDrawer = prefs[KEY_AUTO_MINIMIZE_DRAWER] ?: fallback.autoMinimizeDrawer,
            language = prefs[KEY_LANGUAGE] ?: fallback.language,
            cockpitTheme = prefs[KEY_COCKPIT_THEME] ?: fallback.cockpitTheme,
            themeAppearanceMode = prefs[KEY_THEME_APPEARANCE_MODE] ?: fallback.themeAppearanceMode,
            accentColor = prefs[KEY_ACCENT_COLOR] ?: fallback.accentColor,
            carName = prefs[KEY_CAR_NAME] ?: fallback.carName,
            fuelType = prefs[KEY_FUEL_TYPE] ?: fallback.fuelType,
            tankCapacityLiters = prefs[KEY_TANK_CAPACITY] ?: fallback.tankCapacityLiters,
            expectedMileageKmpl = prefs[KEY_EXPECTED_MILEAGE] ?: fallback.expectedMileageKmpl,
            defaultGeofenceRadiusMeters = prefs[KEY_DEFAULT_GEOFENCE_RADIUS] ?: fallback.defaultGeofenceRadiusMeters,
            stationarySpeedThresholdKmh = prefs[KEY_STATIONARY_SPEED_THRESHOLD] ?: fallback.stationarySpeedThresholdKmh,
            idleDurationThresholdSeconds = prefs[KEY_IDLE_DURATION_THRESHOLD] ?: fallback.idleDurationThresholdSeconds,
            autoTripDetection = prefs[KEY_AUTO_TRIP_DETECTION] ?: fallback.autoTripDetection,
            engineOffDetection = prefs[KEY_ENGINE_OFF_DETECTION] ?: fallback.engineOffDetection,
            stationStopWatchdog = prefs[KEY_STATION_STOP_WATCHDOG] ?: fallback.stationStopWatchdog,
            stationPromptDwellSeconds = prefs[KEY_STATION_DWELL_SECONDS] ?: fallback.stationPromptDwellSeconds,
            missedRefillThresholdLiters = prefs[KEY_MISSED_REFILL_THRESHOLD] ?: fallback.missedRefillThresholdLiters,
            dynamicSpeedLimits = prefs[KEY_DYNAMIC_SPEED_LIMITS] ?: fallback.dynamicSpeedLimits,
            overspeedAlerts = prefs[KEY_OVERSPEED_ALERTS] ?: fallback.overspeedAlerts,
            nearLimitWarning = prefs[KEY_NEAR_LIMIT_WARNING] ?: fallback.nearLimitWarning,
            driverScoring = prefs[KEY_DRIVER_SCORING] ?: fallback.driverScoring,
            mapSatelliteMode = prefs[KEY_MAP_SATELLITE_MODE] ?: fallback.mapSatelliteMode,
            mapDarkMode = prefs[KEY_MAP_DARK_MODE] ?: fallback.mapDarkMode,
            mapBuildingFootprints = prefs[KEY_MAP_BUILDING_FOOTPRINTS] ?: fallback.mapBuildingFootprints,
            autoDayNightSolar = prefs[KEY_AUTO_DAY_NIGHT_SOLAR] ?: fallback.autoDayNightSolar,
            mediaPillEnabled = prefs[KEY_MEDIA_PILL_ENABLED] ?: fallback.mediaPillEnabled,
            autoForegroundOnBoot = prefs[KEY_AUTO_FOREGROUND_ON_BOOT] ?: fallback.autoForegroundOnBoot,
            mapRenderFps = prefs[KEY_MAP_RENDER_FPS] ?: fallback.mapRenderFps
        )
    }

    suspend fun saveAllSettings(context: Context, state: SettingsState) {
        context.appDataStore.edit { prefs ->
            prefs[KEY_SCREEN_ALWAYS_ON] = state.screenAlwaysOn
            prefs[KEY_AUTO_MINIMIZE_DRAWER] = state.autoMinimizeDrawer
            prefs[KEY_LANGUAGE] = state.language
            prefs[KEY_COCKPIT_THEME] = state.cockpitTheme
            prefs[KEY_THEME_APPEARANCE_MODE] = state.themeAppearanceMode
            prefs[KEY_ACCENT_COLOR] = state.accentColor
            prefs[KEY_MAP_RENDER_FPS] = state.mapRenderFps
            prefs[KEY_CAR_NAME] = state.carName
            prefs[KEY_FUEL_TYPE] = state.fuelType
            prefs[KEY_TANK_CAPACITY] = state.tankCapacityLiters
            prefs[KEY_EXPECTED_MILEAGE] = state.expectedMileageKmpl
            prefs[KEY_DEFAULT_GEOFENCE_RADIUS] = state.defaultGeofenceRadiusMeters
            prefs[KEY_STATIONARY_SPEED_THRESHOLD] = state.stationarySpeedThresholdKmh
            prefs[KEY_IDLE_DURATION_THRESHOLD] = state.idleDurationThresholdSeconds
            prefs[KEY_AUTO_TRIP_DETECTION] = state.autoTripDetection
            prefs[KEY_AUTO_FOREGROUND_ON_BOOT] = state.autoForegroundOnBoot
            prefs[KEY_ENGINE_OFF_DETECTION] = state.engineOffDetection
            prefs[KEY_STATION_STOP_WATCHDOG] = state.stationStopWatchdog
            prefs[KEY_STATION_DWELL_SECONDS] = state.stationPromptDwellSeconds
            prefs[KEY_MISSED_REFILL_THRESHOLD] = state.missedRefillThresholdLiters
            prefs[KEY_DYNAMIC_SPEED_LIMITS] = state.dynamicSpeedLimits
            prefs[KEY_OVERSPEED_ALERTS] = state.overspeedAlerts
            prefs[KEY_NEAR_LIMIT_WARNING] = state.nearLimitWarning
            prefs[KEY_DRIVER_SCORING] = state.driverScoring
            prefs[KEY_MAP_SATELLITE_MODE] = state.mapSatelliteMode
            prefs[KEY_MAP_DARK_MODE] = state.mapDarkMode
            prefs[KEY_MAP_BUILDING_FOOTPRINTS] = state.mapBuildingFootprints
            prefs[KEY_AUTO_DAY_NIGHT_SOLAR] = state.autoDayNightSolar
            prefs[KEY_MEDIA_PILL_ENABLED] = state.mediaPillEnabled
        }
    }

    suspend fun clear(context: Context) {
        context.appDataStore.edit { it.clear() }
    }
}
