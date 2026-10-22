package com.faroquetech.aospisland

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.net.Uri
import android.content.IntentFilter
import android.app.KeyguardManager
import android.service.notification.StatusBarNotification
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import kotlin.math.max

/** Where the camera cutout is and how big the island should be around it (all px, current rotation). */
data class Geometry(val centerX: Int, val top: Int, val holeW: Int, val pillH: Int, val screenW: Int, val screenH: Int) {
    val landscape: Boolean get() = screenW > screenH

    companion object {
        @Suppress("DEPRECATION")
        fun compute(ctx: Context, cfg: IslandConfig): Geometry {
            val d = ctx.resources.displayMetrics.density
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
            val size = Point(); display.getRealSize(size)
            var rect: Rect? = null
            if (!cfg.manualCutout && Build.VERSION.SDK_INT >= 29) {
                val cutout = display.cutout
                rect = cutout?.boundingRects?.filter { it.top <= 1 && !it.isEmpty && it.width() < size.x / 2 }
                    ?.minByOrNull { kotlin.math.abs(it.centerX() - size.x / 2) }
            }
            if (rect == null) {
                val w = (cfg.cutoutWdp * d).toInt(); val h = (cfg.cutoutHdp * d).toInt(); val t = (cfg.cutoutTopDp * d).toInt()
                rect = Rect(size.x / 2 - w / 2, t, size.x / 2 + w / 2, t + h)
            }
            val margin = (3 * d).toInt()
            val top = max(0, rect.top - margin) + (cfg.yOffsetDp * d).toInt()
            val bottom = rect.bottom + margin + (cfg.yOffsetDp * d).toInt()
            val pillH = max(bottom - top + (cfg.heightTrimDp * d).toInt(), (12 * d).toInt())
            return Geometry(rect.centerX(), top, rect.width() + margin, pillH, size.x, size.y)
        }
    }
}

/**
 * Hosts an [IslandView] in two windows:
 *  - the VISUAL window: full screen width, anchored at the top, NOT touchable. Its x and width never change after attach
 *    (only its height follows the expanded card), so the window manager can never move the pill sideways. The pill is
 *    drawn at the cutout's x inside it.
 *  - the INPUT proxy: a small invisible window that follows the pill's bounds and forwards every touch to the view.
 *    It is the only window that moves / resizes, and nothing is drawn in it, so any lag there is invisible. Touches
 *    everywhere else go straight to the app underneath.
 */
class IslandWindow(private val ctx: Context, private val windowType: Int, private val systemHost: Boolean) {
    companion object { const val ACTION_NOWBAR_POSITION = "com.faroquetech.aospisland.action.NOWBAR_POSITION"; const val EXTRA_NOWBAR_POSITION = "position_pct" }
    val view = IslandView(ctx)
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var lp: WindowManager.LayoutParams? = null
    private var geo: Geometry? = null
    private var config = IslandConfig()
    var attached = false; private set

    private var proxy: InputProxy? = null
    private var proxyLp: WindowManager.LayoutParams? = null
    private var pendingBounds: Rect? = null
    private val nowBar = NowBarView(ctx)
    private var nowBarLp: WindowManager.LayoutParams? = null
    @Volatile private var locked = false
    @Volatile private var nowBarPositionPct = 94
    private var positionReceiverRegistered = false
    private val positionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_NOWBAR_POSITION) return
            nowBarPositionPct = intent.getIntExtra(EXTRA_NOWBAR_POSITION, nowBarPositionPct).coerceIn(0, 100)
            repositionNowBar()
        }
    }
    private var lockReceiverRegistered = false
    private val keyguard by lazy { ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager }
    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { refreshLockState() }
    }

    private inner class InputProxy(c: Context) : View(c) {
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            val a = lp ?: return false; val b = proxyLp ?: return false
            val e = MotionEvent.obtain(ev)
            e.offsetLocation((b.x - a.x).toFloat(), (b.y - a.y).toFloat())
            val handled = try { view.dispatchTouchEvent(e) } finally { e.recycle() }
            return handled
        }
    }

    private fun baseFlags(): Int {
        var f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (systemHost) f = f or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        return f
    }

    private fun cutoutMode(p: WindowManager.LayoutParams) {
        if (Build.VERSION.SDK_INT >= 30) { p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS; p.fitInsetsTypes = 0 }
        else if (Build.VERSION.SDK_INT >= 28) p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    @Suppress("DEPRECATION")
    fun attach(cfg: IslandConfig) {
        if (attached) { update(cfg); return }
        config = cfg
        val d = ctx.resources.displayMetrics.density
        val g = Geometry.compute(ctx, cfg); geo = g
        nowBarPositionPct = cfg.nowBarPositionPct   // from the config (in SystemUI, ctx's own prefs are NOT the app's)
        val params = WindowManager.LayoutParams(
            g.screenW, g.pillH + (18 * d).toInt(), windowType,
            baseFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            title = "AOSPIsland"
            x = 0; y = g.top
            cutoutMode(this)
        }
        lp = params
        view.onSizeNeeded = { _, h -> resize(h) }
        view.onTouchBounds = { r -> updateProxy(r) }
        view.onInputMode = { on -> setInputMode(on) }
        view.config = cfg
        view.setGeometry(g.holeW, g.pillH, g.screenW, g.centerX, 0, g.top)
        view.suppressed = cfg.hideInLandscape && g.landscape
        wm.addView(view, params)
        attached = true

        // The lock-screen Now Bar is a completely separate view/window; IslandView remains unchanged.
        val nowBarHeight = nowBar.contentHeightPx
        val nowBarWidth = NowBarView.widthFor(d, g.screenW)
        nowBar.onDismiss = { key -> IslandHub.cancel(key) }
        nowBar.onContentChanged = { applyNowBarVisibility(); repositionNowBar(); nbLog("bar: ${nowBar.stats} visible=${nowBar.visibility == View.VISIBLE} locked=$locked") }
        val nbType = config.nowBarWindowType
        val nbp = WindowManager.LayoutParams(
            nowBarWidth, nowBarHeight, nbType,
            baseFlags(), PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            title = "AOSPIslandNowBar"
            x = (g.screenW - nowBarWidth) / 2; y = nowBarY(g.screenH, nowBarHeight)
            cutoutMode(this)
        }
        nowBarLp = nbp
        nowBar.visibility = View.GONE
        try { wm.addView(nowBar, nbp); nbLog("window added type=$nbType y=${nbp.y} h=${nbp.height}", true) }
        catch (t: Throwable) {
            nbLog("addView FAILED (type=$nbType): $t - falling back to type=$windowType", true)
            try { nbp.type = windowType; wm.addView(nowBar, nbp); nbLog("window added type=$windowType (fallback)", true) }
            catch (t2: Throwable) { nowBarLp = null; nbLog("addView FAILED (type=$windowType): $t2", true) }
        }
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
            }
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(lockReceiver, filter, Context.RECEIVER_EXPORTED)
            else ctx.registerReceiver(lockReceiver, filter)
            lockReceiverRegistered = true
        } catch (_: Throwable) { }
        refreshLockState()
        try {
            val pf = IntentFilter(ACTION_NOWBAR_POSITION)
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(positionReceiver, pf, Context.RECEIVER_EXPORTED)
            else ctx.registerReceiver(positionReceiver, pf)
            positionReceiverRegistered = true
        } catch (_: Throwable) { }

        val pv = InputProxy(ctx)
        val pp = WindowManager.LayoutParams(
            1, 1, windowType,
            baseFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSPARENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            title = "AOSPIslandInput"
            x = 0; y = g.top
            cutoutMode(this)
        }
        proxy = pv; proxyLp = pp
        try { wm.addView(pv, pp) } catch (ignored: Throwable) { proxy = null; proxyLp = null }
        if (locked) updateProxy(null) else updateProxy(view.touchBounds())
    }

    private fun nowBarY(screenH: Int, barH: Int): Int {
        val pct = nowBarPositionPct.coerceIn(0, 100)
        return ((screenH - barH).coerceAtLeast(0) * pct / 100f).toInt()
    }

    private var lastNbLog = 0L
    /** Writes a line into the app's "SystemUI hook" status box (works from SystemUI and from the overlay service). */
    private fun nbLog(msg: String, force: Boolean = false) {
        val now = android.os.SystemClock.uptimeMillis()
        if (!force && now - lastNbLog < 400) return
        lastNbLog = now
        try { ctx.contentResolver.call(Uri.parse("content://${IslandConfig.AUTHORITY}"), "hello", "nowbar: $msg", null) } catch (_: Throwable) { }
    }

    /** The app's saved position, read straight from the app (SystemUI cannot read the app's prefs itself). */
    @Suppress("DEPRECATION")
    private fun savedNowBarPct(): Int? = try {
        ctx.contentResolver.call(Uri.parse("content://${IslandConfig.AUTHORITY}"), "get", null, null)
            ?.let { IslandConfig.fromBundle(it).nowBarPositionPct }
    } catch (_: Throwable) { null }

    private fun repositionNowBar(refresh: Boolean = false) {
        val g = geo ?: return
        val p = nowBarLp ?: run { nbLog("no window (addView failed earlier)"); return }
        if (refresh) savedNowBarPct()?.let { nowBarPositionPct = it }
        val dens = ctx.resources.displayMetrics.density
        val collapsedH = nowBar.contentHeightPx
        val h = nowBar.desiredHeightPx(g.screenW); val w = nowBar.desiredWidthPx(g.screenW)
        val baseY = nowBarY(g.screenH, collapsedH)
        p.height = h; p.width = w; p.x = (g.screenW - w) / 2
        // an expanded card grows upward from the pill's spot
        p.y = if (h > collapsedH) (baseY + collapsedH - h).coerceAtLeast((32 * dens).toInt()) else baseY
        try { if (attached) wm.updateViewLayout(nowBar, p) }
        catch (t: Throwable) { nbLog("updateViewLayout FAILED: $t", true); return }
        nbLog("pct=$nowBarPositionPct y=${p.y} h=${p.height} screenH=${g.screenH} locked=$locked", force = refresh)
    }

    /** Set by the runtime: every notification currently known, not only the ones posted since the module loaded. */
    var rawNotifications: (() -> List<StatusBarNotification>)? = null

    /** Called after every notification feed update so media can be re-linked to its notification. */
    var onFeed: (() -> Unit)? = null

    fun updateNotifications(list: List<StatusBarNotification>) {
        val src = try { rawNotifications?.invoke() } catch (_: Throwable) { null }
        nowBar.setNotifications(if (!src.isNullOrEmpty()) src else list)
        nbLog("feed: hook=${list.size} source=${src?.size ?: -1}")
        try { onFeed?.invoke() } catch (_: Throwable) { }
    }
    fun updateMedia(a: IslandActivity?) { nowBar.setMedia(a) }
    fun updateLive(list: List<IslandActivity>) { nowBar.setLive(list) }

    /** The pill is only there on the lock screen, and only when there is media or a notification to show. */
    private fun applyNowBarVisibility() { nowBar.visibility = if (locked && attached && nowBar.hasContent) View.VISIBLE else View.GONE }

    private fun refreshLockState() {
        val isLocked = try { keyguard.isKeyguardLocked } catch (_: Throwable) { false }
        if (locked == isLocked) return
        locked = isLocked
        view.lockHidden = locked
        view.visibility = if (locked) View.INVISIBLE else View.VISIBLE
        if (locked) updateProxy(null) else updateProxy(view.touchBounds())
        applyNowBarVisibility()
        repositionNowBar(refresh = true)
    }

    fun update(cfg: IslandConfig) {
        config = cfg
        if (!attached) return
        val g = Geometry.compute(ctx, cfg); geo = g
        nowBarPositionPct = cfg.nowBarPositionPct
        view.config = cfg
        view.setGeometry(g.holeW, g.pillH, g.screenW, g.centerX, 0, g.top)
        view.suppressed = cfg.hideInLandscape && g.landscape
        lp?.let { it.x = 0; it.y = g.top; it.width = g.screenW; safeUpdate(it) }
        if (locked) updateProxy(null) else updateProxy(view.touchBounds())
        refreshLockState()
        repositionNowBar()   // rotation / display-size change
    }

    /** Call on rotation / display changes. */
    fun onConfigurationChanged(@Suppress("UNUSED_PARAMETER") c: Configuration?) = update(config)

    /** Only the height can change: x and width of the visual window are fixed. */
    private fun resize(h: Int) {
        val p = lp ?: return; val g = geo ?: return
        if (p.height == h && p.y == g.top && p.x == 0 && p.width == g.screenW) return
        p.height = h; p.x = 0; p.y = g.top; p.width = g.screenW
        safeUpdate(p)
    }

    private fun updateProxy(r: Rect?) {
        pendingBounds = r
        if (!attached) return
        val p = proxyLp ?: return; val pv = proxy ?: return; val g = geo ?: return
        val notTouchable = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (r == null || r.width() <= 0 || r.height() <= 0) {
            if (p.flags and notTouchable == 0) {
                p.flags = p.flags or notTouchable; p.width = 1; p.height = 1; p.x = 0; p.y = g.top
                try { wm.updateViewLayout(pv, p) } catch (ignored: Throwable) { }
            }
            return
        }
        val nx = r.left; val ny = g.top + r.top; val nw = r.width(); val nh = r.height()
        if (p.flags and notTouchable == 0 && p.x == nx && p.y == ny && p.width == nw && p.height == nh) return
        p.flags = p.flags and notTouchable.inv()
        p.x = nx; p.y = ny; p.width = nw; p.height = nh
        try { wm.updateViewLayout(pv, p) } catch (ignored: Throwable) { }
    }

    /** Reply typing: the (otherwise never focusable) island window takes focus so the keyboard can attach, then gives it back. */
    private fun setInputMode(on: Boolean) {
        val p = lp ?: return
        val nf = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val focusable = p.flags and nf == 0
        if (focusable == on) return
        p.flags = if (on) p.flags and nf.inv() else p.flags or nf
        p.softInputMode = if (on) WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING else WindowManager.LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED
        safeUpdate(p)
    }

    private fun safeUpdate(p: WindowManager.LayoutParams) { try { if (attached) wm.updateViewLayout(view, p) } catch (ignored: Throwable) { } }

    fun detach() {
        if (!attached) return
        attached = false
        proxy?.let { try { wm.removeViewImmediate(it) } catch (ignored: Throwable) { } }
        proxy = null; proxyLp = null
        try { wm.removeViewImmediate(view) } catch (ignored: Throwable) { }
        try { wm.removeViewImmediate(nowBar) } catch (_: Throwable) { }
        nowBarLp = null
        if (lockReceiverRegistered) { try { ctx.unregisterReceiver(lockReceiver) } catch (_: Throwable) { }; lockReceiverRegistered = false }
        if (positionReceiverRegistered) { try { ctx.unregisterReceiver(positionReceiver) } catch (_: Throwable) { }; positionReceiverRegistered = false }
    }
}
