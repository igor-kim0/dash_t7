package com.dash.headunit.music

import org.junit.Assert.assertEquals
import org.junit.Test

class YandexMusicHubTest {

    @Test
    fun retryBacksOffUpToTwoMinutes() {
        assertEquals(10_000L, YandexMusicHub.retryDelayMs(0))
        assertEquals(10_000L, YandexMusicHub.retryDelayMs(1))
        assertEquals(20_000L, YandexMusicHub.retryDelayMs(2))
        assertEquals(80_000L, YandexMusicHub.retryDelayMs(4))
        assertEquals(120_000L, YandexMusicHub.retryDelayMs(5))
        assertEquals(120_000L, YandexMusicHub.retryDelayMs(50))
    }
}
