package com.dash.headunit.navi

import com.dash.headunit.navi.cluster.Guidance
import com.dash.headunit.navi.cluster.GuidanceText
import com.dash.headunit.navi.cluster.Maneuver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FreeDriveTest {

    @Test
    fun keepsOnlyWarningsAndNearCamera() {
        val g = Guidance(
            maneuver = Maneuver.LEFT, distance = "300 м", street = "Ленина", remainingTime = "5 мин",
            speedLimit = 60, cameraDistance = "350 м", cameraLimit = 60,
        ).freeDrive()!!
        assertNull(g.maneuver)
        assertNull(g.distance)
        assertNull(g.remainingTime)
        assertEquals(60, g.speedLimit)
        assertEquals("350 м", g.cameraDistance)
    }

    @Test
    fun farOrUnknownCameraIsDropped() {
        assertNull(Guidance(cameraDistance = "500 м", cameraLimit = 60).freeDrive())
        assertNull(Guidance(cameraDistance = "1,2 км").freeDrive())
        assertNull(Guidance(cameraDistance = "камера").freeDrive())
        assertEquals(60, Guidance(speedLimit = 60, cameraDistance = "800 м").freeDrive()!!.speedLimit)
        assertNull(Guidance(speedLimit = 60, cameraDistance = "800 м").freeDrive()!!.cameraDistance)
    }

    @Test
    fun currentStreetAlone() {
        assertEquals("Рижский просп.", Guidance(currentStreet = "Рижский просп.", remainingDistance = "5 км").freeDrive()!!.currentStreet)
        assertEquals("Рижский просп.", GuidanceText.currentStreet(" Рижский просп. "))
        assertNull(GuidanceText.currentStreet("Поиск GPS"))
        assertNull(GuidanceText.currentStreet(""))
    }

    @Test
    fun nothingToShow() {
        assertNull(Guidance(remainingDistance = "5 км").freeDrive())
    }
}
