package com.faroquetech.aospisland

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Most extra activity bubbles that can sit to the left of the pill. */
private const val MAX_BUBBLES = 4
/** Bubbles never extend further left than this from the screen edge (the clock lives there). */
private const val BUBBLE_CLOCK_SAFE_DP = 84f
/** Up to this many bubbles sit side by side; beyond it they slide together into a stack. */
private const val BUBBLES_BEFORE_STACK = 3
private const val STYLE_MONET = 3
private const val STYLE_ADAPTIVE = 4

/**
 * The island itself. A black shape (width / height / corner radius driven by springs) that is always horizontally
 * centred in this view and anchored at y = 0. The host window is positioned so that the shape wraps the camera cutout.
 *
 * States: hidden (collapsed into the cutout), compact (content on both sides of the cutout), expanded (card),
 * plus a detached "bubble" for a second simultaneous activity.
 */
class IslandView(context: Context) : ViewGroup(context) {
    private enum class Mode { COMPACT, EXPANDED }

    // ---- host callbacks ----
    var onSizeNeeded: ((Int, Int) -> Unit)? = null
    var onAction: ((ActivityAction) -> Unit)? = null
    var onOpen: ((IslandActivity) -> Unit)? = null
    var onCycle: ((Int) -> Unit)? = null
    var onSwap: ((IslandActivity) -> Unit)? = null
    var onClear: ((IslandActivity) -> Unit)? = null
    var onExpandedChanged: ((Boolean) -> Unit)? = null
    /** The host makes the island window focusable while a card text field is being typed in (keyboard), and not focusable again after. */
    var onInputMode: ((Boolean) -> Unit)? = null
    private var inputMode = false
    private fun endInput() { if (inputMode) (card as? LiveCard)?.endTyping() }

    var config = IslandConfig()
        set(v) {
            val oldStyle = field.islandStyle
            field = v; configureSprings()
            refreshMonet()
            val rp = rawPrimary
            // switching style re-skins the accents (Material You) and the Adaptive surface, so rebind from the raw activities
            if (oldStyle != v.islandStyle && rp != null) bind(rp, rawExtras) else applyTargets()
            retintSurface(false)
            invalidate()
        }
    var suppressed = false
        set(v) { if (field != v) { field = v; applyTargets() } }
    /** True while the keyguard is showing: the island is completely hidden on the lock screen (the Now Bar takes over). */
    var lockHidden = false
        set(v) { if (field == v) return; field = v; if (v) { sA.snap(0f); sBs.forEach { it.snap(0f); it.target = 0f }; visibility = INVISIBLE }; applyTargets() }

    /** Swipe right on the compact pill hides it into the cutout; swipe left (or tap) on the cutout shows it again. */
    private var userHidden = false

    val isExpanded: Boolean get() = mode == Mode.EXPANDED && primary != null

    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private fun dpf(v: Float) = v * d

    // ---- geometry (px) ----
    private var holeW = dp(30)
    private var pillH = dp(36)
    private var screenW = resources.displayMetrics.widthPixels

    // ---- state ----
    private var primary: IslandActivity? = null
    private var extras: List<IslandActivity> = emptyList()
    private var mode = Mode.COMPACT
    private var initialized = false
    private var fullscreenHidden = false

    // ---- children ----
    private val lead = LeadView(context)
    private val trail = TrailView(context)
    private val bubbles = List(MAX_BUBBLES) { LeadView(context) }
    private var card: Card? = null
    private var cardFamily = -1
    private var cardH = 0
    private var compactW = 0
    private var leadSize = 0
    private var trailW = 0

    // ---- springs ----
    private val sW = Spring(0f)
    private val sH = Spring(0f)
    private val sR = Spring(0f)
    private val sP = Spring(0f, restDelta = 0.002f)   // 0 compact .. 1 expanded (content cross-fade)
    private val sA = Spring(0f, restDelta = 0.004f)   // island visibility
    private val sBs = List(MAX_BUBBLES) { Spring(0f, restDelta = 0.004f) }   // bubble visibility
    private val sOv = Spring(0f, restDelta = 0.004f)   // 0 = bubbles spaced normally, 1 = tucked behind one another (more than 3)
    private val sTop = Spring(0f)                      // vertical offset: the expanded card sits below the status bar
    private var winTopPx = 0
    private fun statusBarPx(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(26)
    }
    /** Top of the expanded card inside the window: just under the status bar. */
    private fun expTop(): Float = max(0f, (statusBarPx() + dp(6) - winTopPx).toFloat())

    private val shapePath = Path()
    private val shapeRect = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    // ---- Island / pill / bubble surface ----
    // style 0 = solid black, 1 = liquid glass (clear, specular highlight, bright rim), 2 = frost glass (milky, grainy, diffuse).
    // Both glass styles are translucent tints; Android has no per-shape backdrop blur for overlay windows, so frost is simulated.
    private val gBase = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gMilk = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gSheen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, 0f, 1f, 1f, intArrayOf(0x59FFFFFF, 0x00FFFFFF, 0x26FFFFFF), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    }
    private val gSpec = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, 0.06f, 0f, 0.5f, 0xA6FFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
    }
    private val gDiffuse = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, 0f, 0f, 1f, intArrayOf(0x4DFFFFFF, 0x00FFFFFF, 0x12FFFFFF), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
    }
    private val gRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        shader = LinearGradient(0f, 0f, 1f, 1f, intArrayOf(0xE6FFFFFF.toInt(), 0x26FFFFFF, 0x80FFFFFF.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    }
    private val gEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val gGrain = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        val n = 96; val px = IntArray(n * n); val rnd = java.util.Random(7)
        for (i in px.indices) { val v = if (rnd.nextBoolean()) 255 else 0; px[i] = Color.argb(rnd.nextInt(256), v, v, v) }
        shader = BitmapShader(Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
    private val gM = Matrix()

    // ---- Material You (style 3) + Adaptive (style 4) ----
    private var rawPrimary: IslandActivity? = null
    private var rawExtras: List<IslandActivity> = emptyList()
    /** Material You palette, read from the system dynamic colors (Android 12+); mint fallback on older versions. */
    private var moBase = 0xFF16403B.toInt()
    private var moPrimary = 0xFF72E5D5.toInt()
    private var moRim = 0xFF72E5D5.toInt()
    /** Adaptive surface: the color currently drawn (animated) and the one it is heading to. */
    private var surfaceCur = Color.BLACK
    private var surfaceTarget = Color.BLACK
    private var surfaceAnim: ValueAnimator? = null
    private val gTone = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun sysColor(id: Int, fallback: Int): Int =
        if (Build.VERSION.SDK_INT >= 31) try { context.getColor(id) } catch (ignored: Throwable) { fallback } else fallback

    private fun refreshMonet() {
        moBase = sysColor(android.R.color.system_accent1_800, 0xFF16403B.toInt())
        moPrimary = sysColor(android.R.color.system_accent1_200, 0xFF72E5D5.toInt())
        moRim = sysColor(android.R.color.system_accent1_300, 0xFF72E5D5.toInt())
    }

    /** Material You re-colors the accent of non-semantic activities with the system primary; calls, recording and charging keep theirs. */
    private fun styled(a: IslandActivity): IslandActivity {
        if (config.islandStyle != STYLE_MONET) return a
        return when (a.kind) {
            Kind.MEDIA, Kind.LIVE, Kind.BLUETOOTH, Kind.HOTSPOT -> a.copy(accent = moPrimary)
            else -> a
        }
    }

    /** Deep tonal surface derived from an app / album-art / notification color. Neutral sources give a neutral near-black. */
    private fun adaptiveSurface(src: Int): Int {
        val hsv = FloatArray(3); Color.colorToHSV(src, hsv)
        hsv[1] = if (hsv[1] < 0.12f) 0f else (hsv[1] * 0.85f).coerceIn(0.3f, 0.8f)
        hsv[2] = 0.30f
        return Color.HSVToColor(hsv)
    }

    private fun tintFor(a: IslandActivity?): Int {
        if (a == null) return Color.BLACK
        return adaptiveSurface(if (a.surfaceColor != 0) a.surfaceColor else a.accent)
    }

    private fun retintSurface(animate: Boolean) {
        val tgt = tintFor(primary)
        if (tgt == surfaceTarget && surfaceAnim != null) return
        if (tgt == surfaceTarget && surfaceCur == tgt) return
        surfaceTarget = tgt
        surfaceAnim?.cancel(); surfaceAnim = null
        if (!animate || config.islandStyle != STYLE_ADAPTIVE || surfaceCur == tgt) { surfaceCur = tgt; invalidate(); return }
        surfaceAnim = ValueAnimator.ofObject(ArgbEvaluator(), surfaceCur, tgt).apply {
            duration = 380L
            addUpdateListener { surfaceCur = it.animatedValue as Int; invalidate() }
            start()
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration?) { super.onConfigurationChanged(newConfig); refreshMonet(); invalidate() }

    private fun withAlpha(c: Int, al: Float) = Color.argb((Color.alpha(c) * al).toInt().coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    /** Flat tonal fill + soft rim, shared by Material You and Adaptive. */
    private fun drawTonal(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float, al: Float, base: Int, rimColor: Int, lit: Boolean) {
        gTone.style = Paint.Style.FILL; gTone.color = withAlpha(base, al)
        c.drawRoundRect(l, t, r, b, rad, rad, gTone)
        if (lit) {
            gM.setScale(r - l, b - t); gM.postTranslate(l, t)
            gDiffuse.shader.setLocalMatrix(gM); gDiffuse.alpha = (255 * 0.5f * al).toInt()
            c.drawRoundRect(l, t, r, b, rad, rad, gDiffuse)
        }
        val sw = dpf(1f)
        gTone.style = Paint.Style.STROKE; gTone.strokeWidth = sw; gTone.color = withAlpha(rimColor, al)
        c.drawRoundRect(l + sw / 2f, t + sw / 2f, r - sw / 2f, b - sw / 2f, max(0f, rad - sw / 2f), max(0f, rad - sw / 2f), gTone)
        gTone.style = Paint.Style.FILL
    }

    private fun drawSurface(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float, a: Float, tint: Int = Color.BLACK) {
        val w = r - l; val h = b - t
        val al = a.coerceIn(0f, 1f)
        if (w <= 0f || h <= 0f || al <= 0f) return
        val style = config.islandStyle
        if (style == STYLE_MONET) { drawTonal(c, l, t, r, b, rad, al, moBase, withAlpha(moRim, 0.35f), false); return }
        if (style == STYLE_ADAPTIVE) {
            val hsv = FloatArray(3); Color.colorToHSV(tint, hsv); hsv[2] = 0.8f
            drawTonal(c, l, t, r, b, rad, al, tint, withAlpha(Color.HSVToColor(hsv), 0.3f), true); return
        }
        if (style != 1 && style != 2) {
            paint.alpha = (255 * al).toInt(); c.drawRoundRect(l, t, r, b, rad, rad, paint); paint.alpha = 255
            return
        }
        val tint = config.glassTintPct.coerceIn(10, 100) / 100f
        val ia = (255 * al).toInt()
        gM.setScale(w, h); gM.postTranslate(l, t)
        // thin dark outer hairline so the glass still reads on light wallpapers
        val hl = dpf(0.5f)
        gEdge.strokeWidth = dpf(1f); gEdge.color = Color.argb((0x2E * al).toInt(), 0, 0, 0)
        c.drawRoundRect(l - hl, t - hl, r + hl, b + hl, rad + hl, rad + hl, gEdge)
        if (style == 1) {
            // LIQUID GLASS: clear tint + diagonal sheen + specular band + bright refractive rim
            gBase.color = Color.argb((255 * tint * al).toInt(), 12, 12, 16)
            c.drawRoundRect(l, t, r, b, rad, rad, gBase)
            gSheen.shader.setLocalMatrix(gM); gSheen.alpha = ia
            c.drawRoundRect(l, t, r, b, rad, rad, gSheen)
            gSpec.shader.setLocalMatrix(gM); gSpec.alpha = ia
            val inx = min(w, h) * 0.12f
            c.drawRoundRect(l + inx, t + h * 0.06f, r - inx, t + h * 0.52f, rad * 0.7f, rad * 0.7f, gSpec)
            val sw = max(dpf(1.1f), min(w, h) * 0.04f)
            gRim.strokeWidth = sw; gRim.shader.setLocalMatrix(gM); gRim.alpha = ia
            c.drawRoundRect(l + sw / 2f, t + sw / 2f, r - sw / 2f, b - sw / 2f, max(0f, rad - sw / 2f), max(0f, rad - sw / 2f), gRim)
        } else {
            // FROST GLASS: milky grey tint + white veil + diffuse top glow + fine grain + soft uniform rim
            gBase.color = Color.argb((255 * tint * 0.9f * al).toInt(), 34, 34, 40)
            c.drawRoundRect(l, t, r, b, rad, rad, gBase)
            gMilk.color = Color.argb((0x38 * al).toInt(), 255, 255, 255)
            c.drawRoundRect(l, t, r, b, rad, rad, gMilk)
            gDiffuse.shader.setLocalMatrix(gM); gDiffuse.alpha = ia
            c.drawRoundRect(l, t, r, b, rad, rad, gDiffuse)
            gGrain.alpha = (0x2A * al).toInt()
            c.drawRoundRect(l, t, r, b, rad, rad, gGrain)
            gEdge.strokeWidth = dpf(1f); gEdge.color = Color.argb((0x73 * al).toInt(), 255, 255, 255)
            c.drawRoundRect(l + dpf(0.5f), t + dpf(0.5f), r - dpf(0.5f), b - dpf(0.5f), max(0f, rad - dpf(0.5f)), max(0f, rad - dpf(0.5f)), gEdge)
        }
    }
    private var winW = 0
    private var winH = 0
    private var running = false
    private var lastNs = 0L

    init {
        setWillNotDraw(false)
        clipChildren = false
        addView(lead); addView(trail); bubbles.asReversed().forEach { addView(it) }   // farthest first, so the nearest bubble sits on top when they overlap
        visibility = INVISIBLE
        configureSprings()
        setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30 && config.hideInFullscreen) {
                val hidden = !insets.isVisible(WindowInsets.Type.statusBars())
                if (hidden != fullscreenHidden) { fullscreenHidden = hidden; applyTargets() }
            }
            v.onApplyWindowInsets(insets)
        }
    }

    // =====================================================================================================
    // Public API
    // =====================================================================================================

    fun setGeometry(holeWpx: Int, pillHpx: Int, screenWpx: Int, centerXpx: Int = -1, windowLeftPx: Int = 0, windowTopPx: Int = 0) {
        winTopPx = windowTopPx
        holeW = max(holeWpx, dp(18)); pillH = max(pillHpx, dp(12)); screenW = screenWpx
        anchorX = centerXpx; winLeftPx = windowLeftPx
        primary?.let { bindContents(it) }
        applyTargets()
        requestLayout()
    }

    fun bind(p0: IslandActivity?, s0: List<IslandActivity>) {
        rawPrimary = p0; rawExtras = s0.take(MAX_BUBBLES)
        if (p0 == null) endInput()
        val p = p0?.let { styled(it) }
        val old = primary
        primary = p; extras = rawExtras.map { styled(it) }
        if (p != null) {
            val isNew = old == null || old.id != p.id
            if (isNew) endInput()
            // a new activity (or a call that starts ringing) always brings a manually hidden pill back
            if (isNew || (old != null && p.autoExpand && !old.autoExpand)) userHidden = false
            if (isNew) {
                mode = if (p.autoExpand && p.expandable) Mode.EXPANDED else Mode.COMPACT
                if (old != null) { sW.velocity += dpf(220f); sH.velocity += dpf(60f) }  // little "pulse" when switching
                if (p.kind == Kind.LIVE) flashAt = SystemClock.uptimeMillis()   // attention flash for a new notification / flashlight
                if (mode == Mode.EXPANDED) haptic(HapticFeedbackConstants.CONTEXT_CLICK)
            } else {
                if (mode == Mode.EXPANDED && (!p.expandable || (old!!.ringing && !p.ringing))) mode = Mode.COMPACT
                else if (mode == Mode.COMPACT && p.autoExpand && !old!!.autoExpand) mode = Mode.EXPANDED
            }
            ensureCard(p)
            bindContents(p)
        }
        bindBubbles(extras)
        retintSurface(old != null && p != null)
        applyTargets()
    }

    fun expand() {
        val p = primary ?: return
        if (!p.expandable || mode == Mode.EXPANDED) return
        mode = Mode.EXPANDED; haptic(HapticFeedbackConstants.LONG_PRESS); applyTargets(); onExpandedChanged?.invoke(true)
    }

    fun collapse() {
        if (mode != Mode.EXPANDED) return
        endInput()
        mode = Mode.COMPACT; haptic(HapticFeedbackConstants.CONTEXT_CLICK); applyTargets(); onExpandedChanged?.invoke(false)
    }

    // =====================================================================================================
    // Content
    // =====================================================================================================

    private fun ensureCard(p: IslandActivity) {
        val fam = when (p.kind) { Kind.MEDIA -> 0; Kind.CALL -> 1; Kind.LIVE, Kind.RECORD, Kind.HOTSPOT -> 2; else -> -1 }
        if (fam == cardFamily) return
        card?.let { endInput(); removeView(it) }
        card = null; cardFamily = fam
        val act: (ActivityAction) -> Unit = { a -> onAction?.invoke(a) }
        card = when (fam) { 0 -> MediaCard(context, act); 1 -> CallCard(context, act); 2 -> LiveCard(context, act); else -> null }
        card?.let { c -> c.onInput = { on -> inputMode = on; onInputMode?.invoke(on) }; addView(c, 0) }
    }

    private fun bindContents(p: IslandActivity) {
        leadSize = min(pillH - dp(12), dp(24)).coerceAtLeast(dp(14))
        // leading
        when {
            p.kind == Kind.MEDIA -> if (p.art != null) lead.setBitmap(p.art) else if (p.icon != null) lead.setDrawable(p.icon) else lead.setGlyph(Glyph.PLAY, p.accent)
            p.kind == Kind.LIVE && p.icon != null -> lead.setDrawable(p.icon)
            else -> lead.setGlyph(glyphFor(p), p.accent)
        }
        // trailing
        val now = SystemClock.elapsedRealtime()
        trailW = when {
            p.kind == Kind.MEDIA -> { trail.setWave(p.accent, p.playing); dp(22) }
            p.kind == Kind.CALL && p.ringing -> { trail.setWave(p.accent, true); dp(22) }
            p.hasChrono -> { val t = chronoText(p, now); trail.setText(t, legible(p.accent)); ceilW(max(trail.textWidth(t), trail.textWidth("00:00"))) }
            p.indeterminateProgress -> { trail.setRing(0f, p.accent, true); dp(22) }
            p.progress >= 0f -> { trail.setRing(p.progress, p.accent); dp(22) }
            !p.trailingText.isNullOrBlank() -> {
                // never wider than the pill can be; long Bluetooth names are shown in full up to that point
                val maxSide = (max(dp(120), screenW - dp(96)) - holeW) / 2
                val t = trail.fit(p.trailingText, (maxSide - dp(12)).coerceAtLeast(dp(40)).toFloat())
                trail.setText(t, legible(p.accent)); ceilW(trail.textWidth(t))
            }
            else -> { trail.setNone(); 0 }
        }
        val side = max(leadSize, trailW) + dp(10)
        val trimPx = dp(config.widthTrimDp)
        // The 34%-of-screen minimum shifts down with negative trim so the slider keeps working below it; never narrower than the camera hole.
        val desiredCompactW = max(max(holeW + 2 * side + trimPx, (screenW * 0.34f).toInt() + min(0, trimPx)), holeW)
        val safeMaxW = max(dp(120), screenW - dp(96))
        compactW = min(desiredCompactW, safeMaxW)
        card?.let { c ->
            if (p.expandable) {
                c.bind(p)
                c.measure(MeasureSpec.makeMeasureSpec(expandedW(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                cardH = c.measuredHeight
            }
        }
        requestLayout()
        scheduleTick()
    }

    /** Text on the pill must stay readable on pure black (AMOLED): lift dark / saturated accents (e.g. system blue) to a light tint. */
    private fun legible(c: Int): Int {
        val hsv = FloatArray(3); Color.colorToHSV(c, hsv)
        hsv[1] = min(hsv[1], 0.5f); hsv[2] = max(hsv[2], 0.97f)
        return Color.HSVToColor(hsv)
    }

    private fun ceilW(f: Float) = Math.ceil(f.toDouble()).toInt() + dp(2)

    private fun glyphFor(p: IslandActivity): Glyph = when (p.kind) {
        Kind.CALL -> Glyph.PHONE
        Kind.CHARGING -> Glyph.BOLT
        Kind.BLUETOOTH -> Glyph.BLUETOOTH
        Kind.RINGER -> if (p.title == "Ringer") Glyph.BELL else Glyph.BELL_OFF
        Kind.LIVE -> if (p.id == "torch") Glyph.TORCH else if (p.hasChrono) Glyph.TIMER else Glyph.LIVE
        Kind.RECORD -> Glyph.REC
        Kind.HOTSPOT -> Glyph.HOTSPOT
        Kind.MEDIA -> Glyph.PLAY
    }

    private fun chronoText(a: IslandActivity, now: Long) =
        if (a.chronoCountDown) Fmt.time(max(0L, a.chronoBase - now)) else Fmt.time(max(0L, now - a.chronoBase))

    private fun bindBubbles(list: List<IslandActivity>) {
        // Extra activity bubbles sit on the LEFT of the pill, away from Wi-Fi, cellular and battery indicators.
        // Each one is tappable to bring that activity to the front.
        list.forEachIndexed { i, a ->
            val b = bubbles[i]
            when {
                a.kind == Kind.MEDIA && a.art != null -> b.setBitmap(a.art)
                a.icon != null -> b.setDrawable(a.icon)
                else -> b.setGlyph(glyphFor(a), a.accent)
            }
        }
    }

    // ---- fixed anchor ----
    // In system mode the window manager applies a window's new position / size a frame before (or after) the new
    // content is drawn, so any window that moves or changes width while the pill is visible makes the pill jump
    // sideways for a frame. The visible window is therefore FULL SCREEN WIDTH, never moves and never changes width
    // (only its height follows the card), and the pill is drawn at the cutout's x inside it. That x is a constant.
    // Touches are not taken by this window (it is NOT_TOUCHABLE): a small invisible proxy window (see IslandWindow)
    // follows the pill's bounds and forwards touches here, so jitter in that window can never be seen.
    private var anchorX = -1
    private var winLeftPx = 0
    private fun pillCx(): Float = if (anchorX >= 0) (anchorX - winLeftPx).toFloat() else width / 2f

    /** Called whenever the bounds that should receive touches change. Rect is in screen x, window-relative y; null = none. */
    var onTouchBounds: ((android.graphics.Rect?) -> Unit)? = null

    fun touchBounds(): android.graphics.Rect? {
        val p = primary
        if (p == null && extras.isNotEmpty() && !userHidden && !suppressed && !lockHidden && !fullscreenHidden && config.enabled && anchorX >= 0) {
            // only the bubbles take touches; the folded pill over the camera does not
            val right = anchorX - holeW / 2
            val left = (bubbleCx(pillCx(), extras.size - 1) - pillH / 2f - dp(6)).toInt() + (anchorX - pillCx().toInt())
            return android.graphics.Rect(left, 0, right, pillH + dp(6))
        }
        if (p == null || suppressed || lockHidden || fullscreenHidden || !config.enabled || anchorX < 0) return null
        if (userHidden) {
            // small invisible swipe zone on the cutout so the pill can be brought back
            val half = hiddenHalfW()
            return android.graphics.Rect(anchorX - half, 0, anchorX + half, pillH + dp(12))
        }
        val slop = dp(6)
        val expanded = mode == Mode.EXPANDED
        val w = if (expanded) expandedW() else compactW
        val h = if (expanded) max(cardH, dp(90)) else pillH
        var left = anchorX - w / 2 - slop
        val right = anchorX + w / 2 + slop
        if (!expanded && extras.isNotEmpty()) left = min(left, (bubbleCx(pillCx(), extras.size - 1) - pillH / 2f - dp(6)).toInt() + (anchorX - pillCx().toInt()))
        val top = if (expanded) expTop().toInt() else 0
        return android.graphics.Rect(left, top, right, top + h + slop)
    }

    /** Bubble center X: always one gap to the left of the pill's CURRENT left edge, so it never overlaps the expanded card. */
    private fun bubbleBaseW(): Float = if (primary == null) holeW.toFloat() else compactW.toFloat()
    /** Centre of the nearest bubble (one gap left of the pill's current left edge). */
    private fun bubbleFirstCx(pillCx: Float) = pillCx - max(sW.value, bubbleBaseW()) / 2f - dp(8) - pillH / 2f

    /**
     * Distance between bubble centres. Normally one bubble + gap; when the row would run into the clock (left part of the
     * status bar) the bubbles tuck behind one another, the nearest staying fully visible on top.
     */
    private fun bubbleStep(pillCx: Float): Float {
        val n = extras.size
        val normal = (pillH + dp(6)).toFloat()
        if (n <= 1) return normal
        val stacked = pillH * 0.56f
        val want = normal + (stacked - normal) * sOv.value.coerceIn(0f, 1f)
        val room = bubbleFirstCx(pillCx) - (dpf(BUBBLE_CLOCK_SAFE_DP) - winLeftPx) - pillH / 2f   // limit is a screen x; the view is window-relative
        return min(want, max(pillH * 0.38f, room / (n - 1)))
    }

    /** Depth of bubble i in the stack: 1 for the nearest, smaller and dimmer further back (only while stacked). */
    private fun bubbleDepth(i: Int): Float = 1f - 0.08f * i * sOv.value.coerceIn(0f, 1f)
    private fun bubbleCx(pillCx: Float, i: Int) = bubbleFirstCx(pillCx) - i * bubbleStep(pillCx)

    private fun expandedW() = min(screenW - dp(24), dp(config.cardWidthDp))

    // ---- periodic updates (chronometers, media progress) ----
    private val ticker = object : Runnable {
        override fun run() {
            val p = primary
            if (p == null || !isAttachedToWindow) return
            val now = SystemClock.elapsedRealtime()
            if (p.hasChrono && !(p.kind == Kind.CALL && p.ringing)) trail.setText(chronoText(p, now), legible(p.accent))
            if (sP.value > 0.02f) card?.tick(p, now)
            postDelayed(this, 500)
        }
    }
    private fun scheduleTick() { removeCallbacks(ticker); post(ticker) }

    // =====================================================================================================
    // Targets & animation
    // =====================================================================================================

    private fun configureSprings() {
        val sp = config.animSpeed.coerceIn(0.5f, 2f); val k = sp * sp
        sW.stiffness = 300f * k; sH.stiffness = 300f * k; sR.stiffness = 300f * k
        sP.stiffness = 260f * k; sP.dampingRatio = 1f
        sA.stiffness = 420f * k; sA.dampingRatio = 1f
        sBs.forEach { it.stiffness = 320f * k; it.dampingRatio = 0.62f }
        sOv.stiffness = 240f * k; sOv.dampingRatio = 0.8f
        sTop.stiffness = 300f * k; sTop.dampingRatio = 0.86f
    }

    private fun applyTargets() {
        val p = primary
        // with no main activity the pill stays folded into the cutout, but persistent bubbles (Bluetooth) remain visible
        val hidden = (p == null && extras.isEmpty()) || suppressed || lockHidden || fullscreenHidden || !config.enabled || userHidden
        val expanded = !hidden && p != null && mode == Mode.EXPANDED
        val tw: Float; val th: Float; val tr: Float
        when {
            hidden || p == null -> { tw = holeW.toFloat(); th = pillH * 0.9f; tr = th / 2f }
            expanded -> { tw = expandedW().toFloat(); th = max(cardH, dp(90)).toFloat(); tr = min(dpf(44f), th / 2f) }
            else -> { tw = compactW.toFloat(); th = pillH.toFloat(); tr = th / 2f }
        }
        val grow = tw * th > sW.target * sH.target
        // slightly firmer spring on the way back, softer overshoot on the way out (feels like iOS)
        val damp = if (grow) 0.74f else 0.84f
        sW.dampingRatio = damp; sH.dampingRatio = damp; sR.dampingRatio = 0.9f
        sW.target = tw; sH.target = th; sR.target = tr
        sTop.target = if (expanded) expTop() else 0f
        sP.target = if (expanded) 1f else 0f
        sA.target = if (hidden) 0f else 1f
        if (!initialized) {
            initialized = true
            sW.snap(tw); sH.snap(th); sR.snap(tr); sTop.snap(sTop.target); sP.snap(sP.target); sA.snap(0f); sBs.forEach { it.snap(0f); it.target = 0f }
        }
        // the secondary bubble is only shown next to the compact pill; it fades out while the card is open
        sBs.forEachIndexed { i, sp -> sp.target = if (i < extras.size && !hidden && !expanded) 1f else 0f }
        sOv.target = if (extras.size > BUBBLES_BEFORE_STACK) 1f else 0f
        if (!hidden && visibility != VISIBLE) visibility = VISIBLE
        syncWindow(false)
        kick()
    }

    private fun targetWinH() = max(sH.target.toInt(), pillH) + sTop.target.toInt() + dp(18)

    private fun syncWindow(settled: Boolean) {
        val th = targetWinH()
        val nh = if (settled) th else max(winH, th)
        winW = screenW   // fixed: the window never changes width or x
        if (nh != winH) {
            winH = nh
            onSizeNeeded?.invoke(winW, nh)
        }
        onTouchBounds?.invoke(touchBounds())
    }

    private fun kick() {
        if (running) return
        running = true; lastNs = System.nanoTime(); postOnAnimation(frame)
    }

    private val frame = object : Runnable {
        override fun run() {
            val now = System.nanoTime(); val dt = (now - lastNs) / 1e9f; lastNs = now
            sW.step(dt); sH.step(dt); sR.step(dt); sTop.step(dt); sP.step(dt); sA.step(dt); sOv.step(dt); sBs.forEach { it.step(dt) }
            applyVisuals()
            invalidate()
            if (sW.settled && sH.settled && sR.settled && sTop.settled && sP.settled && sA.settled && sOv.settled && sBs.all { it.settled }) {
                running = false
                syncWindow(true)
                if (sA.target == 0f) visibility = INVISIBLE
            } else postOnAnimation(this)
        }
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float { val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

    private fun applyVisuals() {
        val p = sP.value.coerceIn(0f, 1f)
        val ca = 1f - smooth(0f, 0.35f, p)
        val xa = smooth(0.4f, 0.85f, p)
        lead.alpha = ca; trail.alpha = ca
        val c = card
        if (c != null) {
            c.alpha = xa; c.visibility = if (xa <= 0.01f) INVISIBLE else VISIBLE
            val s = 0.9f + 0.1f * xa; c.pivotX = c.width / 2f; c.pivotY = 0f; c.scaleX = s; c.scaleY = s; c.translationY = sTop.value
        }
        bubbles.forEachIndexed { i, bubble ->
            val b = sBs[i].value
            val dp0 = bubbleDepth(i)
            bubble.alpha = (b * (0.55f + 0.45f * dp0)).coerceIn(0f, 1f); bubble.scaleX = max(0f, b) * dp0; bubble.scaleY = max(0f, b) * dp0
            bubble.visibility = if (b <= 0.01f) INVISIBLE else VISIBLE
            val laidOutCx = pillCx() - compactW / 2f - dp(8) - pillH / 2f - i * (pillH + dp(6))
            bubble.translationX = bubbleCx(pillCx(), i) - laidOutCx  // follow the pill's left edge and the overlap spacing
        }
        alpha = sA.value.coerceIn(0f, 1f)
        if (config.blur && Build.VERSION.SDK_INT >= 31) {
            setBlur(lead, (1f - ca) * dpf(8f)); setBlur(trail, (1f - ca) * dpf(8f)); c?.let { setBlur(it, (1f - xa) * dpf(14f)) }
        }
    }

    private fun setBlur(v: View, px: Float) {
        if (Build.VERSION.SDK_INT < 31) return
        val r = px.toInt()
        if (v.tag == r) return
        v.tag = r
        v.setRenderEffect(if (r <= 0) null else RenderEffect.createBlurEffect(r.toFloat(), r.toFloat(), Shader.TileMode.CLAMP))
    }

    // =====================================================================================================
    // Layout & drawing
    // =====================================================================================================

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = if (MeasureSpec.getMode(wSpec) == MeasureSpec.UNSPECIFIED) max(winW, compactW) else MeasureSpec.getSize(wSpec)
        val h = if (MeasureSpec.getMode(hSpec) == MeasureSpec.UNSPECIFIED) max(winH, pillH) else MeasureSpec.getSize(hSpec)
        setMeasuredDimension(w, h)
        lead.measure(MeasureSpec.makeMeasureSpec(leadSize, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(leadSize, MeasureSpec.EXACTLY))
        trail.measure(MeasureSpec.makeMeasureSpec(trailW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(pillH, MeasureSpec.EXACTLY))
        val bs = (pillH * 0.58f).toInt()
        bubbles.forEach { it.measure(MeasureSpec.makeMeasureSpec(bs, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(bs, MeasureSpec.EXACTLY)) }
        card?.measure(MeasureSpec.makeMeasureSpec(expandedW(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(max(cardH, 1), MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cx = pillCx().toInt()
        val left = cx - compactW / 2 + dp(10)
        val ly = (pillH - lead.measuredHeight) / 2
        lead.layout(left, ly, left + lead.measuredWidth, ly + lead.measuredHeight)
        val tr = cx + compactW / 2 - dp(10)
        trail.layout(tr - trail.measuredWidth, 0, tr, pillH)
        bubbles.forEachIndexed { i, bubble ->
            val bx = cx - compactW / 2 - dp(8) - pillH - i * (pillH + dp(6))
            val by = (pillH - bubble.measuredHeight) / 2
            bubble.layout(bx + (pillH - bubble.measuredWidth) / 2, by, bx + (pillH + bubble.measuredWidth) / 2, by + bubble.measuredHeight)
        }
        card?.let { c -> val ex = expandedW(); c.layout(cx - ex / 2, 0, cx + ex / 2, c.measuredHeight) }
    }


    // ---- charging animation: battery-level energy fill, shimmer, rising sparks, pulsing bolt halo, outward ripples ----
    private val fxP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fxPath = Path()

    private fun chargePct(a: IslandActivity): Int = a.trailingText?.filter { it.isDigit() }?.toIntOrNull()?.coerceIn(0, 100) ?: 100

    private fun drawChargeFx(c: Canvas, a: IslandActivity, rad: Float) {
        val now = SystemClock.uptimeMillis()
        val l = shapeRect.left; val t = shapeRect.top; val r = shapeRect.right; val b = shapeRect.bottom
        val w = r - l; val h = b - t
        if (w <= 0f || h <= 0f) return
        val acc = a.accent
        val rgb = acc and 0x00FFFFFF
        val pct = chargePct(a)
        // 1) outward ripples (outside the pill)
        for (k in 0..1) {
            val ph = ((now + k * 700L) % 1400L) / 1400f
            val grow = dpf(7f) * ph
            fxP.style = Paint.Style.STROKE; fxP.strokeWidth = dpf(1.6f)
            fxP.color = ((((1f - ph) * 0.55f * 255).toInt()) shl 24) or rgb
            c.drawRoundRect(l - grow, t - grow * 0.4f, r + grow, b + grow, rad + grow, rad + grow, fxP)
        }
        c.save(); c.clipPath(shapePath)
        // 2) energy fill up to the battery level, with a living wave edge
        val fillX = l + w * (0.18f + 0.82f * (pct / 100f))
        fxPath.reset(); fxPath.moveTo(l, t)
        val amp = dpf(2.2f); val ph = now / 260.0
        fxPath.lineTo(fillX + amp, t)
        var y = t
        while (y <= b) { fxPath.lineTo(fillX + amp * kotlin.math.sin(ph + y / dpf(5f)).toFloat(), y); y += dpf(2f) }
        fxPath.lineTo(l, b); fxPath.close()
        fxP.style = Paint.Style.FILL; fxP.color = (0x3C shl 24) or rgb
        c.drawPath(fxPath, fxP)
        // 3) diagonal shimmer sweeping through
        val sh = ((now % 1700L) / 1700f)
        val sx = l - w * 0.3f + w * 1.6f * sh
        fxPath.reset(); fxPath.moveTo(sx, t); fxPath.lineTo(sx + dpf(16f), t); fxPath.lineTo(sx + dpf(16f) - h * 0.45f, b); fxPath.lineTo(sx - h * 0.45f, b); fxPath.close()
        fxP.color = 0x30FFFFFF; c.drawPath(fxPath, fxP)
        // 4) rising sparks
        for (i in 0 until 7) {
            val per = 1100L + i * 170L
            val f = ((now + i * 313L) % per) / per.toFloat()
            val px = l + w * (0.1f + 0.8f * (((i * 37) % 100) / 100f))
            val py = b - h * f
            fxP.color = (((1f - f) * 0.85f * 255).toInt() shl 24) or rgb
            c.drawCircle(px, py, dpf(1.1f + (i % 3) * 0.5f), fxP)
        }
        // 5) pulsing halo behind the bolt
        val lx = lead.x + lead.width / 2f; val ly = lead.y + lead.height / 2f
        val pu = 0.5f + 0.5f * kotlin.math.sin(now / 190.0).toFloat()
        fxP.color = ((0x30 + (0x40 * pu).toInt()) shl 24) or rgb
        c.drawCircle(lx, ly, min(lead.width, lead.height) * (0.62f + 0.22f * pu), fxP)
        c.restore()
        postInvalidateOnAnimation()
    }


    // ---- attention flash: two quick white flashes + an accent outline when a new notification / flashlight arrives ----
    private var flashAt = 0L
    private fun drawFlash(c: Canvas, rad: Float) {
        val e = SystemClock.uptimeMillis() - flashAt
        if (e > 1200L) { flashAt = 0L; return }
        val k = 1f - e / 1200f
        val a = (kotlin.math.abs(kotlin.math.sin(e / 1200f * Math.PI * 2.0)).toFloat() * k).coerceIn(0f, 1f)
        val col = (primary?.accent ?: Color.WHITE) and 0x00FFFFFF
        c.save(); c.clipPath(shapePath)
        fxP.style = Paint.Style.FILL; fxP.color = ((a * 0.42f * 255).toInt() shl 24) or 0x00FFFFFF
        c.drawRoundRect(shapeRect, rad, rad, fxP)
        c.restore()
        val g = dpf(2f)
        fxP.style = Paint.Style.STROKE; fxP.strokeWidth = dpf(2.2f); fxP.color = ((a * 255).toInt() shl 24) or col
        c.drawRoundRect(shapeRect.left - g, shapeRect.top - g, shapeRect.right + g, shapeRect.bottom + g, rad + g, rad + g, fxP)
        postInvalidateOnAnimation()
    }

    override fun dispatchDraw(canvas: Canvas) {
        val cx = pillCx()
        val w = max(0f, sW.value); val h = max(0f, sH.value); val rad = min(sR.value, min(w, h) / 2f)
        shapeRect.set(cx - w / 2f, sTop.value, cx + w / 2f, sTop.value + h)
        shapePath.reset(); shapePath.addRoundRect(shapeRect, rad, rad, Path.Direction.CW)
        if (primary != null) drawSurface(canvas, shapeRect.left, shapeRect.top, shapeRect.right, shapeRect.bottom, rad, 1f, surfaceCur)
        primary?.let { if (it.kind == Kind.CHARGING) drawChargeFx(canvas, it, rad) }
        if (flashAt != 0L) drawFlash(canvas, rad)
        super.dispatchDraw(canvas)
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        val bi = bubbles.indexOfFirst { it === child }
        if (bi >= 0) {
            // each bubble paints its own surface right before its glyph, so overlapping bubbles stack cleanly
            val b = sBs[bi].value
            if (b > 0.01f) {
                val bd = pillH.toFloat()
                val bcx = bubbleCx(pillCx(), bi)
                val br = bd / 2f * max(0f, b) * bubbleDepth(bi)
                val ov = sOv.value.coerceIn(0f, 1f)
                if (ov > 0.02f && bi < extras.size - 1) {
                    // dark separation ring: this bubble looks cut out of the one tucked behind it
                    fxP.style = Paint.Style.STROKE; fxP.strokeWidth = dpf(2.2f)
                    fxP.color = ((0xB0 * ov * b.coerceIn(0f, 1f)).toInt() shl 24)
                    canvas.drawCircle(bcx, bd / 2f, br + dpf(1.1f), fxP)
                }
                drawSurface(canvas, bcx - br, bd / 2f - br, bcx + br, bd / 2f + br, br, b, tintFor(extras.getOrNull(bi)))
                if (ov > 0.02f) {
                    // fine accent hairline so each bubble in the stack keeps its own colour
                    fxP.style = Paint.Style.STROKE; fxP.strokeWidth = dpf(1f)
                    fxP.color = ((0x73 * ov * b.coerceIn(0f, 1f)).toInt() shl 24) or ((extras.getOrNull(bi)?.accent ?: Color.WHITE) and 0x00FFFFFF)
                    canvas.drawCircle(bcx, bd / 2f, br - dpf(0.5f), fxP)
                }
            }
            return super.drawChild(canvas, child, drawingTime)
        }
        canvas.save(); canvas.clipPath(shapePath)
        val r = super.drawChild(canvas, child, drawingTime)
        canvas.restore()
        return r
    }

    // =====================================================================================================
    // Touch: tap = open app, long-press / swipe-down = expand, swipe-up = collapse, swipe sideways = next activity
    // =====================================================================================================

    private var downX = 0f
    private var downY = 0f
    private var downOnBubble = false
    private var downBubble = -1
    private var longFired = false
    private val longPress = Runnable {
        if (mode == Mode.COMPACT && primary?.expandable == true && !downOnBubble) { longFired = true; expand() }
    }

    private fun hitShape(x: Float, y: Float): Boolean {
        return shapeRect.contains(x, y) || (x >= shapeRect.left - dp(6) && x <= shapeRect.right + dp(6) && y <= shapeRect.bottom + dp(6))
    }
    /** Index of the extra bubble under the touch, or -1. */
    private fun hitBubble(x: Float, y: Float): Int {
        val bd = pillH.toFloat()
        val r = bd / 2f + dp(6)
        for (i in extras.indices) {
            if (sBs[i].value < 0.5f) continue
            val dx = x - bubbleCx(pillCx(), i); val dy = y - bd / 2f
            if (dx * dx + dy * dy <= r * r) return i
        }
        return -1
    }

    /**
     * When the card is open, a tap on anything that is not a real control (button, seek bar) belongs to the island itself and
     * opens the app. Taking the touch here, before the card's text rows / padding see it, makes the whole card behave the
     * same instead of only the text lines.
     */
    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_DOWN || !isExpanded) return false
        val c = card ?: return false
        return !controlAt(c, e.x, e.y)
    }

    /** True if (x, y), given in [v]'s parent coordinates, is on a clickable control inside [v]. */
    private fun controlAt(v: View, x: Float, y: Float): Boolean {
        if (v.visibility != VISIBLE) return false
        var lx = x - v.left - v.translationX; var ly = y - v.top - v.translationY
        val sx = if (v.scaleX == 0f) 1f else v.scaleX; val sy = if (v.scaleY == 0f) 1f else v.scaleY
        lx = (lx - v.pivotX) / sx + v.pivotX; ly = (ly - v.pivotY) / sy + v.pivotY
        if (lx < 0f || ly < 0f || lx >= v.width || ly >= v.height) return false
        if (v is ViewGroup) {
            for (i in v.childCount - 1 downTo 0) if (controlAt(v.getChildAt(i), lx, ly)) return true
            return false
        }
        return v.isClickable || (v is SeekBarView && v.onSeek != null)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val p = primary
        if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) { if (isExpanded && !inputMode) collapse(); return false }
        if (p == null) {
            // bubble-only state: a tap on a bubble (e.g. Bluetooth) peeks it
            if (suppressed || lockHidden || userHidden || sA.value < 0.5f) return false
            val slopB = ViewConfiguration.get(context).scaledTouchSlop
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downBubble = hitBubble(e.x, e.y); if (downBubble < 0) return false; downX = e.x; downY = e.y; return true }
                MotionEvent.ACTION_UP -> {
                    if (downBubble >= 0 && abs(e.x - downX) < slopB * 2 && abs(e.y - downY) < slopB * 2) {
                        haptic(HapticFeedbackConstants.CONTEXT_CLICK); extras.getOrNull(downBubble)?.let { onSwap?.invoke(it) }
                    }
                    downBubble = -1
                    return true
                }
            }
            return downBubble >= 0
        }
        if (suppressed || lockHidden) return false
        if (userHidden) return onHiddenTouch(e)
        if (sA.value < 0.5f) return false
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downBubble = hitBubble(e.x, e.y); downOnBubble = downBubble >= 0
                if (!downOnBubble && !hitShape(e.x, e.y)) return false
                downX = e.x; downY = e.y; longFired = false
                pivotX = pillCx(); pivotY = 0f
                animate().scaleX(0.965f).scaleY(0.965f).setDuration(110).start()
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> if (abs(e.x - downX) > slop || abs(e.y - downY) > slop) removeCallbacks(longPress)
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress); animate().scaleX(1f).scaleY(1f).setDuration(160).start() }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                if (longFired) return true
                val dx = e.x - downX; val dy = e.y - downY
                val vertical = abs(dy) > abs(dx)
                when {
                    downOnBubble && abs(dx) < slop * 2 && abs(dy) < slop * 2 -> { haptic(HapticFeedbackConstants.CONTEXT_CLICK); extras.getOrNull(downBubble)?.let { onSwap?.invoke(it) } }
                    // swipe to clear: notification-backed activities only (compact: swipe up, open card: swipe sideways)
                    p.id.startsWith("live:") && !isExpanded && vertical && dy < -dp(22) -> { haptic(HapticFeedbackConstants.CONTEXT_CLICK); onClear?.invoke(p) }
                    p.id.startsWith("live:") && isExpanded && !vertical && abs(dx) > dp(70) -> { haptic(HapticFeedbackConstants.CONTEXT_CLICK); collapse(); onClear?.invoke(p) }
                    isExpanded && dy < -dp(26) && vertical -> collapse()
                    !isExpanded && dy > dp(22) && vertical -> expand()
                    // swipe sideways, either direction: hide the pill into the cutout (the same swipe on the cutout shows it again)
                    !isExpanded && abs(dx) > dp(40) && !vertical -> { haptic(HapticFeedbackConstants.CONTEXT_CLICK); userHidden = true; applyTargets() }
                    abs(dx) < slop * 2 && abs(dy) < slop * 2 -> {
                        if (config.tapExpands && !isExpanded && p.expandable) expand()
                        else { onOpen?.invoke(p); if (isExpanded) collapse() }
                    }
                }
                return true
            }
        }
        return true
    }

    /** Half width of the swipe zone while hidden: as wide as the pill was, so a swipe where the pill used to be is caught. */
    private fun hiddenHalfW(): Int = max(compactW / 2 + dp(10), holeW / 2 + dp(36))

    /** Touches while the pill is hidden by the user: swipe left (or tap) on the cutout zone shows it again. */
    private fun onHiddenTouch(e: MotionEvent): Boolean {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (abs(e.x - pillCx()) > hiddenHalfW() || e.y > pillH + dp(12)) return false
                downX = e.x; downY = e.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = e.x - downX; val dy = e.y - downY
                // any sideways swipe or a tap on the cutout zone brings the pill back
                if ((abs(dx) > dp(30) && abs(dx) > abs(dy)) || (abs(dx) < slop * 2 && abs(dy) < slop * 2)) {
                    haptic(HapticFeedbackConstants.CONTEXT_CLICK); userHidden = false; applyTargets()
                }
                return true
            }
        }
        return true
    }

    private fun haptic(kind: Int) {
        if (config.haptics) performHapticFeedback(kind, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); refreshMonet(); requestApplyInsets(); scheduleTick() }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); removeCallbacks(ticker); removeCallbacks(longPress); running = false }
}
