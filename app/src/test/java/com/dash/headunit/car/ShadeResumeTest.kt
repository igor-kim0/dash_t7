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
}
