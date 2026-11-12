package com.dash.headunit.navi.cluster

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NaviOnClusterDrawnTest {

    private val pkg = "ru.yandex.yandexnavi"

    private fun dump(display: Int, state: String) = """
          Window #3 Window{a1 u0 com.dash.headunit/com.dash.headunit.MainActivity}:
            mDisplayId=0 stackId=1 mSession=Session{x}
            mDrawState=HAS_DRAWN mLastHidden=false
          Window #4 Window{b2 u0 $pkg/ru.yandex.yandexnavi.core.NavigatorActivity}:
            mDisplayId=$display stackId=31 mSession=Session{y}
            mDrawState=$state mLastHidden=false
    """.trimIndent()

    @Test
    fun drawnOnCluster() {
        assertTrue(NaviOnCluster.isDrawnOn(dump(1, "HAS_DRAWN"), pkg, 1))
    }

    @Test
    fun pendingOnCluster() {
        assertFalse(NaviOnCluster.isDrawnOn(dump(1, "DRAW_PENDING"), pkg, 1))
    }

    @Test
    fun drawnButStillOnMainScreen() {
        assertFalse(NaviOnCluster.isDrawnOn(dump(0, "HAS_DRAWN"), pkg, 1))
    }

    @Test
    fun otherPackageDrawnOnCluster() {
        assertFalse(NaviOnCluster.isDrawnOn(dump(1, "NO_SURFACE"), pkg, 1))
        assertFalse(NaviOnCluster.isDrawnOn(dump(1, "HAS_DRAWN"), "com.other", 1))
    }
}
