package com.dash.headunit.diag

import com.dash.headunit.car.CoolantEtm
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

    /** Диагностический тест и боевой старт шлют одинаковые строки. */
    @Test
    fun entryMatchesRunningCoolantEtm() {
        assertEquals(CoolantEtm.entry(1), CoolantEtmTest.entry(1))
        assertEquals(CoolantEtm.entry(0), CoolantEtmTest.entry(0))
    }
}
