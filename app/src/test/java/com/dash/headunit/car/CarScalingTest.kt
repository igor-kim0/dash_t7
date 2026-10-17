package com.dash.headunit.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Проверки формул пересчёта CAN/VHAL (логика из Jarvis). */
class CarScalingTest {

    @Test
    fun bytesToInt_assemblesBigEndian() {
        assertEquals(0x0102, CarScaling.bytesToInt(intArrayOf(0x01, 0x02)))
        assertEquals(580, CarScaling.bytesToInt(intArrayOf(0x02, 0x44)))
        assertEquals(-1, CarScaling.bytesToInt(null))
        assertEquals(-1, CarScaling.bytesToInt(intArrayOf()))
        assertEquals(-1, CarScaling.bytesToInt(intArrayOf(1, 2, 3, 4, 5)))
    }

    @Test
    fun desay_scaling() {
        assertEquals(58f, CarScaling.desayFuelPct(580)!!, 0.001f)
        assertNull(CarScaling.desayFuelPct(1001))
        assertEquals(420, CarScaling.desayRangeKm(420))
        assertNull(CarScaling.desayRangeKm(3001))
        assertEquals(14.1f, CarScaling.desayVolts(141)!!, 0.001f)
        assertNull(CarScaling.desayVolts(50))
    }

    @Test
    fun vhal_fuel_chery_dividesByTwo() {
        assertEquals(58f, CarScaling.vhalFuelPct(116, true)!!, 0.001f)
        assertEquals(100f, CarScaling.vhalFuelPct(250, true)!!, 0.001f)
        assertEquals(58f, CarScaling.vhalFuelPct(58, false)!!, 0.001f)
        assertEquals(58f, CarScaling.vhalFuelPct(580, false)!!, 0.001f)
        assertNull(CarScaling.vhalFuelPct(-1, true))
    }

    @Test
    fun vhal_volts_scalesByMagnitude() {
        assertEquals(12.4f, CarScaling.vhalVolts(12400.0)!!, 0.001f) // мВ
        assertEquals(12.4f, CarScaling.vhalVolts(124.0)!!, 0.001f) // сВ
        assertEquals(12.4f, CarScaling.vhalVolts(12.4)!!, 0.001f) // В
        assertNull(CarScaling.vhalVolts(5.0))
        assertNull(CarScaling.vhalVolts(25.0))
    }

    @Test
    fun fuelLiters_fromPctAndTank() {
        assertEquals(29.58f, CarScaling.fuelLiters(58f, 51)!!, 0.01f)
        assertNull(CarScaling.fuelLiters(null, 51))
        assertNull(CarScaling.fuelLiters(58f, 0))
    }

    @Test
    fun coolantTemp_validatesRange() {
        assertEquals(91f, CarScaling.coolantTemp(91f)!!, 0.001f)
        assertNull(CarScaling.coolantTemp(200f))
        assertNull(CarScaling.coolantTemp(null))
    }

    @Test
    fun outsideTemp_minus120() {
        // При погоде 4–9 °C ячейка даёт 125–128; Oct12 134→14.
        assertEquals(5f, CarScaling.outsideTemp(125)!!, 0.001f)
        assertEquals(7f, CarScaling.outsideTemp(127)!!, 0.001f)
        assertEquals(14f, CarScaling.outsideTemp(134)!!, 0.001f)
        assertNull(CarScaling.outsideTemp(69))
        assertNull(CarScaling.outsideTemp(171))
    }

    @Test
    fun rpmDesay_dividesByFour() {
        // Приборка/PPS: сырое 1000→250, 900→225, 788→197 об/мин.
        assertEquals(250, CarScaling.rpmDesay(1000))
        assertEquals(225, CarScaling.rpmDesay(900))
        assertEquals(197, CarScaling.rpmDesay(788))
        assertNull(CarScaling.rpmDesay(-1))
        assertNull(CarScaling.rpmDesay(40001))
    }
}
