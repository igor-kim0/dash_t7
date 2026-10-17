package com.dash.headunit.car

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClusterParserTest {

    private fun frame(vararg bytes: Int) = "{\"len\":${bytes.size},\"data\":[${bytes.joinToString(",")}]}"

    /** Реальные кадры с ГУ T18FL3 (лог 2026-10-13, в движении). */
    private val a000 = frame(
        244, 4, 244, 4, 38, 0, 38, 0, 244, 1, 244, 1, 227, 2, 227, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 81, 34
    )
    private val a5a5 = frame(
        135, 194, 137, 0, 29, 0, 252, 7, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        37, 70, 1, 75, 238, 255, 6, 0, 210, 0, 28, 3, 166, 2, 80, 195, 123, 192, 68, 112,
        9, 2, 9, 2, 170, 85, 252, 0, 73, 78
    )

    /** a0ff: b14|b15, °C = raw × 0.75 − 48. */
    private fun a0ff(raw: Int) = frame(*IntArray(14) + intArrayOf(raw and 0xFF, raw shr 8))

    @Test
    fun parsesDataArray() {
        assertArrayEquals(intArrayOf(1, 255, 0), ClusterParser.parseData("{\"data\": [1, -1, 256]}"))
        assertNull(ClusterParser.parseData("{\"x\":[1]}"))
        assertNull(ClusterParser.parseData("{\"data\":[1,z]}"))
    }

    @Test
    fun a000GivesNeedlesSpeedRpm() {
        val p = ClusterParser()
        p.onFrame("a000", a000, 0)
        assertEquals(0.5f, p.needle(0)!!, 0.001f)
        assertEquals(0.739f, p.fuelNeedle(0)!!, 0.001f)
        assertEquals(38, p.speedKmh(0))
        assertEquals(1268, p.rpm(0))
    }

    @Test
    fun a000NeedleCopyIsNotCoolant() {
        // На T19 b10|b11 была «цифра» ОЖ; на T18FL3 там копия стрелки (500).
        val p = ClusterParser()
        p.onFrame("a000", a000, 0)
        assertNull(p.coolant(0))
    }

    @Test
    fun a5a5IsNotCoolantButGivesSpeedRpm() {
        val p = ClusterParser()
        p.onFrame("a000", a000, 0)
        p.onFrame("a5a5", a5a5, 0)
        assertNull(p.coolant(0))
        assertEquals(29, p.speedKmh(0))
        assertEquals(2044, p.rpm(0))
    }

    @Test
    fun a5a5HasPriorityOverA000WhileFresh() {
        val p = ClusterParser()
        p.onFrame("a5a5", a5a5, 0)
        p.onFrame("a000", a000, 10_000)
        assertEquals(29, p.speedKmh(10_000))
        // a5a5 пропал — берём a000.
        p.onFrame("a000", a000, 40_000)
        assertEquals(38, p.speedKmh(40_000))
    }

    @Test
    fun a5a5WithoutMarkerIgnored() {
        val p = ClusterParser()
        p.onFrame("a5a5", frame(0, 0, 184, 0, 50, 0), 0)
        assertNull(p.speedKmh(0))
    }

    @Test
    fun speedAndRpmExpireFast() {
        val p = ClusterParser()
        p.onFrame("a5a5", a5a5, 0)
        assertNull(p.speedKmh(30_000))
        assertNull(p.rpm(30_000))
    }

    @Test
    fun a0ffGivesCoolant() {
        val p = ClusterParser()
        p.onFrame("a0ff", a0ff(184), 0) // 184 × 0.75 − 48 = 90
        assertEquals(90f, p.coolant(0)!!, 0.01f)
        assertNull(p.coolant(15 * 60_000L))
    }

    @Test
    fun a0ffNoDataMarkersIgnored() {
        val p = ClusterParser()
        for (raw in listOf(0, 255, 65535)) p.onFrame("a0ff", a0ff(raw), 0)
        assertNull(p.coolant(0))
    }

    /** a001 с ГУ T18FL3 (запись 2026-10-14 07:34, приборка: пробег 5,1, расход 9,4). */
    private val a001 = frame(
        57, 76, 0, 0, 51, 0, 0, 0, 190, 196, 0, 0, 255, 255, 0, 0, 0, 0, 0, 0, 94, 0, 208, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 79, 0, 148, 0, 0, 0, 0, 0, 0, 0, 0, 0, 225, 1, 0, 34, 96, 0, 101, 19, 88, 12
    )

    /** a001 сразу после включения зажигания: расход/скорость 0xFFFF, запас 0xF000. */
    private val a001AtStart = frame(
        52, 76, 0, 0, 0, 0, 0, 0, 139, 196, 0, 0, 255, 255, 0, 0, 0, 0, 0, 0, 255, 255, 255, 255,
        0, 0, 0, 0, 0, 0, 0, 0, 79, 0, 148, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 240, 0, 34, 0, 0, 0, 0, 0, 62
    )

    @Test
    fun a001GivesTripComputer() {
        val p = ClusterParser()
        p.onFrame("a001", a001, 0)
        val t = p.trip(0)!!
        assertEquals(19513L, t.odometerKm)
        assertEquals(5.1f, t.tripKm!!, 0.001f)
        assertEquals(5036.6f, t.tripSinceResetKm!!, 0.01f)
        assertEquals(9.4f, t.avgFuelL100!!, 0.001f)
        assertEquals(20.8f, t.avgSpeedKmh!!, 0.001f)
        assertEquals(481, t.rangeKm)
        assertNull(p.trip(15 * 60_000L))
    }

    @Test
    fun a001NoDataMarkersIgnored() {
        val p = ClusterParser()
        p.onFrame("a001", a001AtStart, 0)
        val t = p.trip(0)!!
        assertEquals(19508L, t.odometerKm)
        assertEquals(0f, t.tripKm!!, 0.001f)
        assertNull(t.avgFuelL100)
        assertNull(t.avgSpeedKmh)
        assertNull(t.rangeKm)
    }

    @Test
    fun a001ShortFrameIgnored() {
        val p = ClusterParser()
        p.onFrame("a001", frame(57, 76, 0, 0), 0)
        assertNull(p.trip(0))
    }
}
