package com.dash.headunit.car

import android.os.Build
import java.util.Locale

/**
 * Чистые функции пересчёта сырых значений CAN/VHAL.
 * Логика повторяет проверенные формулы Jarvis (декомпил jarvis-5.3-300):
 * - Desay SV: топливо (4,68)/10 %, запас (4,72) км, напряжение (4,76)/10 В;
 * - Chery T19FL3 VHAL: топливо 557850663 /2 %, запас 557850664,
 *   напряжение 559947843 (мВ/сВ/В по величине), SOC 557850690, RPM 557850661.
 */
object CarScaling {

    /** Платформа Chery T19FL3 — особый скейлинг топлива (/2). */
    fun isCheryT19(): Boolean {
        val locale = Locale.ROOT
        return Build.PRODUCT.lowercase(locale).startsWith("chery_t19fl3") ||
            Build.DEVICE.lowercase(locale).startsWith("chery_t19fl3")
    }

    /** Сборка big-endian int из сырых байт Desay (b3.c из Jarvis). */
    fun bytesToInt(bytes: IntArray?): Int {
        if (bytes == null || bytes.isEmpty() || bytes.size > 4) return -1
        var v = 0
        for (b in bytes) v = (v shl 8) or (b and 0xFF)
        return v
    }

    /** Топливо Desay (4,68): десятые доли процента. */
    fun desayFuelPct(raw: Int): Float? {
        if (raw < 0 || raw > 1000) return null
        return raw / 10f
    }

    /** Запас хода Desay (4,72): км. */
    fun desayRangeKm(raw: Int): Int? {
        if (raw < 0 || raw > 3000) return null
        return raw
    }

    /** Напряжение Desay (4,76): десятые доли вольта. */
    fun desayVolts(raw: Int): Float? {
        val v = raw / 10f
        if (v < 6f || v > 18f) return null
        return v
    }

    /** Топливо VHAL 557850663 (целое). */
    fun vhalFuelPct(raw: Int, isChery: Boolean): Float? {
        if (raw < 0) return null
        return if (isChery) {
            (raw / 2f).coerceAtMost(100f)
        } else if (raw <= 100) {
            raw.toFloat()
        } else if (raw <= 1000) {
            raw / 10f
        } else {
            null
        }
    }

    /** Запас хода VHAL 557850664: км. */
    fun vhalRangeKm(raw: Int): Int? {
        if (raw <= 0 || raw > 3000) return null
        return raw
    }

    /** Напряжение VHAL 559947843: масштаб по величине значения. */
    fun vhalVolts(raw: Double): Float? {
        if (raw.isNaN()) return null
        val v = when {
            raw >= 6000.0 -> raw / 1000.0
            raw >= 60.0 && raw <= 180.0 -> raw / 10.0
            else -> raw
        }
        if (v < 6.0 || v > 18.0) return null
        return v.toFloat()
    }

    /**
     * Забортная температура Desay (16,19): сырое минус 120.
     * Шкала ×10 опровергнута: при реальной погоде 4–9 °C ячейка даёт
     * 125–128 (×10 дало бы 12.5–12.8). Зато raw−120 ложится идеально:
     * 125→5, 127→7, 128→8; Oct12 134→14 (тёплый вечер, правдоподобно).
     * Формула TiggoBridge «raw × 0.5 − 40» на этом ГУ НЕ подходит: те же
     * 125–134 она превращает в +22…+27 в октябре (проверено 2026-10-22).
     */
    fun outsideTemp(raw: Int): Float? {
        if (raw < 70 || raw > 170) return null
        return (raw - 120).toFloat()
    }

    /** Температура ОЖ: грубая валидация. */
    fun coolantTemp(raw: Float?): Float? {
        if (raw == null || raw.isNaN() || raw < -40f || raw > 150f) return null
        return raw
    }

    /**
     * Оценка °C ОЖ по стрелке приборки (a000, 0..1), когда прямых градусов
     * нет. Стрелка T18FL3 нелинейна: на прогретом двигателе стоит ровно
     * на середине (500/1000, логи 2026-10-13/14) во всём рабочем диапазоне;
     * по замеру пользователя середина = 98 °C (2026-10-22; линейная
     * 40..130 давала 85). Кусочно-линейно: 0 → 40, 0,5 → 98, 1 → 130.
     */
    fun needleToCoolant(n: Float): Float {
        val x = n.coerceIn(0f, 1f)
        return if (x <= NEEDLE_MID) {
            NEEDLE_COLD_C + x / NEEDLE_MID * (NEEDLE_MID_C - NEEDLE_COLD_C)
        } else {
            NEEDLE_MID_C + (x - NEEDLE_MID) / (1f - NEEDLE_MID) * (NEEDLE_HOT_C - NEEDLE_MID_C)
        }
    }

    /** Обратное к [needleToCoolant]: °C → положение стрелки 0..1. */
    fun coolantToNeedle(c: Float): Float = when {
        c <= NEEDLE_COLD_C -> 0f
        c <= NEEDLE_MID_C -> (c - NEEDLE_COLD_C) / (NEEDLE_MID_C - NEEDLE_COLD_C) * NEEDLE_MID
        c <= NEEDLE_HOT_C -> NEEDLE_MID + (c - NEEDLE_MID_C) / (NEEDLE_HOT_C - NEEDLE_MID_C) * (1f - NEEDLE_MID)
        else -> 1f
    }

    private const val NEEDLE_MID = 0.5f
    private const val NEEDLE_COLD_C = 40f
    private const val NEEDLE_MID_C = 98f
    private const val NEEDLE_HOT_C = 130f

    /**
     * ОЖ Desay READONLY (4,100), заводская логика реф. приложения
     * TiggoBridge (formatCoolant, MainActivity.java:1063): raw ≤ 200 — целые
     * градусы, raw > 200 — десятые (925 → 92.5). 0 — «не в шине» (на этом ГУ
     * ячейка часто не заводится: ProbeDiagnose.java:62), не градусы.
     */
    fun desayCoolant(raw: Int?): Float? {
        if (raw == null || raw == 0 || raw > 1500) return null
        return coolantTemp(if (raw > 200) raw / 10f else raw.toFloat())
    }

    /** Обороты Desay (4,62): сырое 16-бит BE × 4 (единица — 4 об/мин).
     * Проверено по приборке (PPS) на T18FL3: холостые 184→736 (приборка
     * 736), 432→1728 (приборка 1628–1684), после глушения висит 50→200. */
    fun rpmDesay(raw: Int): Int? {
        if (raw < 0 || raw > 2500) return null
        return raw * 4
    }

    /** Литры в баке по проценту и объёму бака. */
    fun fuelLiters(pct: Float?, tankLiters: Int): Float? {
        if (pct == null || tankLiters <= 0) return null
        return pct / 100f * tankLiters
    }
}
