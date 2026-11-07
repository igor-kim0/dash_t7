package com.dash.headunit.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Математика автооткрытия шторки ([ShadeResume]) на синтетике. */
class ShadeResumeTest {

    private val write = ShadeResume.AutoOpen.Action.WRITE
    private val none = ShadeResume.AutoOpen.Action.NONE

    @Test
    fun wasOpen_openIs6() {
        assertTrue(ShadeResume.wasOpen(ShadeResume.OPEN_RAW))
        assertFalse(ShadeResume.wasOpen(1))
        assertFalse(ShadeResume.wasOpen(-1))
    }

    @Test
    fun notOpenAtEnd_finishesWithoutWrite() {
        val a = ShadeResume.AutoOpen()
        assertEquals(none, a.step(busAlive = true, wantOpen = false) { 1 })
        assertTrue(a.finished)
    }

    @Test
    fun waitsForBus_thenWritesAndVerifies() {
        val a = ShadeResume.AutoOpen()
        assertEquals(none, a.step(busAlive = false, wantOpen = true) { -1 })
        assertEquals(write, a.step(true, true) { 1 })
        repeat(ShadeResume.VERIFY_TICKS) { assertEquals(none, a.step(true, true) { 1 }) }
        // Открылась — готово, повторов нет.
        assertEquals(none, a.step(true, true) { ShadeResume.OPEN_RAW })
        assertTrue(a.finished)
        assertEquals(1, a.attempts)
    }

    @Test
    fun notOpenedAfterWrite_retriesThenGivesUp() {
        val a = ShadeResume.AutoOpen()
        var writes = 0
        repeat(50) { if (a.step(true, true) { 1 } == write) writes++ }
        assertEquals(ShadeResume.MAX_ATTEMPTS, writes)
        assertTrue(a.finished)
    }

    @Test
    fun failedWrite_retriedNextTick() {
        val a = ShadeResume.AutoOpen()
        assertEquals(write, a.step(true, true) { 1 })
        a.writeFailed()
        assertEquals(write, a.step(true, true) { 1 })
    }

    @Test
    fun finished_rearmedWhenEngineStartsAfterSleep() {
        // ГУ спало с замершим процессом: автооткрытие отработало вчера.
        val a = ShadeResume.AutoOpen()
        a.finish()
        val last = 1_000L
        assertTrue(ShadeResume.rearmIfRestarted(a, last, last + ShadeResume.REARM_GAP_MS + 1))
        assertFalse(a.finished)
        assertEquals(write, a.step(true, true) { 1 })
    }

    @Test
    fun finished_notRearmedWhileDriving() {
        val a = ShadeResume.AutoOpen()
        a.finish()
        assertFalse(ShadeResume.rearmIfRestarted(a, 1_000L, 6_000L))
        assertTrue(a.finished)
    }

    @Test
    fun finished_rearmedOnFirstEngineStartInProcess() {
        // Открывали ещё на зажигании без двигателя — на пуске повторяем.
        val a = ShadeResume.AutoOpen()
        a.finish()
        assertTrue(ShadeResume.rearmIfRestarted(a, 0L, 5_000L))
    }

    @Test
    fun pending_notResetByEngineStart() {
        val a = ShadeResume.AutoOpen()
        assertEquals(write, a.step(true, true) { 1 })
        assertFalse(ShadeResume.rearmIfRestarted(a, 0L, 5_000L))
        assertEquals(1, a.attempts)
    }

    @Test
    fun recordLag_dropsLastTicksBeforeEngineOff() {
        // Ехали с открытой шторкой; на глушении машина её закрывает,
        // обороты в шине ещё «живые» — последние чтения уже 1.
        val lag = ShadeResume.RecordLag(ticks = 2)
        val recorded = mutableListOf<Int>()
        for (raw in listOf(6, 6, 6, 6, 1, 1)) {
            lag.push(raw)?.let { recorded += it }
        }
        // Двигатель заглох — хвост выбрасывается.
        lag.clear()
        assertEquals(listOf(6, 6, 6, 6), recorded)
    }

    @Test
    fun recordLag_badReadsAreNotRecorded() {
        val lag = ShadeResume.RecordLag(ticks = 1)
        assertEquals(null, lag.push(-1))
        assertEquals(null, lag.push(6))
        assertEquals(6, lag.push(6))
    }
}
