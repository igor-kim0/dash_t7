package com.dash.headunit.navi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AliceGuardTest {

    @Test
    fun focusedActivity() {
        val dump = "  mCurrentFocus=Window{3f2a1b u0 com.desaysv.vehiclesetting/com.desaysv.vehiclesetting.ui.activity.SettingActivity}\n"
        assertEquals(
            "com.desaysv.vehiclesetting/com.desaysv.vehiclesetting.ui.activity.SettingActivity",
            NaviUnderlay.focusedComponent(dump)
        )
    }

    @Test
    fun noActivityFocus() {
        assertNull(NaviUnderlay.focusedComponent("  mCurrentFocus=Window{1 u0 StatusBar}\n"))
        assertNull(NaviUnderlay.focusedComponent("  mCurrentFocus=null\n"))
        assertNull(NaviUnderlay.focusedComponent(""))
    }

    @Test
    fun foreign() {
        val navi = "ru.yandex.yandexnavi"
        val own = "com.dash.headunit"
        assertTrue(NaviUnderlay.isForeign("com.desaysv.vehiclesetting/.ui.activity.SettingActivity", navi, own))
        assertFalse(NaviUnderlay.isForeign("ru.yandex.yandexnavi/ru.yandex.yandexnavi.core.NavigatorActivity", navi, own))
        assertFalse(NaviUnderlay.isForeign("com.dash.headunit/com.dash.headunit.MainActivity", navi, own))
    }
}
