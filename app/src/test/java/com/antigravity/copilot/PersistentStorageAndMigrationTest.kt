package com.antigravity.copilot

import com.antigravity.copilot.core.database.AppDatabase
import com.antigravity.copilot.core.settings.AppSettingsManager
import com.antigravity.copilot.core.storage.SettingsDataStore
import com.antigravity.copilot.core.storage.StorageMigrationManager
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

/**
 * PersistentStorageAndMigrationTest:
 * Validates version-safe migration, DataStore settings mappings,
 * Room sequential migrations, and non-destructive storage guarantees.
 */
class PersistentStorageAndMigrationTest {

    @Test
    fun testRoomSequentialMigrationsIntegrity() {
        // AppDatabase must have all sequential migrations from 1 to 8 defined
        val migrations = AppDatabase.ALL_MIGRATIONS
        assertEquals(7, migrations.size)

        for (i in 1..7) {
            val migration = migrations[i - 1]
            assertEquals("Migration startVersion must be sequential", i, migration.startVersion)
            assertEquals("Migration endVersion must be sequential", i + 1, migration.endVersion)
        }
    }

    @Test
    fun testDatabaseSchemaVersionConstant() {
        assertEquals(8, AppDatabase.DATABASE_SCHEMA_VERSION)
    }

    @Test
    fun testStorageSchemaVersionIsIndependentOfAppVersion() {
        assertEquals(1, StorageMigrationManager.CURRENT_STORAGE_VERSION)
        val appVersionCode = BuildConfig.VERSION_CODE
        val appVersionName = BuildConfig.VERSION_NAME

        assertEquals(54, appVersionCode)
        assertEquals("1.31.0", appVersionName)
        assertNotEquals(appVersionCode, StorageMigrationManager.CURRENT_STORAGE_VERSION)
    }

    @Test
    fun testSettingsStateDefaultsAndPriority() {
        val defaultState = AppSettingsManager.SettingsState()
        assertEquals("My Car", defaultState.carName)
        assertEquals(48.0, defaultState.tankCapacityLiters, 0.001)
        assertEquals(18.0, defaultState.expectedMileageKmpl, 0.001)
        assertTrue(defaultState.screenAlwaysOn)
        assertTrue(defaultState.autoTripDetection)

        // Verify user configuration takes priority over defaults
        val userConfigured = defaultState.copy(
            carName = "Apex Turbo",
            tankCapacityLiters = 60.0,
            expectedMileageKmpl = 22.5,
            screenAlwaysOn = false,
            autoTripDetection = false
        )

        assertEquals("Apex Turbo", userConfigured.carName)
        assertEquals(60.0, userConfigured.tankCapacityLiters, 0.001)
        assertEquals(22.5, userConfigured.expectedMileageKmpl, 0.001)
        assertFalse(userConfigured.screenAlwaysOn)
        assertFalse(userConfigured.autoTripDetection)
    }

    @Test
    fun testPersistentDataBackupWithAppSettingsSerialization() {
        val root = JsonObject()
        root.addProperty("version", 1)

        val sObj = JsonObject().apply {
            addProperty("car_name", "Grand Tourer")
            addProperty("fuel_type", "Diesel")
            addProperty("tank_capacity_liters", 55.0)
            addProperty("expected_mileage_kmpl", 20.0)
            addProperty("cockpit_theme", "premium")
            addProperty("auto_trip_detection", true)
        }
        root.add("app_settings", sObj)

        val lctObj = JsonObject().apply {
            addProperty("last_completed_trip_id", 42L)
            addProperty("last_completed_trip_time", 1731630000000L)
        }
        root.add("last_completed_trip", lctObj)

        val jsonStr = root.toString()
        val parsed = JsonParser.parseString(jsonStr).asJsonObject

        assertTrue(parsed.has("app_settings"))
        val restoredSettings = parsed.getAsJsonObject("app_settings")
        assertEquals("Grand Tourer", restoredSettings.get("car_name").asString)
        assertEquals("Diesel", restoredSettings.get("fuel_type").asString)
        assertEquals(55.0, restoredSettings.get("tank_capacity_liters").asDouble, 0.001)
        assertEquals(20.0, restoredSettings.get("expected_mileage_kmpl").asDouble, 0.001)

        assertTrue(parsed.has("last_completed_trip"))
        val restoredLct = parsed.getAsJsonObject("last_completed_trip")
        assertEquals(42L, restoredLct.get("last_completed_trip_id").asLong)
    }

    @Test
    fun testSettingsDataStoreKeysIntegrity() {
        assertNotNull(SettingsDataStore.KEY_CAR_NAME.name)
        assertNotNull(SettingsDataStore.KEY_FUEL_TYPE.name)
        assertNotNull(SettingsDataStore.KEY_TANK_CAPACITY.name)
        assertNotNull(SettingsDataStore.KEY_EXPECTED_MILEAGE.name)
        assertNotNull(SettingsDataStore.KEY_COCKPIT_THEME.name)
        assertNotNull(SettingsDataStore.KEY_THEME_APPEARANCE_MODE.name)
        assertNotNull(SettingsDataStore.KEY_SCREEN_ALWAYS_ON.name)
        assertNotNull(SettingsDataStore.KEY_AUTO_TRIP_DETECTION.name)
        assertNotNull(SettingsDataStore.KEY_STORAGE_SCHEMA_VERSION.name)
        assertNotNull(SettingsDataStore.KEY_LAST_MIGRATION_TIMESTAMP.name)
    }

    @Test
    fun testSafeMigrationResultContract() {
        val successResult = StorageMigrationManager.MigrationResult(isSuccess = true, schemaVersion = 1)
        assertTrue(successResult.isSuccess)
        assertEquals(1, successResult.schemaVersion)
        assertNull(successResult.errorMessage)

        val failureResult = StorageMigrationManager.MigrationResult(
            isSuccess = false,
            schemaVersion = 1,
            errorMessage = "Safe recoverable warning"
        )
        assertFalse(failureResult.isSuccess)
        assertEquals("Safe recoverable warning", failureResult.errorMessage)
    }
}
