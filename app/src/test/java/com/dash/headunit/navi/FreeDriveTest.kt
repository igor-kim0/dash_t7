package com.dash.headunit.navi

import com.dash.headunit.navi.cluster.Guidance
import com.dash.headunit.navi.cluster.Maneuver
import com.dash.headunit.navi.cluster.TrafficLight
import com.dash.headunit.navi.cluster.TrafficLightGrab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FreeDriveTest {

    @Test
    fun keepsOnlyWarningsAndNearCamera() {
        val g = Guidance(
            maneuver = Maneuver.LEFT, distance = "300 м", street = "Ленина", remainingTime = "5 мин",
            speedLimit = 60, cameraDistance = "350 м", cameraLimit = 60, trafficLight = TrafficLight.RED,
        ).freeDrive()!!
        assertNull(g.maneuver)
        assertNull(g.distance)
        assertNull(g.remainingTime)
        assertEquals(60, g.speedLimit)
        assertEquals("350 м", g.cameraDistance)
        assertEquals(TrafficLight.RED, g.trafficLight)
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
    fun nothingToShow() {
        assertNull(Guidance(remainingDistance = "5 км").freeDrive())
    }

    private fun px(rgb: Int, n: Int) = IntArray(n) { 0xFF000000.toInt() or rgb }

    @Test
    fun trafficLightColorsOfYandex() {
        // Кнопка: тёмный фон + лампа цвета Яндекса.
        fun button(lamp: Int) = px(0x2A2E36, 900) + px(lamp, 100)
        assertEquals(TrafficLight.RED, TrafficLightGrab.classify(button(0xE55245)).light)
        assertEquals(TrafficLight.YELLOW, TrafficLightGrab.classify(button(0xFFDF61)).light)
        assertEquals(TrafficLight.GREEN, TrafficLightGrab.classify(button(0x85CC3D)).light)
    }

    @Test
    fun greyOrWhiteButtonIsNoLight() {
        assertNull(TrafficLightGrab.classify(px(0x2A2E36, 900) + px(0x646670, 100)).light)
        assertNull(TrafficLightGrab.classify(px(0xFFFFFF, 1000)).light)
        assertNull(TrafficLightGrab.classify(IntArray(0)).light)
    }
}
