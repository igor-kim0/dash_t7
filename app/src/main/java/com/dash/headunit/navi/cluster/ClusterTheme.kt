package com.dash.headunit.navi.cluster

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint

/**
 * Своя тема приборки — фон и шкалы под штатными слоями «Карт» (дисплей 1,
 * 1920×720). Фото ГУ 2026-11-21 (ClusterViewTest): наше окно видно на весь
 * дисплей, приборка поверх рисует только верхнюю строку (y ≈ 0–90), цифры
 * скорости слева (полоса A, y ≈ 290–430), оборотов справа (x ≈ 1500–1800),
 * нижнюю строку (y ≈ 620–720) и лампы. Убрать их нельзя — тема обходит
 * эти места: шкалы-дуги под цифрами (y ≈ 450–560), по бокам от карточки.
 *
 * Статичная часть (фон, риски, подписи) рисуется один раз в [Bitmap] на
 * размер окна; каждый кадр — только картинка и заполнение шкал.
 */
abstract class ClusterTheme {

    /** Цвета плашки маршрута в этой теме. */
    abstract val cardFill: Int
    abstract val cardStroke: Int

    private var cache: Bitmap? = null

    /** Фон и шкалы. [speed] км/ч, [rpm] об/мин — сглаженные; null — нет данных. */
    fun draw(canvas: Canvas, w: Int, h: Int, speed: Float?, rpm: Float?) {
        val bmp = cache?.takeIf { it.width == w && it.height == h } ?: run {
            cache?.recycle()
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                drawStatic(Canvas(it), w.toFloat(), h.toFloat(), h / 720f)
                cache = it
            }
        }
        canvas.drawBitmap(bmp, 0f, 0f, null)
        val s = h / 720f
        val pair = bars?.takeIf { barsW == w && barsH == h }
            ?: (leftBar(w.toFloat(), s) to rightBar(w.toFloat(), s)).also { bars = it; barsW = w; barsH = h }
        drawValue(canvas, pair.first, speed?.let { it / SPEED_MAX }, false, s)
        drawValue(canvas, pair.second, rpm?.let { it / RPM_MAX }, true, s)
    }

    private var bars: Pair<Bar, Bar>? = null
    private var barsW = 0
    private var barsH = 0

    fun release() {
        cache?.recycle()
        cache = null
    }

    protected abstract fun drawStatic(c: Canvas, w: Float, h: Float, s: Float)

    /** Заполнение шкалы долей [frac] 0..1 (null — шкала пустая); [rpm] — правая. */
    protected abstract fun drawValue(c: Canvas, bar: Bar, frac: Float?, rpm: Boolean, s: Float)

    /**
     * Шкала: дуга из [x0] в [x1] на высоте [y], прогнутая вверх на [bulge]
     * (у края экрана — ниже, к карточке — выше, как продолжение дуг приборки).
     */
    class Bar(val x0: Float, val x1: Float, val y: Float, val bulge: Float) {
        val path = Path().apply {
            for (i in 0..STEPS) {
                val t = i / STEPS.toFloat()
                val x = x0 + (x1 - x0) * t
                val yy = y - bulge * Math.sin(Math.PI * t * 0.5).toFloat()
                if (i == 0) moveTo(x, yy) else lineTo(x, yy)
            }
        }
        private val measure = PathMeasure(path, false)
        val length = measure.length

        /** Кусок дуги от доли [from] до [to]. */
        fun segment(from: Float, to: Float, out: Path) {
            out.rewind()
            measure.getSegment(length * from.coerceIn(0f, 1f), length * to.coerceIn(0f, 1f), out, true)
        }

        /** Точка и нормаль (вверх) на доле [t]: [x, y, nx, ny]. */
        fun point(t: Float, out: FloatArray) {
            measure.getPosTan(length * t.coerceIn(0f, 1f), pos, tan)
            out[0] = pos[0]; out[1] = pos[1]
            // Нормаль, смотрящая вверх (к цифрам).
            var nx = tan[1]; var ny = -tan[0]
            if (ny > 0) { nx = -nx; ny = -ny }
            out[2] = nx; out[3] = ny
        }

        private val pos = FloatArray(2)
        private val tan = FloatArray(2)

        private companion object { const val STEPS = 48 }
    }

    protected fun leftBar(w: Float, s: Float) = Bar(36f * s, 380f * s, 540f * s, 70f * s)

    /** Правая — зеркально: от края экрана к карточке. */
    protected fun rightBar(w: Float, s: Float) = Bar(w - 36f * s, w - 380f * s, 540f * s, 70f * s)

    protected val seg = Path()
    protected val pt = FloatArray(4)

    /** Риски и подписи по шкале: каждые [step] единиц от 0 до [max]. */
    protected fun ticks(
        c: Canvas, bar: Bar, max: Int, step: Int, labelEvery: Int, s: Float,
        tick: Paint, label: TextPaint?, len: Float, label1000: Boolean = false
    ) {
        var v = 0
        while (v <= max) {
            bar.point(v / max.toFloat(), pt)
            val major = v % labelEvery == 0
            val l = if (major) len else len * 0.55f
            c.drawLine(pt[0], pt[1], pt[0] + pt[2] * l, pt[1] + pt[3] * l, tick)
            if (major && label != null) {
                val text = if (label1000) (v / 1000).toString() else v.toString()
                // Подписи — под шкалой, чтобы не лезть к штатным цифрам.
                val off = 30f * s
                c.drawText(text, pt[0] - pt[2] * off, pt[1] - pt[3] * off + label.textSize * 0.35f, label)
            }
            v += step
        }
    }

    companion object {
        const val OFF = 0
        const val NEON = 1
        const val CLASSIC = 2
        const val MINIMAL = 3

        const val SPEED_MAX = 200f
        const val RPM_MAX = 7000f
        const val RPM_RED = 6000f

        fun create(id: Int): ClusterTheme? = when (id) {
            NEON -> Neon()
            CLASSIC -> Classic()
            MINIMAL -> Minimal()
            else -> null
        }
    }
}

/** Синтвейв: тёмно-фиолетовое небо, сетка «пола» к горизонту, светящиеся дуги. */
private class Neon : ClusterTheme() {
    override val cardFill = Color.argb(235, 0x14, 0x0C, 0x2A)
    override val cardStroke = Color.rgb(0xB0, 0x30, 0xE0)

    private val cyan = Color.rgb(0x00, 0xE5, 0xFF)
    private val magenta = Color.rgb(0xFF, 0x2E, 0xC8)

    override fun drawStatic(c: Canvas, w: Float, h: Float, s: Float) {
        val horizon = 430f * s
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(Color.rgb(0x05, 0x02, 0x10), Color.rgb(0x1A, 0x06, 0x36), Color.rgb(0x2A, 0x05, 0x3A), Color.rgb(0x06, 0x02, 0x12)),
            floatArrayOf(0f, horizon / h * 0.85f, horizon / h, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, w, h, p)
        // Свечение горизонта.
        p.shader = RadialGradient(w / 2, horizon, w * 0.55f, Color.argb(110, 0xFF, 0x2E, 0xC8), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, horizon - 220f * s, w, horizon + 220f * s, p)
        p.shader = null
        // Сетка пола: лучи из точки схода и поперечные линии, сгущающиеся к горизонту.
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f * s
        p.color = Color.argb(150, 0xFF, 0x2E, 0xC8)
        val vx = w / 2
        for (i in -14..14) c.drawLine(vx + i * 26f * s, horizon, vx + i * 230f * s, h, p)
        var k = 1f
        while (true) {
            val y = horizon + (h - horizon) * (k * k) / 64f
            if (y > h) break
            p.alpha = (60 + 120 * (y - horizon) / (h - horizon)).toInt()
            c.drawLine(0f, y, w, y, p)
            k += 1f
        }
        // Линия горизонта.
        p.color = magenta
        p.alpha = 200
        p.strokeWidth = 3f * s
        c.drawLine(0f, horizon, w, horizon, p)
        // Тёмные подложки под штатными цифрами — чтобы читались.
        p.style = Paint.Style.FILL
        for (cx in floatArrayOf(160f * s, w - 260f * s)) {
            p.shader = RadialGradient(cx, 360f * s, 230f * s, Color.argb(200, 0x05, 0x02, 0x10), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawCircle(cx, 360f * s, 230f * s, p)
        }
        p.shader = null
        // Пустые дорожки шкал и риски.
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 16f * s
            color = Color.argb(120, 0x30, 0x10, 0x50)
        }
        val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f * s; color = Color.argb(170, 0x9F, 0xE8, 0xFF) }
        val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 20f * s
            color = Color.argb(200, 0x9F, 0xE8, 0xFF)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val l = leftBar(w, s)
        val r = rightBar(w, s)
        c.drawPath(l.path, track)
        c.drawPath(r.path, track)
        ticks(c, l, 200, 10, 40, s, tick, label, 18f * s)
        ticks(c, r, 7000, 500, 1000, s, tick, label, 18f * s, label1000 = true)
    }

    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    override fun drawValue(c: Canvas, bar: Bar, frac: Float?, rpm: Boolean, s: Float) {
        if (frac == null || frac <= 0.002f) return
        bar.segment(0f, frac, seg)
        val red = rpm && frac * RPM_MAX >= RPM_RED
        val col = if (red) Color.rgb(0xFF, 0x30, 0x40) else if (rpm) magenta else cyan
        // Свечение без BlurMaskFilter (не работает с аппаратным ускорением): широкие полупрозрачные штрихи.
        glow.shader = null
        glow.color = col
        for ((wd, a) in arrayOf(40f to 30, 28f to 60, 16f to 255)) {
            glow.strokeWidth = wd * s
            glow.alpha = a
            c.drawPath(seg, glow)
        }
        glow.color = Color.WHITE
        glow.alpha = 220
        glow.strokeWidth = 5f * s
        c.drawPath(seg, glow)
    }
}

/** Классика: графит с виньеткой, хромовая шкала с рисками и оранжевый указатель. */
private class Classic : ClusterTheme() {
    override val cardFill = Color.rgb(0x1A, 0x1C, 0x1F)
    override val cardStroke = Color.rgb(0x55, 0x5A, 0x62)

    private val orange = Color.rgb(0xFF, 0x7A, 0x1A)

    override fun drawStatic(c: Canvas, w: Float, h: Float, s: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h, Color.rgb(0x22, 0x24, 0x28), Color.rgb(0x0A, 0x0B, 0x0D), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        // Мелкая «карбоновая» диагональная штриховка.
        p.shader = null
        p.strokeWidth = 1f
        p.color = Color.argb(14, 255, 255, 255)
        var x = -h
        while (x < w) {
            c.drawLine(x, h, x + h, 0f, p)
            x += 6f * s
        }
        // Виньетка по краям.
        p.shader = RadialGradient(w / 2, h / 2, w * 0.62f, Color.TRANSPARENT, Color.argb(200, 0, 0, 0), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null
        // Хромовая окантовка-«бровь» над шкалами.
        val chrome = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f * s
            strokeCap = Paint.Cap.ROUND
        }
        val l = leftBar(w, s)
        val r = rightBar(w, s)
        for (b in arrayOf(l, r)) {
            chrome.shader = LinearGradient(b.x0, 0f, b.x1, 0f, Color.rgb(0x60, 0x64, 0x6A), Color.rgb(0xE0, 0xE4, 0xEA), Shader.TileMode.MIRROR)
            c.drawPath(b.path, chrome)
        }
        // Красная зона оборотов.
        val redZone = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 10f * s
            color = Color.rgb(0xC6, 0x28, 0x28)
        }
        r.segment(RPM_RED / RPM_MAX, 1f, seg)
        c.drawPath(seg, redZone)
        val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 3f * s; color = Color.rgb(0xE6, 0xE6, 0xE6) }
        val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 22f * s
            color = Color.rgb(0xDD, 0xDD, 0xDD)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }
        ticks(c, l, 200, 10, 40, s, tick, label, 22f * s)
        ticks(c, r, 7000, 500, 1000, s, tick, label, 22f * s, label1000 = true)
    }

    private val needle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = orange }
    private val trail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(140, 0xFF, 0x7A, 0x1A)
    }
    private val tri = Path()

    override fun drawValue(c: Canvas, bar: Bar, frac: Float?, rpm: Boolean, s: Float) {
        if (frac == null) return
        if (frac > 0.002f) {
            bar.segment(0f, frac, seg)
            trail.strokeWidth = 6f * s
            c.drawPath(seg, trail)
        }
        // Указатель — треугольник поперёк шкалы, остриём к рискам.
        bar.point(frac, pt)
        val x = pt[0]; val y = pt[1]; val nx = pt[2]; val ny = pt[3]
        val tx = -ny; val ty = nx
        val len = 44f * s
        val half = 9f * s
        tri.rewind()
        tri.moveTo(x - nx * 8f * s, y - ny * 8f * s)
        tri.lineTo(x + nx * len + tx * half, y + ny * len + ty * half)
        tri.lineTo(x + nx * len - tx * half, y + ny * len - ty * half)
        tri.close()
        needle.color = if (rpm && frac * RPM_MAX >= RPM_RED) Color.rgb(0xFF, 0x30, 0x30) else orange
        c.drawPath(tri, needle)
    }
}

/** Минимализм: чёрный фон, тонкая серая дорожка, белое заполнение. */
private class Minimal : ClusterTheme() {
    override val cardFill = Color.BLACK
    override val cardStroke = Color.rgb(0x30, 0x30, 0x30)

    override fun drawStatic(c: Canvas, w: Float, h: Float, s: Float) {
        c.drawColor(Color.BLACK)
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 4f * s
            color = Color.rgb(0x2C, 0x2C, 0x2C)
        }
        val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f * s; color = Color.rgb(0x55, 0x55, 0x55) }
        val l = leftBar(w, s)
        val r = rightBar(w, s)
        c.drawPath(l.path, track)
        c.drawPath(r.path, track)
        ticks(c, l, 200, 50, 50, s, tick, null, 12f * s)
        ticks(c, r, 7000, 1000, 1000, s, tick, null, 12f * s)
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    override fun drawValue(c: Canvas, bar: Bar, frac: Float?, rpm: Boolean, s: Float) {
        if (frac == null || frac <= 0.002f) return
        bar.segment(0f, frac, seg)
        fill.strokeWidth = 6f * s
        fill.color = if (rpm && frac * RPM_MAX >= RPM_RED) Color.rgb(0xE5, 0x39, 0x35) else Color.WHITE
        c.drawPath(seg, fill)
    }
}
