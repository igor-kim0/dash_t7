package com.dash.headunit.widgets

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.dash.headunit.R
import com.dash.headunit.car.CarData
import com.dash.headunit.car.CarHub
import com.dash.headunit.car.CarScaling
import com.dash.headunit.car.ClusterPps
import com.dash.headunit.car.CoolantCalibration
import com.dash.headunit.car.DemoData
import com.dash.headunit.dashboard.WidgetSettingsStore
import com.dash.headunit.theme.DashTheme
import java.util.Locale

/** Иконка бака: силуэт заливается снизу вверх на fraction (0..1) цветом [fillColor]. */
private class FuelGaugeIconView(context: Context) : View(context) {

    // Два отдельных инстанса drawable: на аппаратном canvas повторный draw()
    // одного объекта с разными setTint рисует одинаковым последним цветом.
    private val baseIcon = androidx.appcompat.content.res.AppCompatResources
        .getDrawable(context, R.drawable.ic_fuel)?.mutate()
    private val fillIcon = androidx.appcompat.content.res.AppCompatResources
        .getDrawable(context, R.drawable.ic_fuel)?.mutate()

    var fraction: Float? = null
        set(value) {
            field = value?.coerceIn(0f, 1f)
            invalidate()
        }

    var baseColor: Int = DashTheme.DARK.secondaryText
        set(value) {
            field = value
            baseIcon?.setTint(value)
            invalidate()
        }

    var fillColor: Int = DashTheme.DARK.success
        set(value) {
            field = value
            fillIcon?.setTint(value)
            invalidate()
        }

    init {
        baseIcon?.setTint(baseColor)
        fillIcon?.setTint(fillColor)
    }

    override fun onDraw(canvas: Canvas) {
        val b = baseIcon ?: return
        // Незалитый силуэт (фон иконки).
        b.setBounds(0, 0, width, height)
        b.draw(canvas)
        // Заливка снизу вверх — «уровень топлива».
        val f = fraction ?: return
        if (f <= 0f) return
        val fi = fillIcon ?: return
        val save = canvas.save()
        canvas.clipRect(0, (height * (1f - f)).toInt(), width, height)
        fi.setBounds(0, 0, width, height)
        fi.draw(canvas)
        canvas.restoreToCount(save)
    }
}

internal val FUEL_RED = 0xFFE53935.toInt()
internal val FUEL_YELLOW = 0xFFFDD835.toInt()
internal val TEMP_BLUE = 0xFF42A5F5.toInt()

/**
 * Цвет заливки иконки бака: больше 25 % — зелёный ([green]),
 * меньше 8 % — красный, между этими значениями (включая границы) — жёлтый.
 */
internal fun fuelLevelColor(pct: Float, green: Int): Int = when {
    pct > 25f -> green
    pct < 8f -> FUEL_RED
    else -> FUEL_YELLOW
}

/** Цвет иконки бортовой сети: 11,2…14,6 В (включительно) — зелёный, вне диапазона — красный. */
internal fun voltageColor(v: Float, green: Int): Int =
    if (v < 11.2f || v > 14.6f) FUEL_RED else green

/** Цвет иконки температуры двигателя: <35 синий, <80 жёлтый, <110 зелёный, иначе красный. */
internal fun engineTempColor(t: Float, green: Int): Int = when {
    t < 35f -> TEMP_BLUE
    t < 80f -> FUEL_YELLOW
    t < 110f -> green
    else -> FUEL_RED
}

/** Цвет иконки запаса хода: <70 км красный, <200 км жёлтый, иначе зелёный. */
internal fun rangeColor(km: Int, green: Int): Int = when {
    km < 70 -> FUEL_RED
    km < 200 -> FUEL_YELLOW
    else -> green
}

/**
 * Виджет «Автомобиль»: компактный ряд из метрик —
 * ТОПЛИВО (литры / % бака; иконка бака заливается снизу вверх:
 * >25 % зелёный, <8 % красный, иначе жёлтый) | АККУМУЛЯТОР (вольты / статус) |
 * ДВИГАТЕЛЬ (температура / прогрев) | ЗАПАС ХОДА (км).
 * Иконки остальных метрик целиком окрашиваются по порогам
 * ([voltageColor], [engineTempColor], [rangeColor]).
 * Вместо текстовых заголовков — иконки слева от значений (если в колонку
 * не помещается — над значениями, у всех метрик сразу); подписи — от левого
 * края иконки; единицы выровнены по низу цифр.
 *
 * Какие метрики показывать и в каком порядке — настройка виджета
 * (ключи "items" и "order" в [WidgetSettingsStore], см. [MainActivity.showCarSettings]);
 * по умолчанию — все в порядке [ITEM_KEYS].
 * Данные — из общего [CarHub] (Desay SV binder → VHAL, опрос каждые 5 с).
 * Отсутствующие значения рисуются как "—".
 */
class CarWidgetView(
    context: Context,
    private val theme: DashTheme = DashTheme.DARK,
    private val widgetId: String? = null,
    private val settings: WidgetSettingsStore? = null
) : LinearLayout(context) {

    private val locale = Locale("ru")
    private val hubListener: () -> Unit = { render() }

    // Метрики.
    private val fuelValue = valueView()
    private val fuelUnit = unitView()
    private val fuelSub = subView()
    private val fuelIcon = FuelGaugeIconView(context).apply {
        baseColor = theme.secondaryText
    }
    private val battValue = valueView()
    private val battUnit = unitView()
    private val battSub = subView()
    private val battIcon = iconView(R.drawable.ic_battery)
    private val engValue = valueView()
    private val engUnit = unitView()
    private val engSub = subView()
    private val engIcon = iconView(R.drawable.ic_thermo)
    private val rangeValue = valueView()
    private val rangeUnit = unitView()
    private val rangeSub = subView()
    private val rangeIcon = iconView(R.drawable.ic_range)

    // Объявлены до init: заполняются в metric().
    private val metricViews = mutableListOf<MetricViews>()
    private var iconBeside = true

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL

        val row = row()
        val cols = orderedItems().map { key ->
            when (key) {
                "batt" -> metric(battIcon, battValue, battUnit, battSub)
                "eng" -> metric(engIcon, engValue, engUnit, engSub, unitTop = true)
                "range" -> metric(rangeIcon, rangeValue, rangeUnit, rangeSub)
                else -> metric(fuelIcon, fuelValue, fuelUnit, fuelSub)
            }
        }
        // Колонки по ширине содержимого, свободное место — поровну между ними
        // (равные промежутки, а не равные колонки).
        cols.forEachIndexed { i, col ->
            if (i > 0) row.addView(View(context), LayoutParams(0, 0, 1f))
            row.addView(col, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        // Крайние метрики не должны липнуть к краям карточки.
        row.setPadding(dp(SIDE_INDENT_DP), 0, dp(SIDE_INDENT_DP), 0)
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        setOnClickListener { CarHub.refresh() }
        render()
    }

    private fun row(): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        // Колонки от верха: иконки и значения на одной высоте.
        gravity = Gravity.TOP
    }

    private fun valueView(): TextView = TextView(context).apply {
        setTextColor(theme.primaryText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
    }

    private fun unitView(): TextView = TextView(context).apply {
        setTextColor(theme.secondaryText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setPadding(dp(4), 0, 0, 0)
    }

    private fun subView(): TextView = TextView(context).apply {
        setTextColor(theme.secondaryText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
    }

    private fun iconView(res: Int): ImageView = ImageView(context).apply {
        setImageResource(res)
        setColorFilter(theme.secondaryText)
    }

    /** Вёрстка одной метрики — нужна для переключения «иконка сбоку / сверху». */
    private class MetricViews(
        val col: LinearLayout,
        val top: LinearLayout,
        val icon: View,
        val value: TextView,
        val unit: TextView,
        val sub: TextView
    )

    /**
     * Колонка метрики: иконка слева от значения+единицы (или над ним, если
     * в ширину виджета не помещается — см. [onMeasure]); под ними — подпись
     * от левого края иконки.
     * [unitTop] — единицу прижать к верху цифр (для «°»: кружок живёт
     * в верхней части кегля, снизу выглядит оторванной точкой).
     */
    private fun metric(
        icon: View,
        value: TextView,
        unit: TextView,
        sub: TextView,
        unitTop: Boolean = false
    ): LinearLayout {
        val col = LinearLayout(context).apply { orientation = VERTICAL }
        val iconSize = dp(40)
        val top = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(icon, LayoutParams(iconSize, iconSize).apply { rightMargin = dp(ICON_GAP_DP) })
        val valueRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            // Единицы (л / В / ° / км) — по нижнему краю цифр, у всех метрик одинаково.
            gravity = Gravity.BOTTOM
        }
        valueRow.addView(value, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        valueRow.addView(unit, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            if (unitTop) gravity = Gravity.TOP
        })
        top.addView(valueRow)
        col.addView(top)
        col.addView(sub)
        metricViews += MetricViews(col, top, icon, value, unit, sub)
        return col
    }

    /** Иконка сбоку от значения ([beside]) или над ним — у всех метрик сразу. */
    private fun applyIconBeside(beside: Boolean) {
        iconBeside = beside
        for (m in metricViews) {
            m.top.orientation = if (beside) HORIZONTAL else VERTICAL
            m.top.gravity = if (beside) Gravity.CENTER_VERTICAL else Gravity.START
            (m.icon.layoutParams as LayoutParams).rightMargin = if (beside) dp(ICON_GAP_DP) else 0
        }
    }

    private fun textWidth(t: TextView): Int =
        kotlin.math.ceil(t.paint.measureText(t.text.toString())).toInt() +
            t.paddingLeft + t.paddingRight

    /** Ширина метрики с иконкой сбоку: иконка + отступ + цифры + единица (или подпись, если шире). */
    private fun besideWidth(m: MetricViews): Int {
        val lp = m.icon.layoutParams as LayoutParams
        val top = lp.width + dp(ICON_GAP_DP) + textWidth(m.value) + textWidth(m.unit)
        return maxOf(top, textWidth(m.sub))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Иконки сбоку, если все метрики с минимальными промежутками помещаются
        // в ширину; иначе — иконки над значениями у всех.
        val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight -
            2 * dp(SIDE_INDENT_DP)
        val need = metricViews.sumOf { besideWidth(it) } +
            (metricViews.size - 1).coerceAtLeast(0) * dp(MIN_COL_GAP_DP)
        val beside = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED ||
            need <= available
        if (beside != iconBeside) applyIconBeside(beside)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()

    /** Выбранные в настройках метрики в порядке пользователя; пусто/нет настроек — все по умолчанию. */
    private fun orderedItems(): List<String> {
        val orderRaw = widgetId?.let { settings?.getOption(it, OPT_ORDER, "") } ?: ""
        val order = orderRaw.split(',')
            .mapNotNull { it.trim().takeIf { s -> s in ITEM_KEYS } }
            .distinct()
        val base = if (order.isEmpty()) ITEM_KEYS
        else order + ITEM_KEYS.filter { it !in order }
        val itemsRaw = widgetId?.let { settings?.getOption(it, OPT_ITEMS, "") } ?: ""
        // Пустая опция = по умолчанию показываем всё.
        if (itemsRaw.isEmpty()) return base
        val visible = itemsRaw.split(',')
            .mapNotNull { it.trim().takeIf { s -> s in ITEM_KEYS } }
            .toSet()
        return base.filter { it in visible }.ifEmpty { ITEM_KEYS }
    }

    // ---------- отрисовка ----------

    private fun render() {
        // Нет данных авто (эмулятор) — показываем демо-снимок.
        val d = CarHub.data ?: DemoData.car()
        // Топливо.
        if (d?.fuelLiters != null) {
            fuelValue.text = "%.0f".format(locale, d.fuelLiters)
            fuelUnit.text = "л"
            val pct = d.fuelPct
            fuelSub.text = if (pct != null) {
                context.getString(R.string.car_fuel_sub, Math.round(pct))
            } else {
                context.getString(R.string.car_no_data)
            }
            fuelIcon.fraction = pct?.div(100f)
            if (pct != null) fuelIcon.fillColor = fuelLevelColor(pct, theme.success)
        } else {
            fuelValue.text = "—"
            fuelUnit.text = ""
            fuelSub.text = context.getString(R.string.car_no_data)
            fuelIcon.fraction = null
        }
        // Аккумулятор.
        if (d?.voltageV != null) {
            battValue.text = "%.1f".format(locale, d.voltageV)
            battUnit.text = "В"
            battSub.text = if (d.charging == true) {
                context.getString(R.string.car_charging)
            } else {
                context.getString(R.string.car_board_net)
            }
            battIcon.setColorFilter(voltageColor(d.voltageV, theme.success))
        } else {
            battValue.text = "—"
            battUnit.text = ""
            battSub.text = context.getString(R.string.car_no_data)
            battIcon.setColorFilter(theme.secondaryText)
        }
        // Двигатель: прямые градусы (a0ff приборки / OBD / VHAL / Desay),
        // без них — оценка по стрелке ОЖ приборки, как у ретро-спидометра
        // ([CoolantCalibration.estimate], со знаком «≈»), иначе «—».
        val engExact = d?.engineTempC
        val eng = engExact ?: ClusterPps.needle()?.let { CoolantCalibration.estimate(it) }
        if (eng != null) {
            engValue.text = (if (engExact == null) "≈" else "") + "%.0f".format(locale, eng)
            engUnit.text = "°"
            engIcon.setColorFilter(engineTempColor(eng, theme.success))
            // Те же пороги, что у цвета иконки ([engineTempColor]).
            engSub.text = when {
                eng < 35f -> context.getString(R.string.car_cold)
                eng < 80f -> context.getString(R.string.car_warming)
                eng < 110f -> context.getString(R.string.car_warm)
                else -> context.getString(R.string.car_overheat)
            }
        } else {
            engValue.text = "—"
            engUnit.text = ""
            engSub.text = context.getString(R.string.car_no_data)
            engIcon.setColorFilter(theme.secondaryText)
        }
        // Запас хода.
        if (d?.rangeKm != null && d.rangeKm > 0) {
            rangeValue.text = d.rangeKm.toString()
            rangeUnit.text = "км"
            rangeSub.text = context.getString(R.string.car_range_sub)
            rangeIcon.setColorFilter(rangeColor(d.rangeKm, theme.success))
        } else {
            rangeValue.text = "—"
            rangeUnit.text = ""
            rangeSub.text = context.getString(R.string.car_no_data)
            rangeIcon.setColorFilter(theme.secondaryText)
        }
        // Забортная температура переехала в виджет «Часы».
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

    companion object {
        /** Ключи метрик в настройке "items" (порядок — порядок отображения). */
        val ITEM_KEYS = listOf("fuel", "batt", "eng", "range")

        /** Имя опции в [WidgetSettingsStore] со списком видимых метрик. */
        const val OPT_ITEMS = "items"

        /** Имя опции в [WidgetSettingsStore] с порядком всех метрик. */
        const val OPT_ORDER = "order"

        /** Отступ между иконкой и значением, когда иконка сбоку. */
        private const val ICON_GAP_DP = 8

        /** Дополнительный отступ крайних метрик от краёв карточки. */
        private const val SIDE_INDENT_DP = 12

        /** Минимальный промежуток между метриками, при котором иконки ещё сбоку. */
        private const val MIN_COL_GAP_DP = 12
    }
}
