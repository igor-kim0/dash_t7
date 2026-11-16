package com.dash.headunit.screensaver

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import com.dash.headunit.music.MusicHub
import com.dash.headunit.music.SpectrumHub
import com.dash.headunit.radio.RadioHub
import com.dash.headunit.ui.DashVisibility
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Фон «Цветомузыка» — как светомузыкальная приставка 80-х: три лампы
 * за матовым стеклом (низкие, средние, высокие частоты) вспыхивают
 * в такт музыке, на сильной доле баса гамма сдвигается и экран коротко
 * подсвечивается. По центру — часы и дата, подкрашенные текущим светом.
 *
 * Уровни — [SpectrumHub] (Visualizer на общем выходе; если звук идёт мимо
 * микшера Android, как BT на ГУ, — имитация с ритмом, пока плеер играет).
 * Играет ли что-то — [MusicHub] и [RadioHub]. Под этой заставкой их опросы
 * не засыпают ([DashVisibility.setMusicSaver]). Тишина — лампы медленно
 * «дышат» еле-еле, гамма плывёт.
 *
 * Лампа — заранее посчитанное мягкое пятно (ALPHA_8), рисуется цветом
 * кисти со сложением света: без шейдеров и выделений памяти на кадр.
 */
class ColorMusicView(context: Context) : SaverView(context, 30) {

    private val glow: Bitmap = makeGlow()
    private val lampPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val dst = RectF()

    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }

    /** Сглаженная яркость ламп: низкие, средние, высокие. */
    private val lamp = FloatArray(3)
    /** Средний уровень баса за ~2 с — фон для поиска сильной доли. */
    private var bassAvg = 0f
    private var sinceBeat = 10f
    /** Вспышка на сильной доле, 1 → 0. */
    private var flash = 0f
    /** Сдвиг гаммы, градусы: плывёт сам и прыгает на сильной доле. */
    private var hue = 0f
    private var hueTarget = 0f
    private val hsv = FloatArray(3)

    private val noop: () -> Unit = {}
    private val playingListener: () -> Unit = { updatePlaying() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        DashVisibility.setMusicSaver(true)
        MusicHub.init(context)
        MusicHub.addListener(playingListener)
        MusicHub.ensurePolling()
        RadioHub.init(context)
        RadioHub.addListener(playingListener)
        SpectrumHub.addListener(context, noop)
        updatePlaying()
    }

    override fun onDetachedFromWindow() {
        SpectrumHub.clearPlaying(KEY)
        SpectrumHub.removeListener(noop)
        RadioHub.removeListener(playingListener)
        MusicHub.removeListener(playingListener)
        DashVisibility.setMusicSaver(false)
        super.onDetachedFromWindow()
    }

    /** Для имитации спектра: играет ли плеер или штатное радио. */
    private fun updatePlaying() {
        val playing = MusicHub.data?.playing == true || RadioHub.state.playing == true
        SpectrumHub.setPlaying(KEY, playing)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        timePaint.textSize = h * 0.24f
        datePaint.textSize = h * 0.055f
    }

    override fun draw(canvas: Canvas, t: Float, dt: Float) {
        step(t, dt)
        val w = width.toFloat()
        val h = height.toFloat()

        // Вспышка доли — общий отсвет текущим цветом баса.
        val base = color(0, 1f)
        val fl = (flash * 40).toInt()
        canvas.drawColor(Color.rgb(Color.red(base) * fl / 255, Color.green(base) * fl / 255, Color.blue(base) * fl / 255))

        // Три лампы по ширине: низкие в центре (самая большая), средние и
        // высокие по бокам; центры медленно плавают — картинка не застывает.
        val r = h * 0.95f
        drawLamp(canvas, w * 0.5f + w * 0.04f * sin(t * 0.11f), h * 0.62f, r * (1.05f + 0.25f * lamp[0]), 0)
        drawLamp(canvas, w * 0.17f + w * 0.05f * sin(t * 0.07f + 1f), h * 0.42f + h * 0.08f * sin(t * 0.09f), r * (0.8f + 0.2f * lamp[1]), 1)
        drawLamp(canvas, w * 0.83f + w * 0.05f * sin(t * 0.08f + 2f), h * 0.45f + h * 0.08f * sin(t * 0.1f + 1f), r * (0.75f + 0.2f * lamp[2]), 2)

        // Часы — чуть подкрашены светом баса, ореол — им же.
        val tint = blend(Color.WHITE, base, 0.25f + 0.25f * lamp[0])
        val halo = Color.argb((90 + 120 * max(lamp[0], flash)).toInt().coerceAtMost(255), Color.red(base), Color.green(base), Color.blue(base))
        timePaint.color = tint
        datePaint.color = blend(Color.WHITE, base, 0.15f)
        datePaint.alpha = 200
        timePaint.setShadowLayer(h * 0.03f, 0f, 0f, halo)
        // Медленный дрейф по экрану от выгорания.
        val cx = w / 2f + w * 0.03f * sin(t * 0.013f)
        val cy = h * 0.5f + h * 0.03f * sin(t * 0.017f + 1f)
        val timeBase = cy - (timePaint.ascent() + timePaint.descent()) / 2f - h * 0.04f
        canvas.drawText(timeText(), cx, timeBase, timePaint)
        canvas.drawText(dateText(), cx, timeBase + timePaint.descent() - datePaint.ascent() + h * 0.01f, datePaint)
    }

    private fun drawLamp(c: Canvas, x: Float, y: Float, radius: Float, i: Int) {
        // Тишина — еле тлеет, громко — почти полная яркость.
        val level = 0.10f + 0.90f * lamp[i]
        lampPaint.color = color(i, 1f)
        lampPaint.alpha = (255 * level.coerceIn(0f, 1f)).toInt()
        dst.set(x - radius, y - radius, x + radius, y + radius)
        c.drawBitmap(glow, null, dst, lampPaint)
    }

    /** Один шаг: уровни ламп, поиск сильной доли, гамма. */
    private fun step(t: Float, dt: Float) {
        val lv = SpectrumHub.levels
        val bass = band(lv, 0, 6)
        val mid = band(lv, 6, 18)
        val high = band(lv, 18, lv.size)
        // Быстрый подъём, плавный спад — как нить лампы.
        follow(0, bass, dt)
        follow(1, mid, dt)
        follow(2, high, dt)

        bassAvg += (bass - bassAvg) * (dt / 2f).coerceAtMost(1f)
        sinceBeat += dt
        if (bass > 0.22f && bass > bassAvg * 1.3f && sinceBeat > 0.28f) {
            sinceBeat = 0f
            flash = 1f
            hueTarget += 35f + 40f * bass
        }
        flash = max(0f, flash - dt * 3.5f)
        // Сама по себе гамма плывёт по кругу за ~2 мин; к цели — плавно.
        hueTarget += dt * 3f
        hue += (hueTarget - hue) * (dt * 4f).coerceAtMost(1f)
        if (hue > 3600f) {
            hue -= 3600f
            hueTarget -= 3600f
        }
        // Тишина: еле заметное «дыхание», чтобы экран не застыл.
        if (bass + mid + high < 0.02f) {
            val breath = 0.06f * (0.5f + 0.5f * sin(t * 0.6f))
            for (i in 0..2) lamp[i] = max(lamp[i], breath)
        }
    }

    private fun follow(i: Int, target: Float, dt: Float) {
        val v = target.pow(0.8f)
        val k = if (v > lamp[i]) (dt * 18f) else (dt * 3f)
        lamp[i] += (v - lamp[i]) * k.coerceAtMost(1f)
    }

    private fun band(lv: FloatArray, from: Int, to: Int): Float {
        var s = 0f
        for (i in from until to) s += lv[i]
        return s / (to - from)
    }

    /** Цвет лампы [i]: красная, зелёная, синяя по кругу, сдвинутые на [hue]. */
    private fun color(i: Int, value: Float): Int {
        hsv[0] = ((BASE_HUES[i] + hue) % 360f + 360f) % 360f
        hsv[1] = 0.85f
        hsv[2] = value
        return Color.HSVToColor(hsv)
    }

    private fun blend(a: Int, b: Int, k: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt()
    )

    companion object {
        private const val KEY = "screensaver_color_music"
        private val BASE_HUES = floatArrayOf(0f, 120f, 230f)
        private const val GLOW_SIZE = 256

        /** Мягкое пятно: гаусс по радиусу, к краю — ноль. */
        private fun makeGlow(): Bitmap {
            val n = GLOW_SIZE
            val px = IntArray(n * n)
            val c = (n - 1) / 2f
            for (y in 0 until n) for (x in 0 until n) {
                val dx = (x - c) / c
                val dy = (y - c) / c
                val d = sqrt(dx * dx + dy * dy)
                val a = if (d >= 1f) 0f else exp(-d * d * 3.2f) * (1f - d * d)
                px[y * n + x] = ((a * 255f).toInt() shl 24)
            }
            val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ALPHA_8)
            bmp.setPixels(px, 0, n, 0, 0, n, n)
            return bmp
        }
    }
}
