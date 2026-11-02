package com.dash.headunit.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrumBandsTest {

    private val size = 1024
    private val rate = 44100

    @Test
    fun silenceGivesZeroBands() {
        val bands = SpectrumHub.bandsFromFft(ByteArray(size), rate, SpectrumHub.BANDS)
        assertTrue(bands.all { it == 0f })
    }

    @Test
    fun toneLandsInMatchingBand() {
        val fft = ByteArray(size)
        val k = Math.round(1000.0 * size / rate).toInt() // бин 1 кГц
        fft[2 * k] = 100
        fft[2 * k + 1] = 0
        val bands = SpectrumHub.bandsFromFft(fft, rate, SpectrumHub.BANDS)
        val loudest = bands.indices.maxByOrNull { bands[it] }!!
        // Лог-сетка 40 Гц..16 кГц: 1 кГц ≈ ln(25)/ln(400) ≈ 0.537 → полоса 17.
        assertEquals(17.0, loudest.toDouble(), 1.0)
        assertTrue(bands[loudest] > 0.8f)
        assertEquals(0f, bands[2])
    }

    @Test
    fun simulationStaysInRange() {
        val out = FloatArray(SpectrumHub.BANDS)
        for (t in 0 until 200) {
            SpectrumHub.simulate(t * 0.033, out)
            assertTrue(out.all { it in 0f..1f })
        }
    }
}
