package com.dash.headunit.widgets

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.dash.headunit.R
import com.dash.headunit.dashboard.WidgetSettingsStore
import com.dash.headunit.elm.ElmBtProbe
import com.dash.headunit.elm.ElmHub
import com.dash.headunit.elm.ObdProfile
import com.dash.headunit.theme.DashTheme
import com.dash.headunit.ui.DashDialog

/**
 * Виджет «OBD (ELM)»: показания с Bluetooth-сканера ELM327 ([ElmHub]).
 * Без JSON-профиля — стандартный OBD-II (ОЖ, бортсеть, обороты, нагрузка,
 * температура впускного воздуха, дроссель, топливные балансировки, MAP,
 * уровень топлива); с профилем ([ObdProfile], выбирается в настройках
 * плитки) — ОЖ, бортсеть и параметры профиля. Отмеченные показатели
 * хранятся per-widget ([WidgetSettingsStore] → опция [OPT_ITEMS]) и
 * отображаются в порядке [items].
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
        val label: String,
        val format: (Double) -> String,
        val color: ((Double) -> Int?)? = null
    )

    private class Cell(val value: TextView, val caption: TextView)

    private val statusView = TextView(context)
    /** Отображаемые показатели (в порядке списка для настроек). */
    private val metrics: List<Metric>
    /** Ячейка на каждый отображаемый показатель. */
    private val cells = HashMap<String, Cell>()
    private val handler = Handler(Looper.getMainLooper())
    private var tickerRunning = false

    /** Сетка показателей: ряды по [columns] ячеек, делят всю высоту поровну. */
    private val grid = LinearLayout(context)
    private val boxes = ArrayList<LinearLayout>()
    private var gridW = 0
    private var gridH = 0
    private var columns = 0
    private var cellW = 0
    private var cellH = 0
    /** Высота подписи в ячейке, px (для расчёта места под значение). */
    private var captionPx = 0f
    private val measurePaint = android.graphics.Paint()

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

        // Профиль читается в start(): до него набор показателей не известен.
        ElmHub.start(context)
        val profile = ElmHub.profile()
        metrics = selectedIds(settingsStore, widgetId).mapNotNull { id -> metric(id, profile) }

        // Ячейки создаются один раз; раскладку по рядам и размер шрифта
        // считает [relayout], когда известен размер сетки.
        metrics.forEach { metric ->
            val box = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER
            }
            val value = TextView(context).apply {
                setTextColor(theme.primaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
                gravity = Gravity.CENTER
                maxLines = 1
                includeFontPadding = false
            }
            val caption = TextView(context).apply {
                text = metric.label
                setTextColor(theme.secondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            box.addView(value, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            box.addView(caption, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            boxes += box
            cells[metric.key] = Cell(value, caption)
        }
        grid.orientation = VERTICAL
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        grid.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val w = r - l
            val h = b - t
            // Перестраивать ряды внутри прохода layout нельзя — откладываем.
            if (w != gridW || h != gridH) handler.post { relayout(w, h) }
        }

        setOnClickListener { showDiagnostics() }
        render()
    }

    private fun metric(key: String, profile: ObdProfile?): Metric? {
        profileParam(profile, key)?.let { p ->
            return Metric(key, p.name, p::format, { if (p.outOfRange(it)) FUEL_RED else null })
        }
        val label = labelRes(key)?.let(context::getString) ?: return null
        return when (key) {
            "coolant" -> Metric(key, label, { "%.0f°".format(it) }, { engineTempColor(it.toFloat(), theme.success) })
            "volt" -> Metric(key, label, { "%.1f В".format(it) }, { voltageColor(it.toFloat(), theme.success) })
            "rpm" -> Metric(key, label, { "%.0f".format(it) })
            "load" -> Metric(key, label, { "%.0f %%".format(it) })
            "iat" -> Metric(key, label, { "%.0f°".format(it) })
            "throttle" -> Metric(key, label, { "%.0f %%".format(it) })
            "trim_short" -> Metric(key, label, { "%+.0f %%".format(it) })
            "trim_long" -> Metric(key, label, { "%+.0f %%".format(it) })
            "map" -> Metric(key, label, { "%.0f кПа".format(it) })
            "fuel_level" -> Metric(key, label, { "%.0f %%".format(it) })
            else -> null
        }
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

        fitValues()

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

    /**
     * Разложить ячейки по сетке [w]×[h]: число колонок выбирается так,
     * чтобы значения вышли крупнее всего (значение ≈ 4 знака шириной
     * 0,6 кегля, высота — ячейка без подписи), ряды делят высоту поровну.
     */
    private fun relayout(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        gridW = w
        gridH = h
        val n = boxes.size
        if (n == 0) return
        var best = 1
        var bestSize = 0f
        for (cols in 1..n) {
            val rows = (n + cols - 1) / cols
            val cw = w.toFloat() / cols
            val ch = h.toFloat() / rows
            val size = minOf(cw * 0.9f / (VALUE_CHARS * 0.6f), ch * (1 - CAPTION_SHARE) * 0.85f)
            if (size > bestSize * 1.02f) {
                best = cols
                bestSize = size
            }
        }
        val rows = (n + best - 1) / best
        cellW = w / best
        cellH = h / rows
        val captionSp = (cellH * CAPTION_SHARE * 0.55f / resources.displayMetrics.scaledDensity).coerceIn(12f, 18f)
        boxes.forEach { box ->
            (box.getChildAt(1) as TextView).setTextSize(TypedValue.COMPLEX_UNIT_SP, captionSp)
        }
        captionPx = captionSp * resources.displayMetrics.scaledDensity * 1.25f
        if (best != columns || grid.childCount != rows) {
            columns = best
            boxes.forEach { (it.parent as? LinearLayout)?.removeView(it) }
            grid.removeAllViews()
            boxes.chunked(best).forEach { chunk ->
                val row = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER
                }
                chunk.forEach { row.addView(it, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)) }
                // Неполный последний ряд — пустые места, чтобы ячейки были одной ширины.
                repeat(best - chunk.size) { row.addView(android.view.View(context), LayoutParams(0, 0, 1f)) }
                grid.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
            }
        }
        fitValues()
    }

    /**
     * Один кегль для всех значений — наибольший, при котором каждое текущее
     * значение (и шаблон из [VALUE_CHARS] цифр) влезает в свою ячейку:
     * так соседние числа одного размера и не прыгают от каждой цифры.
     */
    private fun fitValues() {
        if (cellW <= 0 || cellH <= 0) return
        val byHeight = (cellH - captionPx) * 0.85f
        if (byHeight <= 0f) return
        measurePaint.typeface = cells.values.firstOrNull()?.value?.typeface
        measurePaint.textSize = 100f
        val sample = measurePaint.measureText("0".repeat(VALUE_CHARS))
        val widest = cells.values.maxOfOrNull { measurePaint.measureText(it.value.text.toString()) } ?: 0f
        val byWidth = cellW * 0.9f * 100f / maxOf(sample, widest, 1f)
        val px = minOf(byHeight, byWidth)
        cells.values.forEach { c ->
            if (Math.abs(c.value.textSize - px) > 1f) c.value.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
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

        /** Под сколько знаков значения рассчитан кегль (длиннее — уменьшается). */
        private const val VALUE_CHARS = 4

        /** Доля высоты ячейки под подпись. */
        private const val CAPTION_SHARE = 0.22f

        /** Стандартный OBD-II (без профиля) — порядок совпадает с опросом [ElmHub]. */
        val ITEM_KEYS =
            listOf("coolant", "volt", "rpm", "load", "iat", "throttle", "trim_short", "trim_long", "map", "fuel_level")

        /** С профилем [ElmHub] всегда опрашивает ОЖ и напряжение адаптера. */
        private val PROFILE_BASE_KEYS = listOf("coolant", "volt")

        /** Пока виджет не настроен — как было: четыре базовых показателя. */
        val DEFAULT_ITEMS = listOf("coolant", "volt", "rpm", "load")

        /** Показатель для списка настроек: ключ [ElmHub.readings] и подпись. */
        class Item(val key: String, val label: String)

        /** Параметр профиля по ключу показателя ([ElmHub.profileKey]) или null. */
        private fun profileParam(profile: ObdProfile?, key: String): ObdProfile.Param? =
            if (profile == null || !key.startsWith("p:")) null
            else profile.params.firstOrNull { ElmHub.profileKey(it) == key }

        /**
         * Все показатели, которые сейчас опрашиваются: без профиля —
         * [ITEM_KEYS], с профилем — ОЖ, напряжение и параметры профиля
         * (подпись «Группа: имя»).
         */
        fun items(context: Context): List<Item> {
            val profile = ElmHub.profile()
                ?: return ITEM_KEYS.map { Item(it, context.getString(checkNotNull(labelRes(it)))) }
            return PROFILE_BASE_KEYS.map { Item(it, context.getString(checkNotNull(labelRes(it)))) } +
                profile.groups.flatMap { g -> g.params.map { Item(ElmHub.profileKey(it), "${g.title}: ${it.name}") } }
        }

        private fun availableKeys(): List<String> {
            val profile = ElmHub.profile() ?: return ITEM_KEYS
            return PROFILE_BASE_KEYS + profile.params.map(ElmHub::profileKey)
        }

        /** Набор по умолчанию: без профиля — [DEFAULT_ITEMS], с профилем — ОЖ, АКБ и два первых параметра. */
        private fun defaultIds(): List<String> {
            val profile = ElmHub.profile() ?: return DEFAULT_ITEMS
            return PROFILE_BASE_KEYS + profile.params.take(2).map(ElmHub::profileKey)
        }

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

        /**
         * Выбранные для показа показатели в порядке [items]: пустая опция —
         * дефолт, «-» — пустой набор. Если из сохранённого набора в текущем
         * профиле не осталось ничего (сменили профиль) — тоже дефолт.
         */
        fun selectedIds(store: WidgetSettingsStore?, widgetId: String): List<String> {
            val raw = store?.getOption(widgetId, OPT_ITEMS, "")?.trim().orEmpty()
            if (raw.isEmpty()) return defaultIds()
            if (raw == NONE_ITEMS) return emptyList()
            val saved = raw.split(',').map { it.trim() }.toSet()
            return availableKeys().filter { it in saved }.ifEmpty { defaultIds() }
        }
    }
}
