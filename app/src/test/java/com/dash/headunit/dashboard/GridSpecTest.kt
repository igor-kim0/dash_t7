package com.dash.headunit.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GridSpecTest {

    private fun spec(x: Int, y: Int, w: Int, h: Int, type: WidgetType = WidgetType.CLOCK) =
        WidgetSpec("w", type, x, y, w, h)

    @Test
    fun fineIsIdentity() {
        val s = spec(4, 6, 60, 24)
        assertEquals(s, GridSpec.FINE.fromFine(s))
        assertEquals(s, GridSpec.FINE.toFine(s))
    }

    @Test
    fun fineAppliesOldMinimums() {
        val s = GridSpec.FINE.fromFine(spec(0, 0, 3, 3))
        assertEquals(DashboardGridLayout.MIN_W, s.w)
        assertEquals(DashboardGridLayout.MIN_H, s.h)
    }

    @Test
    fun defaultLayoutTo4x4() {
        val g = GridSpec.BLOCKS_4
        // Комбо 4,6 60x24 → колонки 0..2, строки 0..2 (края 0.16→0, 2.56→3; 0.34→0, 1.71→2).
        assertEquals(spec(0, 0, 3, 2), g.fromFine(spec(4, 6, 60, 24)))
        // Прогноз 4,32 92x34 → вся ширина, строки 2..4.
        assertEquals(spec(0, 2, 4, 2), g.fromFine(spec(4, 32, 92, 34)))
    }

    @Test
    fun blockMinimumIsOneCell() {
        val s = GridSpec(12, 12).fromFine(spec(0, 0, 2, 2))
        assertEquals(1, s.w)
        assertEquals(1, s.h)
    }

    @Test
    fun roundTripIsStableForAllGrids() {
        for (c in GridSpec.COLS_RANGE) for (r in GridSpec.ROWS_RANGE) {
            val g = GridSpec(c, r)
            for (x in 0 until c) for (w in 1..(c - x)) {
                val y = (x * 7) % r
                val h = 1 + (w % (r - y))
                val s = spec(x, y, w, h)
                val back = g.fromFine(g.toFine(s))
                assertEquals("grid ${c}x$r", s, back)
                // Хранимая раскладка проходит загрузку без изменений.
                val fine = g.toFine(s)
                assertEquals(fine, fine.clamped(minW = 1, minH = 1))
            }
        }
    }

    @Test
    fun blockStaysInsideGrid() {
        val g = GridSpec(5, 3)
        val s = g.fromFine(spec(95, 65, 40, 40))
        assertTrue(s.x + s.w <= 5 && s.y + s.h <= 3)
    }
}
