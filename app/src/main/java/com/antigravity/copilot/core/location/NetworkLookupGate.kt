package com.antigravity.copilot.core.location

import android.content.Context
import com.antigravity.copilot.core.settings.AppSettingsManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.floor

/**
 * Online lookups (Overpass / Nominatim) behind one gate (audit R1 / H4):
 * - the "Online lookups" setting switches them off entirely (privacy);
 * - one shared per-host rate limiter (Nominatim policy: max 1 request / s);
 * - every connection is disconnected in a finally block.
 */
object NetworkLookupGate {
    const val MIN_INTERVAL_MS = 1_100L

    private val mutex = Mutex()
    private val lastRequestMs = HashMap<String, Long>()

    fun isAllowed(context: Context): Boolean =
        AppSettingsManager.settingsState.value.onlineLookups && LocationNameResolver.isNetworkConnected(context)

    /** Waits for this host's slot, then runs the GET; null on any failure / non-200. */
    suspend fun httpGet(url: String, userAgent: String, timeoutMs: Int = 4_000): String? {
        val host = runCatching { URL(url).host }.getOrNull() ?: return null
        mutex.withLock {
            val now = android.os.SystemClock.elapsedRealtime()
            val wait = RateLimit.waitMs(lastRequestMs[host], now, MIN_INTERVAL_MS)
            if (wait > 0) delay(wait)
            lastRequestMs[host] = android.os.SystemClock.elapsedRealtime()
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("User-Agent", userAgent)
        }
        return try {
            if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() } else null
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}

/** Pure helpers for the lookup gate and the geo cache (unit-tested). */
object RateLimit {
    fun waitMs(lastMs: Long?, nowMs: Long, minIntervalMs: Long): Long =
        if (lastMs == null) 0L else (lastMs + minIntervalMs - nowMs).coerceAtLeast(0L)
}

object GeoCell {
    /** Grid key of ~[cellM] metres (latitude-scaled longitude step). */
    fun key(kind: String, lat: Double, lon: Double, cellM: Double): String {
        val latStep = cellM / 111_320.0
        val lonStep = cellM / (111_320.0 * Math.cos(Math.toRadians(lat)).coerceAtLeast(0.01))
        return String.format(Locale.US, "%s:%d:%d", kind, floor(lat / latStep).toLong(), floor(lon / lonStep).toLong())
    }

    fun isFresh(updatedAtMs: Long, nowMs: Long, ttlMs: Long): Boolean = nowMs - updatedAtMs in 0..ttlMs
}
