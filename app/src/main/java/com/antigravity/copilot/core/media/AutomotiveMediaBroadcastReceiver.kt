package com.antigravity.copilot.core.media

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.os.Build
import android.os.Bundle
import android.util.Log

/**
 * Universal Automotive Media Broadcast Receiver:
 * Listens for system-wide, AVRCP, and Chinese Car Infotainment (FYT, TS10, Microntek, etc.)
 * Bluetooth metadata and playback broadcasts.
 *
 * Translates low-level broadcast intents into unified MediaPlaybackState updates
 * so songs, artists, and playback states display accurately in Copilot's homescreen
 * even when the stock BT app does not provide a standard Android MediaSession.
 */
class AutomotiveMediaBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AutoMediaReceiver"

        // Standard Android & OEM music player actions
        const val ACTION_ANDROID_METACHANGED = "com.android.music.metachanged"
        const val ACTION_ANDROID_PLAYSTATECHANGED = "com.android.music.playstatechanged"
        const val ACTION_ANDROID_PLAYBACKCOMPLETE = "com.android.music.playbackcomplete"
        const val ACTION_ANDROID_QUEUECHANGED = "com.android.music.queuechanged"
        const val ACTION_HTC_METACHANGED = "com.htc.music.metachanged"
        const val ACTION_LASTFM_METACHANGED = "fm.last.android.metachanged"
        const val ACTION_SEC_METACHANGED = "com.sec.android.app.music.metachanged"
        const val ACTION_WINAMP_METACHANGED = "com.nullsoft.Winamp.metachanged"
        const val ACTION_AMAZON_METACHANGED = "com.amazon.mp3.metachanged"
        const val ACTION_MIUI_METACHANGED = "com.miui.player.metachanged"
        const val ACTION_REAL_METACHANGED = "com.real.AndroidPlayer.metachanged"
        const val ACTION_SONY_METACHANGED = "com.sonyericsson.music.metachanged"
        const val ACTION_RDIO_METACHANGED = "com.rdio.android.metachanged"
        const val ACTION_SAMSUNG_METACHANGED = "com.samsung.sec.android.MusicPlayer.metachanged"

        // Bluetooth A2DP & AVRCP stack actions
        const val ACTION_BT_A2DP_PLAYING = "android.bluetooth.a2dp.profile.action.PLAYING_STATE_CHANGED"
        const val ACTION_BT_A2DP_CONNECTION = "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED"
        const val ACTION_BT_A2DP_SINK_PLAYING = "android.bluetooth.a2dp-sink.profile.action.PLAYING_STATE_CHANGED"
        const val ACTION_BT_A2DP_SINK_CONNECTION = "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED"
        const val ACTION_BT_AVRCP_TRACK = "android.bluetooth.avrcp-controller.profile.action.TRACK_CHANGED"
        const val ACTION_BT_ADAPTER_STATE = BluetoothAdapter.ACTION_STATE_CHANGED
        const val ACTION_BT_CONNECTION_STATE = BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED

        // Chinese Car Head Unit Vendor Bluetooth Actions (FYT, Microntek, TS10, Topway, etc.)
        const val ACTION_SYU_BT_TITLE = "com.syu.bt.music.title"
        const val ACTION_SYU_BT_ARTIST = "com.syu.bt.music.artist"
        const val ACTION_SYU_BT_PLAY = "com.syu.bt.music.play"
        const val ACTION_SYU_BT_PAUSE = "com.syu.bt.music.pause"
        const val ACTION_SYU_MUSIC = "com.syu.music"
        const val ACTION_TS_BT_MUSIC = "com.ts.bt.music"
        const val ACTION_MICRONTEK_BTMUSIC = "com.microntek.btmusic"
        const val ACTION_MICRONTEK_MUSIC_PLAY = "com.microntek.music.play"
        const val ACTION_MICRONTEK_MUSIC_TITLE = "com.microntek.music.title"
        const val ACTION_MICRONTEK_MUSIC_ARTIST = "com.microntek.music.artist"
        const val ACTION_MICRONTEK_MUSIC_ALBUM = "com.microntek.music.album"

        fun createIntentFilter(): IntentFilter {
            return IntentFilter().apply {
                // Android music broadcasts
                addAction(ACTION_ANDROID_METACHANGED)
                addAction(ACTION_ANDROID_PLAYSTATECHANGED)
                addAction(ACTION_ANDROID_PLAYBACKCOMPLETE)
                addAction(ACTION_ANDROID_QUEUECHANGED)
                addAction(ACTION_HTC_METACHANGED)
                addAction(ACTION_LASTFM_METACHANGED)
                addAction(ACTION_SEC_METACHANGED)
                addAction(ACTION_WINAMP_METACHANGED)
                addAction(ACTION_AMAZON_METACHANGED)
                addAction(ACTION_MIUI_METACHANGED)
                addAction(ACTION_REAL_METACHANGED)
                addAction(ACTION_SONY_METACHANGED)
                addAction(ACTION_RDIO_METACHANGED)
                addAction(ACTION_SAMSUNG_METACHANGED)

                // Bluetooth broadcasts
                addAction(ACTION_BT_A2DP_PLAYING)
                addAction(ACTION_BT_A2DP_CONNECTION)
                addAction(ACTION_BT_A2DP_SINK_PLAYING)
                addAction(ACTION_BT_A2DP_SINK_CONNECTION)
                addAction(ACTION_BT_AVRCP_TRACK)
                addAction(ACTION_BT_ADAPTER_STATE)
                addAction(ACTION_BT_CONNECTION_STATE)

                // Head Unit specific vendor broadcasts
                addAction(ACTION_SYU_BT_TITLE)
                addAction(ACTION_SYU_BT_ARTIST)
                addAction(ACTION_SYU_BT_PLAY)
                addAction(ACTION_SYU_BT_PAUSE)
                addAction(ACTION_SYU_MUSIC)
                addAction(ACTION_TS_BT_MUSIC)
                addAction(ACTION_MICRONTEK_BTMUSIC)
                addAction(ACTION_MICRONTEK_MUSIC_PLAY)
                addAction(ACTION_MICRONTEK_MUSIC_TITLE)
                addAction(ACTION_MICRONTEK_MUSIC_ARTIST)
                addAction(ACTION_MICRONTEK_MUSIC_ALBUM)
            }
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        Log.d(TAG, "Received automotive media broadcast: $action")

        try {
            // 1. Extract Title
            var title = extractTitle(intent)

            // 2. Extract Artist
            var artist = extractArtist(intent)

            // 3. Fallback: Parse "Artist - Title" or "Title - Artist" if one contains delimiter and other is blank
            if (artist.isNullOrBlank() && title != null && title.contains(" - ")) {
                val parts = title.split(" - ", limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                    title = parts[0].trim()
                    artist = parts[1].trim()
                }
            }

            // 4. Extract Album
            val album = extractAlbum(intent)

            // 5. Extract Playing State
            val isPlaying = extractPlayingState(intent, action)

            // 6. Extract Duration and Position with automatic seconds-to-millis scaling
            val durationMs = extractDuration(intent)
            val positionMs = extractPosition(intent, durationSecondsUnit(intent))

            val packageName = intent.getStringExtra("package") ?: intent.component?.packageName

            // Dispatch update if we got meaningful metadata or playback state change
            if (!title.isNullOrBlank() || !artist.isNullOrBlank() || isPlaying != null) {
                Log.i(TAG, "Parsed Media Broadcast -> Title: '$title', Artist: '$artist', DurationMs: $durationMs, PosMs: $positionMs, isPlaying: $isPlaying")
                MediaPlaybackManager.updateFromBroadcast(
                    title = title,
                    artist = artist,
                    album = album,
                    isPlaying = isPlaying,
                    durationMs = durationMs,
                    positionMs = positionMs,
                    packageName = packageName
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing automotive media broadcast", e)
        }
    }

    fun extractTitle(intent: Intent): String? {
        val bundle = intent.extras

        // 1. Check for MediaMetadata object (standard in Android AVRCP controller)
        if (bundle != null) {
            val mediaMetadata = try {
                bundle.getParcelable<MediaMetadata>("android.bluetooth.avrcp-controller.profile.extra.METADATA")
                    ?: bundle.getParcelable<MediaMetadata>("android.media.MediaMetadata")
                    ?: bundle.getParcelable<MediaMetadata>("EXTRA_METADATA")
            } catch (_: Exception) { null }

            if (mediaMetadata != null) {
                val t = mediaMetadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                    ?: mediaMetadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                if (!t.isNullOrBlank() && !isIgnoredPlaceholder(t)) return t.trim()
            }
        }

        // 2. High-priority exact keys (using bundle.get to support CharSequence, String, Spanned)
        val priorityKeys = listOf(
            "track", "title", "song", "name", "music_name", "song_name", "track_name",
            "com.syu.bt.music.title", "com.syu.music.title", "com.microntek.music.title",
            "com.ts.bt.music.title", "com.ts.bt.title", "ts_track", "ts_title",
            "EXTRA_TRACK", "EXTRA_TITLE", "a2dp_title", "bt_title", "bt_music_title",
            "trackTitle", "SongTitle", "MediaTitle", "android.media.metadata.TITLE",
            "android.intent.extra.TITLE"
        )
        if (bundle != null) {
            for (key in priorityKeys) {
                val v = bundle.get(key)?.toString()?.trim()
                if (!v.isNullOrBlank() && !isIgnoredPlaceholder(v)) return v
            }

            // 3. Dynamic search across all bundle keys
            for (key in bundle.keySet()) {
                val lower = key.lowercase()
                if ((lower.contains("title") || lower.contains("track") || lower.contains("song")) &&
                    !lower.contains("package") && !lower.contains("class") && !lower.contains("id")
                ) {
                    val v = bundle.get(key)?.toString()?.trim()
                    if (!v.isNullOrBlank() && !isIgnoredPlaceholder(v)) return v
                }
            }
        }

        return null
    }

    fun extractArtist(intent: Intent): String? {
        val bundle = intent.extras

        // 1. Check for MediaMetadata object
        if (bundle != null) {
            val mediaMetadata = try {
                bundle.getParcelable<MediaMetadata>("android.bluetooth.avrcp-controller.profile.extra.METADATA")
                    ?: bundle.getParcelable<MediaMetadata>("android.media.MediaMetadata")
                    ?: bundle.getParcelable<MediaMetadata>("EXTRA_METADATA")
            } catch (_: Exception) { null }

            if (mediaMetadata != null) {
                val a = mediaMetadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: mediaMetadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                    ?: mediaMetadata.getString(MediaMetadata.METADATA_KEY_AUTHOR)
                if (!a.isNullOrBlank() && !isIgnoredPlaceholder(a)) return a.trim()
            }
        }

        // 2. High-priority exact keys
        val priorityKeys = listOf(
            "artist", "singer", "author", "singer_name", "artist_name",
            "com.syu.bt.music.artist", "com.syu.music.artist", "com.microntek.music.artist",
            "com.ts.bt.music.artist", "com.ts.bt.artist", "ts_artist", "ts_singer",
            "EXTRA_ARTIST", "a2dp_artist", "bt_artist", "bt_music_artist",
            "trackArtist", "SingerName", "android.media.metadata.ARTIST"
        )
        if (bundle != null) {
            for (key in priorityKeys) {
                val v = bundle.get(key)?.toString()?.trim()
                if (!v.isNullOrBlank() && !isIgnoredPlaceholder(v)) return v
            }

            // 3. Dynamic search across all bundle keys
            for (key in bundle.keySet()) {
                val lower = key.lowercase()
                if ((lower.contains("artist") || lower.contains("singer") || lower.contains("author")) &&
                    !lower.contains("package") && !lower.contains("class")
                ) {
                    val v = bundle.get(key)?.toString()?.trim()
                    if (!v.isNullOrBlank() && !isIgnoredPlaceholder(v)) return v
                }
            }
        }

        return null
    }

    fun extractAlbum(intent: Intent): String? {
        val bundle = intent.extras ?: return null
        val keys = listOf("album", "EXTRA_ALBUM", "com.microntek.music.album", "album_name", "android.media.metadata.ALBUM")
        for (key in keys) {
            val v = bundle.get(key)?.toString()?.trim()
            if (!v.isNullOrBlank() && !isIgnoredPlaceholder(v)) return v
        }
        return null
    }

    fun extractPlayingState(intent: Intent, action: String): Boolean? {
        // Special actions
        when (action) {
            ACTION_ANDROID_PLAYBACKCOMPLETE, ACTION_SYU_BT_PAUSE -> return false
            ACTION_SYU_BT_PLAY, ACTION_MICRONTEK_MUSIC_PLAY -> return true
            ACTION_BT_A2DP_PLAYING, ACTION_BT_A2DP_SINK_PLAYING -> {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                if (state == BluetoothA2dp.STATE_PLAYING || state == 10) return true
                if (state == BluetoothA2dp.STATE_NOT_PLAYING || state == 11) return false
            }
            ACTION_BT_CONNECTION_STATE, ACTION_BT_A2DP_CONNECTION, ACTION_BT_A2DP_SINK_CONNECTION -> {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                if (state == BluetoothProfile.STATE_DISCONNECTED) return false
            }
        }

        val bundle = intent.extras ?: return null
        val boolKeys = listOf("playing", "isplaying", "playstate", "state", "isPlaying")
        for (key in boolKeys) {
            if (bundle.containsKey(key)) {
                val raw = bundle.get(key)
                when (raw) {
                    is Boolean -> return raw
                    is Number -> {
                        val intVal = raw.toInt()
                        if (intVal >= 0) return intVal == 1 || intVal == 10
                    }
                    is String -> {
                        if (raw.equals("true", ignoreCase = true) || raw == "1") return true
                        if (raw.equals("false", ignoreCase = true) || raw == "0") return false
                    }
                }
            }
        }

        return null
    }

    fun extractDuration(intent: Intent): Long? {
        val bundle = intent.extras ?: return null

        // 1. MediaMetadata
        try {
            val mediaMetadata = bundle.getParcelable<MediaMetadata>("android.bluetooth.avrcp-controller.profile.extra.METADATA")
                ?: bundle.getParcelable<MediaMetadata>("android.media.MediaMetadata")
            if (mediaMetadata != null) {
                // MediaMetadata durations are milliseconds by contract: never rescale them.
                val d = mediaMetadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
                if (d > 0L) return d
            }
        } catch (_: Exception) {}

        // 2. Bundle keys
        val keys = listOf("duration", "total_time", "totaltime", "max_time", "duration_ms", "time", "length")
        for (key in keys) {
            if (bundle.containsKey(key)) {
                val raw = bundle.get(key)
                val num = when (raw) {
                    is Number -> raw.toLong()
                    is String -> raw.toLongOrNull() ?: -1L
                    else -> -1L
                }
                if (num > 0L) return MediaTimeUnits.durationToMillis(num, key)
            }
        }
        return null
    }

    /**
     * Unit of the duration in this broadcast: true = seconds, false = milliseconds, null = no
     * duration. The position in the same broadcast uses the same unit (audit Q1).
     */
    private fun durationSecondsUnit(intent: Intent): Boolean? {
        val bundle = intent.extras ?: return null
        val keys = listOf("duration", "total_time", "totaltime", "max_time", "duration_ms", "time", "length")
        for (key in keys) {
            if (!bundle.containsKey(key)) continue
            val num = when (val raw = bundle.get(key)) {
                is Number -> raw.toLong()
                is String -> raw.toLongOrNull() ?: -1L
                else -> -1L
            }
            if (num > 0L) return MediaTimeUnits.isSecondsDuration(num, key)
        }
        return if (extractDuration(intent) != null) false else null
    }

    fun extractPosition(intent: Intent, durationInSeconds: Boolean? = null): Long? {
        val bundle = intent.extras ?: return null

        val keys = listOf("position", "current_time", "currenttime", "cur_time", "position_ms", "progress")
        for (key in keys) {
            if (bundle.containsKey(key)) {
                val raw = bundle.get(key)
                val num = when (raw) {
                    is Number -> raw.toLong()
                    is String -> raw.toLongOrNull() ?: -1L
                    else -> -1L
                }
                if (num >= 0L) return MediaTimeUnits.positionToMillis(num, key, durationInSeconds)
            }
        }
        return null
    }

    /**
     * Normalizes duration or position values to milliseconds.
     * Many Chinese car head units send duration/position in seconds (e.g. 240 for 4 mins).
     * If value is in 1..7200 (less than 2 hours in seconds), scale by 1000 to milliseconds.
     */
    fun normalizeToMillis(value: Long): Long {
        if (value <= 0L) return 0L
        return if (value in 1..7200) value * 1000L else value
    }

    fun isIgnoredPlaceholder(value: String): Boolean {
        val lower = value.trim().lowercase()
        return lower in listOf(
            "unknown", "null", "none", "title", "track", "no title", "<unknown>",
            "unknown artist", "unknown title", "unknown track", "audio playing", "car audio",
            "bluetooth audio", "connected device"
        )
    }
}
