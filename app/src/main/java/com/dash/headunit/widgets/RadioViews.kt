package com.dash.headunit.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import com.dash.headunit.radio.RadioHub
import com.dash.headunit.radio.RadioStations
import com.dash.headunit.theme.DashTheme
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * Значок станции: цветной круг с буквами названия
 * ([RadioStations.initials]); без названия — частота.
 */
class StationAvatarView(context: Context) : View(context) {

    private val circle = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private var label = ""
    private var small = false

    /** Кольцо вокруг значка (станция играет); null — без кольца. */
    var ringColor: Int? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Показать станцию: цвет, буквы или частоту. Нет станции — серый круг. */
    fun bind(band: String, freq: Double?, title: String?, color: Int?) {
        val letters = RadioStations.initials(title)
        small = letters.isEmpty()
        label = letters.ifEmpty { freq?.let { RadioStations.freqText(band, it) } ?: band.uppercase() }
        circle.color = when {
            freq == null -> Color.GRAY
            else -> RadioStations.colorOf(band, freq, color)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        if (size <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val ringW = if (ringColor != null) size * 0.06f else 0f
        val r = size / 2f - ringW * 1.6f
        // Лёгкий градиент объёма — вторым кругом темнее снизу.
        canvas.drawCircle(cx, cy, r, circle)
        ringColor?.let {
            ring.color = it
            ring.strokeWidth = ringW
            canvas.drawCircle(cx, cy, size / 2f - ringW / 2f, ring)
        }
        val maxW = r * 1.6f
        text.textSize = if (small) r * 0.62f else r * (if (label.length > 1) 0.78f else 0.95f)
        val w = text.measureText(label)
        if (w > maxW) text.textSize *= maxW / w
        val y = cy - (text.descent() + text.ascent()) / 2f
        canvas.drawText(label, cx, y, text)
    }
}

/**
 * Индикатор «в эфире»: три полоски, прыгают, пока радио играет.
 * Анимация ~15 кадров в секунду и только пока видна.
 */
class OnAirView(context: Context, private val theme: DashTheme) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.accent }

    var playing = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
            if (value) tick()
        }

    private val ticker = Runnable { tick() }

    private fun tick() {
        removeCallbacks(ticker)
        invalidate()
        if (playing && isAttachedToWindow && isShown) postDelayed(ticker, FRAME_MS)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (playing) tick()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (playing) tick()
    }

    override fun onDraw(canvas: Canvas) {
        val bars = 3
        val gap = width * 0.18f
        val bw = (width - gap * (bars - 1)) / bars
        val t = SystemClock.uptimeMillis() / 1000.0
        paint.color = if (playing) theme.accent else ColorUtils.setAlphaComponent(theme.secondaryText, 0x80)
        for (i in 0 until bars) {
            val k = if (playing) {
                0.3f + 0.7f * abs(sin(t * (5.0 + i * 1.7) + i * 1.3)).toFloat()
            } else {
                0.25f
            }
            val h = height * k
            val x = i * (bw + gap)
            canvas.drawRoundRect(x, height - h, x + bw, height.toFloat(), bw / 3f, bw / 3f, paint)
        }
    }

    companion object {
        private const val FRAME_MS = 66L
    }
}

/**
 * Мини-шкала диапазона: деления, точки известных станций (избранные — их
 * цветом), стрелка текущей частоты и кольцо цели, пока идёт переключение.
 *
 * Тап по точке — настроиться на станцию ([onTune]); свайп — поиск
 * соседней станции ([onSeek]: +1 вправо по частоте, −1 влево); тап во
 * время переключения — отменить его ([onCancel]).
 */
class BandScaleView(context: Context, private val theme: DashTheme) : View(context) {

    var onTune: (RadioStations.Station) -> Unit = {}
    var onSeek: (Int) -> Unit = {}
    var onCancel: () -> Unit = {}

    private var band = RadioHub.FM
    private var stations: List<RadioStations.Station> = emptyList()
    private var current: Double? = null
    private var target: Double? = null
    private var walking = false

    private val density = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = theme.secondaryText
        textSize = 11f * resources.displayMetrics.scaledDensity
    }

    private val pulse = Runnable { invalidate(); schedulePulse() }

    fun bind(
        band: String,
        stations: List<RadioStations.Station>,
        current: Double?,
        target: Double?,
        walking: Boolean
    ) {
        val inBand = stations.filter { it.band == band }
        val changed = band != this.band || inBand != this.stations || current != this.current ||
            target != this.target || walking != this.walking
        this.band = band
        this.stations = inBand
        this.current = current
        this.target = target
        this.walking = walking
        if (changed) invalidate()
        schedulePulse()
    }

    private fun schedulePulse() {
        removeCallbacks(pulse)
        if (walking && isAttachedToWindow) postDelayed(pulse, 80L)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(pulse)
        super.onDetachedFromWindow()
    }

    private val lo get() = if (band == RadioHub.AM) AM_LO else FM_LO
    private val hi get() = if (band == RadioHub.AM) AM_HI else FM_HI
    private val pad get() = 14f * density

    private fun xOf(f: Double): Float {
        val k = ((f - lo) / (hi - lo)).coerceIn(0.0, 1.0)
        return pad + (width - 2 * pad) * k.toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0) return
        val midY = height * 0.42f
        // Деления: мелкие и крупные с подписью.
        val minor = if (band == RadioHub.AM) 50.0 else 0.5
        val major = if (band == RadioHub.AM) 200.0 else 4.0
        line.color = ColorUtils.setAlphaComponent(theme.secondaryText, 0x70)
        var f = Math.ceil(lo / minor) * minor
        while (f <= hi + 1e-6) {
            val isMajor = abs(f / major - Math.round(f / major)) < 1e-6
            val h = (if (isMajor) 9f else 4f) * density
            line.strokeWidth = (if (isMajor) 1.6f else 1f) * density
            val x = xOf(f)
            canvas.drawLine(x, midY - h, x, midY + h, line)
            if (isMajor) {
                canvas.drawText(f.toInt().toString(), x, height - 2f * density, label)
            }
            f += minor
        }
        // Станции из списка.
        stations.forEach { s ->
            val x = xOf(s.freq)
            if (s.favorite) {
                dot.color = RadioStations.colorOf(s)
                canvas.drawCircle(x, midY, 4.5f * density, dot)
            } else {
                dot.color = theme.primaryText
                canvas.drawCircle(x, midY, 2.5f * density, dot)
            }
        }
        // Цель переключения — пульсирующее кольцо.
        target?.let { t ->
            val k = (SystemClock.uptimeMillis() % 900L) / 900f
            ring.color = ColorUtils.setAlphaComponent(theme.accent, (255 * (1f - k)).toInt())
            ring.strokeWidth = 2f * density
            canvas.drawCircle(xOf(t), midY, (6f + 8f * k) * density, ring)
        }
        // Стрелка текущей частоты.
        current?.let { c ->
            val x = xOf(c)
            line.color = theme.accent
            line.strokeWidth = 3f * density
            canvas.drawLine(x, midY - 15f * density, x, midY + 15f * density, line)
            dot.color = theme.accent
            canvas.drawCircle(x, midY - 15f * density, 3.5f * density, dot)
        }
    }

    private var downX = 0f
    private var downY = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = e.x - downX
                when {
                    walking -> onCancel()
                    abs(dx) > SWIPE_DP * density -> onSeek(if (dx < 0) 1 else -1)
                    else -> nearest(e.x)?.let { onTune(it) }
                }
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    /** Ближайшая к точке касания станция, не дальше [HIT_DP]. */
    private fun nearest(x: Float): RadioStations.Station? =
        stations.minByOrNull { abs(xOf(it.freq) - x) }
            ?.takeIf { abs(xOf(it.freq) - x) <= HIT_DP * density }

    companion object {
        const val FM_LO = 87.5
        const val FM_HI = 108.0
        const val AM_LO = 522.0
        const val AM_HI = 1620.0
        private const val SWIPE_DP = 40f
        private const val HIT_DP = 28f
    }
}
