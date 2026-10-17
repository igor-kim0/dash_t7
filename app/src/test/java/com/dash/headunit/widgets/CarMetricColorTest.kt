package com.dash.headunit.widgets

import org.junit.Assert.assertEquals
import org.junit.Test

/** Пороги цвета иконок бортовой сети, температуры двигателя и запаса хода. */
class CarMetricColorTest {

    private val green = 0xFF66BB6A.toInt()

    @Test
    fun voltage() {
        assertEquals(FUEL_RED, voltageColor(11.1f, green))
        assertEquals(green, voltageColor(11.2f, green))
        assertEquals(green, voltageColor(13.8f, green))
        assertEquals(green, voltageColor(14.6f, green))
        assertEquals(FUEL_RED, voltageColor(14.7f, green))
    }

    @Test
    fun engineTemp() {
        assertEquals(TEMP_BLUE, engineTempColor(-10f, green))
        assertEquals(TEMP_BLUE, engineTempColor(34.9f, green))
        assertEquals(FUEL_YELLOW, engineTempColor(35f, green))
        assertEquals(FUEL_YELLOW, engineTempColor(79.9f, green))
        assertEquals(green, engineTempColor(80f, green))
        assertEquals(green, engineTempColor(109.9f, green))
        assertEquals(FUEL_RED, engineTempColor(110f, green))
    }

    @Test
    fun range() {
        assertEquals(FUEL_RED, rangeColor(69, green))
        assertEquals(FUEL_YELLOW, rangeColor(70, green))
        assertEquals(FUEL_YELLOW, rangeColor(199, green))
        assertEquals(green, rangeColor(200, green))
    }
}
