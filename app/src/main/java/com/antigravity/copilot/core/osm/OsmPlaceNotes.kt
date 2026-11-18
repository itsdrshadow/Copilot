package com.antigravity.copilot.core.osm

import android.content.Context
import android.util.Log
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.antigravity.copilot.core.database.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * "Add place details for OSM": the driver names a place the offline map does not know (a shop,
 * a pump, a dhaba). It is stored here first (works fully offline) and later sent to
 * OpenStreetMap as a public map note, which volunteer mappers turn into a map feature.
 * OSM notes can be created without an account, so no login is needed.
 */
@Entity(tableName = "osm_place_notes")
data class OsmPlaceNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val latitude: Double,
    val longitude: Double,
    val name: String,
    val category: String,
    val details: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** [OsmNoteStatus] name. */
    val status: String = OsmNoteStatus.PENDING.name,
    /** OSM note id once sent. */
    val osmNoteId: Long? = null,
    val lastError: String? = null,
    val attempts: Int = 0
)

enum class OsmNoteStatus { PENDING, SENT, FAILED }

@Dao
interface OsmPlaceNoteDao {
    @Insert
    suspend fun insert(note: OsmPlaceNoteEntity): Long

    @Update
    suspend fun update(note: OsmPlaceNoteEntity)

    @Query("SELECT * FROM osm_place_notes ORDER BY createdAt DESC LIMIT 20")
    fun observeRecent(): Flow<List<OsmPlaceNoteEntity>>

    @Query("SELECT * FROM osm_place_notes WHERE status = 'PENDING' ORDER BY createdAt")
    suspend fun pending(): List<OsmPlaceNoteEntity>

    @Query("DELETE FROM osm_place_notes WHERE id = :id AND status != 'SENT'")
    suspend fun deleteUnsent(id: Long)

    @Query("DELETE FROM osm_place_notes")
    suspend fun clearAll()
}

object OsmPlaceNotes {
    private const val TAG = "OsmPlaceNotes"
    private const val WORK_NAME = "osm_place_notes_sync"
    private const val NOTES_URL = "https://api.openstreetmap.org/api/0.6/notes.json"
    /** Give up on a note after this many server rejections / errors (it stays visible as Failed). */
    private const val MAX_ATTEMPTS = 5

    val categories = listOf("Shop", "Restaurant / Dhaba", "Petrol pump", "Temple", "School", "Hospital", "Bank / ATM", "Other")

    fun observeRecent(context: Context): Flow<List<OsmPlaceNoteEntity>> =
        AppDatabase.getInstance(context).osmPlaceNoteDao().observeRecent()

    /** Saves offline right away, then asks WorkManager to send it whenever a network exists. */
    suspend fun add(context: Context, lat: Double, lon: Double, name: String, category: String, details: String) {
        AppDatabase.getInstance(context).osmPlaceNoteDao().insert(
            OsmPlaceNoteEntity(latitude = lat, longitude = lon, name = name.trim(), category = category, details = details.trim())
        )
        scheduleSync(context)
    }

    suspend fun discard(context: Context, id: Long) = AppDatabase.getInstance(context).osmPlaceNoteDao().deleteUnsent(id)

    fun scheduleSync(context: Context) {
        val request = OneTimeWorkRequestBuilder<OsmPlaceNotesWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /** Note text a mapper reads; no personal data, just the place and where the hint came from. */
    fun noteText(n: OsmPlaceNoteEntity): String = buildString {
        append("Missing place: ").append(n.name)
        append("\nType: ").append(n.category)
        if (n.details.isNotBlank()) append("\nDetails: ").append(n.details)
        append("\n\nReported from a car's GPS position (may be off by a few metres, on the road next to the place).")
        append("\n#CopilotCarApp")
    }

    /** Sends every pending note. Returns false when a retry later makes sense (network / server). */
    internal suspend fun syncPending(context: Context): Boolean = withContext(Dispatchers.IO) {
        val dao = AppDatabase.getInstance(context).osmPlaceNoteDao()
        val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        var retry = false
        for (n in dao.pending()) {
            try {
                val body = FormBody.Builder()
                    .add("lat", "%.6f".format(java.util.Locale.US, n.latitude))
                    .add("lon", "%.6f".format(java.util.Locale.US, n.longitude))
                    .add("text", noteText(n))
                    .build()
                val req = Request.Builder().url(NOTES_URL).post(body).header("User-Agent", "Copilot-car-launcher").build()
                client.newCall(req).execute().use { r ->
                    val text = r.body?.string().orEmpty()
                    when {
                        r.isSuccessful -> {
                            val id = runCatching { JSONObject(text).getJSONObject("properties").getLong("id") }.getOrNull()
                            dao.update(n.copy(status = OsmNoteStatus.SENT.name, osmNoteId = id, lastError = null, attempts = n.attempts + 1))
                            Log.i(TAG, "Sent note ${n.id} as OSM note $id")
                        }
                        r.code in 500..599 || r.code == 429 -> {
                            dao.update(n.copy(lastError = "Server busy (${r.code})", attempts = n.attempts + 1))
                            retry = true
                        }
                        else -> {
                            val failed = n.attempts + 1 >= MAX_ATTEMPTS
                            dao.update(
                                n.copy(
                                    status = if (failed) OsmNoteStatus.FAILED.name else n.status,
                                    lastError = "Rejected (${r.code}) ${text.take(120)}",
                                    attempts = n.attempts + 1
                                )
                            )
                            if (!failed) retry = true
                        }
                    }
                }
            } catch (e: java.io.IOException) {
                // No usable network after all: keep pending, WorkManager retries.
                Log.w(TAG, "Note ${n.id} not sent: ${e.message}")
                retry = true
            }
        }
        !retry
    }
}

class OsmPlaceNotesWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        if (OsmPlaceNotes.syncPending(applicationContext)) Result.success() else Result.retry()
}
