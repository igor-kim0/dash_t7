package com.dash.headunit.widgets

import org.junit.Assert.assertEquals
import org.junit.Test

/** Пороги цвета заливки иконки бака. */
class FuelLevelColorTest {

    private val green = 0xFF66BB6A.toInt()

    @Test
    fun above25_isGreen() {
        assertEquals(green, fuelLevelColor(25.1f, green))
        assertEquals(green, fuelLevelColor(65f, green))
        assertEquals(green, fuelLevelColor(100f, green))
    }

    @Test
    fun between8and25_isYellow() {
        assertEquals(FUEL_YELLOW, fuelLevelColor(8f, green))
        assertEquals(FUEL_YELLOW, fuelLevelColor(15f, green))
        assertEquals(FUEL_YELLOW, fuelLevelColor(25f, green))
    }

    @Test
    fun below8_isRed() {
        assertEquals(FUEL_RED, fuelLevelColor(7.9f, green))
        assertEquals(FUEL_RED, fuelLevelColor(0f, green))
    }
}
