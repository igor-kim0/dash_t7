package com.dash.headunit.navi

import android.view.KeyEvent
import com.dash.headunit.navi.WheelKeys.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class WheelKeysTest {

    private fun d(
        learning: Boolean = false, enabled: Boolean = true, assigned: Int = 287,
        key: Int = 287, action: Int = KeyEvent.ACTION_DOWN, repeat: Int = 0,
        now: Long = 10_000, last: Long = 0
    ) = WheelKeys.decide(learning, enabled, assigned, key, action, repeat, now, last)

    @Test
    fun learningConsumesAndAssignsOnUp() {
        assertEquals(Action.CONSUME, d(learning = true, enabled = false, assigned = -1, key = 24))
        assertEquals(Action.CONSUME_LEARN, d(learning = true, key = 24, action = KeyEvent.ACTION_UP))
    }

    @Test
    fun assignedKeyTriggersOnceThenConsumes() {
        assertEquals(Action.CONSUME_TRIGGER, d())
        assertEquals(Action.CONSUME, d(action = KeyEvent.ACTION_UP))
        assertEquals(Action.CONSUME, d(repeat = 3))
        assertEquals(Action.CONSUME, d(now = 10_500, last = 10_000))
        assertEquals(Action.CONSUME_TRIGGER, d(now = 13_000, last = 10_000))
    }

    @Test
    fun otherKeysAndDisabledPass() {
        assertEquals(Action.PASS, d(key = 24))
        assertEquals(Action.PASS, d(enabled = false))
        assertEquals(Action.PASS, d(assigned = -1))
    }
}
