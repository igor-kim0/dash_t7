package com.dash.headunit.theme

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import com.dash.headunit.dashboard.WidgetSettingsStore

/**
 * Общий вид «Ретро» для виджетов без своей ретро-отрисовки (у «Спидометра»,
 * «Климата» и «Управления климатом» она своя): чёрная табличка в
 * металлической рамке, текст — «лампами» ([THEME]) узким шрифтом с ореолом,
 * как надписи [com.dash.headunit.widgets.RetroSpeedometerView].
 *
 * Виджет получает [THEME] вместо темы дашборда, рамка — [panel],
 * текстовые вью — [applyText].
 */
object RetroLook {

    /** Опция виджета: вид ([LOOK_CLASSIC] / [LOOK_RETRO]). */
    const val OPT_LOOK = "look"
    const val LOOK_CLASSIC = "classic"
    const val LOOK_RETRO = "retro"

    fun isOn(store: WidgetSettingsStore, widgetId: String): Boolean =
        store.getOption(widgetId, OPT_LOOK, LOOK_CLASSIC) == LOOK_RETRO

    /** Палитра ламп: тёплый белый, янтарь, зелёный — как [com.dash.headunit.widgets.RetroClimateIcons]. */
    val THEME = DashTheme(
        id = "retro",
        name = "Ретро",
        background = 0xFF07090B.toInt(),
        cardBackground = 0xFF101418.toInt(),
        cardBorder = 0xFF3A4046.toInt(),
        primaryText = 0xFFFFF2D8.toInt(),
        secondaryText = 0xFF9A9384.toInt(),
        accent = 0xFFFFB03A.toInt(),
        success = 0xFF9CE086.toInt()
    )

    private val condensed = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    private val condensedBold = Typeface.create("sans-serif-condensed", Typeface.BOLD)

    /** Фон блока: металлический ободок (светлый сверху) и табличка с градиентом. */
    fun panel(context: Context): Drawable {
        val r = dp(context, 12f)
        val bezel = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFFA8B0B8.toInt(), 0xFF3A4046.toInt())
        ).apply { cornerRadius = r }
        val plate = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFF1C232A.toInt(), 0xFF0A0D10.toInt())
        ).apply { cornerRadius = r - dp(context, 2f) }
        val inset = dp(context, 2f).toInt()
        return LayerDrawable(arrayOf(bezel, plate)).apply {
            setLayerInset(1, inset, inset, inset, inset)
        }
    }

    /**
     * Узкий шрифт и ореол цветом текста у всех [TextView] в [root].
     * Виджеты добавляют строки и меняют цвета на ходу — повторяем на каждом
     * обходе раскладки; повторный вызов без изменений ничего не трогает.
     */
    fun applyText(root: View) {
        walk(root)
        // Наблюдатель общий на окно — снимаем, когда виджет убрали с дашборда.
        val listener = ViewTreeObserver.OnGlobalLayoutListener { walk(root) }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.viewTreeObserver.addOnGlobalLayoutListener(listener)
            }

            override fun onViewDetachedFromWindow(v: View) {
                v.viewTreeObserver.removeOnGlobalLayoutListener(listener)
            }
        })
    }

    private fun walk(v: View) {
        if (v is TextView) {
            val bold = v.typeface?.isBold == true
            val face = if (bold) condensedBold else condensed
            if (v.typeface !== face) v.typeface = face
            val glow = v.textSize * 0.25f
            val color = v.currentTextColor
            if (v.shadowColor != color || v.shadowRadius != glow) v.setShadowLayer(glow, 0f, 0f, color)
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
    }

    private fun dp(context: Context, v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics)
}
