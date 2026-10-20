package com.dash.headunit.elm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [ObdParser]: разбор ответов ELM327 (ОЖ, обороты, нагрузка, напряжение). */
class ObdParserTest {

    @Test
    fun coolant_standardSpaces() {
        // «41 05 5A» = 90 − 40… 0x5A = 90 → 50 °C.
        assertEquals(50f, ObdParser.coolant("41 05 5A\r\r>"))
    }

    @Test
    fun coolant_withoutSpaces() {
        // 0x5E = 94 → 54 °C.
        assertEquals(54f, ObdParser.coolant("ATZ\rELM327 v1.5\r\r0105\r41055E\r\r>"))
    }

    @Test
    fun coolant_multilineWithSearch() {
        val text = "SEARCHING...\r\r0105\r\r00\r41 05 3C\r\r>"
        assertEquals(20f, ObdParser.coolant(text))
    }

    @Test
    fun coolant_noData() {
        assertNull(ObdParser.coolant("NO DATA\r\r>"))
        assertNull(ObdParser.coolant(""))
    }

    @Test
    fun coolant_otherPidIgnored() {
        assertNull(ObdParser.coolant("41 0C 1A F8\r\r>"))
    }

    @Test
    fun coolant_truncatedAnswer() {
        assertNull(ObdParser.coolant("41 05\r\r>"))
    }

    @Test
    fun scanResponse_bytes() {
        assertEquals(listOf(0x41, 0x0C, 0x1A, 0xF8), ObdParser.scanResponse("41 0C 1A F8\r>", "410C"))
    }

    @Test
    fun rpm_standard() {
        // 0x1AF8 = 6904 / 4 = 1726 об/мин.
        assertEquals(1726f, ObdParser.rpm("41 0C 1A F8\r\r>"))
    }

    @Test
    fun rpm_idleWithoutSpaces() {
        // 0x0D48 = 3400 / 4 = 850.
        assertEquals(850f, ObdParser.rpm("010C\r410C0D48\r\r>"))
    }

    @Test
    fun rpm_truncatedAndNoData() {
        assertNull(ObdParser.rpm("41 0C 1A\r\r>"))
        assertNull(ObdParser.rpm("NO DATA\r\r>"))
    }

    @Test
    fun engineLoad_standard() {
        // 0xFF → 100 %, 0x33 = 51 → 20 %.
        assertEquals(100f, ObdParser.engineLoad("41 04 FF\r>"))
        assertEquals(20f, ObdParser.engineLoad("41 04 33\r>")!!, 0.01f)
    }

    @Test
    fun voltage_variants() {
        assertEquals(12.6f, ObdParser.voltage("12.6V\r\r>"))
        assertEquals(14.25f, ObdParser.voltage("ATRV\r14.25V\r\r>"))
        assertEquals(13.9f, ObdParser.voltage("13,9 V\r>"))
    }

    @Test
    fun voltage_garbage() {
        assertNull(ObdParser.voltage("?\r>"))
        assertNull(ObdParser.voltage(""))
        assertNull(ObdParser.voltage("ELM327 v1.5\r>"))
    }
}
