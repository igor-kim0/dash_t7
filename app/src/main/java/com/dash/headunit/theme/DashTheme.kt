package com.dash.headunit.theme

import androidx.annotation.ColorInt

/**
 * Описание темы дашборда. Новые темы добавляются как новые объекты
 * и автоматически появляются в диалоге выбора (луна в топ-баре).
 */
data class DashTheme(
    val id: String,
    val name: String,
    @ColorInt val background: Int,
    @ColorInt val cardBackground: Int,
    @ColorInt val cardBorder: Int,
    @ColorInt val primaryText: Int,
    @ColorInt val secondaryText: Int,
    @ColorInt val accent: Int,
    /** Цвет "всё хорошо" (прогретый двигатель и т.п.). */
    @ColorInt val success: Int,
    /** Виджеты без фона и рамки — прямо на фоне дашборда. */
    val flatWidgets: Boolean = false,
    /**
     * Свечение в центре фона (как подсветка приборки) и светящаяся линия
     * под верхней панелью; null — фон сплошной.
     */
    @ColorInt val glow: Int? = null
) {
    companion object {
        val DARK = DashTheme(
            id = "dark",
            name = "Тёмная",
            background = 0xFF101214.toInt(),
            cardBackground = 0xFF1B2024.toInt(),
            cardBorder = 0xFF2E353B.toInt(),
            primaryText = 0xFFEDEDED.toInt(),
            secondaryText = 0xFF9AA3AD.toInt(),
            accent = 0xFF4DA3FF.toInt(),
            success = 0xFF66BB6A.toInt()
        )

        /** Фиолетовая, по макету: тёмно-сливовый фон, сиреневый акцент. */
        val VIOLET = DashTheme(
            id = "violet",
            name = "Фиолетовая",
            background = 0xFF14111C.toInt(),
            cardBackground = 0xFF211C33.toInt(),
            cardBorder = 0xFF3B3354.toInt(),
            primaryText = 0xFFF4F1FB.toInt(),
            secondaryText = 0xFFA79DC4.toInt(),
            accent = 0xFF9D7BFF.toInt(),
            success = 0xFF7BC98A.toInt()
        )

        /**
         * Тёмно-синяя, в стиле штатной приборки: почти чёрные края, синее
         * свечение по центру, голубой акцент; виджеты без карточек.
         */
        val NAVY = DashTheme(
            id = "navy",
            name = "Тёмно-синяя",
            background = 0xFF03070F.toInt(),
            // Полупрозрачные кнопки — сквозь них видно свечение фона.
            cardBackground = 0x990A1B36.toInt(),
            cardBorder = 0xFF1C4F86.toInt(),
            primaryText = 0xFFF2F7FF.toInt(),
            secondaryText = 0xFF9DB4D2.toInt(),
            accent = 0xFF35B6FF.toInt(),
            success = 0xFF4FD8A0.toInt(),
            flatWidgets = true,
            glow = 0xFF0D4F96.toInt()
        )

        /** Все доступные темы. */
        val ALL: List<DashTheme> = listOf(DARK, VIOLET, NAVY)
    }
}

/** Хранилище текущей темы (SharedPreferences). */
class ThemeStore(prefsName: String, private val context: android.content.Context) {
    private val prefs = context.getSharedPreferences(prefsName, android.content.Context.MODE_PRIVATE)

    fun current(): DashTheme {
        val id = prefs.getString(KEY_THEME, DashTheme.DARK.id) ?: DashTheme.DARK.id
        return DashTheme.ALL.firstOrNull { it.id == id } ?: DashTheme.DARK
    }

    fun set(theme: DashTheme) {
        prefs.edit().putString(KEY_THEME, theme.id).apply()
    }

    companion object {
        /** Файл настроек темы — общий для дашборда и диалогов. */
        const val PREFS = "theme_prefs"
        private const val KEY_THEME = "theme_id"
    }
}
