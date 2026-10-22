package com.dash.headunit.widgets

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.dash.headunit.dashboard.WidgetSettingsStore
import com.dash.headunit.theme.DashTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Виджет «Часы»: крупное время и «день недели, число месяц».
 * Забортная температура живёт отдельным виджетом ([OutsideWidgetView]).
 *
 * Время тикает каждую секунду. Размер текста умножается на настройку
 * масштаба [OPT_SCALE].
 */
class ComboWidgetView(
    context: Context,
    private val widgetId: String,
    private val theme: DashTheme = DashTheme.DARK,
    private val settings: WidgetSettingsStore? = null
) : LinearLayout(context) {

    private val timeView = TextView(context)
    private val dateView = TextView(context)

    private val locale = Locale("ru")
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            renderClock(Date())
            handler.postDelayed(this, 1000L)
        }
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER

        val scale = settings?.let { scaleValue(it, widgetId) } ?: 1f
        val bold = settings?.let { boldValue(it, widgetId) } ?: false
        timeView.apply {
            setTextColor(theme.primaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 56f * scale)
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            gravity = Gravity.CENTER
        }
        dateView.apply {
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f * scale)
            gravity = Gravity.CENTER
        }
        addView(timeView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(dateView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        renderClock(Date())
    }

    private fun renderClock(now: Date) {
        timeView.text = SimpleDateFormat("HH:mm", locale).format(now)
        dateView.text = SimpleDateFormat("EEEE, d MMMM", locale).format(now)
            .replaceFirstChar { it.uppercaseChar() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(ticker)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    companion object {
        /** Опция «Масштаб» — строковое значение, напр. "1.5". */
        const val OPT_SCALE = "scale"
        const val DEFAULT_SCALE = "1.0"
        val SCALES = listOf("0.5", "1.0", "1.5", "2.0")

        /** Опция «Время жирным»: "1" / "0". */
        const val OPT_BOLD = "bold"
        const val DEFAULT_BOLD = "0"

        /** Значение масштаба [widgetId]; повреждённое опускается до 1.0. */
        fun scaleValue(settings: WidgetSettingsStore, widgetId: String): Float =
            settings.getOption(widgetId, OPT_SCALE, DEFAULT_SCALE).toFloatOrNull() ?: 1f

        fun boldValue(settings: WidgetSettingsStore, widgetId: String): Boolean =
            settings.getOption(widgetId, OPT_BOLD, DEFAULT_BOLD) == "1"
    }
}
