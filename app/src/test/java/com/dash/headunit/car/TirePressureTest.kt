package com.dash.headunit.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TirePressureTest {

    @Test
    fun wheel_byLatinName() {
        assertEquals(Wheel.FL, wheelByName("Tire FL"))
        assertEquals(Wheel.FR, wheelByName("Tire FR"))
        assertEquals(Wheel.RL, wheelByName("Tire RL"))
        assertEquals(Wheel.RR, wheelByName("Tire RR"))
        assertEquals(Wheel.FL, wheelByName("TPMS FL"))
    }

    @Test
    fun wheel_byRussianName() {
        assertEquals(Wheel.FL, wheelByName("Давление в шине Передняя Левая"))
        assertEquals(Wheel.FR, wheelByName("Давление в шине Передняя Правая"))
        assertEquals(Wheel.RL, wheelByName("Давление в шине Задняя Левая"))
        assertEquals(Wheel.RR, wheelByName("Давление в шине Задняя Правая"))
    }

    @Test
    fun wheel_unknown() {
        assertNull(wheelByName("ID датчика шины"))
        assertNull(wheelByName("Обороты двигателя"))
    }

    @Test
    fun temperatureAndSensorNotPressure() {
        // Колесо в названии есть, но единица — не давление: такой параметр
        // отбрасывается по единице измерения.
        assertEquals(Wheel.FL, wheelByName("Температура шины FL T"))
        assertTrue(!isPressureUnit("°C"))
        assertTrue(!isPressureUnit(""))
    }

    @Test
    fun unit_toBar() {
        assertEquals(2.15f, toBar(215f, "кПа")!!, 0.001f)
        assertEquals(2.3f, toBar(2.3f, "bar")!!, 0.001f)
        assertEquals(2.3f, toBar(2.3f, "бар")!!, 0.001f)
        assertEquals(2.206f, toBar(32f, "psi")!!, 0.001f)
        assertEquals(23f, toBar(2.3f, "MPa")!!, 0.001f)
    }

    @Test
    fun unit_notPressure() {
        assertNull(toBar(12f, "°C"))
        assertNull(toBar(1f, ""))
        assertTrue(!isPressureUnit("°C"))
        assertTrue(isPressureUnit("кПа"))
    }

    @Test
    fun pressures_belowThreshold() {
        val p = TirePressures(fl = 2.4f, fr = 1.6f, rl = 2.3f, rr = null)
        val low = p.below(2f)
        assertEquals(1, low.size)
        assertEquals(Wheel.FR, low[0].first)
        assertEquals(1.6f, low[0].second, 0.001f)
        assertEquals(1.6f, p.min()!!, 0.001f)
    }

    @Test
    fun pressures_allOk() {
        val p = TirePressures(fl = 2.4f, fr = 2.2f, rl = 2.3f, rr = 2.1f)
        assertTrue(p.below(2f).isEmpty())
        assertTrue(!p.isEmpty)
    }

    @Test
    fun pressures_empty() {
        val p = TirePressures()
        assertTrue(p.isEmpty)
        assertNull(p.min())
        assertTrue(p.below(2f).isEmpty())
    }

    @Test
    fun raw_calibrationPoint() {
        // 2026-11-14: сырые 125,128,126,123 — приборка FL 2,3, остальные 2,2.
        val t = tiresFromRaw(intArrayOf(125, 128, 126, 123))!!
        assertEquals(2.3f, t.fl!!, 0.05f)
        assertEquals(2.2f, t.fr!!, 0.05f)
        assertEquals(2.2f, t.rl!!, 0.05f)
        assertEquals(2.2f, t.rr!!, 0.05f)
        // 2026-10-14: сырые 112 и 115 — оба 2,0 на приборке.
        assertEquals(2.0f, tireBarFromRaw(112)!!, 0.05f)
        assertEquals(2.0f, tireBarFromRaw(115)!!, 0.05f)
    }

    @Test
    fun raw_noData() {
        assertNull(tireBarFromRaw(0))
        assertNull(tireBarFromRaw(255))
        assertNull(tiresFromRaw(intArrayOf(0, 0, 0, 0)))
        assertNull(tiresFromRaw(intArrayOf(115, 112)))
        assertNull(tiresFromRaw(null))
        val partial = tiresFromRaw(intArrayOf(115, 0, 123, 124))!!
        assertNull(partial.fl)
        assertEquals(2.02f, partial.fr!!, 0.01f)
    }

    @Test
    fun a005_frame() {
        val p = ClusterParser()
        val frame = IntArray(52).also {
            it[2] = 1; it[3] = 86; it[4] = 121; it[5] = 116; it[6] = 129; it[7] = 130
        }
        p.onFrame("a005", frame, 1000L)
        assertEquals(listOf(121, 116, 129, 130), p.tireRaw(1000L)!!.toList())
        assertTrue(p.tires(1000L)!!.fl!! > 2f)
        // b2 = 252 — TPMS без данных: прежние значения не затираются.
        p.onFrame("a005", IntArray(52).also { it[2] = 252 }, 2000L)
        assertEquals(121, p.tireRaw(2000L)!![0])
    }
}
