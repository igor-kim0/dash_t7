package com.dash.headunit.navi.cluster

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import androidx.core.content.ContextCompat
import com.dash.headunit.R
import com.dash.headunit.car.CarHub
import com.dash.headunit.car.ClusterPps
import com.dash.headunit.car.CoolantCalibration
import com.dash.headunit.car.DriveMode
import com.dash.headunit.car.TripTracker
import com.dash.headunit.widgets.gearIndex
import java.util.Locale

/**
 * Тема приборки «Тест» (дисплей 1, 1920×720, страница «Карты»; раньше этот
 * же класс без [full] рисовал «Современную», её заменила «Ретро»).
 * Штатный слой приборки спрятан целиком ([ClusterGauges] режим «всё»).
 *
 * По центру — большой круг: металлический обод (снизу между 0 и 8 его нет —
 * там одометр), сине-серый циферблат, обороты 0–8 тыс. (красная зона с 6000),
 * стрелка; внутри скорость, передача, запас хода. Слева вдоль обода —
 * температура двигателя, топливо и за бортом; дальше влево — список поездки
 * (расход, пробег, средняя скорость, моточасы). Сверху слева — дата и часы.
 * Справа — карточка навигации, знак ограничения в её левом верхнем углу.
 * Штатные лампы QNX (indicator.kzb) не прячутся и стоят на фиксированных
 * местах — под них нарисованы «гнёзда» ([drawLampSockets]): круиз, режим
 * движения, AUTO HOLD/стояночный, Старт-стоп. Режим (a00d) ещё и красит
 * кромку циферблата и плашку передачи ([modeColor]).
 * Ветки `!full` остались от «Современной» и сейчас не используются.
 */
class ModernCluster(private val view: ClusterGuidanceView, private val full: Boolean = false) {

    private val dialCy = if (full) FULL_DIAL_CY else DIAL_CY
    private val dialR = if (full) FULL_DIAL_R else DIAL_R
    private val clock = java.text.SimpleDateFormat("HH:mm", RU)
    private val date = java.text.SimpleDateFormat("EE, d MMMM", RU)

    private val white = Color.rgb(0xF2, 0xF4, 0xF7)
    private val grey = Color.rgb(0x9C, 0xA8, 0xB6)
    private val needle = Color.rgb(0xFF, 0x3B, 0x1F)
    private val red = Color.rgb(0xE5, 0x39, 0x35)
    private val amber = Color.rgb(0xFF, 0xB3, 0x00)
    private val blue = Color.rgb(0x64, 0xB5, 0xF6)
    private val green = Color.rgb(0x66, 0xBB, 0x6A)
    private val socketFill = Color.rgb(0x0B, 0x0F, 0x14)
    private val socketEdge = Color.rgb(0x2A, 0x32, 0x3C)

    /** Режим движения на этот кадр (null — неизвестен). */
    private var mode: DriveMode? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val round = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val bold = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val oval = RectF()

    private var shaderKey = 0f
    private var faceShader: Shader? = null
    private var bezelShader: Shader? = null
    private var sideShader: Shader? = null

    private val thermo: Drawable? = ContextCompat.getDrawable(view.context, R.drawable.ic_thermo)?.mutate()
    private val fuel: Drawable? = ContextCompat.getDrawable(view.context, R.drawable.ic_fuel)?.mutate()

    fun draw(canvas: Canvas, g: Guidance, speed: Float?, rpm: Float?, shownSpeed: Int?) {
        canvas.drawColor(Color.BLACK)
        val s = view.height / 720f
        val cx = view.width / 2f
        mode = if (full) ClusterPps.driveMode() else null
        val cy = dialCy * s
        val r = dialR * s
        if (shaderKey != cx * 10_000 + r) makeShaders(cx, cy, r)
        drawSide(canvas, cx, cy, r, s)
        drawFace(canvas, cx, cy, r, s)
        if (full) {
            // «Тест»: стрелка проходит под цифрами и рисками шкалы.
            drawNeedle(canvas, cx, cy, r, s, rpm)
            drawScale(canvas, cx, cy, s)
        } else {
            drawScale(canvas, cx, cy, s)
            drawNeedle(canvas, cx, cy, r, s, rpm)
        }
        drawCenter(canvas, cx, cy, s, shownSpeed ?: speed?.let { Math.round(it) })
        drawInfo(canvas, cx, cy, r, s)
        if (full) {
            drawOdometer(canvas, cx, cy, r, s)
            drawTopBar(canvas, s, cx, cy, r)
            drawTripCard(canvas, cx, r, s)
            // Знак ограничения — внутри карточки, поэтому она от самого круга.
            drawRoute(canvas, g, cx + r + CARD_GAP * s, s)
            // Гнёзда — после карточки: Старт-стоп лежит на ней.
            drawLampSockets(canvas, s)
        } else {
            g.speedLimit?.let {
                view.drawSign(canvas, it, g.overspeed, cx + r + SIGN_GAP * s + SIGN_R * s, cy, SIGN_R * s)
            }
        }
    }

    // ── «Тест»: весь экран ───────────────────────────────────────────

    /**
     * Сверху слева: дата, за ней крупные часы — строка прижата правым краем
     * к границе тёмного градиента за левыми дугами (улица — в карточке навигации).
     */
    private fun drawTopBar(c: Canvas, s: Float, cx: Float, cy: Float, r: Float) {
        val now = java.util.Date()
        val y = BAR_TOP_Y * s
        val day = date.format(now)
        val time = clock.format(now)
        text.textSize = 32f * s
        bold.textSize = 60f * s
        val dw = text.measureText(day)
        val gap = 24f * s
        val width = dw + gap + bold.measureText(time)
        // Левая граница круга градиента ([drawSide]) на середине высоты часов.
        val gr = r * (1 + SIDE_OUT)
        val dy = cy - (y - 22f * s)
        val edge = if (dy < gr) cx - r * GLOW_SHIFT - Math.sqrt((gr * gr - dy * dy).toDouble()).toFloat() else view.width.toFloat()
        val left = (edge - TOP_BAR_GAP * s - width).coerceAtLeast(EDGE * s)
        text.color = grey
        text.textAlign = Paint.Align.LEFT
        c.drawText(day, left, y, text)
        text.textAlign = Paint.Align.CENTER
        bold.color = white
        bold.textAlign = Paint.Align.LEFT
        c.drawText(time, left + dw + gap, y, bold)
        bold.textAlign = Paint.Align.CENTER
    }

    /**
     * Слева от круга — поездка списком без рамок: средний расход, пробег,
     * средняя скорость, моточасы. Подписи выровнены по правому краю, значения
     * с единицей — по левому, у общей границы. Справа список кончается до подписей левых дуг.
     */
    private fun drawTripCard(c: Canvas, cx: Float, r: Float, s: Float) {
        val left = EDGE * s
        val right = cx - r - TRIP_CARD_GAP * s
        if (right - left < 240f * s) return
        val t = ClusterPps.trip()
        val v = CarHub.tripView
        val hours = TripTracker.engineHours(view.context).totalMillis.coerceAtLeast(0L) / 3_600_000.0
        val rows = listOf(
            Triple("Мгновенный расход", ClusterPps.instFuelL100()?.let { "%.1f".format(RU, it.coerceAtMost(INST_MAX)) }, "л"),
            Triple("Средний расход", (v?.avgCons ?: t?.avgFuelL100)?.let { "%.1f".format(RU, it) }, "л"),
            Triple("Пробег поездки", (t?.tripKm ?: v?.distKm)?.let { "%.1f".format(RU, it) }, "км"),
            Triple("Средняя скорость", (t?.avgSpeedKmh ?: v?.avgSpeedKmh)?.let { "%.0f".format(RU, it) }, "км/ч"),
            Triple("Моточасы", "%.1f".format(RU, hours), "ч"),
        )
        // Колонка значений — по самому широкому образцу, чтобы граница не
        // прыгала со сменой цифр; подписи — вправо к ней, значения — влево от неё.
        bold.textSize = 40f * s
        text.textSize = 24f * s
        val unitGap = 8f * s
        val valueW = bold.measureText("000,0") + unitGap + text.measureText("км/ч")
        val split = right - valueW
        val labelRight = split - TRIP_SPLIT_GAP * s
        // Строки — под гнездом круиза, над нижним рядом ламп.
        var y = TRIP_FIRST_Y * s
        for ((label, value, unit) in rows) {
            val base = y + 12f * s
            text.color = grey
            text.textSize = 28f * s
            text.textAlign = Paint.Align.RIGHT
            c.drawText(label, labelRight, base, text)
            bold.color = white
            bold.textSize = 40f * s
            bold.textAlign = Paint.Align.LEFT
            val shown = value ?: "--"
            c.drawText(shown, split, base, bold)
            text.textSize = 24f * s
            text.textAlign = Paint.Align.LEFT
            c.drawText(unit, split + bold.measureText(shown) + unitGap, base, text)
            y += TRIP_STEP * s
        }
        text.textAlign = Paint.Align.CENTER
        bold.textAlign = Paint.Align.CENTER
    }

    /** Внизу круга, на месте убранного обода между 0 и 8: одометр. */
    private fun drawOdometer(c: Canvas, cx: Float, cy: Float, r: Float, s: Float) {
        val odo = ClusterPps.trip()?.odometerKm ?: CarHub.data?.odoKm?.toLong()
        // Жирным и без «км» (просьба пользователя 2026-11-24).
        bold.color = white
        bold.textSize = ODO_SIZE * s
        bold.textAlign = Paint.Align.CENTER
        val fm = bold.fontMetrics
        // По середине полосы обода.
        val y = cy + r - BEZEL * s / 2 - (fm.ascent + fm.descent) / 2
        c.drawText(odo?.let { "%,d".format(RU, it) } ?: "--", cx, y, bold)
    }

    /**
     * «ПОДПИСЬ  значение» от [x] вправо ([left]) или влево до [x];
     * возвращает дальний край.
     */
    private fun pair(c: Canvas, label: String, value: String, x: Float, y: Float, s: Float, left: Boolean): Float {
        text.textSize = 20f * s
        val lw = text.measureText(label)
        text.textSize = 30f * s
        val vw = text.measureText(value)
        val gap = 12f * s
        val x0 = if (left) x else x - lw - gap - vw
        text.textAlign = Paint.Align.LEFT
        text.color = grey
        text.textSize = 20f * s
        c.drawText(label, x0, y, text)
        text.color = white
        text.textSize = 30f * s
        c.drawText(value, x0 + lw + gap, y, text)
        text.textAlign = Paint.Align.CENTER
        return if (left) x0 + lw + gap + vw else x0
    }

    /**
     * Справа — карточка навигации, видна всегда: на маршруте манёвр,
     * расстояние, улица, остаток пути и прибытие; без маршрута — «маршрут
     * не задан» и улица, по которой едем. Камера — в обоих случаях.
     */
    private fun drawRoute(c: Canvas, g: Guidance, cardLeft: Float, s: Float) {
        val cardRight = view.width - EDGE_RIGHT * s
        if (cardRight - cardLeft < 240f * s) return
        oval.set(cardLeft, CARD_TOP * s, cardRight, (if (full) CARD_BOTTOM_LAMPS else CARD_BOTTOM) * s)
        fill.color = Color.rgb(0x10, 0x15, 0x1C)
        c.drawRoundRect(oval, 18f * s, 18f * s, fill)
        stroke.color = Color.rgb(0x2A, 0x32, 0x3C)
        stroke.strokeWidth = 2f * s
        c.drawRoundRect(oval, 18f * s, 18f * s, stroke)

        val left = cardLeft + CARD_PAD * s
        val right = cardRight - CARD_PAD * s
        val w = right - left
        text.textAlign = Paint.Align.LEFT
        bold.textAlign = Paint.Align.LEFT
        var y = (CARD_TOP + CARD_PAD) * s
        // Знак ограничения на текущем участке — в левом верхнем углу карточки,
        // манёвр и надписи шапки — правее него.
        val signTop = y
        val signW = g.speedLimit?.let {
            val sr = SIGN_R * s
            view.drawSign(c, it, g.overspeed, left + sr, y + sr, sr)
            sr * 2 + 20f * s
        } ?: 0f
        val signBottom = if (signW > 0f) signTop + SIGN_R * 2 * s + 40f * s else 0f
        val hl = left + signW
        // Шапка кончается до гнезда Старт-стоп в правом верхнем углу.
        val hw = (lx(SS_X - 16f) - hl).coerceAtMost(w - signW)
        if (g.hasManeuver) {
            val icon = 130f * s
            view.drawManeuver(c, g, hl, y, icon)
            g.distance?.let {
                bold.color = white
                bold.textSize = 60f * s
                c.drawText(view.fit(it, bold, hw - icon - 20f * s), hl + icon + 20f * s, y + icon * 0.62f, bold)
            }
            y += icon + 50f * s
        } else if (!g.onRoute) {
            y += 40f * s
            text.color = grey
            text.textSize = 30f * s
            c.drawText(view.fit("Маршрут не задан", text, hw), hl, y, text)
            y += 60f * s
            g.currentStreet?.let {
                text.color = white
                text.textSize = 32f * s
                c.drawText(view.fit(it, text, hw), hl, y, text)
                y += 30f * s
            }
        }
        // Дальше строки во всю ширину — ниже знака.
        if (y < signBottom) y = signBottom
        g.street?.takeIf { g.onRoute }?.let {
            text.color = white
            text.textSize = 32f * s
            c.drawText(view.fit(it, text, w), left, y, text)
            y += 30f * s
        }
        if (g.onRoute) {
            stroke.color = Color.rgb(0x2A, 0x32, 0x3C)
            stroke.strokeWidth = 2f * s
            c.drawLine(left, y, right, y, stroke)
            y += 50f * s
            val rest = listOfNotNull(g.remainingDistance, g.remainingTime).joinToString(" · ")
            if (rest.isNotEmpty()) {
                pair(c, "ОСТАЛОСЬ", rest, left, y, s, left = true)
                y += 46f * s
            }
            g.arrival?.let {
                pair(c, "ПРИБЫТИЕ", it, left, y, s, left = true)
                y += 46f * s
            }
        } else {
            y += 30f * s
        }
        g.cameraDistance?.let {
            text.textSize = 20f * s
            text.color = amber
            text.textAlign = Paint.Align.LEFT
            val lw = text.measureText("КАМЕРА")
            c.drawText("КАМЕРА", left, y, text)
            text.textSize = 30f * s
            val lim = g.cameraLimit?.let { l -> " · $l" } ?: ""
            c.drawText(view.fit("$it$lim", text, w - lw - 12f * s), left + lw + 12f * s, y, text)
        }
        text.textAlign = Paint.Align.CENTER
        bold.textAlign = Paint.Align.CENTER
    }

    /** Цвет режима: ECO — зелёный, SPORT — красный, NORMAL — голубой, внедорожные — янтарь. */
    private fun modeColor(): Int? = when (mode) {
        DriveMode.ECO -> green
        DriveMode.SPORT -> red
        DriveMode.NORMAL -> blue
        DriveMode.SNOW, DriveMode.MUD, DriveMode.SAND -> amber
        null -> null
    }

    /** X штатной лампы (координаты indicator.kzb в 1920×720) на нашем экране. */
    private fun lx(x: Float) = x * view.width / 1920f

    /**
     * Гнёзда под штатные лампы (indicator.kzb, тема 2 «Карты», разбор
     * 2026-11-28): тёмные ниши с рамкой. Горящая лампа QNX ложится внутрь,
     * погасшая — остаётся аккуратная пустая ниша.
     */
    private fun drawLampSockets(c: Canvas, s: Float) {
        // Круиз / ACC: значок, «CRUISE …», скорость и «км/ч» — слот 170×100.
        socket(c, lx(CRUISE_X - 12f), (CRUISE_Y - 12f) * s, lx(CRUISE_X + 182f), (CRUISE_Y + 112f) * s, s, null)
        caption(c, "КРУИЗ", lx(CRUISE_X - 12f), (CRUISE_Y - 22f) * s, s, Paint.Align.LEFT)

        // Режим движения: слово ECO/NORMAL/SPORT по центру слота 143×25.
        val mc = modeColor()
        socket(c, lx(MODE_X - 14f), (MODE_Y - 10f) * s, lx(MODE_X + 157f), (MODE_Y + 35f) * s, s, mc)
        caption(c, "РЕЖИМ", lx(MODE_X - 28f), (MODE_Y + 19f) * s, s, Paint.Align.RIGHT)

        // Внизу слева: стояночный (красный), AUTO HOLD, стояночный (жёлтый).
        socket(c, lx(EPB_X - 10f), (BOTTOM_Y - 8f) * s, lx(EPB_X + 2 * LAMP_STEP + 62f), view.height.toFloat() + 20f * s, s, null)
        for (i in 0..2) ring(c, lx(EPB_X + i * LAMP_STEP + 26f), (BOTTOM_Y + 26f) * s, 28f * s)

        // Старт-стоп — круглое гнездо в правом верхнем углу карточки навигации.
        fill.color = socketFill
        c.drawCircle(lx(SS_X + 26f), (SS_Y + 26f) * s, 34f * s, fill)
        ring(c, lx(SS_X + 26f), (SS_Y + 26f) * s, 34f * s)
    }

    private fun socket(c: Canvas, l: Float, t: Float, r: Float, b: Float, s: Float, edge: Int?) {
        oval.set(l, t, r, b)
        fill.color = socketFill
        c.drawRoundRect(oval, 12f * s, 12f * s, fill)
        stroke.color = edge ?: socketEdge
        stroke.strokeWidth = (if (edge != null) 3f else 2f) * s
        c.drawRoundRect(oval, 12f * s, 12f * s, stroke)
    }

    private fun ring(c: Canvas, x: Float, y: Float, r: Float) {
        stroke.color = socketEdge
        stroke.strokeWidth = 2f * view.height / 720f
        c.drawCircle(x, y, r, stroke)
    }

    private fun caption(c: Canvas, label: String, x: Float, y: Float, s: Float, align: Paint.Align) {
        text.textSize = 18f * s
        text.color = grey
        text.letterSpacing = 0.12f
        text.textAlign = align
        c.drawText(label, x, y, text)
        text.letterSpacing = 0f
        text.textAlign = Paint.Align.CENTER
    }

    private fun makeShaders(cx: Float, cy: Float, r: Float) {
        shaderKey = cx * 10_000 + r
        faceShader = RadialGradient(
            cx, cy - r * 0.15f, r,
            intArrayOf(Color.rgb(0x4A, 0x5D, 0x73), Color.rgb(0x2A, 0x38, 0x48), Color.rgb(0x10, 0x16, 0x1E)),
            floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP
        )
        // Металл: светлые блики справа-снизу и слева-сверху.
        bezelShader = SweepGradient(
            cx, cy,
            intArrayOf(
                Color.rgb(0x5A, 0x60, 0x68), Color.rgb(0xE8, 0xEC, 0xF0), Color.rgb(0x6A, 0x70, 0x78),
                Color.rgb(0x30, 0x34, 0x3A), Color.rgb(0xC8, 0xCE, 0xD4), Color.rgb(0x50, 0x56, 0x5E),
                Color.rgb(0x5A, 0x60, 0x68)
            ),
            floatArrayOf(0f, 0.12f, 0.3f, 0.5f, 0.66f, 0.85f, 1f)
        )
        sideShader = RadialGradient(
            cx - r * GLOW_SHIFT, cy, r * (1 + SIDE_OUT),
            intArrayOf(Color.rgb(0x18, 0x20, 0x2B), Color.rgb(0x0B, 0x0F, 0x15), Color.argb(0, 0, 0, 0)),
            floatArrayOf(0.55f, 0.85f, 1f), Shader.TileMode.CLAMP
        )
    }

    // ── Циферблат ─────────────────────────────────────────────────────

    private fun drawFace(c: Canvas, cx: Float, cy: Float, r: Float, s: Float) {
        if (full) {
            // «Тест»: обод только вдоль шкалы — снизу, между 0 и 8, его нет
            // (там одометр, [drawOdometer]).
            val br = r - BEZEL * s / 2
            oval.set(cx - br, cy - br, cx + br, cy + br)
            stroke.shader = bezelShader
            stroke.strokeWidth = BEZEL * s
            c.drawArc(oval, START, SWEEP, false, stroke)
            stroke.shader = null
        } else {
            fill.shader = bezelShader
            c.drawCircle(cx, cy, r, fill)
            fill.shader = null
        }
        // Узкая кромка между ободом и циферблатом — цветом режима движения.
        fill.color = modeColor()?.let { Color.argb(200, Color.red(it), Color.green(it), Color.blue(it)) }
            ?: Color.rgb(0x08, 0x0B, 0x0F)
        c.drawCircle(cx, cy, r - BEZEL * s, fill)
        fill.shader = faceShader
        c.drawCircle(cx, cy, r - (BEZEL + 4f) * s, fill)
        fill.shader = null
        // Риски на ободе через 1000 об/мин.
        round.color = Color.rgb(0x20, 0x24, 0x2A)
        round.strokeWidth = 4f * s
        val r0 = r - 4f * s
        val r1 = r - (BEZEL - 4f) * s
        for (i in 0..(RPM_MAX / 1000f).toInt()) {
            val (cos, sin) = dir(i * 1000f)
            c.drawLine(cx + cos * r0, cy + sin * r0, cx + cos * r1, cy + sin * r1, round)
        }
    }

    /** Красная зона по внутренней кромке обода, риски и цифры оборотов. */
    private fun drawScale(c: Canvas, cx: Float, cy: Float, s: Float) {
        val face = dialR * s - (BEZEL + 4f) * s
        val zr = face - 7f * s
        oval.set(cx - zr, cy - zr, cx + zr, cy + zr)
        stroke.color = red
        stroke.strokeWidth = 10f * s
        val a0 = START + SWEEP * RED_RPM / RPM_MAX
        c.drawArc(oval, a0, START + SWEEP - a0, false, stroke)

        val outer = face - 16f * s
        var v = 0f
        while (v <= RPM_MAX + 1f) {
            val major = v % 1000f == 0f
            val (cos, sin) = dir(v)
            val len = (if (major) 18f else 9f) * s
            round.color = if (v >= RED_RPM) red else white
            round.strokeWidth = (if (major) 4f else 2f) * s
            c.drawLine(cx + cos * outer, cy + sin * outer, cx + cos * (outer - len), cy + sin * (outer - len), round)
            if (major) {
                bold.textSize = 44f * s
                bold.color = if (v >= RED_RPM) red else white
                val tr = outer - len - 34f * s
                val fm = bold.fontMetrics
                c.drawText((v / 1000f).toInt().toString(), cx + cos * tr, cy + sin * tr - (fm.ascent + fm.descent) / 2, bold)
            }
            v += 250f
        }
        // Подпись шкалы — в нижнем разрыве, между 0 и 8 («Тест» — без неё).
        if (!full) {
            text.color = grey
            text.textSize = 20f * s
            c.drawText("×1000 об/мин", cx, cy + 150f * s, text)
        }
    }

    private fun drawNeedle(c: Canvas, cx: Float, cy: Float, r: Float, s: Float, rpm: Float?) {
        if (rpm == null) return
        val (cos, sin) = dir(rpm.coerceIn(0f, RPM_MAX))
        val r1 = r - (BEZEL + 14f) * s
        // «Тест»: стрелка на треть короче — кончик у обода, начало дальше от центра.
        val r0 = if (full) r1 - (r1 - r * NEEDLE_FROM) * 2f / 3f else r * NEEDLE_FROM
        round.color = needle
        round.alpha = 70
        round.strokeWidth = 22f * s
        c.drawLine(cx + cos * r0, cy + sin * r0, cx + cos * r1, cy + sin * r1, round)
        round.alpha = 255
        round.strokeWidth = 9f * s
        c.drawLine(cx + cos * r0, cy + sin * r0, cx + cos * r1, cy + sin * r1, round)
        round.color = Color.argb(160, 255, 210, 200)
        round.strokeWidth = 2.5f * s
        c.drawLine(cx + cos * r0, cy + sin * r0, cx + cos * r1, cy + sin * r1, round)
    }

    /** Скорость, км/ч, черта, передача и плашка режима. */
    private fun drawCenter(c: Canvas, cx: Float, cy: Float, s: Float, speed: Int?) {
        bold.color = white
        bold.textSize = (if (full) SPEED_SIZE_FULL else 120f) * s
        val top = cy - 40f * s
        // «Тест»: без «км/ч», скорость чуть ниже — ближе к черте.
        c.drawText(speed?.toString() ?: "--", cx, top + if (full) SPEED_DROP * s else 0f, bold)
        if (!full) {
            text.color = white
            text.textSize = 28f * s
            c.drawText("км/ч", cx, top + 34f * s, text)
        }
        stroke.color = Color.argb(150, 255, 255, 255)
        stroke.strokeWidth = 2f * s
        c.drawLine(cx - 90f * s, top + 50f * s, cx + 90f * s, top + 50f * s, stroke)

        val gi = gearIndex(CarHub.data?.gearRaw)
        if (gi < 0) return
        val label = GEARS[gi]
        val drive = gi >= 3
        bold.textSize = 80f * s
        bold.color = if (label == "R") amber else white
        val gy = top + 122f * s
        c.drawText(if (drive) "D" else label, cx, gy, bold)
        if (drive) {
            // Номер передачи мелко справа от D.
            bold.textSize = 36f * s
            bold.textAlign = Paint.Align.LEFT
            c.drawText(label, cx + 32f * s, gy, bold)
            bold.textAlign = Paint.Align.CENTER
        }
        val badge = when (label) {
            "P" -> "ПАРКОВКА"
            "R" -> "НАЗАД"
            "N" -> "НЕЙТРАЛЬ"
            else -> "АВТО"
        }
        text.textSize = 22f * s
        val bw = text.measureText(badge) + 22f * s
        val by = top + 136f * s
        oval.set(cx - bw / 2, by, cx + bw / 2, by + 30f * s)
        fill.color = if (drive) modeColor() ?: white else white
        c.drawRoundRect(oval, 6f * s, 6f * s, fill)
        text.color = Color.rgb(0x1A, 0x22, 0x2C)
        c.drawText(badge, cx, by + 23f * s, text)
    }

    /** Справа внизу циферблата: запас хода и средний расход. */
    private fun drawInfo(c: Canvas, cx: Float, cy: Float, r: Float, s: Float) {
        val t = ClusterPps.trip()
        val range = t?.rangeKm ?: CarHub.data?.rangeKm
        val avg = CarHub.tripView?.avgCons ?: t?.avgFuelL100
        val rangeRow = range?.let { "ЗАПАС" to "$it км" }
        val avgRow = avg?.let { "РАСХОД" to "%.1f л".format(RU, it) }
        // «Тест»: расход — в карточке поездки слева, в круге только запас.
        val rows = if (full) listOfNotNull(rangeRow) else listOfNotNull(rangeRow, avgRow)
        if (rows.isEmpty()) return
        // Строки «ЗАПАС  364 км» по центру, ниже цифр 0 и 8.
        var y = cy + (if (full) INFO_Y_FULL else INFO_Y) * s
        val gap = 12f * s
        // «Тест»: запас крупнее.
        val labelSize = (if (full) INFO_LABEL_FULL else 20f) * s
        val valueSize = (if (full) INFO_VALUE_FULL else 30f) * s
        for ((label, value) in rows) {
            text.textSize = labelSize
            val lw = text.measureText(label)
            text.textSize = valueSize
            val vw = text.measureText(value)
            var x = cx - (lw + gap + vw) / 2f
            text.textAlign = Paint.Align.LEFT
            text.color = grey
            text.textSize = labelSize
            c.drawText(label, x, y, text)
            x += lw + gap
            text.color = white
            text.textSize = valueSize
            c.drawText(value, x, y, text)
            text.textAlign = Paint.Align.CENTER
            y += INFO_STEP * s
        }
    }

    // ── Слева: двигатель, за бортом, топливо ─────────────────────────

    private fun drawSide(c: Canvas, cx: Float, cy: Float, r: Float, s: Float) {
        // Тёмная «линза» за дугами, плавно уходящая в чёрный.
        fill.shader = sideShader
        c.drawCircle(cx - r * GLOW_SHIFT, cy, r * (1 + SIDE_OUT), fill)
        fill.shader = null

        val ar = r + ARC_GAP * s
        val d = CarHub.data

        // Двигатель: снизу вверх 40 → 130 °C (по часовой на левой стороне — вверх).
        val exact = d?.engineTempC
        val eng = exact ?: ClusterPps.needle()?.let { CoolantCalibration.estimate(it) }
        sideArc(
            c, cx, cy, ar, s, ENG_FROM,
            eng?.let { ((it - TEMP_MIN) / (TEMP_MAX - TEMP_MIN)).coerceIn(0f, 1f) },
            eng?.let { if (it >= 115f) red else if (it < 60f) blue else white } ?: grey,
            listOf("40", "70", "100", "130°C"), thermo,
            eng?.let { "${Math.round(it)}°" }, iconAt = 0.5f
        )
        // Топливо: снизу вверх E → 1/1.
        val pct = d?.fuelPct
        sideArc(
            c, cx, cy, ar, s, FUEL_FROM,
            pct?.let { (it / 100f).coerceIn(0f, 1f) },
            pct?.let { if (it < 10f) red else if (it < 20f) amber else white } ?: grey,
            listOf("E", "1/2", "1/1"), fuel,
            d?.fuelLiters?.let { "${Math.round(it)} л" }, iconShiftX = FUEL_ICON_SHIFT
        )
        // За бортом — в разрыве между дугами.
        val out = ClusterPps.outsideTempC() ?: d?.outsideTempC
        bold.color = white
        bold.textSize = 40f * s
        bold.textAlign = Paint.Align.RIGHT
        val fm = bold.fontMetrics
        c.drawText(out?.let { "${Math.round(it)}°C" } ?: "--°C", cx - ar + 8f * s, cy - (fm.ascent + fm.descent) / 2, bold)
        bold.textAlign = Paint.Align.CENTER
    }

    /**
     * Дуга-указатель вдоль обода: от угла [from] по часовой на [ARC_SWEEP]
     * (на левой стороне — снизу вверх), заполнение [frac], подписи [labels]
     * равномерно снаружи, значок слева от середины дуги.
     */
    private fun sideArc(
        c: Canvas, cx: Float, cy: Float, ar: Float, s: Float, from: Float,
        frac: Float?, color: Int, labels: List<String>, icon: Drawable?,
        value: String? = null,
        /** «Тест»: значок по высоте — на уровне подписей в этой доле дуги
         *  (0,5 — между 70 и 100), а не на луче
         *  середины дуги (на большом радиусе он уходит от подписей). */
        iconAt: Float? = null,
        /** «Тест»: сдвиг значка со значением вправо (px шкалы 1920). */
        iconShiftX: Float = 0f
    ) {
        oval.set(cx - ar, cy - ar, cx + ar, cy + ar)
        stroke.strokeWidth = 12f * s
        stroke.color = Color.rgb(0x26, 0x2E, 0x38)
        c.drawArc(oval, from, ARC_SWEEP, false, stroke)
        if (frac != null && frac > 0.01f) {
            stroke.color = color
            c.drawArc(oval, from, ARC_SWEEP * frac, false, stroke)
        }
        text.color = grey
        text.textSize = 22f * s
        for ((i, label) in labels.withIndex()) {
            val a = Math.toRadians((from + ARC_SWEEP * i / (labels.size - 1)).toDouble())
            val cos = Math.cos(a).toFloat()
            val sin = Math.sin(a).toFloat()
            round.color = grey
            round.strokeWidth = 2f * s
            c.drawLine(cx + cos * (ar + 8f * s), cy + sin * (ar + 8f * s), cx + cos * (ar + 18f * s), cy + sin * (ar + 18f * s), round)
            text.textAlign = Paint.Align.RIGHT
            c.drawText(label, cx + cos * (ar + 24f * s), cy + sin * (ar + 24f * s) + 8f * s, text)
            text.textAlign = Paint.Align.CENTER
        }
        icon?.let {
            val a = Math.toRadians((from + ARC_SWEEP / 2).toDouble())
            // «Тест»: значок дальше от дуги — справа от него значение цифрами.
            val ir = ar + (if (full) ICON_OUT_FULL else 110f) * s
            val ix = cx + Math.cos(a).toFloat() * ir + (if (full) iconShiftX * s else 0f)
            val iy = if (full && iconAt != null)
                cy + Math.sin(Math.toRadians((from + ARC_SWEEP * iconAt).toDouble())).toFloat() * (ar + 24f * s)
            else cy + Math.sin(a).toFloat() * ir
            val half = 18f * s
            it.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
            it.setBounds((ix - half).toInt(), (iy - half).toInt(), (ix + half).toInt(), (iy + half).toInt())
            it.draw(c)
            if (full) {
                bold.color = white
                bold.textSize = 30f * s
                bold.textAlign = Paint.Align.LEFT
                val fm = bold.fontMetrics
                c.drawText(value ?: "--", ix + half + 10f * s, iy - (fm.ascent + fm.descent) / 2, bold)
                bold.textAlign = Paint.Align.CENTER
            }
        }
    }

    /** Направление на значение [rpm] шкалы оборотов. */
    private fun dir(rpm: Float): Pair<Float, Float> {
        val a = Math.toRadians((START + SWEEP * rpm / RPM_MAX).toDouble())
        return Math.cos(a).toFloat() to Math.sin(a).toFloat()
    }

    companion object {
        /** Шкала оборотов: 0 слева внизу, по часовой на 300°; внизу разрыв 60°. */
        const val START = 120f
        const val SWEEP = 300f
        const val RPM_MAX = 8000f
        const val RED_RPM = 6000f
        const val SPEED_MAX = 220f
        /** Круг между верхней (до 90) и нижней (с 620) строками приборки. */
        const val DIAL_CY = 355f
        const val DIAL_R = 262f
        const val BEZEL = 22f
        /** Стрелка — от этой доли радиуса до обода (центр занят цифрами). */
        const val NEEDLE_FROM = 0.42f
        /** Знак ограничения — справа от обода; запас/расход — в нижнем разрыве шкалы. */
        const val SIGN_GAP = 60f
        const val SIGN_R = 52f
        const val INFO_Y = 186f
        /** «Тест»: круг по центру высоты, строки у краёв экрана. */
        const val FULL_DIAL_CY = 360f
        const val FULL_DIAL_R = 340f
        const val EDGE = 48f
        const val BAR_TOP_Y = 110f
        /** «Тест»: зазор от строки даты/часов до границы градиента (меньше 0 — заходит на градиент). */
        const val TOP_BAR_GAP = -20f
        /** «Тест»: карточка навигации справа — по высоте круга. */
        const val CARD_TOP = 90f
        const val CARD_BOTTOM = 580f
        /** «Тест»: низ карточки — над гнездом режима движения. */
        const val CARD_BOTTOM_LAMPS = 540f
        /** Слоты штатных ламп (indicator.kzb, T2-navi; левый верхний угол). */
        const val CRUISE_X = 184f
        const val CRUISE_Y = 241f
        const val MODE_X = 1380f
        const val MODE_Y = 572f
        const val EPB_X = 332f
        const val BOTTOM_Y = 658f
        const val LAMP_STEP = 58f
        const val SS_X = 1708f
        const val SS_Y = 106f
        /** «Тест»: список поездки — под гнездом круиза. */
        const val TRIP_FIRST_Y = 392f
        const val TRIP_STEP = 56f
        /** Мгновенный расход выше — как у приборки, не больше 99,9 (место под цифры). */
        const val INST_MAX = 99.9f
        const val CARD_PAD = 28f
        /** Карточка — от обода круга, до правого края. */
        const val CARD_GAP = 40f
        /** «Тест»: поездка слева — до обода с запасом под левые дуги и подписи. */
        const val TRIP_CARD_GAP = 150f
        /** Зазор между подписями и колонкой значений. */
        const val TRIP_SPLIT_GAP = 20f
        const val EDGE_RIGHT = 32f
        /** Запас/расход в нижнем разрыве шкалы — ниже цифр 0 и 8 крупного круга. */
        const val INFO_Y_FULL = 292f
        /** «Тест»: размеры подписи «ЗАПАС» и значения. */
        const val INFO_LABEL_FULL = 26f
        const val INFO_VALUE_FULL = 42f
        /** «Тест»: насколько ниже скорость (на месте «км/ч»). */
        const val SPEED_DROP = 14f
        /** «Тест»: размер цифр скорости. */
        const val SPEED_SIZE_FULL = 144f
        /** «Тест»: размер цифр одометра в разрыве обода. */
        const val ODO_SIZE = 40f
        /** «Тест»: значки двигателя/топлива от дуги (место под цифры справа). */
        const val ICON_OUT_FULL = 150f
        /** «Тест»: значок топлива правее (на луче середины дуги он далеко от неё). */
        const val FUEL_ICON_SHIFT = 50f
        const val INFO_STEP = 34f
        /** Левые дуги: отступ от обода, угол; начало (по часовой = вверх). */
        const val ARC_GAP = 34f
        const val ARC_SWEEP = 40f
        const val ENG_FROM = 194f
        const val FUEL_FROM = 126f
        /** Насколько тёмный фон слева выходит за обод, доля радиуса. */
        const val SIDE_OUT = 0.55f
        /** Центр мягкой подсветки сдвинут влево — за левые дуги. */
        const val GLOW_SHIFT = 0.3f
        const val TEMP_MIN = 40f
        const val TEMP_MAX = 130f
        private val GEARS = listOf("R", "N", "P", "1", "2", "3", "4", "5", "6", "7")
        private val RU = Locale("ru")
    }
}
