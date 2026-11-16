package com.antigravity.copilot.core.media

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
            val title = extractTitle(intent)

            // 2. Extract Artist
            val artist = extractArtist(intent)

            // 3. Extract Album
            val album = extractAlbum(intent)

            // 4. Extract Playing State
            val isPlaying = extractPlayingState(intent, action)

            // 5. Extract Duration and Position
            val durationMs = extractDuration(intent)
            val positionMs = extractPosition(intent)

            val packageName = intent.getStringExtra("package") ?: intent.component?.packageName

            // Dispatch update if we got meaningful metadata or playback state change
            if (!title.isNullOrBlank() || !artist.isNullOrBlank() || isPlaying != null) {
                Log.i(TAG, "Parsed Media Broadcast -> Title: '$title', Artist: '$artist', isPlaying: $isPlaying")
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
        val keys = listOf(
            "track", "title", "song", "name", "music_name",
            "com.syu.bt.music.title", "EXTRA_TRACK", "track_name",
            "com.microntek.music.title"
        )
        for (key in keys) {
            val v = intent.getStringExtra(key)?.trim()
            if (!v.isNullOrEmpty()) return v
        }
        return null
    }

    fun extractArtist(intent: Intent): String? {
        val keys = listOf(
            "artist", "singer", "author",
            "com.syu.bt.music.artist", "EXTRA_ARTIST", "artist_name",
            "com.microntek.music.artist"
        )
        for (key in keys) {
            val v = intent.getStringExtra(key)?.trim()
            if (!v.isNullOrEmpty()) return v
        }
        return null
    }

    fun extractAlbum(intent: Intent): String? {
        val keys = listOf("album", "EXTRA_ALBUM", "com.microntek.music.album")
        for (key in keys) {
            val v = intent.getStringExtra(key)?.trim()
            if (!v.isNullOrEmpty()) return v
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

        // Boolean / Int extras
        val boolKeys = listOf("playing", "isplaying", "playstate", "state")
        for (key in boolKeys) {
            if (intent.hasExtra(key)) {
                return try {
                    intent.getBooleanExtra(key, false)
                } catch (_: Exception) {
                    val intVal = intent.getIntExtra(key, -1)
                    if (intVal >= 0) (intVal == 1 || intVal == 10) else null
                }
            }
        }

        return null
    }

    fun extractDuration(intent: Intent): Long? {
        val keys = listOf("duration", "total_time", "totaltime", "max_time")
        for (key in keys) {
            if (intent.hasExtra(key)) {
                val l = intent.getLongExtra(key, -1L)
                if (l > 0L) return l
                val i = intent.getIntExtra(key, -1)
                if (i > 0) return i.toLong()
            }
        }
        return null
    }

    fun extractPosition(intent: Intent): Long? {
        val keys = listOf("position", "current_time", "currenttime", "cur_time")
        for (key in keys) {
            if (intent.hasExtra(key)) {
                val l = intent.getLongExtra(key, -1L)
                if (l >= 0L) return l
                val i = intent.getIntExtra(key, -1)
                if (i >= 0) return i.toLong()
            }
        }
        return null
    }
}
