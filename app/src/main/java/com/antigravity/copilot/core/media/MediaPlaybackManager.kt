package com.antigravity.copilot.core.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object MediaPlaybackManager {

    private val _playbackState = MutableStateFlow(MediaPlaybackState())
    val playbackState: StateFlow<MediaPlaybackState> = _playbackState.asStateFlow()

    private var activeController: MediaController? = null
    private var appContext: Context? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var broadcastReceiver: AutomotiveMediaBroadcastReceiver? = null

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updateFromController(activeController)
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            updateFromController(activeController)
        }

        override fun onSessionDestroyed() {
            activeController = null
            _playbackState.value = MediaPlaybackState(
                isPlaying = false,
                hasActiveSession = false,
                playbackStateCode = PlaybackState.STATE_NONE
            )
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        refreshActiveSessions()
        registerBroadcastReceiver(context.applicationContext)
    }

    private fun registerBroadcastReceiver(context: Context) {
        if (broadcastReceiver == null) {
            broadcastReceiver = AutomotiveMediaBroadcastReceiver()
            val filter = AutomotiveMediaBroadcastReceiver.createIntentFilter()
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(broadcastReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    context.registerReceiver(broadcastReceiver, filter)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun updateFromBroadcast(
        title: String?,
        artist: String?,
        album: String? = null,
        isPlaying: Boolean? = null,
        durationMs: Long? = null,
        positionMs: Long? = null,
        packageName: String? = null
    ) {
        mainHandler.post {
            val current = _playbackState.value

            val cleanTitle = title?.trim()?.takeIf { it.isNotEmpty() && !isIgnoredPlaceholder(it) }
            val cleanArtist = artist?.trim()?.takeIf { it.isNotEmpty() && !isIgnoredPlaceholder(it) }

            val newTitle = cleanTitle ?: current.title
            val newArtist = cleanArtist ?: current.artist
            val newAlbum = album?.trim()?.takeIf { it.isNotEmpty() && !isIgnoredPlaceholder(it) } ?: current.album
            val newPlaying = isPlaying ?: current.isPlaying

            // Duration: normalize if sent in seconds (<= 7200)
            val normalizedDuration = durationMs?.let { if (it in 1..7200) it * 1000L else it }
            val newDuration = normalizedDuration?.takeIf { it > 0L } ?: current.durationMs

            // Position: normalize if sent in seconds (<= 7200)
            val normalizedPosition = positionMs?.let { if (it in 1..7200) it * 1000L else it }

            val isNewTrack = cleanTitle != null && !cleanTitle.equals(current.title, ignoreCase = true)
            val nowElapsed = android.os.SystemClock.elapsedRealtime()

            val newPosition: Long
            val lastUpdate: Long

            if (isNewTrack) {
                // Track changed: reset playback position to start
                newPosition = normalizedPosition ?: 0L
                lastUpdate = nowElapsed
            } else if (normalizedPosition != null && normalizedPosition > 0L) {
                // Incoming broadcast has an explicit non-zero timestamp
                val estimatedLive = current.calculateLivePositionMs(nowElapsed)
                if (Math.abs(normalizedPosition - estimatedLive) > 2500L) {
                    newPosition = normalizedPosition
                    lastUpdate = nowElapsed
                } else {
                    newPosition = estimatedLive
                    lastUpdate = nowElapsed
                }
            } else {
                // Position is 0 or null (e.g. keepalive broadcast):
                // DO NOT reset to 0! Keep smoothly advancing live position
                if (newPlaying) {
                    val estimatedLive = current.calculateLivePositionMs(nowElapsed)
                    newPosition = estimatedLive
                    lastUpdate = nowElapsed
                } else {
                    newPosition = current.positionMs
                    lastUpdate = if (current.lastPositionUpdateTime > 0L) current.lastPositionUpdateTime else nowElapsed
                }
            }

            val stateCode = when {
                newPlaying -> PlaybackState.STATE_PLAYING
                newTitle.isNotBlank() -> PlaybackState.STATE_PAUSED
                else -> PlaybackState.STATE_STOPPED
            }

            _playbackState.value = current.copy(
                title = newTitle,
                artist = newArtist,
                album = newAlbum,
                isPlaying = newPlaying,
                durationMs = newDuration,
                positionMs = newPosition,
                playbackStateCode = stateCode,
                hasActiveSession = newPlaying || newTitle.isNotBlank() || current.hasActiveSession,
                packageName = packageName ?: current.packageName,
                lastPositionUpdateTime = lastUpdate
            )
        }
    }

    fun updateFromNotification(
        title: String?,
        artist: String?,
        album: String? = null,
        albumArt: android.graphics.Bitmap? = null,
        packageName: String? = null
    ) {
        mainHandler.post {
            val current = _playbackState.value

            val cleanTitle = title?.trim()?.takeIf { it.isNotEmpty() && !isIgnoredPlaceholder(it) }
            val cleanArtist = artist?.trim()?.takeIf { it.isNotEmpty() && !isIgnoredPlaceholder(it) }

            if (cleanTitle != null || cleanArtist != null) {
                val isNewTrack = cleanTitle != null && !cleanTitle.equals(current.title, ignoreCase = true)
                val nowElapsed = android.os.SystemClock.elapsedRealtime()

                val newPosition = if (isNewTrack) 0L else current.calculateLivePositionMs(nowElapsed)

                _playbackState.value = current.copy(
                    title = cleanTitle ?: current.title,
                    artist = cleanArtist ?: current.artist,
                    album = album?.trim()?.takeIf { it.isNotEmpty() && !isIgnoredPlaceholder(it) } ?: current.album,
                    albumArt = albumArt ?: current.albumArt,
                    isPlaying = true,
                    hasActiveSession = true,
                    playbackStateCode = PlaybackState.STATE_PLAYING,
                    packageName = packageName ?: current.packageName,
                    positionMs = newPosition,
                    lastPositionUpdateTime = nowElapsed
                )
            }
        }
    }

    private fun isIgnoredPlaceholder(value: String): Boolean {
        val lower = value.trim().lowercase()
        return lower in listOf(
            "unknown", "null", "none", "title", "track", "no title", "<unknown>",
            "unknown artist", "unknown title", "unknown track", "audio playing", "car audio",
            "bluetooth audio", "connected device"
        )
    }

    fun setActiveController(controller: MediaController?) {
        mainHandler.post {
            try {
                activeController?.unregisterCallback(controllerCallback)
            } catch (_: Exception) {}

            activeController = controller

            if (controller != null) {
                try {
                    controller.registerCallback(controllerCallback, mainHandler)
                    updateFromController(controller)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                _playbackState.value = _playbackState.value.copy(hasActiveSession = false, isPlaying = false)
            }
        }
    }

    fun updateFromController(controller: MediaController?) {
        if (controller == null) {
            _playbackState.value = _playbackState.value.copy(hasActiveSession = false, isPlaying = false)
            return
        }

        val metadata = try { controller.metadata } catch (_: Exception) { null }
        val pbState = try { controller.playbackState } catch (_: Exception) { null }

        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: ""

        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""

        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""

        val albumArt = try {
            metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        } catch (_: Exception) { null }

        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val position = pbState?.position ?: 0L
        val lastUpdateTime = pbState?.lastPositionUpdateTime ?: 0L
        val speed = pbState?.playbackSpeed ?: 1.0f
        val stateCode = pbState?.state ?: PlaybackState.STATE_NONE
        val isPlaying = stateCode == PlaybackState.STATE_PLAYING

        _playbackState.value = MediaPlaybackState(
            title = title,
            artist = artist,
            album = album,
            albumArt = albumArt,
            isPlaying = isPlaying,
            packageName = controller.packageName,
            durationMs = duration.coerceAtLeast(0L),
            positionMs = position.coerceAtLeast(0L),
            playbackSpeed = speed,
            lastPositionUpdateTime = lastUpdateTime,
            playbackStateCode = stateCode,
            hasActiveSession = true
        )
    }

    fun refreshActiveSessions() {
        val ctx = appContext ?: return
        try {
            val sessionManager = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            val notificationListenerComponent = ComponentName(ctx, CarMediaNotificationListener::class.java)
            val controllers = sessionManager?.getActiveSessions(notificationListenerComponent)
            val active = controllers?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: controllers?.firstOrNull()
            setActiveController(active)
        } catch (_: SecurityException) {
            // Notification access not granted yet; key-event fallback will still work
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun seekTo(positionMs: Long) {
        val controller = activeController
        if (controller != null) {
            try {
                controller.transportControls.seekTo(positionMs)
            } catch (_: Exception) {}
        }
    }

    fun setTestPlaybackState(state: MediaPlaybackState) {
        _playbackState.value = state
    }

    fun togglePlayPause() {
        val controller = activeController
        if (controller != null) {
            val isPlaying = _playbackState.value.isPlaying
            try {
                if (isPlaying) {
                    controller.transportControls.pause()
                } else {
                    controller.transportControls.play()
                }
                broadcastHeadUnitCommand("PLAY_PAUSE")
                return
            } catch (_: Exception) {}
        }
        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        broadcastHeadUnitCommand("PLAY_PAUSE")
    }

    fun skipToNext() {
        val controller = activeController
        if (controller != null) {
            try {
                controller.transportControls.skipToNext()
                broadcastHeadUnitCommand("NEXT")
                return
            } catch (_: Exception) {}
        }
        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        broadcastHeadUnitCommand("NEXT")
    }

    fun skipToPrevious() {
        val controller = activeController
        if (controller != null) {
            try {
                controller.transportControls.skipToPrevious()
                broadcastHeadUnitCommand("PREV")
                return
            } catch (_: Exception) {}
        }
        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        broadcastHeadUnitCommand("PREV")
    }

    private fun broadcastHeadUnitCommand(command: String) {
        val ctx = appContext ?: return
        try {
            when (command) {
                "PLAY_PAUSE" -> {
                    ctx.sendBroadcast(Intent("com.syu.bt.music.playpause"))
                    ctx.sendBroadcast(Intent("com.microntek.btmusic.play"))
                    ctx.sendBroadcast(Intent("com.ts.bt.music.play"))
                }
                "NEXT" -> {
                    ctx.sendBroadcast(Intent("com.syu.bt.music.next"))
                    ctx.sendBroadcast(Intent("com.microntek.btmusic.next"))
                    ctx.sendBroadcast(Intent("com.ts.bt.music.next"))
                }
                "PREV" -> {
                    ctx.sendBroadcast(Intent("com.syu.bt.music.prev"))
                    ctx.sendBroadcast(Intent("com.microntek.btmusic.prev"))
                    ctx.sendBroadcast(Intent("com.ts.bt.music.prev"))
                }
            }
        } catch (_: Exception) {}
    }

    private fun dispatchMediaKey(keyCode: Int) {
        val ctx = appContext ?: return
        try {
            val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun openActivePlayerApp(context: Context) {
        val pkg = _playbackState.value.packageName
        if (!pkg.isNullOrBlank()) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                return
            }
        }
        // Fallback: open default music intent
        try {
            val musicIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_APP_MUSIC)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(musicIntent)
        } catch (_: Exception) {}
    }

    fun isNotificationAccessGranted(context: Context): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        val pkg = context.packageName
        return flat?.contains(pkg) == true
    }

    fun openNotificationAccessSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }
}
