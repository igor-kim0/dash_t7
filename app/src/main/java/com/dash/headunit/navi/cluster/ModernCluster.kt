package com.dash.headunit.navi.cluster

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import androidx.core.content.ContextCompat
import com.dash.headunit.R
import com.dash.headunit.car.CarHub
import com.dash.headunit.car.ClusterPps
import com.dash.headunit.car.CoolantCalibration
import com.dash.headunit.widgets.gearIndex
import java.util.Locale

/**
 * Тема приборки «Современная» (дисплей 1, 1920×720, страница «Карты»).
 * Штатные скорость, обороты и угловые дуги спрятаны ([ClusterGauges]);
 * верхняя строка приборки (y ≈ 0–90) и нижняя (y ≈ 620–720) остаются —
 * всё своё рисуем между ними.
 *
 * По центру — один большой круг: металлический обод, сине-серый
 * циферблат, обороты 0–8 тыс. по кругу от низа по часовой (красная зона
 * с 6000), красная стрелка. Внутри — скорость, передача с плашкой режима,
 * знак ограничения слева внизу, запас хода и расход справа внизу. Слева
 * от круга по дуге вдоль обода — температура двигателя (сверху), топливо
 * (снизу) и температура за бортом между ними.
 */
class ModernCluster(private val view: ClusterGuidanceView) {

    private val white = Color.rgb(0xF2, 0xF4, 0xF7)
    private val grey = Color.rgb(0x9C, 0xA8, 0xB6)
    private val needle = Color.rgb(0xFF, 0x3B, 0x1F)
    private val red = Color.rgb(0xE5, 0x39, 0x35)
    private val amber = Color.rgb(0xFF, 0xB3, 0x00)
    private val blue = Color.rgb(0x64, 0xB5, 0xF6)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val round = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val bold = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val oval = RectF()

    private var shaderKey = 0f
    private var faceShader: Shader? = null
    private var bezelShader: Shader? = null
    private var sideShader: Shader? = null

    private val thermo: Drawable? = ContextCompat.getDrawable(view.context, R.drawable.ic_thermo)?.mutate()
    private val fuel: Drawable? = ContextCompat.getDrawable(view.context, R.drawable.ic_fuel)?.mutate()

    fun draw(canvas: Canvas, g: Guidance, speed: Float?, rpm: Float?, shownSpeed: Int?) {
        canvas.drawColor(Color.BLACK)
        val s = view.height / 720f
        val cx = view.width / 2f
        val cy = DIAL_CY * s
        val r = DIAL_R * s
        if (shaderKey != cx * 10_000 + r) makeShaders(cx, cy, r)
        drawSide(canvas, cx, cy, r, s)
        drawFace(canvas, cx, cy, r, s)
        drawScale(canvas, cx, cy, s)
        drawNeedle(canvas, cx, cy, r, s, rpm)
        drawCenter(canvas, cx, cy, s, shownSpeed ?: speed?.let { Math.round(it) })
        drawInfo(canvas, cx, cy, r, s)
        g.speedLimit?.let {
            view.drawSign(canvas, it, g.overspeed, cx + r + SIGN_GAP * s + SIGN_R * s, cy, SIGN_R * s)
        }
    }

    private fun makeShaders(cx: Float, cy: Float, r: Float) {
        shaderKey = cx * 10_000 + r
        faceShader = RadialGradient(
            cx, cy - r * 0.15f, r,
            intArrayOf(Color.rgb(0x4A, 0x5D, 0x73), Color.rgb(0x2A, 0x38, 0x48), Color.rgb(0x10, 0x16, 0x1E)),
            floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP
        )
        // Металл: светлые блики справа-снизу и слева-сверху.
        bezelShader = SweepGradient(
            cx, cy,
            intArrayOf(
                Color.rgb(0x5A, 0x60, 0x68), Color.rgb(0xE8, 0xEC, 0xF0), Color.rgb(0x6A, 0x70, 0x78),
                Color.rgb(0x30, 0x34, 0x3A), Color.rgb(0xC8, 0xCE, 0xD4), Color.rgb(0x50, 0x56, 0x5E),
                Color.rgb(0x5A, 0x60, 0x68)
            ),
            floatArrayOf(0f, 0.12f, 0.3f, 0.5f, 0.66f, 0.85f, 1f)
        )
        sideShader = RadialGradient(
            cx - r * GLOW_SHIFT, cy, r * (1 + SIDE_OUT),
            intArrayOf(Color.rgb(0x18, 0x20, 0x2B), Color.rgb(0x0B, 0x0F, 0x15), Color.argb(0, 0, 0, 0)),
            floatArrayOf(0.55f, 0.85f, 1f), Shader.TileMode.CLAMP
        )
    }

    // ── Циферблат ─────────────────────────────────────────────────────

    private fun drawFace(c: Canvas, cx: Float, cy: Float, r: Float, s: Float) {
        fill.shader = bezelShader
        c.drawCircle(cx, cy, r, fill)
        fill.shader = null
        fill.color = Color.rgb(0x08, 0x0B, 0x0F)
        c.drawCircle(cx, cy, r - BEZEL * s, fill)
        fill.shader = faceShader
        c.drawCircle(cx, cy, r - (BEZEL + 4f) * s, fill)
        fill.shader = null
        // Риски на ободе через 1000 об/мин.
        round.color = Color.rgb(0x20, 0x24, 0x2A)
        round.strokeWidth = 4f * s
        val r0 = r - 4f * s
        val r1 = r - (BEZEL - 4f) * s
        for (i in 0..(RPM_MAX / 1000f).toInt()) {
            val (cos, sin) = dir(i * 1000f)
            c.drawLine(cx + cos * r0, cy + sin * r0, cx + cos * r1, cy + sin * r1, round)
        }
    }

    /** Красная зона по внутренней кромке обода, риски и цифры оборотов. */
    private fun drawScale(c: Canvas, cx: Float, cy: Float, s: Float) {
        val face = DIAL_R * s - (BEZEL + 4f) * s
        val zr = face - 7f * s
        oval.set(cx - zr, cy - zr, cx + zr, cy + zr)
        stroke.color = red
        stroke.strokeWidth = 10f * s
        val a0 = START + SWEEP * RED_RPM / RPM_MAX
        c.drawArc(oval, a0, START + SWEEP - a0, false, stroke)

        val outer = face - 16f * s
        var v = 0f
        while (v <= RPM_MAX + 1f) {
            val major = v % 1000f == 0f
            val (cos, sin) = dir(v)
            val len = (if (major) 18f else 9f) * s
            round.color = if (v >= RED_RPM) red else white
            round.strokeWidth = (if (major) 4f else 2f) * s
            c.drawLine(cx + cos * outer, cy + sin * outer, cx + cos * (outer - len), cy + sin * (outer - len), round)
            if (major) {
                bold.textSize = 44f * s
                bold.color = if (v >= RED_RPM) red else white
                val tr = outer - len - 34f * s
                val fm = bold.fontMetrics
                c.drawText((v / 1000f).toInt().toString(), cx + cos * tr, cy + sin * tr - (fm.ascent + fm.descent) / 2, bold)
            }
            v += 250f
        }
        // Подпись шкалы — в нижнем разрыве, между 0 и 8.
        text.color = grey
        text.textSize = 20f * s
        c.drawText("×1000 об/мин", cx, cy + 150f * s, text)
    }

    private fun drawNeedle(c: Canvas, cx: Float, cy: Float, r: Float, s: Float, rpm: Float?) {
        if (rpm == null) return
        val (cos, sin) = dir(rpm.coerceIn(0f, RPM_MAX))
        val r0 = r * NEEDLE_FROM
        val r1 = r - (BEZEL + 14f) * s
        round.color = needle
        round.alpha = 70
        round.strokeWidth = 22f * s
        c.drawLine(cx + cos * r0, cy + sin * r0, cx + cos * r1, cy + sin * r1, round)
        round.alpha = 255
        round.strokeWidth = 9f * s
        c.drawLine(cx + cos * r0, cy + sin * r0, cx + cos * r1, cy + sin * r1, round)
        round.color = Color.argb(160, 255, 210, 200)
        round.strokeWidth = 2.5f * s
        c.drawLine(cx + cos * r0, cy + sin * r0, cx + cos * r1, cy + sin * r1, round)
    }

    /** Скорость, км/ч, черта, передача и плашка режима. */
    private fun drawCenter(c: Canvas, cx: Float, cy: Float, s: Float, speed: Int?) {
        bold.color = white
        bold.textSize = 120f * s
        val top = cy - 40f * s
        c.drawText(speed?.toString() ?: "--", cx, top, bold)
        text.color = white
        text.textSize = 28f * s
        c.drawText("км/ч", cx, top + 34f * s, text)
        stroke.color = Color.argb(150, 255, 255, 255)
        stroke.strokeWidth = 2f * s
        c.drawLine(cx - 90f * s, top + 50f * s, cx + 90f * s, top + 50f * s, stroke)

        val gi = gearIndex(CarHub.data?.gearRaw)
        if (gi < 0) return
        val label = GEARS[gi]
        val drive = gi >= 3
        bold.textSize = 80f * s
        bold.color = if (label == "R") amber else white
        val gy = top + 122f * s
        c.drawText(if (drive) "D" else label, cx, gy, bold)
        if (drive) {
            // Номер передачи мелко справа от D.
            bold.textSize = 36f * s
            bold.textAlign = Paint.Align.LEFT
            c.drawText(label, cx + 32f * s, gy, bold)
            bold.textAlign = Paint.Align.CENTER
        }
        val badge = when (label) {
            "P" -> "ПАРКОВКА"
            "R" -> "НАЗАД"
            "N" -> "НЕЙТРАЛЬ"
            else -> "АВТО"
        }
        text.textSize = 22f * s
        val bw = text.measureText(badge) + 22f * s
        val by = top + 136f * s
        oval.set(cx - bw / 2, by, cx + bw / 2, by + 30f * s)
        fill.color = white
        c.drawRoundRect(oval, 6f * s, 6f * s, fill)
        text.color = Color.rgb(0x1A, 0x22, 0x2C)
        c.drawText(badge, cx, by + 23f * s, text)
    }

    /** Справа внизу циферблата: запас хода и средний расход. */
    private fun drawInfo(c: Canvas, cx: Float, cy: Float, r: Float, s: Float) {
        val t = ClusterPps.trip()
        val range = t?.rangeKm ?: CarHub.data?.rangeKm
        val avg = CarHub.tripView?.avgCons ?: t?.avgFuelL100
        val rows = listOfNotNull(
            range?.let { "ЗАПАС" to "$it км" },
            avg?.let { "РАСХОД" to "%.1f л".format(RU, it) },
        )
        if (rows.isEmpty()) return
        // Строки «ЗАПАС  364 км» по центру, ниже цифр 0 и 8.
        var y = cy + INFO_Y * s
        val gap = 12f * s
        for ((label, value) in rows) {
            text.textSize = 20f * s
            val lw = text.measureText(label)
            text.textSize = 30f * s
            val vw = text.measureText(value)
            var x = cx - (lw + gap + vw) / 2f
            text.textAlign = Paint.Align.LEFT
            text.color = grey
            text.textSize = 20f * s
            c.drawText(label, x, y, text)
            x += lw + gap
            text.color = white
            text.textSize = 30f * s
            c.drawText(value, x, y, text)
            text.textAlign = Paint.Align.CENTER
            y += INFO_STEP * s
        }
    }

    // ── Слева: двигатель, за бортом, топливо ─────────────────────────

    private fun drawSide(c: Canvas, cx: Float, cy: Float, r: Float, s: Float) {
        // Тёмная «линза» за дугами, плавно уходящая в чёрный.
        fill.shader = sideShader
        c.drawCircle(cx - r * GLOW_SHIFT, cy, r * (1 + SIDE_OUT), fill)
        fill.shader = null

        val ar = r + ARC_GAP * s
        val d = CarHub.data

        // Двигатель: снизу вверх 40 → 130 °C (по часовой на левой стороне — вверх).
        val exact = d?.engineTempC
        val eng = exact ?: ClusterPps.needle()?.let { CoolantCalibration.estimate(it) }
        sideArc(
            c, cx, cy, ar, s, ENG_FROM,
            eng?.let { ((it - TEMP_MIN) / (TEMP_MAX - TEMP_MIN)).coerceIn(0f, 1f) },
            eng?.let { if (it >= 115f) red else if (it < 60f) blue else white } ?: grey,
            listOf("40", "70", "100", "130°C"), thermo
        )
        // Топливо: снизу вверх E → 1/1.
        val pct = d?.fuelPct
        sideArc(
            c, cx, cy, ar, s, FUEL_FROM,
            pct?.let { (it / 100f).coerceIn(0f, 1f) },
            pct?.let { if (it < 10f) red else if (it < 20f) amber else white } ?: grey,
            listOf("E", "1/2", "1/1"), fuel
        )
        // За бортом — в разрыве между дугами.
        val out = ClusterPps.outsideTempC() ?: d?.outsideTempC
        bold.color = white
        bold.textSize = 40f * s
        bold.textAlign = Paint.Align.RIGHT
        val fm = bold.fontMetrics
        c.drawText(out?.let { "${Math.round(it)}°C" } ?: "--°C", cx - ar + 8f * s, cy - (fm.ascent + fm.descent) / 2, bold)
        bold.textAlign = Paint.Align.CENTER
    }

    /**
     * Дуга-указатель вдоль обода: от угла [from] по часовой на [ARC_SWEEP]
     * (на левой стороне — снизу вверх), заполнение [frac], подписи [labels]
     * равномерно снаружи, значок слева от середины дуги.
     */
    private fun sideArc(
        c: Canvas, cx: Float, cy: Float, ar: Float, s: Float, from: Float,
        frac: Float?, color: Int, labels: List<String>, icon: Drawable?
    ) {
        oval.set(cx - ar, cy - ar, cx + ar, cy + ar)
        stroke.strokeWidth = 12f * s
        stroke.color = Color.rgb(0x26, 0x2E, 0x38)
        c.drawArc(oval, from, ARC_SWEEP, false, stroke)
        if (frac != null && frac > 0.01f) {
            stroke.color = color
            c.drawArc(oval, from, ARC_SWEEP * frac, false, stroke)
        }
        text.color = grey
        text.textSize = 22f * s
        for ((i, label) in labels.withIndex()) {
            val a = Math.toRadians((from + ARC_SWEEP * i / (labels.size - 1)).toDouble())
            val cos = Math.cos(a).toFloat()
            val sin = Math.sin(a).toFloat()
            round.color = grey
            round.strokeWidth = 2f * s
            c.drawLine(cx + cos * (ar + 8f * s), cy + sin * (ar + 8f * s), cx + cos * (ar + 18f * s), cy + sin * (ar + 18f * s), round)
            text.textAlign = Paint.Align.RIGHT
            c.drawText(label, cx + cos * (ar + 24f * s), cy + sin * (ar + 24f * s) + 8f * s, text)
            text.textAlign = Paint.Align.CENTER
        }
        icon?.let {
            val a = Math.toRadians((from + ARC_SWEEP / 2).toDouble())
            val ir = ar + 110f * s
            val ix = cx + Math.cos(a).toFloat() * ir
            val iy = cy + Math.sin(a).toFloat() * ir
            val half = 18f * s
            it.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
            it.setBounds((ix - half).toInt(), (iy - half).toInt(), (ix + half).toInt(), (iy + half).toInt())
            it.draw(c)
        }
    }

    /** Направление на значение [rpm] шкалы оборотов. */
    private fun dir(rpm: Float): Pair<Float, Float> {
        val a = Math.toRadians((START + SWEEP * rpm / RPM_MAX).toDouble())
        return Math.cos(a).toFloat() to Math.sin(a).toFloat()
    }

    companion object {
        /** Шкала оборотов: 0 слева внизу, по часовой на 300°; внизу разрыв 60°. */
        const val START = 120f
        const val SWEEP = 300f
        const val RPM_MAX = 8000f
        const val RED_RPM = 6000f
        const val SPEED_MAX = 220f
        /** Круг между верхней (до 90) и нижней (с 620) строками приборки. */
        const val DIAL_CY = 355f
        const val DIAL_R = 262f
        const val BEZEL = 22f
        /** Стрелка — от этой доли радиуса до обода (центр занят цифрами). */
        const val NEEDLE_FROM = 0.42f
        /** Знак ограничения — справа от обода; запас/расход — в нижнем разрыве шкалы. */
        const val SIGN_GAP = 60f
        const val SIGN_R = 52f
        const val INFO_Y = 186f
        const val INFO_STEP = 34f
        /** Левые дуги: отступ от обода, угол; начало (по часовой = вверх). */
        const val ARC_GAP = 34f
        const val ARC_SWEEP = 40f
        const val ENG_FROM = 194f
        const val FUEL_FROM = 126f
        /** Насколько тёмный фон слева выходит за обод, доля радиуса. */
        const val SIDE_OUT = 0.55f
        /** Центр мягкой подсветки сдвинут влево — за левые дуги. */
        const val GLOW_SHIFT = 0.3f
        const val TEMP_MIN = 40f
        const val TEMP_MAX = 130f
        private val GEARS = listOf("R", "N", "P", "1", "2", "3", "4", "5", "6", "7")
        private val RU = Locale("ru")
    }
}
