package com.dash.headunit.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NeedleCalibrationTest {

    private val factory = { n: Float -> 1000f + n }

    @Test
    fun emptyTableUsesFallback() {
        assertEquals(1000.5f, NeedleCalibration().estimate(0.5f, factory), 0.001f)
    }

    @Test
    fun interpolatesBetweenLearnedBins() {
        val t = NeedleCalibration()
        repeat(5) { t.add(0.25f, 60f) }
        repeat(5) { t.add(0.5f, 90f) }
        assertEquals(60f, t.estimate(0.25f, factory), 0.01f)
        assertEquals(90f, t.estimate(0.5f, factory), 0.01f)
        assertEquals(75f, t.estimate(0.375f, factory), 0.01f)
    }

    @Test
    fun belowAndAboveRangeClampFallbackToLearnedEdges() {
        val t = NeedleCalibration()
        repeat(5) { t.add(0.5f, 90f) }
        val below = NeedleCalibration().apply { repeat(5) { add(0.5f, 90f) } }
        // Заводская кривая «горячее» выученного края — снизу не выше края.
        assertEquals(90f, below.estimate(0.1f) { 95f }, 0.01f)
        assertEquals(50f, t.estimate(0.1f) { 50f }, 0.01f)
        // Сверху — не холоднее края.
        assertEquals(90f, t.estimate(0.9f) { 80f }, 0.01f)
        assertEquals(120f, t.estimate(0.9f) { 120f }, 0.01f)
    }

    @Test
    fun fewSamplesAreNotTrusted() {
        val t = NeedleCalibration()
        repeat(NeedleCalibration.MIN_SAMPLES - 1) { t.add(0.5f, 90f) }
        assertEquals(1000.5f, t.estimate(0.5f, factory), 0.001f)
    }

    @Test
    fun monotonicAcrossNoisyBins() {
        val t = NeedleCalibration()
        repeat(5) { t.add(0.45f, 88f) }
        repeat(5) { t.add(0.475f, 85f) }
        repeat(5) { t.add(0.5f, 92f) }
        assertEquals(88f, t.estimate(0.475f, factory), 0.01f)
    }

    @Test
    fun rejectsZeroNeedleAndBadTemps() {
        val t = NeedleCalibration()
        assertFalse(t.add(0f, 30f))
        assertFalse(t.add(0.5f, 200f))
        assertFalse(t.add(0.5f, Float.NaN))
    }

    @Test
    fun saveLoadRoundTrip() {
        val t = NeedleCalibration()
        repeat(4) { t.add(0.5f, 91f) }
        repeat(3) { t.add(0.3f, 70f) }
        val copy = NeedleCalibration().apply { load(t.save()) }
        assertEquals(t.save(), copy.save())
        assertEquals(t.estimate(0.4f, factory), copy.estimate(0.4f, factory), 0.001f)
        copy.load("мусор;1:x:2;99:1:1")
        assertEquals("не выучено", copy.describe())
    }
}
