package com.dash.headunit.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import com.dash.headunit.car.CarHub
import com.dash.headunit.car.CarScaling
import com.dash.headunit.car.ClusterPps
import com.dash.headunit.car.CoolantCalibration
import com.dash.headunit.car.DemoData
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * «Спидометр» в виде классической приборки Жигулей (ВАЗ-2101…2106):
 * слева три малых круглых прибора — топливо (0 · 1/2 · 4/4), вода
 * (50 · 90 · 130 с красным «!» при перегреве) и масло (0 · 4 · 8;
 * давления в CAN нет — стрелка на нуле), в центре тахометр ×100 min⁻¹
 * с красной зоной и контрольными лампами (P оранжевая, АКБ красная,
 * N), справа спидометр 0–180 км/ч с барабанным одометром — цифры в
 * отдельных окошках, крайняя красная, как на механическом счётчике.
 *
 * Приборки на чёрной панели, каждая в хромированном ободке.
 * Данные — живой канал [CarHub]; стрелки догоняют значения
 * демпфированно, как настоящие. Схема повторяет [RetroSpeedometerView]:
 * рисуем в виртуальных координатах [VW]×[VH] и вписываем в блок с
 * сохранением пропорций.
 */
class VazClusterView(context: Context) : View(context) {

    private val hubListener: () -> Unit = { onData() }

    /** Целевые значения (null — данных нет, стрелка на упоре). */
    private var speedTarget: Float? = null
    private var fuelTarget: Float? = null
    private var waterTarget: Float? = null
    /** Градиусы ОЖ: [waterEstimated] — оценка по стрелке приборки. */
    private var waterC: Float? = null
    private var waterEstimated = false
    private var rpmTarget: Int? = null
    private var odo: Int? = null
    /** Индекс в [GEARS], -1 — неизвестно. */
    private var gear = -1
    private var charging: Boolean? = null

    /** Показываемые положения стрелок (0..1 по шкале). */
    private var speedPos = 0f
    private var fuelPos = 0f
    private var waterPos = 0f
    private var rpmPos = 0f
    private var gearPos = 0f
    private var lastFrame = 0L

    private val dialColor = Color.rgb(0x0D, 0x0F, 0x12)
    private val white = Color.rgb(0xE8, 0xE2, 0xD4)
    private val dimWhite = Color.rgb(0x97, 0x92, 0x88)
    private val red = Color.rgb(0xD8, 0x38, 0x2C)
    private val amber = Color.rgb(0xFF, 0xB0, 0x3A)

    private val panel = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chrome = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val face = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(0x80, 0, 0, 0) }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = white
        strokeCap = Paint.Cap.BUTT
    }
    private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = white
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = dimWhite
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    }
    private val needle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = white
        strokeCap = Paint.Cap.ROUND
        setShadowLayer(5f, 0f, 0f, Color.argb(0x70, 0xFF, 0xE8, 0xB0))
    }
    private val hubCap = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x2A, 0x2E, 0x33) }
    private val redMarker = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = red }
    private val redArc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = red
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val lampText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = white
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val digit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = white
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("monospace", Typeface.BOLD)
    }
    private val cellFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cellEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x3A, 0x3E, 0x44)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val rect = RectF()

    init {
        // Тень стрелок рисуется только программно.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        onData()
        speedPos = speedFrac()
        fuelPos = fuelTarget ?: GAUGE_REST
        waterPos = waterTarget ?: GAUGE_REST
        rpmPos = rpmFrac()
        gearPos = gear.coerceAtLeast(0).toFloat()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        CarHub.init(context)
        CarHub.addLiveListener(hubListener)
        CarHub.ensurePolling()
    }

    override fun onDetachedFromWindow() {
        CarHub.removeLiveListener(hubListener)
        super.onDetachedFromWindow()
    }

    private fun onData() {
        val d = CarHub.data ?: DemoData.car()
        speedTarget = d.speedKmh
        fuelTarget = d.fuelPct?.let { (it / 100f).coerceIn(0f, 1f) }
        // Стрелка ОЖ приборки (0..1); если её нет, но есть градусы —
        // та же шкала через CarScaling.coolantToNeedle (как в ретро-виде).
        waterTarget = ClusterPps.needle()
            ?: d.engineTempC?.let { CarScaling.coolantToNeedle(it) }
        waterC = d.engineTempC
        waterEstimated = false
        if (waterC == null) {
            waterC = ClusterPps.needle()?.let {
                waterEstimated = true
                CoolantCalibration.estimate(it)
            }
        }
        rpmTarget = d.rpm
        odo = d.odoKm
        gear = gearIndex(d.gearRaw)
        charging = d.charging
        postInvalidateOnAnimation()
    }

    private fun speedFrac(): Float = ((speedTarget ?: 0f) / MAX_SPEED).coerceIn(0f, 1.02f)
    private fun rpmFrac(): Float = ((rpmTarget ?: 0) / MAX_RPM).coerceIn(0f, 1.02f)

    /** Стрелки догоняют цель с постоянной времени [NEEDLE_TAU_MS]; true — ещё движется. */
    private fun step(): Boolean {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 16L else (now - lastFrame).coerceIn(1, 100)
        lastFrame = now
        val k = 1f - exp(-dt / NEEDLE_TAU_MS)
        fun ease(pos: Float, target: Float): Float {
            val d = target - pos
            return if (kotlin.math.abs(d) < 0.0005f) target else pos + d * k
        }
        speedPos = ease(speedPos, speedFrac())
        fuelPos = ease(fuelPos, fuelTarget ?: GAUGE_REST)
        waterPos = ease(waterPos, waterTarget ?: GAUGE_REST)
        rpmPos = ease(rpmPos, rpmFrac())
        // Указатель селектора: передача неизвестна — стоит, где был.
        val gearTarget = if (gear >= 0) gear.toFloat() else gearPos
        gearPos = if (kotlin.math.abs(gearTarget - gearPos) < 0.01f) gearTarget
        else gearPos + (gearTarget - gearPos) * k
        return speedPos != speedFrac() || rpmPos != rpmFrac() ||
            fuelPos != (fuelTarget ?: GAUGE_REST) ||
            waterPos != (waterTarget ?: GAUGE_REST) || gearPos != gearTarget
    }

    override fun onDraw(canvas: Canvas) {
        val moving = step()
        val s = minOf(width / VW, height / VH)
        canvas.save()
        canvas.translate((width - VW * s) / 2f, (height - VH * s) / 2f)
        canvas.scale(s, s)

        drawPanel(canvas)
        // Приборки слева направо: топливо, вода, масло, тахометр, спидометр.
        drawSmallFace(canvas, FUEL_CX)
        drawFuel(canvas, FUEL_CX)
        drawSmallFace(canvas, WATER_CX)
        drawWater(canvas, WATER_CX)
        drawSmallFace(canvas, OIL_CX)
        drawOil(canvas, OIL_CX)
        drawBigFace(canvas, TACH_CX)
        drawTach(canvas, TACH_CX)
        drawBigFace(canvas, SPEED_CX)
        drawSpeedo(canvas, SPEED_CX)

        canvas.restore()
        if (moving) postInvalidateOnAnimation() else lastFrame = 0L
    }

    private fun drawPanel(c: Canvas) {
        rect.set(4f, 4f, VW - 4f, VH - 4f)
        panel.shader = LinearGradient(
            0f, 0f, 0f, VH,
            Color.rgb(0x26, 0x2A, 0x30), Color.rgb(0x0B, 0x0D, 0x10), Shader.TileMode.CLAMP
        )
        c.drawRoundRect(rect, 30f, 30f, panel)
        panel.shader = null
    }

    /** Хромированный ободок и чёрный циферблат малой приборки. */
    private fun drawSmallFace(c: Canvas, cx: Float) {
        shadow.setShadowLayer(6f, 2f, 4f, Color.argb(0xA0, 0, 0, 0))
        c.drawCircle(cx, CY, SMALL_R + 2f, shadow)
        shadow.clearShadowLayer()
        chromeRing(c, cx, CY, SMALL_R)
        face.shader = RadialGradient(
            cx, CY, SMALL_R,
            intArrayOf(Color.rgb(0x1C, 0x20, 0x25), dialColor),
            floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, CY, SMALL_R - BEZEL, face)
        face.shader = null
    }

    /** Хромированный ободок большой приборки плюс циферблат. */
    private fun drawBigFace(c: Canvas, cx: Float) {
        shadow.setShadowLayer(8f, 3f, 6f, Color.argb(0xA0, 0, 0, 0))
        c.drawCircle(cx, CY, BIG_R + 2f, shadow)
        shadow.clearShadowLayer()
        chromeRing(c, cx, CY, BIG_R)
        face.shader = RadialGradient(
            cx, CY, BIG_R,
            intArrayOf(Color.rgb(0x1B, 0x1F, 0x24), dialColor),
            floatArrayOf(0.6f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, CY, BIG_R - BEZEL, face)
        face.shader = null
    }

    /** Три кольца ободка: свет-тень-свет (как хром на чёрном пластике). */
    private fun chromeRing(c: Canvas, cx: Float, cy: Float, r: Float) {
        chrome.strokeWidth = 7f
        chrome.shader = LinearGradient(
            cx, cy - r, cx, cy + r,
            Color.rgb(0xD0, 0xD6, 0xDC), Color.rgb(0x40, 0x46, 0x4C), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, r, chrome)
        chrome.strokeWidth = 2f
        chrome.shader = LinearGradient(
            cx, cy - r, cx, cy + r,
            Color.rgb(0x90, 0x96, 0x9C), Color.rgb(0xE0, 0xE6, 0xEC), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, r - 5f, chrome)
        chrome.shader = null
    }

    /** Угол от вертикали (в радианах) для доли шкалы 0..1: −[sweep]..+[sweep]. */
    private fun ang(sweep: Double, frac: Float): Double =
        Math.toRadians(-sweep + 2 * sweep * frac)

    /** Угол для drawArc (0° = «3 часа», по часовой) из угла от вертикали. */
    private fun avX(a: Double): Float = Math.toDegrees(a).toFloat() - 90f

    private fun arrow(
        c: Canvas, cx: Float, cy: Float, a: Double, rOuter: Float, rInner: Float, width: Float,
    ) {
        needle.strokeWidth = width
        c.drawLine(
            cx + rInner * sin(a).toFloat(), cy - rInner * cos(a).toFloat(),
            cx + rOuter * sin(a).toFloat(), cy - rOuter * cos(a).toFloat(), needle
        )
    }

    /** Тонкая стрелка малых приборов: с хвостиком и тёмной ступицей. */
    private fun smallArrow(c: Canvas, cx: Float, a: Double) {
        arrow(c, cx, CY, a, SMALL_INNER, -SMALL_INNER * 0.25f, 5f)
        c.drawCircle(cx, CY, 7f, hubCap)
    }

    /** Стрелки на упоре (прибор без данных). */
    private fun restArrow(c: Canvas, cx: Float) =
        smallArrow(c, cx, Math.toRadians(-GAUGE_SWEEP))

    private fun fuelArrow(c: Canvas, cx: Float, pos: Float) =
        smallArrow(c, cx, ang(GAUGE_SWEEP, pos))

    private fun waterArrow(c: Canvas, cx: Float, pos: Float) =
        smallArrow(c, cx, ang(GAUGE_SWEEP, pos))

    /**
     * Малый прибор: риски и подписи по дуге (радиус [arcR]), «!»
     * красным при [onLow] (резерв топлива) / [onHot] (перегрев),
     * заголовок в нижней трети циферблата.
     */
    private fun smallGauge(
        c: Canvas, cx: Float, arcR: Float, title: String,
        labels: List<String>, onLow: Boolean = false, onHot: Boolean = false,
    ) {
        num.color = white
        num.textSize = 20f
        labels.forEachIndexed { i, text ->
            val a = Math.toRadians(-GAUGE_SWEEP + 2 * GAUGE_SWEEP * i / (labels.size - 1).coerceAtLeast(1))
            val r = arcR + 15f
            c.drawText(text, cx + r * sin(a).toFloat(), CY - r * cos(a).toFloat() + 7f, num)
        }
        // Деления между подписями.
        tick.color = white
        val half = (labels.size - 1).coerceAtLeast(1)
        for (i in 0..(half * 4)) {
            val frac = i.toFloat() / (half * 4)
            val major = i % 4 == 0
            val a = Math.toRadians(-GAUGE_SWEEP + 2 * GAUGE_SWEEP * frac)
            val r1 = arcR * (if (major) 0.86f else 0.91f)
            tick.strokeWidth = if (major) 3.5f else 2f
            c.drawLine(
                cx + r1 * sin(a).toFloat(), CY - r1 * cos(a).toFloat(),
                cx + arcR * sin(a).toFloat(), CY - arcR * cos(a).toFloat(), tick
            )
        }
        // «!» за краем шкалы.
        if (onLow || onHot) {
            num.color = red
            num.textSize = 26f
            val a = Math.toRadians((if (onHot) 1 else -1) * (GAUGE_SWEEP + 26.0))
            val r = arcR + 16f
            c.drawText("!", cx + r * sin(a).toFloat(), CY - r * cos(a).toFloat() + 8f, num)
            num.color = white
        }
        small.textSize = 16f
        c.drawText(title, cx, CY + 62f, small)
    }

    private fun drawFuel(c: Canvas, cx: Float) {
        smallGauge(c, cx, SMALL_ARC, "БЕНЗИН", listOf("0", "1/2", "4/4"),
            onLow = (fuelTarget ?: 1f) < FUEL_LOW)
        if (fuelTarget != null) fuelArrow(c, cx, fuelPos) else restArrow(c, cx)
        // Иконка колонки — под ступицей, в центре диска.
        icon.style = Paint.Style.STROKE
        rect.set(cx - 5f, CY + 22f, cx + 7f, CY + 40f)
        c.drawRect(rect, icon)
        c.drawRect(cx + 7f, CY + 26f, cx + 11f, CY + 32f, icon)
        c.drawLine(cx - 9f, CY + 40f, cx + 10f, CY + 40f, icon)
        icon.style = Paint.Style.FILL
    }

    private fun drawWater(c: Canvas, cx: Float) {
        smallGauge(c, cx, SMALL_ARC, "ВОДА",
            if (waterC != null) listOf("50", "90", "130") else emptyList(),
            onHot = (waterC ?: 0f) >= 110f)
        if (waterTarget != null) waterArrow(c, cx, waterPos) else restArrow(c, cx)
        // Иконка термометра — под ступицей.
        icon.style = Paint.Style.STROKE
        c.drawCircle(cx, CY + 32f, 6f, icon)
        icon.style = Paint.Style.FILL
        c.drawRect(cx - 2.5f, CY + 20f, cx + 2.5f, CY + 32f, icon)
        icon.style = Paint.Style.STROKE
        // Перегрев — риска «130» краснеет и заголовок тоже.
        if ((waterC ?: 0f) >= 110f) small.color = red
    }

    private fun drawOil(c: Canvas, cx: Float) {
        smallGauge(c, cx, SMALL_ARC, "МАСЛО", listOf("0", "4", "8"))
        restArrow(c, cx)
        // Иконка маслёнки — под ступицей.
        icon.style = Paint.Style.STROKE
        c.drawLine(cx - 10f, CY + 36f, cx + 1f, CY + 36f, icon)
        c.drawLine(cx + 1f, CY + 36f, cx + 1f, CY + 26f, icon)
        icon.style = Paint.Style.FILL
        c.drawCircle(cx + 6f, CY + 23f, 5f, icon)
        icon.style = Paint.Style.STROKE
    }

    /** Большая стрелка: белая с красным флажком на конце. */
    private fun bigArrow(c: Canvas, cx: Float, cy: Float, a: Double, r: Float) {
        arrow(c, cx, cy, a, r * 0.82f, -r * 0.16f, 7f)
        c.save()
        c.translate(cx + r * 0.70f * sin(a).toFloat(), cy - r * 0.70f * cos(a).toFloat())
        c.rotate(Math.toDegrees(a).toFloat())
        rect.set(-6f, -28f, 6f, -8f)
        c.drawRoundRect(rect, 3f, 3f, redMarker)
        c.restore()
        c.drawCircle(cx, cy, 9f, hubCap)
        c.drawCircle(cx, cy, 4f, needle)
    }

    /**
     * Большая шкала: сектор 240°, цифры каждые [majorStep], риски
     * каждые [minorStep]; для тахометра — красная дуга с [redFrom]
     * и подпись ×100; у спидометра только «км/ч».
     */
    private fun bigGauge(
        c: Canvas, cx: Float, cy: Float, pos: Float, max: Int,
        majorStep: Int, minorStep: Int, redFrom: Int?, caption: String?, unit: String,
    ) {
        val r = BIG_R - 22f
        // Красная зона — дуга у обода.
        if (redFrom != null && redFrom in 1 until max) {
            val a0 = ang(BIG_SWEEP, redFrom.toFloat() / max)
            val a1 = ang(BIG_SWEEP, 1f)
            redArc.strokeWidth = 11f
            c.drawArc(
                cx - r, cy - r, cx + r, cy + r,
                avX(a0), Math.toDegrees(a1 - a0).toFloat(), false, redArc
            )
        }
        // Риски и цифры.
        var v = 0
        while (v <= max) {
            val major = v % majorStep == 0
            val frac = v.toFloat() / max
            val a = Math.toRadians(-BIG_SWEEP + 2 * BIG_SWEEP * frac)
            tick.color = if (major) white else dimWhite
            tick.strokeWidth = if (major) 4.5f else 2.5f
            val r1 = r * (if (major) 0.83f else 0.89f)
            c.drawLine(
                cx + r1 * sin(a).toFloat(), cy - r1 * cos(a).toFloat(),
                cx + r * sin(a).toFloat(), cy - r * cos(a).toFloat(), tick
            )
            if (major) {
                num.color = white
                num.textSize = 20f
                val rx = r * 0.62f
                c.drawText(v.toString(), cx + rx * sin(a).toFloat(), cy - rx * cos(a).toFloat() + 7f, num)
            }
            v += minorStep
        }
        num.color = dimWhite
        caption?.let {
            num.textSize = 22f
            c.drawText(it, cx, cy + 36f, num)
        }
        num.textSize = 18f
        if (unit.isNotEmpty()) c.drawText(unit, cx, cy + 58f, num)
        num.color = white
        bigArrow(c, cx, cy, Math.toRadians(-BIG_SWEEP + 2 * BIG_SWEEP * pos), r)
    }

    private fun drawTach(c: Canvas, cx: Float) {
        bigGauge(c, cx, CY, rpmPos, 80, 10, 5, redFrom = 70,
            caption = "×100", unit = "об/мин")
        lamps(c, cx)
    }

    private fun drawSpeedo(c: Canvas, cx: Float) {
        bigGauge(c, cx, CY, speedPos, 180, 20, 10, redFrom = null, caption = null, unit = "")
        odometer(c, cx)
        // «км/ч» над одометром: unit рисуется отдельно, чтобы окошко не перекрывало.
        num.color = dimWhite
        num.textSize = 18f
        c.drawText("км/ч", cx, CY + 34f, num)
        num.color = white
    }

    /** Контрольные лампы под осью тахометра: P · АКБ · N. */
    private fun lamps(c: Canvas, cx: Float) {
        val y = CY + BIG_R * 0.56f + 10f
        val spacing = 46f
        // P — оранжевая звёздочка селектора.
        drawLamp(c, cx - spacing, y, "P", if (gear == 2) amber else LAMP_OFF)
        // АКБ — красная пиктограмма, горит когда сеть не заряжается.
        val batColor = if (charging == false) red else LAMP_OFF
        drawBattery(c, cx, y - 8f, batColor)
        // N — буква нейтрали.
        drawLamp(c, cx + spacing, y, "N", if (gear == 1) white else LAMP_OFF)
    }

    /** Плашка с буквой: включённая светится с ореолом, выключенная тусклая. */
    private fun drawLamp(c: Canvas, cx: Float, cy: Float, letter: String, color: Int) {
        lampText.color = color
        lampText.textSize = 26f
        if (color != LAMP_OFF) lampText.setShadowLayer(12f, 0f, 0f, color)
        c.drawText(letter, cx, cy + 9f, lampText)
        lampText.clearShadowLayer()
    }

    /** Пиктограмма АКБ: прямоугольник с клеммами и «− +». */
    private fun drawBattery(c: Canvas, cx: Float, cy: Float, color: Int) {
        icon.color = color
        rect.set(cx - 12f, cy - 8f, cx + 12f, cy + 8f)
        c.drawRect(rect, icon)
        c.drawRect(cx - 12f, cy - 11f, cx - 7f, cy - 8f, icon)
        c.drawRect(cx + 7f, cy - 11f, cx + 12f, cy - 8f, icon)
        small.color = color
        small.textSize = 13f
        c.drawText("−", cx - 6f, cy + 4f, small)
        c.drawText("+", cx + 6f, cy + 4f, small)
        small.color = dimWhite
        icon.color = white
    }

    /**
     * Барабанный одометр: пять белых цифр и красная шестая — как на
     * механическом счётчике с красным барабаном на конце.
     */
    private fun odometer(c: Canvas, cx: Float) {
        val digits = odo?.let { "%06d".format(it % 1_000_000) } ?: "------"
        val cw = 30f
        val gap = 2f
        val total = digits.length * cw + (digits.length - 1) * gap
        val left = cx - total / 2f
        val top = CY + BIG_R * 0.39f
        digit.textSize = 30f
        val fm = digit.fontMetrics
        digits.forEachIndexed { i, ch ->
            val x = left + i * (cw + gap)
            rect.set(x, top, x + cw, top + 42f)
            cellFill.color = if (i == digits.length - 1 && digits != "------")
                Color.rgb(0x7A, 0x14, 0x10) else Color.rgb(0x18, 0x1B, 0x1F)
            c.drawRect(rect, cellFill)
            c.drawText(ch.toString(), x + cw / 2f, top + 21f - (fm.ascent + fm.descent) / 2f, digit)
        }
        rect.set(left - 3f, top - 3f, left + total + 3f, top + 45f)
        c.drawRect(rect, cellEdge)
    }

    private companion object {
        const val VW = 1120f
        const val VH = 400f

        const val MAX_SPEED = 180f
        /** Предел тахометра: 8000 об/мин → «80» при ×100. */
        const val MAX_RPM = 8000f

        /** Полуразмах большой шкалы от вертикали, градусы (сектор 240°). */
        const val BIG_SWEEP = 120.0
        /** Полуразмах шкал малых приборов, градусы. */
        const val GAUGE_SWEEP = 56.0

        /** Центры приборок: три малых слева, тахометр и спидометр справа. */
        const val FUEL_CX = 92f
        const val WATER_CX = 232f
        const val OIL_CX = 372f
        const val TACH_CX = 610f
        const val SPEED_CX = 925f
        const val CY = 205f

        /** Радиусы циферблатов и зазор до ободка. */
        const val SMALL_R = 74f
        const val BIG_R = 145f
        const val BEZEL = 6f
        /** Радиус дуги малой шкалы и стрелок. */
        const val SMALL_ARC = 52f
        const val SMALL_INNER = 56f

        /** Ход стрелки топлива, ниже которого горит «!» резерва. */
        const val FUEL_LOW = 0.12f
        /** Тусклый цвет выключенной лампы. */
        const val LAMP_OFF = 0xFF4E4840.toInt()
        /** Упор малых стрелок без данных (чуть ниже нуля). */
        const val GAUGE_REST = -0.08f
        const val NEEDLE_TAU_MS = 130f
    }
}
