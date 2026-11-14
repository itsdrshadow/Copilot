package com.antigravity.copilot.core.media

import android.graphics.Bitmap

data class MediaPlaybackState(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArt: Bitmap? = null,
    val isPlaying: Boolean = false,
    val packageName: String? = null,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val hasActiveSession: Boolean = false
) {
    val displayTitle: String
        get() = if (title.isNotBlank()) title else if (hasActiveSession) "Audio Playing" else "Media Idle"

    val displayArtist: String
        get() = if (artist.isNotBlank()) artist else if (hasActiveSession) "Car Audio" else "Tap to Play Music"
}
