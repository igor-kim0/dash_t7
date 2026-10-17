package com.dash.headunit.weather

/** Одна точка почасового прогноза. */
data class HourlyPoint(
    /** Подпись точки, уже отформатированная ("14:00"). */
    val label: String,
    val temperatureC: Double,
    /** WMO-код погоды Open-Meteo. */
    val weatherCode: Int
)

/** Один день прогноза. */
data class DailyPoint(
    /** Дата, ISO "yyyy-MM-dd". */
    val dateIso: String,
    val maxC: Double,
    val minC: Double,
    /** WMO-код погоды Open-Meteo. */
    val weatherCode: Int
)

/** Прогноз, готовый к показу: текущая + ближайшие дни. */
data class WeatherData(
    val latitude: Double,
    val longitude: Double,
    /** Название населённого пункта (может быть null). */
    val city: String?,
    /** Текущие данные. */
    val nowTempC: Double?,
    val nowCode: Int?,
    /** Ближайшие часы (3 точки). */
    val hours: List<HourlyPoint>,
    /** Ближайшие дни. */
    val days: List<DailyPoint>,
    val fetchedAtMillis: Long,
    /** true если координаты — fallback, а не реальная геолокация. */
    val isFallbackLocation: Boolean,
    /** Источник данных: [SOURCE_OPEN_METEO] или [SOURCE_OWM]. */
    val source: String = SOURCE_OPEN_METEO
) {
    companion object {
        const val SOURCE_OPEN_METEO = "open-meteo"
        const val SOURCE_OWM = "owm"

        fun sourceDisplayName(source: String): String = when (source) {
            SOURCE_OWM -> "OWM"
            else -> "Open-Meteo"
        }
    }
}
