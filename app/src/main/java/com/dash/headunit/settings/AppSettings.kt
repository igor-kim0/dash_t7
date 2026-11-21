package com.dash.headunit.settings

import android.content.Context
import com.dash.headunit.dashboard.GridSpec

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
     * При включённом [clusterNavi]: на приборке сам Яндекс Навигатор
     * ([com.dash.headunit.navi.cluster.NaviOnCluster]), а не наша карточка.
     */
    fun clusterYandex(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CLUSTER_YANDEX, false)

    fun setClusterYandex(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CLUSTER_YANDEX, enabled).apply()
    }


    /** Ширина центральной полосы картинки на приборке, px (по бокам — шкалы приборки). */
    fun clusterWidth(context: Context): Int =
        prefs(context).getInt(KEY_CLUSTER_WIDTH, 1100)

    fun setClusterWidth(context: Context, px: Int) {
        prefs(context).edit().putInt(KEY_CLUSTER_WIDTH, px).apply()
    }

    /**
     * Тема приборки: [CLUSTER_THEME_STOCK] — карточка маршрута поверх
     * штатных приборов; [CLUSTER_THEME_MODERN] — свой тахометр со скоростью
     * по центру, штатные скорость/обороты спрятаны
     * ([com.dash.headunit.navi.cluster.ClusterGauges]). Рисуется в окне
     * маршрута на приборке, т. е. при [clusterNavi].
     */
    fun clusterTheme(context: Context): Int =
        prefs(context).getInt(KEY_CLUSTER_THEME, CLUSTER_THEME_STOCK)

    fun setClusterTheme(context: Context, id: Int) {
        prefs(context).edit().putInt(KEY_CLUSTER_THEME, id).apply()
    }

    const val CLUSTER_THEME_STOCK = 0
    const val CLUSTER_THEME_MODERN = 1

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

    /** Включать штатное радио при запуске приложения ([com.dash.headunit.radio.RadioHub.startOnLaunch]). */
    fun radioOnStart(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RADIO_ON_START, false)

    fun setRadioOnStart(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_RADIO_ON_START, enabled).apply()
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

    /**
     * Минимальное давление в шинах, бар: ниже — колесо в виджете
     * «Автомобиль» красное. Хранится в десятых бара (20 = 2,0 бар).
     */
    fun tirePressureMin(context: Context): Float =
        prefs(context).getInt(KEY_TIRE_MIN, TIRE_MIN_DEFAULT) / 10f

    fun setTirePressureMin(context: Context, bar: Float) {
        val tenths = (bar * 10).toInt().coerceIn(TIRE_MIN_MIN, TIRE_MIN_MAX)
        prefs(context).edit().putInt(KEY_TIRE_MIN, tenths).apply()
    }

    /**
     * Сетка дашборда: [GRID_FREE] — свободная 100 x 70 (как раньше),
     * [GRID_CUSTOM] — колонки x строки пользователя. Убранный режим «4 × 4»
     * (значение 1, 2026-11-19) переезжает в «Свою» 4 x 4 — вид не меняется.
     */
    fun gridMode(context: Context): Int {
        val p = prefs(context)
        val mode = p.getInt(KEY_GRID_MODE, GRID_FREE)
        if (mode == LEGACY_GRID_4X4) {
            p.edit().putInt(KEY_GRID_MODE, GRID_CUSTOM).putInt(KEY_GRID_COLS, 4).putInt(KEY_GRID_ROWS, 4).apply()
            return GRID_CUSTOM
        }
        return if (mode == GRID_CUSTOM) GRID_CUSTOM else GRID_FREE
    }

    fun gridCols(context: Context): Int =
        prefs(context).getInt(KEY_GRID_COLS, GRID_CUSTOM_DEFAULT_COLS).coerceIn(GridSpec.COLS_RANGE)

    fun gridRows(context: Context): Int =
        prefs(context).getInt(KEY_GRID_ROWS, GRID_CUSTOM_DEFAULT_ROWS).coerceIn(GridSpec.ROWS_RANGE)

    fun setGrid(context: Context, mode: Int, cols: Int, rows: Int) {
        prefs(context).edit()
            .putInt(KEY_GRID_MODE, mode)
            .putInt(KEY_GRID_COLS, cols.coerceIn(GridSpec.COLS_RANGE))
            .putInt(KEY_GRID_ROWS, rows.coerceIn(GridSpec.ROWS_RANGE))
            .apply()
    }

    /** Крупная сетка без промежутков между блоками (блоки встык). */
    fun gridNoGap(context: Context): Boolean = prefs(context).getBoolean(KEY_GRID_NO_GAP, false)

    fun setGridNoGap(context: Context, noGap: Boolean) {
        prefs(context).edit().putBoolean(KEY_GRID_NO_GAP, noGap).apply()
    }

    /** Сетка для [mode] и своих размеров. */
    fun gridSpec(mode: Int, cols: Int, rows: Int): GridSpec = when (mode) {
        GRID_CUSTOM -> GridSpec(cols.coerceIn(GridSpec.COLS_RANGE), rows.coerceIn(GridSpec.ROWS_RANGE))
        else -> GridSpec.FINE
    }

    /** Текущая сетка дашборда. */
    fun grid(context: Context): GridSpec =
        gridMode(context).let { mode -> gridSpec(mode, gridCols(context), gridRows(context)) }

    const val GRID_FREE = 0
    /** Бывший режим «4 × 4» — только для переезда в [GRID_CUSTOM]. */
    private const val LEGACY_GRID_4X4 = 1
    const val GRID_CUSTOM = 2
    private const val GRID_CUSTOM_DEFAULT_COLS = 4
    private const val GRID_CUSTOM_DEFAULT_ROWS = 4
    private const val KEY_GRID_MODE = "grid_mode"
    private const val KEY_GRID_COLS = "grid_cols"
    private const val KEY_GRID_ROWS = "grid_rows"
    private const val KEY_GRID_NO_GAP = "grid_no_gap"

    /** Алиса навигатора по кнопке руля ([com.dash.headunit.navi.WheelKeys]). */
    fun wheelAlice(context: Context): Boolean = prefs(context).getBoolean(KEY_WHEEL_ALICE, false)

    fun setWheelAlice(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_WHEEL_ALICE, on).apply()
    }

    /** Назначенная кнопка руля (keyCode), −1 — не назначена. */
    fun wheelAliceKey(context: Context): Int = prefs(context).getInt(KEY_WHEEL_ALICE_KEY, -1)

    fun setWheelAliceKey(context: Context, keyCode: Int) {
        prefs(context).edit().putInt(KEY_WHEEL_ALICE_KEY, keyCode).apply()
    }

    private const val KEY_WHEEL_ALICE = "wheel_alice"

    /** Источник координат навигатора ([com.dash.headunit.location.GpsInject]). */
    const val GPS_STOCK = 0
    const val GPS_INTERNAL = 1
    const val GPS_BT = 2

    fun gpsMode(context: Context): Int = prefs(context).getInt(KEY_GPS_MODE, GPS_STOCK)

    /** Опережение метки, мс (0 — без поправки). */
    fun gpsLeadMs(context: Context): Int = prefs(context).getInt(KEY_GPS_LEAD, 0)

    /** Адрес Bluetooth-устройства с NMEA (внешний GPS), "" — не выбрано. */
    fun gpsBtAddress(context: Context): String = prefs(context).getString(KEY_GPS_BT, "").orEmpty()

    fun setGps(context: Context, mode: Int, leadMs: Int, btAddress: String) {
        prefs(context).edit()
            .putInt(KEY_GPS_MODE, mode)
            .putInt(KEY_GPS_LEAD, leadMs)
            .putString(KEY_GPS_BT, btAddress)
            .apply()
    }

    private const val KEY_GPS_MODE = "gps_mode"
    private const val KEY_GPS_LEAD = "gps_lead_ms"
    private const val KEY_GPS_BT = "gps_bt_address"

    /**
     * Ступеней подогрева сидений у машины: 3 (по умолчанию) или 2. У
     * двухступенчатых команда «ступень 3» игнорируется — первое нажатие
     * кнопки сиденья ничего не включало.
     */
    fun seatHeatLevels(context: Context): Int =
        if (prefs(context).getInt(KEY_SEAT_HEAT_LEVELS, 3) == 2) 2 else 3

    fun setSeatHeatLevels(context: Context, levels: Int) {
        prefs(context).edit().putInt(KEY_SEAT_HEAT_LEVELS, if (levels == 2) 2 else 3).apply()
    }

    private const val KEY_SEAT_HEAT_LEVELS = "seat_heat_levels"

    /**
     * Шаг уставки климата: [TEMP_STEP_AUTO] (1°, пока шина не покажет
     * шкалу 0,5°, см. [com.dash.headunit.car.ClimateHub.halfStep]),
     * [TEMP_STEP_ONE] или [TEMP_STEP_HALF].
     */
    fun climateTempStep(context: Context): Int =
        prefs(context).getInt(KEY_TEMP_STEP, TEMP_STEP_AUTO).takeIf { it in TEMP_STEP_AUTO..TEMP_STEP_HALF } ?: TEMP_STEP_AUTO

    fun setClimateTempStep(context: Context, step: Int) {
        prefs(context).edit().putInt(KEY_TEMP_STEP, step).apply()
    }

    /** «Авто» уже увидело на шине шкалу 0,5°. */
    fun climateHalfLearned(context: Context): Boolean = prefs(context).getBoolean(KEY_TEMP_HALF_LEARNED, false)

    fun setClimateHalfLearned(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_TEMP_HALF_LEARNED, on).apply()
    }

    const val TEMP_STEP_AUTO = 0
    const val TEMP_STEP_ONE = 1
    const val TEMP_STEP_HALF = 2
    private const val KEY_TEMP_STEP = "climate_temp_step"
    private const val KEY_TEMP_HALF_LEARNED = "climate_temp_half_learned"

    /** Автопроверка обновлений при старте ([com.dash.headunit.update.UpdateUi]). */
    fun otaAutoCheck(context: Context): Boolean = prefs(context).getBoolean(KEY_OTA_AUTO_CHECK, true)

    fun setOtaAutoCheck(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_OTA_AUTO_CHECK, on).apply()
    }

    /** Версия, которую пользователь пропустил (автопроверка её не предлагает). */
    fun skippedUpdateVersion(context: Context): String? = prefs(context).getString(KEY_OTA_SKIPPED, null)

    fun setSkippedUpdateVersion(context: Context, version: String?) {
        prefs(context).edit().putString(KEY_OTA_SKIPPED, version).apply()
    }

    /** Время последней удачной проверки обновлений, мс. */
    fun otaLastCheckTime(context: Context): Long = prefs(context).getLong(KEY_OTA_LAST_CHECK, 0L)

    fun setOtaLastCheckTime(context: Context, time: Long) {
        prefs(context).edit().putLong(KEY_OTA_LAST_CHECK, time).apply()
    }

    private const val KEY_OTA_AUTO_CHECK = "ota_auto_check"
    private const val KEY_OTA_SKIPPED = "ota_skipped_version"
    private const val KEY_OTA_LAST_CHECK = "ota_last_check"
    private const val KEY_WHEEL_ALICE_KEY = "wheel_alice_key"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    private const val KEY_AUTOSTART = "autostart"
    private const val KEY_BAR_LEFT = "bar_side_left"
    private const val KEY_CLUSTER_NAVI = "cluster_navi"
    private const val KEY_CLUSTER_WIDTH = "cluster_width"
    private const val KEY_CLUSTER_THEME = "cluster_theme_v2"
    private const val KEY_CLUSTER_YANDEX = "cluster_yandex"
    private const val KEY_JOURNAL_KEEP_DAYS = "journal_keep_days"
    private const val KEY_SHADE_RESUME = "shade_resume"
    private const val KEY_RADIO_ON_START = "radio_on_start"
    private const val KEY_ETM_COOLANT = "etm_coolant"
    private const val KEY_TIRE_MIN = "tire_pressure_min"

    /** Срок хранения журнала поездок по умолчанию, дней. */
    private const val JOURNAL_KEEP_DEFAULT = 7

    /** Порог давления в шинах по умолчанию, десятые бара (2,0 бар). */
    private const val TIRE_MIN_DEFAULT = 20

    /** Диапазон порога давления, десятые бара (1,0…3,5 бар). */
    val TIRE_MIN_RANGE = 10..35
    private const val TIRE_MIN_MIN = 10
    private const val TIRE_MIN_MAX = 35
}
