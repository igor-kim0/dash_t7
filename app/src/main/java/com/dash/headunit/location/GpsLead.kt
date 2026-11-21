package com.dash.headunit.location

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Опережение метки навигатора: точка GPS, сдвинутая вперёд по курсу на
 * путь, пройденный за задержку GPS. Задержка — настройка ([leadMs]);
 * путь = скорость × (задержка + сколько прошло с прихода фикса), так что
 * между фиксами точка «едет» сама. Чистый код — тестируется без Android.
 */
object GpsLead {

    /** Ниже — не сдвигаем: на стоянке и в пробке курс GPS пляшет. */
    const val MIN_SPEED_MPS = 5f / 3.6f

    /** Дольше не экстраполируем от одного фикса (источник замолчал). */
    const val MAX_EXTRAPOLATE_MS = 2_000L

    /**
     * Сдвиг вперёд, м. [speedMps] — скорость (лучше машины, из CAN),
     * [leadMs] — настройка, [ageMs] — сколько прошло с прихода фикса.
     */
    fun distance(speedMps: Float, leadMs: Long, ageMs: Long): Double {
        if (speedMps < MIN_SPEED_MPS) return 0.0
        val t = leadMs + ageMs.coerceIn(0L, MAX_EXTRAPOLATE_MS)
        if (t <= 0) return 0.0
        return speedMps.toDouble() * t / 1000.0
    }

    /** Точка в [meters] от (lat, lon) по курсу [bearingDeg]. → (lat, lon). */
    fun project(lat: Double, lon: Double, bearingDeg: Float, meters: Double): Pair<Double, Double> {
        if (meters == 0.0) return lat to lon
        val d = meters / EARTH_M
        val b = Math.toRadians(bearingDeg.toDouble())
        val p1 = Math.toRadians(lat)
        val l1 = Math.toRadians(lon)
        val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
        val l2 = l1 + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        var lon2 = Math.toDegrees(l2)
        if (lon2 > 180) lon2 -= 360 else if (lon2 < -180) lon2 += 360
        return Math.toDegrees(p2) to lon2
    }

    /** Курс от точки 1 к точке 2, градусы 0..360. */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return ((Math.toDegrees(atan2(y, x)) + 360) % 360).toFloat()
    }

    /**
     * Оценка задержки GPS по скоростям: [gps] — (время прихода фикса мс,
     * скорость GPS км/ч), [car] — (время мс, скорость машины км/ч, часто).
     * Ищем сдвиг 0..[maxLagMs], при котором скорость GPS лучше всего
     * совпадает со скоростью машины на [lag] раньше. null — мало данных
     * или машина почти не меняла скорость (сравнивать нечего).
     */
    fun estimateLag(
        gps: List<Pair<Long, Float>>,
        car: List<Pair<Long, Float>>,
        maxLagMs: Long = 3_000L,
        stepMs: Long = 100L,
    ): Long? {
        if (gps.size < 10 || car.size < 20) return null
        val moving = gps.filter { it.second > 3f }
        if (moving.size < 10) return null
        val spread = moving.maxOf { it.second } - moving.minOf { it.second }
        if (spread < 10f) return null
        var best: Long? = null
        var bestErr = Double.MAX_VALUE
        var lag = 0L
        while (lag <= maxLagMs) {
            var err = 0.0
            var n = 0
            for ((t, v) in moving) {
                val c = carAt(car, t - lag) ?: continue
                err += abs(v - c)
                n++
            }
            if (n >= 10) {
                val e = err / n
                if (e < bestErr) {
                    bestErr = e
                    best = lag
                }
            }
            lag += stepMs
        }
        return best
    }

    /** Скорость машины в момент [t] (линейно между соседними замерами). */
    private fun carAt(car: List<Pair<Long, Float>>, t: Long): Float? {
        if (t < car.first().first || t > car.last().first) return null
        var lo = 0
        var hi = car.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (car[mid].first <= t) lo = mid else hi = mid
        }
        val (t0, v0) = car[lo]
        val (t1, v1) = car[hi]
        if (t1 == t0) return v0
        return v0 + (v1 - v0) * (t - t0).toFloat() / (t1 - t0)
    }

    private const val EARTH_M = 6_371_000.0
}
