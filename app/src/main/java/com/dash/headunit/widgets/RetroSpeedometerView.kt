package com.dash.headunit.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
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
import kotlin.math.tan

/**
 * «Спидометр» в стиле классической советской приборки (Москвич-412/2140):
 * цифры 0–160 ровной строкой поверху, риски — отрезки лучей из оси,
 * спрятанной под одометром (поэтому к середине шкалы цифры сжимаются,
 * к краям — разъезжаются), слева указатель топлива 0 · 1/2 · 4/4, справа
 * «ВОДА» (стрелка ОЖ приборки) с подписью в градусах — честные °C из a0ff
 * ([ClusterPps.coolantC]) или OBD в приоритете, без них оценка по стрелке
 * ([CoolantCalibration.estimate]),
 * в центре — одометр, под ним табличка
 * селектора R N P 1–7 (как в цифровом виде): гравированные буквы,
 * включённая подсвечена «лампой», под ней ездит красный указатель.
 *
 * Рисуется в виртуальных координатах [VW]×[VH] и вписывается в блок
 * с сохранением пропорций. Данные — живой канал [CarHub] (скорость с
 * каждого кадра приборки); стрелки догоняют значение плавно, как
 * демпфированная механика.
 */
class RetroSpeedometerView(context: Context) : View(context) {

    private val hubListener: () -> Unit = { onData() }

    /** Целевые значения (null — данных нет, стрелка на упоре). */
    private var speedTarget: Float? = null
    private var fuelTarget: Float? = null
    private var waterTarget: Float? = null
    /** Градусы ОЖ для подписи у «ВОДА»; [waterEstimated] — это оценка по стрелке. */
    private var waterC: Float? = null
    private var waterEstimated = false
    private var odo: Int? = null
    /** Индекс включённой передачи в [GEARS] ([gearIndex]), -1 — неизвестно. */
    private var gear = -1

    /** Показываемые положения стрелок (0..1 по шкале). */
    private var speedPos = 0f
    private var fuelPos = 0f
    private var waterPos = 0f
    /** Положение красного указателя селектора (индекс, дробный в движении). */
    private var gearPos = 0f
    private var lastFrame = 0L

    private val cream = Color.rgb(0xEE, 0xE6, 0xD2)
    private val dimCream = Color.rgb(0xB8, 0xB0, 0x9C)
    private val red = Color.rgb(0xE0, 0x3A, 0x2E)

    private val panel = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bezel = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ledge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x3A, 0x42, 0x4A)
        strokeWidth = 3f
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cream
        strokeCap = Paint.Cap.BUTT
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cream
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    }
    private val needle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0xF6, 0xEE, 0xDA)
        strokeCap = Paint.Cap.ROUND
        setShadowLayer(6f, 0f, 0f, Color.argb(0x90, 0xFF, 0xE8, 0xB0))
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = red }
    private val hood = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x07, 0x09, 0x0B) }
    private val cell = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x16, 0x1A, 0x1E) }
    private val digit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cream
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("monospace", Typeface.BOLD)
    }
    private val gearText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val rect = RectF()

    init {
        // Тень стрелки рисуется только программно.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        onData()
        speedPos = speedFrac()
        fuelPos = fuelTarget ?: GAUGE_REST
        waterPos = waterTarget ?: GAUGE_REST
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
        // Стрелка ОЖ приборки (0..1) — ровно то, что видно на штатной;
        // если её нет, но есть градусы — по той же шкале ([CarScaling.coolantToNeedle]).
        waterTarget = ClusterPps.needle()
            ?: d.engineTempC?.let { CarScaling.coolantToNeedle(it) }
        // Подпись у «ВОДА» в градусах: сначала честные (a0ff приборки /
        // OBD / VHAL / Desay), без них — оценка по положению стрелки
        // ([CoolantCalibration.estimate]).
        waterC = d.engineTempC
        waterEstimated = false
        if (waterC == null) {
            waterC = ClusterPps.needle()?.let {
                waterEstimated = true
                CoolantCalibration.estimate(it)
            }
        }
        odo = d.odoKm ?: ClusterPps.trip()?.odometerKm?.toInt()
        gear = gearIndex(d.gearRaw)
        postInvalidateOnAnimation()
    }

    private fun speedFrac(): Float = ((speedTarget ?: 0f) / MAX_SPEED).coerceIn(0f, 1.03f)

    /** Стрелки догоняют цель с постоянной времени [NEEDLE_TAU_MS]; true — ещё в движении. */
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
        // Указатель селектора: передача неизвестна — стоит, где был.
        val gearTarget = if (gear >= 0) gear.toFloat() else gearPos
        gearPos = if (kotlin.math.abs(gearTarget - gearPos) < 0.01f) gearTarget else gearPos + (gearTarget - gearPos) * k
        return speedPos != speedFrac() || gearPos != gearTarget ||
            fuelPos != (fuelTarget ?: GAUGE_REST) || waterPos != (waterTarget ?: GAUGE_REST)
    }

    override fun onDraw(canvas: Canvas) {
        val moving = step()
        val s = minOf(width / VW, height / VH)
        canvas.save()
        canvas.translate((width - VW * s) / 2f, (height - VH * s) / 2f)
        canvas.scale(s, s)

        drawPanel(canvas)
        drawSpeedScale(canvas)
        drawFuel(canvas)
        drawWater(canvas)
        drawSpeedNeedle(canvas)
        drawOdometer(canvas)
        drawGears(canvas)

        canvas.restore()
        if (moving) postInvalidateOnAnimation() else lastFrame = 0L
    }

    private fun drawPanel(c: Canvas) {
        rect.set(4f, 4f, VW - 4f, VH - 4f)
        panel.shader = LinearGradient(
            0f, 0f, 0f, VH,
            Color.rgb(0x1C, 0x23, 0x2A), Color.rgb(0x0A, 0x0D, 0x10), Shader.TileMode.CLAMP
        )
        c.drawRoundRect(rect, 26f, 26f, panel)
        // Хромированный ободок: светлый сверху, тёмный снизу.
        bezel.strokeWidth = 5f
        bezel.shader = LinearGradient(
            0f, 0f, 0f, VH,
            Color.rgb(0xA8, 0xB0, 0xB8), Color.rgb(0x3A, 0x40, 0x46), Shader.TileMode.CLAMP
        )
        c.drawRoundRect(rect, 26f, 26f, bezel)
        // Нижний козырёк, под которым одометр и ось стрелки.
        c.drawLine(40f, LEDGE_Y, VW - 40f, LEDGE_Y, ledge)
    }

    /** Угол луча (от вертикали) для доли шкалы 0..1. */
    private fun angle(frac: Float): Double = Math.toRadians((-SWEEP + 2 * SWEEP * frac).toDouble())

    /** Точка на луче с углом [a] из оси спидометра, пересекающем горизонталь [y]. */
    private fun xAt(a: Double, y: Float): Float = (PIVOT_X + (PIVOT_Y - y) * tan(a)).toFloat()

    private fun drawSpeedScale(c: Canvas) {
        label.textSize = 40f
        label.color = cream
        var v = 0
        while (v <= MAX_SPEED.toInt()) {
            val a = angle(v / MAX_SPEED)
            val major = v % 20 == 0
            // Риска — отрезок луча от верхней линии к оси.
            val top = TICK_TOP_Y
            val len = if (major) 0.15f else 0.08f
            val bottom = top + (PIVOT_Y - top) * len
            tick.strokeWidth = if (major) 5f else 3.5f
            c.drawLine(xAt(a, top), top, xAt(a, bottom), bottom, tick)
            if (major) c.drawText(v.toString(), xAt(a, LABEL_Y), LABEL_Y, label)
            v += 10
        }
        // Красные метки, как на оригинале (ограничения 60 и 90).
        for (mark in RED_MARKS) {
            val a = angle(mark / MAX_SPEED)
            val y = TICK_TOP_Y - 6f
            c.drawCircle(xAt(a, y), y, 4.5f, dot)
        }
        label.textSize = 24f
        label.color = dimCream
        c.drawText("км/ч", xAt(angle(1f), LABEL_Y) + 68f, LABEL_Y - 2f, label)
    }

    private fun drawSpeedNeedle(c: Canvas) {
        val a = angle(speedPos)
        val reach = (PIVOT_Y - TICK_TOP_Y + 6f) / cos(a).toFloat()
        // Видимая часть — от козырька одометра: ось глубоко под ним.
        val inner = (PIVOT_Y - LEDGE_Y) / cos(a).toFloat()
        needle.strokeWidth = 7f
        c.drawLine(
            PIVOT_X + inner * sin(a).toFloat(), PIVOT_Y - inner * cos(a).toFloat(),
            PIVOT_X + reach * sin(a).toFloat(), PIVOT_Y - reach * cos(a).toFloat(),
            needle
        )
    }

    /** Малый указатель: подписи по дуге над осью [cx, LEDGE_Y], стрелка 0..1. */
    private fun drawSmallGauge(
        c: Canvas, cx: Float, labels: List<String>, title: String?, redAtEnd: Boolean, pos: Float,
    ) {
        val py = LEDGE_Y - 6f
        val r = 92f
        label.color = cream
        label.textSize = 24f
        for (i in 0..4) {
            val a = Math.toRadians((-GAUGE_SWEEP + GAUGE_SWEEP * i / 2.0))
            val r1 = r * 0.62f
            val r2 = r * 0.78f
            tick.strokeWidth = 3f
            c.drawLine(
                cx + r1 * sin(a).toFloat(), py - r1 * cos(a).toFloat(),
                cx + r2 * sin(a).toFloat(), py - r2 * cos(a).toFloat(), tick
            )
        }
        labels.forEachIndexed { i, text ->
            val a = Math.toRadians(-GAUGE_SWEEP + 2 * GAUGE_SWEEP * i / (labels.size - 1).coerceAtLeast(1))
            c.drawText(text, cx + r * sin(a).toFloat(), py - r * cos(a).toFloat(), label)
        }
        title?.let {
            label.textSize = 22f
            c.drawText(it, cx, py - r - 6f, label)
        }
        val dotA = Math.toRadians(if (redAtEnd) GAUGE_SWEEP * 1.25 else -GAUGE_SWEEP * 1.25)
        c.drawCircle(cx + r * 0.72f * sin(dotA).toFloat(), py - r * 0.72f * cos(dotA).toFloat(), 6f, dot)

        val a = Math.toRadians(-GAUGE_SWEEP + 2 * GAUGE_SWEEP * pos)
        needle.strokeWidth = 5f
        c.drawLine(
            cx + 10f * sin(a).toFloat(), py - 10f * cos(a).toFloat(),
            cx + r * 0.7f * sin(a).toFloat(), py - r * 0.7f * cos(a).toFloat(), needle
        )
    }

    private fun drawFuel(c: Canvas) =
        drawSmallGauge(c, 108f, listOf("0", "1/2", "4/4"), null, redAtEnd = false, pos = fuelPos)

    /** «ВОДА»: стрелка ОЖ приборки + подпись градусов под шкалой. */
    private fun drawWater(c: Canvas) {
        drawSmallGauge(c, VW - 108f, emptyList(), "ВОДА", redAtEnd = true, pos = waterPos)
        waterC?.let { t ->
            val text = (if (waterEstimated) "≈" else "") + "%d°".format(t.roundToInt())
            label.textSize = 24f
            // Перегрев — подпись краснеет вместе с меткой в конце шкалы.
            label.color = if (t >= 110f) red else dimCream
            c.drawText(text, VW - 108f, LEDGE_Y + 30f, label)
        }
    }

    private fun drawOdometer(c: Canvas) {
        val digits = odo?.let { "%06d".format(it % 1_000_000) } ?: "------"
        val cw = 27f
        val gap = 3f
        val total = digits.length * cw + (digits.length - 1) * gap
        val left = PIVOT_X - total / 2f
        val top = LEDGE_Y + 8f
        // Козырёк над осью: под ним прячется основание стрелки.
        rect.set(left - 14f, LEDGE_Y - 2f, left + total + 14f, top + 46f)
        c.drawRoundRect(rect, 8f, 8f, hood)
        digit.textSize = 30f
        val fm = digit.fontMetrics
        digits.forEachIndexed { i, ch ->
            val x = left + i * (cw + gap)
            rect.set(x, top, x + cw, top + 38f)
            c.drawRoundRect(rect, 3f, 3f, cell)
            c.drawText(ch.toString(), x + cw / 2f, top + 19f - (fm.ascent + fm.descent) / 2f, digit)
        }
    }

    /**
     * Табличка селектора: чёрная пластина в хромированной рамке,
     * буквы гравированы (тусклые), включённая светится цветом лампы,
     * под ней — красный указатель.
     */
    private fun drawGears(c: Canvas) {
        val cw = 46f
        val total = GEARS.size * cw
        val left = PIVOT_X - total / 2f
        val top = GEAR_TOP
        rect.set(left - 16f, top, left + total + 16f, top + 58f)
        c.drawRoundRect(rect, 10f, 10f, hood)
        bezel.strokeWidth = 2.5f
        c.drawRoundRect(rect, 10f, 10f, bezel)

        gearText.textSize = 34f
        val baseline = top + 34f
        GEARS.forEachIndexed { i, g ->
            val cx = left + cw * i + cw / 2f
            if (i == gear) {
                gearText.color = gearLamp(i)
                gearText.setShadowLayer(14f, 0f, 0f, gearLamp(i))
            } else {
                gearText.color = Color.rgb(0x5C, 0x58, 0x4E)
                gearText.clearShadowLayer()
            }
            c.drawText(g, cx, baseline, gearText)
        }
        gearText.clearShadowLayer()

        // Указатель — красная планка под буквой.
        if (gear >= 0) {
            val px = left + cw * gearPos + cw / 2f
            rect.set(px - 14f, top + 43f, px + 14f, top + 49f)
            c.drawRoundRect(rect, 3f, 3f, dot)
        }
    }

    /** Цвет «лампы»: R янтарная, N голубая, P зелёная, цифры — тёплый белый. */
    private fun gearLamp(i: Int): Int = when (i) {
        0 -> Color.rgb(0xFF, 0xB0, 0x3A)
        1 -> Color.rgb(0x8E, 0xC8, 0xFF)
        2 -> Color.rgb(0x9C, 0xE0, 0x86)
        else -> Color.rgb(0xFF, 0xF2, 0xD8)
    }

    private companion object {
        const val VW = 1000f
        const val VH = 390f
        const val MAX_SPEED = 160f
        /** Полуразмах шкалы спидометра от вертикали, градусы. */
        const val SWEEP = 36.0
        const val GAUGE_SWEEP = 40.0
        const val PIVOT_X = 500f
        /** Ось глубоко под панелью: цифры 0–160 занимают ±[SCALE_HALF] по ширине. */
        val PIVOT_Y = LABEL_Y + SCALE_HALF / tan(Math.toRadians(SWEEP)).toFloat()
        const val LABEL_Y = 78f
        const val SCALE_HALF = 322f
        const val TICK_TOP_Y = 100f
        const val LEDGE_Y = 252f
        /** Верх таблички селектора (под одометром). */
        const val GEAR_TOP = 314f
        /** Порядок как в цифровом виде; индекс — [gearIndex]. */
        val GEARS = listOf("R", "N", "P", "1", "2", "3", "4", "5", "6", "7")
        /** Упор малых стрелок без данных (чуть ниже нуля). */
        const val GAUGE_REST = -0.08f
        const val NEEDLE_TAU_MS = 120f
        val RED_MARKS = listOf(60f, 90f)
    }
}
