package com.faroquetech.aospisland

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class Glyph { PLAY, PAUSE, NEXT, PREV, BOLT, PHONE, HANGUP, BLUETOOTH, BELL, BELL_OFF, TIMER, LIVE, REC, HOTSPOT, TORCH }

/** Draws a [Glyph] into a rect using only Canvas/Path, so no resources are needed (works inside SystemUI). */
object GlyphPainter {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; strokeJoin = Paint.Join.ROUND }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    private val rect = RectF()

    fun draw(c: Canvas, g: Glyph, color: Int, left: Float, top: Float, size: Float) {
        c.save()
        c.translate(left, top)
        val s = size / 24f
        c.scale(s, s)
        fill.color = color; stroke.color = color; stroke.strokeWidth = 2f
        fill.pathEffect = null
        when (g) {
            Glyph.PLAY -> { path.reset(); path.moveTo(8f, 5f); path.lineTo(19f, 12f); path.lineTo(8f, 19f); path.close(); fill.style = Paint.Style.FILL_AND_STROKE; fill.strokeWidth = 2f; c.drawPath(path, fill); fill.style = Paint.Style.FILL }
            Glyph.PAUSE -> { rect.set(6f, 5f, 10f, 19f); c.drawRoundRect(rect, 1.4f, 1.4f, fill); rect.set(14f, 5f, 18f, 19f); c.drawRoundRect(rect, 1.4f, 1.4f, fill) }
            Glyph.NEXT -> { path.reset(); path.moveTo(5f, 5.5f); path.lineTo(15f, 12f); path.lineTo(5f, 18.5f); path.close(); c.drawPath(path, fill); rect.set(16.5f, 5.5f, 19.5f, 18.5f); c.drawRoundRect(rect, 1.4f, 1.4f, fill) }
            Glyph.PREV -> { path.reset(); path.moveTo(19f, 5.5f); path.lineTo(9f, 12f); path.lineTo(19f, 18.5f); path.close(); c.drawPath(path, fill); rect.set(4.5f, 5.5f, 7.5f, 18.5f); c.drawRoundRect(rect, 1.4f, 1.4f, fill) }
            Glyph.BOLT -> { path.reset(); path.moveTo(13.5f, 2f); path.lineTo(5f, 13.5f); path.lineTo(11f, 13.5f); path.lineTo(10f, 22f); path.lineTo(19f, 10f); path.lineTo(13f, 10f); path.close(); c.drawPath(path, fill) }
            Glyph.PHONE -> drawPhone(c)
            Glyph.HANGUP -> { c.save(); c.rotate(135f, 12f, 12f); drawPhone(c); c.restore() }
            Glyph.BLUETOOTH -> { stroke.strokeWidth = 1.9f; path.reset(); path.moveTo(7f, 7.5f); path.lineTo(17f, 16.5f); path.lineTo(12f, 21f); path.lineTo(12f, 3f); path.lineTo(17f, 7.5f); path.lineTo(7f, 16.5f); c.drawPath(path, stroke) }
            Glyph.BELL, Glyph.BELL_OFF -> {
                path.reset(); path.moveTo(5.5f, 17.5f); path.lineTo(7f, 16f); path.lineTo(7f, 11f)
                rect.set(7f, 4.5f, 17f, 17.5f); path.arcTo(rect, 180f, 180f); path.lineTo(17f, 16f); path.lineTo(18.5f, 17.5f); path.close()
                c.drawPath(path, fill); c.drawCircle(12f, 20.5f, 1.8f, fill)
                if (g == Glyph.BELL_OFF) {
                    stroke.color = Color.BLACK; stroke.strokeWidth = 4.6f; c.drawLine(4f, 3.5f, 20f, 20.5f, stroke)
                    stroke.color = color; stroke.strokeWidth = 2f; c.drawLine(4f, 3.5f, 20f, 20.5f, stroke)
                }
            }
            Glyph.TIMER -> { stroke.strokeWidth = 2f; c.drawCircle(12f, 13.5f, 8f, stroke); c.drawLine(12f, 13.5f, 12f, 8.8f, stroke); c.drawLine(9.5f, 2.8f, 14.5f, 2.8f, stroke) }
            Glyph.LIVE -> { stroke.strokeWidth = 2f; c.drawCircle(12f, 12f, 8f, stroke); c.drawCircle(12f, 12f, 3.4f, fill) }
            Glyph.TORCH -> {
                path.reset(); path.moveTo(7.5f, 2f); path.lineTo(16.5f, 2f); path.lineTo(15f, 7.5f); path.lineTo(9f, 7.5f); path.close(); c.drawPath(path, fill)
                rect.set(9f, 9f, 15f, 22f); c.drawRoundRect(rect, 1.8f, 1.8f, fill)
                fill.color = Color.BLACK; rect.set(11f, 12.5f, 13f, 16f); c.drawRoundRect(rect, 1f, 1f, fill); fill.color = color
            }
            Glyph.REC -> { stroke.strokeWidth = 2f; c.drawCircle(12f, 12f, 9f, stroke); c.drawCircle(12f, 12f, 5.2f, fill) }
            Glyph.HOTSPOT -> { stroke.strokeWidth = 2f; c.drawCircle(12f, 12f, 2.2f, fill); c.drawArc(4f, 4f, 20f, 20f, 220f, 100f, false, stroke); c.drawArc(4f, 4f, 20f, 20f, 40f, 100f, false, stroke); c.drawArc(0.5f, 0.5f, 23.5f, 23.5f, 220f, 100f, false, stroke); c.drawArc(0.5f, 0.5f, 23.5f, 23.5f, 40f, 100f, false, stroke) }
        }
        c.restore()
    }

    private fun drawPhone(c: Canvas) {
        path.reset()
        path.moveTo(6.6f, 10.8f)
        path.cubicTo(8.0f, 13.6f, 10.4f, 16.0f, 13.2f, 17.4f)
        path.lineTo(15.4f, 15.2f)
        path.cubicTo(15.7f, 14.9f, 16.1f, 14.8f, 16.4f, 14.9f)
        path.cubicTo(17.6f, 15.3f, 18.8f, 15.5f, 20.0f, 15.5f)
        path.cubicTo(20.6f, 15.5f, 21.0f, 16.0f, 21.0f, 16.5f)
        path.lineTo(21.0f, 20.0f)
        path.cubicTo(21.0f, 20.6f, 20.6f, 21.0f, 20.0f, 21.0f)
        path.cubicTo(10.6f, 21.0f, 3.0f, 13.4f, 3.0f, 4.0f)
        path.cubicTo(3.0f, 3.4f, 3.4f, 3.0f, 4.0f, 3.0f)
        path.lineTo(7.5f, 3.0f)
        path.cubicTo(8.0f, 3.0f, 8.5f, 3.4f, 8.5f, 4.0f)
        path.cubicTo(8.5f, 5.2f, 8.7f, 6.4f, 9.1f, 7.6f)
        path.cubicTo(9.2f, 7.9f, 9.1f, 8.3f, 8.8f, 8.6f)
        path.close()
        c.drawPath(path, fill)
    }
}

/** A square view drawing one glyph. */
class GlyphView(context: Context, var glyph: Glyph = Glyph.LIVE, var color: Int = Color.WHITE, private val pad: Float = 0f) : View(context) {
    fun set(g: Glyph, col: Int) { if (g != glyph || col != color) { glyph = g; color = col; invalidate() } }
    override fun onDraw(canvas: Canvas) {
        val aw = width - paddingLeft - paddingRight; val ah = height - paddingTop - paddingBottom
        val s = min(aw, ah) - pad * 2
        GlyphPainter.draw(canvas, glyph, color, paddingLeft + (aw - s) / 2f, paddingTop + (ah - s) / 2f, s)
    }
}

/** Leading compact content: either a glyph, a rounded bitmap (album art) or a drawable (app icon). */
class LeadView(context: Context) : View(context) {
    private var glyph: Glyph? = null
    private var color = Color.WHITE
    private var bitmap: Bitmap? = null
    private var drawable: Drawable? = null
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clip = Path()
    private val rect = RectF()
    private val src = Rect()

    fun setGlyph(g: Glyph, col: Int) { glyph = g; color = col; bitmap = null; drawable = null; invalidate() }
    fun setBitmap(b: Bitmap) { bitmap = b; glyph = null; drawable = null; invalidate() }
    fun setDrawable(d: Drawable) { drawable = d.constantState?.newDrawable()?.mutate() ?: d; glyph = null; bitmap = null; invalidate() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val s = min(w, h)
        val l = (w - s) / 2f; val t = (h - s) / 2f
        val b = bitmap; val d = drawable; val g = glyph
        when {
            b != null && !b.isRecycled -> {
                rect.set(l, t, l + s, t + s); clip.reset(); clip.addRoundRect(rect, s * 0.28f, s * 0.28f, Path.Direction.CW)
                c.save(); c.clipPath(clip)
                // center-crop
                val side = min(b.width, b.height)
                src.set((b.width - side) / 2, (b.height - side) / 2, (b.width + side) / 2, (b.height + side) / 2)
                c.drawBitmap(b, src, rect, bmpPaint); c.restore()
            }
            d != null -> {
                rect.set(l, t, l + s, t + s); clip.reset(); clip.addRoundRect(rect, s * 0.28f, s * 0.28f, Path.Direction.CW)
                c.save(); c.clipPath(clip); d.setBounds(l.toInt(), t.toInt(), (l + s).toInt(), (t + s).toInt()); d.draw(c); c.restore()
            }
            g != null -> GlyphPainter.draw(c, g, color, l + s * 0.08f, t + s * 0.08f, s * 0.84f)
        }
    }
}

/** Trailing compact content: waveform bars, text, or progress ring. */
class TrailView(context: Context) : View(context) {
    enum class Mode { NONE, WAVE, TEXT, RING }
    var mode = Mode.NONE; private set
    var text: String = ""; private set
    private var color = Color.WHITE
    private var playing = false
    private var progress = 0f
    private var indeterminate = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); textSize = 13f * resources.displayMetrics.scaledDensity; textAlign = Paint.Align.RIGHT }
    private val phase = floatArrayOf(0f, 1.3f, 2.6f, 0.7f, 1.9f)
    private val speeds = floatArrayOf(7.1f, 9.3f, 6.2f, 8.4f, 7.7f)

    fun textWidth(t: String): Float = textPaint.measureText(t)
    /** [t] shortened with an ellipsis only when it is wider than [maxPx]. */
    fun fit(t: String, maxPx: Float): String =
        if (textPaint.measureText(t) <= maxPx) t else android.text.TextUtils.ellipsize(t, android.text.TextPaint(textPaint), maxPx, android.text.TextUtils.TruncateAt.END).toString()
    fun setNone() { mode = Mode.NONE; invalidate() }
    fun setWave(col: Int, isPlaying: Boolean) { mode = Mode.WAVE; color = col; playing = isPlaying; invalidate() }
    fun setText(t: String, col: Int) { mode = Mode.TEXT; text = t; color = col; invalidate() }
    fun setRing(pr: Float, col: Int, isIndeterminate: Boolean = false) { mode = Mode.RING; progress = pr.coerceIn(0f, 1f); color = col; indeterminate = isIndeterminate; invalidate() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        when (mode) {
            Mode.NONE -> {}
            Mode.TEXT -> { textPaint.color = color; val fm = textPaint.fontMetrics; c.drawText(text, w, h / 2f - (fm.ascent + fm.descent) / 2f, textPaint) }
            Mode.RING -> {
                val s = min(w, h) * 0.78f; val cx = w - s / 2f - (w - s) / 2f - 0f; val cy = h / 2f
                p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.16f; p.strokeCap = Paint.Cap.ROUND
                rect.set(w / 2f - s / 2f, cy - s / 2f, w / 2f + s / 2f, cy + s / 2f)
                p.color = (color and 0x00FFFFFF) or 0x40000000; c.drawArc(rect, 0f, 360f, false, p)
                p.color = color
                if (indeterminate) {
                    val start = (SystemClock.uptimeMillis() / 5L % 360L).toFloat() - 90f
                    c.drawArc(rect, start, 105f, false, p)
                    if (isShown) postInvalidateOnAnimation()
                } else c.drawArc(rect, -90f, 360f * progress, false, p)
            }
            Mode.WAVE -> {
                val n = 5; val bw = w / (n * 2f - 1f); val t = SystemClock.uptimeMillis() / 1000f
                p.style = Paint.Style.FILL; p.color = color
                for (i in 0 until n) {
                    val amp = if (playing) 0.28f + 0.72f * (0.5f + 0.5f * sin(t * speeds[i] + phase[i])) else 0.18f
                    val bh = max(bw, h * amp); val x = i * bw * 2f
                    rect.set(x, h / 2f - bh / 2f, x + bw, h / 2f + bh / 2f); c.drawRoundRect(rect, bw / 2f, bw / 2f, p)
                }
                if (playing && isShown) postInvalidateOnAnimation()
            }
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if ((mode == Mode.WAVE && playing) || (mode == Mode.RING && indeterminate)) postInvalidateOnAnimation() }
}

/** Thin rounded progress/seek bar. */
class SeekBarView(context: Context) : View(context) {
    var fraction = 0f; set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var color = Color.WHITE; set(v) { field = v; invalidate() }
    var onSeek: ((Float) -> Unit)? = null
    var dragging = false; private set
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val d = resources.displayMetrics.density

    override fun onDraw(c: Canvas) {
        val th = (if (dragging) 8f else 5f) * d; val cy = height / 2f
        rect.set(0f, cy - th / 2f, width.toFloat(), cy + th / 2f)
        p.color = 0x33FFFFFF; c.drawRoundRect(rect, th / 2f, th / 2f, p)
        rect.right = max(th, width * fraction); p.color = color; c.drawRoundRect(rect, th / 2f, th / 2f, p)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (onSeek == null) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dragging = true; parent?.requestDisallowInterceptTouchEvent(true); fraction = e.x / width; invalidate() }
            MotionEvent.ACTION_MOVE -> fraction = e.x / width
            MotionEvent.ACTION_UP -> { dragging = false; fraction = e.x / width; onSeek?.invoke(fraction) }
            MotionEvent.ACTION_CANCEL -> { dragging = false; invalidate() }
        }
        return true
    }
}

/** Rounded pill button with optional glyph + label (call buttons, notification actions). */
class PillButton(context: Context, private val glyph: Glyph?, private val label: String, private val bg: Int, private val fg: Int = Color.WHITE) : View(context) {
    private val d = resources.displayMetrics.density
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg }
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fg; textSize = 14f * resources.displayMetrics.scaledDensity; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val rect = RectF()
    init { isClickable = true }

    override fun onMeasure(w: Int, h: Int) {
        val desired = (tp.measureText(label) + (if (glyph != null) 22 * d else 0f) + 36 * d).toInt()
        setMeasuredDimension(resolveSize(desired, w), (44 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val a = if (isPressed) 0.7f else 1f; bgPaint.alpha = (255 * a).toInt()
        rect.set(0f, 0f, width.toFloat(), height.toFloat()); c.drawRoundRect(rect, height / 2f, height / 2f, bgPaint)
        val gw = if (glyph != null) 20 * d else 0f; val gap = if (glyph != null && label.isNotEmpty()) 6 * d else 0f
        val total = gw + gap + tp.measureText(label); var x = (width - total) / 2f
        if (glyph != null) { GlyphPainter.draw(c, glyph, fg, x, (height - gw) / 2f, gw); x += gw + gap }
        val fm = tp.fontMetrics; c.drawText(label, x, height / 2f - (fm.ascent + fm.descent) / 2f, tp)
    }

    override fun setPressed(pressed: Boolean) { super.setPressed(pressed); invalidate() }
}

/** Dominant "vibrant" colour of artwork, used to tint waveform/progress like iOS. */
object ArtColor {
    /** Dominant vivid color of an app icon, or 0 when the icon is neutral (white / grey / black). */
    fun fromDrawable(d: Drawable?): Int {
        if (d == null) return 0
        return try {
            val dd = d.constantState?.newDrawable()?.mutate() ?: d
            val n = 16
            val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
            dd.setBounds(0, 0, n, n); dd.draw(Canvas(bmp))
            var r = 0f; var g = 0f; var b = 0f; var w = 0f
            val hsv = FloatArray(3)
            for (y in 0 until n) for (x in 0 until n) {
                val px = bmp.getPixel(x, y)
                if (Color.alpha(px) < 128) continue
                Color.colorToHSV(px, hsv)
                val wt = hsv[1] * hsv[1] * hsv[2]
                r += Color.red(px) * wt; g += Color.green(px) * wt; b += Color.blue(px) * wt; w += wt
            }
            bmp.recycle()
            if (w < 3f) 0 else Color.rgb((r / w).toInt(), (g / w).toInt(), (b / w).toInt())
        } catch (ignored: Throwable) { 0 }
    }

    private var lastBmp: Bitmap? = null
    private var lastColor = 0xFFFFFFFF.toInt()
    fun extract(bmp: Bitmap?, fallback: Int = 0xFFFFFFFF.toInt()): Int {
        if (bmp == null || bmp.isRecycled) return fallback
        if (bmp === lastBmp) return lastColor
        return try {
            val s = Bitmap.createScaledBitmap(bmp, 8, 8, true)
            var r = 0f; var g = 0f; var b = 0f; var wsum = 0f
            val hsv = FloatArray(3)
            for (y in 0 until 8) for (x in 0 until 8) {
                val px = s.getPixel(x, y); Color.colorToHSV(px, hsv)
                val wgt = 0.15f + hsv[1] * hsv[2]
                r += Color.red(px) * wgt; g += Color.green(px) * wgt; b += Color.blue(px) * wgt; wsum += wgt
            }
            if (s !== bmp) s.recycle()
            Color.colorToHSV(Color.rgb((r / wsum).toInt(), (g / wsum).toInt(), (b / wsum).toInt()), hsv)
            hsv[1] = max(hsv[1], 0.45f).coerceAtMost(0.95f); hsv[2] = max(hsv[2], 0.9f)
            val col = Color.HSVToColor(hsv)
            lastBmp = bmp; lastColor = col; col
        } catch (ignored: Throwable) { fallback }
    }
}

object Fmt {
    fun time(ms: Long): String {
        val s = (abs(ms) / 1000).toInt(); val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }
}
