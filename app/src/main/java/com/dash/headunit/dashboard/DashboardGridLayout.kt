package com.dash.headunit.dashboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup

/**
 * Контейнер дашборда с сеткой [grid] (настройка «Сетка»): свободная
 * 100 x 70 мелких клеток (как раньше, без зазоров) или крупная — 4 x 4 /
 * своя, с зазором [gapPx] между блоками и по краям.
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

    /** Текущая сетка. Смена — только с пересборкой виджетов (их клетки в этой сетке). */
    var grid: GridSpec = GridSpec.FINE
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    /** Контуры клеток крупной сетки — в режиме редактирования. */
    var showGuides = false
        set(value) {
            field = value
            invalidate()
        }

    /** Крупная сетка без промежутков: блоки встык (настройка «Без промежутков»). */
    var noGap = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            invalidate()
        }

    private val cols get() = grid.cols
    private val rows get() = grid.rows

    /** Зазор между блоками крупной сетки; у свободной — 0. */
    private val gapPx: Float
        get() = if (grid.fine || noGap) 0f else TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, BLOCK_GAP_DP, resources.displayMetrics
        )

    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1.5f, resources.displayMetrics)
        color = 0x40FFFFFF
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(12f, 10f), 0f)
    }

    init {
        setWillNotDraw(false)
    }

    class LayoutParams(
        var cellX: Int,
        var cellY: Int,
        var cellW: Int,
        var cellH: Int,
        /** Наименьшая ширина при ресайзе (у части виджетов меньше [MIN_W]). */
        val minW: Int = MIN_W
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

    /** Размер клетки без зазора для экрана [w] x [h]. */
    private fun cellSize(w: Int = width, h: Int = height): Pair<Float, Float> {
        val gap = gapPx
        val cw = (w - gap * (cols + 1)) / cols
        val ch = (h - gap * (rows + 1)) / rows
        return cw.coerceAtLeast(0f) to ch.coerceAtLeast(0f)
    }

    /** Шаг клетки (с зазором) в пикселях — нужен фреймам для перевода драга в клетки. */
    fun cellSizePx(): Pair<Float, Float> {
        val (cw, ch) = cellSize()
        val gap = gapPx
        return (cw + gap) to (ch + gap)
    }

    /** Прямоугольник клеток [x],[y] размером [w] x [h] в пикселях. */
    private fun cellRect(x: Int, y: Int, w: Int, h: Int, cw: Float, ch: Float): RectF {
        val gap = gapPx
        val left = gap + x * (cw + gap)
        val top = gap + y * (ch + gap)
        return RectF(left, top, left + w * cw + (w - 1) * gap, top + h * ch + (h - 1) * gap)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!showGuides || grid.fine) return
        val (cw, ch) = cellSize()
        if (cw <= 0f || ch <= 0f) return
        val r = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, resources.displayMetrics)
        for (x in 0 until cols) for (y in 0 until rows) {
            canvas.drawRoundRect(cellRect(x, y, 1, 1, cw, ch), r, r, guidePaint)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        if (w == 0 || h == 0) return
        val (cw, ch) = cellSize(w, h)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val lp = child.layoutParams as LayoutParams
            val rect = cellRect(lp.cellX, lp.cellY, lp.cellW, lp.cellH, cw, ch)
            val childW = rect.right.toInt() - rect.left.toInt()
            val childH = rect.bottom.toInt() - rect.top.toInt()
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
            val rect = cellRect(lp.cellX, lp.cellY, lp.cellW, lp.cellH, cw, ch)
            child.layout(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
        }
    }

    /** Вызывается фреймом после драга/ресайза: обновить параметры и пересчитать. */
    fun updateChild(child: View, cellX: Int, cellY: Int, cellW: Int, cellH: Int) {
        val lp = child.layoutParams as LayoutParams
        // Сначала размер (иначе масштабная арифметика позиций возможна
        // с пустым диапазоном: ROWS - cellH < 0 при перетаскивании края).
        lp.cellW = cellW.coerceIn(lp.minW, cols)
        lp.cellH = cellH.coerceIn(grid.minH, rows)
        lp.cellX = cellX.coerceIn(0, cols - lp.cellW)
        lp.cellY = cellY.coerceIn(0, rows - lp.cellH)
        child.layoutParams = lp
        requestLayout()
        onLayoutChanged?.invoke()
    }

    companion object {
        /** Свободная сетка (в ней же хранится раскладка, см. [GridSpec]). */
        const val COLS = GridSpec.FINE_COLS
        const val ROWS = GridSpec.FINE_ROWS
        /** Минимум размера в свободной сетке. */
        const val MIN_W = 10
        const val MIN_H = 8
        /** Зазор между блоками крупной сетки. */
        private const val BLOCK_GAP_DP = 12f
    }
}
