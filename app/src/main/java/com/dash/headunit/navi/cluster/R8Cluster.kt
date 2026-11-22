package com.dash.headunit.navi.cluster

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import com.dash.headunit.car.CarHub
import com.dash.headunit.car.ClusterPps
import com.dash.headunit.car.CoolantCalibration
import com.dash.headunit.widgets.engineTempColor
import com.dash.headunit.widgets.fuelLevelColor
import com.dash.headunit.widgets.gearIndex

/**
 * Тема приборки «Audi R8» (дисплей 1, 1920×720, страница «Карты»), современный
 * вид: стекло и градиенты, шкала — разомкнутая снизу дуга.
 *
 * Штатные скорость, обороты и угловые дуги спрятаны ([ClusterGauges]);
 * верхняя (до y ≈ 90) и нижняя (с y ≈ 620) строки приборки остаются.
 *
 *  - слева — стеклянная карточка маршрута: стрелка манёвра, дистанция,
 *    улица, полоса прогресса, остаток/прибытие; без маршрута — улица
 *    и предупреждения; внизу запас хода и температура за бортом;
 *  - по центру — открытая дуга 240° с разрывом снизу: тонкий трек,
 *    градиентная заливка до текущих оборотов, сегментные LED переключения,
 *    цифры без наклона, светящаяся точка-стрелка на дуге; внутри —
 *    крупная цифровая скорость, передача и режим;
 *  - справа — два стеклянных индикатора-кольца: ОЖ и топливо;
 *  - знак ограничения — справа от дуги в стеклянном круге.
 */
class R8Cluster(private val view: ClusterGuidanceView) {

    private val white = Color.rgb(0xF2, 0xF5, 0xF8)
    private val grey = Color.rgb(0x8B, 0x93, 0x9C)
    private val red = Color.rgb(0xFF, 0x1E, 0x2D)
    private val redDim = Color.rgb(0x8E, 0x0A, 0x0A)
    private val green = Color.rgb(0x3D, 0xDC, 0x84)
    private val amber = Color.rgb(0xFF, 0xB0, 0x20)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val speedText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val bigText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val rowText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
        textAlign = Paint.Align.LEFT
    }
    private val oval = RectF()

    /** Своя полоса прогресса манёвра (как у карточки маршрута). */
    private val progress = ManeuverProgress()

    private var shaderKey = 0f
    private var bgShader: Shader? = null
    private var panelShader: Shader? = null

    fun draw(canvas: Canvas, g: Guidance, speed: Float?, rpm: Float?, shownSpeed: Int?) {
        val s = view.height / 720f
        ensureShaders(s)
        canvas.drawColor(Color.BLACK)
        bgShader?.let {
            fill.shader = it
            canvas.drawRect(0f, 0f, view.width.toFloat(), view.height.toFloat(), fill)
            fill.shader = null
        }

        val w = view.width.toFloat()
        drawPanel(canvas, g, s)
        drawGauges(canvas, s, w)

        val cx = w / 2f + CX_SHIFT * s
        val cy = DIAL_CY * s
        val r = DIAL_R * s
        drawArc(canvas, cx, cy, r, s, rpm)
        drawCenter(canvas, cx, cy, r, s, shownSpeed ?: speed?.let { Math.round(it) })

        g.speedLimit?.let {
            val sx = cx + r + SIGN_GAP * s + SIGN_R * s
            fill.color = Color.argb(0x14, 255, 255, 255)
            canvas.drawCircle(sx, cy, SIGN_R * s, fill)
            stroke.color = Color.argb(0x2E, 255, 255, 255)
            stroke.strokeWidth = 1.5f * s
            canvas.drawCircle(sx, cy, SIGN_R * s, stroke)
            view.drawSign(canvas, it, g.overspeed, sx, cy, SIGN_R * 0.82f * s)
        }
    }

    /** Фон и стекло — шейдеры только при смене размера. */
    private fun ensureShaders(s: Float) {
        if (s == shaderKey) return
        shaderKey = s
        val w = view.width.toFloat()
        val h = view.height.toFloat()
        bgShader = RadialGradient(
            w / 2f, h * 0.55f, w * 0.62f,
            intArrayOf(Color.rgb(0x0C, 0x10, 0x16), Color.rgb(0x05, 0x06, 0x09), Color.rgb(0x02, 0x02, 0x03)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        panelShader = LinearGradient(
            0f, PANEL_T * s, 0f, PANEL_B * s,
            intArrayOf(
                Color.argb(0x1E, 255, 255, 255),
                Color.argb(0x08, 255, 255, 255),
                Color.argb(0x12, 255, 255, 255)
            ),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
    }

    // ── Слева: стеклянная карточка маршрута ───────────────────────────

    private fun drawPanel(c: Canvas, g: Guidance, s: Float) {
        val l = PANEL_L * s
        val t = PANEL_T * s
        val rr = PANEL_R * s
        val b = PANEL_B * s
        fill.shader = panelShader
        c.drawRoundRect(l, t, rr, b, 26f * s, 26f * s, fill)
        fill.shader = null
        stroke.color = Color.argb(0x2E, 255, 255, 255)
        stroke.strokeWidth = 1.5f * s
        c.drawRoundRect(l, t, rr, b, 26f * s, 26f * s, stroke)

        // Красный акцент и заголовок.
        fill.color = red
        c.drawRoundRect(l + 28f * s, t + 34f * s, l + 32f * s, t + 66f * s, 2f * s, 2f * s, fill)
        rowText.color = grey
        rowText.textSize = 22f * s
        c.drawText("МАРШРУТ", l + 48f * s, t + 58f * s, rowText)

        val pad = 28f * s
        val innerW = rr - l - 2 * pad
        var y = t + 128f * s

        if (g.hasManeuver) {
            // Плитка со стрелкой манёвра.
            var tx = l + pad
            if (g.maneuver != null || g.maneuverIcon != null) {
                val tile = 104f * s
                fill.color = Color.argb(0x12, 255, 255, 255)
                c.drawRoundRect(tx, y - 62f * s, tx + tile, y + 42f * s, 20f * s, 20f * s, fill)
                stroke.color = Color.argb(0x22, 255, 255, 255)
                stroke.strokeWidth = 1.5f * s
                c.drawRoundRect(tx, y - 62f * s, tx + tile, y + 42f * s, 20f * s, 20f * s, stroke)
                view.drawManeuver(c, g, tx + 20f * s, y - 42f * s, tile - 40f * s)
                tx += tile + 22f * s
            }
            bigText.color = white
            bigText.textSize = 64f * s
            bigText.textAlign = Paint.Align.LEFT
            g.distance?.let { c.drawText(fit(it, bigText, rr - pad - tx), tx, y, bigText) }
            bigText.textAlign = Paint.Align.CENTER

            rowText.color = grey
            rowText.textSize = 30f * s
            g.street?.let { c.drawText(fit(it, rowText, innerW), l + pad, y + 40f * s, rowText) }

            val frac = progress.update(g.street.orEmpty(), GuidanceText.meters(g.distance))
            if (frac != null) {
                val by = y + 62f * s
                fill.color = Color.argb(0x1E, 255, 255, 255)
                c.drawRoundRect(l + pad, by, l + pad + innerW, by + 8f * s, 4f * s, 4f * s, fill)
                fill.color = white
                val fw = innerW * frac.coerceIn(0.04f, 1f)
                c.drawRoundRect(l + pad, by, l + pad + fw, by + 8f * s, 4f * s, 4f * s, fill)
            }
            y += 150f * s

            rowText.textSize = 32f * s
            val eta = listOfNotNull(g.remainingDistance, g.remainingTime).joinToString("  ·  ")
            if (eta.isNotEmpty()) {
                rowText.color = white
                c.drawText(fit(eta, rowText, innerW), l + pad, y, rowText)
                y += 42f * s
            }
            g.arrival?.let {
                rowText.color = grey
                c.drawText(fit("прибытие $it", rowText, innerW), l + pad, y, rowText)
                y += 42f * s
            }
            g.cameraDistance?.let { d ->
                val cam = listOfNotNull("камера", g.cameraLimit?.toString(), d).joinToString(" · ")
                rowText.color = amber
                c.drawText(fit(cam, rowText, innerW), l + pad, y, rowText)
            }
        } else {
            // Без маршрута — текущая улица и предупреждения.
            g.currentStreet?.let {
                bigText.color = white
                bigText.textSize = 48f * s
                bigText.textAlign = Paint.Align.LEFT
                c.drawText(fit(it, bigText, innerW), l + pad, y, bigText)
                bigText.textAlign = Paint.Align.CENTER
                y += 62f * s
            }
            g.cameraDistance?.let { d ->
                val cam = listOfNotNull("камера", g.cameraLimit?.toString(), d).joinToString(" · ")
                rowText.color = amber
                rowText.textSize = 30f * s
                c.drawText(fit(cam, rowText, innerW), l + pad, y, rowText)
                y += 42f * s
            }
            if (g.speedLimit == null && g.currentStreet == null && g.cameraDistance == null) {
                rowText.color = grey
                rowText.textSize = 30f * s
                c.drawText("маршрут не задан", l + pad, y, rowText)
            }
        }

        // Нижние строки: запас хода и температура за бортом.
        val d = CarHub.data
        val t2 = ClusterPps.trip()
        val range = t2?.rangeKm ?: d?.rangeKm
        val out = ClusterPps.outsideTempC() ?: d?.outsideTempC
        val rows = listOfNotNull(
            range?.let { "ЗАПАС" to "$it км" },
            out?.let { "ЗА БОРТОМ" to "${Math.round(it)}°C" },
        )
        if (rows.isEmpty()) return
        var ry = b - 52f * s - (rows.size - 1) * 34f * s
        for ((labelText, value) in rows) {
            rowText.textSize = 22f * s
            rowText.color = grey
            val lw = rowText.measureText("$labelText  ")
            c.drawText(labelText, l + pad, ry, rowText)
            rowText.textSize = 30f * s
            rowText.color = white
            c.drawText(value, l + pad + lw, ry, rowText)
            ry += 34f * s
        }
    }

    // ── Справа: стеклянные кольца ОЖ и топливо ────────────────────────

    private fun drawGauges(c: Canvas, s: Float, w: Float) {
        val r = GAUGE_R * s
        val cy = GAUGE_CY * s
        val x2 = w - GAUGE_RIGHT * s - r
        val x1 = x2 - 2f * r - GAUGE_GAP * s

        val d = CarHub.data
        val exact = d?.engineTempC
        val eng = exact ?: ClusterPps.needle()?.let { CoolantCalibration.estimate(it) }
        gauge(
            c, x1, cy, r, s, "ДВИГАТЕЛЬ",
            eng?.let { ((it - TEMP_MIN) / (TEMP_MAX - TEMP_MIN)).coerceIn(0f, 1f) },
            eng?.let { "${Math.round(it)}°" } ?: "--",
            eng?.let { engineTempColor(it, green) } ?: grey
        )

        val pct = d?.fuelPct
        val liters = d?.fuelLiters
        gauge(
            c, x2, cy, r, s, "ТОПЛИВО",
            pct?.let { (it / 100f).coerceIn(0f, 1f) },
            liters?.let { "${Math.round(it)} л" } ?: (pct?.let { "${Math.round(it)}%" } ?: "--"),
            pct?.let { fuelLevelColor(it, green) } ?: grey
        )
    }

    /**
     * Стеклянное кольцо: полупрозрачный круг с обводкой, тонкий трек,
     * градиентная дуга-заполнение [frac], значение по центру, подпись сверху.
     */
    private fun gauge(
        c: Canvas, cx: Float, cy: Float, r: Float, s: Float,
        labelText: String, frac: Float?, value: String, color: Int
    ) {
        fill.color = Color.argb(0x12, 255, 255, 255)
        c.drawCircle(cx, cy, r, fill)
        stroke.color = Color.argb(0x26, 255, 255, 255)
        stroke.strokeWidth = 1.5f * s
        c.drawCircle(cx, cy, r, stroke)

        label.color = grey
        label.textSize = 21f * s
        c.drawText(labelText, cx, cy - r - 16f * s, label)

        val ar = r - 14f * s
        oval.set(cx - ar, cy - ar, cx + ar, cy + ar)
        arc.strokeWidth = 7f * s
        arc.color = Color.rgb(0x22, 0x26, 0x2B)
        c.drawArc(oval, GAUGE_FROM, GAUGE_SWEEP, false, arc)
        if (frac != null && frac > 0.01f) {
            // Свечение и сама дуга.
            arc.color = color
            arc.alpha = 60
            arc.strokeWidth = 14f * s
            c.drawArc(oval, GAUGE_FROM, GAUGE_SWEEP * frac, false, arc)
            arc.alpha = 255
            arc.strokeWidth = 7f * s
            c.drawArc(oval, GAUGE_FROM, GAUGE_SWEEP * frac, false, arc)
        }

        bigText.color = white
        bigText.textSize = 50f * s
        val fm = bigText.fontMetrics
        c.drawText(value, cx, cy - (fm.ascent + fm.descent) / 2 + 3f * s, bigText)
    }

    // ── Центр: открытая дуга тахометра ────────────────────────────────

    private fun drawArc(c: Canvas, cx: Float, cy: Float, r: Float, s: Float, rpm: Float?) {
        // Трек всей шкалы.
        oval.set(cx - r, cy - r, cx + r, cy + r)
        arc.strokeWidth = 7f * s
        arc.color = Color.rgb(0x1E, 0x21, 0x26)
        c.drawArc(oval, START, SWEEP, false, arc)

        // Красная зона — приглушённый сегмент трека.
        arc.color = redDim
        c.drawArc(oval, angle(RED_RPM), angle(RPM_MAX) - angle(RED_RPM), false, arc)

        // Заливка до текущих оборотов: свечение + градиентная дуга.
        val cur = rpm?.coerceIn(0f, RPM_MAX)
        if (cur != null && cur > 0.5f) {
            val color = if (cur >= RED_RPM) red else white
            arc.color = color
            arc.alpha = 50
            arc.strokeWidth = 18f * s
            c.drawArc(oval, START, angle(cur) - START, false, arc)
            arc.alpha = 255
            arc.strokeWidth = 7f * s
            c.drawArc(oval, START, angle(cur) - START, false, arc)
        }

        // Риски: крупные — тысячи, средние — 500, мелкие — 250.
        var v = 0f
        while (v <= RPM_MAX + 1f) {
            val k = when {
                v % 1000f == 0f -> 0
                v % 500f == 0f -> 1
                else -> 2
            }
            val len = floatArrayOf(14f, 9f, 5f)[k] * s
            tick.strokeWidth = floatArrayOf(2.5f, 2f, 1.5f)[k] * s
            tick.color = if (v >= RED_RPM) Color.argb(0xE0, 0xFF, 0x6B, 0x6B)
            else Color.argb(intArrayOf(0xD0, 0x90, 0x60)[k], 0xFF, 0xFF, 0xFF)
            val (cos, sin) = dir(v)
            val r0 = r + 16f * s
            c.drawLine(cx + cos * r0, cy + sin * r0, cx + cos * (r0 + len), cy + sin * (r0 + len), tick)
            v += 250f
        }

        // Цифры без наклона; «0» не пишем — только риска.
        bigText.textSize = r * 0.155f
        bigText.color = white
        val fm = bigText.fontMetrics
        val tr = r - 40f * s
        for (i in 1..(RPM_MAX / 1000f).toInt()) {
            val (cos, sin) = dir(i * 1000f)
            bigText.color = if (i * 1000f >= RED_RPM) red else white
            c.drawText(i.toString(), cx + cos * tr, cy + sin * tr - (fm.ascent + fm.descent) / 2, bigText)
        }

        // Сегментные LED переключения над дугой.
        val ledFrom = SHIFT_RPM
        val step = (RPM_MAX - ledFrom) / LED_COUNT
        val ledR = r + 34f * s
        for (i in 0 until LED_COUNT) {
            val a0 = angle(ledFrom + step * i)
            val a1 = angle(ledFrom + step * (i + 1))
            val on = cur != null && cur >= ledFrom + step * i
            val color = when {
                i >= LED_COUNT * 0.75f -> red
                i >= LED_COUNT * 0.5f -> amber
                else -> green
            }
            oval.set(cx - ledR, cy - ledR, cx + ledR, cy + ledR)
            arc.strokeWidth = 6f * s
            if (on) {
                arc.color = color
                arc.alpha = 60
                arc.strokeWidth = 13f * s
                c.drawArc(oval, a0 + 0.6f, a1 - a0 - 1.2f, false, arc)
                arc.alpha = 255
                arc.strokeWidth = 6f * s
                c.drawArc(oval, a0 + 0.6f, a1 - a0 - 1.2f, false, arc)
            } else {
                arc.color = Color.rgb(0x1C, 0x1F, 0x24)
                c.drawArc(oval, a0 + 0.6f, a1 - a0 - 1.2f, false, arc)
            }
        }

        // Стрелка: светящаяся точка на дуге и короткая красная черта внутрь.
        if (cur != null) {
            val (cos, sin) = dir(cur)
            val px = cx + cos * r
            val py = cy + sin * r
            fill.color = red
            fill.alpha = 55
            c.drawCircle(px, py, 16f * s, fill)
            fill.alpha = 255
            c.drawCircle(px, py, 5.5f * s, fill)
            tick.color = red
            tick.strokeWidth = 3f * s
            c.drawLine(
                cx + cos * (r - 34f * s), cy + sin * (r - 34f * s),
                cx + cos * (r - 6f * s), cy + sin * (r - 6f * s), tick
            )
        }
    }

    /** Скорость, передача и режим внутри дуги. */
    private fun drawCenter(c: Canvas, cx: Float, cy: Float, r: Float, s: Float, speed: Int?) {
        label.color = grey
        label.textSize = r * 0.075f
        c.drawText("км/ч", cx, cy - r * 0.30f, label)

        speedText.color = white
        speedText.textSize = r * 0.46f
        val baseline = cy + r * 0.10f + speedText.textSize * 0.32f
        c.drawText(speed?.toString() ?: "--", cx, baseline, speedText)

        val gi = gearIndex(CarHub.data?.gearRaw)
        if (gi >= 0) {
            val gear = GEARS[gi]
            val drive = gi >= 3
            label.color = if (gear == "R") amber else white
            label.textSize = r * 0.13f
            c.drawText(if (drive) "D$gear" else gear, cx, cy + r * 0.34f, label)
            label.color = grey
            label.textSize = r * 0.05f
            val mode = when (gear) {
                "P" -> "ПАРКОВКА"
                "R" -> "НАЗАД"
                "N" -> "НЕЙТРАЛЬ"
                else -> "АВТО"
            }
            c.drawText(mode, cx, cy + r * 0.43f, label)
        }

        label.color = grey
        label.textSize = r * 0.05f
        c.drawText("×1000 об/мин", cx, cy + r * 0.60f, label)
    }

    /** Угол (градусы canvas: 0 — вправо, по часовой) значения [rpm]. */
    private fun angle(rpm: Float): Float = START + SWEEP * rpm / RPM_MAX

    private fun dir(rpm: Float): Pair<Float, Float> {
        val a = Math.toRadians(angle(rpm).toDouble())
        return Math.cos(a).toFloat() to Math.sin(a).toFloat()
    }

    private fun fit(text: String, paint: TextPaint, width: Float): String =
        if (width <= 0) "" else TextUtils.ellipsize(text, paint, width, TextUtils.TruncateAt.END).toString()

    companion object {
        /**
         * Открытая дуга: 0 слева внизу (150°), по часовой через верх до 8
         * справа внизу (390° = 30°); снизу разрыв 120° под скорость/передачу.
         */
        const val START = 150f
        const val SWEEP = 240f
        const val RPM_MAX = 8000f
        const val RED_RPM = 6000f
        /** С этих оборотов загораются LED переключения. */
        const val SHIFT_RPM = 4500f
        const val LED_COUNT = 12
        /** Центр дуги: между карточкой слева и индикаторами справа. */
        const val CX_SHIFT = 55f
        const val DIAL_CY = 390f
        const val DIAL_R = 258f
        /** Знак ограничения — справа от дуги. */
        const val SIGN_GAP = 28f
        const val SIGN_R = 46f
        /** Стеклянная карточка маршрута слева, px дисплея 1920×720. */
        const val PANEL_L = 40f
        const val PANEL_T = 110f
        const val PANEL_R = 600f
        const val PANEL_B = 610f
        /** Два стеклянных кольца справа. */
        const val GAUGE_R = 88f
        const val GAUGE_CY = 320f
        const val GAUGE_GAP = 52f
        const val GAUGE_RIGHT = 36f
        const val GAUGE_FROM = 135f
        const val GAUGE_SWEEP = 270f
        const val TEMP_MIN = 40f
        const val TEMP_MAX = 130f
        private val GEARS = listOf("R", "N", "P", "1", "2", "3", "4", "5", "6", "7")
    }
}
