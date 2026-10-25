package com.dash.headunit.diag

import org.junit.Assert.assertEquals
import org.junit.Test

class CoolantEtmTestTest {

    /** Формат из RcDataSendThread sva.cluster: [0, 0xA1, 0x01, 52, 0] + rc_msg_send_t. */
    @Test
    fun entryCommandMatchesSvaCluster() {
        val zeros = List(46) { 0 }.joinToString(",")
        assertEquals(
            "xA101:json:{\"data\":[0,161,1,52,0,55,0,0,0,1,1,$zeros]}",
            CoolantEtmTest.entry(1)
        )
        assertEquals(
            "xA101:json:{\"data\":[0,161,1,52,0,55,0,0,0,1,0,$zeros]}",
            CoolantEtmTest.entry(0)
        )
    }
}
