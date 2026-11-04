package com.dash.headunit.elm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [ObdParser]: разбор ответов ELM327 (ОЖ, обороты, нагрузка, напряжение, дроссель и др.). */
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

    @Test
    fun intakeAir_standard() {
        // 0x2C = 44 → 4 °C.
        assertEquals(4f, ObdParser.intakeAir("41 0F 2C\r\r>"))
        assertNull(ObdParser.intakeAir("41 0F\r\r>"))
    }

    @Test
    fun throttle_standard() {
        // 0x80 = 128 → 128·100/255 ≈ 50,2 %.
        assertEquals(128 * 100f / 255f, ObdParser.throttle("41 11 80\r>")!!, 0.01f)
        assertNull(ObdParser.throttle("41 05 80\r>"))
    }

    @Test
    fun fuelLevel_standard() {
        // 0xCC = 204 → 80 %.
        assertEquals(80f, ObdParser.fuelLevel("41 2F CC\r>")!!, 0.01f)
        assertNull(ObdParser.fuelLevel("NO DATA\r\r>"))
    }

    @Test
    fun mapPressure_standard() {
        // 0x66 = 102 кПа.
        assertEquals(102f, ObdParser.mapPressure("41 0B 66\r>")!!)
    }

    @Test
    fun fuelTrim_shortAndLong() {
        // 0x90 = 144 → (144 − 128)·100/128 ≈ +12,5 % (богатая смесь).
        assertEquals(12.5f, ObdParser.fuelTrimShort("41 06 90\r>")!!, 0.01f)
        // 0x70 = 112 → −12,5 % (бедная смесь).
        assertEquals(-12.5f, ObdParser.fuelTrimLong("41 07 70\r>")!!, 0.01f)
        assertNull(ObdParser.fuelTrimShort("41 0C 1A F8\r\r>"))
    }

    // Строки из logs_20261023: клон BT051525 шлёт CAN-заголовок «7E8» (нечётная длина после ATS0).

    @Test
    fun canHeader_oddLengthLines() {
        assertEquals(82f, ObdParser.coolant("7E80341057A>"))
        assertEquals(0f, ObdParser.rpm("SEARCHING...7E804410C0000>"))
        assertEquals(0x27 * 100f / 255f, ObdParser.throttle("7E803411127>")!!, 0.01f)
        assertEquals(101f, ObdParser.mapPressure("7E803410B65>"))
        assertEquals(0f, ObdParser.fuelTrimShort("7E803410680>"))
        assertEquals(0xCD * 100f / 255f, ObdParser.fuelLevel("7E803412FCD>")!!, 0.01f)
    }

    @Test
    fun canHeader_withSpaces() {
        assertEquals(82f, ObdParser.coolant("7E8 03 41 05 7A \r\r>"))
    }

    @Test
    fun serviceWordsDoNotGlue() {
        assertNull(ObdParser.coolant("SEARCHING... UNABLE TO CONNECT\r\r>"))
        assertNull(ObdParser.intakeAir("NO DATA>"))
        assertNull(ObdParser.rpm("BUS INIT: . . . .UNABLE TO CONNECT"))
    }

    @Test
    fun supportedPids_fromLog() {
        // 0100 → BE 3E A8 13: 01 03–07, 0B–0F, 11 13 15, 1C 1F 20; 0110 (MAF) нет.
        val pids = ObdParser.supportedPids("SEARCHING... 7E8064100BE3EA813", 0x00)!!
        assertEquals(
            setOf(0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F,
                0x11, 0x13, 0x15, 0x1C, 0x1F, 0x20),
            pids
        )
        assertEquals(setOf(0x2F), ObdParser.supportedPids("41 20 00 02 00 00", 0x20))
        assertNull(ObdParser.supportedPids("UNABLE TO CONNECT>", 0x00))
        assertNull(ObdParser.supportedPids("41 00 BE 3E", 0x00))
    }

    /** Лог Car Scanner (Tiggo 7): на 7DF первой отвечает КПП 7E9 со своей маской. */
    @Test
    fun supportedPids_unionOfEcus() {
        val reply = "SEARCHING...\r7E906410088188013\r7E8064100BE3EA813\r\r>"
        val pids = ObdParser.supportedPids(reply, 0x00)!!
        // PID двигателя (0104, 0106, 0107, 010B, 010F) не теряются из-за маски КПП.
        for (pid in listOf(0x04, 0x06, 0x07, 0x0B, 0x0F, 0x0C, 0x20)) assert(pid in pids) { "%02X".format(pid) }
        // 0140 двигателя: 7E D0 04 00 → 42–47, 49, 4A, 4C, 56.
        assertEquals(
            setOf(0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0x49, 0x4A, 0x4C, 0x56),
            ObdParser.supportedPids("7E906414040800000\r7E80641407ED00400", 0x40)
        )
    }

    /** Без заголовков (ATH0) ответы двух блоков различимы только порядком — берётся первый. */
    @Test
    fun scanAll_twoEcus() {
        val reply = "7E903411100\r7E80341111C\r\r>"
        assertEquals(listOf(listOf(0x41, 0x11, 0x00), listOf(0x41, 0x11, 0x1C)), ObdParser.scanAll(reply, "4111"))
        assertEquals(0f, ObdParser.throttle(reply))
    }

    /** Многокадровый ответ с CAN-заголовками (клон не послушал ATH0) — VIN целиком. */
    @Test
    fun joinFrames_withHeaders() {
        val reply = "7E81014490201454445\r7E82146423332423553\r7E82245303038353333\r\r>"
        val joined = ObdParser.joinFrames(reply)
        val bytes = ObdParser.scanResponse(joined, "4902")!!
        assertEquals("EDEFB32B5SE008533", bytes.drop(3).map { it.toChar() }.joinToString(""))
    }

    /** Набивка последнего кадра (AA / 55) отрезается по длине из первой строки. */
    @Test
    fun joinFrames_paddingTrimmed() {
        // Калибровка КПП: длина 0x13, последний кадр добит AA.
        val withHeaders = "7E9101349040154434D\r7E9215F423430305F42\r7E9223330315F3032AA\r\r>"
        assertEquals(19, ObdParser.joinFrames(withHeaders).split(' ').size)
        val noHeaders = "013\r0:49040154434D\r1:5F423430305F42\r2:3330315F303255\r\r>"
        val bytes = ObdParser.joinFrames(noHeaders).split(' ')
        assertEquals(19, bytes.size)
        assertEquals("32", bytes.last())
        // Однокадровый ответ не трогаем.
        assertEquals("7E803410570>", ObdParser.joinFrames("7E803410570>"))
    }

    /** Ответы с заголовками (ATH1) для сканера: одиночный, отказ, многокадровый, два блока. */
    @Test
    fun canMessages_fromLog() {
        val single = ObdParser.canMessages("7E80562010A8D78\r\r>")
        assertEquals(1, single.size)
        assertEquals("7E8", single[0].header)
        assertEquals(listOf(0x62, 0x01, 0x0A, 0x8D, 0x78), single[0].data)

        val nrc = ObdParser.canMessages("7E9037F2231\r\r>").single()
        assertEquals(listOf(0x7F, 0x22, 0x31), nrc.data)

        // TCM 7E9 и ECM 7E8 вперемешку (0904 из лога): каждый склеен по своему заголовку.
        val two = ObdParser.canMessages(
            "7E9101349040154434D\r7E810134904014532317E9215F423430305F42\r".replace("7E9215", "\r7E9215") +
                "7E8214E303541353032\r7E82200000000000000\r7E9223330315F3032AA\r\r>"
        )
        assertEquals(listOf("7E8", "7E9"), two.map { it.header })
        assert(two.all { it.complete && it.multiFrame })
        assertEquals(0x13, two[1].data.size)
        assertEquals("CM_B400_B301_02", two[1].data.drop(4).map { it.toChar() }.joinToString(""))

        // Оборванный многокадровый ответ — complete = false.
        val cut = ObdParser.canMessages("7E81014490201454445\r7E82146423332423553\r>").single()
        assertEquals(false, cut.complete)
        // Служебные строки и строки без заголовка пропускаются.
        assert(ObdParser.canMessages("NO DATA\r\r>").isEmpty())
        assert(ObdParser.canMessages("SEARCHING...\r41 0C 0B 92\r>").isEmpty())
    }
}
