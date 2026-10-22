package com.faroquetech.aospisland

import android.content.ComponentName
import android.content.Context
import android.service.notification.StatusBarNotification

/** Process-wide handle shared by the overlay service, the notification listener and the UI (demo buttons). */
object IslandHub {
    @Volatile var runtime: IslandRuntime? = null
    @Volatile var snapshot: Array<StatusBarNotification>? = null
    /** Cancels a notification by key (the overlay's listener, or SystemUI's own listener in system mode). */
    @Volatile var canceller: ((String) -> Unit)? = null
    fun cancel(key: String) { try { canceller?.invoke(key) } catch (_: Throwable) { } }
}

/**
 * Everything the island needs, independent of where the window lives (overlay app or SystemUI).
 * [listener] = enabled NotificationListenerService component for media-session access (null inside SystemUI).
 */
class IslandRuntime(private val ctx: Context, val window: IslandWindow, listener: ComponentName?, initial: IslandConfig) {
    @Volatile var config: IslandConfig = initial; private set
    val controller = IslandController(ctx, window.view)
    private var lastMedia: List<IslandActivity> = emptyList()
    private val media = MediaSource(ctx, listener, { pkg -> activeNotificationsForNowBar().any { it.packageName == pkg && NowBarView.isMediaNotification(it) } }) { lastMedia = it; controller.set("media", if (config.showMedia) it else emptyList()); window.updateMedia(it.firstOrNull { a -> a.kind == Kind.MEDIA && a.playing } ?: it.firstOrNull { a -> a.kind == Kind.MEDIA }) }
    private val events = SystemEventsSource(ctx, controller) { config }
    val notifications = NotificationSource(ctx, { config }) { controller.set("notif", it) }
    private var running = false

    init {
        window.rawNotifications = { activeNotificationsForNowBar() }; window.onFeed = { media.refresh() }; controller.onLive = { window.updateLive(it) }
        // swipe to clear: notification-backed island activities cancel their notification and leave the island right away
        controller.onClear = { a -> if (a.id.startsWith("live:")) { val k = a.id.removePrefix("live:"); IslandHub.cancel(k); notifications.remove(k) } }
    }

    /** Read-only copy of every notification the source currently knows (the SystemUI hook only passes "posted since load"). */
    @Suppress("UNCHECKED_CAST")
    private fun activeNotificationsForNowBar(): List<StatusBarNotification> = try {
        val f = NotificationSource::class.java.getDeclaredField("active"); f.isAccessible = true
        val m = f.get(notifications) as MutableMap<String, StatusBarNotification>
        synchronized(m) { m.values.toList() }
    } catch (_: Throwable) { emptyList() }

    fun start() {
        if (running) return
        running = true
        window.attach(config)
        window.updateNotifications(IslandHub.snapshot?.toList().orEmpty())
        media.start(); events.start()
    }

    fun stop() {
        if (!running) return
        running = false
        media.stop(); events.stop(); controller.clear(); window.detach()
    }

    /** Re-subscribe to media sessions, e.g. right after notification access was granted. */
    fun restartMedia() { if (running) media.start() }

    fun applyConfig(c: IslandConfig) {
        config = c
        window.update(c)
        controller.set("media", if (c.showMedia) lastMedia else emptyList())
        notifications.refresh()
    }
}
