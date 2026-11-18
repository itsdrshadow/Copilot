package com.antigravity.copilot.core.road

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Map colour class of a road anomaly. YELLOW = speed breaker / minor pothole, RED = deep pothole / harsh breaker. */
enum class AnomalySeverity { YELLOW, RED }

/**
 * A crowd-of-one road anomaly learned from vertical jerks. [confidenceScore] rises when the jerk
 * repeats and falls on smooth passes; at 0 the row is deleted (self-healing).
 */
@Entity(
    tableName = "road_anomalies",
    indices = [Index(value = ["latitude", "longitude"])]
)
data class RoadAnomalyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val latitude: Double,
    val longitude: Double,
    /** Peak low-passed vertical acceleration seen at this spot (m/s²). */
    val maxZForce: Float,
    /** [AnomalySeverity] name. */
    val severity: String,
    val confidenceScore: Int = RoadAnomalyConfig.INITIAL_CONFIDENCE,
    val lastUpdated: Long = System.currentTimeMillis(),
    /** Travel heading when first recorded (0..360), or -1 if unknown. Gates smooth-pass decay. */
    val headingDeg: Float = -1f,
    /** [AnomalyKind] name: breaker (axle pair) vs pothole, from the hit pattern (audit K2). */
    @androidx.room.ColumnInfo(defaultValue = "UNKNOWN") val kind: String = AnomalyKind.UNKNOWN.name
) {
    val severityEnum: AnomalySeverity
        get() = if (severity == AnomalySeverity.RED.name) AnomalySeverity.RED else AnomalySeverity.YELLOW
}

@Dao
interface RoadAnomalyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: RoadAnomalyEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<RoadAnomalyEntity>)

    @Update
    suspend fun update(row: RoadAnomalyEntity)

    @Query("DELETE FROM road_anomalies WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM road_anomalies WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): RoadAnomalyEntity?

    /** Spatial bounding-box query (uses the latitude/longitude index). */
    @Query(
        "SELECT * FROM road_anomalies WHERE latitude BETWEEN :minLat AND :maxLat " +
            "AND longitude BETWEEN :minLon AND :maxLon"
    )
    suspend fun inBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<RoadAnomalyEntity>

    @Query("SELECT * FROM road_anomalies")
    fun observeAll(): Flow<List<RoadAnomalyEntity>>

    @Query("SELECT * FROM road_anomalies")
    suspend fun getAll(): List<RoadAnomalyEntity>

    @Query("DELETE FROM road_anomalies")
    suspend fun clearAll()
}
