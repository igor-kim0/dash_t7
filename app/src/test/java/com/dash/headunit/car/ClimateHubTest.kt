package com.dash.headunit.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Калибровка уставки климата: снапшоты ГУ 16,10 = 9→10→11 за тапы
 * «+0.5°» (22.0→22.5→23.0), дефолт Jarvis 9 = 22 °C.
 */
class ClimateHubTest {

    @Test
    fun scaleTemp_raw9_is22() {
        assertEquals(22f, ClimateHub.scaleTemp(9)!!, 0.001f)
    }

    @Test
    fun scaleTemp_halfDegreeStep() {
        assertEquals(22.5f, ClimateHub.scaleTemp(10)!!, 0.001f)
        assertEquals(23.0f, ClimateHub.scaleTemp(11)!!, 0.001f)
    }

    @Test
    fun scaleTemp_range() {
        assertEquals(17.5f, ClimateHub.scaleTemp(0)!!, 0.001f)
        assertEquals(32.0f, ClimateHub.scaleTemp(29)!!, 0.001f)
        assertEquals(32.0f, ClimateHub.scaleTemp(30)!!, 0.001f) // HI
        assertNull(ClimateHub.scaleTemp(-1))
    }

    @Test
    fun roundTrip() {
        for (c in listOf(17.5f, 22f, 22.5f, 23f, 32f)) {
            assertEquals(c, ClimateHub.scaleTemp(ClimateHub.tempToRaw(c))!!, 0.001f)
        }
        assertEquals(0, ClimateHub.tempToRaw(10f))
        assertEquals(29, ClimateHub.tempToRaw(40f))
    }
}
