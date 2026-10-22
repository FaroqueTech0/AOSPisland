package com.faroquetech.aospisland

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min

/**
 * Lock-screen Now Bar (separate from the island - nothing here touches IslandView).
 *  - Media -> album-art pill with prev / play-pause / next.
 *  - Every lock-screen notification is routed here as a Samsung-style app-coloured pill:
 *      tap = expand into a card (full text + action buttons), swipe up/down = change notification with a fixed-position crossfade,
 *      swipe left/right = dismiss the current notification, tap the dots = next.
 */
class NowBarView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ctlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.6f * d; strokeCap = Paint.Cap.ROUND }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 13f * d; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val title2Paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 15f * d; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt(); textSize = 12f * d }
    private val body2Paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6FFFFFF.toInt(); textSize = 13.5f * d }
    private val smallPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3FFFFFF.toInt(); textSize = 11f * d }
    private val chipPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 12.5f * d; textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val path = Path()

    @Volatile private var media: IslandActivity? = null
    private var mediaNotif: StatusBarNotification? = null
    private var feedSeen = false
    private var lastMediaSig = ""
    private val tick = object : Runnable { override fun run() { if (isShown && media?.playing == true) { invalidate(); main.postDelayed(this, 1000L) } } }
    @Volatile private var notifs: List<StatusBarNotification> = emptyList()
    private var raw: List<StatusBarNotification> = emptyList()
    private val dismissed = HashSet<String>()
    private var index = 0
    // card-stack transition
    private var animT = 1f
    private var animDir = 1
    private var outgoing: Page? = null
    private var outgoingIndex = 0
    private var animator: android.animation.ValueAnimator? = null
    // A fresh notification gets an app-specific icon pop/glow, independent of the card-stack transition.
    private var pulseKey: String? = null
    private var pulseProgress = 1f
    private var pulseAnimator: android.animation.ValueAnimator? = null

    /** Moves to another page with the stacked-card transition (dir > 0: next / old card lifts away, dir < 0: previous / new card drops in). */
    private fun switchTo(newIdx: Int, dir: Int) {
        val pgs = pages()
        if (pgs.isEmpty() || newIdx == index) { index = newIdx; return }
        outgoing = pgs[index.coerceIn(0, pgs.lastIndex)]; outgoingIndex = index
        animDir = dir; index = newIdx; animT = 0f
        animator?.cancel()
        animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 440L; interpolator = android.view.animation.PathInterpolator(0.22f, 1f, 0.36f, 1f)
            addUpdateListener { animT = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(a: android.animation.Animator) { animT = 1f; outgoing = null; invalidate() } })
            start()
        }
    }
    private var expanded = false
    private var knownKeys: Set<String>? = null
    private val iconCache = HashMap<String, Drawable?>()
    private var avgArt: Bitmap? = null; private var avgColor = 0
    private val revert = Runnable { switchTo(0, -1); invalidate() }
    private val collapse = Runnable { setExpanded(false) }

    private var hitStop = RectF(); private var hitPrev = RectF(); private var hitPlay = RectF(); private var hitNext = RectF(); private var hitDots = RectF()
    private var hitClose = RectF(); private var hitHeader = RectF(); private var hitBody = RectF()
    private val hitButtons = ArrayList<Pair<RectF, () -> Unit>>()
    private var downX = 0f; private var downY = 0f
    private var swipeTracking = false
    private var swipeAxis = 0 // 1 horizontal, 2 vertical
    private var swipeDx = 0f; private var swipeDy = 0f
    private var swipeAnimator: android.animation.ValueAnimator? = null

    /** Called on the main thread when content or size changes (the host window re-measures and repositions). */
    var onContentChanged: (() -> Unit)? = null
    /** Optional: lets a host also cancel the real notification when the user swipes it away here. */
    var onDismiss: ((String) -> Unit)? = null

    val hasContent: Boolean get() = shownMedia() != null || notifs.isNotEmpty() || liveShown().isNotEmpty()
    val stats: String get() = "raw=${raw.size} shown=${notifs.size} media=${media != null} expanded=$expanded"
    /** Height of the collapsed pill (used for the position maths so the slider maps to the same spot everywhere). */
    val contentHeightPx: Int get() = heightFor(d)

    private sealed class Page { class Media(val a: IslandActivity) : Page(); class Notif(val sbn: StatusBarNotification) : Page(); class Live(val a: IslandActivity) : Page() }
    /** Media is linked to its notification: swiped away / removed there = gone from the Now Bar (unless it is playing). */
    private fun shownMedia(): IslandActivity? {
        val m = media ?: return null
        if (m.id == "demo" || m.playing || !feedSeen) return m
        return if (mediaNotif != null) m else null
    }
    private fun pages(): List<Page> = liveShown().map { Page.Live(it) } + listOfNotNull(shownMedia()?.let { Page.Media(withNotifFallback(it)) }) + notifs.map { Page.Notif(it) }

    // ---- live activities moved here from the island on the lock screen: charger + screen recording ----
    private var liveRaw: List<IslandActivity> = emptyList()
    private val held = LinkedHashMap<String, Pair<IslandActivity, Long>>()
    private val chargeSeen = HashMap<String, Long>()
    private val fxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fxPath = Path()
    private val pruneHeld = Runnable { pruneAndRefresh() }
    private var animRunning = false
    private val animTick = object : Runnable { override fun run() { if (isShown && currentIsLive()) { invalidate(); postOnAnimation(this) } else animRunning = false } }
    private fun ensureAnim() { if (!animRunning) { animRunning = true; postOnAnimation(animTick) } }
    private fun currentIsLive(): Boolean {
        val p = pages(); if (p.isEmpty()) return false
        val pg = p[index.coerceIn(0, p.lastIndex)]
        return pg is Page.Live || (pg is Page.Notif && progressOf(pg.sbn) == -1f)
    }
    private fun mediaIdx(): Int = liveShown().size

    /** Download / upload / install progress carried by a notification: null = none, -1 = indeterminate, else 0..1. */
    private fun progressOf(sbn: StatusBarNotification): Float? {
        val ex = sbn.notification?.extras ?: return null
        val max = ex.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val ind = ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
        if (max <= 0 && !ind) return null
        if (ind || max <= 0) return -1f
        val f = (ex.getInt(Notification.EXTRA_PROGRESS, 0).toFloat() / max).coerceIn(0f, 1f)
        return if (f >= 1f) null else f
    }

    private fun drawProgressBar(c: Canvas, x0: Float, x1: Float, y: Float, bh: Float, frac: Float) {
        val track = RectF(x0, y - bh / 2, x1, y + bh / 2)
        artPaint.color = 0x38FFFFFF; c.drawRoundRect(track, bh / 2, bh / 2, artPaint)
        artPaint.color = Color.WHITE
        if (frac >= 0f) {
            c.drawRoundRect(RectF(x0, track.top, x0 + (x1 - x0) * frac.coerceAtLeast(0.02f), track.bottom), bh / 2, bh / 2, artPaint)
        } else {
            val seg = (x1 - x0) * 0.35f
            val t = (android.os.SystemClock.uptimeMillis() % 1400L) / 1400f
            val sx = x0 - seg + (x1 - x0 + seg) * t
            val sv = c.save(); path.reset(); path.addRoundRect(track, bh / 2, bh / 2, Path.Direction.CW); c.clipPath(path)
            c.drawRoundRect(RectF(sx, track.top, sx + seg, track.bottom), bh / 2, bh / 2, artPaint)
            c.restoreToCount(sv)
        }
    }

    /** Persistent live activities (screen recording) plus charger events, which stay for a few seconds even after the island's own timer ends. */
    private fun liveShown(): List<IslandActivity> {
        val now = android.os.SystemClock.uptimeMillis()
        val out = ArrayList<IslandActivity>()
        for (a in liveRaw) if (!a.transient) out.add(a)
        for ((_, v) in held) if (v.second > now) out.add(v.first)
        return out.sortedByDescending { it.priority }
    }

    private fun pruneAndRefresh() {
        val now = android.os.SystemClock.uptimeMillis()
        held.entries.removeAll { it.value.second <= now }
        chargeSeen.keys.retainAll(held.keys)
        index = index.coerceIn(0, (pages().size - 1).coerceAtLeast(0)); fixExpanded()
        invalidate(); onContentChanged?.invoke()
    }

    /** Charger / screen-record activities from the island controller. */
    fun setLive(list: List<IslandActivity>) {
        main.post {
            val before = liveShown().map { it.id }.toSet()
            liveRaw = list
            val now = android.os.SystemClock.uptimeMillis()
            for (a in list) if (a.transient) { val old = held[a.id]; if (old == null || old.second <= now) chargeSeen.remove(a.id); held[a.id] = a to (if (old != null && old.second > now) old.second else now + 5500L) }
            held.entries.removeAll { it.value.second <= now }
            main.removeCallbacks(pruneHeld); if (held.isNotEmpty()) main.postDelayed(pruneHeld, 5600L)
            val fresh = liveShown().firstOrNull { it.id !in before }
            if (fresh != null) {
                expanded = false; main.removeCallbacks(collapse); main.removeCallbacks(revert)
                val at = pages().indexOfFirst { it is Page.Live && it.a.id == fresh.id }.coerceAtLeast(0)
                switchTo(at, -1)
            }
            index = index.coerceIn(0, (pages().size - 1).coerceAtLeast(0)); fixExpanded()
            invalidate(); onContentChanged?.invoke()
        }
    }

    /** Fill in what the media session left blank from the media notification itself (title, text, large icon). */
    private fun withNotifFallback(a: IslandActivity): IslandActivity {
        val n = mediaNotif ?: return a
        val ex = n.notification.extras
        val t = if (a.title.isBlank()) ex?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty() else a.title
        val s = if (a.subtitle.isBlank()) ex?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty() else a.subtitle
        var art = a.art
        if (art == null) try { art = (n.notification.getLargeIcon()?.loadDrawable(context) as? android.graphics.drawable.BitmapDrawable)?.bitmap } catch (_: Throwable) { }
        val ci = a.contentIntent ?: n.notification.contentIntent
        return if (t == a.title && s == a.subtitle && art === a.art && ci === a.contentIntent) a else a.copy(title = t, subtitle = s, art = art, contentIntent = ci)
    }
    private fun currentNotif(): StatusBarNotification? {
        val p = pages(); if (p.isEmpty()) return null
        return (p[index.coerceIn(0, p.lastIndex)] as? Page.Notif)?.sbn
    }

    // ------------------------------------------------------------------------------------------ size (asked by the window)

    fun desiredWidthPx(screenW: Int): Int = if (expanded && currentNotif() != null) expandedWidthFor(d, screenW) else widthFor(d, screenW)
    fun desiredHeightPx(screenW: Int): Int {
        val sbn = currentNotif()
        return if (expanded && sbn != null) expandedLayout(sbn, expandedWidthFor(d, screenW)).height else heightFor(d)
    }

    // ------------------------------------------------------------------------------------------ data in

    fun setMedia(a: IslandActivity?) {
        main.post {
            val old = media
            media = a
            mediaNotif = a?.packageName?.let { pkg -> raw.firstOrNull { it.packageName == pkg && isMediaNotification(it) } }
            // a new track, a different player or play pressed -> bring the media page to the front (like Samsung)
            val sig = if (a == null) "" else "${a.packageName}|${a.title}|${a.subtitle}"
            val becamePlaying = a?.playing == true && old?.playing != true
            if (a != null && (sig != lastMediaSig || becamePlaying)) { switchTo(mediaIdx(), -1); expanded = false; main.removeCallbacks(collapse); main.removeCallbacks(revert) }
            lastMediaSig = sig
            index = index.coerceIn(0, (pages().size - 1).coerceAtLeast(0)); fixExpanded()
            main.removeCallbacks(tick); if (a?.playing == true) main.post(tick)
            invalidate(); onContentChanged?.invoke()
        }
    }

    fun setNotifications(list: List<StatusBarNotification>) { main.post { raw = list; recompute() } }

    private fun recompute() {
        val mine = context.packageName
        feedSeen = true
        mediaNotif = raw.filter { isMediaNotification(it) }.let { l -> media?.packageName?.let { pkg -> l.firstOrNull { it.packageName == pkg } } }
        val clean = raw.filter {
            val n = it.notification
            n != null && it.packageName != mine && !isMediaNotification(it) &&
                (n.flags and Notification.FLAG_GROUP_SUMMARY) == 0 && "${it.key}|${it.postTime}" !in dismissed
        }.sortedWith(compareBy<StatusBarNotification> { it.isOngoing && progressOf(it) == null }.thenByDescending { it.postTime }).take(10)
        val liveIds = raw.map { "${it.key}|${it.postTime}" }.toSet()
        dismissed.retainAll(liveIds)
        val keys = clean.map { it.key }.toSet()
        val old = knownKeys
        notifs = clean
        if (old != null) {
            val fresh = clean.firstOrNull { it.key !in old && (!it.isOngoing || progressOf(it) != null) }
            if (fresh != null) {
                // Trigger the Now Bar's per-app entrance animation whenever any app posts a new notification.
                pulseKey = fresh.key
                pulseAnimator?.cancel()
                pulseProgress = 0f
                pulseAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 620L
                    interpolator = android.view.animation.OvershootInterpolator(1.25f)
                    addUpdateListener { pulseProgress = (it.animatedValue as Float).coerceIn(0f, 1.15f); invalidate() }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(a: android.animation.Animator) { pulseProgress = 1f; pulseKey = null; invalidate() }
                    })
                    start()
                }
                switchTo(pages().indexOfFirst { it is Page.Notif && it.sbn.key == fresh.key }.coerceAtLeast(0), 1)
                expanded = false
                main.removeCallbacks(revert)
                if (media != null) main.postDelayed(revert, 6000L)
            }
        }
        knownKeys = keys
        index = index.coerceIn(0, (pages().size - 1).coerceAtLeast(0))
        fixExpanded()
        invalidate(); onContentChanged?.invoke()
    }

    private fun fixExpanded() { if (expanded && currentNotif() == null) { expanded = false; main.removeCallbacks(collapse) } }

    /** Settings-screen preview only. */
    fun showDemo() {
        media = IslandActivity(
            id = "demo", kind = Kind.MEDIA, priority = 0, title = "Bigger In Texas", subtitle = "Demo", playing = true,
            accent = 0xFF9B4B55.toInt(),
            actions = listOf(ActivityAction(ActionKind.PREV) { }, ActivityAction(ActionKind.PLAYPAUSE) { }, ActivityAction(ActionKind.NEXT) { })
        )
        invalidate()
    }

    // ------------------------------------------------------------------------------------------ state changes

    private fun setExpanded(v: Boolean) {
        if (v && currentNotif() == null) return
        expanded = v
        main.removeCallbacks(collapse)
        if (v) main.postDelayed(collapse, 9000L)
        invalidate(); onContentChanged?.invoke()
    }

    private fun cycle(delta: Int) {
        val n = pages().size; if (n < 2) return
        switchTo((index + delta + n) % n, if (delta >= 0) 1 else -1)
        main.removeCallbacks(revert); expanded = false; main.removeCallbacks(collapse)
        invalidate(); onContentChanged?.invoke()
    }

    private fun dismiss(sbn: StatusBarNotification) {
        dismissed.add("${sbn.key}|${sbn.postTime}")
        expanded = false; main.removeCallbacks(collapse)
        try { onDismiss?.invoke(sbn.key) } catch (_: Throwable) { }
        recompute()
    }

    // ------------------------------------------------------------------------------------------ helpers

    private fun tint(a: IslandActivity): Int {
        val art = a.art
        var c = a.accent
        if (art != null) {
            if (art !== avgArt) {
                avgArt = art
                avgColor = try { Bitmap.createScaledBitmap(art, 1, 1, true).getPixel(0, 0) } catch (_: Throwable) { 0 }
            }
            if (avgColor != 0) c = avgColor
        }
        if (c == 0xFFFFFFFF.toInt() || c == 0) c = 0xFF5A5F6B.toInt()
        return darken(c, 0.55f, 0xE8)
    }

    private fun darken(c: Int, f: Float, a: Int) = Color.argb(a, (Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())

    /** App accent colour of a notification (falls back to a neutral blue-grey). */
    private fun colorOf(sbn: StatusBarNotification): Int {
        val c = sbn.notification.color
        return if (c == 0) 0xFF5A6A85.toInt() else (c or 0xFF000000.toInt())
    }

    private fun appIcon(pkg: String?): Drawable? {
        if (pkg == null) return null
        return iconCache.getOrPut(pkg) { try { context.packageManager.getApplicationIcon(pkg) } catch (_: Throwable) { null } }
    }

    private fun labelOf(sbn: StatusBarNotification): String =
        try { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(sbn.packageName, 0)).toString() } catch (_: Throwable) { sbn.packageName.substringAfterLast('.') }

    private fun titleOf(sbn: StatusBarNotification): String =
        sbn.notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().ifBlank { labelOf(sbn) }

    private fun textOf(sbn: StatusBarNotification): String {
        val ex = sbn.notification.extras
        return (ex?.getCharSequence(Notification.EXTRA_TEXT) ?: ex?.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString().orEmpty()
    }

    private fun bigTextOf(sbn: StatusBarNotification): String {
        val ex = sbn.notification.extras
        return (ex?.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex?.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
    }

    private fun fit(t: CharSequence, p: TextPaint, w: Float): String = TextUtils.ellipsize(t, p, w.coerceAtLeast(0f), TextUtils.TruncateAt.END).toString()

    private fun drawCircleDrawable(c: Canvas, dr: Drawable, cx: Float, cy: Float, r: Float) {
        c.save()
        path.reset(); path.addCircle(cx, cy, r, Path.Direction.CW); c.clipPath(path)
        dr.setBounds((cx - r).toInt(), (cy - r).toInt(), (cx + r).toInt(), (cy + r).toInt()); dr.draw(c)
        c.restore()
    }

    private fun drawArt(c: Canvas, a: IslandActivity, cx: Float, cy: Float, r: Float) {
        val art = a.art
        if (art != null) {
            try {
                val sh = BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                val s = (2 * r) / min(art.width, art.height).toFloat()
                val m = Matrix(); m.setScale(s, s)
                m.postTranslate(cx - art.width * s / 2f, cy - art.height * s / 2f)
                sh.setLocalMatrix(m); artPaint.shader = sh
                c.drawCircle(cx, cy, r, artPaint); artPaint.shader = null
                return
            } catch (_: Throwable) { artPaint.shader = null }
        }
        val ic = a.icon ?: appIcon(a.packageName)
        if (ic != null) drawCircleDrawable(c, ic, cx, cy, r)
        else { artPaint.color = 0x55FFFFFF; c.drawCircle(cx, cy, r, artPaint) }
    }

    private fun drawAppBadge(c: Canvas, sbn: StatusBarNotification, cx: Float, cy: Float, r: Float) {
        val isPulsing = pulseKey == sbn.key && pulseProgress < 1f
        if (isPulsing) {
            val t = pulseProgress.coerceIn(0f, 1f)
            val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorOf(sbn); alpha = ((1f - t) * 145).toInt() }
            c.drawCircle(cx, cy, r * (1.05f + 0.75f * (1f - t)), halo)
            c.save()
            val scale = (0.72f + 0.28f * t).coerceIn(0.72f, 1f)
            c.scale(scale, scale, cx, cy)
        } else c.save()
        val ic = appIcon(sbn.packageName)
        if (ic != null) drawCircleDrawable(c, ic, cx, cy, r) else { artPaint.color = 0x55FFFFFF; c.drawCircle(cx, cy, r, artPaint) }
        strokePaint.alpha = 90; c.drawCircle(cx, cy, r, strokePaint); strokePaint.alpha = 255
        c.restore()
    }

    private fun drawBars(c: Canvas, cx: Float, cy: Float, s: Float) {
        val bw = 3.4f * d
        c.drawRoundRect(RectF(cx - bw - 1.8f * d, cy - s / 2, cx - 1.8f * d, cy + s / 2), 1.2f * d, 1.2f * d, ctlPaint)
        c.drawRoundRect(RectF(cx + 1.8f * d, cy - s / 2, cx + bw + 1.8f * d, cy + s / 2), 1.2f * d, 1.2f * d, ctlPaint)
    }
    private fun drawPlay(c: Canvas, cx: Float, cy: Float, s: Float) {
        path.reset(); path.moveTo(cx - s * 0.38f, cy - s / 2); path.lineTo(cx + s * 0.5f, cy); path.lineTo(cx - s * 0.38f, cy + s / 2); path.close(); c.drawPath(path, ctlPaint)
    }
    private fun drawSkip(c: Canvas, cx: Float, cy: Float, s: Float, forward: Boolean) {
        val dir = if (forward) 1f else -1f
        val bar = 2.6f * d
        path.reset()
        path.moveTo(cx - dir * s / 2f, cy - s / 2); path.lineTo(cx + dir * (s / 2f - bar - 1.5f * d), cy); path.lineTo(cx - dir * s / 2f, cy + s / 2); path.close()
        c.drawPath(path, ctlPaint)
        val bx = cx + dir * (s / 2f - bar)
        c.drawRoundRect(RectF(minOf(bx, bx + bar), cy - s / 2, maxOf(bx, bx + bar), cy + s / 2), 1.2f * d, 1.2f * d, ctlPaint)
    }

    // ------------------------------------------------------------------------------------------ expanded card layout

    private class ExpLayout(val body: StaticLayout?, val height: Int)

    private fun expandedLayout(sbn: StatusBarNotification, widthPx: Int): ExpLayout {
        val pad = 16f * d
        val inner = (widthPx - 2 * pad).toInt().coerceAtLeast(40)
        val text = bigTextOf(sbn)
        val sl = if (text.isNotBlank())
            StaticLayout.Builder.obtain(text, 0, text.length, body2Paint, inner).setMaxLines(4).setEllipsize(TextUtils.TruncateAt.END).build()
        else null
        val h = pad + 28f * d + 8f * d + 22f * d + (if (sl != null) 4f * d + sl.height else 0f) + (if (progressOf(sbn) != null) 20f * d else 0f) + 14f * d + 38f * d + pad
        return ExpLayout(sl, ceil(h).toInt())
    }

    private fun buttonsOf(sbn: StatusBarNotification): List<Pair<String, () -> Unit>> {
        val n = sbn.notification
        val out = ArrayList<Pair<String, () -> Unit>>()
        out.add("Open" to { openNotif(sbn) })
        n.actions?.filter { !it.title.isNullOrBlank() }?.take(2)?.forEach { a ->
            val hasReply = a.remoteInputs?.isNotEmpty() == true
            out.add(a.title.toString() to { if (hasReply || a.actionIntent == null) openNotif(sbn) else { send(a.actionIntent, sbn.packageName); setExpanded(false) } })
        }
        return out
    }

    private fun drawExpanded(c: Canvas, sbn: StatusBarNotification) {
        val w = width.toFloat(); val h = height.toFloat(); val pad = 16f * d
        val lay = expandedLayout(sbn, width)
        bgPaint.color = darken(colorOf(sbn), 0.42f, 0xF2)
        c.drawRoundRect(RectF(0f, 0f, w, h), 28f * d, 28f * d, bgPaint)

        // header: app icon, app name, time, close
        val ir = 12f * d; val icx = pad + ir; val icy = pad + 14f * d
        drawAppBadge(c, sbn, icx, icy, ir)
        val cr = 12f * d; val ccx = w - pad - cr
        val time = DateUtils.getRelativeTimeSpanString(sbn.postTime, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
        c.drawText(fit("${labelOf(sbn)} · $time", smallPaint, ccx - cr - 10f * d - (icx + ir + 8f * d)), icx + ir + 8f * d, icy + 4f * d, smallPaint)
        artPaint.color = 0x33FFFFFF; c.drawCircle(ccx, icy, cr, artPaint)
        val x = 4.2f * d
        c.drawLine(ccx - x, icy - x, ccx + x, icy + x, strokePaint); c.drawLine(ccx - x, icy + x, ccx + x, icy - x, strokePaint)
        hitClose = RectF(ccx - 22f * d, icy - 22f * d, ccx + 22f * d, icy + 22f * d)
        hitHeader = RectF(0f, 0f, ccx - 22f * d, pad + 28f * d)

        // title + body
        var y = pad + 28f * d + 8f * d
        c.drawText(fit(titleOf(sbn), title2Paint, w - 2 * pad), pad, y + 16f * d, title2Paint)
        y += 22f * d
        lay.body?.let { y += 4f * d; c.save(); c.translate(pad, y); it.draw(c); c.restore(); y += it.height }
        progressOf(sbn)?.let { pr ->
            y += 12f * d
            if (pr >= 0f) { val s = "${(pr * 100).toInt()}%"; val tw = smallPaint.measureText(s); c.drawText(s, w - pad - tw, y + 4f * d, smallPaint); drawProgressBar(c, pad, w - pad - tw - 8f * d, y, 5f * d, pr) }
            else drawProgressBar(c, pad, w - pad, y, 5f * d, pr)
            y += 8f * d
        }
        hitBody = RectF(0f, hitHeader.bottom, w, y)

        // buttons
        y += 14f * d
        val btns = buttonsOf(sbn); val gap = 8f * d
        val bw = (w - 2 * pad - gap * (btns.size - 1)) / btns.size; val bh = 38f * d
        btns.forEachIndexed { i, (label, run) ->
            val r = RectF(pad + i * (bw + gap), y, pad + i * (bw + gap) + bw, y + bh)
            artPaint.color = 0x2EFFFFFF; c.drawRoundRect(r, bh / 2f, bh / 2f, artPaint)
            c.drawText(fit(label, chipPaint, bw - 16f * d), r.centerX(), r.centerY() + 4.5f * d, chipPaint)
            hitButtons.add(r to run)
        }
    }

    // ------------------------------------------------------------------------------------------ draw

    private fun pageColor(pg: Page): Int = when (pg) {
        is Page.Media -> tint(pg.a)
        is Page.Notif -> darken(colorOf(pg.sbn), 0.46f, 0xEA)
        is Page.Live -> darken(if (pg.a.accent == 0xFFFFFFFF.toInt() || pg.a.accent == 0) 0xFF5A5F6B.toInt() else pg.a.accent, 0.34f, 0xF0)
    }

    /** Extra room under the collapsed pill where the cards waiting behind it peek out (Samsung-style stack). */
    private val stackPad: Float get() = 10f * d

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val pgs = pages(); if (pgs.isEmpty()) return
        val i = index.coerceIn(0, pgs.lastIndex); val pg = pgs[i]
        val w = width.toFloat(); val h = height.toFloat()
        hitPrev = RectF(); hitPlay = RectF(); hitNext = RectF(); hitDots = RectF()
        hitClose = RectF(); hitHeader = RectF(); hitBody = RectF(); hitButtons.clear()

        if (pg is Page.Notif && expanded) { drawExpanded(c, pg.sbn); return }
        hitStop = RectF()
        if (pg is Page.Live || (pg is Page.Notif && progressOf(pg.sbn) == -1f)) ensureAnim()

        val ch = h - stackPad          // height of the front card
        val total = pgs.size
        val p = animT
        val animating = p < 1f && outgoing != null
        val fwd = animDir >= 0

        // cards waiting behind the front one
        if (total > 1) {
            for (k in minOf(2, total - 1) downTo 1) {
                val back = pgs[(i + (if (fwd || !animating) k else k)) % total]
                var grow = 0f
                if (animating && fwd && k == 1) grow = p           // the next card rises to the front while the old one leaves
                val inset = (k - grow) * 10f * d
                val bottom = ch + (k - grow) * (stackPad / 2f)
                val top = 6f * d * (k - grow)
                bgPaint.color = pageColor(back); bgPaint.alpha = if (k == 1) 0xC0 else 0x80
                c.drawRoundRect(RectF(inset, top, w - inset, bottom), (bottom - top) / 2f, (bottom - top) / 2f, bgPaint)
                bgPaint.alpha = 255
            }
        }

        val out = outgoing
        if (animating && out != null) {
            // Keep the Now Bar and its card geometry anchored. Only crossfade the card contents;
            // translating by a fraction of the full card height makes the notification appear
            // to fly away from the pill and creates the detached-ring effect seen in recordings.
            val eased = p * p * (3f - 2f * p) // smoothstep
            drawPageLayer(c, out, outgoingIndex, total, w, ch,
                1f - eased, 0f, 1f - 0.015f * eased, false)
            resetHits()
            drawPageLayer(c, pg, i, total, w, ch,
                eased, 0f, 0.985f + 0.015f * eased, true)
        } else {
            drawPageLayer(c, pg, i, total, w, ch, 1f, 0f, 1f, true)
        }
    }

    private fun resetHits() { hitPrev = RectF(); hitPlay = RectF(); hitNext = RectF(); hitDots = RectF(); hitStop = RectF() }

    @Suppress("DEPRECATION")
    private fun drawPageLayer(c: Canvas, pg: Page, i: Int, total: Int, w: Float, h: Float, alpha: Float, dy: Float, scale: Float, record: Boolean) {
        val sc = c.save()
        c.translate(0f, dy)
        c.scale(scale, scale, w / 2f, h / 2f)
        val a = alpha.coerceIn(0f, 1f)
        val layer = if (a < 0.999f) c.saveLayerAlpha(-4f * d, -4f * d, w + 4f * d, h + 4f * d, (a * 255).toInt()) else -1
        drawPage(c, pg, i, total, w, h, record)
        if (layer >= 0) c.restoreToCount(layer)
        c.restoreToCount(sc)
    }

    private fun drawPage(c: Canvas, pg: Page, i: Int, total: Int, w: Float, h: Float, record: Boolean) {
        val pad = 8f * d; val r = h / 2f - pad; val cy = h / 2f
        bgPaint.color = pageColor(pg)
        c.drawRoundRect(RectF(0f, 0f, w, h), h / 2f, h / 2f, bgPaint)

        val textLeft = pad + 2 * r + 12f * d
        when (pg) {
            is Page.Media -> {
                val a = pg.a
                drawArt(c, a, pad + r, cy, r)
                val right = w - 18f * d
                c.drawText(fit(a.title.ifBlank { a.subtitle }, titlePaint, right - textLeft), textLeft, h * 0.40f, titlePaint)
                val sz = 16f * d; val span = right - textLeft
                val cxs = floatArrayOf(textLeft + span / 6f, textLeft + span / 2f, textLeft + span * 5f / 6f)
                val cyC = h * 0.73f; val half = 20f * d
                val hasPrev = a.actions.any { it.kind == ActionKind.PREV }; val hasNext = a.actions.any { it.kind == ActionKind.NEXT }
                ctlPaint.alpha = if (hasPrev) 255 else 90; drawSkip(c, cxs[0], cyC, sz, false)
                ctlPaint.alpha = 255; if (a.playing) drawBars(c, cxs[1], cyC, sz) else drawPlay(c, cxs[1], cyC, sz)
                ctlPaint.alpha = if (hasNext) 255 else 90; drawSkip(c, cxs[2], cyC, sz, true)
                ctlPaint.alpha = 255
                if (record) {
                    hitPrev = RectF(cxs[0] - half, cyC - half, cxs[0] + half, cyC + half)
                    hitPlay = RectF(cxs[1] - half, cyC - half, cxs[1] + half, cyC + half)
                    hitNext = RectF(cxs[2] - half, cyC - half, cxs[2] + half, cyC + half)
                }
                if (a.durationMs > 0 && a.positionMs >= 0) {
                    val el = if (a.playing) ((android.os.SystemClock.elapsedRealtime() - a.positionAt) * a.speed).toLong() else 0L
                    val f = ((a.positionMs + el).toFloat() / a.durationMs).coerceIn(0f, 1f)
                    val bx0 = textLeft; val bx1 = right; val by = h - 7f * d
                    artPaint.color = 0x33FFFFFF; c.drawRoundRect(RectF(bx0, by - 1.5f * d, bx1, by + 1.5f * d), 1.5f * d, 1.5f * d, artPaint)
                    artPaint.color = Color.WHITE; c.drawRoundRect(RectF(bx0, by - 1.5f * d, bx0 + (bx1 - bx0) * f, by + 1.5f * d), 1.5f * d, 1.5f * d, artPaint)
                }
            }
            is Page.Notif -> {
                val sbn = pg.sbn
                drawAppBadge(c, sbn, pad + r, cy, r)
                var right = w - 18f * d
                if (total > 1) {
                    val k = min(total, 5); val gap = 10f * d
                    val startX = w - 18f * d - (k - 1) * gap
                    val cur = if (i < k) i else k - 1
                    for (j in 0 until k) { artPaint.color = if (j == cur) 0xFFFFFFFF.toInt() else 0x66FFFFFF; c.drawCircle(startX + j * gap, cy, 2.8f * d, artPaint) }
                    right = startX - 14f * d
                    if (record) hitDots = RectF(startX - 14f * d, 0f, w, h)
                }
                val title = titleOf(sbn); val text = textOf(sbn)
                val prog = progressOf(sbn)
                if (prog != null) {
                    // download / upload: title + percent on top, bar underneath
                    if (prog >= 0f) {
                        val s = "${(prog * 100).toInt()}%"; val tw = title2Paint.measureText(s)
                        c.drawText(s, right - tw, h * 0.40f, title2Paint); right -= tw + 10f * d
                    }
                    c.drawText(fit(title, titlePaint, right - textLeft), textLeft, h * 0.38f, titlePaint)
                    if (text.isNotBlank()) c.drawText(fit(text, smallPaint, w - 18f * d - textLeft), textLeft, h * 0.60f, smallPaint)
                    drawProgressBar(c, textLeft, w - 18f * d, h - 11f * d, 4.5f * d, prog)
                } else {
                    c.drawText(fit(title, titlePaint, right - textLeft), textLeft, if (text.isBlank()) cy + 4.5f * d else h * 0.42f, titlePaint)
                    if (text.isNotBlank()) c.drawText(fit(text, bodyPaint, right - textLeft), textLeft, h * 0.70f, bodyPaint)
                }
            }
            is Page.Live -> drawLive(c, pg.a, i, total, w, h, record, pad, r, cy, textLeft)
        }
    }

    private fun drawBolt(c: Canvas, cx: Float, cy: Float, r: Float) {
        path.reset()
        path.moveTo(cx + 0.12f * r, cy - 0.62f * r); path.lineTo(cx - 0.36f * r, cy + 0.10f * r); path.lineTo(cx - 0.03f * r, cy + 0.10f * r)
        path.lineTo(cx - 0.12f * r, cy + 0.62f * r); path.lineTo(cx + 0.36f * r, cy - 0.10f * r); path.lineTo(cx + 0.03f * r, cy - 0.10f * r); path.close()
        c.drawPath(path, ctlPaint)
    }

    /** Charging: animated energy fill to the battery level, rising sparks, all clipped to the card. */
    private fun drawChargeFx(c: Canvas, a: IslandActivity, w: Float, h: Float, pct: Int, accent: Int) {
        val now = android.os.SystemClock.uptimeMillis()
        val rgb = accent and 0x00FFFFFF
        val sv = c.save()
        fxPath.reset(); fxPath.addRoundRect(RectF(0f, 0f, w, h), h / 2f, h / 2f, Path.Direction.CW); c.clipPath(fxPath)
        // energy fill with a wavy leading edge
        val fillX = w * (0.12f + 0.88f * (pct / 100f))
        fxPath.reset(); fxPath.moveTo(0f, 0f); fxPath.lineTo(fillX + 3f * d, 0f)
        val ph = now / 240.0
        var y = 0f
        while (y <= h) { fxPath.lineTo(fillX + 3f * d * kotlin.math.sin(ph + y / (6f * d)).toFloat(), y); y += 2f * d }
        fxPath.lineTo(0f, h); fxPath.close()
        fxPaint.style = Paint.Style.FILL; fxPaint.color = (0x44 shl 24) or rgb
        c.drawPath(fxPath, fxPaint)
        // sparks rising
        for (i in 0 until 12) {
            val per = 1300L + i * 190L
            val f = ((now + i * 421L) % per) / per.toFloat()
            val px = w * (0.08f + 0.84f * (((i * 53) % 100) / 100f))
            fxPaint.color = (((1f - f) * 0.8f * 255).toInt() shl 24) or rgb
            c.drawCircle(px, h - h * f, (1.2f + (i % 3) * 0.6f) * d, fxPaint)
        }
        c.restoreToCount(sv)
    }

    /** Charger connected (pulsing bolt + a charge bar with a moving shine) and screen recording (pulsing dot, timer, Stop). */
    private fun drawLive(c: Canvas, a: IslandActivity, i: Int, total: Int, w: Float, h: Float, record: Boolean, pad: Float, r: Float, cy: Float, textLeft: Float) {
        val now = android.os.SystemClock.uptimeMillis()
        var acc = a.accent
        val bx = pad + r
        var right = w - 18f * d
        // charging: count the percentage up from 0 and paint the energy effect behind everything
        var shownPct: Int? = null
        if (a.kind == Kind.CHARGING) {
            val real = a.trailingText?.filter { it.isDigit() }?.toIntOrNull()?.coerceIn(0, 100)
            if (real != null) {
                val t0 = chargeSeen.getOrPut(a.id) { now }
                val k = ((now - t0) / 1100f).coerceIn(0f, 1f); val e = 1f - (1f - k) * (1f - k) * (1f - k)
                shownPct = (real * e).toInt()
            }
            // the colour animates with the counting percentage: red > orange > yellow > green
            acc = chargeColorAt((shownPct ?: 100).toFloat())
            drawChargeFx(c, a, w, h, shownPct ?: 100, acc)
        }
        // page dots when there is more than this one card
        if (total > 1) {
            val k = min(total, 5); val gap = 8f * d
            val startX = w - 14f * d - (k - 1) * gap
            val cur = if (i < k) i else k - 1
            for (j in 0 until k) { artPaint.color = if (j == cur) 0xFFFFFFFF.toInt() else 0x55FFFFFF; c.drawCircle(startX + j * gap, 9f * d, 2.2f * d, artPaint) }
        }
        // badge: coloured disc with an expanding pulse ring
        val t = (now % 1500L) / 1500f
        artPaint.color = acc; artPaint.alpha = ((1f - t) * 110).toInt(); c.drawCircle(bx, cy, r * (1f + 0.38f * t), artPaint)
        artPaint.color = acc; artPaint.alpha = 255; c.drawCircle(bx, cy, r, artPaint)
        if (a.kind == Kind.RECORD) {
            val pulse = 0.55f + 0.45f * kotlin.math.sin(now / 240.0).toFloat()
            ctlPaint.alpha = (pulse * 255).toInt(); c.drawCircle(bx, cy, r * 0.32f, ctlPaint); ctlPaint.alpha = 255
            val el = ((android.os.SystemClock.elapsedRealtime() - a.chronoBase) / 1000L).coerceAtLeast(0L)
            val stop = a.actions.firstOrNull()
            var tr = right
            if (stop != null) {
                val sw = 64f * d; val rect = RectF(right - sw, cy - 15f * d, right, cy + 15f * d)
                artPaint.color = 0x38FFFFFF; c.drawRoundRect(rect, 15f * d, 15f * d, artPaint)
                c.drawText("Stop", rect.centerX(), rect.centerY() + 4.5f * d, chipPaint)
                if (record) hitStop = RectF(rect.left - 8f * d, 0f, w, h)
                tr = rect.left - 10f * d
            }
            c.drawText(fit(a.title.ifBlank { "Screen recording" }, titlePaint, tr - textLeft), textLeft, h * 0.42f, titlePaint)
            c.drawText(DateUtils.formatElapsedTime(el), textLeft, h * 0.72f, bodyPaint)
        } else {
            drawBolt(c, bx, cy, r)
            // charge ring around the badge: track + arc to the battery level + a bright spinning highlight
            run {
                val rr = r + 4f * d
                val rect = RectF(bx - rr, cy - rr, bx + rr, cy + rr)
                fxPaint.style = Paint.Style.STROKE; fxPaint.strokeWidth = 2.2f * d; fxPaint.strokeCap = Paint.Cap.ROUND
                fxPaint.color = 0x33FFFFFF; c.drawArc(rect, 0f, 360f, false, fxPaint)
                fxPaint.color = 0xFFFFFFFF.toInt(); c.drawArc(rect, -90f, 360f * ((shownPct ?: 100) / 100f), false, fxPaint)
                val spin = (now / 6L % 360L).toFloat()
                fxPaint.color = 0xAAFFFFFF.toInt(); fxPaint.strokeWidth = 3f * d; c.drawArc(rect, spin, 28f, false, fxPaint)
                fxPaint.style = Paint.Style.FILL; fxPaint.strokeCap = Paint.Cap.BUTT
            }
            val pct = shownPct ?: a.trailingText?.filter { it.isDigit() }?.toIntOrNull()
            // no charge bar any more: title and percentage sit on the vertical centre of the card
            val ty = h * 0.58f
            if (pct != null) {
                val s = "$pct%"; val tw = title2Paint.measureText(s)
                c.drawText(s, right - tw, ty, title2Paint); right -= tw + 10f * d
            }
            c.drawText(fit(a.title, titlePaint, right - textLeft), textLeft, ty, titlePaint)
        }
    }

    // ------------------------------------------------------------------------------------------ actions out

    private fun send(pi: PendingIntent?, pkg: String?) {
        try {
            if (pi != null) {
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    val o = android.app.ActivityOptions.makeBasic()
                    o.setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    pi.send(context, 0, null, null, null, null, o.toBundle())
                } else pi.send()
                return
            }
        } catch (_: Throwable) { }
        try { pkg?.let { context.packageManager.getLaunchIntentForPackage(it)?.let { i -> context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } } catch (_: Throwable) { }
    }

    private fun openNotif(sbn: StatusBarNotification) { send(sbn.notification.contentIntent, sbn.packageName); setExpanded(false) }

    private fun tap(x: Float, y: Float) {
        val pgs = pages(); if (pgs.isEmpty()) return
        when (val pg = pgs[index.coerceIn(0, pgs.lastIndex)]) {
            is Page.Media -> {
                val a = pg.a
                fun act(k: ActionKind) = a.actions.firstOrNull { it.kind == k }?.let { try { it.run() } catch (_: Throwable) { } }
                when {
                    hitPrev.contains(x, y) -> act(ActionKind.PREV)
                    hitPlay.contains(x, y) -> act(ActionKind.PLAYPAUSE)
                    hitNext.contains(x, y) -> act(ActionKind.NEXT)
                    else -> send(a.contentIntent, a.packageName)
                }
            }
            is Page.Live -> {
                val a = pg.a
                if (a.kind == Kind.RECORD && hitStop.contains(x, y)) a.actions.firstOrNull()?.let { try { it.run() } catch (_: Throwable) { } }
            }
            is Page.Notif -> {
                if (expanded) {
                    when {
                        hitClose.contains(x, y) -> dismiss(pg.sbn)
                        hitButtons.any { it.first.contains(x, y) } -> hitButtons.first { it.first.contains(x, y) }.second()
                        hitHeader.contains(x, y) -> setExpanded(false)
                        else -> openNotif(pg.sbn)
                    }
                } else if (hitDots.contains(x, y)) cycle(1) else setExpanded(true)
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                swipeAnimator?.cancel(); downX = e.x; downY = e.y
                swipeTracking = false; swipeAxis = 0; swipeDx = 0f; swipeDy = 0f
                if (expanded) { main.removeCallbacks(collapse); main.postDelayed(collapse, 9000L) }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX; val dy = e.y - downY
                val threshold = 8f * d
                if (!swipeTracking && (abs(dx) > threshold || abs(dy) > threshold)) {
                    swipeTracking = true
                    swipeAxis = if (abs(dx) > abs(dy) * 1.12f) 1 else 2
                }
                if (swipeTracking) {
                    // Keep the Now Bar fixed in place. Gesture direction is indicated only by a subtle fade.
                    alpha = if (swipeAxis == 1) (1f - abs(dx) / (width.coerceAtLeast(1) * 2.8f)).coerceIn(0.82f, 1f)
                            else (1f - abs(dy) / (height.coerceAtLeast(1) * 3.0f)).coerceIn(0.86f, 1f)
                    translationX = 0f; translationY = 0f; rotation = 0f
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val cancelled = e.actionMasked == MotionEvent.ACTION_CANCEL
                val dx = if (cancelled) 0f else e.x - downX
                val dy = if (cancelled) 0f else e.y - downY
                val big = 40f * d
                if (swipeTracking) {
                    val horizontal = !cancelled && swipeAxis == 1 && abs(dx) > big && abs(dx) > abs(dy)
                    val vertical = !cancelled && swipeAxis == 2 && abs(dy) > big && abs(dy) > abs(dx)
                    swipeAnimator?.cancel()
                    if (horizontal) {
                        // Horizontal swipe dismisses only the notification currently shown; never move the pill.
                        val current = currentNotif()
                        if (current != null) {
                            swipeAnimator = android.animation.ValueAnimator.ofFloat(alpha, 0f).apply {
                                duration = 110L
                                interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0.2f, 1f)
                                addUpdateListener { alpha = it.animatedValue as Float }
                                addListener(object : android.animation.AnimatorListenerAdapter() {
                                    override fun onAnimationEnd(animation: android.animation.Animator) {
                                        dismiss(current)
                                        translationX = 0f; translationY = 0f; rotation = 0f
                                        swipeAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                                            duration = 170L
                                            interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0.2f, 1f)
                                            addUpdateListener { alpha = it.animatedValue as Float }
                                            addListener(object : android.animation.AnimatorListenerAdapter() {
                                                override fun onAnimationEnd(animation: android.animation.Animator) { alpha = 1f }
                                            })
                                            start()
                                        }
                                    }
                                })
                                start()
                            }
                        } else {
                            swipeAnimator = android.animation.ValueAnimator.ofFloat(alpha, 1f).apply {
                                duration = 160L; addUpdateListener { alpha = it.animatedValue as Float }; start()
                            }
                        }
                    } else if (vertical) {
                        // Vertical swipe navigates notifications: up = next, down = previous.
                        // Do not run a second View-alpha animator here: switchTo() already owns
                        // the anchored card crossfade. Competing animators can leave the whole
                        // Now Bar transparent or make the swipe appear to do nothing.
                        val direction = if (dy < 0) 1 else -1
                        alpha = 1f
                        translationX = 0f; translationY = 0f; rotation = 0f
                        cycle(direction)
                    } else {
                        // Short/cancelled gestures spring back without changing or dismissing anything.
                        swipeAnimator = android.animation.ValueAnimator.ofFloat(alpha, 1f).apply {
                            duration = 180L
                            interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0.2f, 1f)
                            addUpdateListener { alpha = it.animatedValue as Float }
                            addListener(object : android.animation.AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: android.animation.Animator) {
                                    translationX = 0f; translationY = 0f; alpha = 1f; rotation = 0f
                                }
                            })
                            start()
                        }
                    }
                    swipeTracking = false; swipeAxis = 0
                    return true
                }
                when {
                    abs(dx) < 12f * d && abs(dy) < 12f * d -> tap(e.x, e.y)
                }
                return true
            }
        }
        return true
    }

    override fun onDetachedFromWindow() { swipeAnimator?.cancel(); translationX = 0f; translationY = 0f; alpha = 1f; rotation = 0f; animator?.cancel(); main.removeCallbacks(animTick); main.removeCallbacks(pruneHeld); animRunning = false; main.removeCallbacks(tick); main.removeCallbacks(revert); main.removeCallbacks(collapse); super.onDetachedFromWindow() }

    companion object {
        /** True for a media-player notification (transport category or a MediaStyle session token). */
        fun isMediaNotification(sbn: StatusBarNotification): Boolean {
            val n = sbn.notification ?: return false
            if (n.category == Notification.CATEGORY_TRANSPORT) return true
            return try { n.extras?.getParcelable<android.media.session.MediaSession.Token>("android.mediaSession") != null } catch (_: Throwable) { false }
        }
        /** Collapsed pill height. */
        fun heightFor(density: Float): Int = ceil(78f * density).toInt()
        /** Collapsed pill width: fills the gap between the lock-screen phone and camera shortcuts. */
        fun widthFor(density: Float, screenW: Int): Int =
            (screenW - 160f * density).toInt().coerceIn((200f * density).toInt(), (330f * density).toInt()).coerceAtMost(screenW)
        /** Expanded card width: nearly full width. */
        fun expandedWidthFor(density: Float, screenW: Int): Int =
            (screenW - 32f * density).toInt().coerceAtMost((420f * density).toInt()).coerceAtMost(screenW)
    }
}
