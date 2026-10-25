package com.dash.headunit.widgets

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.dash.headunit.R
import com.dash.headunit.car.ClimateHub
import com.dash.headunit.theme.DashTheme
import java.util.Locale

/**
 * Виджет «Климат»: только чтение состояния HVAC (Desay шина, [ClimateHub]).
 * Пока данных нет — прочерк. Никаких команд — только показ.
 */
class ClimateWidgetView(
    context: Context,
    private val theme: DashTheme = DashTheme.DARK
) : LinearLayout(context) {

    private val locale = Locale("ru")
    private val hubListener: () -> Unit = { render() }

    private val tempValue = TextView(context)
    private val fanValue = TextView(context)
    private val modeValue = TextView(context)
    private val autoValue = TextView(context)
    private val acValue = TextView(context)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER

        val title = TextView(context).apply {
            text = context.getString(R.string.widget_climate)
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
        }
        addView(title, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        addView(row(context.getString(R.string.cl_temp), tempValue))
        addView(row(context.getString(R.string.cl_fan), fanValue))
        addView(row(context.getString(R.string.cl_mode), modeValue))
        addView(row(context.getString(R.string.cl_auto), autoValue))
        addView(row(context.getString(R.string.cl_ac), acValue))

        render()
    }

    private fun row(name: String, value: TextView): LinearLayout =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            val label = TextView(context).apply {
                text = name
                setTextColor(theme.secondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }
            value.apply {
                setTextColor(theme.primaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }
            addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            addView(value, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
            })
        }

    private fun render() {
        val s = ClimateHub.data ?: ClimateHub.ClimateState.EMPTY

        val t = s.tempDriverC
        tempValue.text = if (t != null) {
            if (t % 1f == 0f) "%.0f°".format(locale, t) else "%.1f°".format(locale, t)
        } else "—"

        fanValue.text = text(s.fanSpeed?.let { "%d" })
        modeValue.text = directionText(s.fanDirection)
        autoValue.text = onOffText(s.auto)
        acValue.text = onOffText(s.ac)
    }

    private fun text(s: String?): String = when (s) {
        null -> "—"
        else -> s
    }

    private fun directionText(raw: Int?): String = when (raw) {
        null -> "—"
        1 -> context.getString(R.string.cl_mode_face)
        2 -> context.getString(R.string.cl_mode_feet)
        5 -> context.getString(R.string.cl_mode_windshield)
        else -> raw.toString()
    }

    private fun onOffText(v: Boolean?): String = when (v) {
        null -> "—"
        true -> context.getString(R.string.cl_on)
        else -> context.getString(R.string.cl_off)
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ClimateHub.init(context)
        ClimateHub.addListener(hubListener)
        ClimateHub.ensurePolling()
    }

    override fun onDetachedFromWindow() {
        ClimateHub.removeListener(hubListener)
        super.onDetachedFromWindow()
    }
}
