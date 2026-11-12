package com.dash.headunit.car

import com.dash.headunit.car.HuBrightness.Saved
import com.dash.headunit.car.HuBrightness.State
import com.dash.headunit.screensaver.Screensaver
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HuBrightnessTest {

    @Test
    fun parseDump() {
        // Дамп ГУ: режим 0, день, день=ночь=10.
        val s = State.parse(intArrayOf(0, 0, 9, 9))!!
        assertEquals(10, s.current)
        assertNull(State.parse(intArrayOf(0, 0, 9)))
        assertNull(State.parse(null))
    }

    @Test
    fun dayWriteLikeStockSlider() {
        val s = State(mode = 0, dayNight = 0, dayLevel = 8, nightLevel = 4)
        assertEquals(0, s.setArea)
        assertArrayEquals(intArrayOf(2, 3), s.previewData(3))
        assertArrayEquals(intArrayOf(2, 3), s.setData(3))
    }

    @Test
    fun nightWrite() {
        val s = State(mode = 1, dayNight = 1, dayLevel = 8, nightLevel = 4)
        assertEquals(2, s.setArea)
        assertEquals(4, s.current)
        assertArrayEquals(intArrayOf(7, 1), s.setData(2))
        // Режим 0: area = значение день/ночь как есть.
        assertEquals(3, State(0, 3, 5, 5).setArea)
        assertEquals(1, State(2, 0, 5, 5).setArea)
    }

    @Test
    fun clampsLevel() {
        val s = State(0, 0, 5, 5)
        assertArrayEquals(intArrayOf(0, 4), s.setData(0))
        assertArrayEquals(intArrayOf(9, 4), s.setData(42))
    }

    @Test
    fun savedRoundTrip() {
        val v = Saved(day = 7, nightLevel = 3, night = true, set = 2)
        assertEquals(v, Saved.parse(v.encode()))
        assertNull(Saved.parse("1,2"))
        assertNull(Saved.parse(null))
    }

    @Test
    fun screensaverLevelMigration() {
        assertEquals(4, Screensaver.levelOf("4", "80"))
        assertEquals(2, Screensaver.levelOf("", "20"))
        assertEquals(1, Screensaver.levelOf("", "0"))
        assertEquals(10, Screensaver.levelOf("", "100"))
        assertEquals(Screensaver.DEFAULT_LEVEL, Screensaver.levelOf("", ""))
    }
}
