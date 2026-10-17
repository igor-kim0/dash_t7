package com.dash.headunit.weather

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Клиент Open-Meteo (https://open-meteo.com).
 * Бесплатно для некоммерческого использования, ключ API не нужен,
 * внешних зависимостей кроме встроенного org.json нет.
 *
 * За один запрос забираем current + hourly (ближайшие часы) + daily.
 * Название города — отдельным бесплатным запросом reverse-geocode
 * (не критично: при ошибке просто показываем координаты).
 */
object OpenMeteoClient {

    /**
     * @param hoursCount сколько ближайших часовых точек вернуть (нужно 3).
     * @param daysCount сколько ближайших дней вернуть (нужно 3).
     */
    suspend fun fetchForecast(
        latitude: Double,
        longitude: Double,
        hoursCount: Int = 3,
        daysCount: Int = 3,
        isFallbackLocation: Boolean = false
    ): WeatherData = withContext(Dispatchers.IO) {
        val url =
            "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$latitude&longitude=$longitude" +
                "&current=temperature_2m,weather_code" +
                "&hourly=temperature_2m,weather_code" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
                "&timezone=auto&forecast_days=$daysCount"
        val body = httpGet(url)
        val data = parse(
            JSONObject(body), latitude, longitude,
            hoursCount, daysCount, isFallbackLocation
        )
        // Город — best effort, не валим весь прогноз при ошибке.
        val city = try {
            reverseGeocode(latitude, longitude)
        } catch (e: Exception) {
            null
        }
        data.copy(city = city)
    }

    /** Разбор ответа /v1/forecast. Видимый для unit-тестов. */
    fun parse(
        root: JSONObject,
        latitude: Double,
        longitude: Double,
        hoursCount: Int,
        daysCount: Int,
        isFallbackLocation: Boolean,
        city: String? = null
    ): WeatherData {
        val current = root.optJSONObject("current")
        val nowTemp = current?.optDouble("temperature_2m")
            ?.takeIf { !it.isNaN() }
        val nowCode = current?.optInt("weather_code", -1)
            ?.takeIf { it >= 0 }

        val hourly = root.getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        val temps = hourly.getJSONArray("temperature_2m")
        val codes = hourly.optJSONArray("weather_code")
            ?: hourly.optJSONArray("weathercode")
            ?: throw WeatherException("no weather_code")

        // Текущий час в таймзоне прогноза: первая точка >= "сейчас".
        // API отдаёт локальное время, сравниваем по префиксу "yyyy-MM-dd'T'HH".
        val nowHourPrefix =
            SimpleDateFormat("yyyy-MM-dd'T'HH", Locale.US).format(Date())
        var start = 0
        for (i in 0 until times.length()) {
            if (times.getString(i).startsWith(nowHourPrefix)) {
                start = i
                break
            }
        }
        val hours = mutableListOf<HourlyPoint>()
        for (i in start until minOf(start + hoursCount, times.length())) {
            val iso = times.getString(i)
            hours.add(
                HourlyPoint(
                    label = iso.substringAfter("T").take(5),
                    temperatureC = temps.getDouble(i),
                    weatherCode = codes.getInt(i)
                )
            )
        }

        val daily = root.getJSONObject("daily")
        val dates = daily.getJSONArray("time")
        val maxs = daily.getJSONArray("temperature_2m_max")
        val mins = daily.getJSONArray("temperature_2m_min")
        val dcodes = daily.optJSONArray("weather_code")
            ?: daily.optJSONArray("weathercode")
            ?: throw WeatherException("no daily weather_code")
        val days = mutableListOf<DailyPoint>()
        for (i in 0 until minOf(daysCount, dates.length())) {
            days.add(
                DailyPoint(
                    dateIso = dates.getString(i),
                    maxC = maxs.getDouble(i),
                    minC = mins.getDouble(i),
                    weatherCode = dcodes.getInt(i)
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
            isFallbackLocation = isFallbackLocation
        )
    }

    /** Бесплатный reverse-geocode Open-Meteo, язык русский. */
    fun reverseGeocode(latitude: Double, longitude: Double): String? {
        val url =
            "https://geocoding-api.open-meteo.com/v1/reverse" +
                "?latitude=$latitude&longitude=$longitude" +
                "&language=" + URLEncoder.encode("ru", "UTF-8") +
                "&format=json"
        val body = httpGet(url)
        val results = JSONObject(body).optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        return results.getJSONObject(0).optString("name").takeIf { it.isNotBlank() }
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.requestMethod = "GET"
            val code = conn.responseCode
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
