package com.dash.headunit.theme

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import com.dash.headunit.dashboard.WidgetSettingsStore

/**
 * Фон блока виджета (опция каждого виджета): как в теме, градиент в цветах
 * темы или прозрачный (виджет лежит прямо на фоне дашборда).
 */
object WidgetBackground {

    const val OPT_BG = "bg"
    /** Как задумано темой: карточка, в плоской теме — без фона. */
    const val BG_THEME = "theme"
    const val BG_GRADIENT = "gradient"
    const val BG_TRANSPARENT = "transparent"

    val ALL = listOf(BG_THEME, BG_GRADIENT, BG_TRANSPARENT)

    fun get(store: WidgetSettingsStore, widgetId: String): String =
        store.getOption(widgetId, OPT_BG, BG_THEME).takeIf { it in ALL } ?: BG_THEME

    /**
     * Градиент по теме: сверху — карточка с отсветом акцента, книзу уходит
     * к фону дашборда; рамка — цвет рамки темы, радиус как у карточки.
     */
    fun gradient(context: Context, theme: DashTheme): GradientDrawable {
        val top = mix(theme.cardBackground, theme.accent, 0.22f)
        val bottom = mix(theme.cardBackground, theme.background, 0.6f)
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(top, theme.cardBackground, bottom)
        ).apply {
            cornerRadius = dp(context, 12f)
            setStroke(dp(context, 1f).toInt(), theme.cardBorder)
        }
    }

    /**
     * Фон вне редактирования. [retroPanel] — вид «Ретро»: табличка остаётся
     * при «как в теме» и «градиенте» (она и так градиентная).
     */
    fun drawable(context: Context, theme: DashTheme, mode: String, retro: Boolean): Drawable? =
        when {
            mode == BG_TRANSPARENT -> null
            retro -> RetroLook.panel(context)
            mode == BG_GRADIENT -> gradient(context, theme)
            else -> ThemeCard.widget(context, theme)
        }

    /** Смесь ARGB-цветов: [t] = 0 → [a], 1 → [b]. */
    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int): Int {
            val x = (a ushr shift) and 0xFF
            val y = (b ushr shift) and 0xFF
            return (x + (y - x) * t).toInt().coerceIn(0, 255) shl shift
        }
        // Прозрачность — от карточки: полупрозрачная тема остаётся такой же.
        return (a and 0xFF000000.toInt()) or ch(16) or ch(8) or ch(0)
    }

    private fun dp(context: Context, v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics)
}
