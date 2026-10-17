package com.dash.headunit.widgets

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.dash.headunit.R
import com.dash.headunit.car.CarHub
import com.dash.headunit.car.DemoData
import com.dash.headunit.theme.DashTheme

/**
 * Виджет «За бортом»: подпись + крупная забортная температура
 * (раньше жила в виджете «Часы»).
 *
 * Температура — из общего [CarHub] (пока данных нет — демо-значение).
 * Тап по виджету — обновить данные авто.
 */
class OutsideWidgetView(
    context: Context,
    private val theme: DashTheme = DashTheme.DARK
) : LinearLayout(context) {

    private val labelView = TextView(context)
    private val tempView = TextView(context)

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
        addView(labelView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(tempView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

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
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        CarHub.init(context)
        CarHub.addListener(hubListener)
        CarHub.ensurePolling()
    }

    override fun onDetachedFromWindow() {
        CarHub.removeListener(hubListener)
        super.onDetachedFromWindow()
    }
}
