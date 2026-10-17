package com.dash.headunit.navi

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * Фон дашборда с прозрачным вырезом [hole] (координаты вью): через вырез
 * виден навигатор, работающий под полупрозрачным дашбордом.
 * [glow] — вытянутое по ширине свечение в центре поверх сплошного [color].
 *
 * Окно навигатора само не скругляется (чужая поверхность, Android 9) —
 * углы выреза закрашиваются фоном дашборда поверх карты ([holeRadius]),
 * по контуру — рамка как у карточек ([holeBorderColor]).
 */
class HoleBackground(color: Int, private val glow: Int? = null) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val corners = Path().apply { fillType = Path.FillType.EVEN_ODD }
    private val borderRect = RectF()

    /** Радиус скругления выреза, px (0 — прямые углы). */
    var holeRadius = 0f
        set(value) {
            field = value
            updateCorners()
        }

    /** Цвет рамки выреза (null — без рамки) и её толщина, px. */
    var holeBorderColor: Int? = null
        set(value) {
            field = value
            invalidateSelf()
        }
    var holeBorderWidth = 0f
        set(value) {
            field = value
            borderPaint.strokeWidth = value
            updateCorners()
        }

    var hole: Rect? = null
        set(value) {
            if (field == value) return
            field = value?.let { Rect(it) }
            updateCorners()
        }

    // Углы = прямоугольник выреза минус скруглённый прямоугольник.
    private fun updateCorners() {
        corners.reset()
        hole?.let { h ->
            if (holeRadius > 0f) {
                corners.addRect(RectF(h), Path.Direction.CW)
                corners.addRoundRect(RectF(h), holeRadius, holeRadius, Path.Direction.CW)
            }
            borderRect.set(h)
            borderRect.inset(holeBorderWidth / 2, holeBorderWidth / 2)
        }
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val h = hole
        canvas.save()
        if (h != null) canvas.clipOutRect(h)
        canvas.drawRect(bounds, paint)
        if (glow != null) canvas.drawRect(bounds, glowPaint)
        canvas.restore()
        if (h == null) return
        if (!corners.isEmpty) {
            canvas.drawPath(corners, paint)
            if (glow != null) canvas.drawPath(corners, glowPaint)
        }
        holeBorderColor?.let {
            if (holeBorderWidth <= 0f) return@let
            borderPaint.color = it
            val r = (holeRadius - holeBorderWidth / 2).coerceAtLeast(0f)
            canvas.drawRoundRect(borderRect, r, r, borderPaint)
        }
    }

    override fun onBoundsChange(bounds: Rect) {
        val g = glow ?: return
        if (bounds.isEmpty) return
        // Круговой градиент, растянутый в эллипс на всю ширину; центр чуть ниже середины.
        val r = bounds.height() * 0.75f
        val cx = bounds.exactCenterX()
        val cy = bounds.top + bounds.height() * 0.55f
        glowPaint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(g, Color.argb(110, Color.red(g), Color.green(g), Color.blue(g)), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        ).apply {
            setLocalMatrix(Matrix().apply { setScale(bounds.width() * 0.5f / r, 1f, cx, cy) })
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        glowPaint.alpha = alpha
        borderPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        glowPaint.colorFilter = colorFilter
        borderPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
