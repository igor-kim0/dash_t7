package com.dash.headunit.navi

import android.view.KeyEvent
import com.dash.headunit.navi.WheelKeys.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class WheelKeysTest {

    private fun d(
        learning: Boolean = false, enabled: Boolean = true, assigned: Int = 292,
        key: Int = 292, action: Int = KeyEvent.ACTION_DOWN, hold: Long = 0,
        down: Long = 5_000, triggered: Long = -1
    ) = WheelKeys.decide(learning, enabled, assigned, key, action, hold, down, triggered)

    @Test
    fun learningAssignsOnUp() {
        assertEquals(Action.NONE, d(learning = true, enabled = false, assigned = -1, key = 297))
        assertEquals(Action.LEARN, d(learning = true, key = 297, action = KeyEvent.ACTION_UP))
    }

    @Test
    fun shortPressIsLeftToStockAssistant() {
        assertEquals(Action.NONE, d())
        assertEquals(Action.NONE, d(action = KeyEvent.ACTION_UP, hold = 300))
    }

    @Test
    fun longPressTriggersOncePerPress() {
        assertEquals(Action.NONE, d(hold = WheelKeys.LONG_MS - 1))
        assertEquals(Action.TRIGGER, d(hold = WheelKeys.LONG_MS))
        // Дальнейшие повторы и отпускание того же нажатия — уже нет.
        assertEquals(Action.NONE, d(hold = 2_000, triggered = 5_000))
        assertEquals(Action.NONE, d(action = KeyEvent.ACTION_UP, hold = 4_000, triggered = 5_000))
        // Повторов не было — сработает на отпускании.
        assertEquals(Action.TRIGGER, d(action = KeyEvent.ACTION_UP, hold = 1_200))
        // Следующее нажатие.
        assertEquals(Action.TRIGGER, d(hold = 900, down = 9_000, triggered = 5_000))
    }

    @Test
    fun otherKeysAndDisabledIgnored() {
        assertEquals(Action.NONE, d(key = 286, hold = 2_000))
        assertEquals(Action.NONE, d(enabled = false, hold = 2_000))
        assertEquals(Action.NONE, d(assigned = -1, hold = 2_000))
    }
}
