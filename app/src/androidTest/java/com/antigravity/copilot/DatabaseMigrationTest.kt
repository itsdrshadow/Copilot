package com.antigravity.copilot

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.antigravity.copilot.core.database.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room migration tests on the exported schemas (audit M1). 16 → 17 must keep every existing
 * fuel / ledger / refuel-log row and mark the new replay columns as "not recorded".
 */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {

    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun migrate16To17_keepsDataAndDefaultsNewColumns() {
        helper.createDatabase(dbName, 16).use { db ->
            db.execSQL(
                "INSERT INTO fuel_events (id, vehicleId, timestamp, eventType, liters, midLevelObserved, pricePerLiter, totalCost, " +
                    "calculatedBalanceLiters, notes, odometerKm, calculatedMileageKmpl, locationName, latitude, longitude) " +
                    "VALUES (12, 1, 1700000000000, 'REFUEL', 10.0, 10, 100.0, 1000.0, 40.0, '', 45000.0, 15.0, 'HP', 21.9, 83.4)"
            )
            db.execSQL(
                "INSERT INTO refuel_logs (logId, stationId, liters, rate, refuelMode, odometerAtRefuel, status, calculatedMileage, timestamp) " +
                    "VALUES (3, NULL, 10.0, 100.0, 'FULL_TANK', 45000.0, 'PENDING_EVALUATION', NULL, 1700000000000)"
            )
        }

        val db = helper.runMigrationsAndValidate(dbName, 17, true, AppDatabase.MIGRATION_16_17)

        db.query("SELECT liters, fullTank, gaugePoint FROM fuel_events WHERE id = 12").use { c ->
            c.moveToFirst()
            assertEquals(10.0, c.getDouble(0), 0.0)
            assertEquals(-1, c.getInt(1))
            assertEquals(-1.0, c.getDouble(2), 0.0)
        }
        db.query("SELECT fuelEventId FROM refuel_logs WHERE logId = 3").use { c ->
            c.moveToFirst()
            assertNull(if (c.isNull(0)) null else c.getLong(0))
        }
    }

    @Test
    fun migration16To17_isIdempotent() {
        helper.createDatabase(dbName, 16).close()
        val db = helper.runMigrationsAndValidate(dbName, 17, true, AppDatabase.MIGRATION_16_17)
        // A re-run (e.g. interrupted first attempt) must not fail on the existing columns.
        AppDatabase.MIGRATION_16_17.migrate(db)
    }
}
