package com.antigravity.copilot.core.media

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class CarMediaNotificationListener : NotificationListenerService() {

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
        // Can re-check active session on notification post
        MediaPlaybackManager.refreshActiveSessions()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        MediaPlaybackManager.refreshActiveSessions()
    }
}
