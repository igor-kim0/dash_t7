package com.dash.headunit.dashboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Загрузка/сохранение раскладки виджетов.
 * Хранится в SharedPreferences в виде JSON.
 */
class DashboardStore(context: Context) {
    private val prefs =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Загрузка раскладки. Дефолт — только при первом запуске (ключа нет вообще).
     * Пустой список — осознанное состояние "дашборд очищен", его не перезаписываем.
     */
    fun load(): List<WidgetSpec> {
        val raw = prefs.getString(KEY_LAYOUT, null) ?: return defaultLayout()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getJSONObject(it) }.filter {
                it.getString("type") !in WidgetType.REMOVED_IDS
            }.map { o ->
                WidgetSpec(
                    widgetId = o.getString("id"),
                    type = WidgetType.byId(o.getString("type")),
                    x = o.getInt("x"),
                    y = o.getInt("y"),
                    w = o.getInt("w"),
                    h = o.getInt("h")
                ).clamped()
            }
        } catch (e: Exception) {
            defaultLayout()
        }
    }

    fun save(specs: List<WidgetSpec>) {
        val arr = JSONArray()
        specs.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.widgetId)
                    .put("type", it.type.id)
                    .put("x", it.x)
                    .put("y", it.y)
                    .put("w", it.w)
                    .put("h", it.h)
            )
        }
        prefs.edit().putString(KEY_LAYOUT, arr.toString()).apply()
    }

    fun reset() {
        prefs.edit().remove(KEY_LAYOUT).apply()
    }

    /** Очистить дашборд: сохранить пустой список (не дефолт!). */
    fun clearAll() {
        save(emptyList())
    }

    /** Раскладка по умолчанию для альбомного экрана ГУ. */
    fun defaultLayout(): List<WidgetSpec> = listOf(
        WidgetSpec("combo1", WidgetType.COMBO, x = 4, y = 6, w = 60, h = 24),
        WidgetSpec("forecast1", WidgetType.WEATHER, x = 4, y = 32, w = 92, h = 34)
    )

    companion object {
        private const val PREFS = "dashboard_prefs"
        private const val KEY_LAYOUT = "layout_v1"
    }
}
