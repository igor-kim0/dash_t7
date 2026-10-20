package com.dash.headunit.navi.cluster

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.View

/**
 * Картинка маршрута для приборки (дисплей 1, 1920×720). Рисуется в
 * центральной полосе шириной [contentWidth] px — по бокам у приборки
 * свои стрелочные/цифровые шкалы.
 *
 *   [стрелка]  300 м               (60)
 *              ул. Ленина         знак
 *        12 км · 18 мин · 14:35   камера 60 · 350 м
 *
 * Чёрный фон: на приборке он сливается с рамкой.
 */
class ClusterGuidanceView(context: Context) : View(context) {

    var guidance: Guidance? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Ширина центральной полосы, px (настройка — adb NAVI_CLUSTER --ei width). */
    var contentWidth: Int = 1100
        set(value) {
            field = value
            invalidate()
        }

    private val white = Color.rgb(0xF2, 0xF4, 0xF7)
    private val grey = Color.rgb(0x9A, 0xA3, 0xAE)
    private val amber = Color.rgb(0xFF, 0xB3, 0x00)
    private val red = Color.rgb(0xE5, 0x39, 0x35)

    private val iconPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = PorterDuffColorFilter(white, PorterDuff.Mode.SRC_IN)
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = white
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white }
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
    private val signFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val signText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val path = Path()
    private val src = Rect()
    private val dst = RectF()

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val g = guidance ?: return
        val h = height.toFloat()
        val s = h / 720f
        val cw = contentWidth.coerceAtMost(width).toFloat()
        val left = (width - cw) / 2f

        // Стрелка манёвра: квадрат слева.
        val arrow = 300f * s
        val ax = left
        val ay = h * 0.42f - arrow / 2f
        val icon = g.maneuverIcon
        if (icon != null) {
            src.set(0, 0, icon.width, icon.height)
            val k = minOf(arrow / icon.width, arrow / icon.height)
            val iw = icon.width * k
            val ih = icon.height * k
            dst.set(ax + (arrow - iw) / 2, ay + (arrow - ih) / 2, ax + (arrow + iw) / 2, ay + (arrow + ih) / 2)
            canvas.drawBitmap(icon, src, dst, iconPaint)
        } else if (g.maneuver != null) {
            drawArrow(canvas, g.maneuver, ax, ay, arrow)
        }

        // Знак ограничения скорости — справа.
        val signR = 90f * s
        val signCx = left + cw - signR
        val signCy = h * 0.42f
        g.speedLimit?.let { drawSign(canvas, it, signCx, signCy, signR) }

        // Тексты между стрелкой и знаком.
        val tx = ax + arrow + 50f * s
        val tw = (signCx - signR - 40f * s) - tx
        bigText.textSize = 130f * s
        midText.textSize = 52f * s
        g.distance?.let { canvas.drawText(fit(it, bigText, tw), tx, h * 0.42f, bigText) }
        g.street?.let { canvas.drawText(fit(it, midText, tw), tx, h * 0.42f + 85f * s, midText) }

        // Нижняя строка: остаток маршрута и камера.
        smallText.textSize = 44f * s
        val eta = listOfNotNull(g.remainingDistance, g.remainingTime, g.arrival).joinToString("  ·  ")
        val by = h * 0.86f
        if (eta.isNotEmpty()) canvas.drawText(fit(eta, smallText, cw * 0.6f), left, by, smallText)
        if (g.cameraDistance != null) {
            val cam = listOfNotNull("Камера", g.cameraLimit?.toString(), g.cameraDistance).joinToString(" · ")
            smallText.color = amber
            val w = smallText.measureText(cam)
            canvas.drawText(cam, left + cw - w, by, smallText)
            smallText.color = grey
        }
    }

    private fun fit(text: String, paint: TextPaint, width: Float): String =
        if (width <= 0) "" else TextUtils.ellipsize(text, paint, width, TextUtils.TruncateAt.END).toString()

    private fun drawSign(canvas: Canvas, limit: Int, cx: Float, cy: Float, r: Float) {
        canvas.drawCircle(cx, cy, r, signFill)
        signRing.strokeWidth = r * 0.2f
        canvas.drawCircle(cx, cy, r - signRing.strokeWidth / 2, signRing)
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
                headPaint.style = Paint.Style.STROKE
                headPaint.strokeWidth = u * 0.9f
                canvas.drawCircle(cx, y + size * 0.45f, u * 2f, headPaint)
                headPaint.style = Paint.Style.FILL
                path.moveTo(cx, bottom)
                path.lineTo(cx, y + size * 0.45f + u * 2f)
                path.moveTo(cx + u * 1.4f, y + size * 0.45f - u * 1.4f)
                path.lineTo(cx + u * 3.2f, y + u * 2f)
                tip = Triple(cx + u * 3.2f, y + u * 2f, 35f)
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
}
