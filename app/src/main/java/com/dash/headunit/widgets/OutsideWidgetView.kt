package com.dash.headunit.widgets

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.dash.headunit.R
import com.dash.headunit.car.CarData
import com.dash.headunit.car.CarHub
import com.dash.headunit.car.DemoData
import com.dash.headunit.theme.DashTheme
import com.dash.headunit.weather.WeatherHub

/**
 * Виджет «За бортом»: подпись + крупная забортная температура
 * (раньше жила в виджете «Часы»).
 *
 * Температура — из общего [CarHub] (пока данных нет — демо-значение).
 * Вторая строка — что бы показал Jarvis (см. Jarvis/03-outside-temp):
 * ячейка 16,19 как прямые °C (−50..60), иначе — погода (кросс-валидация
 * >15° у Jarvis тоже в пользу погоды).
 * Тап по виджету — обновить данные авто.
 */
class OutsideWidgetView(
    context: Context,
    private val theme: DashTheme = DashTheme.DARK
) : LinearLayout(context) {

    private val labelView = TextView(context)
    private val tempView = TextView(context)
    private val jarvisView = TextView(context)

    private val hubListener: () -> Unit = { render() }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER

        labelView.apply {
            text = context.getString(R.string.car_outside)
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
        }
        tempView.apply {
            setTextColor(theme.primaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
            gravity = Gravity.CENTER
        }
        jarvisView.apply {
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
        }
        addView(labelView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(tempView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(jarvisView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        setOnClickListener { CarHub.refresh() }
        render()
    }

    private fun render() {
        val d = CarHub.data
        val out = d?.outsideTempC ?: if (d == null) DemoData.car().outsideTempC else null
        if (out != null) {
            tempView.text = "%+.0f°".format(out)
            tempView.visibility = VISIBLE
        } else {
            tempView.visibility = GONE
        }
        val jv = jarvisTemp(d)
        if (jv != null) {
            jarvisView.text = "%+.0f° (%s)".format(jv.first, jv.second)
            jarvisView.visibility = VISIBLE
        } else {
            jarvisView.visibility = GONE
        }
    }

    /** Что бы показал Jarvis: шина 16,19 прямых °C (−50..60), иначе погода. */
    private fun jarvisTemp(d: CarData?): Pair<Float, String>? {
        val weather = WeatherHub.data?.nowTempC?.toFloat()
        val bus = d?.outsideRaw?.takeIf { it in -50..60 }
        return when {
            bus != null && (weather == null || kotlin.math.abs(bus - weather) <= 15f) ->
                bus.toFloat() to "jv:шина"
            weather != null -> weather to "jv:погода"
            else -> null
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        CarHub.init(context)
        CarHub.addListener(hubListener)
        CarHub.ensurePolling()
        WeatherHub.init(context)
        WeatherHub.addListener(hubListener)
        WeatherHub.ensureLoaded()
    }

    override fun onDetachedFromWindow() {
        CarHub.removeListener(hubListener)
        WeatherHub.removeListener(hubListener)
        super.onDetachedFromWindow()
    }
}
