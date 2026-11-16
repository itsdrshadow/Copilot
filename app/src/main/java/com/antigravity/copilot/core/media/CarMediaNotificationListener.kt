package com.antigravity.copilot.core.media

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class CarMediaNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "CarMediaNotifListener"
    }

    private var sessionManager: MediaSessionManager? = null

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        val active = controllers?.firstOrNull { it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING }
            ?: controllers?.firstOrNull()
        MediaPlaybackManager.setActiveController(active)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        try {
            sessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            val component = ComponentName(this, CarMediaNotificationListener::class.java)
            sessionManager?.addOnActiveSessionsChangedListener(sessionListener, component)
            val initialControllers = sessionManager?.getActiveSessions(component)
            val active = initialControllers?.firstOrNull { it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING }
                ?: initialControllers?.firstOrNull()
            MediaPlaybackManager.setActiveController(active)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        try {
            sessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
        } catch (_: Exception) {}
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        MediaPlaybackManager.refreshActiveSessions()
        val s = sbn ?: return
        val notification = s.notification ?: return
        val extras = notification.extras ?: return

        // 1. Try to get MediaSession token from notification if attached
        try {
            @Suppress("DEPRECATION")
            val token = extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
            if (token != null) {
                val controller = MediaController(this, token)
                MediaPlaybackManager.setActiveController(controller)
                return
            }
        } catch (_: Exception) {}

        // 2. Extract Title, Artist, Album, and Art from notification extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()

        @Suppress("DEPRECATION")
        val largeIcon = try {
            extras.getParcelable<Bitmap>(Notification.EXTRA_LARGE_ICON)
                ?: extras.getParcelable<Bitmap>(Notification.EXTRA_PICTURE)
        } catch (_: Exception) { null }

        val pkg = s.packageName ?: ""
        val isMediaNotification = notification.category == Notification.CATEGORY_TRANSPORT ||
                pkg.contains("music", ignoreCase = true) ||
                pkg.contains("bt", ignoreCase = true) ||
                pkg.contains("audio", ignoreCase = true) ||
                pkg.contains("radio", ignoreCase = true) ||
                pkg.contains("player", ignoreCase = true) ||
                pkg.contains("spotify", ignoreCase = true) ||
                pkg.contains("syu", ignoreCase = true) ||
                pkg.contains("ts", ignoreCase = true) ||
                pkg.contains("microntek", ignoreCase = true) ||
                notification.actions?.isNotEmpty() == true

        if (isMediaNotification && (!title.isNullOrBlank() || !text.isNullOrBlank())) {
            if (title != "Copilot" && title != "System UI" && title != "Android System") {
                Log.d(TAG, "Captured media notification from $pkg: '$title' by '$text'")
                MediaPlaybackManager.updateFromNotification(
                    title = title,
                    artist = text,
                    album = subText,
                    albumArt = largeIcon,
                    packageName = pkg
                )
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        MediaPlaybackManager.refreshActiveSessions()
    }
}
