package com.dash.headunit.widgets

import org.junit.Assert.assertEquals
import org.junit.Test

/** Селектор Desay (4,26) → ячейка ряда R N P 1–7 (сырое = 4 + передача в D). */
class GearIndexTest {

    @Test
    fun reverse() {
        assertEquals(0, gearIndex(2))
    }

    @Test
    fun neutral_inferredSlot() {
        assertEquals(1, gearIndex(3))
    }

    @Test
    fun park() {
        assertEquals(2, gearIndex(1))
    }

    @Test
    fun driveGears_areRawMinusFour() {
        // Жалоба с ГУ: реальная 1-я (сырое 5) показывала «3-ю».
        assertEquals(3, gearIndex(5))
        assertEquals(4, gearIndex(6))
        assertEquals(5, gearIndex(7))
        assertEquals(9, gearIndex(11))
    }

    @Test
    fun unknown_highlightsNothing() {
        assertEquals(-1, gearIndex(null))
        assertEquals(-1, gearIndex(0))
        assertEquals(-1, gearIndex(4))
        assertEquals(-1, gearIndex(12))
    }
}
