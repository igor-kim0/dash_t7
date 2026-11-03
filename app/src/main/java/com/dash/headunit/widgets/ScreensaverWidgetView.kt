package com.dash.headunit.widgets

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.FrameLayout
import com.dash.headunit.R
import com.dash.headunit.screensaver.Screensaver
import com.dash.headunit.theme.DashTheme

/**
 * Плитка виджета «Заставка» — одна кнопка (иконка луны, без надписей):
 * включить / выключить заставку ([Screensaver]). Включена — цвет акцента,
 * выключена — серая. Настройки (задержка, яркость, фон) — в шестерёнке.
 */
class ScreensaverWidgetView(
    context: Context,
    private val theme: DashTheme,
    config: Screensaver.Config,
    private val canToggle: () -> Boolean,
    private val onToggle: (Boolean) -> Unit
) : FrameLayout(context) {

    private var enabled = config.enabled
    private val icon = ImageView(context).apply {
        setImageResource(R.drawable.ic_moon)
        scaleType = ImageView.ScaleType.FIT_CENTER
        adjustViewBounds = true
    }

    init {
        val pad = dp(8)
        setPadding(pad, pad, pad, pad)
        addView(icon, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
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
        icon.alpha = if (enabled) 1f else 0.5f
        contentDescription = context.getString(
            if (enabled) R.string.screensaver_on else R.string.screensaver_off
        )
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
