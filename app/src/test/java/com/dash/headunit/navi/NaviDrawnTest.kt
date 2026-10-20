package com.dash.headunit.navi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NaviDrawnTest {

    private val pkg = "ru.yandex.yandexnavi"

    private fun dump(state: String) = """
          Window #4 Window{1 u0 com.dash.headunit/com.dash.headunit.MainActivity}:
              mDrawState=HAS_DRAWN       mLastHidden=false
          Window #5 Window{2 u0 $pkg/$pkg.core.NavigatorActivity}:
              mDrawState=$state       mLastHidden=false
    """.trimIndent()

    @Test
    fun drawnNavi() = assertTrue(NaviUnderlay.isDrawn(dump("HAS_DRAWN"), pkg))

    @Test
    fun notYetDrawn() = assertFalse(NaviUnderlay.isDrawn(dump("DRAW_PENDING"), pkg))

    @Test
    fun otherWindowDrawnDoesNotCount() = assertFalse(NaviUnderlay.isDrawn(dump("NO_SURFACE"), pkg))
}
