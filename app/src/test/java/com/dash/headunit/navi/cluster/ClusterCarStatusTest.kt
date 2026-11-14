package com.dash.headunit.navi.cluster

import org.junit.Assert.assertEquals
import org.junit.Test

class ClusterCarStatusTest {

    @Test
    fun avgCons_oneDecimalWithComma() {
        assertEquals("8,4 л", avgConsText(8.4f))
        assertEquals("12,0 л", avgConsText(12f))
    }
}
