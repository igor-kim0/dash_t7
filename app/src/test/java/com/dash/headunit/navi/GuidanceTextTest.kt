package com.dash.headunit.navi

import com.dash.headunit.navi.cluster.GuidanceText
import com.dash.headunit.navi.cluster.Maneuver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidanceTextTest {

    @Test
    fun maneuverPhrases() {
        assertEquals(Maneuver.RIGHT, GuidanceText.classify("Поверните направо"))
        assertEquals(Maneuver.LEFT, GuidanceText.classify("Через 300 метров поверните налево"))
        assertEquals(Maneuver.KEEP_LEFT, GuidanceText.classify("Держитесь левее"))
        assertEquals(Maneuver.SLIGHT_RIGHT, GuidanceText.classify("Плавно направо"))
        assertEquals(Maneuver.SHARP_LEFT, GuidanceText.classify("Резко налево"))
        assertEquals(Maneuver.UTURN_LEFT, GuidanceText.classify("Развернитесь"))
        assertEquals(Maneuver.ROUNDABOUT, GuidanceText.classify("На круговом движении второй съезд"))
        assertEquals(Maneuver.STRAIGHT, GuidanceText.classify("Прямо"))
        assertEquals(Maneuver.FINISH, GuidanceText.classify("Вы приехали"))
        assertNull(GuidanceText.classify("ул. Ленина"))
        assertNull(GuidanceText.classify(null))
    }

    @Test
    fun distanceAndClock() {
        assertTrue(GuidanceText.isDistance("300 м"))
        assertTrue(GuidanceText.isDistance("1,2 км"))
        assertTrue(GuidanceText.isDistance("800m"))
        assertFalse(GuidanceText.isDistance("ул. Ленина"))
        assertTrue(GuidanceText.isClock("14:35"))
        assertFalse(GuidanceText.isClock("18 мин"))
    }

    @Test
    fun speedLimitOnlyRealSigns() {
        assertEquals(60, GuidanceText.speedLimit("60"))
        assertEquals(110, GuidanceText.speedLimit("110 км/ч"))
        assertNull(GuidanceText.speedLimit("63"))
        assertNull(GuidanceText.speedLimit("0"))
        assertNull(GuidanceText.speedLimit("—"))
    }

    @Test
    fun naviAreaRequestFromMeter() {
        assertEquals(1, GuidanceText.naviAreaRequest(mapOf("RequestNaviAreaDisplay" to 1)))
        assertEquals(0, GuidanceText.naviAreaRequest(mapOf("map_status" to "0")))
        assertEquals(1, GuidanceText.naviAreaRequest(mapOf("data" to "{\"RequestNaviAreaDisplay\": 1}")))
        assertEquals(0, GuidanceText.naviAreaRequest(mapOf("data" to "{\"RequestNaviAreaDisplay\":\"0\"}")))
        assertNull(GuidanceText.naviAreaRequest(mapOf("speed" to 40)))
    }

    @Test
    fun metersAndSeconds() {
        assertEquals(300, GuidanceText.meters("300 м"))
        assertEquals(1200, GuidanceText.meters("1,2 км"))
        assertEquals(1500, GuidanceText.meters("1.5\u00A0km"))
        assertEquals(680, GuidanceText.meters("680m"))
        assertNull(GuidanceText.meters("5 min"))
        assertNull(GuidanceText.meters(null))
        assertEquals(18 * 60, GuidanceText.seconds("18 мин"))
        assertEquals(65 * 60, GuidanceText.seconds("1 ч 5 мин"))
        assertEquals(2 * 60, GuidanceText.seconds("2 min"))
        assertNull(GuidanceText.seconds("14:35"))
    }
}
