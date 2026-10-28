package com.dash.headunit.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.dash.headunit.R
import com.dash.headunit.car.ClimateControl
import com.dash.headunit.car.ClimateControl.Cmd
import com.dash.headunit.car.ClimateHub
import com.dash.headunit.dashboard.WidgetSettingsStore
import com.dash.headunit.theme.DashTheme

/**
 * Виджет «Управление климатом»: кнопки климата и подогревов с записью в шину
 * ([ClimateControl]) и показом состояния ([ClimateHub]).
 *
 * Состав и порядок кнопок — в настройках виджета ([OPT_ITEMS], [OPT_ORDER]),
 * тема — [OPT_THEME]: классическая (цвета темы дашборда) или ретро (чёрные
 * таблички с «лампами», как [ClimateIconsView]). Символы обеих тем — [RetroClimateIcons].
 *
 * Раскладка подстраивается под блок: кнопки делятся на 1..4 строки так, чтобы
 * «единичная» кнопка была как можно крупнее; вытянутый блок — одна строка.
 * Уставки и вентилятор занимают две единицы: «−  значение  +».
 */
class ClimateControlView(
    context: Context,
    private val theme: DashTheme,
    private val retro: Boolean,
    private val items: List<Item>
) : View(context) {

    /** Кнопка: ключ настроек, ширина в единицах, подпись в настройках, команда. */
    enum class Item(val key: String, val weight: Int, val labelRes: Int, val cmd: Cmd) {
        TEMP_DRIVER("temp_driver", 2, R.string.cc_temp_driver, Cmd.TEMP_DRIVER),
        FAN("fan", 2, R.string.cc_fan, Cmd.FAN),
        TEMP_PASS("temp_pass", 2, R.string.cc_temp_pass, Cmd.TEMP_PASS),
        POWER("power", 1, R.string.cc_power, Cmd.POWER),
        AUTO("auto", 1, R.string.cc_auto, Cmd.AUTO),
        AC("ac", 1, R.string.cc_ac, Cmd.AC),
        RECIRC("recirc", 1, R.string.cc_recirc, Cmd.RECIRC),
        MODE("mode", 1, R.string.cc_mode, Cmd.MODE),
        MAX_DEFROST("max_defrost", 1, R.string.cc_max_defrost, Cmd.MAX_DEFROST),
        SYNC("sync", 1, R.string.cc_sync, Cmd.SYNC),
        WINDSHIELD_HEAT("windshield_heat", 1, R.string.cc_windshield_heat, Cmd.WINDSHIELD_HEAT),
        WHEEL_HEAT("wheel_heat", 1, R.string.cc_wheel_heat, Cmd.WHEEL_HEAT),
        SEAT_DRIVER("seat_driver", 1, R.string.cc_seat_driver, Cmd.SEAT_DRIVER),
        SEAT_PASS("seat_pass", 1, R.string.cc_seat_pass, Cmd.SEAT_PASS)
    }

    private class Cell(val item: Item, val rect: RectF)

    private val hubListener: () -> Unit = { postInvalidateOnAnimation() }

    private val hood = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (retro) Color.rgb(0x07, 0x09, 0x0B) else theme.cardBackground
    }
    private val panel = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bezel = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val button = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icons = RetroClimateIcons(hood).apply {
        if (!retro) {
            glow = false
            offColor = theme.secondaryText
            text.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
    }

    private var cells: List<Cell> = emptyList()
    private val tmp = RectF()

    /** Нажатая кнопка и зона (−1 «минус», 0 середина, +1 «плюс»). */
    private var pressed: Cell? = null
    private var pressedZone = 0

    /** Значения из тапов, пока шина их не подтвердила (логические, как в [Cmd]). */
    private val optimistic = mutableMapOf<Item, Int>()
    private val pending = mutableMapOf<Item, Int>()

    init {
        // Ореол «ламп» рисуется только программно.
        if (retro) setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutCells()
        if (retro) {
            panel.shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                Color.rgb(0x1C, 0x23, 0x2A), Color.rgb(0x0A, 0x0D, 0x10), Shader.TileMode.CLAMP
            )
        }
        bezel.shader = if (retro) LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            Color.rgb(0xA8, 0xB0, 0xB8), Color.rgb(0x3A, 0x40, 0x46), Shader.TileMode.CLAMP
        ) else null
        bezel.color = theme.cardBorder
    }

    // ---------- раскладка ----------

    /**
     * Делим кнопки (по порядку) на r = 1..4 строк с минимальной самой широкой
     * строкой и берём r, при котором единица кнопки (min ширины и высоты) крупнее.
     */
    private fun layoutCells() {
        if (items.isEmpty() || width == 0 || height == 0) {
            cells = emptyList()
            return
        }
        val pad = minOf(width, height) * 0.04f
        val w = width - 2 * pad
        val h = height - 2 * pad
        var best: List<List<Item>> = listOf(items)
        var bestUnit = -1f
        for (r in 1..minOf(4, items.size)) {
            val rows = split(items, r)
            val maxW = rows.maxOf { row -> row.sumOf { it.weight } }
            val unit = minOf(w / maxW, h / r)
            if (unit > bestUnit * 1.05f) {
                best = rows
                bestUnit = unit
            }
        }
        val rowH = h / best.size
        val gap = bestUnit * 0.08f
        val out = mutableListOf<Cell>()
        best.forEachIndexed { ri, row ->
            val rowWeight = row.sumOf { it.weight }.toFloat()
            var x = pad
            val top = pad + ri * rowH
            for (item in row) {
                val cw = w * item.weight / rowWeight
                out += Cell(item, RectF(x + gap / 2, top + gap / 2, x + cw - gap / 2, top + rowH - gap / 2))
                x += cw
            }
        }
        cells = out
    }

    /** Последовательное деление на [r] строк с минимальной максимальной шириной (ДП). */
    private fun split(list: List<Item>, r: Int): List<List<Item>> {
        val n = list.size
        val prefix = IntArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + list[i].weight
        val inf = Int.MAX_VALUE
        val cost = Array(r + 1) { IntArray(n + 1) { inf } }
        val cut = Array(r + 1) { IntArray(n + 1) }
        cost[0][0] = 0
        for (k in 1..r) for (i in k..n) for (j in (k - 1) until i) {
            if (cost[k - 1][j] == inf) continue
            val c = maxOf(cost[k - 1][j], prefix[i] - prefix[j])
            if (c < cost[k][i]) {
                cost[k][i] = c
                cut[k][i] = j
            }
        }
        val rows = ArrayList<List<Item>>()
        var i = n
        for (k in r downTo 1) {
            val j = cut[k][i]
            rows.add(0, list.subList(j, i))
            i = j
        }
        return rows
    }

    // ---------- рисование ----------

    override fun onDraw(canvas: Canvas) {
        val s = ClimateHub.data
        if (retro) {
            val rad = minOf(width, height) * 0.08f
            tmp.set(2f, 2f, width - 2f, height - 2f)
            canvas.drawRoundRect(tmp, rad, rad, panel)
            bezel.strokeWidth = 4f
            canvas.drawRoundRect(tmp, rad, rad, bezel)
        }
        for (cell in cells) drawCell(canvas, cell, s)
    }

    private fun drawCell(c: Canvas, cell: Cell, s: ClimateHub.ClimateState?) {
        val r = cell.rect
        val unit = minOf(r.width() / cell.item.weight, r.height())
        val rad = unit * 0.14f
        val isPressed = pressed === cell
        if (retro) {
            c.drawRoundRect(r, rad, rad, hood)
            bezel.strokeWidth = maxOf(1.5f, unit * 0.025f)
            c.drawRoundRect(r, rad, rad, bezel)
            if (isPressed) {
                button.color = 0x33FFFFFF
                c.drawRoundRect(r, rad, rad, button)
            }
        } else {
            button.color = if (isPressed) withAlpha(theme.accent, 0x55) else withAlpha(theme.primaryText, 0x14)
            c.drawRoundRect(r, rad, rad, button)
        }

        val on = s != null && s.power != false
        val cx = r.centerX()
        val cy = r.centerY()
        val k = unit * 0.6f / ICON_BOX
        val v = value(cell.item, s)
        when (cell.item) {
            Item.TEMP_DRIVER, Item.TEMP_PASS -> {
                lamp(on && v != null, RetroClimateIcons.LAMP_WHITE, theme.primaryText)
                drawText(c, RetroClimateIcons.setpointDeg(v?.let { ClimateHub.scaleTemp(it) }), cx, cy, unit * 0.42f, r.width() * 0.45f)
                drawPlusMinus(c, cell, unit)
            }
            Item.FAN -> {
                val fan = v?.takeIf { on && it > 0 }
                lamp(fan != null, RetroClimateIcons.LAMP_WHITE, theme.primaryText)
                val fx = cx - unit * 0.22f
                scaled(c, fx, cy, k * 0.8f) { icons.fan(c, fx, cy) }
                val txt = if (s?.power == false) "OFF" else fan?.toString() ?: "--"
                drawText(c, txt, cx + unit * 0.24f, cy, unit * if (txt.length > 1) 0.26f else 0.42f, unit * 0.4f)
                drawPlusMinus(c, cell, unit)
            }
            Item.POWER -> {
                lamp(s?.power == true, RetroClimateIcons.LAMP_GREEN, theme.accent)
                scaled(c, cx, cy, k) { icons.power(c, cx, cy) }
            }
            Item.AUTO -> {
                lamp(on && v == 1, RetroClimateIcons.LAMP_GREEN, theme.accent)
                drawText(c, "AUTO", cx, cy, unit * 0.26f, r.width() * 0.85f)
            }
            Item.SYNC -> {
                lamp(on && v == 1, RetroClimateIcons.LAMP_GREEN, theme.accent)
                drawText(c, "SYNC", cx, cy, unit * 0.26f, r.width() * 0.85f)
            }
            Item.AC -> {
                lamp(on && v == 1, RetroClimateIcons.LAMP_BLUE, theme.accent)
                scaled(c, cx, cy, k) { icons.snowflake(c, cx, cy) }
            }
            Item.RECIRC -> {
                lamp(on && v == 1, RetroClimateIcons.LAMP_GREEN, theme.accent)
                scaled(c, cx, cy, k) { icons.recirc(c, cx, cy) }
            }
            Item.MODE -> {
                lamp(on && v != null, RetroClimateIcons.LAMP_WHITE, theme.primaryText)
                scaled(c, cx, cy, k) { icons.direction(c, cx, cy, v) }
            }
            Item.MAX_DEFROST -> {
                lamp(on && v == 1, RetroClimateIcons.LAMP_AMBER, HEAT_ON)
                scaled(c, cx, cy, k) { icons.defrost(c, cx, cy) }
            }
            Item.WINDSHIELD_HEAT -> {
                lamp(v == 1, RetroClimateIcons.LAMP_AMBER, HEAT_ON)
                scaled(c, cx, cy, k) { icons.windshieldHeat(c, cx, cy) }
            }
            Item.WHEEL_HEAT -> {
                lamp(v == 1, RetroClimateIcons.LAMP_AMBER, HEAT_ON)
                scaled(c, cx, cy, k) { icons.wheel(c, cx, cy) }
            }
            Item.SEAT_DRIVER, Item.SEAT_PASS -> {
                val level = v ?: 0
                lamp(level > 0, RetroClimateIcons.LAMP_AMBER, HEAT_ON)
                scaled(c, cx, cy, k) { icons.seat(c, cx, cy, level) }
            }
        }
    }

    /** «−» и «+» по краям широкой кнопки. */
    private fun drawPlusMinus(c: Canvas, cell: Cell, unit: Float) {
        val r = cell.rect
        lamp(true, RetroClimateIcons.LAMP_WHITE, theme.secondaryText)
        if (retro) icons.text.clearShadowLayer()
        val size = unit * 0.4f
        drawText(c, "−", r.left + r.width() * SIDE / 2f, r.centerY(), size, r.width() * SIDE)
        drawText(c, "+", r.right - r.width() * SIDE / 2f, r.centerY(), size, r.width() * SIDE)
    }

    /** Текст по центру ([cx], [cy]), кегль [size], ужат до [maxW]. */
    private fun drawText(c: Canvas, text: String, cx: Float, cy: Float, size: Float, maxW: Float) {
        val p = icons.text
        p.textSize = size
        val tw = p.measureText(text)
        if (tw > maxW && tw > 0f) p.textSize = size * maxW / tw
        val fm = p.fontMetrics
        c.drawText(text, cx, cy - (fm.ascent + fm.descent) / 2f, p)
    }

    /** Цвет символа по теме: ретро — лампа [retroColor], классика — [classicColor]. */
    private fun lamp(lit: Boolean, retroColor: Int, classicColor: Int) {
        icons.lamp(lit, if (retro) retroColor else classicColor)
    }

    private inline fun scaled(c: Canvas, cx: Float, cy: Float, k: Float, draw: () -> Unit) {
        c.save()
        c.scale(k, k, cx, cy)
        draw()
        c.restore()
    }

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

    // ---------- состояние ----------

    /** Логическое значение кнопки: из тапа (пока не подтверждено) или из шины. */
    private fun value(item: Item, s: ClimateHub.ClimateState?): Int? {
        optimistic[item]?.let { return it }
        if (s == null) return null
        fun b(v: Boolean?) = v?.let { if (it) 1 else 0 }
        return when (item) {
            Item.TEMP_DRIVER -> s.tempDriverC?.let { ClimateControl.tempRaw(it) }
            Item.TEMP_PASS -> s.tempPassC?.let { ClimateControl.tempRaw(it) }
            Item.FAN -> s.fanSpeed
            Item.POWER -> b(s.power)
            Item.AUTO -> b(s.auto)
            Item.AC -> b(s.ac)
            Item.RECIRC -> b(s.recirc)
            Item.MODE -> s.fanDirection
            Item.MAX_DEFROST -> b(s.defrostFront)
            Item.SYNC -> b(s.sync)
            Item.WINDSHIELD_HEAT -> b(s.windshieldHeat)
            Item.WHEEL_HEAT -> b(s.wheelHeat)
            Item.SEAT_DRIVER -> s.seatHeatDriver
            Item.SEAT_PASS -> s.seatHeatPass
        }
    }

    // ---------- касания ----------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val cell = cells.firstOrNull { it.rect.contains(event.x, event.y) } ?: return false
                pressed = cell
                pressedZone = zone(cell, event.x)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val cell = pressed
                pressed = null
                invalidate()
                if (cell != null && cell.rect.contains(event.x, event.y)) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    tap(cell.item, pressedZone)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = null
                invalidate()
                return true
            }
        }
        return pressed != null
    }

    private fun zone(cell: Cell, x: Float): Int {
        if (cell.item.weight < 2) return 0
        val r = cell.rect
        return when {
            x < r.left + r.width() * SIDE -> -1
            x > r.right - r.width() * SIDE -> 1
            else -> 0
        }
    }

    private fun tap(item: Item, zone: Int) {
        val cur = value(item, ClimateHub.data)
        val next = when (item) {
            Item.TEMP_DRIVER, Item.TEMP_PASS -> {
                if (cur == null || zone == 0) return
                (cur + zone).coerceIn(0, 16)
            }
            Item.FAN -> {
                if (zone == 0) return
                ((cur ?: 0) + zone).coerceIn(1, 7)
            }
            // 1 лицо, 2 ноги, 3 лицо+ноги, 4 ноги+стекло; 5 (стекло) — кнопка MAX.
            Item.MODE -> (cur ?: 0).coerceIn(0, 4) % 4 + 1
            Item.SEAT_DRIVER, Item.SEAT_PASS -> ((cur ?: 0) + 1) % 4
            else -> if (cur == 1) 0 else 1
        }
        if (next == cur) return
        optimistic[item] = next
        pending[item] = (pending[item] ?: 0) + 1
        invalidate()
        ClimateControl.send(context, item.cmd, next) {
            // Шина перечитывается асинхронно — даём ей время, потом отпускаем значение тапа.
            postDelayed({
                val left = (pending[item] ?: 1) - 1
                if (left <= 0) {
                    pending.remove(item)
                    optimistic.remove(item)
                } else {
                    pending[item] = left
                }
                invalidate()
            }, SETTLE_MS)
        }
    }

    companion object {
        const val OPT_THEME = "theme"
        const val OPT_ITEMS = "items"
        const val OPT_ORDER = "order"
        const val THEME_CLASSIC = "classic"
        const val THEME_RETRO = "retro"

        /** Набор по умолчанию: климат без подогревов. */
        val DEFAULT_ITEMS = listOf(
            Item.TEMP_DRIVER, Item.FAN, Item.TEMP_PASS, Item.POWER, Item.AUTO,
            Item.AC, Item.RECIRC, Item.MODE, Item.MAX_DEFROST
        )

        /** Символы [RetroClimateIcons] — в коробке ~40 единиц. */
        private const val ICON_BOX = 40f

        /** Доля ширины широкой кнопки под «−» / «+». */
        private const val SIDE = 0.28f

        private const val SETTLE_MS = 1500L

        /** Включённый подогрев в классической теме — жёлтый, как в «Подогреве». */
        private val HEAT_ON = 0xFFFDD835.toInt()

        /** Полный порядок кнопок виджета [widgetId] (сохранённый + новые в конце). */
        fun order(settings: WidgetSettingsStore, widgetId: String): List<Item> {
            val saved = parse(settings.getOption(widgetId, OPT_ORDER, ""))
            val base = if (saved.isEmpty()) DEFAULT_ITEMS + Item.values().filter { it !in DEFAULT_ITEMS } else saved
            return base + Item.values().filter { it !in base }
        }

        /** Показываемые кнопки по порядку; пусто в настройках — [DEFAULT_ITEMS]. */
        fun selected(settings: WidgetSettingsStore, widgetId: String): List<Item> {
            val raw = settings.getOption(widgetId, OPT_ITEMS, "")
            if (raw.isEmpty()) return DEFAULT_ITEMS
            val visible = parse(raw).toSet()
            return order(settings, widgetId).filter { it in visible }
        }

        private fun parse(raw: String): List<Item> = raw.split(',')
            .mapNotNull { key -> Item.values().firstOrNull { it.key == key.trim() } }
            .distinct()
    }
}
