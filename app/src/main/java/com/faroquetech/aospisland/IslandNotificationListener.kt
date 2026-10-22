package com.faroquetech.aospisland

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Overlay mode: provides notification access, which unlocks media-session access and call / timer / live activities.
 * (In LSPosed mode the SystemUI hook feeds the same data without needing this permission.)
 */
class IslandNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        IslandHub.canceller = { k -> try { cancelNotification(k) } catch (ignored: Throwable) { } }
        try { IslandHub.snapshot = activeNotifications } catch (ignored: Throwable) { }
        IslandHub.runtime?.let { it.notifications.reset(IslandHub.snapshot); it.window.updateNotifications(IslandHub.snapshot?.toList().orEmpty()); it.restartMedia() }
    }
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        try { IslandHub.snapshot = activeNotifications } catch (ignored: Throwable) { }
        IslandHub.runtime?.let { it.notifications.post(sbn); it.window.updateNotifications(IslandHub.snapshot?.toList().orEmpty()) }
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        try { IslandHub.snapshot = activeNotifications } catch (ignored: Throwable) { }
        IslandHub.runtime?.let { it.notifications.remove(sbn.key); it.window.updateNotifications(IslandHub.snapshot?.toList().orEmpty()) }
    }

    companion object {
        fun isEnabled(ctx: Context): Boolean =
            Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")?.contains(ctx.packageName) == true
        fun requestAccess(ctx: Context) {
            ctx.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        @Suppress("unused") fun component(ctx: Context) = ComponentName(ctx, IslandNotificationListener::class.java)
    }
}
