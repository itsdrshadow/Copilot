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

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updateFromController(activeController)
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            updateFromController(activeController)
        }

        override fun onSessionDestroyed() {
            activeController = null
            _playbackState.value = _playbackState.value.copy(
                isPlaying = false,
                hasActiveSession = false
            )
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        refreshActiveSessions()
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
        val isPlaying = pbState?.state == PlaybackState.STATE_PLAYING

        _playbackState.value = MediaPlaybackState(
            title = title,
            artist = artist,
            album = album,
            albumArt = albumArt,
            isPlaying = isPlaying,
            packageName = controller.packageName,
            durationMs = duration.coerceAtLeast(0L),
            positionMs = position.coerceAtLeast(0L),
            hasActiveSession = true
        )
    }

    fun refreshActiveSessions() {
        val ctx = appContext ?: return
        try {
            val sessionManager = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            val notificationListenerComponent = ComponentName(ctx, CarMediaNotificationListener::class.java)
            val controllers = sessionManager?.getActiveSessions(notificationListenerComponent)
            val active = controllers?.firstOrNull()
            setActiveController(active)
        } catch (_: SecurityException) {
            // Notification access not granted yet; key-event fallback will still work
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
                return
            } catch (_: Exception) {}
        }
        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    }

    fun skipToNext() {
        val controller = activeController
        if (controller != null) {
            try {
                controller.transportControls.skipToNext()
                return
            } catch (_: Exception) {}
        }
        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
    }

    fun skipToPrevious() {
        val controller = activeController
        if (controller != null) {
            try {
                controller.transportControls.skipToPrevious()
                return
            } catch (_: Exception) {}
        }
        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
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
