package com.dash.headunit.navi.cluster

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import com.dash.headunit.car.CarHub
import com.dash.headunit.car.ClusterPps
import com.dash.headunit.car.CoolantCalibration
import com.dash.headunit.widgets.engineTempColor
import com.dash.headunit.widgets.fuelLevelColor

/**
 * Картинка маршрута для приборки (дисплей 1, 1920×720) — карточка манёвра
 * в центральной полосе шириной [contentWidth] px (по бокам у приборки свои
 * шкалы):
 *
 *  ╭────╮ ╭──────╮
 *  │(60)│ │  ↰   │  300 м
 *  ╰────╯ │      │  ▓▓▓▓▓▓▓▓░░░░   — сколько осталось до манёвра
 *         ╰──────╯  ул. Ленина          камера 60 · 350 м
 *         12 км · 18 мин · прибытие 14:35            ▮ светофор
 *
 * Знак ограничения при маршруте — в своём блоке слева от карточки, в
 * секторе B, на уровне строки «расстояние + полоса до манёвра» (верх
 * блока вровень с плиткой стрелки).
 *
 * Без маршрута ([Guidance.freeDrive]) карточка остаётся на месте, но
 * пустая: в ней строка предупреждений (если есть) — знак ограничения,
 * камера, светофор, текущая улица. Внизу карточки справа — литры в баке
 * и температура двигателя.
 *
 * Чёрный фон: на приборке он сливается с рамкой. Окно держим и без
 * маршрута — иначе на дисплей 1 вернётся зеркало главного экрана.
 * [calibration] — поверх всего дисплея сетка координат (полосы A–H
 * по 240 px, линии через 80 px) и контур центральной полосы.
 *
 * Внизу блока маршрута, у правого края, — литры в баке и температура
 * двигателя цифрами; цвет по тем же порогам, что у виджета «Автомобиль»
 * ([fuelLevelColor], [engineTempColor]).
 */
class ClusterGuidanceView(context: Context) : View(context) {

    var guidance: Guidance? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Ширина центральной полосы, px ([com.dash.headunit.settings.AppSettings.clusterWidth]). */
    var contentWidth: Int = 1100
        set(value) {
            field = value
            invalidate()
        }

    /** Калибровочная рамка; без маршрута — с образцом [ClusterOverlay.SAMPLE]. */
    var calibration = false
        set(value) {
            field = value
            invalidate()
        }

    /** Строки состояния внизу рамки (только в [calibration]). */
    var info: List<String> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private val progress = ManeuverProgress()

    private val white = Color.rgb(0xF2, 0xF4, 0xF7)
    private val grey = Color.rgb(0x9A, 0xA3, 0xAE)
    private val amber = Color.rgb(0xFF, 0xB3, 0x00)
    private val red = Color.rgb(0xE5, 0x39, 0x35)
    private val green = Color.rgb(0x66, 0xBB, 0x6A)

    /** Обновление данных авто (топливо/ОЖ) — перерисовать строку внизу. */
    private val hubListener: () -> Unit = { invalidate() }

    private val cardFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x16, 0x1B, 0x22) }
    private val cardStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x2A, 0x31, 0x3C)
        style = Paint.Style.STROKE
    }
    private val tileFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x26, 0x30, 0x40) }
    private val barTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x2E, 0x35, 0x40) }
    private val barFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white }
    /** Картинка уже белая ([YandexNotificationParser] красит её при разборе) — без фильтров. */
    private val iconPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = white
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = white
        alpha = 100
        style = Paint.Style.STROKE
    }
    private val bigText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = white
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val midText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = white }
    private val smallText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = grey }
    private val signRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = red
        style = Paint.Style.STROKE
    }
    private val signFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val signText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val gridPaint = Paint().apply { strokeWidth = 1f }
    private val gridText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val framePaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val stripPaint = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
    }
    private val path = Path()
    private val src = Rect()
    private val dst = RectF()

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        if (calibration) drawCalibration(canvas)
        val g = guidance ?: ClusterOverlay.SAMPLE.takeIf { calibration } ?: Guidance()
        val h = height.toFloat()
        val s = h / 720f
        val cw = contentWidth.coerceAtMost(width).toFloat()
        val left = (width - cw) / 2f
        val right = left + cw
        val pad = 30f * s

        val cardH = 280f * s
        // Сверху на «Картах» приборка рисует свою строку (температура, время)
        // и под ней родную подсказку манёвра — до ~200 px (ГУ 2026-10-27).
        val top = 210f * s
        val bottom = top + cardH

        // Без маршрута знак открывает строку предупреждений.
        val signR = 62f * s
        val signCy = top + pad + signR
        val limit = g.speedLimit

        // Блок маршрута показываем всегда — без маршрута он пустой,
        // чтобы на приборке оставалась привычная плашка.
        dst.set(left, top, right, bottom)
        canvas.drawRoundRect(dst, 36f * s, 36f * s, cardFill)
        cardStroke.strokeWidth = 2f * s
        canvas.drawRoundRect(dst, 36f * s, 36f * s, cardStroke)

        if (g.hasManeuver) {
            // Плитка со стрелкой манёвра. Стрелки нет (навигатор в блоке:
            // карточка Яндекса скрыта, уведомления нет) — нет и плитки, а
            // не пустой серый квадрат (ГУ 2026-11-02).
            val tile = cardH - 2 * pad
            val tx0 = left + pad
            val ty0 = top + pad
            val hasArrow = g.maneuver != null || g.maneuverIcon != null
            if (hasArrow) {
                dst.set(tx0, ty0, tx0 + tile, ty0 + tile)
                canvas.drawRoundRect(dst, 28f * s, 28f * s, tileFill)
                val inset = 26f * s
                drawManeuver(canvas, g, tx0 + inset, ty0 + inset, tile - 2 * inset)
            }

            // Камера — справа внизу карточки.
            val tx = if (hasArrow) tx0 + tile + 40f * s else tx0 + 16f * s
            var streetRight = right - pad - 24f * s
            smallText.textSize = 34f * s
            if (g.cameraDistance != null) {
                val cam = listOfNotNull("камера", g.cameraLimit?.toString(), g.cameraDistance).joinToString(" · ")
                smallText.color = amber
                val w = smallText.measureText(cam)
                canvas.drawText(cam, right - pad - w, bottom - pad - 6f * s, smallText)
                smallText.color = grey
                streetRight = right - pad - w - 32f * s
            }

            // Расстояние и полоса отсчёта до манёвра.
            val distRight = right - pad - 24f * s
            bigText.textSize = 104f * s
            val distText = g.distance?.let { fit(it, bigText, distRight - tx) }
            distText?.let { canvas.drawText(it, tx, top + pad + 96f * s, bigText) }
            // Знак полос (вырезка с экрана Яндекса) — справа от расстояния.
            val lanesLeft = g.lanes?.let { drawLanes(canvas, it, tx + (distText?.let(bigText::measureText) ?: 0f) + 40f * s, distRight, top + pad, 136f * s) }
            val frac = progress.update(
                // Без типа манёвра: он появляется со снимком через секунды — отсчёт не сбрасываем.
                g.street.orEmpty(),
                GuidanceText.meters(g.distance)
            )
            if (frac != null) {
                val barW = minOf(560f * s, (lanesLeft ?: distRight) - 24f * s - tx)
                val by = top + pad + 128f * s
                val bh = 12f * s
                dst.set(tx, by, tx + barW, by + bh)
                canvas.drawRoundRect(dst, bh / 2, bh / 2, barTrack)
                dst.set(tx, by, tx + barW * frac.coerceIn(0.04f, 1f), by + bh)
                canvas.drawRoundRect(dst, bh / 2, bh / 2, barFill)
            }

            // Улица после манёвра.
            midText.textSize = 44f * s
            g.street?.let { canvas.drawText(fit(it, midText, streetRight - tx), tx, bottom - pad - 6f * s, midText) }
        }

        if (g.hasManeuver) {
            limit?.let { drawSideSign(canvas, it, g.overspeed, left, s) }
            g.trafficLight?.let {
                val th = 120f * s
                // Светофор — ниже нижней строки блока: справа в ней
                // теперь топливо и температура.
                drawTrafficLight(canvas, it, right - pad - th * TL_ASPECT / 2, bottom + 100f * s + th / 2, th)
            }
        } else {
            drawWarningsRow(canvas, g, left, right, signCy, signR, s)
        }

        // Нижняя строка блока: слева остаток маршрута и время прибытия,
        // справа — литры в баке и температура двигателя.
        smallText.textSize = 38f * s
        val eta = listOfNotNull(
            g.remainingDistance,
            g.remainingTime,
            g.arrival?.let { "прибытие $it" }
        ).joinToString("  ·  ")
        val statusW = drawCarStatus(canvas, right - pad, bottom + 70f * s, s)
        val etaRight = right - pad - if (statusW > 0f) statusW + 28f * s else 0f
        val etaW = etaRight - (left + pad)
        if (eta.isNotEmpty() && etaW > 0f) {
            canvas.drawText(fit(eta, smallText, etaW), left + pad, bottom + 70f * s, smallText)
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

    /**
     * Топливо и ОЖ в нижней строке блока маршрута, прижаты к правому краю.
     * Возвращает занятую ширину (0 — данных нет): по ней слева ужимается
     * остаток пути. Цвет — по тем же порогам, что у виджета «Автомобиль»
     * ([fuelLevelColor], [engineTempColor]).
     */
    private fun drawCarStatus(canvas: Canvas, right: Float, baseline: Float, s: Float): Float {
        val d = CarHub.data ?: return 0f
        val fuel = d.fuelLiters
        // Прямые градусы, без них — оценка по стрелке ОЖ приборки
        // (как в ретро-спидометре и виджете «Автомобиль»).
        val exact = d.engineTempC
        val eng = exact ?: ClusterPps.needle()?.let { CoolantCalibration.estimate(it) }
        if (fuel == null && eng == null) return 0f

        smallText.textAlign = Paint.Align.RIGHT
        smallText.textSize = 38f * s
        var x = right
        if (eng != null) {
            smallText.color = engineTempColor(eng, green)
            val text = (if (exact == null) "≈" else "") + "${Math.round(eng)}°"
            canvas.drawText(text, x, baseline, smallText)
            x -= smallText.measureText(text) + if (fuel != null) 28f * s else 0f
        }
        if (fuel != null) {
            smallText.color = d.fuelPct?.let { fuelLevelColor(it, green) } ?: grey
            val text = "${Math.round(fuel)} л"
            canvas.drawText(text, x, baseline, smallText)
            x -= smallText.measureText(text)
        }
        smallText.color = grey
        smallText.textAlign = Paint.Align.LEFT
        return right - x
    }

    /**
     * Картинка полос высотой до [maxH] от [top], прижата вправо к [r], не левее [l]
     * (уже не влезает — уменьшается). Возвращает левый край нарисованного; null — места нет.
     */
    private fun drawLanes(canvas: Canvas, bmp: android.graphics.Bitmap, l: Float, r: Float, top: Float, maxH: Float): Float? {
        val room = r - l
        if (room < maxH * 0.5f || bmp.width <= 0 || bmp.height <= 0) return null
        val k = minOf(maxH / bmp.height, room / bmp.width)
        val w = bmp.width * k
        val h = bmp.height * k
        src.set(0, 0, bmp.width, bmp.height)
        dst.set(r - w, top + (maxH - h) / 2, r, top + (maxH + h) / 2)
        canvas.drawBitmap(bmp, src, dst, iconPaint)
        return r - w
    }

    /** Без маршрута: знак, камера, светофор, улица — в ряд от левого края полосы. */
    private fun drawWarningsRow(canvas: Canvas, g: Guidance, left: Float, right: Float, cy: Float, r: Float, s: Float) {
        val gap = 48f * s
        val th = 2 * r
        midText.textSize = 52f * s
        midText.color = amber
        val cam = g.cameraDistance?.let { d ->
            listOfNotNull("камера", g.cameraLimit?.toString(), d).joinToString(" · ")
        }
        var x = left + 30f * s
        g.speedLimit?.let {
            drawSign(canvas, it, g.overspeed, x + r, cy, r)
            x += 2 * r + gap
        }
        cam?.let {
            val fm = midText.fontMetrics
            canvas.drawText(fit(it, midText, right - x), x, cy - (fm.ascent + fm.descent) / 2, midText)
            x += midText.measureText(it) + gap
        }
        midText.color = white
        g.trafficLight?.let {
            drawTrafficLight(canvas, it, x + th * TL_ASPECT / 2, cy, th)
            x += th * TL_ASPECT + gap
        }
        g.currentStreet?.let {
            midText.textSize = 56f * s
            val fm = midText.fontMetrics
            canvas.drawText(fit(it, midText, right - 30f * s - x), x, cy - (fm.ascent + fm.descent) / 2, midText)
        }
    }

    /** Светофор высотой [h] с центром (cx, cy): горит лампа [light], остальные тусклые. */
    private fun drawTrafficLight(canvas: Canvas, light: TrafficLight, cx: Float, cy: Float, h: Float) {
        val w = h * TL_ASPECT
        dst.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        canvas.drawRoundRect(dst, w * 0.3f, w * 0.3f, tileFill)
        val lr = w * 0.32f
        val step = h / 3
        TrafficLight.entries.forEachIndexed { i, l ->
            signFill.color = if (l == light) LAMP_ON[i] else LAMP_OFF
            canvas.drawCircle(cx, cy - h / 2 + step * (i + 0.5f), lr, signFill)
        }
    }

    /**
     * Стрелка: своя по типу манёвра, а если тип не узнан — картинка
     * навигатора. Не наоборот: картинка из уведомления на ГУ (1.6,
     * 2026-11-02) на приборке не видна — пустая плитка во всех положениях
     * Яндекса, хотя по ней же тип узнаётся.
     */
    private fun drawManeuver(canvas: Canvas, g: Guidance, x: Float, y: Float, size: Float) {
        val icon = g.maneuverIcon
        if (g.maneuver != null) {
            drawArrow(canvas, g.maneuver, x, y, size)
        } else if (icon != null) {
            src.set(0, 0, icon.width, icon.height)
            val k = minOf(size / icon.width, size / icon.height)
            val iw = icon.width * k
            val ih = icon.height * k
            dst.set(x + (size - iw) / 2, y + (size - ih) / 2, x + (size + iw) / 2, y + (size + ih) / 2)
            canvas.drawBitmap(icon, src, dst, iconPaint)
        }
    }

    /**
     * Сетка в пикселях дисплея: полосы A–H по 240 px (как docs/research/grid.png),
     * линии через 80 px с подписями по краям, красная рамка по краю
     * и пунктир центральной полосы [contentWidth].
     */
    private fun drawCalibration(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        for (i in 0 until 8) {
            gridPaint.color = BANDS[i]
            canvas.drawRect(i * 240f, 0f, (i + 1) * 240f, h, gridPaint)
        }
        gridText.textSize = 22f
        var x = 0f
        while (x <= w) {
            gridPaint.color = if (x % 240f == 0f) Color.argb(160, 255, 255, 255) else Color.argb(60, 255, 255, 255)
            canvas.drawLine(x, 0f, x, h, gridPaint)
            gridText.color = Color.YELLOW
            canvas.drawText(x.toInt().toString(), x + 4f, 26f, gridText)
            canvas.drawText(x.toInt().toString(), x + 4f, h - 10f, gridText)
            x += 80f
        }
        var y = 0f
        while (y <= h) {
            gridPaint.color = Color.argb(60, 255, 255, 255)
            canvas.drawLine(0f, y, w, y, gridPaint)
            if (y > 0f && y < h) {
                gridText.color = Color.CYAN
                canvas.drawText(y.toInt().toString(), 10f, y - 6f, gridText)
                canvas.drawText(y.toInt().toString(), w - 60f, y - 6f, gridText)
            }
            y += 80f
        }
        gridText.textSize = 64f
        gridText.color = Color.argb(120, 255, 255, 255)
        for (i in 0 until 8) canvas.drawText(('A' + i).toString(), i * 240f + 96f, 120f, gridText)
        canvas.drawRect(3f, 3f, w - 3f, h - 3f, framePaint)
        val cw = contentWidth.coerceAtMost(width).toFloat()
        val left = (w - cw) / 2f
        canvas.drawRect(left, 40f, left + cw, h - 40f, stripPaint)
        gridText.textSize = 24f
        gridText.color = Color.CYAN
        canvas.drawText("полоса ${cw.toInt()} px: x ${left.toInt()}…${(left + cw).toInt()}", left + 10f, h - 50f, gridText)
        gridText.color = Color.WHITE
        gridText.textSize = 24f
        info.forEachIndexed { i, line ->
            canvas.drawText(fit(line, gridText, cw - 20f), left + 10f, h - 74f - (info.size - 1 - i) * 28f, gridText)
        }
    }

    private fun fit(text: String, paint: TextPaint, width: Float): String =
        if (width <= 0) "" else TextUtils.ellipsize(text, paint, width, TextUtils.TruncateAt.END).toString()

    /**
     * Знак ограничения в отдельном блоке слева от карточки (сектор B, рядом
     * с текущей скоростью приборки). Блок не заходит на карточку: при узкой
     * полосе ([contentWidth]) прижимается к её левому краю.
     */
    private fun drawSideSign(canvas: Canvas, limit: Int, over: Boolean, cardLeft: Float, s: Float) {
        val half = SIDE_BLOCK / 2 * s
        val gap = 14f * s
        val cx = minOf(SIDE_SIGN_CX * s, cardLeft - gap - half)
        val cy = SIDE_SIGN_CY * s
        if (cx - half < 0f) return
        dst.set(cx - half, cy - half, cx + half, cy + half)
        canvas.drawRoundRect(dst, 28f * s, 28f * s, cardFill)
        cardStroke.strokeWidth = 2f * s
        canvas.drawRoundRect(dst, 28f * s, 28f * s, cardStroke)
        drawSign(canvas, limit, over, cx, cy, half - 12f * s)
    }

    /** Знак ограничения; при превышении — красный с белыми цифрами. */
    private fun drawSign(canvas: Canvas, limit: Int, over: Boolean, cx: Float, cy: Float, r: Float) {
        signFill.color = if (over) red else Color.WHITE
        canvas.drawCircle(cx, cy, r, signFill)
        signRing.strokeWidth = r * 0.2f
        canvas.drawCircle(cx, cy, r - signRing.strokeWidth / 2, signRing)
        signText.color = if (over) Color.WHITE else Color.BLACK
        signText.textSize = if (limit >= 100) r * 0.78f else r * 0.95f
        val fm = signText.fontMetrics
        canvas.drawText(limit.toString(), cx, cy - (fm.ascent + fm.descent) / 2, signText)
    }

    /**
     * Своя стрелка, когда картинки из навигатора нет. Рисуется в квадрате
     * [x, y, size]: ствол снизу по центру, поворот и наконечник по типу.
     */
    private fun drawArrow(canvas: Canvas, m: Maneuver, x: Float, y: Float, size: Float) {
        val u = size / 10f
        arrowPaint.strokeWidth = u * 1.1f
        val cx = x + size / 2
        val bottom = y + size - u
        path.reset()
        // (кончик x, y; угол наконечника в градусах: 0 — вверх, 90 — вправо)
        val tip: Triple<Float, Float, Float>
        when (m) {
            Maneuver.FINISH -> {
                headPaint.style = Paint.Style.STROKE
                headPaint.strokeWidth = u * 0.9f
                canvas.drawCircle(cx, y + size / 2, u * 3f, headPaint)
                headPaint.style = Paint.Style.FILL
                canvas.drawCircle(cx, y + size / 2, u * 1.4f, headPaint)
                return
            }
            Maneuver.ROUNDABOUT -> {
                // Как context_ra_out_circular_movement Яндекса: бледное кольцо,
                // ствол снизу, обход кольца справа (против часовой) и съезд
                // вправо-вверх. Номер съезда навигатор не отдаёт.
                val r = u * 2.7f
                val ccy = y + size * 0.5f
                ringPaint.strokeWidth = u * 0.9f
                canvas.drawCircle(cx, ccy, r, ringPaint)
                path.moveTo(cx, bottom)
                path.lineTo(cx, ccy + r)
                path.arcTo(cx - r, ccy - r, cx + r, ccy + r, 90f, -135f, false)
                val d = r * 0.7071f
                val tx = cx + d + u * 1.8f
                val ty = ccy - d - u * 1.8f
                path.lineTo(tx, ty)
                tip = Triple(tx, ty, 45f)
            }
            Maneuver.STRAIGHT -> {
                path.moveTo(cx, bottom)
                path.lineTo(cx, y + u * 2f)
                tip = Triple(cx, y + u * 2f, 0f)
            }
            Maneuver.LEFT, Maneuver.RIGHT -> {
                val d = if (m == Maneuver.RIGHT) 1 else -1
                path.moveTo(cx - d * u, bottom)
                path.lineTo(cx - d * u, y + u * 4.5f)
                path.quadTo(cx - d * u, y + u * 3f, cx + d * u * 0.5f, y + u * 3f)
                path.lineTo(cx + d * u * 2.5f, y + u * 3f)
                tip = Triple(cx + d * u * 2.5f, y + u * 3f, d * 90f)
            }
            Maneuver.SLIGHT_LEFT, Maneuver.SLIGHT_RIGHT, Maneuver.KEEP_LEFT, Maneuver.KEEP_RIGHT -> {
                val d = if (m == Maneuver.SLIGHT_RIGHT || m == Maneuver.KEEP_RIGHT) 1 else -1
                path.moveTo(cx - d * u, bottom)
                path.lineTo(cx - d * u, y + u * 5.5f)
                path.lineTo(cx + d * u * 2f, y + u * 2.5f)
                tip = Triple(cx + d * u * 2f, y + u * 2.5f, d * 45f)
            }
            Maneuver.SHARP_LEFT, Maneuver.SHARP_RIGHT -> {
                val d = if (m == Maneuver.SHARP_RIGHT) 1 else -1
                path.moveTo(cx - d * u, bottom)
                path.lineTo(cx - d * u, y + u * 2.5f)
                path.lineTo(cx + d * u * 2f, y + u * 5.5f)
                tip = Triple(cx + d * u * 2f, y + u * 5.5f, d * 135f)
            }
            Maneuver.UTURN_LEFT, Maneuver.UTURN_RIGHT -> {
                val d = if (m == Maneuver.UTURN_RIGHT) 1 else -1
                path.moveTo(cx - d * u * 1.5f, bottom)
                path.lineTo(cx - d * u * 1.5f, y + u * 4f)
                path.arcTo(
                    minOf(cx - d * u * 1.5f, cx + d * u * 1.5f), y + u * 2.5f,
                    maxOf(cx - d * u * 1.5f, cx + d * u * 1.5f), y + u * 5.5f,
                    if (d == 1) 180f else 0f, d * 180f, false
                )
                path.lineTo(cx + d * u * 1.5f, y + u * 6.5f)
                tip = Triple(cx + d * u * 1.5f, y + u * 6.5f, 180f)
            }
        }
        canvas.drawPath(path, arrowPaint)
        drawHead(canvas, tip.first, tip.second, tip.third, u)
    }

    /** Наконечник-треугольник с остриём в (x, y), направленный под углом [deg]. */
    private fun drawHead(canvas: Canvas, x: Float, y: Float, deg: Float, u: Float) {
        headPaint.style = Paint.Style.FILL
        canvas.save()
        canvas.rotate(deg, x, y)
        val p = Path()
        p.moveTo(x, y - u * 0.6f)
        p.lineTo(x + u * 1.6f, y + u * 1.6f)
        p.lineTo(x - u * 1.6f, y + u * 1.6f)
        p.close()
        canvas.drawPath(p, headPaint)
        canvas.restore()
    }

    private companion object {
        /** Ширина светофора к высоте. */
        const val TL_ASPECT = 0.42f
        /** Блок знака ограничения при маршруте: сторона и центр, px дисплея 1920×720. */
        const val SIDE_BLOCK = 140f
        const val SIDE_SIGN_CX = 322f
        const val SIDE_SIGN_CY = 310f
        /** Лампы сверху вниз — в порядке [TrafficLight]: красная, жёлтая, зелёная. */
        val LAMP_ON = intArrayOf(Color.rgb(0xE5, 0x52, 0x45), Color.rgb(0xFF, 0xDF, 0x61), Color.rgb(0x85, 0xCC, 0x3D))
        val LAMP_OFF = Color.rgb(0x3A, 0x40, 0x4A)
        /** Цвета полос A–H (приглушённые, как в grid.png). */
        val BANDS = intArrayOf(
            Color.rgb(0x50, 0x00, 0x00), Color.rgb(0x50, 0x28, 0x00),
            Color.rgb(0x48, 0x48, 0x00), Color.rgb(0x00, 0x48, 0x00),
            Color.rgb(0x00, 0x40, 0x48), Color.rgb(0x00, 0x18, 0x60),
            Color.rgb(0x34, 0x00, 0x58), Color.rgb(0x48, 0x00, 0x30),
        )
    }
}

/**
 * Доля пути до манёвра, оставшаяся с момента, как он появился: 1 — только
 * что показан, 0 — на месте. Новый манёвр (другой [key]) или выросшее
 * расстояние (перестроение маршрута) — отсчёт заново. Чистая логика.
 */
class ManeuverProgress {
    private var key: String? = null
    private var start = 0

    /** null — расстояние неизвестно (полосу не рисуем). */
    fun update(key: String, meters: Int?): Float? {
        if (meters == null) return null
        if (key != this.key || meters > start) {
            this.key = key
            start = meters
        }
        return if (start <= 0) null else meters.toFloat() / start
    }
}
