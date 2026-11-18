package com.antigravity.copilot.core.fuel.station

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

enum class StationQuality { EXCELLENT, AVERAGE, POOR, NOT_AVAILABLE }

/** The three driver-facing modes, plus the dashboard "Reserve light ON" odometer marker. */
enum class RefuelMode { FULL_TANK, AT_RESERVE, TOP_UP, RESERVE_MARKER }

enum class RefuelLogStatus { PENDING_EVALUATION, ACCUMULATING, COMPLETED }

@Entity(
    tableName = "fuel_stations",
    indices = [Index(value = ["latitude", "longitude"])]
)
data class FuelStationEntity(
    @PrimaryKey(autoGenerate = true) val stationId: Long = 0,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val lastFuelRate: Double = 0.0,
    /** Rolling mean of the last few completed cycles that burned this station's fuel (km/L); 0 = none. */
    val averageMileage: Double = 0.0,
    /** [StationQuality] name. */
    val qualityRating: String = StationQuality.NOT_AVAILABLE.name,
    val visitCount: Int = 0,
    /** Number of completed mileage cycles behind [averageMileage]. */
    val ratedCycles: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    /** Mean ₹/L over every refuel logged at this station (0 = none); [lastFuelRate] is the latest. */
    val avgFuelRate: Double = 0.0
) {
    val quality: StationQuality
        get() = runCatching { StationQuality.valueOf(qualityRating) }.getOrDefault(StationQuality.NOT_AVAILABLE)
}

@Entity(
    tableName = "refuel_logs",
    indices = [Index(value = ["timestamp"]), Index(value = ["stationId"])]
)
data class RefuelLogEntity(
    @PrimaryKey(autoGenerate = true) val logId: Long = 0,
    val stationId: Long? = null,
    val liters: Double,
    val rate: Double,
    /** [RefuelMode] name. */
    val refuelMode: String,
    val odometerAtRefuel: Double,
    /** [RefuelLogStatus] name. */
    val status: String = RefuelLogStatus.PENDING_EVALUATION.name,
    /** km/L of the cycle this log anchored, once closed; null while open or when not evaluable. */
    val calculatedMileage: Double? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    val mode: RefuelMode
        get() = runCatching { RefuelMode.valueOf(refuelMode) }.getOrDefault(RefuelMode.TOP_UP)
}

@Dao
interface FuelStationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: FuelStationEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<FuelStationEntity>)

    @Update
    suspend fun update(row: FuelStationEntity)

    @Update
    suspend fun updateAll(rows: List<FuelStationEntity>)

    @Query("SELECT * FROM fuel_stations WHERE stationId = :id LIMIT 1")
    suspend fun getById(id: Long): FuelStationEntity?

    /** Spatial bounding-box query (uses the latitude/longitude index). */
    @Query(
        "SELECT * FROM fuel_stations WHERE latitude BETWEEN :minLat AND :maxLat " +
            "AND longitude BETWEEN :minLon AND :maxLon"
    )
    suspend fun inBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<FuelStationEntity>

    @Query("SELECT * FROM fuel_stations")
    fun observeAll(): Flow<List<FuelStationEntity>>

    @Query("SELECT * FROM fuel_stations")
    suspend fun getAll(): List<FuelStationEntity>

    @Query("DELETE FROM fuel_stations")
    suspend fun clearAll()
}

@Dao
interface RefuelLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: RefuelLogEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<RefuelLogEntity>)

    @Update
    suspend fun updateAll(rows: List<RefuelLogEntity>)

    @Query("DELETE FROM refuel_logs WHERE logId = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM refuel_logs ORDER BY timestamp ASC, logId ASC")
    suspend fun getAllChronological(): List<RefuelLogEntity>

    @Query("SELECT * FROM refuel_logs ORDER BY timestamp DESC, logId DESC LIMIT 1")
    fun observeLatest(): Flow<RefuelLogEntity?>

    @Query("DELETE FROM refuel_logs")
    suspend fun clearAll()
}
