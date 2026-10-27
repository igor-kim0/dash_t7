package com.dash.headunit.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import com.dash.headunit.car.ClimateHub

/**
 * Виджет «Климат» в виде ретро-панели (вариант к текстовому
 * [ClimateWidgetView]): та же чёрная панель в хромированном ободке, что у
 * [RetroSpeedometerView]. Сверху три таблички — уставка водителя,
 * вентилятор со скоростью, уставка пассажира; снизу контрольные лампы:
 * режим обдува, AUTO, A/C, рециркуляция, обдув лобового ([RetroClimateIcons]).
 * Только показ состояния [ClimateHub]; нет данных или климат выключен —
 * всё гравировкой. Рисуется в виртуальных координатах [VW]×[VH] и
 * вписывается в блок с сохранением пропорций.
 */
class ClimateIconsView(context: Context) : View(context) {

    private val hubListener: () -> Unit = { postInvalidateOnAnimation() }

    private val panel = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bezel = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val hood = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x07, 0x09, 0x0B) }
    private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0xB8, 0xB0, 0x9C)
        textAlign = Paint.Align.CENTER
        textSize = 20f
        typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    }
    private val icons = RetroClimateIcons(hood)
    private val rect = RectF()

    init {
        // Ореол «ламп» рисуется только программно.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
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

    override fun onDraw(canvas: Canvas) {
        val sc = minOf(width / VW, height / VH)
        canvas.save()
        canvas.translate((width - VW * sc) / 2f, (height - VH * sc) / 2f)
        canvas.scale(sc, sc)
        drawPanel(canvas)
        drawClimate(canvas)
        canvas.restore()
    }

    private fun drawPanel(c: Canvas) {
        rect.set(4f, 4f, VW - 4f, VH - 4f)
        panel.shader = LinearGradient(
            0f, 0f, 0f, VH,
            Color.rgb(0x1C, 0x23, 0x2A), Color.rgb(0x0A, 0x0D, 0x10), Shader.TileMode.CLAMP
        )
        c.drawRoundRect(rect, 26f, 26f, panel)
        bezel.strokeWidth = 5f
        bezel.shader = LinearGradient(
            0f, 0f, 0f, VH,
            Color.rgb(0xA8, 0xB0, 0xB8), Color.rgb(0x3A, 0x40, 0x46), Shader.TileMode.CLAMP
        )
        c.drawRoundRect(rect, 26f, 26f, bezel)
    }

    private fun drawClimate(c: Canvas) {
        val s = ClimateHub.data
        val on = s != null && s.power != false
        val text = icons.text

        // Верх: уставки по краям, вентилятор в середине.
        plate(c, 24f, TOP_Y, 204f, TOP_Y + TOP_H)
        plate(c, 220f, TOP_Y, 380f, TOP_Y + TOP_H)
        plate(c, 396f, TOP_Y, 576f, TOP_Y + TOP_H)
        c.drawText("ВОДИТЕЛЬ", 114f, TOP_Y + 26f, caption)
        c.drawText("ПАССАЖИР", 486f, TOP_Y + 26f, caption)
        text.textSize = 56f
        icons.lamp(on && s?.tempDriverC != null, RetroClimateIcons.LAMP_WHITE)
        c.drawText(RetroClimateIcons.setpointDeg(s?.tempDriverC), 114f, TOP_Y + 86f, text)
        icons.lamp(on && s?.tempPassC != null, RetroClimateIcons.LAMP_WHITE)
        c.drawText(RetroClimateIcons.setpointDeg(s?.tempPassC), 486f, TOP_Y + 86f, text)

        val fan = s?.fanSpeed?.takeIf { on && it > 0 }
        icons.lamp(fan != null, RetroClimateIcons.LAMP_WHITE)
        scaled(c, 262f, TOP_Y + TOP_H / 2f, 1.5f) { icons.fan(c, 262f, TOP_Y + TOP_H / 2f) }
        val speed = when {
            s?.power == false -> "OFF"
            else -> fan?.toString() ?: "–"
        }
        text.textSize = if (speed.length > 1) 34f else 52f
        c.drawText(speed, 340f, TOP_Y + TOP_H / 2f + text.textSize * 0.36f, text)

        // Низ: контрольные лампы.
        plate(c, 24f, BOTTOM_Y, VW - 24f, BOTTOM_Y + BOTTOM_H)
        val step = (VW - 48f) / 5f
        val cy = BOTTOM_Y + BOTTOM_H / 2f
        fun x(i: Int) = 24f + step * (i + 0.5f)
        icons.lamp(on && s?.fanDirection != null, RetroClimateIcons.LAMP_WHITE)
        scaled(c, x(0), cy, LAMP_SCALE) { icons.direction(c, x(0), cy, s?.fanDirection) }
        icons.lamp(on && s?.auto == true, RetroClimateIcons.LAMP_GREEN)
        text.textSize = 30f
        c.drawText("AUTO", x(1), cy + 11f, text)
        icons.lamp(on && s?.ac == true, RetroClimateIcons.LAMP_BLUE)
        scaled(c, x(2), cy, LAMP_SCALE) { icons.snowflake(c, x(2), cy) }
        icons.lamp(on && s?.recirc == true, RetroClimateIcons.LAMP_GREEN)
        scaled(c, x(3), cy, LAMP_SCALE) { icons.recirc(c, x(3), cy) }
        icons.lamp(on && s?.defrostFront == true, RetroClimateIcons.LAMP_AMBER)
        scaled(c, x(4), cy, LAMP_SCALE) { icons.defrost(c, x(4), cy) }
    }

    /** Чёрная пластина таблички в хромированной рамке, как у селектора спидометра. */
    private fun plate(c: Canvas, l: Float, t: Float, r: Float, b: Float) {
        rect.set(l, t, r, b)
        c.drawRoundRect(rect, 10f, 10f, hood)
        bezel.strokeWidth = 2.5f
        c.drawRoundRect(rect, 10f, 10f, bezel)
    }

    private inline fun scaled(c: Canvas, cx: Float, cy: Float, k: Float, draw: () -> Unit) {
        c.save()
        c.scale(k, k, cx, cy)
        draw()
        c.restore()
    }

    private companion object {
        const val VW = 600f
        const val VH = 260f
        const val TOP_Y = 24f
        const val TOP_H = 106f
        const val BOTTOM_Y = 146f
        const val BOTTOM_H = 90f
        const val LAMP_SCALE = 1.5f
    }
}
