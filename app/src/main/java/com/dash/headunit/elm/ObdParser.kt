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
