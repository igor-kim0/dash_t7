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
            postDelayed(this, 1000L / fps)
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
        val now = Date()
        val right = width - 32 * density
        val dateBase = height - 24 * density - cornerDate.descent()
        canvas.drawText(formatDate(now), right, dateBase, cornerDate)
        val timeBase = dateBase + cornerDate.ascent() - cornerTime.descent()
        canvas.drawText(TIME.format(now), right, timeBase, cornerTime)
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
