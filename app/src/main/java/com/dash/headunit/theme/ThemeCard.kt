package com.dash.headunit.theme

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue

/**
 * Программные фоны в цветах текущей темы (XML-фонды статичны
 * и под смену тем не подходят).
 */
object ThemeCard {

    /** Карточка виджета/кнопки: заливка + тонкая рамка, радиус 12dp. */
    fun card(context: Context, theme: DashTheme): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, 12)
            setColor(theme.cardBackground)
            setStroke(dp(context, 1).toInt(), theme.cardBorder)
        }

    /**
     * Фон блока виджета: карточка, а в теме с [DashTheme.flatWidgets] —
     * ничего (null), виджет лежит прямо на фоне дашборда.
     */
    fun widget(context: Context, theme: DashTheme): GradientDrawable? =
        if (theme.flatWidgets) null else card(context, theme)

    /** Акцентная кнопка (Готово и т.п.): заливка акцентом, радиус 12dp. */
    fun accent(context: Context, theme: DashTheme): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, 12)
            setColor(theme.accent)
        }

    /** Круг заданного цвета (кнопки транспорта музыки). */
    fun circle(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    /** Квадратная кнопка с мягким радиусом (кнопки транспорта музыки). */
    fun square(color: Int, radiusDp: Int, context: Context): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, radiusDp)
            setColor(color)
        }

    private fun dp(context: Context, value: Int): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics
        )
}
