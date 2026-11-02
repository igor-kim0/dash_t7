package com.dash.headunit.elm

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Профиль диагностики автомобиля из JSON (пример — assets/profiles/
 * Chery Tiggo.json): какие команды слать ELM327, какому блоку
 * (CAN-заголовок, ATSH) и как пересчитать байты ответа в величину.
 *
 * Формат (лишние поля игнорируются):
 * ```
 * { "profile_name": "...", "description": "...",
 *   "connection_settings": { "protocol": "CAN_11bit_500", "timeout": 2000, "default_header": "7E0" },
 *   "parameters": { "<группа>": [ { "name": "...", "short_name": "RPM", "command": "010C",
 *        "header": "7E0", "response_header": "7E8",
 *        "formula": "((A*256)+B)/4", "unit": "об/мин",
 *        "min": 0, "max": 8000, "precision": 0, "display_as_hex": false, "note": "..." } ] },
 *   "vehicle_information": { "vin": { "command": "0902", "header": "7E0" } } }
 * ```
 * «response_header» — CAN-ID ответа блока (фильтр ATCRA). Нужен для
 * блоков вне пары 7E0..7E7 → 7E8..7EF (например, 740 → 750): иначе
 * ELM может не принять их ответ. Не задан — ATAR (авто-фильтр).
 *
 * Параметры с ошибкой (нет команды, кривая формула) пропускаются
 * и попадают в [warnings]; профиль без единого годного параметра —
 * ошибка разбора.
 */
class ObdProfile(
    val name: String,
    val version: String?,
    val author: String?,
    val description: String?,
    /** Протокол как в JSON («CAN_11bit_500»). */
    val protocol: String?,
    /** Тот же протокол номером ELM (ATSP6) или null — авто-поиск. */
    val elmProtocol: String?,
    val timeoutMs: Long?,
    val defaultHeader: String?,
    val groups: List<Group>,
    val info: List<Info>,
    val warnings: List<String>,
) {
    val params: List<Param> get() = groups.flatMap { it.params }

    class Group(val key: String, val title: String, val params: List<Param>)

    /** Сведения об автомобиле, читаются один раз за сессию (VIN, калибровка, имя ЭБУ). */
    class Info(
        val key: String,
        val title: String,
        val command: String,
        val header: String?,
        val rxHeader: String? = null,
    ) {
        /** Режим 09: первый байт данных — число элементов, дальше ASCII. Иначе — hex. */
        fun decode(reply: String): String? {
            val bytes = ObdParser.scanResponse(ObdParser.joinFrames(reply), responseId(command)) ?: return null
            var data = bytes.drop(command.length / 2)
            if (command.startsWith("09")) {
                data = data.drop(1)
                val s = data.filter { it in 0x20..0x7E }.map { it.toChar() }.joinToString("").trim()
                return s.ifEmpty { null }
            }
            return data.joinToString(" ") { "%02X".format(it) }.ifEmpty { null }
        }
    }

    class Param(
        /** Уникальный в профиле ключ: «группа/индекс». */
        val key: String,
        val name: String,
        val shortName: String,
        val command: String,
        val header: String?,
        /** CAN-ID ответа (ATCRA) или null — авто-фильтр ELM (ATAR). */
        val rxHeader: String?,
        val formula: Formula,
        val unit: String,
        val min: Double?,
        val max: Double?,
        val precision: Int,
        val hex: Boolean,
        val note: String?,
    ) {
        private val labels = note?.let(::parseLabels).orEmpty()

        /** Значение из полного текста ответа ELM или null (нет ответа / отказ блока). */
        fun decode(reply: String): Double? {
            if (command.startsWith("AT")) return ObdParser.voltage(reply)?.toDouble()
            val bytes = ObdParser.scanResponse(ObdParser.joinFrames(reply), responseId(command)) ?: return null
            return formula.eval(bytes.drop(command.length / 2))
        }

        /** Значение для экрана: hex, подпись из note («0=P, 1=R…») или число с единицей. */
        fun format(v: Double): String {
            if (hex) return "%X".format(v.toLong())
            labels[Math.round(v).toInt()]?.takeIf { Math.abs(v - Math.round(v)) < 1e-6 }?.let { return it }
            val num = String.format(Locale.US, "%.${precision}f", v)
            return if (unit.isEmpty()) num else "$num $unit"
        }

        /** Вне диапазона min..max из профиля — повод подсветить. */
        fun outOfRange(v: Double): Boolean = (min != null && v < min) || (max != null && v > max)
    }

    companion object {

        /**
         * Разобрать текст профиля. Группы идут в порядке файла: JSONObject
         * порядок ключей не гарантирует, поэтому сортируем по позиции ключа
         * в тексте.
         */
        fun parse(text: String): ObdProfile =
            parse(JSONObject(text)) { key -> text.indexOf("\"$key\"").let { if (it < 0) Int.MAX_VALUE else it } }

        fun parse(root: JSONObject, order: (String) -> Int = { 0 }): ObdProfile {
            val warnings = ArrayList<String>()
            val conn = root.optJSONObject("connection_settings")
            val defaultHeader = conn?.str("default_header")?.let(::cleanHex)
            val protocol = conn?.str("protocol")

            val groups = ArrayList<Group>()
            when (val p = root.opt("parameters")) {
                is JSONObject -> p.keys().asSequence().sortedBy(order).forEach { key ->
                    val arr = p.optJSONArray(key)
                    if (arr == null) warnings += "группа «$key»: не массив" else
                        group(key, arr, defaultHeader, warnings)?.let(groups::add)
                }
                // Допускаем и плоский список параметров без групп.
                is JSONArray -> group("parameters", p, defaultHeader, warnings)?.let(groups::add)
                else -> throw IllegalArgumentException("нет раздела «parameters»")
            }
            if (groups.isEmpty()) {
                throw IllegalArgumentException("ни одного годного параметра" +
                    warnings.firstOrNull()?.let { ": $it" }.orEmpty())
            }

            val info = ArrayList<Info>()
            root.optJSONObject("vehicle_information")?.let { vi ->
                vi.keys().asSequence().sortedBy(order).forEach { key ->
                    val o = vi.optJSONObject(key) ?: return@forEach
                    val cmd = (o.str("command") ?: o.str("pid"))?.let(::cleanHex)
                    if (cmd == null || cmd.length < 4) {
                        warnings += "сведения «$key»: нет команды"
                        return@forEach
                    }
                    info += Info(key, INFO_TITLES[key] ?: key, cmd, o.str("header")?.let(::cleanHex) ?: defaultHeader,
                        o.str("response_header")?.let(::cleanHex))
                }
            }

            return ObdProfile(
                name = root.str("profile_name") ?: root.str("name") ?: "Без названия",
                version = root.str("profile_version") ?: root.str("version"),
                author = root.str("author"),
                description = root.str("description"),
                protocol = protocol,
                elmProtocol = protocol?.let(::elmProtocol),
                timeoutMs = conn?.optLong("timeout", 0)?.takeIf { it > 0 },
                defaultHeader = defaultHeader,
                groups = groups,
                info = info,
                warnings = warnings,
            )
        }

        private fun group(key: String, arr: JSONArray, defaultHeader: String?, warnings: MutableList<String>): Group? {
            val params = ArrayList<Param>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.str("name") ?: o.str("short_name") ?: "$key #${i + 1}"
                try {
                    params += param("$key/$i", name, o, defaultHeader)
                } catch (e: IllegalArgumentException) {
                    warnings += "«$name»: ${e.message}"
                }
            }
            if (params.isEmpty()) return null
            return Group(key, GROUP_TITLES[key] ?: key.replace('_', ' ').replaceFirstChar { it.uppercase() }, params)
        }

        private fun param(key: String, name: String, o: JSONObject, defaultHeader: String?): Param {
            val raw = o.str("command") ?: o.str("pid") ?: throw IllegalArgumentException("нет команды")
            val command = if (raw.trim().uppercase().startsWith("AT")) raw.trim().uppercase().replace(" ", "")
            else cleanHex(raw)
            if (!command.startsWith("AT") && (command.length < 2 || command.length % 2 != 0)) {
                throw IllegalArgumentException("команда «$raw» — не hex-байты")
            }
            val formula = Formula.compile(o.str("formula") ?: "A")
            return Param(
                key = key,
                name = name,
                shortName = o.str("short_name") ?: name,
                command = command,
                header = o.str("header")?.let(::cleanHex) ?: defaultHeader,
                rxHeader = o.str("response_header")?.let(::cleanHex),
                formula = formula,
                unit = o.str("unit") ?: "",
                min = o.num("min"),
                max = o.num("max"),
                precision = o.optInt("precision", 1).coerceIn(0, 6),
                hex = o.optBoolean("display_as_hex", false),
                note = o.str("note"),
            )
        }

        /** Идентификатор положительного ответа: первый байт + 0x40, остальное как в запросе. */
        fun responseId(command: String): String {
            val mode = command.substring(0, 2).toInt(16) + 0x40
            return "%02X".format(mode) + command.substring(2)
        }

        /** «CAN_11bit_500» → «6» (ATSP6); неизвестное → null (авто-поиск). */
        fun elmProtocol(name: String): String? {
            val n = name.uppercase().replace("-", "_").replace(" ", "_")
            return when {
                "CAN" in n && "11" in n && "500" in n -> "6"
                "CAN" in n && "29" in n && "500" in n -> "7"
                "CAN" in n && "11" in n && "250" in n -> "8"
                "CAN" in n && "29" in n && "250" in n -> "9"
                "KWP" in n && "FAST" in n -> "5"
                "KWP" in n -> "4"
                "9141" in n -> "3"
                "PWM" in n -> "1"
                "VPW" in n -> "2"
                else -> null
            }
        }

        /**
         * Подписи значений из note: «0=P, 1=R, 2=N, 3-9=D1-D7» →
         * 0→P, 1→R, 2→N, 3→D1 … 9→D7. Прочий текст в note игнорируется.
         */
        fun parseLabels(note: String): Map<Int, String> {
            val out = HashMap<Int, String>()
            for (part in note.split(',', ';')) {
                val m = Regex("""^\s*(\d+)(?:\s*-\s*(\d+))?\s*=\s*(.+?)\s*$""").find(part) ?: continue
                val from = m.groupValues[1].toInt()
                val to = m.groupValues[2].toIntOrNull()
                val label = m.groupValues[3]
                if (to == null) {
                    out[from] = label
                    continue
                }
                // Диапазон: «D1-D7» — префикс и стартовый номер.
                val r = Regex("""^(\D*)(\d+)\s*-\s*\D*\d+$""").find(label) ?: continue
                val start = r.groupValues[2].toInt()
                for (v in from..to) out[v] = r.groupValues[1] + (start + v - from)
            }
            return out
        }

        private fun cleanHex(s: String) = s.replace(" ", "").uppercase()

        private fun JSONObject.str(key: String): String? =
            if (has(key) && !isNull(key)) optString(key).trim().takeIf { it.isNotEmpty() } else null

        private fun JSONObject.num(key: String): Double? =
            if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null

        private val GROUP_TITLES = mapOf(
            "engine" to "Двигатель",
            "fuel_system" to "Топливная система",
            "transmission" to "Трансмиссия",
            "tpms" to "Шины (TPMS)",
            "electrical" to "Электрика",
            "emissions" to "Экология",
            "hybrid_system" to "Гибридная система",
            "performance" to "Динамика",
            "body" to "Кузов",
            "climate" to "Климат",
            "abs" to "ABS / ESP",
        )

        private val INFO_TITLES = mapOf(
            "vin" to "VIN",
            "calibration_id" to "Калибровка",
            "ecu_name" to "ЭБУ",
            "tcm_calibration_id" to "Калибровка КПП",
            "tcm_name" to "ЭБУ КПП",
        )
    }
}
