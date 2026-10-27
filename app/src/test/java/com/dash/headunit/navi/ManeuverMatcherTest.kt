package com.dash.headunit.navi

import com.dash.headunit.navi.cluster.ManeuverMatcher
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManeuverMatcherTest {

    private val white = 0xffffffff.toInt()
    private val blue = 0xff1669fa.toInt()

    /** Стрелка-«Г» (вверх и налево) размером [size] на синем поле с отступом. */
    private fun hook(size: Int, mirror: Boolean = false): Pair<IntArray, Int> {
        val pad = 4
        val w = size + pad * 2
        val px = IntArray(w * w) { blue }
        val t = maxOf(2, size / 5)
        for (y in 0 until size) for (x in 0 until size) {
            val ink = (x in size - t until size) || (y < t)
            val xx = if (mirror) size - 1 - x else x
            if (ink) px[(y + pad) * w + xx + pad] = white
        }
        return px to w
    }

    private fun mask(size: Int, mirror: Boolean = false): FloatArray {
        val (px, w) = hook(size, mirror)
        return ManeuverMatcher.mask(px, w, w, ManeuverMatcher::whiteInk)!!
    }

    @Test
    fun sameShapeAnySize() {
        // Мелкая стрелка (меньше маски) не должна давать дырявую маску.
        assertTrue(ManeuverMatcher.score(mask(16), mask(96)) < 0.08f)
        assertTrue(ManeuverMatcher.score(mask(24), mask(64)) < 0.08f)
    }

    @Test
    fun mirroredShapeDiffers() {
        assertTrue(ManeuverMatcher.score(mask(32), mask(32, mirror = true)) > 0.2f)
    }

    @Test
    fun blueOnlyHasNoArrow() {
        val px = IntArray(40 * 40) { blue }
        assertNull(ManeuverMatcher.mask(px, 40, 40, ManeuverMatcher::whiteInk))
        assertNotNull(ManeuverMatcher.mask(hook(20).first, 28, 28, ManeuverMatcher::whiteInk))
    }

    @Test
    fun inkFunctions() {
        assertTrue(ManeuverMatcher.whiteInk(white) > 0.99f)
        assertTrue(ManeuverMatcher.whiteInk(blue) < 0.01f)
        assertTrue(ManeuverMatcher.alphaInk(0x00ffffff) < 0.01f)
    }
}
