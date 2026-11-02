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
 * Плитка виджета «Заставка»: сам виджет ничего не показывает, только
 * включает заставку ([Screensaver]) и хранит её настройки. На плитке —
 * текущие настройки; тап — показать заставку сразу.
 */
class ScreensaverWidgetView(
    context: Context,
    theme: DashTheme,
    config: Screensaver.Config,
    onTap: () -> Unit
) : LinearLayout(context) {

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_moon)
            setColorFilter(theme.accent)
        }, LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(12) })
        val texts = LinearLayout(context).apply { orientation = VERTICAL }
        texts.addView(TextView(context).apply {
            text = context.getString(R.string.widget_screensaver)
            setTextColor(theme.primaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        })
        texts.addView(TextView(context).apply {
            text = context.getString(
                R.string.screensaver_summary,
                Screensaver.timeoutLabel(config.timeoutSec),
                config.brightness
            )
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        })
        addView(texts, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        setOnClickListener { onTap() }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
