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
    /** Виджет-приложение: встраивание чужого приложения (ActivityView). */
    APP("app"),
    /** Бортовой компьютер: расход/пробег/скорость за поездку. */
    TRIP("trip"),
    /** Климат: температура, вентилятор, A/C, режимы, окна/двери. */
    CLIMATE("climate"),
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
    BUTTONS("buttons");

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
        w = w.coerceIn(DashboardGridLayout.MIN_W, DashboardGridLayout.COLS),
        h = h.coerceIn(DashboardGridLayout.MIN_H, DashboardGridLayout.ROWS)
    ).let {
        it.copy(
            x = it.x.coerceAtMost(DashboardGridLayout.COLS - it.w),
            y = it.y.coerceAtMost(DashboardGridLayout.ROWS - it.h)
        )
    }
}
