package com.dash.headunit.screensaver

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.util.Date
import kotlin.random.Random

/**
 * Фон «Тёмный»: чёрный экран и тусклые цифровые часы с датой. Каждые
 * [HOLD_S] с часы гаснут и появляются в другом месте экрана — экран не
 * выгорает и картинка не застывает.
 */
class DarkClockView(context: Context) : SaverView(context, 20) {

    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT_COLOR
        typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    }
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT_COLOR
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }

    private var blockX = 0f
    private var blockTop = 0f
    private var phaseStart = 0f
    private var placed = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        timePaint.textSize = h * 0.26f
        datePaint.textSize = h * 0.055f
        placed = false
    }

    override fun draw(canvas: Canvas, t: Float, dt: Float) {
        canvas.drawColor(Color.BLACK)
        if (!placed) place(t)
        var p = t - phaseStart
        if (p >= HOLD_S) {
            place(t)
            p = 0f
        }
        val a = when {
            p < FADE_S -> p / FADE_S
            p > HOLD_S - FADE_S -> (HOLD_S - p) / FADE_S
            else -> 1f
        }.coerceIn(0f, 1f)
        val now = Date()
        val time = TIME.format(now)
        val date = formatDate(now)
        val alpha = (a * MAX_ALPHA).toInt()
        timePaint.alpha = alpha
        datePaint.alpha = alpha
        val blockW = blockWidth()
        val timeW = timePaint.measureText(time)
        val timeBase = blockTop - timePaint.ascent()
        canvas.drawText(time, blockX + (blockW - timeW) / 2, timeBase, timePaint)
        val dateW = datePaint.measureText(date)
        canvas.drawText(
            date, blockX + (blockW - dateW) / 2,
            timeBase + timePaint.descent() - datePaint.ascent(), datePaint
        )
    }

    /** Ширина блока — по самой широкой строке, чтобы он не вылезал за экран. */
    private fun blockWidth(): Float = maxOf(
        timePaint.measureText("88:88"),
        datePaint.measureText(formatDate(Date()))
    )

    private fun place(t: Float) {
        val blockH = timePaint.descent() - timePaint.ascent() +
            datePaint.descent() - datePaint.ascent()
        val margin = 24 * density
        val freeX = (width - blockWidth() - 2 * margin).coerceAtLeast(0f)
        val freeY = (height - blockH - 2 * margin).coerceAtLeast(0f)
        blockX = margin + Random.nextFloat() * freeX
        blockTop = margin + Random.nextFloat() * freeY
        phaseStart = t
        placed = true
    }

    companion object {
        private const val TEXT_COLOR = 0xFFA8A8A8.toInt()
        private const val MAX_ALPHA = 200
        private const val HOLD_S = 15f
        private const val FADE_S = 1.2f
    }
}
