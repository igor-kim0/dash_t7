package com.dash.headunit.car

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Математика автооткрытия шторки ([ShadeResume]) на синтетике. */
class ShadeResumeTest {

    @Test
    fun wasOpen_openIs6() {
        assertTrue(ShadeResume.wasOpen(ShadeResume.OPEN_RAW))
        assertFalse(ShadeResume.wasOpen(1))
        assertFalse(ShadeResume.wasOpen(-1))
    }
}
