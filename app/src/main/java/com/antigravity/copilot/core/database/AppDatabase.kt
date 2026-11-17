package com.antigravity.copilot.core.database

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrip(trip: TripEntity): Long

    @Update
    suspend fun updateTrip(trip: TripEntity)

    @Query("SELECT * FROM trips WHERE id = :tripId")
    suspend fun getTripById(tripId: Long): TripEntity?

    @Query("SELECT * FROM trips WHERE status IN ('ACTIVE_TRIP', 'TEMPORARY_STOP') ORDER BY startTime DESC LIMIT 1")
    suspend fun getActiveTrip(): TripEntity?

    @Query("SELECT * FROM trips WHERE status = 'COMPLETED' AND isQualifying = 1 ORDER BY startTime DESC")
    fun getAllQualifyingTrips(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips WHERE status = 'COMPLETED' ORDER BY endTime DESC, startTime DESC LIMIT 1")
    suspend fun getLastCompletedTrip(): TripEntity?

    @Query("SELECT * FROM trips WHERE status = 'COMPLETED' AND isQualifying = 1 ORDER BY startTime DESC")
    fun getAllHistoricalTrips(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips WHERE status = 'COMPLETED' AND isQualifying = 1 ORDER BY startTime DESC LIMIT 3")
    fun getLastThreeTrips(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips ORDER BY startTime DESC LIMIT 20")
    fun getRecentTrips(): Flow<List<TripEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPoints(points: List<TripPointEntity>)

    @Query("SELECT * FROM trip_points WHERE tripId = :tripId ORDER BY timestamp ASC")
    suspend fun getPointsForTrip(tripId: Long): List<TripPointEntity>

    @Query("SELECT * FROM trip_points WHERE tripId = :tripId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastPointForTrip(tripId: Long): TripPointEntity?

    @Query("DELETE FROM trip_points WHERE tripId = :tripId")
    suspend fun deletePointsForTrip(tripId: Long)

    @Query("DELETE FROM trips WHERE id = :tripId")
    suspend fun deleteTrip(tripId: Long)

    @Query("DELETE FROM trips")
    suspend fun clearAllTrips()

    @Query("DELETE FROM trip_points")
    suspend fun clearAllTripPoints()

    @Query("SELECT * FROM trips ORDER BY startTime ASC")
    suspend fun getAllTrips(): List<TripEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrips(trips: List<TripEntity>)

    @Query("SELECT * FROM trip_points ORDER BY tripId ASC, timestamp ASC")
    suspend fun getAllTripPoints(): List<TripPointEntity>

    @Query("SELECT COUNT(*) FROM trip_points")
    suspend fun getTripPointCount(): Int

    @Query("SELECT COUNT(*) FROM trips")
    suspend fun getTripCount(): Int
}

@Dao
interface FuelDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFuelEvent(event: FuelEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFuelEvents(events: List<FuelEventEntity>)

    @Query("SELECT * FROM fuel_events WHERE vehicleId = :vehicleId ORDER BY timestamp DESC")
    fun getFuelEvents(vehicleId: Int): Flow<List<FuelEventEntity>>

    @Query("SELECT * FROM fuel_events WHERE vehicleId = :vehicleId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestFuelEvent(vehicleId: Int): FuelEventEntity?

    @Query("DELETE FROM fuel_events")
    suspend fun clearAllFuelEvents()

    @Update
    suspend fun updateFuelEvent(event: FuelEventEntity)

    @Query("DELETE FROM fuel_events WHERE id = :id")
    suspend fun deleteFuelEvent(id: Long)

    @Query("SELECT * FROM fuel_events WHERE id = :id LIMIT 1")
    suspend fun getFuelEventById(id: Long): FuelEventEntity?

    @Query("SELECT * FROM fuel_events ORDER BY timestamp ASC")
    suspend fun getAllFuelEvents(): List<FuelEventEntity>

    @Query("SELECT COUNT(*) FROM fuel_events")
    suspend fun getFuelEventCount(): Int

    /** Unlogged-consumption rows carry a stable "[unlogged:tripId:time]" marker in notes. */
    @Query("SELECT * FROM fuel_events WHERE eventType = 'UNLOGGED_CONSUMPTION' AND notes LIKE :pattern LIMIT 1")
    suspend fun findUnloggedByMarker(pattern: String): FuelEventEntity?
}

@Dao
interface FuelLedgerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: FuelLedgerEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvents(events: List<FuelLedgerEntity>)

    @Query("SELECT * FROM fuel_ledger_events ORDER BY timestamp DESC")
    fun getAllEventsFlow(): Flow<List<FuelLedgerEntity>>

    @Query("SELECT * FROM fuel_ledger_events ORDER BY timestamp ASC")
    suspend fun getAllEventsAsc(): List<FuelLedgerEntity>

    @Query("SELECT * FROM fuel_ledger_events ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestEvent(): FuelLedgerEntity?

    @Query("SELECT * FROM fuel_ledger_events WHERE id = :id LIMIT 1")
    suspend fun getEventById(id: Long): FuelLedgerEntity?

    @Query("SELECT * FROM fuel_ledger_events WHERE parentId = :parentId")
    suspend fun getEventsByParentId(parentId: Long): List<FuelLedgerEntity>

    @Query("SELECT * FROM fuel_ledger_events WHERE eventType IN ('REFUEL', 'INITIAL_FUEL_BASELINE') ORDER BY timestamp DESC")
    fun getRefuelRecordsFlow(): Flow<List<FuelLedgerEntity>>

    @Query("SELECT * FROM fuel_ledger_events WHERE eventType = 'INFERRED_REFUEL' AND confidence = 'PROVISIONAL' ORDER BY timestamp DESC")
    fun getProvisionalInferredEventsFlow(): Flow<List<FuelLedgerEntity>>

    @Query("DELETE FROM fuel_ledger_events WHERE id = :id")
    suspend fun deleteEventById(id: Long)

    @Query("SELECT * FROM fuel_ledger_events WHERE timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp ASC")
    suspend fun getEventsBetween(startTime: Long, endTime: Long): List<FuelLedgerEntity>

    @Query("DELETE FROM fuel_ledger_events")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM fuel_ledger_events")
    suspend fun getEventCount(): Int
}

@Dao
interface VehicleStateDao {
    @Insert
    suspend fun insertTransition(transition: VehicleStateTransitionEntity): Long

    @Query("SELECT * FROM vehicle_state_transitions ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentTransitions(limit: Int = 50): kotlinx.coroutines.flow.Flow<List<VehicleStateTransitionEntity>>

    @Query("SELECT * FROM vehicle_state_transitions WHERE toState = 'ENGINE_OFF' ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastEngineOffTransition(): VehicleStateTransitionEntity?

    @Query("SELECT * FROM vehicle_state_transitions WHERE tripId = :tripId ORDER BY timestamp ASC")
    suspend fun getTransitionsForTrip(tripId: Long): List<VehicleStateTransitionEntity>

    @Query("DELETE FROM vehicle_state_transitions WHERE tripId = :tripId")
    suspend fun deleteTransitionsForTrip(tripId: Long)

    @Query("SELECT * FROM vehicle_state_transitions WHERE timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp ASC")
    suspend fun getTransitionsBetween(startTime: Long, endTime: Long): List<VehicleStateTransitionEntity>

    @Query("DELETE FROM vehicle_state_transitions")
    suspend fun clearAll()

    @Query("SELECT * FROM vehicle_state_transitions ORDER BY timestamp ASC")
    suspend fun getAllTransitions(): List<VehicleStateTransitionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransitions(transitions: List<VehicleStateTransitionEntity>)

    @Query("SELECT COUNT(*) FROM vehicle_state_transitions")
    suspend fun getTransitionCount(): Int
}

@Dao
interface VehicleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateVehicle(vehicle: VehicleEntity)

    @Query("SELECT * FROM vehicles WHERE id = :id")
    suspend fun getVehicle(id: Int = 1): VehicleEntity?

    @Query("SELECT * FROM vehicles WHERE id = :id")
    fun getVehicleFlow(id: Int = 1): Flow<VehicleEntity?>

    @Query("DELETE FROM vehicles")
    suspend fun clearAllVehicles()
}


@Dao
interface TripGeofenceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGeofence(geofence: TripGeofenceEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGeofences(geofences: List<TripGeofenceEntity>)

    @Update
    suspend fun updateGeofence(geofence: TripGeofenceEntity)

    @Delete
    suspend fun deleteGeofence(geofence: TripGeofenceEntity)

    @Query("DELETE FROM trip_geofences WHERE id = :id")
    suspend fun deleteGeofenceById(id: Long)

    @Query("SELECT * FROM trip_geofences WHERE id = :id")
    suspend fun getGeofenceById(id: Long): TripGeofenceEntity?

    @Query("SELECT * FROM trip_geofences ORDER BY createdAt ASC")
    fun getAllGeofences(): Flow<List<TripGeofenceEntity>>

    @Query("SELECT * FROM trip_geofences ORDER BY createdAt ASC")
    suspend fun getAllGeofencesList(): List<TripGeofenceEntity>

    @Query("SELECT * FROM trip_geofences WHERE isEnabled = 1 ORDER BY createdAt ASC")
    fun getEnabledGeofences(): Flow<List<TripGeofenceEntity>>

    @Query("SELECT * FROM trip_geofences WHERE isEnabled = 1 ORDER BY createdAt ASC")
    suspend fun getEnabledGeofencesList(): List<TripGeofenceEntity>

    @Query("SELECT COUNT(*) FROM trip_geofences")
    suspend fun getGeofenceCount(): Int

    @Query("DELETE FROM trip_geofences")
    suspend fun clearAllGeofences()
}

@Dao
interface FuelModelDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: FuelModelStateEntity)

    @Query("SELECT * FROM fuel_model_state WHERE id = 1")
    suspend fun get(): FuelModelStateEntity?

    @Query("DELETE FROM fuel_model_state")
    suspend fun clear()
}

@Database(
    entities = [
        VehicleEntity::class,
        TripEntity::class,
        TripPointEntity::class,
        FuelEventEntity::class,
        VehicleStateTransitionEntity::class,
        FuelLedgerEntity::class,
        TripGeofenceEntity::class,
        FuelModelStateEntity::class
    ],
    version = 9,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun fuelDao(): FuelDao
    abstract fun vehicleDao(): VehicleDao
    abstract fun vehicleStateDao(): VehicleStateDao
    abstract fun fuelLedgerDao(): FuelLedgerDao
    abstract fun tripGeofenceDao(): TripGeofenceDao
    abstract fun fuelModelDao(): FuelModelDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Version 1 to 2 migration
            }
        }

        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE fuel_events ADD COLUMN odometerKm REAL NOT NULL DEFAULT 0.0")
                } catch (_: Exception) {}
                try {
                    db.execSQL("ALTER TABLE fuel_events ADD COLUMN calculatedMileageKmpl REAL NOT NULL DEFAULT 0.0")
                } catch (_: Exception) {}
            }
        }

        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE fuel_events ADD COLUMN locationName TEXT NOT NULL DEFAULT ''")
                } catch (_: Exception) {}
                try {
                    db.execSQL("ALTER TABLE fuel_events ADD COLUMN latitude REAL NOT NULL DEFAULT 0.0")
                } catch (_: Exception) {}
                try {
                    db.execSQL("ALTER TABLE fuel_events ADD COLUMN longitude REAL NOT NULL DEFAULT 0.0")
                } catch (_: Exception) {}
            }
        }

        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_trips_isQualifying_startTime ON trips (isQualifying, startTime)")
                } catch (_: Exception) {}
            }
        }

        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `vehicle_state_transitions` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `fromState` TEXT NOT NULL,
                            `toState` TEXT NOT NULL,
                            `timestamp` INTEGER NOT NULL,
                            `confidence` TEXT NOT NULL,
                            `triggerReason` TEXT NOT NULL,
                            `tripId` INTEGER,
                            `extraMetadata` TEXT
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_vehicle_state_transitions_timestamp` ON `vehicle_state_transitions` (`timestamp`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_vehicle_state_transitions_toState` ON `vehicle_state_transitions` (`toState`)")
                } catch (_: Exception) {}
            }
        }

        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `fuel_ledger_events` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `correlationId` TEXT NOT NULL,
                            `parentId` INTEGER,
                            `eventType` TEXT NOT NULL,
                            `timestamp` INTEGER NOT NULL,
                            `odometerKm` REAL NOT NULL,
                            `fuelQuantityLiters` REAL NOT NULL,
                            `fuelGaugePoints` INTEGER NOT NULL,
                            `pricePerLiter` REAL NOT NULL,
                            `totalAmount` REAL NOT NULL,
                            `source` TEXT NOT NULL,
                            `confidence` TEXT NOT NULL,
                            `locationName` TEXT NOT NULL,
                            `latitude` REAL NOT NULL,
                            `longitude` REAL NOT NULL,
                            `nominalFuelLiters` REAL NOT NULL,
                            `conservativeFuelLiters` REAL NOT NULL,
                            `upperFuelLiters` REAL NOT NULL,
                            `estimatedRangeKm` REAL NOT NULL,
                            `estimatedMileageKmpl` REAL NOT NULL,
                            `confidencePercent` INTEGER NOT NULL,
                            `reasonNote` TEXT NOT NULL,
                            `calculationMetadataJson` TEXT NOT NULL
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_fuel_ledger_events_timestamp` ON `fuel_ledger_events` (`timestamp`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_fuel_ledger_events_eventType` ON `fuel_ledger_events` (`eventType`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_fuel_ledger_events_odometerKm` ON `fuel_ledger_events` (`odometerKm`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_fuel_ledger_events_parentId` ON `fuel_ledger_events` (`parentId`)")

                    // Non-destructive seeding from existing fuel_events if table exists and has rows
                    db.execSQL("""
                        INSERT INTO `fuel_ledger_events` (
                            correlationId, parentId, eventType, timestamp, odometerKm, fuelQuantityLiters,
                            fuelGaugePoints, pricePerLiter, totalAmount, source, confidence,
                            locationName, latitude, longitude, nominalFuelLiters, conservativeFuelLiters,
                            upperFuelLiters, estimatedRangeKm, estimatedMileageKmpl, confidencePercent,
                            reasonNote, calculationMetadataJson
                        )
                        SELECT
                            'migrated_' || id,
                            NULL,
                            CASE WHEN eventType = 'REFUEL' THEN 'REFUEL'
                                 WHEN eventType = 'MID_OBSERVED' THEN 'FUEL_GAUGE_UPDATE'
                                 WHEN eventType = 'CONSUMPTION' THEN 'TRIP_FUEL_CONSUMPTION'
                                 ELSE 'INITIAL_FUEL_BASELINE' END,
                            timestamp,
                            odometerKm,
                            liters,
                            midLevelObserved,
                            pricePerLiter,
                            totalCost,
                            'MANUAL',
                            'CONFIRMED',
                            locationName,
                            latitude,
                            longitude,
                            calculatedBalanceLiters,
                            MAX(0.0, calculatedBalanceLiters - 2.0),
                            calculatedBalanceLiters + 2.0,
                            calculatedBalanceLiters * 18.0,
                            CASE WHEN calculatedMileageKmpl > 0.0 THEN calculatedMileageKmpl ELSE 18.0 END,
                            85,
                            notes,
                            '{"migratedFromV6":true}'
                        FROM fuel_events
                    """.trimIndent())
                } catch (_: Exception) {}
            }
        }

        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `trip_geofences` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `name` TEXT NOT NULL,
                            `latitude` REAL NOT NULL,
                            `longitude` REAL NOT NULL,
                            `radiusMeters` REAL NOT NULL DEFAULT 200.0,
                            `isEnabled` INTEGER NOT NULL DEFAULT 1,
                            `createdAt` INTEGER NOT NULL,
                            `updatedAt` INTEGER NOT NULL
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_trip_geofences_isEnabled` ON `trip_geofences` (`isEnabled`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_trip_geofences_name` ON `trip_geofences` (`name`)")
                } catch (_: Exception) {}
            }
        }

        /** Fuel model JSON snapshot. Strict: failures surface instead of being swallowed. */
        val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `fuel_model_state` (" +
                        "`id` INTEGER NOT NULL, `json` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
            }
        }

        const val DATABASE_SCHEMA_VERSION = 9

        val ALL_MIGRATIONS = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9
        )

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "driving_report.db"
                )
                    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                    .addMigrations(*ALL_MIGRATIONS)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
