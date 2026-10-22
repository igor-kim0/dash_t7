package com.dash.headunit.widgets

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.dash.headunit.R
import com.dash.headunit.dashboard.WidgetSettingsStore
import com.dash.headunit.elm.ElmBtProbe
import com.dash.headunit.elm.ElmHub
import com.dash.headunit.theme.DashTheme
import com.dash.headunit.ui.DashDialog

/**
 * Виджет «OBD (ELM)»: показания с Bluetooth-сканера ELM327 ([ElmHub]).
 * Набор показателей (ОЖ, бортсеть, обороты, нагрузка, температура
 * впускного воздуха, дроссель, топливные балансировки, MAP, уровень
 * топлива) выбирается в настройках плитки и хранится per-widget
 * ([WidgetSettingsStore] → ключ «elmb_items»): отображаются только
 * отмеченные, в фиксированном порядке.
 *
 * Тап по плитке — панель Bluetooth-диагностики ([ElmDiagPanel]): скан,
 * спаривание, выбор адаптера, тест подключения, журнал.
 */
class ElmWidgetView(
    context: Context,
    private val theme: DashTheme = DashTheme.DARK,
    private val widgetId: String = "",
    private val settingsStore: WidgetSettingsStore? = null
) : LinearLayout(context) {

    private class Metric(
        val key: String,
        val labelRes: Int,
        val format: (Float) -> String,
        val color: ((Float) -> Int?)? = null
    )

    private class Cell(val value: TextView, val caption: TextView)

    private val statusView = TextView(context)
    /** Отображаемые показатели (в порядке списка для настроек). */
    private val metrics: List<Metric>
    /** Ячейка на каждый отображаемый показатель. */
    private val cells = HashMap<String, Cell>()
    private val handler = Handler(Looper.getMainLooper())
    private var tickerRunning = false

    private val ticker: Runnable = object : Runnable {
        override fun run() {
            render()
            if (tickerRunning) handler.postDelayed(this, REFRESH_MS)
        }
    }

    init {
        orientation = VERTICAL
        val pad = dp(6)
        setPadding(pad, pad, pad, pad)

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = context.getString(R.string.elm_title)
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        statusView.apply {
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.END
            maxLines = 1
        }
        header.addView(statusView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        metrics = selectedIds(settingsStore, widgetId).mapNotNull { id -> metric(id) }

        // Сетка: по 2 показателя в ряду, ряды делят высоту поровну.
        val rows = (metrics.size + 1) / 2
        val valueSize = if (rows <= 2) 32f else 24f
        val captionSize = if (rows <= 2) 13f else 12f
        metrics.chunked(2).forEach { pair ->
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
            }
            pair.forEach { metric ->
                val box = LinearLayout(context).apply {
                    orientation = VERTICAL
                    gravity = Gravity.CENTER
                }
                val value = TextView(context).apply {
                    setTextColor(theme.primaryText)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, valueSize)
                    gravity = Gravity.CENTER
                    maxLines = 1
                }
                val caption = TextView(context).apply {
                    text = context.getString(metric.labelRes)
                    setTextColor(theme.secondaryText)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, captionSize)
                    gravity = Gravity.CENTER
                }
                box.addView(value, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
                box.addView(caption, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
                row.addView(box, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                cells[metric.key] = Cell(value, caption)
            }
            addView(
                row,
                LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            )
        }

        setOnClickListener { showDiagnostics() }
        render()
    }

    private fun metric(key: String): Metric? = when (key) {
        "coolant" -> Metric(key, R.string.elm_coolant, { "%.0f°".format(it) }, { engineTempColor(it, theme.success) })
        "volt" -> Metric(key, R.string.elm_voltage, { "%.1f В".format(it) }, { voltageColor(it, theme.success) })
        "rpm" -> Metric(key, R.string.elm_rpm, { "%.0f".format(it) })
        "load" -> Metric(key, R.string.elm_load, { "%.0f %%".format(it) })
        "iat" -> Metric(key, R.string.elm_intake, { "%.0f°".format(it) })
        "throttle" -> Metric(key, R.string.elm_throttle, { "%.0f %%".format(it) })
        "trim_short" -> Metric(key, R.string.elm_trim_short, { "%+.0f %%".format(it) })
        "trim_long" -> Metric(key, R.string.elm_trim_long, { "%+.0f %%".format(it) })
        "map" -> Metric(key, R.string.elm_map, { "%.0f кПа".format(it) })
        "fuel_level" -> Metric(key, R.string.elm_fuel_level, { "%.0f %%".format(it) })
        else -> null
    }

    private fun render() {
        val snapshot = ElmHub.readings()
        metrics.forEach { metric ->
            val cell = cells[metric.key] ?: return@forEach
            val v = snapshot[metric.key]
            show(
                cell.value,
                v?.let { metric.format(it) },
                v?.let { metric.color?.invoke(it) }
            )
        }

        val s = ElmHub.lastStatus
        statusView.text = when {
            ElmHub.address() == null -> context.getString(R.string.elm_no_adapter)
            // Напряжение (ATRV) меряет сам адаптер — связь с ЭБУ это не доказывает.
            ElmHub.connected && snapshot.keys.any { it != "volt" } -> context.getString(R.string.elm_link_ok)
            ElmHub.connected && snapshot.isNotEmpty() -> context.getString(R.string.elm_adapter_only)
            ElmHub.connected || s.startsWith("подключа") -> context.getString(R.string.elm_connecting)
            else -> context.getString(R.string.elm_no_link)
        }
    }

    private fun show(value: TextView, text: String?, color: Int?) {
        value.text = text ?: "—"
        value.setTextColor(color ?: theme.primaryText)
    }

    private fun showDiagnostics() {
        DashDialog.Builder(context)
            .setTitle(R.string.elm_diag_title)
            .setView(ElmDiagPanel(context, theme))
            .setNegativeButton(R.string.dialog_close, null)
            .showFullScreen()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ElmHub.start(context)
        ElmBtProbe.start(context)
        tickerRunning = true
        handler.post(ticker)
    }

    override fun onDetachedFromWindow() {
        tickerRunning = false
        handler.removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        /** Опция «OBD (ELM)»: какие показатели показывать ([WidgetSettingsStore]). */
        const val OPT_ITEMS = "items"

        /** Маркер опции «показатели сняты все» — иначе пустота означала бы дефолт. */
        const val NONE_ITEMS = "-"

        private const val REFRESH_MS = 500L

        /** Все возможные показатели — порядок совпадает с опросом [ElmHub]. */
        val ITEM_KEYS =
            listOf("coolant", "volt", "rpm", "load", "iat", "throttle", "trim_short", "trim_long", "map", "fuel_level")

        /** Пока виджет не настроен — как было: четыре базовых показателя. */
        val DEFAULT_ITEMS = listOf("coolant", "volt", "rpm", "load")

        fun labelRes(key: String): Int? = when (key) {
            "coolant" -> R.string.elm_coolant
            "volt" -> R.string.elm_voltage
            "rpm" -> R.string.elm_rpm
            "load" -> R.string.elm_load
            "iat" -> R.string.elm_intake
            "throttle" -> R.string.elm_throttle
            "trim_short" -> R.string.elm_trim_short
            "trim_long" -> R.string.elm_trim_long
            "map" -> R.string.elm_map
            "fuel_level" -> R.string.elm_fuel_level
            else -> null
        }

        /** Выбранные для показа показатели: пустая опция — дефолт, «-» — пустой набор. */
        fun selectedIds(store: WidgetSettingsStore?, widgetId: String): List<String> {
            val raw = store?.getOption(widgetId, OPT_ITEMS, "")?.trim().orEmpty()
            if (raw.isEmpty()) return DEFAULT_ITEMS
            if (raw == NONE_ITEMS) return emptyList()
            return raw.split(',').map { it.trim() }
                .filter { it in ITEM_KEYS }
                .distinct()
        }
    }
}
