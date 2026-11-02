package com.dash.headunit.screensaver

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Фон «Камин»: широкая полоса живого пламени на чёрном фоне, внизу его
 * отражение в глянцевой поверхности.
 *
 * Огонь — клеточная модель «тепла» (классический demoscene-огонь): две
 * нижние строки — источник жара, каждый шаг клетка получает среднее
 * соседей снизу минус «остывание» из карты шума, которая прокручивается
 * вверх. Шум размыт сильно по вертикали и слабо по горизонтали — из его
 * вытянутых полос получаются тонкие языки. Сетка мелкая (~200 клеток в
 * ширину) и растягивается на экран с фильтрацией.
 */
class FireplaceView(context: Context) : SaverView(context, 30) {

    private var gw = 0
    private var gh = 0
    private var heat = FloatArray(0)
    private var next = FloatArray(0)
    private var cool = FloatArray(0)
    private var coolH = 0
    private var coolOffset = 0
    private var pixels = IntArray(0)
    private var bitmap: Bitmap? = null
    // Источник жара по x: ровный, к краям полосы сходит на нет.
    private var profile = FloatArray(0)
    // К краям полосы огонь остывает быстрее — там языки ниже.
    private var sideCool = FloatArray(0)
    private var topFade = FloatArray(0)
    // Фазы медленных «волн топлива»: участки полосы то разгораются, то стихают.
    private val fuelPhase = FloatArray(FUEL_WAVES) { Random.nextFloat() * 6.28f }

    private val fireRect = RectF()
    private val src = android.graphics.Rect()
    private var baseY = 0f
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val reflectionPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = REFLECTION_ALPHA }
    private val fadePaint = Paint()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        baseY = h * BASE_Y
        val fireW = w * FIRE_W
        fireRect.set((w - fireW) / 2, (baseY - h * FIRE_H).coerceAtLeast(0f), (w + fireW) / 2, baseY)

        gw = GRID_W
        val cell = fireRect.width() / gw
        gh = (fireRect.height() / cell).toInt().coerceIn(24, 160)
        heat = FloatArray(gw * gh)
        next = FloatArray(gw * gh)
        pixels = IntArray(gw * gh)
        bitmap?.recycle()
        bitmap = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888)
        profile = FloatArray(gw) { x ->
            val edge = minOf(x, gw - 1 - x) / (gw * EDGE_FADE)
            smooth(edge.coerceIn(0f, 1f))
        }
        sideCool = FloatArray(gw) { x ->
            val u = abs((x - gw / 2f) / (gw / 2f))
            1f + 1.6f * u * u * u * u
        }
        topFade = FloatArray(gh) { y -> smooth((y / (gh * TOP_FADE)).coerceIn(0f, 1f)) }
        buildCoolMap()

        // Отражение гаснет к низу экрана.
        fadePaint.shader = LinearGradient(
            0f, baseY, 0f, baseY + fireRect.height() * REFLECTION_SCALE,
            0x30000000, 0xFF000000.toInt(), Shader.TileMode.CLAMP
        )
    }

    private fun smooth(v: Float) = v * v * (3 - 2 * v)

    /**
     * Карта остывания: случайный шум, размытый в вертикальные полосы, со
     * средним под высоту пламени ([FLAME_HEIGHT] от высоты сетки).
     */
    private fun buildCoolMap() {
        coolH = gh * 2
        var map = FloatArray(gw * coolH) { Random.nextFloat() }
        var tmp = FloatArray(map.size)
        repeat(BLUR_X) {
            for (y in 0 until coolH) {
                val row = y * gw
                for (x in 0 until gw) {
                    val l = (x + gw - 1) % gw
                    val r = (x + 1) % gw
                    tmp[row + x] = (map[row + x] * 2 + map[row + l] + map[row + r]) / 4f
                }
            }
            map = tmp.also { tmp = map }
        }
        repeat(BLUR_Y) {
            for (y in 0 until coolH) {
                val up = ((y + coolH - 1) % coolH) * gw
                val row = y * gw
                val down = ((y + 1) % coolH) * gw
                for (x in 0 until gw) {
                    tmp[row + x] = (map[row + x] * 2 + map[up + x] + map[down + x]) / 4f
                }
            }
            map = tmp.also { tmp = map }
        }
        val min = map.minOrNull() ?: 0f
        val max = map.maxOrNull() ?: 1f
        val avg = SOURCE_HEAT / (gh * FLAME_HEIGHT)
        cool = FloatArray(map.size) { i ->
            val v = (map[i] - min) / (max - min).coerceAtLeast(1e-6f)
            // Высокий контраст: между языками — провалы, где огонь гаснет сразу.
            avg * (0.08f + 6f * v * v * v * v)
        }
        coolOffset = 0
    }

    override fun draw(canvas: Canvas, t: Float, dt: Float) {
        canvas.drawColor(Color.BLACK)
        repeat(STEPS_PER_FRAME) { step(t) }
        val bmp = bitmap ?: return
        for (y in 0 until gh) {
            // У верхнего края сетки всполохи гаснут, а не срезаются.
            val k = MAX_INDEX * topFade[y]
            val row = y * gw
            for (x in 0 until gw) {
                pixels[row + x] = PALETTE[(heat[row + x] * k).toInt().coerceIn(0, MAX_INDEX)]
            }
        }
        bmp.setPixels(pixels, 0, gw, 0, 0, gw, gh)
        // Строки источника (шумная «линия жара») не рисуем.
        src.set(0, 0, gw, gh - 2)
        canvas.drawBitmap(bmp, src, fireRect, bitmapPaint)

        // Отражение: то же пламя, перевёрнутое и сплюснутое, под чёрной вуалью.
        canvas.save()
        canvas.translate(0f, baseY)
        canvas.scale(1f, -REFLECTION_SCALE)
        canvas.translate(0f, -baseY)
        canvas.drawBitmap(bmp, src, fireRect, reflectionPaint)
        canvas.restore()
        canvas.drawRect(0f, baseY, width.toFloat(), height.toFloat(), fadePaint)
        drawCornerClock(canvas, CLOCK_COLOR)
    }

    /** Шаг модели: среднее соседей снизу минус остывание из карты. */
    private fun step(t: Float) {
        for (row in intArrayOf(gh - 1, gh - 2)) {
            val base = row * gw
            for (x in 0 until gw) {
                val u = x / gw.toFloat()
                var fuel = 0f
                for (k in 0 until FUEL_WAVES) {
                    fuel += sin(u * (3f + k * 4.3f) * 6.28f + t * (0.4f + k * 0.37f) + fuelPhase[k])
                }
                val wave = 0.82f + 0.18f * fuel / FUEL_WAVES
                val hot = if (Random.nextInt(100) < 6) 1.25f else 0.8f + Random.nextFloat() * 0.25f
                heat[base + x] = profile[x] * wave * hot
            }
        }
        for (y in 0 until gh - 2) {
            val row = y * gw
            val b1 = row + gw
            val b2 = b1 + gw
            val c = ((y + coolOffset) % coolH) * gw
            next[row] = 0f
            next[row + gw - 1] = 0f
            for (x in 1 until gw - 1) {
                val v = (heat[b1 + x - 1] + heat[b1 + x] * 2 + heat[b1 + x + 1] + heat[b2 + x] * 2) / 6f -
                    cool[c + x] * sideCool[x]
                next[row + x] = if (v > 0f) v else 0f
            }
        }
        System.arraycopy(heat, (gh - 2) * gw, next, (gh - 2) * gw, gw * 2)
        val swap = heat
        heat = next
        next = swap
        coolOffset = (coolOffset + 1) % coolH
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        bitmap?.recycle()
        bitmap = null
    }

    companion object {
        private const val GRID_W = 200
        private const val STEPS_PER_FRAME = 2
        private const val MAX_INDEX = 255

        /** Линия основания пламени и размеры полосы — доли экрана. */
        private const val BASE_Y = 0.76f
        private const val FIRE_W = 0.88f
        private const val FIRE_H = 0.72f
        /** Доля ширины по краям, где источник сходит на нет. */
        private const val EDGE_FADE = 0.12f
        /** Доля высоты сверху, где пламя плавно гаснет. */
        private const val TOP_FADE = 0.2f

        /** Средний жар источника и доля высоты, на которой пламя гаснет. */
        private const val SOURCE_HEAT = 1.0f
        private const val FLAME_HEIGHT = 0.46f
        private const val BLUR_X = 1
        private const val BLUR_Y = 6
        private const val FUEL_WAVES = 3

        private const val REFLECTION_SCALE = 0.4f
        private const val REFLECTION_ALPHA = 150
        /** Тёплый приглушённый — в тон огню. */
        private const val CLOCK_COLOR = 0xD0FFD9A8.toInt()

        /** Цвет по теплу: прозрачный → тёмно-красный → оранжевый → жёлтый → белёсый. */
        private val PALETTE = IntArray(MAX_INDEX + 1) { i ->
            val stops = floatArrayOf(0f, 0.1f, 0.26f, 0.46f, 0.66f, 0.86f, 1f)
            val cols = arrayOf(
                intArrayOf(0, 0, 0, 0),
                intArrayOf(120, 12, 0, 120),
                intArrayOf(200, 45, 0, 235),
                intArrayOf(240, 110, 10, 255),
                intArrayOf(255, 170, 30, 255),
                intArrayOf(255, 215, 90, 255),
                intArrayOf(255, 245, 190, 255)
            )
            val v = i / MAX_INDEX.toFloat()
            var k = 0
            while (k < stops.size - 2 && v > stops[k + 1]) k++
            val f = ((v - stops[k]) / (stops[k + 1] - stops[k])).coerceIn(0f, 1f)
            fun mix(c: Int) = (cols[k][c] + (cols[k + 1][c] - cols[k][c]) * f).toInt()
            Color.argb(mix(3), mix(0), mix(1), mix(2))
        }
    }
}
