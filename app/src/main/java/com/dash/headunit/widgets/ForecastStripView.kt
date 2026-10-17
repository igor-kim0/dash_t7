package com.dash.headunit.widgets

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.dash.headunit.R
import com.dash.headunit.dashboard.WidgetSettingsStore
import com.dash.headunit.theme.DashTheme
import com.dash.headunit.weather.DailyPoint
import com.dash.headunit.weather.HourlyPoint
import com.dash.headunit.weather.WeatherHub
import com.dash.headunit.weather.WmoCodes
import com.dash.headunit.weather.formatDayShort
import java.util.Locale

/**
 * Полоса прогноза с боковым переключателем-иконками слева:
 * часы — три ближайшие часовые точки (время, иконка, температура),
 * календарь — сегодня + два следующих дня (день, иконка, день/ночь).
 *
 * Данные — из общего [WeatherHub]. Тап вне переключателя — обновить.
 */
class ForecastStripView(
    context: Context,
    private val widgetId: String,
    private val theme: DashTheme = DashTheme.DARK,
    private val settings: WidgetSettingsStore = WidgetSettingsStore(context)
) : LinearLayout(context) {

    private enum class Tab { HOURS, DAYS }

    private var tab = Tab.HOURS
    private val locale = Locale("ru")

    private val hubListener: () -> Unit = { render() }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setOnClickListener { WeatherHub.refresh() }
        render()
    }

    private fun selectTab(next: Tab) {
        tab = next
        render()
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()

    // ---------- каркас ----------

    private fun render() {
        removeAllViews()
        addView(rail(), LayoutParams(dp(56), LayoutParams.MATCH_PARENT))
        addView(contentView(), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    }

    // ---------- рейка-переключатель ----------

    private fun rail(): LinearLayout {
        val rail = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
        }
        rail.addView(
            railButton(
                R.drawable.ic_clock,
                contentDesc = context.getString(R.string.tab_hours),
                selected = tab == Tab.HOURS
            ) { selectTab(Tab.HOURS) }
        )
        rail.addView(
            railButton(
                R.drawable.ic_calendar,
                contentDesc = context.getString(R.string.tab_days),
                selected = tab == Tab.DAYS
            ) { selectTab(Tab.DAYS) }
        )
        return rail
    }

    private fun railButton(
        @DrawableRes iconRes: Int,
        contentDesc: String,
        selected: Boolean,
        onTap: () -> Unit
    ): ImageButton =
        ImageButton(context).apply {
            setImageResource(iconRes)
            contentDescription = contentDesc
            // Выбранный пункт — акцент, иначе приглушённый.
            setColorFilter(if (selected) theme.accent else theme.secondaryText)
            @Suppress("UseCompatLoadingForDrawables")
            background = context.getDrawable(android.R.drawable.list_selector_background)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { onTap() }
            layoutParams = LayoutParams(dp(56), dp(56)).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            }
        }

    // ---------- контент ----------

    private fun contentView(): LinearLayout {
        val box = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val d = WeatherHub.data
        if (d == null) {
            box.addView(
                TextView(context).apply {
                    text = if (WeatherHub.loading) {
                        context.getString(R.string.loading)
                    } else {
                        context.getString(R.string.weather_error)
                    }
                    setTextColor(theme.secondaryText)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    gravity = Gravity.CENTER
                },
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            )
            return box
        }
        when (tab) {
            Tab.HOURS -> d.hours.take(hoursCount()).forEach {
                box.addView(hourColumn(it), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            }
            Tab.DAYS -> {
                d.days.take(daysCount()).forEachIndexed { index, day ->
                    val label = if (index == 0) {
                        context.getString(R.string.today)
                    } else {
                        formatDayShort(day.dateIso, locale)
                    }
                    box.addView(
                        dayColumn(label, day),
                        LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                    )
                }
            }
        }
        return box
    }

    private fun daysCount(): Int = settings.getDays(widgetId)

    private fun hoursCount(): Int = settings.getHours(widgetId)

    /** Температура в единицах виджета: °C → "+9°", °F → "+48°F". */
    private fun temp(celsius: Double): String {
        return if (settings.getUnit(widgetId) == "F") {
            "%+.0f°F".format(celsius * 9.0 / 5.0 + 32.0)
        } else {
            "%+.0f°".format(celsius)
        }
    }

    private fun hourColumn(p: HourlyPoint): LinearLayout {
        val col = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
        }
        col.addView(cellText(p.label, theme.secondaryText, 18f))
        col.addView(cellText(WmoCodes.icon(p.weatherCode), theme.primaryText, 32f))
        col.addView(cellText(temp(p.temperatureC), theme.primaryText, 32f))
        return col
    }

    private fun dayColumn(label: String, day: DailyPoint): LinearLayout {
        val col = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
        }
        col.addView(cellText(label, theme.secondaryText, 18f))
        col.addView(cellText(WmoCodes.icon(day.weatherCode), theme.primaryText, 30f))
        col.addView(cellText(temp(day.maxC), theme.primaryText, 28f))
        col.addView(cellText(temp(day.minC), theme.secondaryText, 18f))
        return col
    }

    private fun cellText(text: String, color: Int, sp: Float): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        WeatherHub.init(context)
        WeatherHub.addListener(hubListener)
        WeatherHub.ensureLoaded()
    }

    override fun onDetachedFromWindow() {
        WeatherHub.removeListener(hubListener)
        super.onDetachedFromWindow()
    }
}
