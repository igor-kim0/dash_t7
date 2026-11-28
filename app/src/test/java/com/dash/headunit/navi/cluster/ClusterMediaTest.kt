package com.dash.headunit.navi.cluster

import com.dash.headunit.music.MusicData
import com.dash.headunit.radio.RadioHub
import com.dash.headunit.radio.RadioStations.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClusterMediaTest {

    private val stations = listOf(Station("fm", 104.0, name = "Радио Победа"))
    private fun music(playing: Boolean) = MusicData("Трек", "Исполнитель", null, null, "bt", playing)

    @Test
    fun nothingPlaying() {
        assertNull(ClusterMedia.pick(music(false), RadioHub.State(playing = false), stations))
        assertNull(ClusterMedia.pick(null, RadioHub.State(), stations))
    }

    @Test
    fun musicFirst() {
        val m = ClusterMedia.pick(music(true), RadioHub.State(frequency = "104.0MHz", playing = true), stations)!!
        assertEquals("Трек", m.title)
        assertEquals("Исполнитель", m.sub)
    }

    @Test
    fun knownStation() {
        val m = ClusterMedia.pick(music(false), RadioHub.State(frequency = "104.0MHz", playing = true), stations)!!
        assertEquals("Радио Победа", m.title)
        assertEquals("FM 104 МГц", m.sub)
        assertEquals("П", m.badge)
    }

    @Test
    fun unknownStationShowsFrequency() {
        val m = ClusterMedia.pick(null, RadioHub.State(frequency = "101.5MHz", playing = true), stations)!!
        assertEquals("101.5 МГц", m.title)
        assertEquals("Радио FM", m.sub)
        assertEquals("FM", m.badge)
    }
}
