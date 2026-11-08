package com.dash.headunit.elm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class ObdProfileTest {

    private fun eval(f: String, vararg bytes: Int) = Formula.compile(f).eval(bytes.toList())

    @Test
    fun formula_arithmetic() {
        assertEquals(1000.0, eval("((A*256)+B)/4", 0x0F, 0xA0)!!, 1e-9)
        assertEquals(-40.0, eval("A-40", 0)!!, 1e-9)
        assertEquals(-100.0, eval("((A-128)*100)/128", 0)!!, 1e-9)
        assertEquals(14.0, eval("2+3*4")!!, 1e-9)
        assertEquals(-5.0, eval("-(2+3)")!!, 1e-9)
        assertEquals(0x12345678.toDouble(), eval("A*16777216+B*65536+C*256+D", 0x12, 0x34, 0x56, 0x78)!!, 1e-9)
    }

    @Test
    fun formula_bits() {
        assertEquals(1.0, eval("(A>>3)&1", 0b1000)!!, 1e-9)
        assertEquals(0x0F.toDouble(), eval("A|B", 0x0C, 0x03)!!, 1e-9)
        assertEquals(512.0, eval("A<<8", 2)!!, 1e-9)
    }

    @Test
    fun formula_missingByteAndErrors() {
        assertNull(eval("A*256+B", 5))
        assertNull(eval("A/B", 1, 0))
        for (bad in listOf("A*", "(A", "AB+1", "A $ 2", "")) {
            try {
                Formula.compile(bad)
                fail("ожидалась ошибка для «$bad»")
            } catch (e: IllegalArgumentException) {
                // ок
            }
        }
        assertEquals(4, Formula.compile("A*16777216+B*65536+C*256+D").bytesNeeded)
    }

    @Test
    fun responseId_modes() {
        assertEquals("410C", ObdProfile.responseId("010C"))
        assertEquals("621234", ObdProfile.responseId("221234"))
        assertEquals("4902", ObdProfile.responseId("0902"))
    }

    @Test
    fun labels_fromNote() {
        val m = ObdProfile.parseLabels("0=P, 1=R, 2=N, 3-9=D1-D7")
        assertEquals("P", m[0])
        assertEquals("N", m[2])
        assertEquals("D1", m[3])
        assertEquals("D7", m[9])
        assertTrue(ObdProfile.parseLabels("Chery-specific DID").isEmpty())
    }

    @Test
    fun elmProtocol_names() {
        assertEquals("6", ObdProfile.elmProtocol("CAN_11bit_500"))
        assertEquals("9", ObdProfile.elmProtocol("CAN 29bit 250"))
        assertNull(ObdProfile.elmProtocol("AUTO"))
    }

    /** Встроенный профиль Chery Tiggo целиком разбирается без ошибок. */
    @Test
    fun cheryProfile_parses() {
        val p = ObdProfile.parse(File("src/main/assets/profiles/Chery Tiggo.json").readText())
        assertEquals("Chery Tiggo 7 Pro 1.6T DCT (по логу Car Scanner)", p.name)
        assertEquals("6", p.elmProtocol)
        assertEquals("7E0", p.defaultHeader)
        assertTrue(p.warnings.toString(), p.warnings.isEmpty())
        assertEquals(56, p.params.size)
        assertEquals("Двигатель", p.groups.first().title)
        assertEquals(listOf("VIN", "Калибровка", "ЭБУ", "Калибровка КПП", "ЭБУ КПП"), p.info.map { it.title })
        assertEquals("7E1", p.info.first { it.key == "tcm_name" }.header)

        val rpm = p.params.first { it.shortName == "RPM" }
        assertEquals("7E0", rpm.header)
        assertNull(rpm.rxHeader) // 7E0 → 7E8 ELM ловит сам
        assertEquals(1000.0, rpm.decode("410C0FA0\r\r>")!!, 1e-9)
        assertEquals("1000 об/мин", rpm.format(1000.0))
        assertTrue(rpm.outOfRange(9000.0))

        val tps = p.params.first { it.shortName == "TPS" }
        assertEquals("100.0 %", tps.format(tps.decode("4111FF")!!))

        val mil = p.params.first { it.shortName == "MIL" }
        val dtc = p.params.first { it.shortName == "DTC" }
        assertEquals("горит", mil.format(mil.decode("41018307E100")!!))
        assertEquals(3.0, dtc.decode("41018307E100")!!, 1e-9)

        // Ответы ниже — из лога Car Scanner (obd code/obd_log.txt).
        val gear = p.params.first { it.shortName == "Gear" }
        assertEquals("703", gear.header)
        assertEquals("713", gear.rxHeader)
        assertEquals("5", gear.format(gear.decode("620A5F05")!!))
        assertEquals("N/P", gear.format(gear.decode("620A5F09")!!))
        assertNull(gear.decode("7F2231\r>")) // отказ блока
        assertNull(gear.decode("NO DATA\r>"))

        val oil = p.params.first { it.shortName == "DCT oil" }
        assertEquals(-16.0, oil.decode("620A7DFFE0")!!, 1e-9)
        assertEquals(57.0, oil.decode("620A7D0072")!!, 1e-9)

        val odo = p.params.first { it.shortName == "Odo DCT" }
        assertEquals(1827.0, odo.decode("620A6A0002C9AC")!!, 1e-9)

        val wheel = p.params.first { it.shortName == "Wheel FL" }
        assertEquals("730", wheel.rxHeader)
        assertEquals(57.6, wheel.decode("6230030400")!!, 1e-9)

        val steering = p.params.first { it.shortName == "Steering" }
        assertEquals(-365.0, steering.decode("62300CF1BE")!!, 1e-9)

        val sensor = p.params.first { it.shortName == "Sensor FL" }
        assertEquals("750", sensor.rxHeader)
        assertEquals("B03964E8", sensor.format(sensor.decode("623400B03964E8")!!))
        val tireT = p.params.first { it.shortName == "Tire FL T" }
        assertEquals(12.0, tireT.decode("62340834")!!, 1e-9)

        val outside = p.params.first { it.shortName == "Outside" }
        assertEquals("75B", outside.rxHeader)
        assertEquals(-9.0, outside.decode("6268023E")!!, 1e-9)
    }

    /** Сводный профиль Cherry Tiggo 7 разбирается без ошибок. */
    @Test
    fun cherryTiggo7Profile_parses() {
        val p = ObdProfile.parse(File("src/main/assets/profiles/Cherry Tiggo 7.json").readText())
        assertEquals("Chery Tiggo 7 / 7 Pro / 7L (1.5T/1.6T) + DCT — сводный профиль", p.name)
        assertEquals("6", p.elmProtocol)
        assertEquals("7E0", p.defaultHeader)
        assertTrue(p.warnings.toString(), p.warnings.isEmpty())
        assertEquals(56, p.params.size)
        // Ответы — из лога Car Scanner (samples/chery_tiggo_from_log.json).
        val gear = p.params.first { it.shortName == "Gear" }
        assertEquals("703", gear.header)
        assertEquals("713", gear.rxHeader)
        assertEquals("N/P", gear.format(gear.decode("620A5F09")!!))
        assertEquals(1837.0, p.params.first { it.shortName == "Odo DCT" }.decode("620A6A0002CD94")!!, 0.01)
        assertEquals("B03964E8", p.params.first { it.shortName == "Sensor FL" }.format(p.params.first { it.shortName == "Sensor FL" }.decode("623400B03964E8")!!))
        assertEquals("VIN", p.info.first().title)
    }

    /** Тестовый набор DID из logs/ddd.csv: все 37 параметров разбираются. */
    @Test
    fun testTiggo7lProfile_parses() {
        val p = ObdProfile.parse(File("src/main/assets/profiles/test tiggo 7l.json").readText())
        assertEquals("test tiggo 7l", p.name)
        assertTrue(p.warnings.toString(), p.warnings.isEmpty())
        assertEquals(37, p.params.size)

        val eot = p.params.first { it.command == "221013" }
        assertEquals("7E0", eot.header)
        assertEquals(50.0, eot.decode("6210135A")!!, 1e-9)

        val knock = p.params.first { it.command == "221221" }
        assertEquals(-1.5, knock.decode("62122104")!!, 1e-9)

        val awd = p.params.first { it.command == "224018" }
        assertEquals("7E4", awd.header)
        assertEquals(2.56, awd.decode("6240180100")!!, 1e-9)
    }

    /** VIN приходит несколькими кадрами ISO-TP. */
    @Test
    fun vin_multiFrame() {
        val p = ObdProfile.parse(File("src/main/assets/profiles/Chery Tiggo.json").readText())
        val vin = p.info.first { it.key == "vin" }
        val reply = "014\r0:490201314734\r1:4A433534343452\r2:37323532333637\r\r>"
        assertEquals("1G4JC5444R7252367", vin.decode(reply))
    }

    @Test
    fun badParamsBecomeWarnings() {
        val p = ObdProfile.parse("""
            {"profile_name":"T","parameters":{"x":[
              {"name":"ok","command":"010C","formula":"A"},
              {"name":"nocmd","formula":"A"},
              {"name":"badf","command":"0105","formula":"A*"}
            ]}}""")
        assertEquals(1, p.params.size)
        assertEquals(2, p.warnings.size)
        try {
            ObdProfile.parse("""{"profile_name":"T","parameters":{"x":[{"name":"nocmd"}]}}""")
            fail()
        } catch (e: IllegalArgumentException) {
            // ок — нет годных параметров
        }
    }
}
