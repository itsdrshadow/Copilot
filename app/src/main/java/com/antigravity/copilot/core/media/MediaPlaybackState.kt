package com.antigravity.copilot.core.media

import android.graphics.Bitmap
import android.media.session.PlaybackState

data class MediaPlaybackState(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArt: Bitmap? = null,
    val isPlaying: Boolean = false,
    val packageName: String? = null,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val lastPositionUpdateTime: Long = 0L,
    val playbackStateCode: Int = PlaybackState.STATE_NONE,
    val hasActiveSession: Boolean = false
) {
    val displayTitle: String
        get() = if (title.isNotBlank()) title else if (hasActiveSession) "Audio Playing" else if (isPlaying) "Bluetooth Audio" else "Media Idle"

    val displayArtist: String
        get() = if (artist.isNotBlank()) artist else if (hasActiveSession) "Car Audio" else if (isPlaying) "Connected Device" else "Tap to Play Music"

    /**
     * True when music is actively playing or actively loaded in a valid paused/buffering session.
     * Automatically returns false when media is stopped, none, or session is inactive.
     */
    val isMusicActive: Boolean
        get() = (hasActiveSession || isPlaying || title.isNotBlank()) &&
                (isPlaying || (title.isNotBlank() &&
                playbackStateCode != PlaybackState.STATE_STOPPED &&
                playbackStateCode != PlaybackState.STATE_NONE &&
                playbackStateCode != PlaybackState.STATE_ERROR))

    /**
     * Calculates the estimated current playback position based on elapsed realtime,
     * avoiding battery-consuming continuous IPC polling.
     */
    fun calculateLivePositionMs(currentElapsedRealtime: Long): Long {
        if (!isPlaying || lastPositionUpdateTime <= 0L) return positionMs
        val elapsed = (currentElapsedRealtime - lastPositionUpdateTime).coerceAtLeast(0L)
        val calculated = positionMs + (elapsed * playbackSpeed).toLong()
        return if (durationMs > 0L) calculated.coerceIn(0L, durationMs) else calculated.coerceAtLeast(0L)
    }
}
