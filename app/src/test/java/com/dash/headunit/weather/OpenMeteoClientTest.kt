package com.dash.headunit.weather

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Проверка разбора ответа Open-Meteo (/v1/forecast) на сгенерированном примере.
 * Сеть не используется; времена строятся относительно текущего часа,
 * как их отдал бы сервер (timezone=auto).
 */
class OpenMeteoClientTest {

    @Test
    fun parse_sampleForecast_returnsCurrentHoursAndDays() {
        val now = Calendar.getInstance()
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:00", Locale.US)

        val times = JSONArray()
        val temps = JSONArray()
        val codes = JSONArray()
        val cal = now.clone() as Calendar
        cal.add(Calendar.HOUR_OF_DAY, -2)
        for (i in 0 until 30) {
            times.put(iso.format(cal.time))
            temps.put(10.0 + i)
            codes.put(if (i % 2 == 0) 3 else 61)
            cal.add(Calendar.HOUR_OF_DAY, 1)
        }

        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val dates = JSONArray()
        val maxs = JSONArray()
        val mins = JSONArray()
        val dcodes = JSONArray()
        val dayCal = now.clone() as Calendar
        repeat(5) {
            dates.put(dayFmt.format(dayCal.time))
            maxs.put(15.0)
            mins.put(5.0)
            dcodes.put(2)
            dayCal.add(Calendar.DAY_OF_MONTH, 1)
        }

        val root = JSONObject()
            .put("current", JSONObject().put("temperature_2m", 12.5).put("weather_code", 3))
            .put(
                "hourly", JSONObject()
                    .put("time", times)
                    .put("temperature_2m", temps)
                    .put("weather_code", codes)
            )
            .put(
                "daily", JSONObject()
                    .put("time", dates)
                    .put("temperature_2m_max", maxs)
                    .put("temperature_2m_min", mins)
                    .put("weather_code", dcodes)
            )

        val data = OpenMeteoClient.parse(root, 55.75, 37.61, 3, 3, false, city = "Москва")

        assertEquals("Москва", data.city)
        assertEquals(12.5, data.nowTempC!!, 0.001)
        assertEquals(3, data.nowCode)

        // 3 часа начиная с текущего.
        val labelFmt = SimpleDateFormat("HH:00", Locale.US)
        assertEquals(3, data.hours.size)
        for (k in 0 until 3) {
            val c = now.clone() as Calendar
            c.add(Calendar.HOUR_OF_DAY, k)
            assertEquals(labelFmt.format(c.time), data.hours[k].label)
        }

        // 3 дня начиная с сегодня.
        assertEquals(3, data.days.size)
        assertEquals(dayFmt.format(Date()), data.days[0].dateIso)
        assertEquals(15.0, data.days[0].maxC, 0.001)
        assertEquals(5.0, data.days[0].minC, 0.001)
        assertEquals(2, data.days[0].weatherCode)
    }

    @Test
    fun codes_russianDescriptions() {
        assertEquals("Ясно", WmoCodes.describe(0))
        assertEquals("Дождь", WmoCodes.describe(63))
        assertEquals("Гроза с градом", WmoCodes.describe(99))
        assertEquals("Пасмурно", WmoCodes.describe(3))
    }
}
