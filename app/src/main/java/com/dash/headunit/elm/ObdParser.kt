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

    /** Температура впускного воздуха из ответа на 010F (0110 — это MAF): A − 40, °C. */
    fun intakeAir(text: String): Float? = byteMinus40(text, "410F")

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
    fun scanResponse(text: String, cmdId: String): List<Int>? = scanAll(text, cmdId).firstOrNull()

    /**
     * Все ответы на [cmdId] в тексте — по одному на строку. На
     * широковещательный запрос (7DF) отвечают несколько блоков: у Tiggo
     * КПП (7E9) отвечает раньше двигателя (7E8), и у неё свои значения
     * (дроссель 0, ОЖ −40 °C, маска PID без 0104/0106/0107/010B/010F).
     */
    fun scanAll(text: String, cmdId: String): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        val pattern = cmdId.chunked(2)
        line@ for (tokens in text.split('\r', '\n').flatMap(::hexRuns)) {
            for (i in 0..tokens.size - pattern.size) {
                var same = true
                for (p in pattern.indices) {
                    if (tokens[i + p].uppercase() != pattern[p]) {
                        same = false
                        break
                    }
                }
                if (!same) continue
                out += tokens.drop(i)
                    .takeWhile { it.length == 2 && it[0].isHex() && it[1].isHex() }
                    .map { it.toInt(16) }
                continue@line
            }
        }
        return out
    }

    /**
     * Склеить многокадровый ответ ISO-TP (VIN, имя ЭБУ, длинные DID)
     * в одну строку hex-байтов. ELM печатает его двумя способами:
     *  - без заголовков (ATH0): строка с длиной («014»), затем кадры
     *    «0: 49 02 01 …», «1: …»;
     *  - с заголовками (клон проигнорировал ATH0): «7E8 10 14 49 02 01 …»
     *    (первый кадр, PCI 1L LL) и «7E8 21 …», «7E8 22 …» (последующие).
     * Данные обрезаются по объявленной длине — хвостовая набивка
     * (00 / AA / 55 — «U» в ASCII) в результат не попадает. Если отвечали
     * несколько блоков, берётся первый. Без многокадрового ответа текст
     * не меняется.
     */
    fun joinFrames(text: String): String {
        val lines = text.split('\r', '\n').map { it.trim().trim(PROMPT).trim() }.filter { it.isNotEmpty() }
        numberedFrames(lines)?.let { return it }
        headerFrames(lines)?.let { return it }
        return text
    }

    private fun numberedFrames(lines: List<String>): String? {
        var length: Int? = null
        val data = ArrayList<String>()
        for (l in lines) {
            val f = FRAME.find(l)
            if (f != null) {
                data += f.groupValues[1].replace(" ", "").replace("\t", "").chunked(2)
                continue
            }
            // Длина перед кадрами: «014» (3 hex-цифры); второй блок — стоп.
            if (LENGTH.matches(l)) {
                if (data.isNotEmpty()) break
                length = l.toInt(16)
            }
        }
        if (data.isEmpty()) return null
        val bytes = data.filter { it.length == 2 }
        return (length?.let { bytes.take(it) } ?: bytes).joinToString(" ")
    }

    private fun headerFrames(lines: List<String>): String? =
        canMessagesOf(lines).firstOrNull { it.multiFrame }?.data?.joinToString(" ") { "%02X".format(it) }

    /**
     * Сообщение CAN 11 бит из ответа ELM с заголовками (ATH1, ATS0, ATCAF1):
     * [header] — CAN-ID ответившего блока («7E8»), [data] — данные ISO-TP
     * без PCI («62 01 70 00»). [complete] — false, если многокадровое
     * сообщение оборвалось (пришли не все последующие кадры).
     */
    class CanMessage(val header: String, val data: List<Int>, val multiFrame: Boolean, val complete: Boolean)

    /**
     * Разобрать ответ ELM с заголовками на сообщения ISO-TP: одиночные кадры
     * (PCI 0L), первый + последующие (1L LL … / 2n …) — склеиваются по
     * заголовку и обрезаются по длине. Кадры управления потоком (3x)
     * и строки без заголовка пропускаются. Порядок — по первому кадру.
     */
    fun canMessages(text: String): List<CanMessage> =
        canMessagesOf(text.split('\r', '\n').map { it.trim().trim(PROMPT).trim() })

    private fun canMessagesOf(lines: List<String>): List<CanMessage> {
        class Pending(val header: String, val length: Int, val data: ArrayList<Int>)
        val out = ArrayList<CanMessage>()
        val pending = LinkedHashMap<String, Pending>()
        for (l in lines) {
            val hex = l.replace(" ", "")
            if (hex.length < 5 || hex.length % 2 == 0 || !hex.all { it.isHex() }) continue
            val h = hex.substring(0, 3).uppercase()
            val bytes = hex.substring(3).chunked(2).map { it.toInt(16) }
            val pci = bytes[0]
            when (pci shr 4) {
                0 -> {
                    val len = pci and 0x0F
                    if (len in 1..bytes.size - 1) out += CanMessage(h, bytes.subList(1, 1 + len), false, true)
                }
                1 -> {
                    if (bytes.size < 2) continue
                    val len = ((pci and 0x0F) shl 8) or bytes[1]
                    pending.remove(h)?.let { out += CanMessage(h, it.data, true, false) }
                    pending[h] = Pending(h, len, ArrayList(bytes.drop(2)))
                }
                2 -> {
                    val p = pending[h] ?: continue
                    p.data += bytes.drop(1)
                    if (p.data.size >= p.length) {
                        pending.remove(h)
                        out += CanMessage(h, p.data.take(p.length), true, true)
                    }
                }
            }
        }
        pending.values.forEach { out += CanMessage(it.header, it.data, true, false) }
        return out
    }

    private val FRAME = Regex("""^\s*[0-9A-Fa-f]\s*:\s*([0-9A-Fa-f ]+)$""")
    private val LENGTH = Regex("""^[0-9A-Fa-f]{3}$""")

    /**
     * Маска поддерживаемых PID из ответа на 0100/0120/0140… ([base] —
     * 0x00, 0x20, 0x40…): номера PID base+1..base+32, чьи биты взведены
     * (старший бит первого байта — base+1). null — ответа нет.
     */
    fun supportedPids(text: String, base: Int): Set<Int>? {
        // Ответили несколько блоков — объединяем их маски: PID, которого
        // нет у адресата, отсеется промахами, а вот потерять PID двигателя
        // из-за маски КПП (она приходит первой) — нельзя.
        val answers = scanAll(text, "41%02X".format(base)).filter { it.size >= 6 }
        if (answers.isEmpty()) return null
        val out = HashSet<Int>()
        for (bytes in answers) {
            for (b in 0 until 4) {
                for (bit in 0 until 8) {
                    if (bytes[2 + b] and (0x80 shr bit) != 0) out.add(base + b * 8 + bit + 1)
                }
            }
        }
        return out
    }

    /**
     * Из строки ELM — hex-пары («41 05 3C» и «41053C» → 41,05,3C).
     * Строка режется на куски из hex-символов и пробелов, чтобы буквы
     * служебных слов («SEARCHING…», «NO DATA») не склеивались с ответом.
     * Нечётная длина куска — впереди 11-битный CAN-заголовок («7E8»,
     * клоны шлют его и без ATH1): первый символ отбрасываем.
     */
    private fun hexRuns(line: String): List<List<String>> {
        val runs = ArrayList<List<String>>()
        val sb = StringBuilder()
        fun flush() {
            if (sb.length % 2 != 0) sb.deleteCharAt(0)
            if (sb.length >= 2) runs.add(sb.toString().chunked(2))
            sb.setLength(0)
        }
        for (c in line) {
            when {
                c.isHex() -> sb.append(c)
                c == ' ' || c == '\t' -> Unit
                else -> flush()
            }
        }
        flush()
        return runs
    }

    private fun Char.isHex(): Boolean =
        isDigit() || (this in 'A'..'F') || (this in 'a'..'f')
}
