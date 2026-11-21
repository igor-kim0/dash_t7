package com.dash.headunit.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NmeaTest {

    private fun withSum(body: String): String {
        var s = 0
        body.forEach { s = s xor it.code }
        return "$" + body + "*" + "%02X".format(s)
    }

    @Test
    fun rmc() {
        val r = Nmea.parse(withSum("GNRMC,123519.50,A,5740.3492,N,02809.8195,E,10.0,84.4,230394,,,A")) as Nmea.Rmc
        assertTrue(r.valid)
        assertEquals(57.672487, r.lat, 1e-6)
        assertEquals(28.163658, r.lon, 1e-6)
        assertEquals(5.144f, r.speedMps!!, 0.01f)
        assertEquals(84.4f, r.bearing!!, 0.01f)
        assertEquals(12 * 3600_000L + 35 * 60_000L + 19_500L, r.utcDayMs)
    }

    @Test
    fun rmcSouthWestAndVoid() {
        val r = Nmea.parse(withSum("GPRMC,000000,V,3345.000,S,07030.000,W,,,010120,,")) as Nmea.Rmc
        assertFalse(r.valid)
        assertEquals(-33.75, r.lat, 1e-9)
        assertEquals(-70.5, r.lon, 1e-9)
        assertNull(r.speedMps)
        assertNull(r.bearing)
    }

    @Test
    fun gga() {
        val g = Nmea.parse(withSum("GPGGA,123519,5740.349,N,02809.819,E,1,08,0.9,45.1,M,46.9,M,,")) as Nmea.Gga
        assertEquals(1, g.quality)
        assertEquals(8, g.satellites)
        assertEquals(0.9f, g.hdop!!, 1e-6f)
        assertEquals(45.1, g.altitude!!, 1e-9)
    }

    @Test
    fun badChecksumAndJunk() {
        assertNull(Nmea.parse("\$GPRMC,123519,A,5740.349,N,02809.819,E,10.0,84.4,230394,,*00"))
        assertNull(Nmea.parse("garbage"))
        assertNull(Nmea.parse(withSum("GPGSV,3,1,11,03,03,111,00")))
        assertNull(Nmea.parse(withSum("GPRMC,123519,A,,N,,E,,,230394,,")))
    }
}
