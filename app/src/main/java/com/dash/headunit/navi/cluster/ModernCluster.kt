package com.dash.headunit.navi.cluster

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import com.dash.headunit.car.CarHub
import com.dash.headunit.car.ClusterPps
import com.dash.headunit.car.CoolantCalibration
import com.dash.headunit.widgets.engineTempColor
import com.dash.headunit.widgets.fuelLevelColor
import com.dash.headunit.widgets.gearIndex
import java.util.Locale

/**
 * Тема приборки «Современная» (дисплей 1, 1920×720, страница «Карты»).
 * Штатные скорость, обороты и угловые дуги спрятаны ([ClusterGauges]);
 * верхняя строка приборки (y ≈ 0–90) и нижняя (y ≈ 620–720) остаются —
 * всё своё рисуем между ними:
 *
 *   ╭ Навигация / Поездка ╮      ╭───╮       Топливо   Двигатель
 *   │ ↰  300 м            │    ╱ 3 4 5 ╲       ╭──╮      ╭──╮
 *   │ ▓▓▓▓▓░░             │   │2  87  6│      │42л│      │89°│
 *   │ ул. Ленина          │   │1 км/ч 7│       ╰──╯      ╰──╯
 *   │ 12 км · 18 мин      │    ╲0 D (60)8
 *   ╰─────────────────────╯
 *
 * По центру — круглый тахометр 0–8 тыс. об/мин (красная зона с 6000),
 * внутри крупно скорость и передача, в нижнем разрыве шкалы — знак
 * ограничения. Слева — манёвр при маршруте, иначе бортовой компьютер.
 * Справа — топливо и температура двигателя (их угловые дуги тоже ушли).
 */
class ModernCluster(private val view: ClusterGuidanceView) {

    private val white = Color.rgb(0xF2, 0xF4, 0xF7)
    private val grey = Color.rgb(0x8A, 0x94, 0xA0)
    private val dim = Color.rgb(0x2A, 0x31, 0x3C)
    private val accent = Color.rgb(0xFF, 0x7A, 0x1A)
    private val red = Color.rgb(0xE5, 0x39, 0x35)
    private val amber = Color.rgb(0xFF, 0xB3, 0x00)
    private val green = Color.rgb(0x66, 0xBB, 0x6A)
    private val blue = Color.rgb(0x42, 0xA5, 0xF5)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val round = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val bold = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val oval = RectF()

    private var discShader: RadialGradient? = null
    private var discKey = 0f

    fun draw(canvas: Canvas, g: Guidance, speed: Float?, rpm: Float?, shownSpeed: Int?) {
        canvas.drawColor(Color.BLACK)
        val w = view.width.toFloat()
        val s = view.height / 720f
        val cx = w / 2f
        val cy = DIAL_CY * s
        val r = DIAL_R * s
        drawDial(canvas, cx, cy, r, s, rpm)
        drawCenter(canvas, cx, cy, s, shownSpeed ?: speed?.let { Math.round(it) })
        g.speedLimit?.let { view.drawSign(canvas, it, g.overspeed, cx, cy + SIGN_DY * s, SIGN_R * s) }
        drawLeft(canvas, g, cx - r - PANEL_GAP * s, s)
        drawRight(canvas, cx + r + PANEL_GAP * s, w, s)
    }

    // ── Тахометр ──────────────────────────────────────────────────────

    private fun drawDial(c: Canvas, cx: Float, cy: Float, r: Float, s: Float, rpm: Float?) {
        if (discShader == null || discKey != r) {
            discShader = RadialGradient(
                cx, cy, r, intArrayOf(Color.rgb(0x14, 0x1A, 0x22), Color.rgb(0x06, 0x08, 0x0B)),
                floatArrayOf(0.35f, 1f), Shader.TileMode.CLAMP
            )
            discKey = r
        }
        fill.shader = discShader
        c.drawCircle(cx, cy, r, fill)
        fill.shader = null
        stroke.color = dim
        stroke.strokeWidth = 3f * s
        c.drawCircle(cx, cy, r - 1.5f * s, stroke)

        // Дорожка шкалы и красная зона.
        val ar = r - 26f * s
        oval.set(cx - ar, cy - ar, cx + ar, cy + ar)
        stroke.strokeWidth = 16f * s
        stroke.color = Color.rgb(0x1E, 0x25, 0x2F)
        c.drawArc(oval, START, SWEEP, false, stroke)
        stroke.color = Color.rgb(0x5A, 0x1A, 0x1A)
        val redFrom = RED_RPM / RPM_MAX
        c.drawArc(oval, START + SWEEP * redFrom, SWEEP * (1 - redFrom), false, stroke)

        // Заполнение по оборотам: свечение шире и прозрачнее, поверх — яркая полоса.
        val frac = ((rpm ?: 0f) / RPM_MAX).coerceIn(0f, 1f)
        if (frac > 0.002f) {
            val col = if ((rpm ?: 0f) >= RED_RPM) red else accent
            stroke.color = col
            stroke.alpha = 60
            stroke.strokeWidth = 30f * s
            c.drawArc(oval, START, SWEEP * frac, false, stroke)
            stroke.alpha = 255
            stroke.strokeWidth = 16f * s
            c.drawArc(oval, START, SWEEP * frac, false, stroke)
        }

        // Риски через 500 об/мин, цифры через 1000.
        val outer = r - 44f * s
        for (i in 0..(RPM_MAX / 500f).toInt()) {
            val major = i % 2 == 0
            val a = Math.toRadians((START + SWEEP * i * 500f / RPM_MAX).toDouble())
            val len = (if (major) 22f else 11f) * s
            val inRed = i * 500f >= RED_RPM
            round.color = if (inRed) red else if (major) white else grey
            round.strokeWidth = (if (major) 4f else 2.5f) * s
            val cos = Math.cos(a).toFloat()
            val sin = Math.sin(a).toFloat()
            c.drawLine(cx + cos * outer, cy + sin * outer, cx + cos * (outer - len), cy + sin * (outer - len), round)
            if (major) {
                bold.textSize = 40f * s
                bold.color = if (inRed) red else white
                val tr = outer - len - 34f * s
                val fm = bold.fontMetrics
                c.drawText((i / 2).toString(), cx + cos * tr, cy + sin * tr - (fm.ascent + fm.descent) / 2, bold)
            }
        }

        // Стрелка-отрезок у края шкалы.
        if (rpm != null) {
            val a = Math.toRadians((START + SWEEP * frac).toDouble())
            val cos = Math.cos(a).toFloat()
            val sin = Math.sin(a).toFloat()
            val r0 = r - 92f * s
            val r1 = r - 8f * s
            round.color = accent
            round.alpha = 90
            round.strokeWidth = 14f * s
            c.drawLine(cx + cos * r0, cy + sin * r0, cx + cos * r1, cy + sin * r1, round)
            round.color = white
            round.strokeWidth = 6f * s
            c.drawLine(cx + cos * r0, cy + sin * r0, cx + cos * r1, cy + sin * r1, round)
        }
    }

    /** Внутренний круг: подпись шкалы, скорость, км/ч, передача. */
    private fun drawCenter(c: Canvas, cx: Float, cy: Float, s: Float, speed: Int?) {
        val ir = INNER_R * s
        fill.color = Color.rgb(0x0A, 0x0E, 0x13)
        c.drawCircle(cx, cy, ir, fill)
        stroke.color = accent
        stroke.alpha = 70
        stroke.strokeWidth = 2f * s
        c.drawCircle(cx, cy, ir, stroke)
        stroke.alpha = 255

        text.color = grey
        text.textSize = 20f * s
        c.drawText("× 1000 об/мин", cx, cy - 76f * s, text)

        bold.color = white
        bold.textSize = 112f * s
        c.drawText(speed?.toString() ?: "--", cx, cy + 36f * s, bold)
        text.textSize = 28f * s
        c.drawText("км/ч", cx, cy + 72f * s, text)

        val gi = gearIndex(CarHub.data?.gearRaw)
        if (gi >= 0) {
            val label = GEARS[gi]
            bold.textSize = 38f * s
            bold.color = when (label) {
                "R" -> amber
                "N" -> blue
                "P" -> green
                else -> accent
            }
            c.drawText(if (gi >= 3) "D$label" else label, cx, cy + 112f * s, bold)
        }
    }

    // ── Левая панель: манёвр или бортовой компьютер ───────────────────

    private fun drawLeft(c: Canvas, g: Guidance, right: Float, s: Float) {
        val left = PANEL_EDGE * s
        val top = PANEL_TOP * s
        val bottom = PANEL_BOTTOM * s
        if (right - left < 300f * s) return
        panel(c, left, top, right, bottom, s)
        val pad = 28f * s
        val x = left + pad
        val inner = right - pad - x
        val headY = top + 50f * s
        text.textAlign = Paint.Align.LEFT
        bold.textAlign = Paint.Align.LEFT
        text.color = white
        text.textSize = 32f * s
        c.drawText(if (g.hasManeuver) "Навигация" else "Поездка", x, headY, text)
        stroke.color = dim
        stroke.strokeWidth = 2f * s
        c.drawLine(x, headY + 20f * s, right - pad, headY + 20f * s, stroke)

        if (g.hasManeuver) drawRoute(c, g, x, headY + 44f * s, inner, bottom - pad, s)
        else drawTrip(c, g, x, headY + 44f * s, inner, bottom - pad, s)
        text.textAlign = Paint.Align.CENTER
        bold.textAlign = Paint.Align.CENTER
    }

    private fun drawRoute(c: Canvas, g: Guidance, x: Float, top: Float, width: Float, bottom: Float, s: Float) {
        val tile = 140f * s
        var tx = x
        if (g.maneuver != null || g.maneuverIcon != null) {
            oval.set(x, top, x + tile, top + tile)
            fill.color = Color.rgb(0x26, 0x30, 0x40)
            c.drawRoundRect(oval, 24f * s, 24f * s, fill)
            val inset = 22f * s
            view.drawManeuver(c, g, x + inset, top + inset, tile - 2 * inset)
            tx = x + tile + 28f * s
        }
        val tw = x + width - tx
        bold.color = white
        bold.textSize = 76f * s
        g.distance?.let { c.drawText(view.fit(it, bold, tw), tx, top + 76f * s, bold) }
        view.progress.update(g.street.orEmpty(), GuidanceText.meters(g.distance))?.let { frac ->
            val by = top + 104f * s
            val bh = 10f * s
            oval.set(tx, by, tx + tw, by + bh)
            fill.color = Color.rgb(0x2E, 0x35, 0x40)
            c.drawRoundRect(oval, bh / 2, bh / 2, fill)
            oval.set(tx, by, tx + tw * frac.coerceIn(0.04f, 1f), by + bh)
            fill.color = white
            c.drawRoundRect(oval, bh / 2, bh / 2, fill)
        }
        var y = top + tile + 58f * s
        text.color = white
        text.textSize = 40f * s
        g.street?.let {
            c.drawText(view.fit(it, text, width), x, y, text)
            y += 54f * s
        }
        g.cameraDistance?.let { d ->
            text.color = amber
            text.textSize = 32f * s
            val cam = listOfNotNull("камера", g.cameraLimit?.toString(), d).joinToString(" · ")
            c.drawText(view.fit(cam, text, width), x, y, text)
        }
        val eta = listOfNotNull(g.remainingDistance, g.remainingTime, g.arrival?.let { "прибытие $it" })
            .joinToString("  ·  ")
        if (eta.isNotEmpty()) {
            text.color = grey
            text.textSize = 32f * s
            c.drawText(view.fit(eta, text, width), x, bottom, text)
        }
    }

    private fun drawTrip(c: Canvas, g: Guidance, x: Float, top: Float, width: Float, bottom: Float, s: Float) {
        val t = ClusterPps.trip()
        val avg = CarHub.tripView?.avgCons ?: t?.avgFuelL100
        val range = t?.rangeKm ?: CarHub.data?.rangeKm
        val rows = listOf(
            "Расход" to (avg?.let { "%.1f л/100".format(RU, it) } ?: "—"),
            "Пробег" to (t?.tripKm?.let { "%.1f км".format(RU, it) } ?: "—"),
            "Ср. скорость" to (t?.avgSpeedKmh?.let { "${Math.round(it)} км/ч" } ?: "—"),
            "Запас хода" to (range?.let { "$it км" } ?: "—"),
        )
        var y = top + 40f * s
        val valueRight = x + width
        for ((label, value) in rows) {
            text.color = grey
            text.textSize = 32f * s
            c.drawText(label, x, y, text)
            bold.color = white
            bold.textSize = 36f * s
            bold.textAlign = Paint.Align.RIGHT
            c.drawText(value, valueRight, y, bold)
            bold.textAlign = Paint.Align.LEFT
            y += 56f * s
        }
        // Без маршрута — предупреждения навигатора и текущая улица внизу.
        val cam = g.cameraDistance?.let { d -> listOfNotNull("камера", g.cameraLimit?.toString(), d).joinToString(" · ") }
        val line = cam ?: g.currentStreet
        if (line != null && y < bottom) {
            text.color = if (cam != null) amber else white
            text.textSize = 34f * s
            c.drawText(view.fit(line, text, width), x, bottom, text)
        }
    }

    // ── Правая панель: топливо и двигатель ────────────────────────────

    private fun drawRight(c: Canvas, left: Float, w: Float, s: Float) {
        val right = w - PANEL_EDGE * s
        if (right - left < 300f * s) return
        val d = CarHub.data
        val gr = GAUGE_R * s
        val gy = GAUGE_CY * s
        val step = (right - left) / 2f
        val fuelPct = d?.fuelPct
        gauge(
            c, left + step / 2f, gy, gr, s, "Топливо",
            fuelPct?.let { it / 100f },
            d?.fuelLiters?.let { "${Math.round(it)} л" } ?: fuelPct?.let { "${Math.round(it)}%" } ?: "—",
            if (d?.fuelLiters != null) fuelPct?.let { "${Math.round(it)} %" } else null,
            fuelPct?.let { fuelLevelColor(it, green) } ?: grey
        )
        val exact = d?.engineTempC
        val eng = exact ?: ClusterPps.needle()?.let { CoolantCalibration.estimate(it) }
        gauge(
            c, left + step * 1.5f, gy, gr, s, "Двигатель",
            eng?.let { ((it - TEMP_MIN) / (TEMP_MAX - TEMP_MIN)).coerceIn(0f, 1f) },
            eng?.let { (if (exact == null) "≈" else "") + "${Math.round(it)}°" } ?: "—",
            null,
            eng?.let { engineTempColor(it, green) } ?: grey
        )
        d?.voltageV?.let {
            text.color = grey
            text.textSize = 30f * s
            c.drawText("АКБ " + "%.1f В".format(RU, it), left + step, gy + gr + 74f * s, text)
        }
    }

    /** Круглый указатель: дуга 270° с заполнением [frac], значение в центре. */
    private fun gauge(
        c: Canvas, cx: Float, cy: Float, r: Float, s: Float,
        title: String, frac: Float?, value: String, sub: String?, color: Int
    ) {
        text.color = white
        text.textSize = 32f * s
        c.drawText(title, cx, cy - r - 30f * s, text)
        fill.color = Color.rgb(0x0C, 0x10, 0x15)
        c.drawCircle(cx, cy, r, fill)
        oval.set(cx - r + 10f * s, cy - r + 10f * s, cx + r - 10f * s, cy + r - 10f * s)
        round.strokeWidth = 12f * s
        round.color = Color.rgb(0x1E, 0x25, 0x2F)
        c.drawArc(oval, START, SWEEP, false, round)
        if (frac != null && frac > 0.01f) {
            round.color = color
            c.drawArc(oval, START, SWEEP * frac, false, round)
        }
        bold.color = white
        bold.textSize = 48f * s
        c.drawText(value, cx, cy + 16f * s, bold)
        if (sub != null) {
            text.color = grey
            text.textSize = 26f * s
            c.drawText(sub, cx, cy + 56f * s, text)
        }
    }

    private fun panel(c: Canvas, l: Float, t: Float, r: Float, b: Float, s: Float) {
        oval.set(l, t, r, b)
        fill.color = Color.rgb(0x0D, 0x11, 0x17)
        c.drawRoundRect(oval, 26f * s, 26f * s, fill)
        stroke.color = dim
        stroke.strokeWidth = 2f * s
        c.drawRoundRect(oval, 26f * s, 26f * s, stroke)
    }

    companion object {
        /** Шкала: от 135° (низ слева) по часовой на 270°. */
        const val START = 135f
        const val SWEEP = 270f
        const val RPM_MAX = 8000f
        const val RED_RPM = 6000f
        const val SPEED_MAX = 220f
        /** Центр и радиус тахометра: между верхней (до 90) и нижней (с 620) строками. */
        const val DIAL_CY = 352f
        const val DIAL_R = 258f
        const val INNER_R = 128f
        const val SIGN_DY = 206f
        const val SIGN_R = 40f
        const val PANEL_GAP = 40f
        const val PANEL_EDGE = 110f
        const val PANEL_TOP = 130f
        const val PANEL_BOTTOM = 590f
        const val GAUGE_R = 110f
        const val GAUGE_CY = 360f
        const val TEMP_MIN = 40f
        const val TEMP_MAX = 130f
        private val GEARS = listOf("R", "N", "P", "1", "2", "3", "4", "5", "6", "7")
        private val RU = Locale("ru")
    }
}
