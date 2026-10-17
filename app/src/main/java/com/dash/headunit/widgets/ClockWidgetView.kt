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
 * Виджет часов: крупное время, дата и день недели на русском.
 * Обновляется каждую секунду.
 */
class ClockWidgetView(context: Context, private val theme: DashTheme = DashTheme.DARK) :
    LinearLayout(context) {

    private val timeView = TextView(context)
    private val dateView = TextView(context)
    private val weekdayView = TextView(context)

    private val handler = Handler(Looper.getMainLooper())
    private val locale = Locale("ru")

    private val ticker = object : Runnable {
        override fun run() {
            render(Date())
            handler.postDelayed(this, 1000L)
        }
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        timeView.apply {
            setTextColor(theme.primaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 68f)
            gravity = Gravity.CENTER
        }
        dateView.apply {
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            gravity = Gravity.CENTER
        }
        weekdayView.apply {
            setTextColor(theme.accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            gravity = Gravity.CENTER
        }
        addView(timeView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(dateView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(weekdayView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        render(Date())
    }

    private fun render(now: Date) {
        timeView.text = SimpleDateFormat("HH:mm", locale).format(now)
        dateView.text = SimpleDateFormat("d MMMM yyyy", locale).format(now)
            .replaceFirstChar { it.uppercaseChar() }
        weekdayView.text = SimpleDateFormat("EEEE", locale).format(now)
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
