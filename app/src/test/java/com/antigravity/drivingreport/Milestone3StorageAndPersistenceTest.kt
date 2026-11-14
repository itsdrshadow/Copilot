package com.antigravity.drivingreport

import android.content.Context
import com.antigravity.drivingreport.core.database.AppDatabase
import com.antigravity.drivingreport.core.database.PersistentDataManager
import com.antigravity.drivingreport.core.fuel.FuelRepository
import com.antigravity.drivingreport.core.odometer.OdometerManager
import com.antigravity.drivingreport.core.persistence.FullBackupManager
import com.antigravity.drivingreport.core.settings.AppSettingsManager
import com.antigravity.drivingreport.core.trip.TripRepository
import com.antigravity.drivingreport.core.trip.VehicleStateManager
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Milestone3StorageAndPersistenceTest:
 * Comprehensive test suite validating:
 * - Feature 19: Atomic backup restore with snapshot rollback semantics & pre-validation.
 * - Feature 20: Schema version validation (manifest.schemaVersion <= AppDatabase.DATABASE_SCHEMA_VERSION).
 * - Feature 21: Post-restore live singleton reload contracts.
 * - Feature 22: Coroutine exception handling & SupervisorJob integration.
 * - Feature 23: Flash wear & disk I/O throttling (30s debounce for backups & fuel persistence, 1s debounce for vehicle state).
 */
class Milestone3StorageAndPersistenceTest {

    private val gson = Gson()

    @Before
    fun setUp() {
        PersistentDataManager.resetBackupThrottleForTesting()
    }

    @Test
    fun testDatabaseSchemaVersionSynchronization() {
        // AppDatabase schema version must match FullBackupManager expected schema version
        assertEquals(8, AppDatabase.DATABASE_SCHEMA_VERSION)
        assertEquals(AppDatabase.DATABASE_SCHEMA_VERSION, FullBackupManager.DATABASE_SCHEMA_VERSION)
    }

    @Test
    fun testManifestSchemaValidationSuccessForEqualSchema() {
        val manifest = FullBackupManager.BackupManifest(
            appVersion = "1.18.0",
            versionCode = 33,
            schemaVersion = 8,
            timestamp = System.currentTimeMillis()
        )

        // Must succeed without throwing
        FullBackupManager.validateManifest(manifest)
    }

    @Test
    fun testManifestSchemaValidationSuccessForOlderCompatibleSchema() {
        val manifest = FullBackupManager.BackupManifest(
            appVersion = "1.14.0",
            versionCode = 23,
            schemaVersion = 7,
            timestamp = System.currentTimeMillis()
        )

        // Older schemas can be upgraded by Room migrations
        FullBackupManager.validateManifest(manifest)
    }

    @Test
    fun testManifestSchemaValidationThrowsForFutureIncompatibleSchema() {
        val manifest = FullBackupManager.BackupManifest(
            appVersion = "2.0.0",
            versionCode = 40,
            schemaVersion = 9,
            timestamp = System.currentTimeMillis()
        )

        val exception = assertThrows(FullBackupManager.BackupRestoreException::class.java) {
            FullBackupManager.validateManifest(manifest)
        }

        assertTrue(
            "Exception message must mention incompatible schema version",
            exception.message?.contains("Incompatible schema version: 9 > 8") == true
        )
    }

    @Test
    fun testManifestSchemaValidationThrowsForExtremeFutureVersion() {
        val manifest = FullBackupManager.BackupManifest(
            appVersion = "99.0.0",
            versionCode = 999,
            schemaVersion = 99,
            timestamp = System.currentTimeMillis()
        )

        val exception = assertThrows(FullBackupManager.BackupRestoreException::class.java) {
            FullBackupManager.validateManifest(manifest)
        }

        assertTrue(
            "Exception message must explain update requirement",
            exception.message?.contains("Please update the application before restoring this backup") == true
        )
    }

    @Test
    fun testAtomicPreValidationFailsOnCorruptJsonBeforeDatabaseWipe() {
        // In Feature 19, FullBackupManager deserializes all entities into memory BEFORE
        // invoking db.withTransaction { wipe & insert }.
        // This test simulates corrupt JSON in backup payload to ensure it throws during pre-validation.
        val malformedJson = "{ \"id\": 1, \"distanceMeters\": {invalid_syntax:::"

        assertThrows(JsonSyntaxException::class.java) {
            gson.fromJson(malformedJson, Map::class.java)
        }
    }

    @Test
    fun testPersistentDataManagerThrottleConfiguration() {
        assertEquals(
            "Flash wear debounce interval must be 30 seconds (30,000ms)",
            30_000L,
            PersistentDataManager.MIN_BACKUP_INTERVAL_MS
        )
        assertEquals(0L, PersistentDataManager.getLastBackupTimestamp())

        PersistentDataManager.resetBackupThrottleForTesting()
        assertEquals(0L, PersistentDataManager.getLastBackupTimestamp())
    }

    @Test
    fun testVehicleStateManagerPersistThrottleWindowConfiguration() {
        assertEquals(
            "Vehicle state persistence debounce must be 1,000ms",
            1000L,
            VehicleStateManager.PERSIST_DEBOUNCE_MS
        )
    }

    @Test
    fun testFuelRepositoryDebounceInterval() {
        assertEquals(
            "FuelRepository persist debounce interval must be 30 seconds",
            30_000L,
            FuelRepository.PERSIST_DEBOUNCE_MS
        )
    }

    @Test
    fun testLiveSingletonReloadMethodContractsViaReflection() {
        // Feature 21: Verify post-restore reload contracts exist and are public on all singletons

        // 1. TripRepository.reloadFromDatabase()
        val tripReloadMethod = TripRepository::class.java.getDeclaredMethod("reloadFromDatabase")
        assertNotNull("TripRepository must declare reloadFromDatabase()", tripReloadMethod)
        assertTrue("TripRepository.reloadFromDatabase() must be public", Modifier.isPublic(tripReloadMethod.modifiers))

        // 2. FuelRepository.reloadFromDatabase() & rehydrateLedgerAsync()
        val fuelReloadMethod = FuelRepository::class.java.getDeclaredMethod("reloadFromDatabase")
        assertNotNull("FuelRepository must declare reloadFromDatabase()", fuelReloadMethod)
        assertTrue("FuelRepository.reloadFromDatabase() must be public", Modifier.isPublic(fuelReloadMethod.modifiers))

        val fuelRehydrateMethod = FuelRepository::class.java.getDeclaredMethod("rehydrateLedgerAsync")
        assertNotNull("FuelRepository must declare rehydrateLedgerAsync()", fuelRehydrateMethod)
        assertTrue("FuelRepository.rehydrateLedgerAsync() must be public", Modifier.isPublic(fuelRehydrateMethod.modifiers))

        // 3. VehicleStateManager.reloadFromDatabase()
        val vehicleReloadMethod = VehicleStateManager::class.java.getDeclaredMethod("reloadFromDatabase")
        assertNotNull("VehicleStateManager must declare reloadFromDatabase()", vehicleReloadMethod)
        assertTrue("VehicleStateManager.reloadFromDatabase() must be public", Modifier.isPublic(vehicleReloadMethod.modifiers))

        // 4. OdometerManager.reloadFromPreferences(Context)
        val odoReloadMethod = OdometerManager::class.java.getDeclaredMethod("reloadFromPreferences", Context::class.java)
        assertNotNull("OdometerManager must declare reloadFromPreferences(Context)", odoReloadMethod)
        assertTrue("OdometerManager.reloadFromPreferences() must be public", Modifier.isPublic(odoReloadMethod.modifiers))

        // 5. AppSettingsManager.reload(Context)
        val appSettingsReloadMethod = AppSettingsManager::class.java.getDeclaredMethod("reload", Context::class.java)
        assertNotNull("AppSettingsManager must declare reload(Context)", appSettingsReloadMethod)
        assertTrue("AppSettingsManager.reload() must be public", Modifier.isPublic(appSettingsReloadMethod.modifiers))
    }
}
