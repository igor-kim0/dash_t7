package com.dash.headunit.car

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.Locale

/**
 * Калибровка стрелки ОЖ приборки (a000, 0..1) по точным °C OBD-сканера.
 *
 * Пока сканер на связи, каждый опрос [CarHub] даёт пару «стрелка → °C»
 * ([observe]); пары копятся по корзинам положения стрелки. Без сканера
 * градусы оцениваются по выученной таблице ([estimate]), а где её нет —
 * по заводской кривой [CarScaling.needleToCoolant].
 *
 * Стрелка T18FL3 на прогретом двигателе стоит на середине (500/1000) во
 * всём рабочем диапазоне — корзина «500» выучит среднюю рабочую температуру,
 * точнее по стрелке не получить. Таблица хранится в SharedPreferences.
 */
object CoolantCalibration {

    private val table = NeedleCalibration()
    private var appContext: Context? = null
    private var dirty = false
    private var savedAt = 0L

    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        val stored = prefs(app).getString(KEY_TABLE, null)
        synchronized(table) { table.load(stored) }
    }

    /** Пара «стрелка приборки ([needle] 0..1) → °C сканера»; null — пропуск. */
    fun observe(needle: Float?, coolantC: Float?) {
        if (needle == null || coolantC == null) return
        val now = SystemClock.uptimeMillis()
        val text = synchronized(table) {
            if (!table.add(needle, coolantC)) return
            dirty = true
            if (now - savedAt < SAVE_MS) return
            dirty = false
            savedAt = now
            table.save()
        }
        appContext?.let { prefs(it).edit().putString(KEY_TABLE, text).apply() }
    }

    /** °C по стрелке: выученная таблица, иначе заводская кривая. */
    fun estimate(needle: Float): Float =
        synchronized(table) { table.estimate(needle, CarScaling::needleToCoolant) }

    /** Забыть выученное. */
    fun reset() {
        synchronized(table) { table.load(null) }
        appContext?.let { prefs(it).edit().remove(KEY_TABLE).apply() }
        Log.i(TAG, "калибровка сброшена")
    }

    /** «стрелка‰ → °C (пар)» по выученным корзинам — для отладки. */
    fun describe(): String = synchronized(table) { table.describe() }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val TAG = "CoolantCalibration"
    private const val PREFS = "coolant_calibration"
    private const val KEY_TABLE = "table"
    /** Сохранять не чаще. */
    private const val SAVE_MS = 60_000L
}

/**
 * Таблица «положение стрелки → °C» без Android: корзины по [BIN] промилле,
 * в каждой — скользящее среднее (вес последних ~[CAP] пар).
 */
class NeedleCalibration {

    private val mean = FloatArray(BINS)
    private val count = IntArray(BINS)

    /** false — пара отброшена (стрелка на нуле / вне шкалы, °C неправдоподобны). */
    fun add(needle: Float, coolantC: Float): Boolean {
        if (needle.isNaN() || needle <= 0f || needle > 1f) return false
        if (coolantC.isNaN() || coolantC < -40f || coolantC > 150f) return false
        val i = bin(needle)
        val n = (count[i] + 1).coerceAtMost(CAP)
        mean[i] += (coolantC - mean[i]) / n
        if (count[i] < Int.MAX_VALUE) count[i]++
        return true
    }

    /**
     * °C по стрелке. Между выученными корзинами — линейно; по стрелке не
     * убывает (шум соседних корзин сглаживается накопленным максимумом).
     * Вне выученного диапазона — [fallback], но не холоднее/горячее
     * крайней выученной точки, чтобы на границе не было скачка назад.
     */
    fun estimate(needle: Float, fallback: (Float) -> Float): Float {
        val pts = points()
        if (pts.isEmpty() || needle.isNaN()) return fallback(needle)
        val x = needle.coerceIn(0f, 1f) * 1000f
        val (xFirst, yFirst) = pts.first()
        val (xLast, yLast) = pts.last()
        // Половина корзины по краям — ещё «выучено».
        if (x < xFirst - BIN / 2f) return minOf(fallback(needle), yFirst)
        if (x > xLast + BIN / 2f) return maxOf(fallback(needle), yLast)
        if (x <= xFirst) return yFirst
        if (x >= xLast) return yLast
        for (k in 1 until pts.size) {
            val (x1, y1) = pts[k]
            if (x <= x1) {
                val (x0, y0) = pts[k - 1]
                return y0 + (x - x0) / (x1 - x0) * (y1 - y0)
            }
        }
        return yLast
    }

    /** Выученные точки (центр корзины ‰, °C), монотонные. */
    private fun points(): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>()
        var max = Float.NEGATIVE_INFINITY
        for (i in 0 until BINS) {
            if (count[i] < MIN_SAMPLES) continue
            max = maxOf(max, mean[i])
            out.add(i * BIN.toFloat() to max)
        }
        return out
    }

    fun describe(): String {
        val parts = (0 until BINS).filter { count[it] > 0 }.map {
            String.format(Locale.US, "%d‰→%.1f°(%d)", it * BIN, mean[it], count[it])
        }
        return if (parts.isEmpty()) "не выучено" else parts.joinToString(" ")
    }

    /** «i:среднее:пар;…». */
    fun save(): String = (0 until BINS).filter { count[it] > 0 }
        .joinToString(";") { String.format(Locale.US, "%d:%.2f:%d", it, mean[it], count[it]) }

    fun load(text: String?) {
        mean.fill(0f)
        count.fill(0)
        text?.split(';')?.forEach { part ->
            val f = part.split(':')
            val i = f.getOrNull(0)?.toIntOrNull() ?: return@forEach
            val m = f.getOrNull(1)?.toFloatOrNull() ?: return@forEach
            val c = f.getOrNull(2)?.toIntOrNull() ?: return@forEach
            if (i in 0 until BINS && c > 0) {
                mean[i] = m
                count[i] = c
            }
        }
    }

    private fun bin(needle: Float): Int = Math.round(needle * 1000f / BIN).coerceIn(0, BINS - 1)

    companion object {
        /** Ширина корзины, ‰ шкалы стрелки; 500 (прогретый) — центр корзины. */
        const val BIN = 25
        const val BINS = 1000 / BIN + 1
        /** Сколько пар нужно корзине, чтобы ей верить (опрос раз в 5 с). */
        const val MIN_SAMPLES = 3
        /** Предел веса: среднее следует за последними ~CAP парами. */
        const val CAP = 120
    }
}
