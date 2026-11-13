package com.dash.headunit.screensaver

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Основа анимированного фона заставки: перерисовка с частотой [fps], пока
 * вью в окне. [draw] получает время с показа (с) и шаг кадра (с).
 */
abstract class SaverView(context: Context, private val fps: Int) : View(context) {

    private var startMs = 0L
    private var lastMs = 0L
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            invalidate()
            postDelayed(this, frameDelayMs((SystemClock.uptimeMillis() - startMs) / 1000f))
        }
    }

    protected val density: Float get() = resources.displayMetrics.density

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startMs = SystemClock.uptimeMillis()
        lastMs = startMs
        running = true
        post(tick)
    }

    override fun onDetachedFromWindow() {
        running = false
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    final override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val now = SystemClock.uptimeMillis()
        // Шаг ограничен: после паузы (окно скрыто) частицы не «прыгают».
        val dt = ((now - lastMs) / 1000f).coerceIn(0f, 0.1f)
        lastMs = now
        draw(canvas, (now - startMs) / 1000f, dt)
    }

    protected abstract fun draw(canvas: Canvas, t: Float, dt: Float)

    /** Пауза до следующего кадра; статичный фон может просить реже [fps]. */
    protected open fun frameDelayMs(t: Float): Long = 1000L / fps

    // Строки часов меняются раз в минуту — не форматируем их каждый кадр.
    private var clockMinute = -1L
    private var clockTime = ""
    private var clockDate = ""

    /** Время «HH:mm» (кэш на минуту). */
    protected fun timeText(): String {
        refreshClock()
        return clockTime
    }

    /** «Понедельник, 2 ноября» (кэш на минуту). */
    protected fun dateText(): String {
        refreshClock()
        return clockDate
    }

    private fun refreshClock() {
        val nowMs = System.currentTimeMillis()
        val minute = nowMs / 60_000L
        if (minute == clockMinute) return
        clockMinute = minute
        val now = Date(nowMs)
        clockTime = TIME.format(now)
        clockDate = formatDate(now)
    }

    private val cornerTime = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        textAlign = Paint.Align.RIGHT
    }
    private val cornerDate = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        textAlign = Paint.Align.RIGHT
    }

    /** Часы и дата в правом нижнем углу (у «Камина» и «Северного сияния»). */
    protected fun drawCornerClock(canvas: Canvas, color: Int) {
        cornerTime.textSize = height * 0.13f
        cornerDate.textSize = height * 0.04f
        cornerTime.color = color
        cornerDate.color = color
        val right = width - 32 * density
        val dateBase = height - 24 * density - cornerDate.descent()
        canvas.drawText(dateText(), right, dateBase, cornerDate)
        val timeBase = dateBase + cornerDate.ascent() - cornerTime.descent()
        canvas.drawText(timeText(), right, timeBase, cornerTime)
    }

    companion object {
        // Как у часов дашборда: 24 ч и русская дата при любой локали ГУ.
        val RU = Locale("ru")
        val TIME = SimpleDateFormat("HH:mm", RU)
        private val DATE = SimpleDateFormat("EEEE, d MMMM", RU)

        /** «Понедельник, 2 ноября». */
        fun formatDate(now: Date): String =
            DATE.format(now).replaceFirstChar { it.titlecase(RU) }
    }
}
