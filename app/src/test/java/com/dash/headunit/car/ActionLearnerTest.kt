package com.dash.headunit.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionLearnerTest {

    private fun k(g: Int, id: Int) = g * 256 + id

    /** Фон: стекло водителя закрыто (0,100 = 2), обороты гуляют. */
    private val base = mapOf(k(0, 100) to "2", k(0, 101) to "2", k(4, 62) to "0,184", k(0, 7) to "0")

    @Test
    fun stateCellFoundNoiseIgnored() {
        // Срез с ГУ 12.10: при опускании стекла водителя 0,100: 2 → 4.
        val timeline = listOf(
            500L to base + (k(4, 62) to "0,190"),
            2000L to base + mapOf(k(0, 100) to "4", k(0, 7) to "1"),
            3500L to base + mapOf(k(0, 100) to "4", k(0, 7) to "0"),
        )
        val r = ActionLearner.analyze(base, setOf(k(4, 62)), timeline)
        assertEquals(listOf("0,100", "0,7"), r.map { it.cell })
        val win = r[0]
        assertFalse(win.pulse)
        assertEquals("2", win.before)
        assertEquals("4", win.after)
        assertEquals(2000L, win.firstMs)
        // Кнопку держали — импульс 0 → 1 → 0.
        val btn = r[1]
        assertTrue(btn.pulse)
        assertEquals("1", btn.pulseValue)
    }

    @Test
    fun nothingChanged() {
        assertTrue(ActionLearner.analyze(base, emptySet(), listOf(1000L to base)).isEmpty())
    }

    @Test
    fun valueToInt() {
        assertEquals(4, ActionLearner.toInt("4"))
        assertEquals(313, ActionLearner.toInt("1,57"))
    }
}
