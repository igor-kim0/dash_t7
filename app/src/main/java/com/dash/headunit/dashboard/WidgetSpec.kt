package com.dash.headunit.dashboard

/**
 * Типы виджетов дашборда.
 */
enum class WidgetType(val id: String) {
    /** Только часы. */
    CLOCK("clock"),
    /** Полоса "часы + текущая погода" (макет 1). */
    COMBO("combo"),
    /** Полоса "прогноз на 5 дней" (макет 2, бывший виджет с закладками). */
    WEATHER("weather"),
    /** Виджет автомобиля: топливо, АКБ, двигатель, запас хода, коробка. */
    CAR("car"),
    /** Виджет музыки: BT AVRCP + плееры, транспорт с акцентом на "далее". */
    MUSIC("music"),
    /** Яндекс Музыка: обложка, транспорт, выбор коллекции (дерево MediaBrowser). */
    YANDEX_MUSIC("yandex_music"),
    /** Радио: штатный FM/AM-тюнер ГУ через службу SVMedia (RadioHub). */
    RADIO("radio"),
    /** Виджет-приложение: встраивание чужого приложения (ActivityView). */
    APP("app"),
    /** Бортовой компьютер: расход/пробег/скорость за поездку. */
    TRIP("trip"),
    /** Климат: температура, вентилятор, A/C, режимы, окна/двери. */
    CLIMATE("climate"),
    /** Управление климатом: кнопки HVAC и подогревов с записью в шину. */
    CLIMATE_CONTROL("climate_control"),
    /** Справочник: свойства VHAL (AOSP + вендор) с ID и живыми значениями. */
    REFERENCE("reference"),
    /** За бортом: крупная забортная температура (была в «Часах»). */
    OUTSIDE("outside"),
    /** OBD (ELM): связь с ELM327 по Bluetooth, температура ОЖ (PID 0105). */
    ELM("elm"),
    /** Подогрев: сиденье водителя, руль, лобовое стекло. */
    HEAT("heat"),
    /** Спидометр: скорость, обороты, селектор передач. */
    SPEED("speed"),

    /** Wi-Fi: статус подключения ГУ, вкл/выкл, выбор сети (WifiHub). */
    WIFI("wifi"),

    /** Кнопки: пульт стёкол/люка/шторки записью команд VrControlMgr в шину Desay. */
    BUTTONS("buttons"),

    /** Заставка: только настройки — гасит экран после простоя без касаний. */
    SCREENSAVER("screensaver"),

    /** Яркость: подсветка экрана ГУ через Desay (HuBrightness), ползунок 1..10. */
    BRIGHTNESS("brightness"),

    /** Зажигание: пропуски по цилиндрам (прирост), P030x, откат, Mode 06 — по ELM. */
    IGNITION("ignition");

    /** Наименьшая ширина в ячейках сетки: «Заставка» — одна иконка-кнопка, ей можно уже. */
    val minW: Int
        get() = if (this == SCREENSAVER) 4 else DashboardGridLayout.MIN_W

    companion object {
        fun byId(id: String): WidgetType =
            values().firstOrNull { it.id == id }
                // Удалённый «Тестовый» в сохранённой раскладке → «Справочник».
                ?: if (id == "test") REFERENCE else CLOCK

        /** Удалённые виджеты: их плитки из сохранённой раскладки выбрасываем. */
        val REMOVED_IDS = setOf("claude_test", "push_test", "learn")
    }
}

/**
 * Положение и размер виджета в клетках сетки.
 * Сетка: [DashboardGridLayout.COLS] x [DashboardGridLayout.ROWS] (100x70).
 */
data class WidgetSpec(
    val widgetId: String,
    val type: WidgetType,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int
) {
    fun clamped(): WidgetSpec = copy(
        x = x.coerceIn(0, DashboardGridLayout.COLS - 1),
        y = y.coerceIn(0, DashboardGridLayout.ROWS - 1),
        w = w.coerceIn(type.minW, DashboardGridLayout.COLS),
        h = h.coerceIn(DashboardGridLayout.MIN_H, DashboardGridLayout.ROWS)
    ).let {
        it.copy(
            x = it.x.coerceAtMost(DashboardGridLayout.COLS - it.w),
            y = it.y.coerceAtMost(DashboardGridLayout.ROWS - it.h)
        )
    }
}
