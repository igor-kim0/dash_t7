package com.dash.headunit.radio

import com.dash.headunit.radio.RadioHub.Json
import com.dash.headunit.radio.RadioStations.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioStationsTest {

    private val list = listOf(
        Station("fm", 101.5, rds = "Europa Plus"),
        Station("fm", 104.0, name = "Радио Победа"),
        Station("fm", 107.1, name = "Ретро FM", favorite = true),
        Station("am", 999.0, name = "Радио Азия")
    )

    @Test
    fun titleIsOwnNameThenRdsThenFrequency() {
        assertEquals("Радио Победа", list[1].title)
        assertEquals("Europa Plus", list[0].title)
        assertEquals("Радио Азия", list[3].title)
        // Без своего имени и RDS — частота.
        assertEquals("999 кГц", Station("am", 999.0).title)
        assertEquals("104 МГц", Station("fm", 104.0).title)
    }

    @Test
    fun searchByNameAndRds() {
        assertEquals(listOf(list[1]), RadioStations.search(list, "победа"))
        assertEquals(listOf(list[0]), RadioStations.search(list, "europa"))
        assertEquals(listOf(list[3]), RadioStations.search(list, "Азия"))
    }

    @Test
    fun searchByFrequencyWithCommaOrDot() {
        assertEquals(listOf(list[0]), RadioStations.search(list, "101.5"))
        assertEquals(listOf(list[0]), RadioStations.search(list, "101,5"))
        assertEquals(listOf(list[1]), RadioStations.search(list, "104"))
    }

    @Test
    fun emptySearchKeepsAll() {
        assertEquals(list, RadioStations.search(list, "  "))
    }

    @Test
    fun favoritesOnly() {
        assertEquals(listOf(list[2]), RadioStations.favorites(list))
    }

    @Test
    fun favoriteLimitIsSix() {
        var stations = listOf<Station>()
        repeat(6) { i ->
            stations = RadioStations.upsertIn(stations, "fm", 90.0 + i)
            stations = RadioStations.favoriteIn(stations, "fm", 90.0 + i, true)!!
        }
        assertEquals(6, RadioStations.favorites(stations).size)
        // Седьмая — не помещается.
        stations = RadioStations.upsertIn(stations, "fm", 96.0)
        assertNull(RadioStations.favoriteIn(stations, "fm", 96.0, true))
        // Уже избранную можно убрать и добавить другую.
        assertTrue(RadioStations.favoriteIn(stations, "fm", 90.0, false) != null)
    }

    @Test
    fun upsertKeepsOwnNameAndFavorite() {
        var stations = RadioStations.upsertIn(list, "fm", 101.5, "Европа Плюс")
        stations = RadioStations.favoriteIn(stations, "fm", 101.5, true)!!
        stations = RadioStations.renamedIn(stations, "fm", 101.5, "Моё радио")
        // Повторный обход диапазона не теряет своё имя.
        stations = RadioStations.upsertIn(stations, "fm", 101.5, "Europa Plus")
        val station = RadioStations.find(stations, "fm", 101.5)!!
        assertEquals("Моё радио", station.name)
        assertEquals("Europa Plus", station.rds)
        assertTrue(station.favorite)
    }

    @Test
    fun upsertAddsNewStation() {
        val stations = RadioStations.upsertIn(list, "fm", 90.1, "Радио Шок")
        assertEquals(5, stations.size)
        assertEquals("Радио Шок", RadioStations.find(stations, "fm", 90.1)?.rds)
    }

    @Test
    fun removeTakesStationAway() {
        val stations = RadioStations.removedIn(list, "fm", 101.5)
        assertEquals(3, stations.size)
        assertNull(RadioStations.find(stations, "fm", 101.5))
    }

    @Test
    fun jsonRoundTrip() {
        val json = RadioStations.serialize(list)
        assertEquals(list, RadioStations.parse(json))
    }

    @Test
    fun brokenJsonGivesEmptyList() {
        assertTrue(RadioStations.parse("не json").isEmpty())
        assertTrue(RadioStations.parse(null).isEmpty())
    }

    @Test
    fun frequenciesAreNormalizedToChannelStep() {
        assertEquals(101.5, RadioStations.normalize("fm", 101.52), 0.001)
        assertEquals(999.0, RadioStations.normalize("am", 999.4), 0.001)
        assertEquals("101.5", RadioStations.freqText("fm", 101.5))
        assertEquals("104", RadioStations.freqText("fm", 104.0))
        assertEquals("999", RadioStations.freqText("am", 999.0))
    }

    @Test
    fun sameFreqAllowsChannelJitter() {
        assertTrue(RadioStations.sameFreq("fm", 101.5, 101.52))
        assertFalse(RadioStations.sameFreq("fm", 101.5, 101.7))
        assertTrue(RadioStations.sameFreq("am", 999.0, 999.2))
    }

    @Test
    fun parseFrequencyOfService() {
        assertEquals(101.5, Json.parseFrequency("101.5MHz")!!, 0.001)
        assertEquals(999.0, Json.parseFrequency("999 kHz")!!, 0.001)
        assertNull(Json.parseFrequency(null))
        assertNull(Json.parseFrequency("—"))
    }
}
