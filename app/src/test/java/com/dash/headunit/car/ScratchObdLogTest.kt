package com.dash.headunit.car

import org.junit.Test
import com.dash.headunit.elm.ObdParser

class ScratchObdLogTest {
    @Test
    fun checkRealReplies() {
        val rpm = "SEARCHING...7E804410C0000>"
        val load = "7E803410400>"
        val coolant = "7E80341057A>"
        val throttle = "7E803411127>"
        val trim = "7E803410680>"
        val map = "7E803410B65>"
        val fuel = "7E803412FCD>"
        println(" rpm  " + ObdParser.scanResponse(rpm, "410C") + " → " + ObdParser.rpm(rpm))
        println(" load " + ObdParser.engineLoad(load))
        println(" cool " + ObdParser.coolant(coolant))
        println(" thr  " + ObdParser.throttle(throttle))
        println(" trim " + ObdParser.fuelTrimShort(trim))
        println(" map  " + ObdParser.mapPressure(map))
        println(" fuel " + ObdParser.fuelLevel(fuel))
        println(" runs " + "7E803410400".length)
    }
}
