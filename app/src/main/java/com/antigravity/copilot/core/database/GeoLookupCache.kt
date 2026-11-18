package com.antigravity.copilot.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** Cached online lookup result per grid cell (audit R1): place names, pump checks. */
@Entity(tableName = "geo_lookup_cache")
data class GeoLookupCacheEntity(
    @PrimaryKey val cellKey: String,
    val value: String,
    val updatedAt: Long
)

@Dao
interface GeoLookupCacheDao {
    @Query("SELECT * FROM geo_lookup_cache WHERE cellKey = :key LIMIT 1")
    suspend fun get(key: String): GeoLookupCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: GeoLookupCacheEntity)

    @Query("DELETE FROM geo_lookup_cache WHERE updatedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query("DELETE FROM geo_lookup_cache")
    suspend fun clearAll()
}
