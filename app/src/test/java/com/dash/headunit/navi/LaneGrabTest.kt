package com.dash.headunit.navi

import com.dash.headunit.navi.cluster.LaneGrab
import com.dash.headunit.navi.cluster.LaneGrab.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LaneGrabTest {

    private val w = 600
    private val h = 400
    private val map = 0xFF2E7D32.toInt()      // зелёная карта
    private val route = 0xFF5AB4FF.toInt()    // линия маршрута — светлее балуна
    private val balloon = 0xFF1666FE.toInt()
    private val white = 0xFFFFFFFF.toInt()

    private fun canvas() = IntArray(w * h) { map }

    private fun IntArray.fill(b: Box, c: Int) {
        for (y in b.t until b.b) for (x in b.l until b.r) this[y * w + x] = c
    }

    /** Плашка полос: синий прямоугольник с белыми «стрелками»-столбиками. */
    private fun IntArray.lanes(b: Box) {
        fill(b, balloon)
        val n = 3
        for (i in 0 until n) {
            val cx = b.l + (i + 1) * b.w / (n + 1)
            fill(Box(cx - 3, b.t + 8, cx + 3, b.b - 8), white)
        }
    }

    @Test
    fun findsLanesOnMapNotCardNorRoute() {
        val px = canvas()
        px.fill(Box(290, 0, 310, h), route)
        val card = Box(10, 10, 160, 90)
        px.lanes(card)                        // карточка того же цвета — исключена
        px.lanes(Box(250, 180, 350, 240))     // плашка на маршруте
        val found = LaneGrab.find(px, w, h, card)!!
        assertEquals(Box(250, 180, 350, 240), found)
    }

    @Test
    fun plainBlueBlockWithoutArrowsIsNotLanes() {
        val px = canvas()
        px.fill(Box(250, 180, 350, 240), balloon)
        assertNull(LaneGrab.find(px, w, h, null))
    }

    @Test
    fun nothingOnPlainMap() {
        val px = canvas()
        px.fill(Box(290, 0, 310, h), route)
        assertNull(LaneGrab.find(px, w, h, null))
    }
}
