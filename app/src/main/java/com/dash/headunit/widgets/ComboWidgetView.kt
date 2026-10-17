package com.dash.headunit.widgets

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.dash.headunit.theme.DashTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Виджет «Часы»: крупное время и «день недели, число месяц».
 * Забортная температура живёт отдельным виджетом ([OutsideWidgetView]).
 *
 * Время тикает каждую секунду.
 */
class ComboWidgetView(
    context: Context,
    private val theme: DashTheme = DashTheme.DARK
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

        timeView.apply {
            setTextColor(theme.primaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 56f)
            gravity = Gravity.CENTER
        }
        dateView.apply {
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
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
}
