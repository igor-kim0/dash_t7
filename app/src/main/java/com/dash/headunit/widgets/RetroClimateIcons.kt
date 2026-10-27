package com.dash.headunit.widgets

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.dash.headunit.car.ClimateHub
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Ретро-символы климата для чёрных табличек ([RetroSpeedometerView],
 * [ClimateIconsView]): вентилятор, режим обдува, A/C, рециркуляция,
 * обдув лобового. Символ рисуется гравировкой ([ENGRAVED]) или «лампой» —
 * цветом с ореолом, как буквы селектора; цвет задаёт [lamp] перед рисованием.
 * Иконки — в коробке ~40×36 вокруг центра; крупнее — через `Canvas.scale`.
 * Ореол рисуется только с `LAYER_TYPE_SOFTWARE` у вида.
 */
class RetroClimateIcons(private val hole: Paint) {

    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val iconFill = Paint(Paint.ANTI_ALIAS_FLAG)
    /** Внутренность «трубки» человечка — цветом фона таблички. */
    private val holeLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    /** Надписи табличек (уставка, скорость, AUTO) — цветом текущей лампы. */
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val path = Path()
    private val rect = RectF()

    /** Цвет символа: включён — «лампа» с ореолом, иначе гравировка. */
    fun lamp(lit: Boolean, color: Int) {
        val col = if (lit) color else ENGRAVED
        icon.color = col
        iconFill.color = col
        text.color = col
        if (lit) {
            icon.setShadowLayer(10f, 0f, 0f, color)
            iconFill.setShadowLayer(10f, 0f, 0f, color)
            text.setShadowLayer(12f, 0f, 0f, color)
        } else {
            icon.clearShadowLayer()
            iconFill.clearShadowLayer()
            text.clearShadowLayer()
        }
    }

    /** Наконечник стрелки в ([x], [y]), смотрит по углу [dir] (радианы, экранные). */
    private fun arrowHead(c: Canvas, x: Float, y: Float, dir: Double, len: Float = 6f) {
        for (side in listOf(-1, 1)) {
            val a = dir + Math.PI + side * Math.toRadians(35.0)
            c.drawLine(x, y, x + len * cos(a).toFloat(), y + len * sin(a).toFloat(), icon)
        }
    }

    /** Вентилятор: три лопасти-капли вокруг ступицы (ступица — цветом [hole]). */
    fun fan(c: Canvas, cx: Float, cy: Float) {
        for (k in 0 until 3) {
            c.save()
            c.rotate(120f * k + 15f, cx, cy)
            path.reset()
            path.moveTo(cx, cy)
            path.cubicTo(cx - 9f, cy - 5f, cx - 7f, cy - 17f, cx + 1f, cy - 17f)
            path.cubicTo(cx + 7f, cy - 16f, cx + 6f, cy - 7f, cx, cy)
            c.drawPath(path, iconFill)
            c.restore()
        }
        c.drawCircle(cx, cy, 4f, hole)
        icon.strokeWidth = 2f
        c.drawCircle(cx, cy, 4f, icon)
    }

    /**
     * Обдув, как на штатных кнопках климата: полулежащий человечек контуром
     * (голова кольцом справа, ноги влево) и толстые стрелки потока слева
     * сверху — в лицо (1, 3), в ноги (2, 3, 4), вверх на стекло (4, 5).
     * Режим неизвестен — без стрелок.
     */
    fun direction(c: Canvas, cx: Float, cy: Float, mode: Int?) {
        // Голова — кольцо.
        icon.strokeWidth = 2f
        c.drawCircle(cx + 11f, cy - 14f, 3.2f, icon)
        // Тело «трубкой»: толстая линия цветом символа, внутри — цветом фона.
        path.reset()
        path.moveTo(cx + 10f, cy - 7f)
        path.lineTo(cx + 8f, cy + 6f)
        path.quadTo(cx + 7f, cy + 10f, cx + 3f, cy + 9f)
        path.lineTo(cx - 4f, cy + 8f)
        path.lineTo(cx - 14f, cy + 14f)
        icon.strokeWidth = 6.5f
        c.drawPath(path, icon)
        holeLine.color = hole.color
        c.drawPath(path, holeLine)
        icon.strokeWidth = 2.5f
        if (mode == 1 || mode == 3) flowArrow(c, cx - 11f, cy - 17f, cx + 3f, cy - 9f)
        if (mode == 2 || mode == 3 || mode == 4) flowArrow(c, cx - 17f, cy - 5f, cx - 10f, cy + 4f)
        if (mode == 4 || mode == 5) {
            // Стекло — наклонная черта слева сверху, стрелка к нему.
            c.drawLine(cx - 18f, cy - 8f, cx - 10f, cy - 19f, icon)
            flowArrow(c, cx + 1f, cy - 5f, cx - 7f, cy - 13f)
        }
    }

    /** Толстая залитая стрелка потока из ([x0], [y0]) остриём в ([x1], [y1]). */
    private fun flowArrow(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float) {
        val dir = atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())
        val ux = cos(dir).toFloat()
        val uy = sin(dir).toFloat()
        val head = 7f
        val half = 5.5f
        val w = icon.strokeWidth
        icon.strokeWidth = 4f
        icon.strokeCap = Paint.Cap.BUTT
        c.drawLine(x0, y0, x1 - ux * head * 0.9f, y1 - uy * head * 0.9f, icon)
        icon.strokeCap = Paint.Cap.ROUND
        icon.strokeWidth = w
        val bx = x1 - ux * head
        val by = y1 - uy * head
        path.reset()
        path.moveTo(x1, y1)
        path.lineTo(bx - uy * half, by + ux * half)
        path.lineTo(bx + uy * half, by - ux * half)
        path.close()
        c.drawPath(path, iconFill)
    }

    /** A/C: снежинка — шесть лучей с «ёлочками». */
    fun snowflake(c: Canvas, cx: Float, cy: Float) {
        icon.strokeWidth = 2.5f
        val r = 15f
        for (k in 0 until 6) {
            val a = Math.toRadians(90.0 + 60.0 * k)
            val ux = cos(a).toFloat()
            val uy = -sin(a).toFloat()
            c.drawLine(cx, cy, cx + r * ux, cy + r * uy, icon)
            val bx = cx + r * 0.55f * ux
            val by = cy + r * 0.55f * uy
            for (side in listOf(-1, 1)) {
                val b = a + side * Math.toRadians(50.0)
                c.drawLine(bx, by, bx + 6f * cos(b).toFloat(), by - 6f * sin(b).toFloat(), icon)
            }
        }
    }

    /** Рециркуляция: силуэт кузова и петля-стрелка внутри. */
    fun recirc(c: Canvas, cx: Float, cy: Float) {
        icon.strokeWidth = 2.5f
        path.reset()
        path.moveTo(cx - 19f, cy + 10f)
        path.lineTo(cx - 19f, cy + 1f)
        path.lineTo(cx - 11f, cy - 1f)
        path.lineTo(cx - 5f, cy - 11f)
        path.lineTo(cx + 8f, cy - 11f)
        path.lineTo(cx + 14f, cy - 1f)
        path.lineTo(cx + 19f, cy + 1f)
        path.lineTo(cx + 19f, cy + 10f)
        c.drawPath(path, icon)
        val r = 6.5f
        val oy = cy + 4f
        rect.set(cx - r, oy - r, cx + r, oy + r)
        c.drawArc(rect, 150f, 270f, false, icon)
        // Конец дуги — 60°, движение по часовой: касательная (-sin, cos).
        val end = Math.toRadians(60.0)
        arrowHead(
            c, cx + r * cos(end).toFloat(), oy + r * sin(end).toFloat(),
            atan2(cos(end), -sin(end)), 5f
        )
    }

    /** Обдув лобового: выгнутое стекло и три волнистые стрелки вверх. */
    fun defrost(c: Canvas, cx: Float, cy: Float) {
        icon.strokeWidth = 2.5f
        path.reset()
        path.moveTo(cx - 18f, cy - 9f)
        path.quadTo(cx, cy - 15f, cx + 18f, cy - 9f)
        path.lineTo(cx + 13f, cy + 12f)
        path.quadTo(cx, cy + 9f, cx - 13f, cy + 12f)
        path.close()
        c.drawPath(path, icon)
        icon.strokeWidth = 2f
        for (dx in listOf(-7f, 0f, 7f)) {
            val x = cx + dx
            path.reset()
            path.moveTo(x, cy + 7f)
            path.cubicTo(x - 3f, cy + 3f, x + 3f, cy - 1f, x, cy - 6f)
            c.drawPath(path, icon)
            arrowHead(c, x, cy - 6f, -Math.PI / 2, 4f)
        }
    }

    companion object {
        val ENGRAVED = Color.rgb(0x5C, 0x58, 0x4E)
        val LAMP_WHITE = Color.rgb(0xFF, 0xF2, 0xD8)
        val LAMP_GREEN = Color.rgb(0x9C, 0xE0, 0x86)
        val LAMP_BLUE = Color.rgb(0x8E, 0xC8, 0xFF)
        val LAMP_AMBER = Color.rgb(0xFF, 0xB0, 0x3A)

        /** Уставка одной зоны: «22», «22.5», LO/HI (без знака градуса). */
        fun setpoint(t: Float): String = when {
            t <= ClimateHub.TEMP_LO -> "LO"
            t >= ClimateHub.TEMP_HI -> "HI"
            t % 1f == 0f -> "%.0f".format(Locale.ROOT, t)
            else -> "%.1f".format(Locale.ROOT, t)
        }

        /** [setpoint] со знаком градуса у чисел; нет данных — «--°». */
        fun setpointDeg(t: Float?): String {
            if (t == null) return "--°"
            val s = setpoint(t)
            return if (s == "LO" || s == "HI") s else "$s°"
        }
    }
}
