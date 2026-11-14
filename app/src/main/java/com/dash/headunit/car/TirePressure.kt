package com.dash.headunit.car

import java.util.Locale

/**
 * Колесо машины (вид сверху): переднее левое/правое, заднее левое/правое.
 * [short] — подпись на приборке и в списках, [title] — словами.
 */
enum class Wheel(val short: String, val title: String) {
    FL("FL", "переднее левое"),
    FR("FR", "переднее правое"),
    RL("RL", "заднее левое"),
    RR("RR", "заднее правое")
}

/**
 * Давление в шинах, бар. Колесо, которое не опрошено (или не ответило), —
 * null. Источник — шина машины ([tiresFromRaw]: Desay 4,10 / приборка a005),
 * запасной — OBD-профиль с TPMS ([com.dash.headunit.elm.ElmHub]).
 */
data class TirePressures(
    val fl: Float? = null,
    val fr: Float? = null,
    val rl: Float? = null,
    val rr: Float? = null
) {

    /** Давление колеса, бар. */
    fun of(w: Wheel): Float? = when (w) {
        Wheel.FL -> fl
        Wheel.FR -> fr
        Wheel.RL -> rl
        Wheel.RR -> rr
    }

    /** Известные давления (без пропусков). */
    fun known(): List<Pair<Wheel, Float>> =
        Wheel.entries.mapNotNull { w -> of(w)?.let { w to it } }

    /** Самое низкое из известных давлений, бар. */
    fun min(): Float? = known().minOfOrNull { it.second }

    /** Колеса ниже порога — вместе с их давлением. */
    fun below(threshold: Float): List<Pair<Wheel, Float>> =
        known().filter { it.second < threshold }

    /** Ни одного давления нет. */
    val isEmpty: Boolean get() = known().isEmpty()
}

/**
 * Колесо по названию параметра профиля: «Tire FL», «TPMS FR»,
 * «Давление в шине Передняя Левая». null — колесо не опознано.
 * Чистая — тестируется.
 */
internal fun wheelByName(name: String): Wheel? {
    val n = name.uppercase(Locale.ROOT)
    // Русские названия: «передняя левая», «задняя правая» и т. п.
    if (n.contains("ЛЕВАЯ")) return if (n.contains("ПЕРЕДНЯЯ")) Wheel.FL else Wheel.RL
    if (n.contains("ПРАВАЯ")) return if (n.contains("ПЕРЕДНЯЯ")) Wheel.FR else Wheel.RR
    // Латинские: FL/FR/RL/RR (иногда LF/RF/LR).
    if (Regex("\\b(FL|LF)\\b").containsMatchIn(n)) return Wheel.FL
    if (Regex("\\b(FR|RF)\\b").containsMatchIn(n)) return Wheel.FR
    if (Regex("\\b(RL|LR)\\b").containsMatchIn(n)) return Wheel.RL
    if (Regex("\\bRR\\b").containsMatchIn(n)) return Wheel.RR
    return null
}

/**
 * Давление в бар по единице измерения профиля: «кПа», «bar», «psi»,
 * «кгс/см²», «MPa». null — единица не похожа на давление.
 * Чистая — тестируется.
 */
internal fun toBar(value: Float, unit: String): Float? {
    val u = unit.lowercase(Locale.ROOT).replace(" ", "").replace("²", "2")
    return when {
        u.isEmpty() -> null
        u.startsWith("бар") || u.startsWith("bar") -> value
        u.startsWith("кпа") || u.startsWith("kpa") -> value / 100f
        u.startsWith("мпа") || u.startsWith("mpa") -> value * 10f
        u.startsWith("psi") -> value / 14.5038f
        u.contains("кгс") || u.contains("kgf") -> value / 0.980665f
        else -> null
    }
}

/** Единица измерения — давление (а не температура и не ID датчика). */
internal fun isPressureUnit(unit: String): Boolean = toBar(1f, unit) != null

/**
 * Давление одного колеса из сырого байта TPMS машины (Desay 4,10 /
 * приборка a005 b4–b7), бар. Формулы в прошивке ГУ нет — считает сама
 * приборка; подобрано по двум снимкам: 2026-10-14 (сырые 112/115 → 2,0) и
 * 2026-11-14 (сырые 123,125,126 → 2,2, 128 → 2,3; приборка округляет до 0,1).
 * Сходится шаг ~1,6…2,1 кПа (2,5 — нет): взято кПа = raw × 2 − 28.
 * 0/255 и вне разумного диапазона — нет данных.
 * Чистая — тестируется. ГУ: ждёт снимка с разным давлением во всех колёсах.
 */
internal fun tireBarFromRaw(raw: Int): Float? {
    if (raw !in TIRE_RAW_MIN..TIRE_RAW_MAX) return null
    return (raw * TIRE_KPA_PER_RAW - TIRE_KPA_OFFSET) / 100f
}

/**
 * Колёса в порядке сырых байтов p0..p3. Снимок 2026-11-14: единственное
 * колесо с 2,3 на приборке — переднее левое, а максимальный байт — p1,
 * значит p1 = FL. Остальные три показывали одинаковые 2,2 — их порядок
 * (p0 = FR, p2 = RR, p3 = RL) пока догадка. Одна точка для правки.
 */
internal val TIRE_RAW_ORDER = listOf(Wheel.FR, Wheel.FL, Wheel.RR, Wheel.RL)

/**
 * Четыре давления из сырых байтов машины (Desay 4,10 или a005 b4–b7).
 * null — массив короче 4 или ни одно колесо не дало данных.
 * Чистая — тестируется.
 */
internal fun tiresFromRaw(raw: IntArray?, offset: Int = 0): TirePressures? {
    if (raw == null || raw.size < offset + TIRE_RAW_ORDER.size) return null
    val bar = HashMap<Wheel, Float>(4)
    TIRE_RAW_ORDER.forEachIndexed { i, w -> tireBarFromRaw(raw[offset + i])?.let { bar[w] = it } }
    if (bar.isEmpty()) return null
    return TirePressures(fl = bar[Wheel.FL], fr = bar[Wheel.FR], rl = bar[Wheel.RL], rr = bar[Wheel.RR])
}

/** ~0,9 бар (raw 60) .. ~3,7 бар (raw 200); 0 и 255 — «нет датчика». */
private const val TIRE_RAW_MIN = 60
private const val TIRE_RAW_MAX = 200
private const val TIRE_KPA_PER_RAW = 2f
private const val TIRE_KPA_OFFSET = 28f
