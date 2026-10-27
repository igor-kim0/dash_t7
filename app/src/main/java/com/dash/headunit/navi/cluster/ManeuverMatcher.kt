package com.dash.headunit.navi.cluster

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log

/**
 * Тип манёвра по картинке стрелки — сравнением с собственными стрелками
 * Яндекс Навигатора (drawable context_ra_*, 14.8.4: белые векторы 32dp).
 * Ими же рисуются стрелка в фоновом уведомлении (nextManeuverViewTinted) и
 * в карточке манёвра на экране (image_maneuverballoon_maneuver) — описания
 * у них нет, поэтому тип узнаём по форме.
 *
 * Форма сводится к маске [N]×[N]: рамка по «чернилам», масштаб с сохранением
 * пропорций, по центру. Сравнение — средняя разница масок ([score]).
 */
object ManeuverMatcher {

    /** Результат: манёвр (null — не узнали), оценки для диагностики. */
    class Match(val maneuver: Maneuver?, val name: String?, val score: Float, val describe: String)

    private class Template(val name: String, val maneuver: Maneuver, val mask: FloatArray)

    @Volatile private var templates: List<Template>? = null

    /** Шаблоны из ресурсов навигатора (один раз; пусто — навигатора нет). */
    private fun templates(ctx: Context): List<Template> {
        templates?.let { return it }
        val res = try {
            ctx.packageManager.getResourcesForApplication(YANDEX_NAVI)
        } catch (e: Exception) {
            return emptyList()
        }
        val list = NAMES.mapNotNull { (name, m) ->
            try {
                val id = res.getIdentifier(name, "drawable", YANDEX_NAVI)
                if (id == 0) return@mapNotNull null
                val d = res.getDrawable(id, null)
                val bmp = Bitmap.createBitmap(RENDER_PX, RENDER_PX, Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, RENDER_PX, RENDER_PX)
                d.draw(Canvas(bmp))
                val mask = mask(pixels(bmp), bmp.width, bmp.height, ::alphaInk) ?: return@mapNotNull null
                Template(name, m, mask)
            } catch (e: Exception) {
                Log.w(TAG, "шаблон $name", e)
                null
            }
        }
        if (list.isNotEmpty()) templates = list
        return list
    }

    /** Картинка из уведомления (форма — по альфе). */
    fun fromIcon(ctx: Context, icon: Bitmap): Match =
        match(templates(ctx), mask(pixels(icon), icon.width, icon.height, ::alphaInk))

    /** Кусок экрана со стрелкой: белая стрелка на цветной карточке. */
    fun fromScreen(ctx: Context, crop: Bitmap): Match =
        match(templates(ctx), mask(pixels(crop), crop.width, crop.height, ::whiteInk))

    private fun match(list: List<Template>, m: FloatArray?): Match {
        if (list.isEmpty()) return Match(null, null, 1f, "нет шаблонов навигатора")
        if (m == null) return Match(null, null, 1f, "стрелки на картинке нет")
        val scored = list.map { it to score(it.mask, m) }.sortedBy { it.second }
        val (best, s) = scored[0]
        // Соперник — ближайший шаблон другого вида для приборки: exit_* и
        // take_* почти одинаковы (0.04), но для TurboDog оба 7/8.
        val rival = scored.firstOrNull { ClusterTbt.turnType(it.first.maneuver) != ClusterTbt.turnType(best.maneuver) }
            ?.second ?: 1f
        val ok = s <= MAX_SCORE && rival - s >= MIN_GAP
        val top = scored.take(3).joinToString(" ") { "${it.first.name.removePrefix("context_ra_")}=%.3f".format(it.second) }
        return Match(if (ok) best.maneuver else null, best.name, s, (if (ok) "ок " else "не уверен ") + top)
    }

    private fun pixels(b: Bitmap): IntArray =
        IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** Доля «чернил» по альфе. */
    fun alphaInk(argb: Int): Float = (argb ushr 24) / 255f

    /** Доля «чернил» для белого на цветном: минимум из RGB (у синего фона он мал). */
    fun whiteInk(argb: Int): Float {
        val r = (argb shr 16) and 0xff
        val g = (argb shr 8) and 0xff
        val b = argb and 0xff
        val min = minOf(r, g, b)
        return ((min - WHITE_LO) / (WHITE_HI - WHITE_LO).toFloat()).coerceIn(0f, 1f) * alphaInk(argb)
    }

    /**
     * Маска [N]×[N]: рамка по пикселям с чернилами > 0.5, вписана с
     * сохранением пропорций по центру. null — чернил нет. Чистая функция.
     *
     * Клетка — доля «чернильных» точек из [SUB]×[SUB] выборок исходника
     * (обратное отображение: стрелка на экране бывает мельче [N]). Порог,
     * а не сама доля: полупрозрачные части стрелок (alpha 0.32) на экране
     * тонут в цвете карточки — пусть и в шаблоне их не будет.
     */
    fun mask(px: IntArray, w: Int, h: Int, ink: (Int) -> Float): FloatArray? {
        val on = BooleanArray(w * h) { ink(px[it]) > 0.5f }
        var x0 = w
        var y0 = h
        var x1 = -1
        var y1 = -1
        for (y in 0 until h) for (x in 0 until w) {
            if (on[y * w + x]) {
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
            }
        }
        if (x1 < 0) return null
        val bw = x1 - x0 + 1
        val bh = y1 - y0 + 1
        if (bw < MIN_INK_PX && bh < MIN_INK_PX) return null
        val scale = N.toFloat() / maxOf(bw, bh)
        val offX = (N - bw * scale) / 2f
        val offY = (N - bh * scale) / 2f
        val out = FloatArray(N * N)
        for (cy in 0 until N) for (cx in 0 until N) {
            var hits = 0
            for (j in 0 until SUB) for (i in 0 until SUB) {
                val sx = (x0 + (cx + (i + 0.5f) / SUB - offX) / scale).toInt()
                val sy = (y0 + (cy + (j + 0.5f) / SUB - offY) / scale).toInt()
                if (sx in x0..x1 && sy in y0..y1 && on[sy * w + sx]) hits++
            }
            out[cy * N + cx] = hits / (SUB * SUB).toFloat()
        }
        return out
    }

    /** Средняя разница масок, 0 — одинаковые. */
    fun score(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += kotlin.math.abs(a[i] - b[i])
        return s / a.size
    }

    /** Стрелки навигатора → наш тип (exit — съезд, take — «держитесь»). */
    private val NAMES = listOf(
        "context_ra_forward" to Maneuver.STRAIGHT,
        "context_ra_finish" to Maneuver.FINISH,
        "context_ra_turn_left" to Maneuver.LEFT,
        "context_ra_turn_right" to Maneuver.RIGHT,
        "context_ra_hard_turn_left" to Maneuver.SHARP_LEFT,
        "context_ra_hard_turn_right" to Maneuver.SHARP_RIGHT,
        "context_ra_take_left" to Maneuver.KEEP_LEFT,
        "context_ra_take_right" to Maneuver.KEEP_RIGHT,
        "context_ra_exit_left" to Maneuver.SLIGHT_LEFT,
        "context_ra_exit_right" to Maneuver.SLIGHT_RIGHT,
        "context_ra_turn_back_left" to Maneuver.UTURN_LEFT,
        "context_ra_turn_back_right" to Maneuver.UTURN_RIGHT,
        "context_ra_in_circular_movement" to Maneuver.ROUNDABOUT,
        "context_ra_out_circular_movement" to Maneuver.ROUNDABOUT,
    )

    const val N = 32
    private const val SUB = 4
    private const val RENDER_PX = 128
    private const val MIN_INK_PX = 6
    private const val WHITE_LO = 140
    private const val WHITE_HI = 230
    /**
     * Подобрано: снимок ГУ (разворот 0.057, следующий 0.309); шаблоны,
     * уменьшенные до 24–64 px на синем, ≤ 0.132; разных видов между собой ≥ 0.17.
     */
    private const val MAX_SCORE = 0.2f
    private const val MIN_GAP = 0.06f
    private const val TAG = "ManeuverMatcher"
}
