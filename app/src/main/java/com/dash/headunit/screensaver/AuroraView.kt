package com.dash.headunit.screensaver

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Фон «Северное сияние»: ночное небо с мерцающими звёздами, по нему
 * медленно колышутся зелёно-фиолетовые полосы сияния с лучами, внизу
 * силуэт хвойного леса, изредка пролетает падающая звезда.
 *
 * Сияние считается в мелкой сетке (~160 клеток в ширину) и растягивается
 * на экран с фильтрацией: полосы — сумма синусоид по x и времени, яркость
 * резко обрывается у нижней кромки и плавно гаснет вверх, цвет от
 * зелёного у кромки к фиолетовому вверху.
 */
class AuroraView(context: Context) : SaverView(context, 20) {

    private var aw = 0
    private var ah = 0
    private var pixels = IntArray(0)
    private var bitmap: Bitmap? = null
    private val dst = Rect()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private var skyR = FloatArray(0)
    private var skyG = FloatArray(0)
    private var skyB = FloatArray(0)
    // Лучи: у каждого столбца своя сила и фаза мерцания.
    private var rayGain = FloatArray(0)
    private var rayPhase = FloatArray(0)

    private class Star(val x: Float, val y: Float, val r: Float, val base: Float, val speed: Float, val phase: Float)
    private var stars = emptyList<Star>()
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    private val forest = Path()
    private val forestPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF020407.toInt() }

    private var meteorAt = 6f
    private var meteorX = 0f
    private var meteorY = 0f
    private var meteorDx = 0f
    private var meteorDy = 0f
    private val meteorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }

    /** Полоса сияния: высота кромки, волны по x, скорость, длина «хвоста» вверх. */
    private class Curtain(
        val base: Float, val f1: Float, val f2: Float, val s1: Float, val s2: Float,
        val amp1: Float, val amp2: Float, val len: Float, val gain: Float, val shift: Float
    )

    private val curtains = listOf(
        Curtain(0.62f, 1.3f, 3.1f, 0.10f, -0.17f, 0.10f, 0.04f, 0.3f, 1.0f, 0f),
        Curtain(0.45f, 1.9f, 4.3f, -0.07f, 0.13f, 0.08f, 0.03f, 0.24f, 0.7f, 2.1f),
        Curtain(0.72f, 0.9f, 2.9f, 0.05f, 0.21f, 0.06f, 0.05f, 0.2f, 0.5f, 4.3f)
    )

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        aw = GRID_W
        ah = (GRID_W * h / w.toFloat()).toInt().coerceIn(20, 120)
        pixels = IntArray(aw * ah)
        bitmap?.recycle()
        bitmap = Bitmap.createBitmap(aw, ah, Bitmap.Config.ARGB_8888)
        dst.set(0, 0, w, h)
        // Небо: почти чёрное вверху, глубокий синий у горизонта.
        skyR = FloatArray(ah) { y -> 0.004f + 0.012f * y / ah }
        skyG = FloatArray(ah) { y -> 0.008f + 0.03f * y / ah }
        skyB = FloatArray(ah) { y -> 0.025f + 0.07f * y / ah }
        rayGain = FloatArray(aw) { 0.5f + Random.nextFloat() * 0.5f }
        rayPhase = FloatArray(aw) { Random.nextFloat() * 6.28f }

        val horizon = h * 0.84f
        stars = List(STARS) {
            Star(
                x = Random.nextFloat() * w,
                y = Random.nextFloat() * horizon,
                r = (0.5f + Random.nextFloat() * Random.nextFloat() * 1.6f) * density,
                base = 0.3f + Random.nextFloat() * 0.6f,
                speed = 0.5f + Random.nextFloat() * 2.5f,
                phase = Random.nextFloat() * 6.28f
            )
        }
        buildForest(w.toFloat(), h.toFloat())
    }

    /** Холмы с ёлками: зубчатый силуэт вдоль низа экрана. */
    private fun buildForest(w: Float, h: Float) {
        forest.reset()
        forest.moveTo(0f, h)
        fun ground(x: Float) = h * (0.9f + 0.03f * sin(x / w * 5.1f) + 0.015f * sin(x / w * 13f + 1f))
        forest.lineTo(0f, ground(0f))
        var x = 0f
        while (x < w) {
            val step = h * (0.012f + Random.nextFloat() * 0.02f)
            val g = ground(x)
            if (Random.nextFloat() < 0.75f) {
                val treeH = h * (0.04f + Random.nextFloat() * 0.07f)
                val half = treeH * 0.22f
                forest.lineTo(x, g)
                // Ёлка: три яруса «лап».
                forest.lineTo(x + half * 0.2f, g - treeH * 0.35f)
                forest.lineTo(x + half * 0.1f, g - treeH * 0.35f)
                forest.lineTo(x + half * 0.55f, g - treeH * 0.7f)
                forest.lineTo(x + half * 0.45f, g - treeH * 0.7f)
                forest.lineTo(x + half, g - treeH)
                forest.lineTo(x + half * 1.55f, g - treeH * 0.7f)
                forest.lineTo(x + half * 1.45f, g - treeH * 0.7f)
                forest.lineTo(x + half * 1.9f, g - treeH * 0.35f)
                forest.lineTo(x + half * 1.8f, g - treeH * 0.35f)
                forest.lineTo(x + half * 2f, g)
                x += half * 2f
            }
            x += step
            forest.lineTo(x, ground(x))
        }
        forest.lineTo(w, h)
        forest.close()
    }

    override fun draw(canvas: Canvas, t: Float, dt: Float) {
        val bmp = bitmap ?: return
        renderAurora(t)
        bmp.setPixels(pixels, 0, aw, 0, 0, aw, ah)
        canvas.drawBitmap(bmp, null, dst, bitmapPaint)

        for (s in stars) {
            val a = s.base * (0.65f + 0.35f * sin(t * s.speed + s.phase))
            starPaint.alpha = (255 * a).toInt().coerceIn(0, 255)
            canvas.drawCircle(s.x, s.y, s.r, starPaint)
        }
        drawMeteor(canvas, t)
        canvas.drawPath(forest, forestPaint)
        drawCornerClock(canvas, CLOCK_COLOR)
    }

    private fun renderAurora(t: Float) {
        val n = curtains.size
        val edge = FloatArray(n)
        val amp = FloatArray(n)
        for (x in 0 until aw) {
            val u = x / aw.toFloat()
            for (k in 0 until n) {
                val c = curtains[k]
                edge[k] = ah * (c.base + c.amp1 * sin(u * c.f1 * 6.28f + t * c.s1 + c.shift) +
                    c.amp2 * sin(u * c.f2 * 6.28f + t * c.s2 + c.shift * 1.3f))
                // Яркость вдоль полосы переливается, лучи мерцают по столбцам.
                val wave = 0.5f + 0.5f * sin(u * 9f - t * 0.35f + c.shift)
                val ray = 0.6f + 0.4f * rayGain[x] * sin(t * 0.9f + rayPhase[x])
                // Квадрат волны — между яркими участками остаются разрывы.
                amp[k] = c.gain * (0.05f + 0.95f * wave * wave) * ray
            }
            for (y in 0 until ah) {
                var r = skyR[y]
                var g = skyG[y]
                var b = skyB[y]
                for (k in 0 until n) {
                    val d = edge[k] - y
                    val len = curtains[k].len * ah
                    val v = amp[k] * if (d >= 0f) exp(-d / len) else exp(d * 0.35f)
                    if (v < 0.003f) continue
                    // У кромки — зелёный, выше — бирюза, затем фиолетовый.
                    val m = (d / (len * 1.6f)).coerceIn(0f, 1f)
                    r += v * (0.12f + 0.4f * m * m)
                    g += v * (0.95f - 0.85f * m)
                    b += v * (0.42f + 0.3f * m)
                }
                pixels[y * aw + x] = Color.rgb(
                    (r.coerceIn(0f, 1f) * 255).toInt(),
                    (g.coerceIn(0f, 1f) * 255).toInt(),
                    (b.coerceIn(0f, 1f) * 255).toInt()
                )
            }
        }
    }

    /** Падающая звезда раз в 10–25 с: полоска с гаснущим хвостом. */
    private fun drawMeteor(canvas: Canvas, t: Float) {
        if (t < meteorAt) return
        val p = (t - meteorAt) / METEOR_S
        if (meteorDx == 0f && meteorDy == 0f) {
            // Новая звезда: летит вниз под 15–35° влево или вправо.
            meteorX = width * (0.2f + Random.nextFloat() * 0.6f)
            meteorY = height * (0.05f + Random.nextFloat() * 0.3f)
            val angle = 0.25f + Random.nextFloat() * 0.35f
            val dist = width * (0.25f + Random.nextFloat() * 0.15f)
            meteorDx = cos(angle) * dist * (if (Random.nextBoolean()) 1 else -1)
            meteorDy = sin(angle) * dist
        }
        if (p >= 1f) {
            meteorAt = t + 10f + Random.nextFloat() * 15f
            meteorDx = 0f
            meteorDy = 0f
            return
        }
        val hx = meteorX + meteorDx * p
        val hy = meteorY + meteorDy * p
        val tail = 0.35f
        val tx = hx - meteorDx * tail
        val ty = hy - meteorDy * tail
        val a = (if (p < 0.8f) 1f else (1f - p) / 0.2f).coerceIn(0f, 1f)
        meteorPaint.strokeWidth = 2f * density
        meteorPaint.shader = LinearGradient(
            hx, hy, tx, ty, Color.argb((230 * a).toInt(), 255, 255, 255), 0x00FFFFFF, Shader.TileMode.CLAMP
        )
        canvas.drawLine(hx, hy, tx, ty, meteorPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        bitmap?.recycle()
        bitmap = null
    }

    companion object {
        private const val GRID_W = 160
        private const val STARS = 180
        private const val METEOR_S = 0.9f
        /** Холодный приглушённый — в тон сиянию. */
        private const val CLOCK_COLOR = 0xC8D8F0E8.toInt()
    }
}
