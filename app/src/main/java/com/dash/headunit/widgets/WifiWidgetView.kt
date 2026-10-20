package com.dash.headunit.widgets

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.dash.headunit.R
import com.dash.headunit.ui.WifiConnectionDialog
import com.dash.headunit.wifi.WifiHub
import com.dash.headunit.theme.DashTheme
import com.dash.headunit.theme.ThemeCard

/**
 * Виджет «Wi-Fi»: статус подключения ГУ к сети, кнопка вкл/выкл.
 * Тап по любому месту виджета — общий диалог управления
 * подключениями ([WifiConnectionDialog]): сохранённые и видимые
 * сети, добавление сети вручную.
 */
class WifiWidgetView(
    context: Context,
    private val theme: DashTheme = DashTheme.DARK
) : LinearLayout(context) {

    private val wifiIcon = ImageView(context)
    private val stateView = TextView(context)
    private val toggleBtn = ImageButton(context)

    private val hubListener: () -> Unit = { render() }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(10), dp(16), dp(10))
        // Весь блок живой: тап (кроме кнопки питания) — диалог подключений.
        setOnClickListener { WifiConnectionDialog.show(context) }

        // Верхняя строка: иконка+подпись "Wi-Fi", правее — кнопка вкл/выкл.
        val head = LinearLayout(context).apply { orientation = HORIZONTAL }
        head.gravity = Gravity.CENTER_VERTICAL
        wifiIcon.apply {
            setImageResource(R.drawable.ic_wifi)
            scaleType = ImageView.ScaleType.CENTER
        }
        head.addView(wifiIcon, LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(8) })
        head.addView(
            TextView(context).apply {
                text = context.getString(R.string.widget_wifi)
                textSize = 13f
                letterSpacing = 0.08f
                setTextColor(theme.secondaryText)
            },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        )
        head.addView(View(context), LayoutParams(0, 0, 1f))
        toggleBtn.apply {
            setImageResource(R.drawable.ic_wifi_toggle)
            background = null
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4))
            contentDescription = context.getString(R.string.cd_wifi_widget)
            setOnClickListener {
                // Только по кнопке: сам виджет открывает диалог.
                WifiHub.state?.let { WifiHub.setEnabled(!it.enabled) }
            }
        }
        head.addView(toggleBtn, LayoutParams(dp(44), dp(44)))
        addView(head, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        stateView.apply {
            textSize = 17f
            setTextColor(theme.primaryText)
            maxLines = 1
            setOnClickListener { WifiConnectionDialog.show(context) }
        }
        addView(
            stateView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2)
            }
        )
        render()
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    // ---------- отрисовка ----------

    private fun render() {
        val st = WifiHub.state
        if (st == null) {
            wifiIcon.setColorFilter(theme.secondaryText)
            toggleBtn.setColorFilter(theme.secondaryText)
            stateView.text = context.getString(R.string.loading)
            return
        }
        val connected = st.enabled && st.connectedSsid != null
        wifiIcon.setColorFilter(
            when {
                connected -> theme.success
                st.enabled -> theme.primaryText
                else -> theme.secondaryText
            }
        )
        toggleBtn.setColorFilter(if (st.enabled) theme.accent else theme.secondaryText)
        stateView.text = when {
            !st.enabled -> context.getString(R.string.wifi_state_off)
            st.connectedSsid == null -> context.getString(R.string.wifi_state_disconnected)
            st.ip != null -> "${st.connectedSsid} · IP ${st.ip}"
            else -> st.connectedSsid
        }
        stateView.setTextColor(
            if (connected) theme.primaryText else theme.secondaryText
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        WifiHub.init(context)
        WifiHub.addListener(hubListener)
        WifiHub.ensurePolling()
    }

    override fun onDetachedFromWindow() {
        WifiHub.removeListener(hubListener)
        super.onDetachedFromWindow()
    }
}
