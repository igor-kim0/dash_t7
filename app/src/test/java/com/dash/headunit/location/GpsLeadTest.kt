package com.dash.headunit.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsLeadTest {

    @Test
    fun distanceIsSpeedTimesTime() {
        // 60 км/ч, опережение 1 с, фикс свежий → 16,7 м.
        assertEquals(16.67, GpsLead.distance(60f / 3.6f, 1_000, 0), 0.05)
        // Через 0,5 с после фикса — ещё 8,3 м.
        assertEquals(25.0, GpsLead.distance(60f / 3.6f, 1_000, 500), 0.05)
        // Возраст фикса ограничен.
        assertEquals(
            GpsLead.distance(10f, 0, GpsLead.MAX_EXTRAPOLATE_MS),
            GpsLead.distance(10f, 0, 60_000), 1e-9
        )
    }

    @Test
    fun noShiftWhenSlowOrNoLead() {
        assertEquals(0.0, GpsLead.distance(1f, 1_000, 0), 0.0)
        assertEquals(0.0, GpsLead.distance(20f, 0, 0), 0.0)
    }

    @Test
    fun projectNorthAndEast() {
        val (lat, lon) = GpsLead.project(57.0, 28.0, 0f, 111.2)
        assertEquals(57.001, lat, 1e-5)
        assertEquals(28.0, lon, 1e-9)
        val (lat2, lon2) = GpsLead.project(57.0, 28.0, 90f, 100.0)
        assertEquals(57.0, lat2, 1e-5)
        assertTrue(lon2 > 28.0)
        // Обратный курс — назад к началу.
        val back = GpsLead.bearing(lat2, lon2, 57.0, 28.0)
        assertEquals(270f, back, 0.1f)
    }

    @Test
    fun lagFoundFromSpeedCurves() {
        // Машина разгоняется 0→80 и тормозит; GPS отстаёт на 800 мс.
        fun v(t: Long): Float {
            val s = t / 1000f
            return when {
                s < 10 -> s * 8f
                s < 20 -> 80f
                s < 30 -> 80f - (s - 20) * 6f
                else -> 20f
            }
        }
        val car = (0L..40_000L step 100).map { it to v(it) }
        val gps = (1_000L..40_000L step 1_000).map { it to v(it - 800) }
        assertEquals(800L, GpsLead.estimateLag(gps, car))
    }

    @Test
    fun lagUnknownWithoutSpeedChange() {
        val car = (0L..40_000L step 100).map { it to 50f }
        val gps = (1_000L..40_000L step 1_000).map { it to 50f }
        assertNull(GpsLead.estimateLag(gps, car))
        assertNotNull(car)
    }
}
