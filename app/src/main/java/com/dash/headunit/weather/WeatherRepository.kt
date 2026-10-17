package com.dash.headunit.weather

import android.content.Context
import com.dash.headunit.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Репозиторий погоды: основной источник — Open-Meteo (без ключа),
 * запасной — OpenWeatherMap (нужен бесплатный ключ).
 * Плюс кэш последнего удачного прогноза на случай отсутствия интернета.
 *
 * Ключ OWM берётся из BuildConfig (генерируется из local.properties,
 * ключ openweathermap.key) и не хранится в git.
 */
class WeatherRepository(context: Context) {

    private val prefs =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    suspend fun refresh(
        latitude: Double,
        longitude: Double,
        isFallbackLocation: Boolean
    ): WeatherData {
        val key = BuildConfig.OPENWEATHER_KEY
        // При наличии ключа OWM идёт первым (стабильнее и быстрее),
        // иначе единственный вариант — Open-Meteo.
        return if (key.isBlank()) {
            val data = OpenMeteoClient.fetchForecast(
                latitude, longitude,
                hoursCount = HOURS_COUNT,
                daysCount = DAYS_COUNT,
                isFallbackLocation = isFallbackLocation
            )
            saveCache(data)
            data
        } else {
            try {
                val data = OwmClient.fetchForecast(
                    latitude, longitude, key,
                    hoursCount = HOURS_COUNT,
                    daysCount = DAYS_COUNT,
                    isFallbackLocation = isFallbackLocation
                )
                saveCache(data)
                data
            } catch (fallback: Exception) {
                try {
                    val data = OpenMeteoClient.fetchForecast(
                        latitude, longitude,
                        hoursCount = HOURS_COUNT,
                        daysCount = DAYS_COUNT,
                        isFallbackLocation = isFallbackLocation
                    )
                    saveCache(data)
                    data
                } catch (primary: Exception) {
                    // Отдаём ошибку OWM — она информативнее для диагностики.
                    throw fallback
                }
            }
        }
    }

    fun cached(): WeatherData? {
        val raw = prefs.getString(KEY_CACHE, null) ?: return null
        return try {
            val o = JSONObject(raw)
            val hours = o.optJSONArray("hours")?.let { arr ->
                List(arr.length()) { i ->
                    val p = arr.getJSONObject(i)
                    HourlyPoint(p.getString("label"), p.getDouble("temp"), p.getInt("code"))
                }
            } ?: emptyList()
            // Кэш без почасовки (от старых версий) — считаем протухшим.
            if (hours.isEmpty()) return null
            WeatherData(
                latitude = o.getDouble("lat"),
                longitude = o.getDouble("lon"),
                city = o.optString("city").takeIf { it.isNotBlank() },
                nowTempC = o.optDouble("nowTemp").takeIf { !it.isNaN() },
                nowCode = o.optInt("nowCode", -1).takeIf { it >= 0 },
                hours = hours,
                days = o.getJSONArray("days").let { arr ->
                    List(arr.length()) { i ->
                        val p = arr.getJSONObject(i)
                        DailyPoint(p.getString("d"), p.getDouble("max"), p.getDouble("min"), p.getInt("code"))
                    }
                },
                fetchedAtMillis = o.getLong("fetchedAt"),
                isFallbackLocation = o.optBoolean("fallback", false),
                source = o.optString("source", WeatherData.SOURCE_OPEN_METEO)
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun saveCache(data: WeatherData) {
        val hours = JSONArray()
        data.hours.forEach {
            hours.put(JSONObject().put("label", it.label).put("temp", it.temperatureC).put("code", it.weatherCode))
        }
        val days = JSONArray()
        data.days.forEach {
            days.put(JSONObject().put("d", it.dateIso).put("max", it.maxC).put("min", it.minC).put("code", it.weatherCode))
        }
        val o = JSONObject()
            .put("lat", data.latitude)
            .put("lon", data.longitude)
            .put("hours", hours)
            .put("days", days)
            .put("fetchedAt", data.fetchedAtMillis)
            .put("fallback", data.isFallbackLocation)
            .put("source", data.source)
        data.city?.let { o.put("city", it) }
        data.nowTempC?.let { o.put("nowTemp", it) }
        data.nowCode?.let { o.put("nowCode", it) }
        prefs.edit().putString(KEY_CACHE, o.toString()).apply()
    }

    companion object {
        private const val PREFS = "weather_prefs"
        private const val KEY_CACHE = "cache_v1"
        /** Сколько часов/дней тянем (виджеты показывают первые N по своим настройкам). */
        const val HOURS_COUNT = 5
        const val DAYS_COUNT = 5
    }
}
