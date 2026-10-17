package com.dash.headunit.weather

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.SimpleTimeZone

/**
 * Проверка разбора пары ответов OpenWeatherMap
 * (/data/2.5/weather + /data/2.5/forecast) на синтетическом примере.
 * Сеть не используется.
 */
class OwmClientTest {

    private val zone = SimpleTimeZone(3 * 3600 * 1000, "msk")

    private fun entry(hourOfDay: Int, dayOffset: Int, temp: Double, id: Int): JSONObject {
        val cal = Calendar.getInstance(zone).apply {
            set(2026, Calendar.OCTOBER, 10, 21, 0, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, dayOffset)
            set(Calendar.HOUR_OF_DAY, hourOfDay)
        }
        return JSONObject()
            .put("dt", cal.timeInMillis / 1000)
            .put(
                "main", JSONObject()
                    .put("temp", temp)
                    .put("temp_min", temp - 1)
                    .put("temp_max", temp + 1)
            )
            .put(
                "weather", JSONArray()
                    .put(JSONObject().put("id", id).put("description", "дождь"))
            )
    }

    @Test
    fun parse_syntheticResponses_returnsHoursDaysAndCity() {
        val current = JSONObject()
            .put("name", "Москва")
            .put("main", JSONObject().put("temp", 7.5))
            .put("weather", JSONArray().put(JSONObject().put("id", 803)))

        val list = JSONArray()
        // День 0: одна точка 21:00.
        list.put(entry(21, 0, 8.0, 500))
        // День 1: точки 00..21 (8 шт), в 12:00 гроза.
        list.put(entry(0, 1, 6.0, 800))
        list.put(entry(3, 1, 5.0, 801))
        list.put(entry(6, 1, 5.5, 802))
        list.put(entry(9, 1, 7.0, 803))
        list.put(entry(12, 1, 10.0, 212))
        list.put(entry(15, 1, 11.0, 501))
        list.put(entry(18, 1, 9.0, 600))
        list.put(entry(21, 1, 7.0, 741))
        // День 2: три точки.
        list.put(entry(0, 2, 4.0, 800))
        list.put(entry(3, 2, 3.0, 800))
        list.put(entry(6, 2, 3.5, 800))

        val forecast = JSONObject()
            .put("city", JSONObject().put("timezone", 10800))
            .put("list", list)

        val data = OwmClient.parse(current, forecast, 55.75, 37.61, 3, 3, false)

        assertEquals(WeatherData.SOURCE_OWM, data.source)
        assertEquals("Москва", data.city)
        assertEquals(7.5, data.nowTempC!!, 0.001)
        assertEquals(3, data.nowCode) // 803 -> WMO 3

        // Часы: первые 3 точки списка как есть.
        assertEquals(3, data.hours.size)
        assertEquals("21:00", data.hours[0].label)
        assertEquals(8.0, data.hours[0].temperatureC, 0.001)
        assertEquals(61, data.hours[0].weatherCode) // 500 -> 61
        assertEquals("00:00", data.hours[1].label)
        assertEquals(0, data.hours[1].weatherCode) // 800 -> 0

        // Дни: 3 штуки.
        assertEquals(3, data.days.size)
        assertEquals("2026-10-10", data.days[0].dateIso)
        assertEquals(9.0, data.days[0].maxC, 0.001)
        assertEquals(7.0, data.days[0].minC, 0.001)

        assertEquals("2026-10-11", data.days[1].dateIso)
        assertEquals(12.0, data.days[1].maxC, 0.001) // 11.0 + 1
        assertEquals(4.0, data.days[1].minC, 0.001) // 5.0 - 1
        assertEquals(95, data.days[1].weatherCode) // точка 12:00, id 212 -> 95

        assertEquals("2026-10-12", data.days[2].dateIso)
    }

    @Test
    fun owmToWmo_mapping() {
        assertEquals(0, OwmClient.owmToWmo(800))
        assertEquals(1, OwmClient.owmToWmo(801))
        assertEquals(3, OwmClient.owmToWmo(804))
        assertEquals(95, OwmClient.owmToWmo(212))
        assertEquals(51, OwmClient.owmToWmo(300))
        assertEquals(61, OwmClient.owmToWmo(500))
        assertEquals(65, OwmClient.owmToWmo(502))
        assertEquals(73, OwmClient.owmToWmo(601))
        assertEquals(75, OwmClient.owmToWmo(602))
        assertEquals(45, OwmClient.owmToWmo(741))
    }
}
