package com.dash.headunit.dashboard

import kotlin.math.roundToInt

/**
 * Сетка дашборда: [cols] × [rows] клеток на весь экран.
 *
 * Раскладка всегда хранится в мелких клетках «свободной» сетки
 * [FINE] (100 × 70, как было до настройки сетки) — так смена режима ничего
 * не портит: при показе координаты пересчитываются в клетки выбранной
 * сетки ([fromFine]), при сохранении — обратно ([toFine]).
 * Свободная сетка — без зазоров (их задаёт сам пользователь), крупная —
 * с зазором между блоками и минимальным размером в одну клетку.
 * Чистая — тестируется.
 */
data class GridSpec(val cols: Int, val rows: Int) {

    /** Свободная (мелкая) сетка — прежнее поведение. */
    val fine: Boolean get() = cols == FINE_COLS && rows == FINE_ROWS

    /** Наименьшая ширина виджета [type] в клетках этой сетки. */
    fun minW(type: WidgetType): Int = if (fine) type.minW else 1

    /** Наименьшая высота в клетках этой сетки. */
    val minH: Int get() = if (fine) DashboardGridLayout.MIN_H else 1

    /** Мелкие клетки → клетки этой сетки (края округляются, размер ≥ минимума). */
    fun fromFine(s: WidgetSpec): WidgetSpec {
        if (fine) return clamp(s)
        val x = scale(s.x, FINE_COLS, cols)
        val y = scale(s.y, FINE_ROWS, rows)
        return clamp(
            s.copy(
                x = x,
                y = y,
                w = scale(s.x + s.w, FINE_COLS, cols) - x,
                h = scale(s.y + s.h, FINE_ROWS, rows) - y
            )
        )
    }

    /** Размер не меньше минимума и не больше сетки, блок целиком внутри. */
    fun clamp(s: WidgetSpec): WidgetSpec {
        val w = s.w.coerceIn(minW(s.type), cols)
        val h = s.h.coerceIn(minH, rows)
        return s.copy(x = s.x.coerceIn(0, cols - w), y = s.y.coerceIn(0, rows - h), w = w, h = h)
    }

    /** Клетки этой сетки → мелкие клетки для хранения. */
    fun toFine(s: WidgetSpec): WidgetSpec {
        if (fine) return s
        val x = scale(s.x, cols, FINE_COLS)
        val y = scale(s.y, rows, FINE_ROWS)
        return s.copy(
            x = x,
            y = y,
            w = scale(s.x + s.w, cols, FINE_COLS) - x,
            h = scale(s.y + s.h, rows, FINE_ROWS) - y
        )
    }

    private fun scale(v: Int, from: Int, to: Int): Int = (v.toDouble() * to / from).roundToInt()

    companion object {
        const val FINE_COLS = 100
        const val FINE_ROWS = 70
        val FINE = GridSpec(FINE_COLS, FINE_ROWS)
        /** Режим «4 × 4». */
        val BLOCKS_4 = GridSpec(4, 4)
        /** Пределы своей сетки. */
        val COLS_RANGE = 2..20
        val ROWS_RANGE = 2..12
    }
}
