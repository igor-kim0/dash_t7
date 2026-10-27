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
 * масштаба [OPT_SCALE]. Опция [OPT_STYLE] = [STYLE_FLIP] — перекидные
 * карточки как у HTC ([FlipClockView]), они заполняют виджет целиком.
 */
class ComboWidgetView(
    context: Context,
    private val widgetId: String,
    private val theme: DashTheme = DashTheme.DARK,
    private val settings: WidgetSettingsStore? = null
) : LinearLayout(context) {

    private val timeView = TextView(context)
    private val dateView = TextView(context)
    private var flipView: FlipClockView? = null

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
        if (settings != null && styleValue(settings, widgetId) == STYLE_FLIP) {
            val flip = FlipClockView(context, theme, bold, invertValue(settings, widgetId))
            flipView = flip
            addView(flip, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        } else {
            addView(timeView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        addView(dateView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        renderClock(Date())
    }

    private fun renderClock(now: Date) {
        val time = SimpleDateFormat("HH:mm", locale).format(now)
        val flip = flipView
        if (flip != null) flip.setTime(time.substring(0, 2), time.substring(3, 5))
        else timeView.text = time
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

        /** Опция «Вид»: [STYLE_DIGITAL] (обычный текст) / [STYLE_FLIP] (перекидные, HTC). */
        const val OPT_STYLE = "style"
        const val STYLE_DIGITAL = "digital"
        const val STYLE_FLIP = "flip"

        fun styleValue(settings: WidgetSettingsStore, widgetId: String): String =
            settings.getOption(widgetId, OPT_STYLE, STYLE_DIGITAL)

        /** Опция «Инверсия» перекидных часов: "1" — тёмные карточки, светлые цифры. */
        const val OPT_INVERT = "invert"

        fun invertValue(settings: WidgetSettingsStore, widgetId: String): Boolean =
            settings.getOption(widgetId, OPT_INVERT, "0") == "1"

        fun boldValue(settings: WidgetSettingsStore, widgetId: String): Boolean =
            settings.getOption(widgetId, OPT_BOLD, DEFAULT_BOLD) == "1"
    }
}
