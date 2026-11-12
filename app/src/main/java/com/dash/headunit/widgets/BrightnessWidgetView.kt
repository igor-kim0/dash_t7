package com.dash.headunit.widgets

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.dash.headunit.R
import com.dash.headunit.car.HuBrightness
import com.dash.headunit.theme.DashTheme
import com.dash.headunit.ui.DashSlider
import java.util.concurrent.atomic.AtomicInteger

/**
 * Виджет «Яркость»: подсветка экрана ГУ через Desay ([HuBrightness]) —
 * ползунок 1..10, как штатный в шторке. Меняется уровень активного режима
 * (день или ночь — подсветка ГУ хранит их раздельно). Раз в [POLL_MS]
 * перечитываем (5,1), чтобы следовать штатному ползунку и смене день/ночь.
 */
class BrightnessWidgetView(
    context: Context,
    private val theme: DashTheme
) : LinearLayout(context) {

    private val handler = Handler(Looper.getMainLooper())
    private val modeView = TextView(context)
    private val valueView = TextView(context)
    private val slider = DashSlider(context, theme, HuBrightness.MIN_LEVEL..HuBrightness.MAX_LEVEL, 1, 5) {
        onUserLevel(it)
    }

    /** Последний уровень от пальца, ещё не отправленный (0 — нет). */
    private val pending = AtomicInteger(0)
    private var lastUserChange = 0L
    private val poll = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, POLL_MS)
        }
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(8), dp(16), dp(8))

        val head = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_brightness)
            scaleType = ImageView.ScaleType.CENTER
            setColorFilter(theme.accent)
        }, LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(8) })
        head.addView(TextView(context).apply {
            text = context.getString(R.string.widget_brightness)
            textSize = 13f
            letterSpacing = 0.08f
            setTextColor(theme.secondaryText)
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        modeView.apply {
            textSize = 13f
            setTextColor(theme.secondaryText)
            setPadding(dp(8), 0, 0, 0)
        }
        head.addView(modeView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        head.addView(View(context), LayoutParams(0, 0, 1f))
        valueView.apply {
            textSize = 26f
            setTextColor(theme.primaryText)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        head.addView(valueView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(head, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addView(slider, LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))
        render(null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(poll)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(poll)
        super.onDetachedFromWindow()
    }

    private fun onUserLevel(level: Int) {
        lastUserChange = SystemClock.uptimeMillis()
        valueView.text = level.toString()
        // Коалесцируем: в очереди одна задача, она берёт самое свежее значение.
        if (pending.getAndSet(level) == 0) {
            val app = context.applicationContext
            HuBrightness.io.execute {
                val l = pending.getAndSet(0)
                if (l > 0) HuBrightness.writeLevel(app, l)
            }
        }
    }

    private fun refresh() {
        if (slider.dragging || SystemClock.uptimeMillis() - lastUserChange < SETTLE_MS) return
        HuBrightness.readAsync(context) { s ->
            if (!isAttachedToWindow) return@readAsync
            if (slider.dragging || SystemClock.uptimeMillis() - lastUserChange < SETTLE_MS) return@readAsync
            render(s)
        }
    }

    private fun render(s: HuBrightness.State?) {
        slider.isEnabled = s != null
        if (s == null) {
            modeView.text = context.getString(R.string.brightness_no_link)
            valueView.text = "—"
            return
        }
        modeView.text = context.getString(if (s.isNight) R.string.brightness_night else R.string.brightness_day)
        valueView.text = s.current.toString()
        slider.setValue(s.current)
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private companion object {
        const val POLL_MS = 3000L
        /** После движения пальцем столько не перечитываем — шина догоняет запись. */
        const val SETTLE_MS = 2500L
    }
}
