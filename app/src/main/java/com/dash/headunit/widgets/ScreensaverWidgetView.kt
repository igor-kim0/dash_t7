package com.dash.headunit.widgets

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.dash.headunit.R
import com.dash.headunit.screensaver.Screensaver
import com.dash.headunit.theme.DashTheme

/**
 * Плитка виджета «Заставка» — одна кнопка «включить / выключить» заставку
 * ([Screensaver]); настройки (задержка, яркость, фон) — в шестерёнке.
 * Состояние на плитке меняется сразу, [onToggle] сохраняет его.
 */
class ScreensaverWidgetView(
    context: Context,
    private val theme: DashTheme,
    private val config: Screensaver.Config,
    private val canToggle: () -> Boolean,
    private val onToggle: (Boolean) -> Unit
) : LinearLayout(context) {

    private var enabled = config.enabled
    private val icon = ImageView(context).apply { setImageResource(R.drawable.ic_power) }
    private val state = TextView(context).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f) }
    private val summary = TextView(context).apply {
        setTextColor(theme.secondaryText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        addView(icon, LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) })
        val texts = LinearLayout(context).apply { orientation = VERTICAL }
        texts.addView(state)
        texts.addView(summary)
        addView(texts, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        render()
        setOnClickListener {
            if (!canToggle()) return@setOnClickListener
            enabled = !enabled
            render()
            onToggle(enabled)
        }
    }

    private fun render() {
        icon.setColorFilter(if (enabled) theme.accent else theme.secondaryText)
        state.text = context.getString(
            if (enabled) R.string.screensaver_on else R.string.screensaver_off
        )
        state.setTextColor(if (enabled) theme.primaryText else theme.secondaryText)
        summary.text = if (enabled) context.getString(
            R.string.screensaver_summary,
            Screensaver.timeoutLabel(config.timeoutSec),
            config.brightness
        ) else context.getString(R.string.screensaver_tap_to_enable)
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
