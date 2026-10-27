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
        assertEquals(25f, ClimateHub.scaleTemp(10)!!, 0.001f)
    }

    @Test
    fun scaleTemp_oneDegreeStep() {
        assertEquals(24f, ClimateHub.scaleTemp(9)!!, 0.001f)
        assertEquals(26f, ClimateHub.scaleTemp(11)!!, 0.001f)
    }

    @Test
    fun scaleTemp_range() {
        assertEquals(ClimateHub.TEMP_LO, ClimateHub.scaleTemp(0)!!, 0.001f)
        assertEquals(16f, ClimateHub.scaleTemp(1)!!, 0.001f)
        assertEquals(30f, ClimateHub.scaleTemp(15)!!, 0.001f)
        assertEquals(ClimateHub.TEMP_HI, ClimateHub.scaleTemp(16)!!, 0.001f)
        assertNull(ClimateHub.scaleTemp(-1))
    }
}
