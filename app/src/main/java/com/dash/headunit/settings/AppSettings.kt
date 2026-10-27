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

    /**
     * Как показывать маршрут на приборке: [CLUSTER_NATIVE] — родной
     * интерфейс приборки (стрелка, расстояние, улица, знак лимита) или
     * [CLUSTER_OVERLAY] — своё окно в виде «Карты» приборки.
     */
    fun clusterMode(context: Context): Int =
        prefs(context).getInt(KEY_CLUSTER_MODE, CLUSTER_NATIVE)

    fun setClusterMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_CLUSTER_MODE, mode).apply()
    }

    /**
     * Вариант родного интерфейса для подбора на ГУ ([CLUSTER_VARIANTS]):
     * что слать, когда тип манёвра неизвестен (Яндекс на экране), слать ли
     * знак лимита и повторять ли подсказку. По умолчанию 2.
     */
    fun clusterVariant(context: Context): Int =
        prefs(context).getInt(KEY_CLUSTER_VARIANT, 2).coerceIn(1, CLUSTER_VARIANTS)

    fun setClusterVariant(context: Context, variant: Int) {
        prefs(context).edit().putInt(KEY_CLUSTER_VARIANT, variant).apply()
    }

    /** Ширина центральной полосы картинки на приборке, px (по бокам — шкалы приборки). */
    fun clusterWidth(context: Context): Int =
        prefs(context).getInt(KEY_CLUSTER_WIDTH, 1100)

    fun setClusterWidth(context: Context, px: Int) {
        prefs(context).edit().putInt(KEY_CLUSTER_WIDTH, px).apply()
    }

    /**
     * Сколько дней хранить журнал поездок ([TripTracker]):
     * записи старше срока удаляются. 0 — хранить без ограничения.
     * По умолчанию 7 дней (неделя).
     */
    fun journalKeepDays(context: Context): Int =
        prefs(context).getInt(KEY_JOURNAL_KEEP_DAYS, JOURNAL_KEEP_DEFAULT)

    fun setJournalKeepDays(context: Context, days: Int) {
        prefs(context).edit().putInt(KEY_JOURNAL_KEEP_DAYS, days).apply()
    }

    /**
     * Открывать шторку люка при старте, если она была открыта
     * при окончании последней поездки ([com.dash.headunit.car.ShadeResume],
     * оценка состояния — ячейка 0,10: 6 = открыто).
     */
    fun shadeResume(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHADE_RESUME, false)

    fun setShadeResume(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHADE_RESUME, enabled).apply()
    }

    /**
     * Вход в инженерное меню (ETM) приборки ради °C ОЖ
     * ([com.dash.headunit.car.CoolantEtm]): запись в tx при старте и
     * переподключениях к приборке. Вкл по умолчанию — осознанная запись
     * в MCU ради честной температуры ОЖ.
     */
    fun etmCoolant(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ETM_COOLANT, true)

    fun setEtmCoolant(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ETM_COOLANT, enabled).apply()
    }

    const val CLUSTER_NATIVE = 0
    const val CLUSTER_OVERLAY = 1
    const val CLUSTER_VARIANTS = 5

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    private const val KEY_AUTOSTART = "autostart"
    private const val KEY_BAR_LEFT = "bar_side_left"
    private const val KEY_CLUSTER_NAVI = "cluster_navi"
    private const val KEY_CLUSTER_WIDTH = "cluster_width"
    private const val KEY_CLUSTER_MODE = "cluster_mode"
    private const val KEY_CLUSTER_VARIANT = "cluster_variant"
    private const val KEY_JOURNAL_KEEP_DAYS = "journal_keep_days"
    private const val KEY_SHADE_RESUME = "shade_resume"
    private const val KEY_ETM_COOLANT = "etm_coolant"

    /** Срок хранения журнала поездок по умолчанию, дней. */
    private const val JOURNAL_KEEP_DEFAULT = 7
}
