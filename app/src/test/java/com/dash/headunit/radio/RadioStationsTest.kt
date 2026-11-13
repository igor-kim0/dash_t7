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
    fun favoriteLimitIsTwelve() {
        val max = RadioStations.MAX_FAVORITES
        assertEquals(12, max)
        var stations = listOf<Station>()
        repeat(max) { i ->
            stations = RadioStations.upsertIn(stations, "fm", 90.0 + i)
            stations = RadioStations.favoriteIn(stations, "fm", 90.0 + i, true)!!
        }
        assertEquals(max, RadioStations.favorites(stations).size)
        // Следующая — не помещается.
        stations = RadioStations.upsertIn(stations, "fm", 90.0 + max)
        assertNull(RadioStations.favoriteIn(stations, "fm", 90.0 + max, true))
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

    @Test
    fun newFavoriteGoesToEndAndCanBeMoved() {
        var l = RadioStations.favoriteIn(list, "fm", 101.5, true)!!
        l = RadioStations.favoriteIn(l, "am", 999.0, true)!!
        // Старое избранное без места — первым, новые — за ним по порядку.
        assertEquals(listOf(107.1, 101.5, 999.0), RadioStations.favorites(l).map { it.freq })
        l = RadioStations.movedIn(l, "am", 999.0, -1)
        assertEquals(listOf(107.1, 999.0, 101.5), RadioStations.favorites(l).map { it.freq })
        // За край не уезжает.
        l = RadioStations.movedIn(l, "fm", 107.1, -1)
        assertEquals(listOf(107.1, 999.0, 101.5), RadioStations.favorites(l).map { it.freq })
        // Порядок переживает сохранение.
        val back = RadioStations.parse(RadioStations.serialize(l))
        assertEquals(listOf(107.1, 999.0, 101.5), RadioStations.favorites(back).map { it.freq })
    }

    @Test
    fun colorIsOwnOrStableByFrequency() {
        val c = RadioStations.colorOf("fm", 101.5)
        assertTrue(c in RadioStations.PALETTE.toList())
        assertEquals(c, RadioStations.colorOf(Station("fm", 101.5, name = "Другое имя")))
        val l = RadioStations.coloredIn(list, "fm", 101.5, 0xFF123456.toInt())
        val back = RadioStations.parse(RadioStations.serialize(l))
        assertEquals(0xFF123456.toInt(), RadioStations.find(back, "fm", 101.5)!!.color)
        assertNull(RadioStations.find(back, "fm", 104.0)!!.color)
    }

    @Test
    fun initialsOfTitles() {
        assertEquals("Р", RadioStations.initials("Радио Рекорд"))
        assertEquals("РF", RadioStations.initials("Ретро FM"))
        assertEquals("ДР", RadioStations.initials("Дорожное радио"))
        assertEquals("М", RadioStations.initials("Маяк"))
        assertEquals("М", RadioStations.initials("Радио Маяк"))
        assertEquals("EP", RadioStations.initials("Europa Plus"))
        assertEquals("Р", RadioStations.initials("Радио"))
        assertEquals("", RadioStations.initials("101.5 МГц".substringBefore(' ')))
        assertEquals("", RadioStations.initials(null))
    }

    @Test
    fun scanMarksMissingOnlyInItsBand() {
        val found = setOf(RadioStations.keyOf("fm", 101.5))
        val l = RadioStations.markedIn(list, "fm", found)
        assertFalse(RadioStations.find(l, "fm", 101.5)!!.missing)
        assertTrue(RadioStations.find(l, "fm", 104.0)!!.missing)
        assertFalse(RadioStations.find(l, "am", 999.0)!!.missing)
        // Отметка переживает сохранение, а повторная находка её снимает.
        val back = RadioStations.parse(RadioStations.serialize(l))
        assertTrue(RadioStations.find(back, "fm", 104.0)!!.missing)
        val again = RadioStations.upsertIn(back, "fm", 104.0)
        assertFalse(RadioStations.find(again, "fm", 104.0)!!.missing)
    }

    @Test
    fun keyRoundTrip() {
        assertEquals(101.5, RadioStations.fromKey("fm:1015")!!.freq, 0.001)
        assertEquals("am", RadioStations.fromKey("am:999")!!.band)
        assertNull(RadioStations.fromKey("dab:1"))
        assertNull(RadioStations.fromKey("мусор"))
    }

    @Test
    fun sortModes() {
        val fm = list.filter { it.band == "fm" }
        assertEquals(listOf(101.5, 104.0, 107.1),
            RadioStations.sorted(fm, RadioStations.SORT_FREQ).map { it.freq })
        // Europa Plus, Радио Победа, Ретро FM — по алфавиту (латиница раньше).
        assertEquals(listOf(101.5, 104.0, 107.1),
            RadioStations.sorted(fm.reversed(), RadioStations.SORT_NAME).map { it.freq })
        val listened = mapOf(RadioStations.keyOf("fm", 107.1) to 5000L, RadioStations.keyOf("fm", 104.0) to 100L)
        assertEquals(listOf(107.1, 104.0, 101.5),
            RadioStations.sorted(fm, RadioStations.SORT_LISTENED, listened).map { it.freq })
    }

    @Test
    fun neighborByList() {
        val mode = RadioStations.SKIP_LIST
        assertEquals(104.0, RadioStations.neighbor(list, mode, "fm", 101.5, 1)!!.freq, 0.001)
        assertEquals(101.5, RadioStations.neighbor(list, mode, "fm", 104.0, -1)!!.freq, 0.001)
        // По кругу: после последней — первая.
        assertEquals(101.5, RadioStations.neighbor(list, mode, "fm", 107.1, 1)!!.freq, 0.001)
        // С частоты не из списка — к ближайшей в эту сторону.
        assertEquals(104.0, RadioStations.neighbor(list, mode, "fm", 102.0, 1)!!.freq, 0.001)
        assertEquals(101.5, RadioStations.neighbor(list, mode, "fm", 102.0, -1)!!.freq, 0.001)
        // Ненайденные пропускаются.
        val l = RadioStations.markedIn(list, "fm", setOf(RadioStations.keyOf("fm", 101.5), RadioStations.keyOf("fm", 107.1)))
        assertEquals(107.1, RadioStations.neighbor(l, mode, "fm", 101.5, 1)!!.freq, 0.001)
        // Одна станция в диапазоне и она играет — переходить некуда.
        assertNull(RadioStations.neighbor(list, mode, "am", 999.0, 1))
    }

    @Test
    fun neighborByFavoritesCrossesBands() {
        var l = RadioStations.favoriteIn(list, "am", 999.0, true)!!
        val mode = RadioStations.SKIP_FAVORITES
        // Ряд: 107.1 FM, 999 AM.
        assertEquals(999.0, RadioStations.neighbor(l, mode, "fm", 107.1, 1)!!.freq, 0.001)
        assertEquals(107.1, RadioStations.neighbor(l, mode, "am", 999.0, 1)!!.freq, 0.001)
        // Не из избранного: ⏭ — первая, ⏮ — последняя.
        assertEquals(107.1, RadioStations.neighbor(l, mode, "fm", 101.5, 1)!!.freq, 0.001)
        assertEquals(999.0, RadioStations.neighbor(l, mode, "fm", 101.5, -1)!!.freq, 0.001)
        // Поиск тюнером — соседей не ищем.
        assertNull(RadioStations.neighbor(l, RadioStations.SKIP_SEEK, "fm", 101.5, 1))
        l = RadioStations.favoriteIn(l, "am", 999.0, false)!!
        assertNull(RadioStations.neighbor(l, mode, "fm", 107.1, 1))
    }
}
