package com.dash.headunit.widgets

import android.content.Context
import android.view.Display

/**
 * Общее для навигации: кандидаты-приложения и поиск дисплея приборки.
 * Раньше жило в виджете карты (удалён — осталось встраивание через
 * виджет «Приложение» и кнопка «На приборку» в топ-баре).
 */
object NavApps {

    /** Кандидаты в навигацию: подпись + пакет (по приоритету). */
    val NAV_APPS = listOf(
        "Яндекс Навигатор" to "ru.yandex.yandexnavi",
        "Яндекс Карты" to "ru.yandex.yandexmaps",
        "Google Карты" to "com.google.android.apps.maps",
        "2ГИС" to "ru.dublgis.dgismobile",
        "OsmAnd" to "net.osmand.plus"
    )

    /**
     * Id второго дисплея (приборки), если есть. Как у Jarvis:
     * запуск через ActivityOptions.setLaunchDisplayId.
     */
    fun getClusterDisplayId(context: Context): Int? {
        return try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE)
                as? android.hardware.display.DisplayManager ?: return null
            dm.displays.firstOrNull {
                it.displayId != Display.DEFAULT_DISPLAY
            }?.displayId
        } catch (e: Exception) {
            null
        }
    }
}
