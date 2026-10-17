package com.dash.headunit.settings

import android.content.Context

/** Настройки приложения (кнопка-шестерёнка в колонке справа). */
object AppSettings {

    /** Открывать дашборд после загрузки ГУ ([AutostartReceiver]). */
    fun autostart(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTOSTART, false)

    fun setAutostart(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTOSTART, enabled).apply()
    }

    /** Вертикальная колонка иконок слева (true) или справа (false, по умолчанию). */
    fun barSideLeft(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BAR_LEFT, false)

    fun setBarSideLeft(context: Context, left: Boolean) {
        prefs(context).edit().putBoolean(KEY_BAR_LEFT, left).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    private const val KEY_AUTOSTART = "autostart"
    private const val KEY_BAR_LEFT = "bar_side_left"
}
