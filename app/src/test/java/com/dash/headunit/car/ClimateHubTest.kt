package com.dash.headunit.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Калибровка уставки климата (шкала «1°»): сверено на машине —
 * панель 25° при raw 16,10 = 10, шаг панели 1° = шаг raw 1.
 */
class ClimateHubTest {

    @Test
    fun scaleTemp_raw10_is25() {
        assertEquals(25f, ClimateHub.scaleTemp(10, false)!!, 0.001f)
    }

    @Test
    fun scaleTemp_oneDegreeStep() {
        assertEquals(24f, ClimateHub.scaleTemp(9, false)!!, 0.001f)
        assertEquals(26f, ClimateHub.scaleTemp(11, false)!!, 0.001f)
    }

    @Test
    fun scaleTemp_range() {
        assertEquals(ClimateHub.TEMP_LO, ClimateHub.scaleTemp(0, false)!!, 0.001f)
        assertEquals(16f, ClimateHub.scaleTemp(1, false)!!, 0.001f)
        assertEquals(30f, ClimateHub.scaleTemp(15, false)!!, 0.001f)
        assertEquals(ClimateHub.TEMP_HI, ClimateHub.scaleTemp(16, false)!!, 0.001f)
        assertNull(ClimateHub.scaleTemp(-1, false))
    }

    /** Шкала 0,5° (Jarvis «0,5°», штатный климат G6SH): °C = (raw + 35) / 2. */
    @Test
    fun halfStepScale() {
        assertEquals(ClimateHub.TEMP_LO, ClimateHub.scaleTemp(0, true)!!, 0.001f)
        assertEquals(18f, ClimateHub.scaleTemp(1, true)!!, 0.001f)
        assertEquals(22.5f, ClimateHub.scaleTemp(10, true)!!, 0.001f)
        assertEquals(32f, ClimateHub.scaleTemp(29, true)!!, 0.001f)
        assertEquals(ClimateHub.TEMP_HI, ClimateHub.scaleTemp(30, true)!!, 0.001f)
    }

    /** °C → raw и обратно на обеих шкалах сходится. */
    @Test
    fun tempRawRoundTrip() {
        for (raw in 0..30) assertEquals(raw, ClimateControl.tempRaw(ClimateHub.scaleTemp(raw, true)!!, true))
        for (raw in 0..16) assertEquals(raw, ClimateControl.tempRaw(ClimateHub.scaleTemp(raw, false)!!, false))
    }
}
