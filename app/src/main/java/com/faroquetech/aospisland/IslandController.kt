package com.faroquetech.aospisland

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Collects activities from all sources, decides which one is the primary (compact/expanded) island and which one
 * is the detached bubble, and feeds the [IslandView]. Host agnostic: used by the overlay app and by the SystemUI hook.
 */
class IslandController(private val ctx: Context, private val view: IslandView) {
    private val main = Handler(Looper.getMainLooper())
    private val bySource = HashMap<String, List<IslandActivity>>()
    private val transients = LinkedHashMap<String, IslandActivity>()
    private val lastSeen = HashMap<String, Long>()
    private var pinnedId: String? = null
    private var lastPrimaryId: String? = null
    /** Charger + screen-record activities, for the lock-screen Now Bar. */
    var onLive: ((List<IslandActivity>) -> Unit)? = null
    /** Swipe-to-clear on a notification-backed activity. */
    var onClear: ((IslandActivity) -> Unit)? = null

    init {
        view.onAction = { a -> try { a.run() } catch (ignored: Throwable) { } }
        view.onOpen = { a -> open(a) }
        view.onCycle = { delta -> cycle(delta) }
        view.onSwap = { a ->
            // a persistent bubble (Bluetooth) never takes over the pill; tapping it briefly shows its name instead
            if (a.bubbleOnly) pushTransient(a.copy(id = "bt", priority = 75, transient = true, bubbleOnly = false), 3000L)
            else { pinnedId = a.id; publish() }
        }
        view.onClear = { a -> onClear?.invoke(a) }
    }

    /** Replace everything a source (e.g. "media", "notif") currently reports. */
    fun set(source: String, list: List<IslandActivity>) {
        main.post {
            val now = SystemClock.elapsedRealtime()
            for (a in list) if (lastSeen[a.id] == null) lastSeen[a.id] = now
            bySource[source] = list
            // forget activities that vanished so they re-enter as "new" later
            val live = bySource.values.flatten().map { it.id }.toSet() + transients.keys
            lastSeen.keys.retainAll(live)
            publish()
        }
    }

    /** Short-lived event (charger, bluetooth, ringer). Auto-dismisses. */
    fun pushTransient(a: IslandActivity, ms: Long = 3000L) {
        main.post {
            transients[a.id] = a
            lastSeen[a.id] = SystemClock.elapsedRealtime()
            pinnedId = null
            main.removeCallbacksAndMessages(a.id)
            main.postAtTime({ transients.remove(a.id); lastSeen.remove(a.id); publish() }, a.id, SystemClock.uptimeMillis() + ms)
            publish()
        }
    }

    fun clear() { main.post { bySource.clear(); transients.clear(); lastSeen.clear(); publish() } }

    private fun ordered(): List<IslandActivity> =
        (bySource.values.flatten() + transients.values)
            .sortedWith(compareByDescending<IslandActivity> { it.priority }.thenByDescending { lastSeen[it.id] ?: 0L })

    private fun publish() {
        val all = ordered()
        val list = all.filter { !it.bubbleOnly }
        val bubbleOnly = all.filter { it.bubbleOnly }
        // a ringing call always wins and clears any manual pin
        if (list.any { it.autoExpand }) pinnedId = null
        if (pinnedId != null && list.none { it.id == pinnedId }) pinnedId = null
        val primary = list.firstOrNull { it.id == pinnedId } ?: list.firstOrNull()
        val maxExtras = (view.config.maxActivities - 1).coerceIn(1, 4)
        // the Bluetooth bubble is always shown (after the regular ones), except while its own name pill is the primary
        val extras = (list.filter { it.id != primary?.id && !it.transient }.take(maxExtras) +
            bubbleOnly.filter { b -> primary?.kind != b.kind }).take(4)
        lastPrimaryId = primary?.id
        try { onLive?.invoke(list.filter { it.kind == Kind.RECORD || it.kind == Kind.CHARGING }) } catch (ignored: Throwable) { }
        view.bind(primary, extras)
    }

    private fun cycle(delta: Int) {
        val list = ordered().filter { !it.bubbleOnly }; if (list.size < 2) return
        val i = list.indexOfFirst { it.id == lastPrimaryId }.coerceAtLeast(0)
        pinnedId = list[(i + delta + list.size) % list.size].id
        publish()
    }

    private fun open(a: IslandActivity) {
        try {
            val pi: PendingIntent? = a.contentIntent
            if (pi != null) {
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    // allow the launch even though the island is not a foreground app
                    val o = android.app.ActivityOptions.makeBasic()
                    o.setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    pi.send(ctx, 0, null, null, null, null, o.toBundle())
                } else pi.send()
                return
            }
        } catch (ignored: Throwable) { }
        try {
            val pkg = a.packageName ?: return
            ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        } catch (ignored: Throwable) { }
    }
}
