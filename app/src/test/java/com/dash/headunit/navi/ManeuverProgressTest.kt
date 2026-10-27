package com.dash.headunit.navi

import com.dash.headunit.navi.cluster.ManeuverProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManeuverProgressTest {

    @Test
    fun countsDownFromFirstSeenDistance() {
        val p = ManeuverProgress()
        assertEquals(1f, p.update("LEFT|Ленина", 400)!!, 1e-6f)
        assertEquals(0.5f, p.update("LEFT|Ленина", 200)!!, 1e-6f)
        assertEquals(0f, p.update("LEFT|Ленина", 0)!!, 1e-6f)
    }

    @Test
    fun newManeuverOrLongerDistanceRestarts() {
        val p = ManeuverProgress()
        p.update("LEFT|Ленина", 400)
        p.update("LEFT|Ленина", 100)
        assertEquals(1f, p.update("RIGHT|Мира", 1200)!!, 1e-6f)
        assertEquals(1f, p.update("RIGHT|Мира", 1500)!!, 1e-6f)
        assertEquals(0.5f, p.update("RIGHT|Мира", 750)!!, 1e-6f)
    }

    @Test
    fun unknownDistanceGivesNoBar() {
        val p = ManeuverProgress()
        assertNull(p.update("LEFT|Ленина", null))
        assertNull(p.update("LEFT|Ленина", 0))
    }
}
