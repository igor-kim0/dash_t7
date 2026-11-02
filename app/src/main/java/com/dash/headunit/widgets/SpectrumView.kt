package com.dash.headunit.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import androidx.core.graphics.ColorUtils
import com.dash.headunit.dashboard.WidgetSettingsStore
import com.dash.headunit.music.SpectrumHub
import com.dash.headunit.theme.DashTheme
import kotlin.math.min

/**
 * Блок спектра в виджетах музыки. Полосы — из [SpectrumHub]
 * (реальный звук или имитация), пять видов отрисовки ([STYLES]).
 */
class SpectrumView(
    context: Context,
    private val theme: DashTheme,
    private val style: String
) : View(context) {

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.primaryText }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeJoin = Paint.Join.ROUND
        color = theme.accent
    }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(theme.cardBorder, 110)
    }
    private val rect = RectF()
    private val path = Path()
    private val fillPath = Path()
    private val xs = FloatArray(SpectrumHub.BANDS)
    private val ys = FloatArray(SpectrumHub.BANDS)
    private var shaderH = -1

    private val hubListener: () -> Unit = { invalidate() }

    /** Цвета светодиодов снизу вверх. */
    private val ledLow = if (theme === com.dash.headunit.theme.RetroLook.THEME) theme.accent else Color.rgb(0x3D, 0xDC, 0x84)
    private val ledMid = if (theme === com.dash.headunit.theme.RetroLook.THEME) theme.primaryText else Color.rgb(0xFF, 0xC1, 0x07)
    private val ledHigh = Color.rgb(0xF4, 0x43, 0x36)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shaderH = -1
    }

    private fun ensureShader() {
        if (shaderH == height) return
        shaderH = height
        val h = height.toFloat()
        fill.shader = when (style) {
            STYLE_WAVE -> LinearGradient(
                0f, 0f, 0f, h,
                ColorUtils.setAlphaComponent(theme.accent, 190),
                ColorUtils.setAlphaComponent(theme.accent, 10),
                Shader.TileMode.CLAMP
            )
            STYLE_MIRROR -> LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(
                    ColorUtils.setAlphaComponent(theme.accent, 90),
                    theme.accent,
                    ColorUtils.setAlphaComponent(theme.accent, 90)
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            STYLE_BARS -> LinearGradient(
                0f, h, 0f, 0f,
                ColorUtils.setAlphaComponent(theme.accent, 150),
                theme.accent,
                Shader.TileMode.CLAMP
            )
            else -> null
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        ensureShader()
        when (style) {
            STYLE_LEDS -> drawLeds(canvas)
            STYLE_WAVE -> drawWave(canvas)
            STYLE_MIRROR -> drawMirror(canvas)
            STYLE_DOTS -> drawDots(canvas)
            else -> drawBars(canvas)
        }
    }

    private fun slot(): Float = width.toFloat() / SpectrumHub.BANDS

    /** 1. Столбцы с пиковыми чёрточками. */
    private fun drawBars(c: Canvas) {
        val s = slot()
        val gap = s * 0.28f
        val h = height.toFloat()
        val r = min((s - gap) / 2f, 4f * density)
        val peakH = 2.5f * density
        for (i in 0 until SpectrumHub.BANDS) {
            val l = SpectrumHub.levels[i]
            val x0 = i * s + gap / 2
            val x1 = x0 + s - gap
            val top = h - l * (h - peakH * 2)
            if (l > 0.003f) {
                rect.set(x0, top, x1, h)
                c.drawRoundRect(rect, r, r, fill)
            }
            val p = SpectrumHub.peaks[i]
            if (p > 0.01f) {
                val py = h - p * (h - peakH * 2) - peakH * 1.6f
                rect.set(x0, py, x1, py + peakH)
                c.drawRect(rect, peakPaint)
            }
        }
    }

    /** 2. Сегменты-светодиоды: зелёный → жёлтый → красный. */
    private fun drawLeds(c: Canvas) {
        val s = slot()
        val gap = s * 0.22f
        val h = height.toFloat()
        val cell = 6f * density
        val rows = ((h + cell * 0.4f) / cell).toInt().coerceAtLeast(3)
        val cellH = h / rows
        val segH = cellH * 0.7f
        for (i in 0 until SpectrumHub.BANDS) {
            val lit = Math.round(SpectrumHub.levels[i] * rows)
            val peakRow = Math.round(SpectrumHub.peaks[i] * rows) - 1
            val x0 = i * s + gap / 2
            val x1 = x0 + s - gap
            for (row in 0 until rows) {
                val y1 = h - row * cellH
                rect.set(x0, y1 - segH, x1, y1)
                val frac = row.toFloat() / rows
                val on = row < lit || (row == peakRow && peakRow > 0)
                if (on) {
                    fill.color = when {
                        frac >= 0.8f -> ledHigh
                        frac >= 0.55f -> ledMid
                        else -> ledLow
                    }
                    c.drawRect(rect, fill)
                } else {
                    c.drawRect(rect, dimPaint)
                }
            }
        }
    }

    /** 3. Гладкая волна с градиентной заливкой. */
    private fun drawWave(c: Canvas) {
        val n = SpectrumHub.BANDS
        val h = height.toFloat()
        val top = linePaint.strokeWidth
        val step = width.toFloat() / (n - 1)
        for (i in 0 until n) {
            xs[i] = i * step
            ys[i] = h - SpectrumHub.levels[i] * (h - top)
        }
        path.reset()
        path.moveTo(xs[0], ys[0])
        for (i in 1 until n) {
            val mx = (xs[i - 1] + xs[i]) / 2
            path.cubicTo(mx, ys[i - 1], mx, ys[i], xs[i], ys[i])
        }
        fillPath.set(path)
        fillPath.lineTo(width.toFloat(), h)
        fillPath.lineTo(0f, h)
        fillPath.close()
        c.drawPath(fillPath, fill)
        c.drawPath(path, linePaint)
    }

    /** 4. Зеркало: столбики вверх и вниз от центральной линии. */
    private fun drawMirror(c: Canvas) {
        val s = slot()
        val gap = s * 0.3f
        val mid = height / 2f
        val r = min((s - gap) / 2f, 3f * density)
        val minHalf = 1f * density
        for (i in 0 until SpectrumHub.BANDS) {
            val half = minHalf + SpectrumHub.levels[i] * (mid - minHalf)
            val x0 = i * s + gap / 2
            rect.set(x0, mid - half, x0 + s - gap, mid + half)
            c.drawRoundRect(rect, r, r, fill)
        }
    }

    /** 5. Точечная матрица: яркость точки по уровню. */
    private fun drawDots(c: Canvas) {
        val s = slot()
        val h = height.toFloat()
        val radius = min(s * 0.32f, 4f * density)
        val pitch = radius * 2.6f
        val rows = (h / pitch).toInt().coerceAtLeast(3)
        val pitchY = h / rows
        for (i in 0 until SpectrumHub.BANDS) {
            val cx = i * s + s / 2
            val l = SpectrumHub.levels[i] * rows
            val peakRow = (SpectrumHub.peaks[i] * rows).toInt()
            for (row in 0 until rows) {
                val cy = h - row * pitchY - pitchY / 2
                val a = (l - row).coerceIn(0f, 1f)
                when {
                    a > 0.05f -> {
                        fill.color = ColorUtils.setAlphaComponent(theme.accent, (70 + 185 * a).toInt())
                        c.drawCircle(cx, cy, radius, fill)
                    }
                    row == peakRow && peakRow > 0 -> c.drawCircle(cx, cy, radius * 0.8f, peakPaint)
                    else -> c.drawCircle(cx, cy, radius * 0.6f, dimPaint)
                }
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        SpectrumHub.addListener(context, hubListener)
    }

    override fun onDetachedFromWindow() {
        SpectrumHub.removeListener(hubListener)
        super.onDetachedFromWindow()
    }

    companion object {
        /** Опция виджета: "on" / "off". */
        const val OPT_SPECTRUM = "spectrum"
        const val OPT_SPECTRUM_STYLE = "spectrum_style"

        const val STYLE_BARS = "bars"
        const val STYLE_LEDS = "leds"
        const val STYLE_WAVE = "wave"
        const val STYLE_MIRROR = "mirror"
        const val STYLE_DOTS = "dots"
        val STYLES = listOf(STYLE_BARS, STYLE_LEDS, STYLE_WAVE, STYLE_MIRROR, STYLE_DOTS)

        fun isOn(store: WidgetSettingsStore, widgetId: String): Boolean =
            store.getOption(widgetId, OPT_SPECTRUM, "off") == "on"

        fun style(store: WidgetSettingsStore, widgetId: String): String =
            store.getOption(widgetId, OPT_SPECTRUM_STYLE, STYLE_BARS).takeIf { it in STYLES } ?: STYLE_BARS
    }
}
