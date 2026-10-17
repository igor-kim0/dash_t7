package com.dash.headunit.weather

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.SimpleTimeZone

/**
 * Запасной источник погоды — OpenWeatherMap, бесплатные endpoint'ы 2.5:
 * текущая погода (/weather) + 3-часовой прогноз на 5 дней (/forecast).
 * Требуется API-ключ (бесплатный тариф: 60 запр/мин).
 *
 * Коды состояний OWM (id) конвертируются в WMO-коды Open-Meteo,
 * поэтому вся остальная цепочка (модели, кэш, виджет) не меняется.
 */
object OwmClient {

    /**
     * @param hoursCount сколько ближайших 3-часовых точек вернуть (нужно 3).
     * @param daysCount сколько ближайших дней вернуть (нужно 3).
     */
    suspend fun fetchForecast(
        latitude: Double,
        longitude: Double,
        apiKey: String,
        hoursCount: Int = 3,
        daysCount: Int = 3,
        isFallbackLocation: Boolean = false
    ): WeatherData = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw WeatherException("empty api key")
        val query = "lat=$latitude&lon=$longitude&appid=$apiKey&units=metric&lang=ru"
        val current = JSONObject(httpGet("https://api.openweathermap.org/data/2.5/weather?$query"))
        val forecast = JSONObject(httpGet("https://api.openweathermap.org/data/2.5/forecast?$query"))
        parse(current, forecast, latitude, longitude, hoursCount, daysCount, isFallbackLocation)
    }

    /** Разбор пары ответов /weather + /forecast. Видимый для unit-тестов. */
    fun parse(
        current: JSONObject,
        forecast: JSONObject,
        latitude: Double,
        longitude: Double,
        hoursCount: Int,
        daysCount: Int,
        isFallbackLocation: Boolean
    ): WeatherData {
        // Сдвиг таймзоны точки прогноза — секунды от UTC.
        val offsetSec = forecast.optJSONObject("city")?.optInt("timezone", 0) ?: 0
        val zone = SimpleTimeZone(offsetSec * 1000, "owm")
        val labelFmt = SimpleDateFormat("HH:00", Locale.US).apply { timeZone = zone }
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone }

        val nowTemp = current.optJSONObject("main")?.optDouble("temp")
            ?.takeIf { !it.isNaN() }
        val nowCode = current.optJSONArray("weather")?.optJSONObject(0)
            ?.optInt("id", -1)?.takeIf { it > 0 }?.let(::owmToWmo)

        val city = current.optString("name").takeIf { it.isNotBlank() }

        val list = forecast.optJSONArray("list") ?: JSONArray()

        // Ближайшие 3-часовые точки (шаг сетки OWM — 3 часа).
        val hours = mutableListOf<HourlyPoint>()
        for (i in 0 until minOf(hoursCount, list.length())) {
            val e = list.getJSONObject(i)
            val main = e.getJSONObject("main")
            val id = e.optJSONArray("weather")?.optJSONObject(0)?.optInt("id", -1) ?: -1
            hours.add(
                HourlyPoint(
                    label = labelFmt.format(Date(e.getLong("dt") * 1000)),
                    temperatureC = main.getDouble("temp"),
                    weatherCode = owmToWmo(id)
                )
            )
        }

        // Группируем 3-часовые точки по локальным суткам.
        val byDay = linkedMapOf<String, MutableList<JSONObject>>()
        for (i in 0 until list.length()) {
            val e = list.getJSONObject(i)
            val key = dayFmt.format(Date(e.getLong("dt") * 1000))
            byDay.getOrPut(key) { mutableListOf() }.add(e)
        }
        val days = mutableListOf<DailyPoint>()
        for ((dateIso, entries) in byDay.entries.take(daysCount)) {
            var max = Double.NEGATIVE_INFINITY
            var min = Double.POSITIVE_INFINITY
            // Состояние дня — точка, ближайшая к 12:00.
            var noonEntry = entries.first()
            var noonDiff = Long.MAX_VALUE
            for (e in entries) {
                val main = e.getJSONObject("main")
                max = maxOf(max, main.optDouble("temp_max", main.getDouble("temp")))
                min = minOf(min, main.optDouble("temp_min", main.getDouble("temp")))
                val cal = java.util.Calendar.getInstance(zone).apply {
                    timeInMillis = e.getLong("dt") * 1000
                }
                val diff = kotlin.math.abs(cal.get(java.util.Calendar.HOUR_OF_DAY) - 12)
                if (diff < noonDiff) {
                    noonDiff = diff.toLong()
                    noonEntry = e
                }
            }
            val noonId = noonEntry.optJSONArray("weather")?.optJSONObject(0)?.optInt("id", -1) ?: -1
            days.add(
                DailyPoint(
                    dateIso = dateIso,
                    maxC = max.takeIf { it != Double.NEGATIVE_INFINITY } ?: 0.0,
                    minC = min.takeIf { it != Double.POSITIVE_INFINITY } ?: 0.0,
                    weatherCode = owmToWmo(noonId)
                )
            )
        }

        return WeatherData(
            latitude = latitude,
            longitude = longitude,
            city = city,
            nowTempC = nowTemp,
            nowCode = nowCode,
            hours = hours,
            days = days,
            fetchedAtMillis = System.currentTimeMillis(),
            isFallbackLocation = isFallbackLocation,
            source = WeatherData.SOURCE_OWM
        )
    }

    /**
     * Конвертация кодов OWM (https://openweathermap.org/weather-conditions)
     * в WMO-коды: гроза→95, морось→51, дождь→61/63/65, снег→71/73/75 и т.д.
     */
    fun owmToWmo(id: Int): Int = when (id) {
        800 -> 0
        801 -> 1
        802 -> 2
        803, 804 -> 3
        in 200..232 -> 95
        in 300..321 -> 51
        500 -> 61
        501 -> 63
        502, 503, 504 -> 65
        511 -> 66
        520 -> 80
        521, 531 -> 81
        522 -> 82
        600, 615, 616, 620 -> 71
        601, 621 -> 73
        602, 622 -> 75
        611, 612, 613 -> 77
        in 701..781 -> 45
        else -> 3
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) {
                throw WeatherException("invalid api key (HTTP 401)")
            }
            if (code != HttpURLConnection.HTTP_OK) {
                throw WeatherException("HTTP $code: $url")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    class WeatherException(message: String) : Exception(message)
}
