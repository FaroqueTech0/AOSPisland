package com.faroquetech.aospisland

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.media.session.MediaSession
import android.media.session.MediaController
import android.media.session.PlaybackState

class IslandMediaListener : NotificationListenerService() {
    companion object {
        const val ACTION_MEDIA_UPDATE = "com.faroquetech.aospisland.MEDIA_UPDATE"
        fun requestPermission(context: Context) { context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        fun publishCurrent(context: Context) {
            try { context.startService(Intent(context, IslandMediaListener::class.java).setAction("refresh")) } catch (_: Exception) { }
        }
    }
    override fun onListenerConnected() { super.onListenerConnected(); publishFromNotifications() }
    override fun onNotificationPosted(sbn: StatusBarNotification?) { publishFromNotifications() }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) { publishFromNotifications() }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { publishFromNotifications(); return START_NOT_STICKY }
    private fun publishFromNotifications() {
        try {
            val candidates = activeNotifications ?: emptyArray()
            for (sbn in candidates.reversed()) {
                val extras = sbn.notification.extras ?: continue
                val token = extras.getParcelable<MediaSession.Token>("android.mediaSession") ?: continue
                val controller = MediaController(this, token)
                val metadata = controller.metadata
                val playback = controller.playbackState
                val state = playback?.state == PlaybackState.STATE_PLAYING
                val title = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: extras.getCharSequence("android.title")?.toString() ?: "Now playing"
                val artist = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: extras.getCharSequence("android.text")?.toString() ?: "Media playback"
                sendBroadcast(Intent(ACTION_MEDIA_UPDATE).setPackage(packageName).putExtra("title", title).putExtra("subtitle", artist).putExtra("playing", state).putExtra("token", token))
                return
            }
            sendBroadcast(Intent(ACTION_MEDIA_UPDATE).setPackage(packageName).putExtra("title", "").putExtra("subtitle", "").putExtra("playing", false))
        } catch (_: Exception) { }
    }
}
