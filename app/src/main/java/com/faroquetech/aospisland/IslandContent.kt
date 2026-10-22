package com.faroquetech.aospisland

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max

/** Expanded-state content. One card class per activity family; bind() is cheap and may be called often. */
abstract class Card(ctx: Context, protected val onAction: (ActivityAction) -> Unit) : LinearLayout(ctx) {
    protected val d = resources.displayMetrics.density
    protected fun dp(v: Int) = (v * d).toInt()
    init { orientation = VERTICAL; setPadding(dp(22), dp(18), dp(22), dp(18)) }

    abstract fun bind(a: IslandActivity)
    /** Set by the island: true while a card text field needs the keyboard (the island window must become focusable). */
    var onInput: ((Boolean) -> Unit)? = null
    /** Called ~2x per second while visible. */
    open fun tick(a: IslandActivity, now: Long) {}

    protected fun text(sizeSp: Float, color: Int, medium: Boolean = false, bold: Boolean = false): TextView = TextView(context).apply {
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sizeSp); setTextColor(color); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        typeface = Typeface.create(if (bold) "sans-serif" else if (medium) "sans-serif-medium" else "sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    protected fun actionOf(a: IslandActivity, k: ActionKind) = a.actions.firstOrNull { it.kind == k }

    protected fun chrono(a: IslandActivity, now: Long): String =
        if (a.chronoCountDown) Fmt.time(max(0L, a.chronoBase - now)) else Fmt.time(max(0L, now - a.chronoBase))
}

class MediaCard(ctx: Context, onAction: (ActivityAction) -> Unit) : Card(ctx, onAction) {
    private val art = LeadView(ctx)
    private val title = text(17f, Color.WHITE, bold = true)
    private val artist = text(14f, 0xFFB0B0B8.toInt())
    private val wave = TrailView(ctx)
    private val seek = SeekBarView(ctx)
    private val elapsed = text(11f, 0xFF9A9AA2.toInt())
    private val remaining = text(11f, 0xFF9A9AA2.toInt())
    private val prev = GlyphView(ctx, Glyph.PREV, Color.WHITE)
    private val play = GlyphView(ctx, Glyph.PAUSE, Color.WHITE)
    private val next = GlyphView(ctx, Glyph.NEXT, Color.WHITE)
    private var seekEnabled = false

    init {
        val top = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(art, LayoutParams(dp(58), dp(58)))
        val col = LinearLayout(ctx).apply { orientation = VERTICAL; setPadding(dp(14), 0, dp(10), 0) }
        col.addView(title); col.addView(artist, LayoutParams(-1, -2).apply { topMargin = dp(3) })
        top.addView(col, LayoutParams(0, -2, 1f))
        top.addView(wave, LayoutParams(dp(26), dp(22)))
        addView(top, LayoutParams(-1, -2))
        addView(seek, LayoutParams(-1, dp(16)).apply { topMargin = dp(14) })
        val times = LinearLayout(ctx).apply { orientation = HORIZONTAL }
        times.addView(elapsed, LayoutParams(0, -2, 1f)); remaining.gravity = Gravity.END; times.addView(remaining, LayoutParams(0, -2, 1f))
        addView(times, LayoutParams(-1, -2).apply { topMargin = dp(2) })
        val controls = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        controls.addView(prev, LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(34) })
        controls.addView(play, LayoutParams(dp(44), dp(44)))
        controls.addView(next, LayoutParams(dp(34), dp(34)).apply { leftMargin = dp(34) })
        addView(controls, LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    override fun bind(a: IslandActivity) {
        title.text = a.title.ifBlank { "Now playing" }
        artist.text = a.subtitle
        if (a.art != null) art.setBitmap(a.art) else if (a.icon != null) art.setDrawable(a.icon) else art.setGlyph(Glyph.PLAY, a.accent)
        wave.setWave(a.accent, a.playing)
        seek.color = a.accent
        play.set(if (a.playing) Glyph.PAUSE else Glyph.PLAY, Color.WHITE)
        bindBtn(prev, actionOf(a, ActionKind.PREV)); bindBtn(play, actionOf(a, ActionKind.PLAYPAUSE)); bindBtn(next, actionOf(a, ActionKind.NEXT))
        seekEnabled = a.seek != null && a.durationMs > 0
        seek.onSeek = if (seekEnabled) ({ f: Float -> a.seek?.invoke((f * a.durationMs).toLong()); Unit }) else null
        seek.visibility = if (a.durationMs > 0) VISIBLE else INVISIBLE
        tick(a, SystemClock.elapsedRealtime())
    }

    private fun bindBtn(v: View, act: ActivityAction?) {
        v.alpha = if (act == null) 0.3f else 1f
        v.setOnClickListener(if (act == null) null else View.OnClickListener { onAction(act) })
    }

    override fun tick(a: IslandActivity, now: Long) {
        if (a.durationMs <= 0 || a.positionMs < 0) { elapsed.text = ""; remaining.text = ""; return }
        val pos = (if (a.playing) a.positionMs + ((now - a.positionAt) * a.speed).toLong() else a.positionMs).coerceIn(0L, a.durationMs)
        if (!seek.dragging) seek.fraction = pos.toFloat() / a.durationMs
        elapsed.text = Fmt.time(pos); remaining.text = "-" + Fmt.time(a.durationMs - pos)
    }
}

class CallCard(ctx: Context, onAction: (ActivityAction) -> Unit) : Card(ctx, onAction) {
    private val avatar = GlyphView(ctx, Glyph.PHONE, Color.WHITE, pad = 0f)
    private val name = text(19f, Color.WHITE, bold = true)
    private val status = text(14f, 0xFF30D158.toInt())
    private val buttons = LinearLayout(ctx).apply { orientation = HORIZONTAL }

    init {
        val top = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        avatar.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF30D158.toInt()) }
        avatar.setPadding(dp(12), dp(12), dp(12), dp(12))
        top.addView(avatar, LayoutParams(dp(52), dp(52)))
        val col = LinearLayout(ctx).apply { orientation = VERTICAL; setPadding(dp(14), 0, 0, 0) }
        col.addView(name); col.addView(status, LayoutParams(-1, -2).apply { topMargin = dp(4) })
        top.addView(col, LayoutParams(0, -2, 1f))
        addView(top, LayoutParams(-1, -2))
        addView(buttons, LayoutParams(-1, -2).apply { topMargin = dp(16) })
    }

    private var builtFor: Boolean? = null
    override fun bind(a: IslandActivity) {
        name.text = a.title.ifBlank { "Unknown" }
        if (builtFor != a.ringing || buttons.childCount == 0) {
            builtFor = a.ringing; buttons.removeAllViews()
            val gap = dp(12)
            if (a.ringing) {
                buttons.addView(button(Glyph.HANGUP, "Decline", 0xFFFF453A.toInt(), actionOf(a, ActionKind.DECLINE) ?: actionOf(a, ActionKind.HANGUP)), LayoutParams(0, -2, 1f).apply { rightMargin = gap / 2 })
                buttons.addView(button(Glyph.PHONE, "Accept", 0xFF30D158.toInt(), actionOf(a, ActionKind.ANSWER)), LayoutParams(0, -2, 1f).apply { leftMargin = gap / 2 })
            } else {
                buttons.addView(button(Glyph.HANGUP, "End call", 0xFFFF453A.toInt(), actionOf(a, ActionKind.HANGUP) ?: actionOf(a, ActionKind.DECLINE)), LayoutParams(-1, -2))
            }
        } else {
            // refresh click targets (lambdas change with each snapshot)
            for (i in 0 until buttons.childCount) {
                val act = if (a.ringing) (if (i == 0) actionOf(a, ActionKind.DECLINE) ?: actionOf(a, ActionKind.HANGUP) else actionOf(a, ActionKind.ANSWER)) else actionOf(a, ActionKind.HANGUP) ?: actionOf(a, ActionKind.DECLINE)
                buttons.getChildAt(i).setOnClickListener(if (act == null) null else View.OnClickListener { onAction(act) })
            }
        }
        tick(a, SystemClock.elapsedRealtime())
    }

    private fun button(g: Glyph, label: String, color: Int, act: ActivityAction?): View =
        PillButton(context, g, label, color).apply { setOnClickListener(if (act == null) null else View.OnClickListener { onAction(act) }) }

    override fun tick(a: IslandActivity, now: Long) {
        status.text = if (a.ringing) (a.subtitle.ifBlank { "Incoming call" }) else if (a.hasChrono) chrono(a, now) else a.subtitle.ifBlank { "On call" }
    }
}

class LiveCard(ctx: Context, onAction: (ActivityAction) -> Unit) : Card(ctx, onAction) {
    private val icon = LeadView(ctx)
    private val title = text(17f, Color.WHITE, bold = true)
    private val sub = text(14f, 0xFFB0B0B8.toInt())
    private val right = text(24f, 0xFFFF9F0A.toInt(), medium = true)
    private val bar = SeekBarView(ctx)
    private val actions = LinearLayout(ctx).apply { orientation = HORIZONTAL }

    // ---- inline reply (messaging notifications) ----
    private var replyFn: ((String) -> Boolean)? = null
    private var typing = false
    private val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private val input = object : EditText(ctx) {
        // Back closes the keyboard first: end typing so the island window gives focus back to the app underneath
        override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { endTyping(); return true }
            return super.onKeyPreIme(keyCode, event)
        }
    }.apply {
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f); setTextColor(Color.WHITE); setHintTextColor(0xFF8E8E93.toInt())
        hint = "Reply…"; background = null; setSingleLine(true); includeFontPadding = false
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_ACTION_SEND; isClickable = true
        setPadding(0, dp(10), dp(6), dp(10))
        setOnTouchListener { _, ev -> if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) beginTyping(); false }
        setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { sendReply(); true } else false }
    }
    private val sendBtn = TextView(ctx).apply {
        text = "↑"; setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f); setTextColor(Color.BLACK); gravity = Gravity.CENTER
        includeFontPadding = false; isClickable = true; typeface = Typeface.create("sans-serif", Typeface.BOLD)
        setOnClickListener { sendReply() }
    }
    private val replyRow = LinearLayout(ctx).apply {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = GONE
        background = GradientDrawable().apply { setColor(0xFF2C2C2E.toInt()); cornerRadius = dp(22).toFloat() }
        setPadding(dp(16), 0, dp(5), 0)
    }

    private fun beginTyping() {
        if (typing) { showKeyboard(); return }
        typing = true; onInput?.invoke(true)
        showKeyboard()
    }
    private fun showKeyboard() { input.requestFocus(); input.postDelayed({ if (typing) imm.showSoftInput(input, 0) }, 40L) }
    /** The island window only becomes focusable after [onInput]; the keyboard is shown as soon as it really has focus. */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) { super.onWindowFocusChanged(hasWindowFocus); if (hasWindowFocus && typing) showKeyboard() }
    fun endTyping() {
        if (!typing) return
        typing = false
        try { imm.hideSoftInputFromWindow(input.windowToken, 0) } catch (ignored: Throwable) { }
        input.clearFocus(); onInput?.invoke(false)
    }
    private fun sendReply() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        val ok = try { replyFn?.invoke(t) ?: false } catch (ignored: Throwable) { false }
        if (ok) input.setText("")
        input.hint = if (ok) "Sent ✓" else "Couldn't send"
        endTyping()
        input.postDelayed({ input.hint = "Reply…" }, 2200L)
    }

    init {
        val top = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(icon, LayoutParams(dp(46), dp(46)))
        val col = LinearLayout(ctx).apply { orientation = VERTICAL; setPadding(dp(14), 0, dp(10), 0) }
        col.addView(title); col.addView(sub, LayoutParams(-1, -2).apply { topMargin = dp(3) })
        top.addView(col, LayoutParams(0, -2, 1f))
        top.addView(right, LayoutParams(-2, -2))
        addView(top, LayoutParams(-1, -2))
        addView(bar, LayoutParams(-1, dp(14)).apply { topMargin = dp(12) })
        addView(actions, LayoutParams(-1, -2).apply { topMargin = dp(12) })
        replyRow.addView(input, LayoutParams(0, dp(44), 1f))
        replyRow.addView(sendBtn, LayoutParams(dp(34), dp(34)))
        addView(replyRow, LayoutParams(-1, dp(44)).apply { topMargin = dp(12) })
    }

    override fun bind(a: IslandActivity) {
        replyFn = a.reply
        replyRow.visibility = if (a.reply != null) VISIBLE else GONE
        (sendBtn.background as? GradientDrawable)?.setColor(a.accent) ?: run { sendBtn.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(a.accent) } }
        if (a.reply == null) endTyping()
        title.text = a.title; sub.text = a.subtitle; sub.visibility = if (a.subtitle.isBlank()) GONE else VISIBLE
        if (a.icon != null) icon.setDrawable(a.icon) else icon.setGlyph(if (a.kind == Kind.RECORD) Glyph.REC else if (a.hasChrono) Glyph.TIMER else Glyph.LIVE, a.accent)
        right.setTextColor(a.accent); bar.color = a.accent
        bar.visibility = if (a.progress >= 0f) VISIBLE else GONE; if (a.progress >= 0f) bar.fraction = a.progress
        actions.removeAllViews()
        val shown = a.actions.filter { it.kind == ActionKind.CUSTOM && it.label.isNotBlank() }.take(3)
        actions.visibility = if (shown.isEmpty()) GONE else VISIBLE
        shown.forEachIndexed { i, act ->
            actions.addView(PillButton(context, null, act.label, 0xFF2C2C2E.toInt()).apply { setOnClickListener { onAction(act) } },
                LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(8) })
        }
        tick(a, SystemClock.elapsedRealtime())
    }

    override fun tick(a: IslandActivity, now: Long) {
        right.text = if (a.hasChrono) chrono(a, now) else a.trailingText ?: ""
    }
}
