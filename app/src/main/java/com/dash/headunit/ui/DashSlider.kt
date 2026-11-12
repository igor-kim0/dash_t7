package com.dash.headunit.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import com.dash.headunit.theme.DashTheme

/**
 * Ползунок: толстая дорожка, заполненная акцентом, круглый бегунок.
 * [onChange] — только на изменения пальцем; [setValue] — обновить извне
 * (во время перетаскивания игнорируется, чтобы не дёргать бегунок).
 */
class DashSlider(
    context: Context,
    private val theme: DashTheme,
    private val range: IntRange,
    private val step: Int,
    start: Int,
    private val onChange: (Int) -> Unit
) : View(context) {
    var value = start.coerceIn(range)
        private set
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val trackH = dp(10f)
    private val thumbR = dp(12f)

    /** Палец на ползунке. */
    var dragging = false
        private set

    fun setValue(v: Int) {
        if (dragging) return
        val c = v.coerceIn(range)
        if (c != value) {
            value = c
            invalidate()
        }
    }

    private fun fraction(): Float =
        if (range.last == range.first) 0f
        else (value - range.first).toFloat() / (range.last - range.first)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                update(e.x)
            }
            MotionEvent.ACTION_MOVE -> update(e.x)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
            }
        }
        return true
    }

    private fun update(x: Float) {
        val left = thumbR
        val right = width - thumbR
        val f = ((x - left) / (right - left)).coerceIn(0f, 1f)
        val raw = range.first + f * (range.last - range.first)
        val snapped = (Math.round(raw / step) * step).coerceIn(range)
        if (snapped != value) {
            value = snapped
            onChange(value)
        }
        invalidate()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.4f
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val left = thumbR
        val right = width - thumbR
        val x = left + (right - left) * fraction()
        paint.style = Paint.Style.FILL
        paint.color = ColorUtils.setAlphaComponent(theme.primaryText, 0x26)
        rect.set(left - trackH / 2, cy - trackH / 2, right + trackH / 2, cy + trackH / 2)
        canvas.drawRoundRect(rect, trackH / 2, trackH / 2, paint)
        paint.color = theme.accent
        rect.set(left - trackH / 2, cy - trackH / 2, x, cy + trackH / 2)
        canvas.drawRoundRect(rect, trackH / 2, trackH / 2, paint)
        if (dragging) {
            paint.color = ColorUtils.setAlphaComponent(theme.accent, 0x40)
            canvas.drawCircle(x, cy, thumbR * 1.7f, paint)
        }
        paint.color = theme.accent
        canvas.drawCircle(x, cy, thumbR, paint)
        paint.color = theme.background
        canvas.drawCircle(x, cy, thumbR * 0.4f, paint)
    }

    private fun dp(v: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
    )
}
