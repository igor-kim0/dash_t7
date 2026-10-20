package com.dash.headunit.elm

/**
 * Разбор ответов ELM327 (OBD-II протокол) — чистые функции
 * без Android-зависимостей, покрыты тестами.
 *
 * ELM пишет построчно: эхо команды (если ещё не выключено), служебные
 * строки («NO DATA», «SEARCHING…»), затем ответ, затем приглашение «>».
 * Байты ответа — hex-пары, через пробелы или подряд: «41 05 3C».
 */
object ObdParser {

    /** Приглашение ELM327 в конце ответа. */
    const val PROMPT = '>'

    /**
     * Температура ОЖ из ответа на 0105 (PID 05): третий байт − 40 °C.
     * null — ответа нет / другая команда / мусор.
     */
    fun coolant(text: String): Float? {
        val bytes = scanResponse(text, "4105") ?: return null
        val value = bytes.getOrNull(2) ?: return null
        return (value - 40).toFloat()
    }

    /** Обороты из ответа на 010C: (A·256 + B) / 4, об/мин. */
    fun rpm(text: String): Float? {
        val bytes = scanResponse(text, "410C") ?: return null
        val a = bytes.getOrNull(2) ?: return null
        val b = bytes.getOrNull(3) ?: return null
        return (a * 256 + b) / 4f
    }

    /** Расчётная нагрузка двигателя из ответа на 0104: A · 100 / 255, %. */
    fun engineLoad(text: String): Float? {
        val bytes = scanResponse(text, "4104") ?: return null
        val a = bytes.getOrNull(2) ?: return null
        return a * 100f / 255f
    }

    /**
     * Напряжение бортовой сети из ответа ELM на ATRV («12.6V»), В.
     * Меряет сам адаптер на 16-м пине OBD — работает на любой машине.
     */
    fun voltage(text: String): Float? {
        val m = VOLTAGE.find(text) ?: return null
        return m.groupValues[1].replace(',', '.').toFloatOrNull()?.takeIf { it in 5f..20f }
    }

    /** Температура впускного воздуха из ответа на 0110: A − 40, °C. */
    fun intakeAir(text: String): Float? = byteMinus40(text, "4110")

    /** Положение дросселя из ответа на 0111: A · 100 / 255, %. */
    fun throttle(text: String): Float? = bytePct(text, "4111")

    /** Уровень топлива в баке из ответа на 012F (PID 47): A · 100 / 255, %. */
    fun fuelLevel(text: String): Float? = bytePct(text, "412F")

    /** Давление во впускном коллекторе (MAP) из ответа на 010B: A, кПа. */
    fun mapPressure(text: String): Float? =
        scanResponse(text, "410B")?.getOrNull(2)?.toFloat()

    /**
     * Короткая топливная балансировка (B1) из ответа на 0106:
     * (A − 128) · 100 / 128, % (минус — бедная смесь).
     */
    fun fuelTrimShort(text: String): Float? = fuelTrim(text, "4106")

    /** Долгая балансировка из ответа на 0107 — та же формула, что и короткая. */
    fun fuelTrimLong(text: String): Float? = fuelTrim(text, "4107")

    private fun fuelTrim(text: String, cmdId: String): Float? =
        scanResponse(text, cmdId)?.getOrNull(2)?.let { (it - 128) * 100f / 128f }

    private fun byteMinus40(text: String, cmdId: String): Float? =
        scanResponse(text, cmdId)?.getOrNull(2)?.let { (it - 40).toFloat() }

    private fun bytePct(text: String, cmdId: String): Float? =
        scanResponse(text, cmdId)?.getOrNull(2)?.let { it * 100f / 255f }

    private val VOLTAGE = Regex("""(\d{1,2}(?:[.,]\d{1,2})?)\s*V""")

    /**
     * Первый найденный в тексте hex-ответ на команду [cmdId] (например,
     * «4105» — ответ ELM на запрос 0105). Ответ ищется внутри одной строки
     * (разные сообщения ELM не склеиваем); возвращаются байты начиная
     * с идентификатора ответа («41 05 …»).
     */
    fun scanResponse(text: String, cmdId: String): List<Int>? {
        val pattern = cmdId.chunked(2)
        for (line in text.split('\r', '\n')) {
            val tokens = hexPairs(line) ?: continue
            for (i in 0..tokens.size - pattern.size) {
                var same = true
                for (p in pattern.indices) {
                    if (tokens[i + p].uppercase() != pattern[p]) {
                        same = false
                        break
                    }
                }
                if (!same) continue
                return tokens.drop(i)
                    .takeWhile { it.length == 2 && it[0].isHex() && it[1].isHex() }
                    .map { it.toInt(16) }
            }
        }
        return null
    }

    /**
     * Из строки ELM — hex-пары («41 05 3C» и «41053C» → 41,05,3C).
     * Прочее (эхо, «NO DATA», «SEARCHING…», «>») отбрасывается;
     * нечётная длина hex-части — строка целиком негодна.
     */
    private fun hexPairs(line: String): List<String>? {
        val cleaned = line.filter { it.isHex() }
        if (cleaned.length < 2 || cleaned.length % 2 != 0) return null
        return cleaned.chunked(2)
    }

    private fun Char.isHex(): Boolean =
        isDigit() || (this in 'A'..'F') || (this in 'a'..'f')
}
