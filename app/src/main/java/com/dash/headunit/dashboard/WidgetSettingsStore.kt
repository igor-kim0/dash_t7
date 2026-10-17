package com.dash.headunit.dashboard

import android.content.Context

/**
 * Настройки отдельных виджетов (ключ — widgetId).
 * Пока используются виджетом погоды: число дней/часов и единицы.
 */
class WidgetSettingsStore(context: Context) {

    private val prefs =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getDays(widgetId: String, default: Int = 3): Int =
        prefs.getInt(key(widgetId, "days"), default).coerceIn(MIN_COUNT, MAX_COUNT)

    fun getHours(widgetId: String, default: Int = 3): Int =
        prefs.getInt(key(widgetId, "hours"), default).coerceIn(MIN_COUNT, MAX_COUNT)

    /** "C" или "F". */
    fun getUnit(widgetId: String): String =
        (prefs.getString(key(widgetId, "unit"), "C") ?: "C").takeIf { it == "F" } ?: "C"

    fun setDays(widgetId: String, value: Int) {
        prefs.edit().putInt(key(widgetId, "days"), value.coerceIn(MIN_COUNT, MAX_COUNT)).apply()
    }

    fun setHours(widgetId: String, value: Int) {
        prefs.edit().putInt(key(widgetId, "hours"), value.coerceIn(MIN_COUNT, MAX_COUNT)).apply()
    }

    fun setUnit(widgetId: String, unit: String) {
        prefs.edit().putString(key(widgetId, "unit"), if (unit == "F") "F" else "C").apply()
    }

    /** Произвольная строковая опция (напр. пакет навигации карты). */
    fun getOption(widgetId: String, name: String, default: String): String =
        prefs.getString(key(widgetId, "opt_$name"), default) ?: default

    fun setOption(widgetId: String, name: String, value: String) {
        prefs.edit().putString(key(widgetId, "opt_$name"), value).apply()
    }

    private fun key(widgetId: String, name: String) = "widget_${widgetId}_$name"

    companion object {
        const val MIN_COUNT = 1
        const val MAX_COUNT = 5
        private const val PREFS = "widget_settings"
    }
}
