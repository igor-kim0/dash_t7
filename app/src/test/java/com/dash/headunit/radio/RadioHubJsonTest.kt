package com.dash.headunit.radio

import com.dash.headunit.radio.RadioHub.Json
import com.dash.headunit.radio.RadioHub.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class RadioHubJsonTest {

    @Test
    fun playInfoGivesFrequencyAndStation() {
        val s = Json.merge(
            State(), "fm", "play_info",
            """{"source":"fm","mTitle":"101.5MHz","mAlbum":"Europa Plus"}"""
        )
        assertEquals("fm", s.band)
        assertEquals("101.5MHz", s.frequency)
        assertEquals("Europa Plus", s.station)
    }

    @Test
    fun unknownStationAndNewFrequencyDropName() {
        val old = State(frequency = "101.5MHz", station = "Europa Plus")
        val s = Json.merge(old, "fm", "play_info", """{"source":"fm","mTitle":"102.0MHz","mAlbum":"Unknown Station"}""")
        assertEquals("102.0MHz", s.frequency)
        assertNull(s.station)
    }

    @Test
    fun amPlayInfoSwitchesBand() {
        val s = Json.merge(State(), "am", "play_info", """{"source":"am","mTitle":"999kHz"}""")
        assertEquals("am", s.band)
        assertEquals("999kHz", s.frequency)
    }

    @Test
    fun playStatusUpdatesPlaying() {
        val s = Json.merge(State(), "fm", "play_status", """{"source":"fm","mPlayStatus":true}""")
        assertEquals(true, s.playing)
    }

    @Test
    fun collectOfOtherBandIgnored() {
        val old = State(band = "fm", favorite = false)
        assertSame(old, Json.merge(old, "am", "collect_status", """{"source":"am","mCollectStatus":true}"""))
        assertEquals(true, Json.merge(old, "fm", "collect_status", """{"source":"fm","mCollectStatus":true}""").favorite)
    }

    @Test
    fun badJsonKeepsState() {
        val old = State(frequency = "101.5MHz")
        assertEquals(old, Json.merge(old, "fm", "play_info", "not json"))
    }

    @Test
    fun splitFrequency() {
        assertEquals("101.5" to "MHz", Json.splitFrequency("101.5MHz"))
        assertEquals("999" to "kHz", Json.splitFrequency("999 kHz"))
        assertEquals("—" to "", Json.splitFrequency(null))
    }
}
