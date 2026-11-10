package com.dash.headunit.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.text.TextUtils
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import com.dash.headunit.R
import com.dash.headunit.radio.RadioHub
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * «Радио» в виде «Ретро» — советская автомагнитола 70-х: чёрный корпус,
 * сверху стеклянное окно шкалы в две строки (FM в МГц, AM в «×100 кГц»,
 * как ДВ/СВ на старых приёмниках) с красной визирной стрелкой, справа
 * клавиши диапазонов; ниже — серебристая панель с канавками, две
 * хромированные ручки, чёрная клавиша посередине и табличка «избранного».
 *
 * Управление: левая ручка — поиск назад, правая — поиск вперёд (ручка
 * проворачивается), средняя клавиша — пуск/пауза, клавиши справа от
 * шкалы — FM/AM, табличка со звездой — избранное, тап по шкале — открыть
 * штатное радио. Под клавишей гравирована частота и имя станции.
 *
 * Рисуется в виртуальных координатах высотой [VH]; ширина [vw] следует за
 * пропорциями блока в пределах [VW_MIN]..[VW_MAX] — шкала растягивается,
 * ручки остаются круглыми.
 */
class RetroRadioView(context: Context) : View(context) {

    private val hubListener: () -> Unit = { postInvalidateOnAnimation() }

    private val condensedBold = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val condensed = Typeface.create("sans-serif-condensed", Typeface.NORMAL)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = condensedBold
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()
    private val path = Path()

    /** Ширина виртуального поля для текущего блока. */
    private var vw = 580f
    private var scale = 1f
    private var offX = 0f
    private var offY = 0f

    /** Положение стрелки на шкале (доля 0..1) и куда она едет. */
    private var needle = -1f
    /** Поворот ручек, градусы; ручка доворачивается до цели. */
    private var rotL = 0f
    private var rotR = 0f
    private var rotLTarget = 0f
    private var rotRTarget = 0f

    /** Что сейчас нажато пальцем — рисуется утопленным. */
    private var pressed: Hit? = null

    private enum class Hit { KNOB_L, KNOB_R, PLAY, KEY_FM, KEY_AM, FAV, SCALE }

    init {
        isClickable = true
    }

    // ---------- геометрия ----------

    private val knobLX get() = vw * 0.18f
    private val knobRX get() = vw - 150f
    private val scaleX0 get() = 86f
    private val scaleX1 get() = vw - 132f
    private val keyX get() = vw - 50f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0 || h <= 0) return
        vw = (w.toFloat() / h * VH).coerceIn(VW_MIN, VW_MAX)
        scale = minOf(w / vw, h / VH)
        offX = (w - vw * scale) / 2f
        offY = (h - VH * scale) / 2f
    }

    // ---------- данные ----------

    private val isAm get() = RadioHub.state.band == RadioHub.AM

    /** Частота в единицах шкалы текущего диапазона (МГц для FM, кГц для AM). */
    private fun frequency(): Float? {
        val (num, unit) = RadioHub.Json.splitFrequency(RadioHub.state.frequency)
        val v = num.replace(',', '.').toFloatOrNull() ?: return null
        val u = unit.lowercase()
        return if (isAm) {
            if (u.startsWith("m") || v < 30f) v * 1000f else v
        } else {
            if (u.startsWith("k") || v > 1000f) v / 1000f else v
        }
    }

    private fun frac(v: Float, am: Boolean): Float {
        val (lo, hi) = if (am) AM_LO to AM_HI else FM_LO to FM_HI
        return ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
    }

    private fun xOf(f: Float): Float = scaleX0 + 14f + (scaleX1 - scaleX0 - 28f) * f

    /** Сдвигает стрелку и ручки к цели; true — ещё едут. */
    private fun step(): Boolean {
        val target = frequency()?.let { frac(it, isAm) } ?: needle.coerceAtLeast(0f)
        if (needle < 0f) needle = target
        needle = approach(needle, target, 0.004f)
        rotL = approach(rotL, rotLTarget, 0.5f)
        rotR = approach(rotR, rotRTarget, 0.5f)
        return needle != target || rotL != rotLTarget || rotR != rotRTarget
    }

    private fun approach(v: Float, t: Float, eps: Float): Float =
        if (abs(t - v) < eps) t else v + (t - v) * 0.18f

    // ---------- отрисовка ----------

    override fun onDraw(canvas: Canvas) {
        val moving = step()
        canvas.save()
        canvas.translate(offX, offY)
        canvas.scale(scale, scale)
        drawBody(canvas)
        drawWindow(canvas)
        drawPanel(canvas)
        drawKnob(canvas, knobLX, rotL, pressed == Hit.KNOB_L)
        drawKnob(canvas, knobRX, rotR, pressed == Hit.KNOB_R)
        drawPlayKey(canvas)
        drawFavPlate(canvas)
        drawCaption(canvas)
        canvas.restore()
        if (moving) postInvalidateOnAnimation()
    }

    private fun drawBody(c: Canvas) {
        rect.set(2f, 2f, vw - 2f, VH - 2f)
        fill.color = Color.BLACK
        fill.shader = LinearGradient(0f, 0f, 0f, VH, 0xFF2C2C2A.toInt(), 0xFF070707.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, 16f, 16f, fill)
        fill.shader = null
        stroke.strokeWidth = 2f
        stroke.color = 0xFF4A4A46.toInt()
        c.drawRoundRect(rect, 16f, 16f, stroke)
        // Светлая планка над окном шкалы.
        rect.set(BEZEL, 12f, vw - BEZEL, WIN_TOP)
        fill.color = Color.BLACK
        fill.shader = LinearGradient(0f, 12f, 0f, WIN_TOP, 0xFFE4E4DC.toInt(), 0xFFADAEA6.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(rect, fill)
        fill.shader = null
    }

    private fun drawWindow(c: Canvas) {
        val playing = RadioHub.state.playing == true && !RadioHub.unavailable
        rect.set(BEZEL, WIN_TOP, vw - BEZEL, WIN_BOTTOM)
        fill.color = 0xFF0B0B0A.toInt()
        c.drawRect(rect, fill)
        // Тёплая подсветка шкалы, пока играет.
        if (playing) {
            fill.color = Color.BLACK
            fill.shader = RadialGradient(
                vw / 2f, (WIN_TOP + WIN_BOTTOM) / 2f, vw * 0.45f,
                0x403A2A10, 0x003A2A10, Shader.TileMode.CLAMP
            )
            c.drawRect(rect, fill)
            fill.shader = null
        }
        // Серая дорожка стрелки между строками.
        rect.set(scaleX0, ROW_MID - 5f, scaleX1, ROW_MID + 5f)
        fill.color = 0xFF34342F.toInt()
        c.drawRect(rect, fill)

        val am = isAm
        val on = 0xFFF4F0E2.toInt()
        val off = 0xFF6E6C64.toInt()
        drawRow(c, ROW1_Y, FM_MARKS, FM_DOTS, false, if (!am) on else off, playing && !am)
        drawRow(c, ROW2_Y, AM_MARKS, AM_DOTS, true, if (am) on else off, playing && am)

        // Подписи единиц слева.
        text.typeface = condensedBold
        text.textAlign = Paint.Align.CENTER
        text.clearShadowLayer()
        text.color = if (!am) on else off
        text.textSize = 15f
        c.drawText("МГц", 50f, ROW1_Y, text)
        text.color = if (am) on else off
        text.textSize = 10f
        c.drawText("100×", 50f, ROW2_Y - 13f, text)
        text.textSize = 15f
        c.drawText("кГц", 50f, ROW2_Y, text)

        // Буквы диапазонов и клавиши справа.
        text.textSize = 24f
        text.color = if (!am) on else off
        c.drawText("FM", vw - 103f, ROW1_Y + 1f, text)
        text.color = if (am) on else off
        c.drawText("AM", vw - 103f, ROW2_Y + 1f, text)
        drawBandKey(c, ROW1_Y - 9f, !am, pressed == Hit.KEY_FM)
        drawBandKey(c, ROW2_Y - 9f, am, pressed == Hit.KEY_AM)

        // Визирная стрелка.
        if (!RadioHub.unavailable && needle >= 0f) {
            val x = xOf(needle)
            stroke.strokeCap = Paint.Cap.BUTT
            stroke.strokeWidth = 7f
            stroke.color = 0x55FF3A1C
            c.drawLine(x, WIN_TOP + 6f, x, WIN_BOTTOM - 4f, stroke)
            stroke.strokeWidth = 2.6f
            stroke.color = 0xFFFF4424.toInt()
            c.drawLine(x, WIN_TOP + 6f, x, WIN_BOTTOM - 4f, stroke)
        }
    }

    /** Строка шкалы: цифры на [marks], точки/штрихи на [dots]. */
    private fun drawRow(
        c: Canvas, y: Float, marks: List<Pair<Float, String>>, dots: List<Float>,
        am: Boolean, color: Int, glow: Boolean
    ) {
        text.typeface = condensedBold
        text.textAlign = Paint.Align.CENTER
        text.textSize = 23f
        text.color = color
        if (glow) text.setShadowLayer(4f, 0f, 0f, (color and 0x00FFFFFF) or 0x90000000.toInt())
        else text.clearShadowLayer()
        marks.forEach { (v, label) -> c.drawText(label, xOf(frac(v, am)), y, text) }
        text.clearShadowLayer()
        fill.color = color
        dots.forEach { v ->
            val x = xOf(frac(v, am))
            if (am) {
                rect.set(x - 1.2f, y - 18f, x + 1.2f, y - 10f)
                c.drawRect(rect, fill)
            } else {
                c.drawCircle(x, y - 2f, 2f, fill)
            }
        }
    }

    /** Клавиша диапазона: включённая — утоплена и светлая. */
    private fun drawBandKey(c: Canvas, cy: Float, active: Boolean, down: Boolean) {
        val x0 = keyX - 22f
        val x1 = keyX + 22f
        val sink = if (active || down) 3f else 0f
        // Колодец клавиши.
        rect.set(x0 - 3f, cy - 9f, x1 + 3f, cy + 9f)
        fill.color = 0xFF1C1C1A.toInt()
        c.drawRoundRect(rect, 2f, 2f, fill)
        rect.set(x0, cy - 6f + sink, x1, cy + 6f)
        fill.color = Color.BLACK
        fill.shader = LinearGradient(
            0f, rect.top, 0f, rect.bottom,
            if (active) 0xFFF8F8F0.toInt() else 0xFFB8B9B1.toInt(),
            if (active) 0xFFC8C9C0.toInt() else 0xFF6F706A.toInt(),
            Shader.TileMode.CLAMP
        )
        c.drawRect(rect, fill)
        fill.shader = null
        // Торец невключённой клавиши.
        if (!active && !down) {
            rect.set(x0, cy + 3f, x1, cy + 6f)
            fill.color = 0xFF4A4B46.toInt()
            c.drawRect(rect, fill)
        }
    }

    private fun drawPanel(c: Canvas) {
        rect.set(BEZEL, WIN_BOTTOM, vw - BEZEL, VH - BEZEL)
        fill.color = Color.BLACK
        fill.shader = LinearGradient(
            0f, WIN_BOTTOM, 0f, VH - BEZEL,
            0xFFD2D4CC.toInt(), 0xFFA3A59D.toInt(), Shader.TileMode.CLAMP
        )
        c.drawRect(rect, fill)
        fill.shader = null
        // Тень под окном шкалы.
        fill.color = Color.BLACK
        fill.shader = LinearGradient(0f, WIN_BOTTOM, 0f, WIN_BOTTOM + 6f, 0x60000000, 0, Shader.TileMode.CLAMP)
        rect.set(BEZEL, WIN_BOTTOM, vw - BEZEL, WIN_BOTTOM + 6f)
        c.drawRect(rect, fill)
        fill.shader = null
        // Канавки поперёк панели: тёмная линия и блик под ней.
        GROOVES.forEach { y ->
            stroke.strokeCap = Paint.Cap.BUTT
            stroke.strokeWidth = 1.6f
            stroke.color = 0xFF5E605A.toInt()
            c.drawLine(BEZEL, y, vw - BEZEL, y, stroke)
            stroke.strokeWidth = 1f
            stroke.color = 0xB0FFFFFF.toInt()
            c.drawLine(BEZEL, y + 1.6f, vw - BEZEL, y + 1.6f, stroke)
        }
    }

    /** Ручка: рифлёное чёрное кольцо, хромированная юбка и колпачок с риской. */
    private fun drawKnob(c: Canvas, cx: Float, rot: Float, down: Boolean) {
        val cy = KNOB_Y
        // Тень на панели.
        fill.color = Color.BLACK
        fill.shader = RadialGradient(cx + 3f, cy + 5f, KNOB_R + 8f, 0x90000000.toInt(), 0, Shader.TileMode.CLAMP)
        c.drawCircle(cx + 3f, cy + 5f, KNOB_R + 8f, fill)
        fill.shader = null
        // Чёрное кольцо с накаткой.
        fill.color = Color.BLACK
        fill.shader = RadialGradient(cx - 10f, cy - 14f, KNOB_R * 1.3f, 0xFF3A3A38.toInt(), 0xFF050505.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, KNOB_R, fill)
        fill.shader = null
        stroke.strokeWidth = 1.2f
        stroke.color = 0x40FFFFFF
        for (i in 0 until 60) {
            val a = Math.toRadians((i * 6 + rot).toDouble())
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            c.drawLine(cx + ca * (KNOB_R - 6f), cy + sa * (KNOB_R - 6f), cx + ca * (KNOB_R - 1f), cy + sa * (KNOB_R - 1f), stroke)
        }
        // Хромированная юбка.
        val skirt = KNOB_R * 0.74f
        fill.color = Color.BLACK
        fill.shader = RadialGradient(cx - 6f, cy - 8f, skirt * 1.2f, 0xFFFFFFFF.toInt(), 0xFF7C7E7A.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, skirt, fill)
        fill.shader = null
        stroke.color = 0xFF3A3B38.toInt()
        stroke.strokeWidth = 1.5f
        c.drawCircle(cx, cy, skirt, stroke)
        // Колпачок — шлифованный металл (блики крутятся с ручкой).
        val cap = KNOB_R * (if (down) 0.46f else 0.5f)
        c.save()
        c.rotate(rot, cx, cy)
        fill.color = Color.BLACK
        fill.shader = SweepGradient(
            cx, cy,
            intArrayOf(
                0xFFF4F4F0.toInt(), 0xFF8E908A.toInt(), 0xFFE8E8E2.toInt(),
                0xFF7E807A.toInt(), 0xFFF4F4F0.toInt()
            ),
            null
        )
        c.drawCircle(cx, cy, cap, fill)
        fill.shader = null
        stroke.color = 0xFF55574F.toInt()
        stroke.strokeWidth = 1f
        c.drawCircle(cx, cy, cap * 0.62f, stroke)
        // Риска.
        stroke.strokeWidth = 2.4f
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.color = 0xFF2A2B28.toInt()
        c.drawLine(cx, cy - cap + 3f, cx, cy - cap * 0.66f, stroke)
        stroke.strokeCap = Paint.Cap.BUTT
        c.restore()
        fill.color = if (down) 0x30000000 else 0x18000000
        c.drawCircle(cx, cy, KNOB_R, fill)
    }

    /** Средняя чёрная клавиша: пуск / пауза. */
    private fun drawPlayKey(c: Canvas) {
        val down = pressed == Hit.PLAY
        val cx = vw / 2f
        val sink = if (down) 2f else 0f
        rect.set(cx - 46f, PLAY_TOP - 3f, cx + 46f, PLAY_BOTTOM + 3f)
        fill.color = 0xFF55574F.toInt()
        c.drawRoundRect(rect, 5f, 5f, fill)
        rect.set(cx - 42f, PLAY_TOP + sink, cx + 42f, PLAY_BOTTOM)
        fill.color = Color.BLACK
        fill.shader = LinearGradient(
            0f, rect.top, 0f, rect.bottom,
            if (down) 0xFF161616.toInt() else 0xFF3A3A38.toInt(), 0xFF050505.toInt(),
            Shader.TileMode.CLAMP
        )
        c.drawRoundRect(rect, 4f, 4f, fill)
        fill.shader = null
        if (!down) {
            stroke.strokeWidth = 1.2f
            stroke.color = 0x50FFFFFF
            c.drawLine(rect.left + 4f, rect.top + 1.5f, rect.right - 4f, rect.top + 1.5f, stroke)
        }
        // Гравированный значок.
        val cy = (rect.top + rect.bottom) / 2f
        fill.color = if (RadioHub.state.playing == true) 0xFFB8B4A6.toInt() else 0xFF8A877C.toInt()
        if (RadioHub.state.playing == true) {
            rect.set(cx - 8f, cy - 9f, cx - 3f, cy + 9f)
            c.drawRect(rect, fill)
            rect.set(cx + 3f, cy - 9f, cx + 8f, cy + 9f)
            c.drawRect(rect, fill)
        } else {
            path.reset()
            path.moveTo(cx - 6f, cy - 10f)
            path.lineTo(cx + 10f, cy)
            path.lineTo(cx - 6f, cy + 10f)
            path.close()
            c.drawPath(path, fill)
        }
    }

    /** Табличка-эмблема справа: в рамке звезда «избранного». */
    private fun drawFavPlate(c: Canvas) {
        val cx = vw - 50f
        val cy = KNOB_Y - 4f
        val fav = RadioHub.state.favorite == true
        val known = RadioHub.state.frequency != null
        rect.set(cx - 22f, cy - 22f, cx + 22f, cy + 22f)
        fill.color = if (pressed == Hit.FAV) 0xFFB4B6AE.toInt() else 0xFFE0E2DA.toInt()
        c.drawRect(rect, fill)
        stroke.color = 0xFF1E1F1C.toInt()
        stroke.strokeWidth = 3f
        c.drawRect(rect, stroke)
        stroke.strokeWidth = 1.5f
        c.drawLine(cx - 22f, cy, cx + 22f, cy, stroke)
        c.drawLine(cx, cy - 22f, cx, cy + 22f, stroke)
        // Звезда поверх креста — как буквы эмблемы.
        star(cx, cy, 15f)
        fill.color = when {
            !known -> 0xFF9A9C94.toInt()
            fav -> 0xFFFF9A1E.toInt()
            else -> 0xFFE0E2DA.toInt()
        }
        c.drawPath(path, fill)
        stroke.color = 0xFF1E1F1C.toInt()
        stroke.strokeWidth = 2f
        c.drawPath(path, stroke)
    }

    private fun star(cx: Float, cy: Float, r: Float) {
        path.reset()
        for (i in 0 until 10) {
            val rr = if (i % 2 == 0) r else r * 0.45f
            val a = Math.toRadians((-90 + i * 36).toDouble())
            val x = cx + rr * cos(a).toFloat()
            val y = cy + rr * sin(a).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }

    /** Гравировка под клавишей: частота и станция или состояние службы. */
    private fun drawCaption(c: Canvas) {
        val s = RadioHub.state
        val freq = frequency()
        val freqText = freq?.let {
            if (isAm) "${it.toInt()} кГц" else String.format("%.1f МГц", it)
        }
        val caption = when {
            RadioHub.unavailable -> context.getString(R.string.radio_unavailable)
            !RadioHub.connected && s.frequency == null -> context.getString(R.string.radio_connecting)
            else -> listOfNotNull(
                freqText,
                s.station ?: if (s.playing == false) context.getString(R.string.radio_paused) else null
            ).joinToString("  ·  ")
        }
        if (caption.isEmpty()) return
        text.typeface = condensedBold
        text.textAlign = Paint.Align.CENTER
        text.clearShadowLayer()
        text.textSize = 17f
        val maxW = (knobRX - KNOB_R) - (knobLX + KNOB_R) - 16f
        val line = TextUtils.ellipsize(caption.uppercase(), text, maxW, TextUtils.TruncateAt.END).toString()
        val cx = (knobLX + knobRX) / 2f
        text.color = 0xC0FFFFFF.toInt()
        c.drawText(line, cx, CAPTION_Y + 1.2f, text)
        text.color = 0xFF3C3E39.toInt()
        c.drawText(line, cx, CAPTION_Y, text)
    }

    // ---------- касания ----------

    private fun hit(px: Float, py: Float): Hit? {
        val x = (px - offX) / scale
        val y = (py - offY) / scale
        fun near(cx: Float, cy: Float, r: Float) = (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r
        return when {
            near(knobLX, KNOB_Y, KNOB_R + 8f) -> Hit.KNOB_L
            near(knobRX, KNOB_Y, KNOB_R + 8f) -> Hit.KNOB_R
            abs(x - vw / 2f) <= 50f && y in PLAY_TOP - 8f..PLAY_BOTTOM + 8f -> Hit.PLAY
            abs(x - (vw - 50f)) <= 28f && y in KNOB_Y - 32f..KNOB_Y + 24f -> Hit.FAV
            x >= vw - 124f && y in WIN_TOP..ROW_MID -> Hit.KEY_FM
            x >= vw - 124f && y in ROW_MID..WIN_BOTTOM -> Hit.KEY_AM
            y in WIN_TOP..WIN_BOTTOM -> Hit.SCALE
            else -> null
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = hit(e.x, e.y)
                invalidate()
                return pressed != null
            }
            MotionEvent.ACTION_MOVE -> {
                if (pressed != null && hit(e.x, e.y) != pressed) {
                    pressed = null
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val h = pressed
                pressed = null
                if (h != null && hit(e.x, e.y) == h) {
                    performClick()
                    act(h)
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = null
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun act(h: Hit) {
        if (RadioHub.unavailable && h != Hit.SCALE) return
        when (h) {
            Hit.KNOB_L -> {
                rotLTarget -= KNOB_TURN
                RadioHub.seekDown()
            }
            Hit.KNOB_R -> {
                rotRTarget += KNOB_TURN
                RadioHub.seekUp()
            }
            Hit.PLAY -> RadioHub.toggle()
            Hit.KEY_FM -> if (isAm) RadioHub.switchBand(context)
            Hit.KEY_AM -> if (!isAm) RadioHub.switchBand(context)
            Hit.FAV -> if (RadioHub.state.frequency != null) RadioHub.toggleFavorite()
            Hit.SCALE -> RadioHub.open(context)
        }
        postInvalidateOnAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        RadioHub.addListener(hubListener)
        RadioHub.init(context)
        RadioHub.refresh()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        RadioHub.removeListener(hubListener)
        super.onDetachedFromWindow()
    }

    companion object {
        const val VH = 220f
        const val VW_MIN = 460f
        const val VW_MAX = 760f

        private const val BEZEL = 12f
        private const val WIN_TOP = 22f
        private const val WIN_BOTTOM = 106f
        private const val ROW1_Y = 56f
        private const val ROW_MID = 66f
        private const val ROW2_Y = 98f
        private const val KNOB_Y = 152f
        private const val KNOB_R = 40f
        private const val PLAY_TOP = 128f
        private const val PLAY_BOTTOM = 166f
        private const val CAPTION_Y = 194f
        private val GROOVES = listOf(122f, 140f, 176f)
        private const val KNOB_TURN = 40f

        private const val FM_LO = 87.5f
        private const val FM_HI = 108f
        private const val AM_LO = 522f
        private const val AM_HI = 1620f

        private val FM_MARKS = listOf(88f, 92f, 96f, 100f, 104f, 108f).map { it to it.toInt().toString() }
        private val FM_DOTS = listOf(90f, 94f, 98f, 102f, 106f)
        private val AM_MARKS = listOf(600f, 800f, 1000f, 1200f, 1400f, 1600f).map { it to (it / 100).toInt().toString() }
        private val AM_DOTS = listOf(700f, 900f, 1100f, 1300f, 1500f)
    }
}
