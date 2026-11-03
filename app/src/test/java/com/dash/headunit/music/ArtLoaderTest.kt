package com.dash.headunit.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtLoaderTest {

    @Test
    fun yandexTemplateGetsSizeAndScheme() {
        assertEquals(
            "https://avatars.yandex.net/get-music-content/123/abc/400x400",
            ArtLoader.normalize("avatars.yandex.net/get-music-content/123/abc/%%")
        )
    }

    @Test
    fun protocolRelativeGetsHttps() {
        assertEquals("https://host.ru/a.jpg", ArtLoader.normalize("//host.ru/a.jpg"))
    }

    @Test
    fun contentAndHttpsKeptAsIs() {
        assertEquals("content://ru.yandex.music.art/1", ArtLoader.normalize("content://ru.yandex.music.art/1"))
        assertEquals("https://x.ru/y", ArtLoader.normalize(" https://x.ru/y "))
    }

    @Test
    fun blankOrGarbageIsNull() {
        assertNull(ArtLoader.normalize("  "))
        assertNull(ArtLoader.normalize("cover"))
    }

    @Test
    fun sampleSizeKeepsAtLeastMax() {
        assertEquals(1, ArtLoader.sampleSize(400, 400, 400))
        assertEquals(1, ArtLoader.sampleSize(700, 700, 400))
        assertEquals(2, ArtLoader.sampleSize(800, 600, 400))
        assertEquals(4, ArtLoader.sampleSize(1000, 1800, 400))
    }
}
