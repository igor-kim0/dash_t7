package com.dash.headunit.elm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [IgnitionMonitor]: разбор ответов ЭБУ, журнал пропусков, прирост и перекос. */
class IgnitionMonitorTest {

    private val day = 24 * 3600_000L

    private fun row(ts: Long, odo: Int?, ecu: Double, vararg c: Int) =
        IgnitionMonitor.Row(ts, odo, ecu, c)

    @Test
    fun counter_fromCarLogWithHeader() {
        // Наш скан 05.11: клон шлёт заголовок 7E8 и без ATH1.
        assertEquals(listOf(0x0D, 0x32), IgnitionMonitor.didBytes("7E8056201830D32\r\r>", "220183"))
        assertEquals(3378.0, IgnitionMonitor.accept("220183", "7E8056201830D32\r\r>"))
    }

    @Test
    fun counter_plainReply() {
        assertEquals(2730.0, IgnitionMonitor.accept("22018A", "62 01 8A 0A AA\r\r>"))
        assertNull(IgnitionMonitor.accept("22018A", "NO DATA\r\r>"))
        assertNull(IgnitionMonitor.accept("22018A", "7F 22 31\r\r>"))
    }

    @Test
    fun area_emptyMeansNoMisfires() {
        // Car Scanner 10.11: FF/00/FE/00 → 10200 / 0 / 99.6 % / 0 %.
        val a = IgnitionMonitor.Area.of(0xFF, 0x00, 0xFE, 0x00)
        assertEquals(10200, a.rpmMin)
        assertEquals(0, a.rpmMax)
        assertEquals(99.6, a.loadMin, 0.05)
        assertTrue(a.empty)
        val b = IgnitionMonitor.Area.of(30, 60, 51, 102)
        assertEquals(1200, b.rpmMin)
        assertEquals(2400, b.rpmMax)
        assertEquals(20.0, b.loadMin, 0.01)
        assertEquals(false, b.empty)
    }

    @Test
    fun dtcs_canSingleFrame() {
        assertEquals(listOf("P0301", "P0304"), IgnitionMonitor.parseDtcs("43 02 03 01 03 04\r\r>", "03"))
        assertEquals(emptyList<String>(), IgnitionMonitor.parseDtcs("43 00\r\r>", "03"))
        assertEquals(listOf("P0300"), IgnitionMonitor.parseDtcs("4701 0300\r\r>", "07"))
        assertNull(IgnitionMonitor.parseDtcs("NO DATA\r\r>", "07"))
    }

    @Test
    fun dtcs_canMultiFrame() {
        // 4 кода: 43 04 + 8 байт = 10 байт — два кадра ISO-TP.
        val reply = "00A\r0: 43 04 03 02 01 71\r1: C1 23 03 03 00 00 00\r\r>"
        assertEquals(listOf("P0302", "P0171", "U0123", "P0303"), IgnitionMonitor.parseDtcs(reply, "03"))
    }

    @Test
    fun dtcLetters() {
        assertEquals("C0035", IgnitionMonitor.dtc(0x40, 0x35))
        assertEquals("B1234", IgnitionMonitor.dtc(0x92, 0x34))
        assertEquals(listOf(0, 2, 4), IgnitionMonitor.misfireCylinders(listOf("P0304", "P0171", "P0300", "P0302")))
    }

    @Test
    fun mode06_supportMask() {
        // A1..A5 → первый байт 1111 1000.
        assertEquals(setOf(0xA1, 0xA2, 0xA3, 0xA4, 0xA5), IgnitionMonitor.parseMode06Support("46 A0 F8 00 00 00\r>"))
        assertNull(IgnitionMonitor.parseMode06Support("NO DATA\r>"))
    }

    @Test
    fun mode06_misfireTests() {
        // MID A2: TID 0B (EWMA) = 3, TID 0C (текущий цикл) = 7, единица 24 (штуки).
        val reply = "013\r0: 46 A2 0B 24 00 03 00\r1: 00 FF FF A2 0C 24 00\r2: 07 00 00 FF FF 00 00\r\r>"
        val t = IgnitionMonitor.parseMode06(reply, 0xA2)
        assertNotNull(t)
        assertEquals(2, t!!.size)
        assertEquals(0x0B, t[0].tid)
        assertEquals(3, t[0].value)
        assertEquals(0xFFFF, t[0].max)
        assertEquals(0x0C, t[1].tid)
        assertEquals(7, t[1].value)
    }

    @Test
    fun row_roundTrip() {
        val r = row(1000L, 12345, 61.5, 2809, 1365, 1219, 3440)
        val back = IgnitionMonitor.decodeRow(IgnitionMonitor.encodeRow(r))!!
        assertEquals(1000L, back.ts)
        assertEquals(12345, back.odoKm)
        assertEquals(61.5, back.ecuMin, 0.01)
        assertArrayEquals(intArrayOf(2809, 1365, 1219, 3440), back.counters)
        assertNull(IgnitionMonitor.decodeRow("1000;;1.0;1;2;3"))
        assertEquals(null, IgnitionMonitor.decodeRow("1000;;1.0;1;2;3;4")!!.odoKm)
    }

    @Test
    fun growth_per1000km() {
        // 05.11 → 10.11 по логам, пробег условно 400 км.
        val rows = listOf(
            row(0, 10000, 0.0, 2730, 1323, 1185, 3378),
            row(2 * day, 10200, 100.0, 2770, 1340, 1200, 3410),
            row(5 * day, 10400, 200.0, 2809, 1365, 1219, 3440),
        )
        val g = IgnitionMonitor.growth(rows, 0)!!
        assertArrayEquals(intArrayOf(79, 42, 34, 62), g.perCyl)
        assertEquals(400, g.km)
        val per = g.per1000km()!!
        assertEquals(197.5, per[0], 0.01)
        assertNull(IgnitionMonitor.skewedCylinder(g.perCyl))
    }

    @Test
    fun growth_smallDistance_usesHours() {
        val rows = listOf(
            row(0, 100, 0.0, 10, 10, 10, 10),
            row(day, 120, 120.0, 12, 14, 10, 40),
        )
        val g = IgnitionMonitor.growth(rows, 0)!!
        assertNull(g.per1000km())
        val h = g.perHour()!!
        assertEquals(15.0, h[3], 0.01)
        assertEquals(3, IgnitionMonitor.skewedCylinder(g.perCyl))
    }

    @Test
    fun growth_counterReset_noNegative() {
        // Ошибки стёрли — ЭБУ обнулил счётчики: шаг сброса не считается.
        val rows = listOf(
            row(0, 0, 0.0, 500, 500, 500, 500),
            row(day, 100, 60.0, 0, 0, 0, 0),
            row(2 * day, 200, 120.0, 5, 3, 2, 30),
        )
        assertArrayEquals(intArrayOf(5, 3, 2, 30), IgnitionMonitor.growth(rows, 0)!!.perCyl)
    }

    @Test
    fun growth_windowAndTooFewRows() {
        val rows = listOf(row(0, 0, 0.0, 1, 1, 1, 1), row(10 * day, 500, 600.0, 9, 9, 9, 9))
        assertNull(IgnitionMonitor.growth(rows, day))
        assertNull(IgnitionMonitor.growth(emptyList(), 0))
    }

    @Test
    fun skew_thresholds() {
        assertNull(IgnitionMonitor.skewedCylinder(intArrayOf(0, 0, 0, 19)))
        assertEquals(3, IgnitionMonitor.skewedCylinder(intArrayOf(0, 0, 0, 20)))
        assertNull(IgnitionMonitor.skewedCylinder(intArrayOf(30, 30, 30, 50)))
        assertEquals(0, IgnitionMonitor.skewedCylinder(intArrayOf(60, 30, 30, 30)))
    }

    @Test
    fun daily_usesPreviousDayAsBase() {
        val rows = listOf(
            row(0, 0, 0.0, 10, 10, 10, 10),
            row(day + 1, 50, 30.0, 12, 10, 10, 11),
            row(day + 2, 60, 40.0, 13, 10, 10, 11),
        )
        val d = IgnitionMonitor.daily(rows) { it / day * day }
        assertEquals(1, d.size)
        assertEquals(day, d[0].first)
        assertArrayEquals(intArrayOf(3, 0, 0, 1), d[0].second.perCyl)
        assertEquals(60, d[0].second.km)
    }
}
