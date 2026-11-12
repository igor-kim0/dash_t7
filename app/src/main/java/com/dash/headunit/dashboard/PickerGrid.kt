package com.dash.headunit.dashboard

import android.content.Context
import android.view.ViewGroup

/**
 * Сетка квадратных ячеек: [columns] колонок, сторона ячейки — от ширины
 * контейнера (минус промежутки), высота равна ширине. Содержимое ячейки
 * ужимается под её сторону (см. [Cell.applyScale]) — иначе подпись не влезет
 * в квадрат на узком экране.
 */
class PickerGrid(
    context: Context,
    private val columns: Int,
    private val gap: Int
) : ViewGroup(context) {

    /** Ячейка сетки: умеет ужимать содержимое под сторону ячейки. */
    interface Cell {
        /** [scale] — доля полного размера (1 — полный размер). */
        fun applyScale(scale: Float)
    }

    /** Сторона ячейки, пиксели. */
    private var cell = 0

    /** Ужим содержимого; -1 — ещё не считали. */
    private var scale = -1f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val avail = width - paddingLeft - paddingRight
        val newCell = ((avail - gap * (columns - 1)) / columns).coerceAtLeast(1)
        if (scale < 0f || newCell != cell) {
            cell = newCell
            // Полный размер содержимого ячейки — по нему считаем ужим.
            val full = measureCards(1f)
            scale = (cell / full.toFloat() * 0.95f).coerceIn(MIN_SCALE, 1f)
        }
        measureCards(scale)
        val rows = (childCount + columns - 1) / columns
        setMeasuredDimension(
            width,
            paddingTop + rows * cell + gap * (rows - 1) + paddingBottom
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val x = paddingLeft + (i % columns) * (cell + gap)
            val y = paddingTop + (i / columns) * (cell + gap)
            child.layout(x, y, x + cell, y + cell)
        }
    }

    /**
     * Измерить карточки с ужимом [newScale]; вернуть максимальную высоту
     * содержимого. Ужим применяется только один раз на значение — иначе
     * вёрстка перезапускалась бы сама по себе.
     */
    private fun measureCards(newScale: Float): Int {
        var max = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            (child as? Cell)?.applyScale(newScale)
            child.measure(
                MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            max = maxOf(max, child.measuredHeight)
        }
        return max
    }

    private companion object {
        /** Ниже этой доли содержимое не ужимаем — подпись станет нечитаемой. */
        const val MIN_SCALE = 0.5f
    }
}
