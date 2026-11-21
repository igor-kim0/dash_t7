package com.dash.headunit.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** versionCode из тега релиза — как в build.gradle.kts (X·10000 + Y·100 + Z). */
class UpdaterTest {
    @Test
    fun versionCode() {
        assertEquals(10805, Updater.versionCode("1.8.5"))
        assertEquals(20000, Updater.versionCode("2.0.0"))
        assertEquals(11012, Updater.versionCode(" 1.10.12 "))
    }

    @Test
    fun badVersions() {
        assertNull(Updater.versionCode("1.8"))
        assertNull(Updater.versionCode("1.8.5-dev"))
        assertNull(Updater.versionCode("1.100.0"))
        assertNull(Updater.versionCode(""))
    }

    @Test
    fun notes() {
        val md = "### Добавлено\n- Первый пункт\n  продолжение.\n- Второй\n\n### Исправлено\n- Третий\n\n**Full Changelog**: https://x"
        assertEquals(
            "Добавлено:\n• Первый пункт продолжение.\n• Второй\n\nИсправлено:\n• Третий",
            Updater.formatNotes(md)
        )
    }
}
