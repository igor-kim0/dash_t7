package com.dash.headunit.widgets

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import com.dash.headunit.theme.DashTheme

/**
 * Перекидные часы в стиле HTC Sense: две карточки «ЧЧ» и «ММ»
 * с линией разлома посередине. При смене значения верхняя половина
 * карточки «падает» вниз — как настоящий флип.
 *
 * Карточки вписываются в размер view с сохранением пропорций.
 * Цвета берутся из [theme]: обычный вид — светлые карточки (оттенок
 * текста темы) с цифрами цвета фона; [inverted] — карточки цвета
 * виджетов темы со светлыми цифрами. Петли на линии разлома — акцент темы.
 */
class FlipClockView(
    context: Context,
    theme: DashTheme = DashTheme.DARK,
    bold: Boolean = false,
    private val inverted: Boolean = false
) : View(context) {

    /** Цвета карточки, выведенные из темы. */
    private class Palette(
        val topFrom: Int, val topTo: Int,
        val bottomFrom: Int, val bottomTo: Int,
        val digits: IntArray,
        val pageFar: Int, val pageNear: Int,
        val line: Int, val lineGlint: Int, val hinge: Int
    )

    private val pal = palette(theme, inverted)

    /** Состояние одной карточки: текущее и предыдущее значение + прогресс флипа. */
    private class Card {
        var value = ""
        var old = ""
        var progress = 1f
        var animator: ValueAnimator? = null
    }

    private val cards = arrayOf(Card(), Card())

    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val digitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val cardRect = RectF()
    private val tmp = RectF()

    /** Новое время; при смене цифр карточка перелистывается (если view на экране). */
    fun setTime(hours: String, minutes: String) {
        update(cards[0], hours)
        update(cards[1], minutes)
    }

    private fun update(card: Card, value: String) {
        if (card.value == value) return
        val animate = card.value.isNotEmpty() && isAttachedToWindow
        card.old = card.value
        card.value = value
        card.animator?.cancel()
        if (!animate) {
            card.progress = 1f
            invalidate()
            return
        }
        card.progress = 0f
        card.animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FLIP_MS
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                card.progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        cards.forEach { it.animator?.cancel(); it.progress = 1f }
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width - paddingLeft - paddingRight
        val h = height - paddingTop - paddingBottom
        if (w <= 0 || h <= 0) return

        // Две карточки + зазор; пропорция карточки ≈ HTC (чуть уже квадрата).
        val gapRatio = 0.08f
        val aspect = CARD_ASPECT
        var cardW = w / (2f + gapRatio)
        var cardH = cardW / aspect
        if (cardH > h) {
            cardH = h.toFloat()
            cardW = cardH * aspect
        }
        val gap = cardW * gapRatio
        val totalW = cardW * 2 + gap
        val left0 = paddingLeft + (w - totalW) / 2f
        val top = paddingTop + (h - cardH) / 2f

        digitPaint.textSize = cardH * 0.78f
        // Двузначное число должно влезать по ширине.
        val textW = digitPaint.measureText("00")
        if (textW > cardW * 0.92f) digitPaint.textSize *= cardW * 0.92f / textW

        cards.forEachIndexed { i, card ->
            val l = left0 + i * (cardW + gap)
            cardRect.set(l, top, l + cardW, top + cardH)
            drawCard(canvas, cardRect, card)
        }
    }

    private fun drawCard(canvas: Canvas, r: RectF, card: Card) {
        val radius = r.width() * 0.07f
        // «Стопка» листов под карточкой — пара полосок снизу.
        val pageStep = r.height() * 0.012f
        val faceRect = RectF(r.left, r.top, r.right, r.bottom - pageStep * 2)
        for (k in 2 downTo 1) {
            pagePaint.color = if (k == 2) pal.pageFar else pal.pageNear
            tmp.set(faceRect.left + k, faceRect.top + pageStep * k, faceRect.right - k, faceRect.bottom + pageStep * k)
            canvas.drawRoundRect(tmp, radius, radius, pagePaint)
        }

        val midY = faceRect.centerY()
        val p = card.progress
        if (p >= 1f) {
            drawHalf(canvas, faceRect, radius, card.value, top = true)
            drawHalf(canvas, faceRect, radius, card.value, top = false)
        } else {
            // Статика: сверху уже новое значение, снизу ещё старое.
            drawHalf(canvas, faceRect, radius, card.value, top = true)
            drawHalf(canvas, faceRect, radius, card.old, top = false)
            canvas.save()
            if (p < 0.5f) {
                // Верхняя половина старого значения складывается к линии разлома.
                val s = 1f - p * 2f
                canvas.scale(1f, s, faceRect.centerX(), midY)
                drawHalf(canvas, faceRect, radius, card.old, top = true, shade = p * 2f)
            } else {
                // Нижняя половина нового значения раскрывается от линии вниз.
                val s = (p - 0.5f) * 2f
                canvas.scale(1f, s, faceRect.centerX(), midY)
                drawHalf(canvas, faceRect, radius, card.value, top = false, shade = 1f - s)
            }
            canvas.restore()
        }

        // Линия разлома с «петлями» по краям.
        val lineH = (r.height() * 0.012f).coerceAtLeast(2f)
        linePaint.color = pal.line
        canvas.drawRect(faceRect.left, midY - lineH / 2, faceRect.right, midY + lineH / 2, linePaint)
        linePaint.color = pal.lineGlint
        canvas.drawRect(faceRect.left, midY + lineH / 2, faceRect.right, midY + lineH, linePaint)
        linePaint.color = pal.hinge
        val hingeW = r.width() * 0.025f
        val hingeH = r.height() * 0.06f
        canvas.drawRect(faceRect.left, midY - hingeH / 2, faceRect.left + hingeW, midY + hingeH / 2, linePaint)
        canvas.drawRect(faceRect.right - hingeW, midY - hingeH / 2, faceRect.right, midY + hingeH / 2, linePaint)
    }

    /**
     * Половина карточки: фон-градиент + цифры, обрезанные по линии разлома.
     * [shade] 0..1 — затемнение листа во время поворота.
     */
    private fun drawHalf(
        canvas: Canvas,
        face: RectF,
        radius: Float,
        text: String,
        top: Boolean,
        shade: Float = 0f
    ) {
        val midY = face.centerY()
        canvas.save()
        if (top) canvas.clipRect(face.left, face.top, face.right, midY)
        else canvas.clipRect(face.left, midY, face.right, face.bottom)

        facePaint.shader = if (top) {
            LinearGradient(0f, face.top, 0f, midY, pal.topFrom, pal.topTo, Shader.TileMode.CLAMP)
        } else {
            LinearGradient(0f, midY, 0f, face.bottom, pal.bottomFrom, pal.bottomTo, Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(face, radius, radius, facePaint)

        // Цифры с лёгким градиентом к низу — как глянец у HTC.
        digitPaint.shader = LinearGradient(
            0f, face.top, 0f, face.bottom,
            pal.digits,
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        val fm = digitPaint.fontMetrics
        val baseline = midY - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text, face.centerX(), baseline, digitPaint)

        if (shade > 0f) {
            shadePaint.color = Color.argb((shade * 140).toInt().coerceIn(0, 255), 0, 0, 0)
            canvas.drawRoundRect(face, radius, radius, shadePaint)
        }
        canvas.restore()
    }

    private companion object {
        const val FLIP_MS = 500L

        fun palette(t: DashTheme, inverted: Boolean): Palette {
            val bg = opaque(t.background, Color.BLACK)
            // У тем с полупрозрачными виджетами (NAVY) карточка — поверх фона.
            val card = opaque(t.cardBackground, bg)
            val text = opaque(t.primaryText, bg)
            return if (inverted) Palette(
                topFrom = mix(card, text, 0.14f), topTo = mix(card, text, 0.06f),
                bottomFrom = mix(card, text, 0.08f), bottomTo = mix(card, Color.BLACK, 0.25f),
                digits = intArrayOf(text, text, mix(text, card, 0.3f)),
                pageFar = mix(bg, Color.BLACK, 0.4f), pageNear = mix(card, Color.BLACK, 0.3f),
                line = mix(bg, Color.BLACK, 0.5f), lineGlint = 0x22FFFFFF, hinge = t.accent
            ) else Palette(
                topFrom = mix(text, Color.WHITE, 0.6f), topTo = mix(text, card, 0.08f),
                bottomFrom = mix(text, Color.WHITE, 0.3f), bottomTo = mix(text, card, 0.18f),
                digits = intArrayOf(bg, mix(bg, card, 0.5f), mix(bg, text, 0.3f)),
                pageFar = mix(text, bg, 0.45f), pageNear = mix(text, bg, 0.25f),
                line = mix(card, Color.BLACK, 0.2f), lineGlint = 0x66FFFFFF, hinge = t.accent
            )
        }

        /** Линейное смешение цветов: t=0 → [a], t=1 → [b]. */
        fun mix(a: Int, b: Int, t: Float): Int = Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
        )

        /** Полупрозрачный [c] поверх непрозрачного [under]. */
        fun opaque(c: Int, under: Int): Int = mix(under, c, Color.alpha(c) / 255f)
        /** Ширина / высота карточки. */
        const val CARD_ASPECT = 0.95f
    }
}
