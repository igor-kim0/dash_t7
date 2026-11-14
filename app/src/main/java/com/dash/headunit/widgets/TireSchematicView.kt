package com.dash.headunit.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.dash.headunit.car.TirePressures
import com.dash.headunit.car.Wheel
import com.dash.headunit.theme.DashTheme

/**
 * Схема машины вид сверху с колёсами: каждое колесо — зелёное, если
 * давление не ниже порога, красное — если ниже, серое — если данных нет.
 * Используется виджетом «Автомобиль» (метрика «Колёса»).
 */
class TireSchematicView(context: Context) : View(context) {

    private var pressures: TirePressures? = null
    private var threshold = 2f
    private var okColor: Int = DashTheme.DARK.success
    private var unknownColor: Int = DashTheme.DARK.secondaryText

    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.6f)
        color = DashTheme.DARK.secondaryText
    }
    private val wheelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    /** Данные и порог; null — давлений нет (колёса серые). */
    fun setState(
        pressures: TirePressures?,
        threshold: Float,
        okColor: Int,
        unknownColor: Int
    ) {
        this.pressures = pressures
        this.threshold = threshold
        this.okColor = okColor
        this.unknownColor = unknownColor
        body.color = unknownColor
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Кузов: вид сверху, перед сверху.
        val bodyW = w * 0.40f
        val bodyH = h * 0.86f
        val left = (w - bodyW) / 2f
        val top = (h - bodyH) / 2f
        rect.set(left, top, left + bodyW, top + bodyH)
        canvas.drawRoundRect(rect, bodyW * 0.28f, bodyW * 0.28f, body)

        // Колёса: по два с каждой стороны, передние сверху.
        val wheelW = w * 0.13f
        val wheelH = h * 0.13f
        val frontY = top + bodyH * 0.16f
        val rearY = top + bodyH * 0.60f
        drawWheel(canvas, left - wheelW * 0.6f, frontY, wheelW, wheelH, Wheel.FL)
        drawWheel(canvas, left + bodyW - wheelW * 0.4f, frontY, wheelW, wheelH, Wheel.FR)
        drawWheel(canvas, left - wheelW * 0.6f, rearY, wheelW, wheelH, Wheel.RL)
        drawWheel(canvas, left + bodyW - wheelW * 0.4f, rearY, wheelW, wheelH, Wheel.RR)
    }

    private fun drawWheel(
        canvas: Canvas,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        wheel: Wheel
    ) {
        val p = pressures?.of(wheel)
        wheelPaint.color = when {
            p == null -> unknownColor
            p < threshold -> FUEL_RED
            else -> okColor
        }
        rect.set(x, y, x + w, y + h)
        canvas.drawRoundRect(rect, h * 0.25f, h * 0.25f, wheelPaint)
    }

    private fun dp(value: Float): Float =
        value * resources.displayMetrics.density
}
