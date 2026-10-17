package com.dash.headunit.elm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [ObdParser]: разбор ответов ELM327 на PID 0105 (ОЖ). */
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
}
