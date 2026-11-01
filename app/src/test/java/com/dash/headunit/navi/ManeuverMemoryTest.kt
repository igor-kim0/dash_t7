package com.dash.headunit.navi

import com.dash.headunit.navi.cluster.Guidance
import com.dash.headunit.navi.cluster.GuidanceText
import com.dash.headunit.navi.cluster.Maneuver
import com.dash.headunit.navi.cluster.ManeuverMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManeuverMemoryTest {

    @Test
    fun abbreviatedStreetIsSame() {
        assertTrue(GuidanceText.sameStreet("Рижский проспект", "Рижский просп."))
        assertTrue(GuidanceText.sameStreet("ул. Ленина", "улица Ленина"))
        assertTrue(GuidanceText.sameStreet(null, null))
        assertFalse(GuidanceText.sameStreet("Рижский проспект", "Невский проспект"))
        assertFalse(GuidanceText.sameStreet("Ленина", null))
    }

    @Test
    fun keepsArrowFromNotificationWhenWindowHasNone() {
        // ГУ 2026-10-31: уведомление «LEFT 540 м Рижский проспект» → окно «550 м Рижский просп.» без стрелки.
        val mem = ManeuverMemory()
        mem.apply(Guidance(maneuver = Maneuver.LEFT, distance = "540 м", street = "Рижский проспект"))
        val g = mem.apply(Guidance(distance = "550 м", street = "Рижский просп."))
        assertEquals(Maneuver.LEFT, g!!.maneuver)
    }

    @Test
    fun newStreetOrGrownDistanceDropsArrow() {
        val mem = ManeuverMemory()
        mem.apply(Guidance(maneuver = Maneuver.LEFT, distance = "300 м", street = "Ленина"))
        assertNull(mem.apply(Guidance(distance = "800 м", street = "Мира"))!!.maneuver)
        // Забыто — к старой улице не возвращается.
        assertNull(mem.apply(Guidance(distance = "200 м", street = "Ленина"))!!.maneuver)

        mem.apply(Guidance(maneuver = Maneuver.RIGHT, distance = "100 м", street = "Ленина"))
        assertNull(mem.apply(Guidance(distance = "1,2 км", street = "Ленина"))!!.maneuver)
    }

    @Test
    fun ownManeuverWinsAndRouteEndForgets() {
        val mem = ManeuverMemory()
        mem.apply(Guidance(maneuver = Maneuver.LEFT, distance = "300 м", street = "Ленина"))
        assertEquals(Maneuver.RIGHT, mem.apply(Guidance(maneuver = Maneuver.RIGHT, distance = "250 м", street = "Ленина"))!!.maneuver)
        assertNull(mem.apply(null))
        assertNull(mem.apply(Guidance(distance = "200 м", street = "Ленина"))!!.maneuver)
    }
}
