package com.dash.headunit.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Проверки математики борткомпьютера на синтетике. */
class TripTrackerTest {

    private fun state() = TripTracker.Period()

    private fun data(odo: Int, fuel: Float) = CarData(
        fuelPct = fuel,
        fuelLiters = null,
        rangeKm = null,
        voltageV = 14.2f,
        charging = true,
        engineTempC = null,
        gearboxTempC = null,
        outsideTempC = null,
        odoKm = odo
    )

    @Test
    fun firstUpdate_baselinesAndShowsZeros() {
        val st = state()
        val v = TripTracker.compute(st, data(19347, 58f), 51, true, 1_000L)
        assertEquals(0f, v.distKm!!, 0.001f)
        assertNull(v.avgCons)
        assertEquals(19347, st.odoStartKm)
    }

    @Test
    fun consumptionAndSpeed_math() {
        val st = state()
        // База: 19347 км, 58%.
        TripTracker.compute(st, data(19347, 58f), 51, true, 0L)
        // +38.4 км за 57.6 мин (40 км/ч), сожгли 3.3408 л (8.7 л/100).
        // 38.4 км * 8.7 / 100 = 3.3408 л = 6.55% бака 51 л.
        val v = TripTracker.compute(st, data(19385, 51.45f), 51, true, 57.6f.times(60_000).toLong())
        assertEquals(38f, v.distKm!!, 0.5f)
        assertEquals(8.7f, v.avgCons!!, 0.15f)
        assertEquals(40f, v.avgSpeedKmh!!, 1f)
        // Таймер: 57.6 мин.
        assertEquals(57.6f * 60_000, v.driveMillis.toFloat(), 1f)
    }

    @Test
    fun engineOff_timeNotAccumulated() {
        val st = state()
        TripTracker.compute(st, data(100, 50f), 51, true, 0L)
        val v = TripTracker.compute(st, data(100, 50f), 51, false, 300_000L)
        assertEquals(0, v.driveMillis)
        assertNull(v.avgSpeedKmh)
    }

    @Test
    fun odoJump_rebaselines() {
        val st = state()
        TripTracker.compute(st, data(100, 50f), 51, true, 0L)
        // Прыжок +1000 км за 5 с — глюк датчика, перебазироваться.
        val v = TripTracker.compute(st, data(1100, 49f), 51, true, 5_000L)
        assertEquals(0f, v.distKm!!, 0.001f)
        assertEquals(1100, st.odoStartKm)
    }

    @Test
    fun noData_nullsKept() {
        val st = state()
        val v = TripTracker.compute(st, null, 51, false, 0L)
        assertNull(v.distKm)
        assertNull(v.avgCons)
    }

    @Test
    fun noFuel_distAndSpeedStillWork() {
        val st = state()
        // Есть одометр, топливо не читается — база ставится по одометру.
        val noFuel = data(100, 50f).copy(fuelPct = null)
        TripTracker.compute(st, noFuel, 51, true, 0L)
        val v = TripTracker.compute(st, noFuel.copy(odoKm = 110), 51, true, 900_000L)
        assertEquals(10f, v.distKm!!, 0.001f)
        assertEquals(40f, v.avgSpeedKmh!!, 1f)
        assertNull(v.avgCons)
    }

    @Test
    fun fuelAppearsLater_rebaselines() {
        val st = state()
        // Сначала без топлива проехали 20 км, потом топливо появилось.
        TripTracker.compute(st, data(100, 50f).copy(fuelPct = null), 51, true, 0L)
        TripTracker.compute(st, data(120, 50f).copy(fuelPct = null), 51, true, 1_800_000L)
        val v = TripTracker.compute(st, data(125, 49f), 51, true, 3_600_000L)
        // Расход считается от момента появления топлива — 0 км, «—».
        assertEquals(0f, v.distKm!!, 0.001f)
        assertNull(v.avgCons)
        assertEquals(125, st.odoStartKm)
    }

    @Test
    fun refuel_rebaselinesConsumption() {
        val st = state()
        TripTracker.compute(st, data(100, 50f), 51, true, 0L)
        TripTracker.compute(st, data(130, 48f), 51, true, 1_800_000L)
        // Заправка: топливо +20 п.п. — база расхода новая, пробег с нуля.
        val v = TripTracker.compute(st, data(135, 68f), 51, true, 3_600_000L)
        assertEquals(0f, v.distKm!!, 0.001f)
        assertEquals(135, st.odoStartKm)
        assertEquals(68f, st.fuelPctStart!!, 0.001f)
    }

    @Test
    fun fuelJitter_noFalseRefuel() {
        val st = state()
        TripTracker.compute(st, data(100, 50f), 51, true, 0L)
        // Дрожание шины (+3 п.п.) — не заправка, база не сбрасывается.
        TripTracker.compute(st, data(105, 53f), 51, true, 1_800_000L)
        val v = TripTracker.compute(st, data(110, 51f), 51, true, 3_600_000L)
        assertEquals(10f, v.distKm!!, 0.001f)
        assertEquals(100, st.odoStartKm)
    }

    @Test
    fun engineOn_detection() {
        assertEquals(true, TripTracker.engineOn(data(0, 0f).copy(charging = true)))
        // Флаг Desay (0,27) достоверен — напряжение его не перебивает.
        assertEquals(false, TripTracker.engineOn(data(0, 0f).copy(charging = false, voltageV = 13.5f)))
        // Без флага (только VHAL) — по напряжению.
        assertEquals(true, TripTracker.engineOn(data(0, 0f).copy(charging = null, voltageV = 13.5f)))
        assertEquals(false, TripTracker.engineOn(data(0, 0f).copy(charging = null, voltageV = 12.4f)))
        assertEquals(false, TripTracker.engineOn(null))
    }

    @Test
    fun engineOn_prefersReliableOffSignals() {
        // Обороты Desay (4,62/4) — прямой признак: выше 400 = работает.
        assertEquals(true, TripTracker.engineOn(data(0, 0f).copy(rpm = 750)))
        assertEquals(false, TripTracker.engineOn(data(0, 0f).copy(rpm = 0)))
        // Флаг Desay (0,27) честно падает в 0 при глушении, обороты
        // могут висеть >0 — «заглушено» приоритетнее «работает».
        assertEquals(
            false,
            TripTracker.engineOn(data(0, 0f).copy(rpm = 750, charging = false))
        )
        // Без оборотов — старый путь по флагу/напряжению.
        assertEquals(true, TripTracker.engineOn(data(0, 0f).copy(rpm = null, charging = true)))
        assertEquals(false, TripTracker.engineOn(data(0, 0f).copy(rpm = null, charging = null, voltageV = 12.4f)))
    }

    @Test
    fun finalizeEntry_skipsJunkTrips() {
        // Ни пробега, ни времени — в журнал не пишем.
        assertNull(
            TripTracker.finalizeEntry(
                1000L, 2000L,
                TripTracker.TripView(0f, null, null, 0L)
            )
        )
        // 50 м за 30 с — тоже мусор.
        assertNull(
            TripTracker.finalizeEntry(
                1000L, 31000L,
                TripTracker.TripView(0.05f, null, null, 30_000L)
            )
        )
    }

    @Test
    fun finalizeEntry_keepsRealTrips() {
        // 100 м, но 5 минут в движении — пишем.
        val v = TripTracker.finalizeEntry(
            1000L, 301_000L,
            TripTracker.TripView(0.05f, 8.5f, 12f, 300_000L)
        )
        assertEquals(1000L, v!!.startTs)
        assertEquals(301_000L, v.endTs)
        assertEquals(0.05f, v.distKm, 0.001f)
        assertEquals(8.5f, v.avgCons!!, 0.001f)
        // 12,6 км за 1:12 — пишем.
        val v2 = TripTracker.finalizeEntry(
            1000L, 4_321_000L,
            TripTracker.TripView(12.6f, 7.4f, 46f, 4_320_000L)
        )
        assertEquals(12.6f, v2!!.distKm, 0.001f)
    }

    @Test
    fun finalizeEntry_tripTimeFromEngineStart_ownAvgSpeed() {
        // Поездка 20 мин, из них в движении 10 мин. Время в журнале —
        // от пуска двигателя; средняя скорость — своя (не с приборки).
        val e = TripTracker.finalizeEntry(
            0L, 1_200_000L,
            TripTracker.TripView(12.6f, 7.4f, null, 600_000L)
        )!!
        assertEquals(1_200_000L, e.driveMillis)
        assertEquals(37.8f, e.avgSpeedKmh!!, 0.5f)
    }

    @Test
    fun finalizeEntry_clusterAvgSpeedKept() {
        // Приборка дала свою среднюю скорость — сохраняем её.
        val e = TripTracker.finalizeEntry(
            0L, 600_000L,
            TripTracker.TripView(5.1f, 9.4f, 20.8f, 300_000L)
        )!!
        assertEquals(600_000L, e.driveMillis)
        assertEquals(20.8f, e.avgSpeedKmh!!, 0.001f)
    }

    @Test
    fun journal_roundTrip() {
        val list = listOf(
            TripTracker.TripEntry(1000L, 2000L, 12.6f, 4_320_000L, 7.4f, 46f),
            TripTracker.TripEntry(3000L, 4000L, 3.0f, 600_000L, null, null)
        )
        val decoded = TripTracker.decodeJournal(TripTracker.encodeJournal(list))
        assertEquals(list, decoded)
    }

    @Test
    fun journal_pruneByKeepDays() {
        val now = 1_700_000_000_000L
        val day = 24L * 3600_000L
        // Свежие записи идут первыми; копим по одному дню назад.
        val list = listOf(
            TripTracker.TripEntry(now - day, now - day + 600_000L, 5f, 600_000L, null),
            TripTracker.TripEntry(now - 6 * day, now - 6 * day + 600_000L, 5f, 600_000L, null),
            TripTracker.TripEntry(now - 24 * day, now - 24 * day + 600_000L, 5f, 600_000L, null)
        )
        // Неделя: граница (ровно 7 дней) остаётся, старше — уходит.
        val pruned = TripTracker.pruneJournal(list, 7, now)
        assertEquals(2, pruned.size)
        assertEquals(list.take(2), pruned)
        // Пустой журнал и «всегда» трогать нельзя.
        assertEquals(list, TripTracker.pruneJournal(list, 0, now))
        assertEquals(
            listOf(list[0]),
            TripTracker.pruneJournal(list, 1, now)
        )
    }

    @Test
    fun journal_toleratesGarbage() {
        assertEquals(emptyList<TripTracker.TripEntry>(), TripTracker.decodeJournal(""))
        assertEquals(
            emptyList<TripTracker.TripEntry>(),
            TripTracker.decodeJournal("мусор;1,2,3")
        )
        // Одна битая запись не роняет хорошие.
        val decoded = TripTracker.decodeJournal(
            "zzz;" + TripTracker.encodeJournal(
                listOf(TripTracker.TripEntry(1000L, 2000L, 5f, 600_000L, null))
            )
        )
        assertEquals(1, decoded.size)
        assertEquals(5f, decoded[0].distKm, 0.001f)
    }

    @Test
    fun journal_readsLegacyFiveFieldRecords() {
        // Записи до появления средней скорости (5 полей) читаются.
        val decoded = TripTracker.decodeJournal("1,2,3.5,600000,7.8")
        assertEquals(1, decoded.size)
        assertEquals(3.5f, decoded[0].distKm, 0.001f)
        assertEquals(7.8f, decoded[0].avgCons!!, 0.001f)
        assertNull(decoded[0].avgSpeedKmh)
    }

    private fun cluster(trip: Float?, fuel: Float?, speed: Float?) =
        ClusterTrip(19513L, trip, 5036.6f, fuel, speed, 481)

    @Test
    fun withCluster_prefersClusterValues() {
        val own = TripTracker.TripView(4.0f, 10.2f, 18f, 600_000)
        val v = TripTracker.withCluster(own, cluster(5.1f, 9.4f, 20.8f))
        assertEquals(5.1f, v.distKm!!, 0.001f)
        assertEquals(9.4f, v.avgCons!!, 0.001f)
        assertEquals(20.8f, v.avgSpeedKmh!!, 0.001f)
        assertEquals(600_000L, v.driveMillis)
        assertEquals(481, v.cluster?.rangeKm)
    }

    @Test
    fun withCluster_keepsOwnWhereClusterHasNoData() {
        // Первые ~100 м после запуска приборка не даёт расход и скорость.
        val own = TripTracker.TripView(0f, null, 12f, 60_000)
        val v = TripTracker.withCluster(own, cluster(0f, null, null))
        assertEquals(0f, v.distKm!!, 0.001f)
        assertNull(v.avgCons)
        assertEquals(12f, v.avgSpeedKmh!!, 0.001f)
    }

    @Test
    fun withCluster_withoutClusterTripUnchanged() {
        val own = TripTracker.TripView(4.0f, 10.2f, 18f, 600_000)
        assertEquals(own, TripTracker.withCluster(own, null))
        assertEquals(own, TripTracker.withCluster(own, cluster(null, 9.4f, 20f)))
    }

    @Test
    fun isGap_detectsSleepOnly() {
        val t0 = 1_700_000_000_000L
        assertFalse(TripTracker.isGap(0L, t0))
        assertFalse(TripTracker.isGap(t0, t0 + 5_000))
        assertFalse(TripTracker.isGap(t0, t0 - 3_600_000))
        assertTrue(TripTracker.isGap(t0, t0 + 8 * 3_600_000L))
    }
}
