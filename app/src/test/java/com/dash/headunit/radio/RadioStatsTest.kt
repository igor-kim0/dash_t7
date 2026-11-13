package com.dash.headunit.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class RadioStatsTest {

    private val zone = TimeZone.getTimeZone("Europe/Moscow")

    /** 2026-11-13 14:05 МСК. */
    private val now = 1_794_567_900_000L

    @Test
    fun segmentsAddUp() {
        val a = RadioStats.added(null, 60_000, 1000)
        val b = RadioStats.added(a, 120_000, 2000)
        assertEquals(180_000, b.totalMs)
        assertEquals(2000, b.last)
    }

    @Test
    fun jsonRoundTrip() {
        val map = mapOf("fm:1015" to RadioStats.Entry(123, 456))
        assertEquals(map, RadioStats.parse(RadioStats.serialize(map)))
        assertTrue(RadioStats.parse("мусор").isEmpty())
    }

    @Test
    fun durationText() {
        assertEquals("меньше минуты", RadioStats.formatDuration(30_000))
        assertEquals("25 мин", RadioStats.formatDuration(25 * 60_000L))
        assertEquals("2 ч", RadioStats.formatDuration(120 * 60_000L))
        assertEquals("3 ч 12 мин", RadioStats.formatDuration(192 * 60_000L))
    }

    @Test
    fun lastListenedText() {
        assertEquals("сегодня в 14:05", RadioStats.formatLast(now, now, zone))
        assertEquals("вчера в 14:05", RadioStats.formatLast(now - 86_400_000L, now, zone))
        assertEquals("1 ноября", RadioStats.formatLast(now - 12 * 86_400_000L, now, zone))
        assertEquals("13.11.2025", RadioStats.formatLast(now - 365 * 86_400_000L, now, zone))
    }
}
