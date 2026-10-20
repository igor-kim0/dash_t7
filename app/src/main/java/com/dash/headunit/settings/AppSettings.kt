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

    /** Показывать маршрут Яндекс Навигатора на приборке ([com.dash.headunit.navi.cluster.ClusterOverlay]). */
    fun clusterNavi(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CLUSTER_NAVI, false)

    fun setClusterNavi(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CLUSTER_NAVI, enabled).apply()
    }

    /** Ширина центральной полосы картинки на приборке, px (по бокам — шкалы приборки). */
    fun clusterWidth(context: Context): Int =
        prefs(context).getInt(KEY_CLUSTER_WIDTH, 1100)

    fun setClusterWidth(context: Context, px: Int) {
        prefs(context).edit().putInt(KEY_CLUSTER_WIDTH, px).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    private const val KEY_AUTOSTART = "autostart"
    private const val KEY_BAR_LEFT = "bar_side_left"
    private const val KEY_CLUSTER_NAVI = "cluster_navi"
    private const val KEY_CLUSTER_WIDTH = "cluster_width"
}
