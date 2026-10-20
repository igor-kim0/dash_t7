package com.dash.headunit.dashboard

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * Контейнер дашборда с фиксированной сеткой [COLS] x [ROWS].
 * Весь экран в альбомной ориентации = 100 x 70 клеток.
 *
 * Каждый ребёнок должен иметь [LayoutParams] с координатами и размером в клетках.
 * Перемещение/ресайз выполняют сами виджеты ([WidgetFrame]) в режиме редактирования,
 * layout только пересчитывает пиксели и сообщает об изменениях через [onLayoutChanged].
 */
class DashboardGridLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    var onLayoutChanged: (() -> Unit)? = null

    class LayoutParams(
        var cellX: Int,
        var cellY: Int,
        var cellW: Int,
        var cellH: Int
    ) : ViewGroup.LayoutParams(0, 0) {
        constructor(c: Context, attrs: AttributeSet?) : this(0, 0, MIN_W, MIN_H)
    }

    override fun generateDefaultLayoutParams(): ViewGroup.LayoutParams =
        LayoutParams(0, 0, MIN_W, MIN_H)

    override fun generateLayoutParams(attrs: AttributeSet?): ViewGroup.LayoutParams =
        LayoutParams(context, attrs)

    override fun generateLayoutParams(p: ViewGroup.LayoutParams?): ViewGroup.LayoutParams =
        LayoutParams(0, 0, MIN_W, MIN_H)

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?): Boolean =
        p is LayoutParams

    private fun cellSize(): Pair<Float, Float> {
        val cw = width.toFloat() / COLS
        val ch = height.toFloat() / ROWS
        return cw to ch
    }

    /** Размер клетки в пикселях — нужен фреймам для перевода драга в клетки. */
    fun cellSizePx(): Pair<Float, Float> = cellSize()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        if (w == 0 || h == 0) return
        val cw = w.toFloat() / COLS
        val ch = h.toFloat() / ROWS
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val lp = child.layoutParams as LayoutParams
            val childW = (lp.cellW * cw).toInt()
            val childH = (lp.cellH * ch).toInt()
            child.measure(
                MeasureSpec.makeMeasureSpec(childW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(childH, MeasureSpec.EXACTLY)
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val (cw, ch) = cellSize()
        if (cw == 0f || ch == 0f) return
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val lp = child.layoutParams as LayoutParams
            val left = (lp.cellX * cw).toInt()
            val top = (lp.cellY * ch).toInt()
            child.layout(
                left,
                top,
                left + (lp.cellW * cw).toInt(),
                top + (lp.cellH * ch).toInt()
            )
        }
    }

    /** Вызывается фреймом после драга/ресайза: обновить параметры и пересчитать. */
    fun updateChild(child: View, cellX: Int, cellY: Int, cellW: Int, cellH: Int) {
        val lp = child.layoutParams as LayoutParams
        // Сначала размер (иначе масштабная арифметика позиций возможна
        // с пустым диапазоном: ROWS - cellH < 0 при перетаскивании края).
        lp.cellW = cellW.coerceIn(MIN_W, COLS)
        lp.cellH = cellH.coerceIn(MIN_H, ROWS)
        lp.cellX = cellX.coerceIn(0, COLS - lp.cellW)
        lp.cellY = cellY.coerceIn(0, ROWS - lp.cellH)
        child.layoutParams = lp
        requestLayout()
        onLayoutChanged?.invoke()
    }

    companion object {
        const val COLS = 100
        const val ROWS = 70
        const val MIN_W = 10
        const val MIN_H = 8
    }
}
