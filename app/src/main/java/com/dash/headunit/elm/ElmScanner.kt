package com.dash.headunit.elm

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Сканер блоков и DID через ELM327 — чтобы собрать сырой лог в машине
 * и разобрать его дома (что за величина, какой масштаб).
 *
 * Режимы ([Mode]):
 *  - [Mode.DISCOVER] — поиск блоков: 3E00 (TesterPresent), затем 1001
 *    (сеанс по умолчанию) на каждый CAN-ID 700–7F7; кто ответил — блок;
 *  - [Mode.QUICK] / [Mode.MEDIUM] / [Mode.FULL] — перебор DID (UDS 22 XXXX)
 *    у выбранных блоков: быстрый — известные страницы (старший байт) блока
 *    и F1xx (идентификация), средний — 00–3F, 60–6F, F0–FF, полный —
 *    0000–FFFF (около часа на блок);
 *  - [Mode.RECORD] — запись: по кругу опрашивает все известные DID (из лога
 *    Car Scanner и найденные сканом) и основные PID двигателя, пока не
 *    остановят; [mark] ставит метку («разгон», «включил обогрев»).
 *
 * Только чтение: сервисы 22, 3E, 10 01, режимы 01/09 — ничего не пишем,
 * сеанс не меняем. Сканировать лучше на стоянке с включённым зажиганием
 * (полный перебор — с заведённым мотором: аккумулятор).
 *
 * Сокет у адаптера один, поэтому сканер работает внутри сессии [ElmHub]:
 * hub забирает запрос ([takePending]) в цикле опроса, отдаёт сканеру канал
 * команд, а после — переинициализирует адаптер (ATZ) и опрос продолжается.
 *
 * Файлы — <externalFilesDir>/obd_scan/ (adb pull /sdcard/Android/data/
 * com.dash.headunit/files/obd_scan), копия — на флешку, если она видна
 * приложению. Каждая строка: время от старта (мс), команда, ответ как есть.
 */
object ElmScanner {

    enum class Mode(val title: String) {
        DISCOVER("поиск блоков"),
        QUICK("скан DID: быстрый"),
        MEDIUM("скан DID: средний"),
        FULL("скан DID: полный"),
        RECORD("запись"),
    }

    /** Канал команд к адаптеру; null — сокет умер. */
    fun interface Link {
        fun cmd(command: String, timeoutMs: Long): String?
    }

    /** Блок: CAN-ID запроса и ответа. */
    data class Ecu(val tx: String, val rx: String) {
        val title: String get() = "$tx→$rx" + (NAMES[tx]?.let { " $it" } ?: "")
        override fun toString() = "$tx/$rx"
    }

    internal class Request(val mode: Mode, val ecus: List<Ecu>)

    @Volatile
    private var pending: Request? = null
    @Volatile
    private var stopRequested = false
    @Volatile
    var running: Mode? = null
        private set
    @Volatile
    var status: String = "не запускался"
        private set
    /** Последний записанный файл. */
    @Volatile
    var lastFile: File? = null
        private set
    @Volatile
    private var markText: String? = null
    private var marks = 0

    /** Поставить задачу; ElmHub выполнит её при живом подключении к ELM. */
    fun request(context: Context, mode: Mode, ecus: List<Ecu> = emptyList()) {
        stopRequested = false
        pending = Request(mode, ecus)
        status = "${mode.title}: жду подключения к ELM…"
        ElmHub.start(context)
    }

    fun stop() {
        if (pending != null) {
            pending = null
            status = "отменено"
        }
        stopRequested = true
    }

    /** Метка в запись (номер растёт сам). */
    fun mark(text: String? = null) {
        marks++
        markText = text ?: "метка $marks"
    }

    val busy: Boolean get() = running != null || pending != null

    /** Для [ElmHub]: забрать задачу (null — нет). */
    internal fun takePending(): Request? = pending?.also { pending = null }

    /** Есть задача — hub прерывает круг опроса, не дожидаясь конца. */
    internal val hasPending: Boolean get() = pending != null

    // ---------- известные блоки и DID ----------

    /** Блоки, найденные поиском, или (если поиска не было) известные по Tiggo 7. */
    fun ecus(context: Context): List<Ecu> = loadEcus(context).ifEmpty { DEFAULT_ECUS }

    /** Сколько DID известно (лог Car Scanner + найденные сканом). */
    fun knownDidCount(context: Context): Int = knownDids(context).values.sumOf { it.size }

    private fun knownDids(context: Context): Map<Ecu, Set<Int>> {
        val out = LinkedHashMap<Ecu, MutableSet<Int>>()
        for ((ecu, dids) in SEED_DIDS) out.getOrPut(ecu) { sortedSetOf() } += dids
        for ((ecu, did) in loadFound(context)) out.getOrPut(ecu) { sortedSetOf() } += did
        return out
    }

    // ---------- выполнение ----------

    /**
     * Выполнить задачу на открытом канале. [protocol] — ATDPN текущей сессии.
     * false — сокет умер (hub переподключится), true — закончили / прервали.
     */
    internal fun run(context: Context, req: Request, protocol: String, link: Link): Boolean {
        if (protocol.removePrefix("A") != "6") {
            status = "${req.mode.title}: нужен CAN 11 бит 500 кбит, а протокол «${protocol.ifEmpty { "не найден" }}» — зажигание?"
            return true
        }
        val dir = File(context.getExternalFilesDir(null), DIR).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "obd_${req.mode.name.lowercase()}_$stamp.txt")
        lastFile = file
        running = req.mode
        stopRequested = false
        marks = 0
        markText = null
        var alive = true
        try {
            file.bufferedWriter().use { w ->
                val s = Session(context, w, link)
                w.write("# DashHeadUnit OBD-сканер: ${req.mode.title}, ${Date()}\n")
                w.write("# адаптер ${ElmHub.name() ?: "?"} ${ElmHub.address() ?: ""}, протокол $protocol\n")
                w.write("# формат: мс_от_старта<TAB>команда<TAB>ответ (CAN-заголовок + PCI + данные)\n")
                alive = try {
                    s.setup() && when (req.mode) {
                        Mode.DISCOVER -> s.discover()
                        Mode.QUICK, Mode.MEDIUM, Mode.FULL -> s.sweep(req.mode, req.ecus.ifEmpty { ecus(context) })
                        Mode.RECORD -> s.record()
                    }
                } catch (e: SocketDead) {
                    false
                }
                if (!alive) {
                    w.write("# сокет ELM оборвался\n")
                    status = "${req.mode.title}: связь с ELM оборвалась — ${s.summary()}"
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "scan", e)
            status = "${req.mode.title}: ошибка ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            running = null
        }
        copyToRemovable(context, file)
        return alive
    }

    private class SocketDead : RuntimeException()

    private class Session(val context: Context, val w: Writer, val link: Link) {
        val t0 = SystemClock.uptimeMillis()
        /** Суффикс «1» — ELM ждёт один ответ и не досиживает таймаут. */
        var countSuffix = true
        var sent = 0
        var found = 0
        var header: String? = null

        fun summary() = "запросов $sent, найдено $found"

        fun cmd(c: String, timeoutMs: Long = CMD_TIMEOUT_MS): String {
            val r = link.cmd(c, timeoutMs) ?: throw SocketDead()
            sent++
            w.write("${SystemClock.uptimeMillis() - t0}\t$c\t${oneLine(r)}\n")
            return r
        }

        fun note(text: String) {
            w.write("# $text\n")
            w.flush()
        }

        fun stopped(): Boolean {
            markText?.let {
                markText = null
                note("МЕТКА: $it")
            }
            return stopRequested
        }

        /** Заголовки в ответе (видно, какой блок ответил), авто-формат ISO-TP. */
        fun setup(): Boolean {
            cmd("ATH1")
            cmd("ATCAF1")
            cmd("ATAT1")
            return true
        }

        /** Переключиться на блок: запрос на tx, фильтр rx, управление потоком на tx. */
        fun target(e: Ecu) {
            if (header == e.toString()) return
            cmd("ATSH${e.tx}")
            cmd("ATCRA${e.rx}")
            cmd("ATFCSH${e.tx}")
            cmd("ATFCSD300000")
            cmd("ATFCSM1")
            header = e.toString()
        }

        /** Запрос к блоку с суффиксом «1», при сбое суффикса или 78 — без него. */
        fun ask(e: Ecu, req: String): List<ObdParser.CanMessage> {
            target(e)
            if (countSuffix) {
                val r = cmd(req + "1")
                if (r.contains('?')) {
                    // Клон не понимает число ответов — дальше без него.
                    countSuffix = false
                    note("адаптер не понимает суффикс числа ответов — опрос медленнее")
                } else {
                    val msgs = ObdParser.canMessages(r).filter { it.header == e.rx }
                    // «Ответ позже» (7F xx 78) или оборванный многокадровый —
                    // ELM вернулся после первого кадра: повторяем без суффикса.
                    val pendingNrc = msgs.any { it.data.size >= 3 && it.data[0] == 0x7F && it.data[2] == NRC_PENDING }
                    if (!pendingNrc && msgs.all { it.complete }) return msgs
                }
            }
            return ObdParser.canMessages(cmd(req, SLOW_TIMEOUT_MS)).filter { it.header == e.rx }
        }

        // ---------- поиск блоков ----------

        fun discover(): Boolean {
            note("поиск блоков: 3E00, затем 1001 на 700–7F7 (кроме 7DF)")
            cmd("ATCF700")
            cmd("ATCM700")
            cmd("ATFCSM0")
            cmd("ATST19")
            val ecus = LinkedHashSet<Ecu>()
            for (id in 0x700..0x7F7) {
                if (stopped()) break
                if (id == 0x7DF) continue
                val tx = "%03X".format(id)
                status = "поиск блоков: $tx (${(id - 0x700) * 100 / 0xF8} %), найдено ${ecus.size}"
                cmd("ATSH$tx")
                var hits = responders(cmd("3E00"), tx, 0x3E)
                if (hits.isEmpty()) hits = responders(cmd("1001"), tx, 0x10)
                for (rx in hits) {
                    if (ecus.add(Ecu(tx, rx))) note("блок: $tx → $rx")
                }
            }
            found = ecus.size
            cmd("ATST32")
            cmd("ATAR")
            if (ecus.isNotEmpty()) saveEcus(context, ecus.toList())
            note("найдено блоков: ${ecus.size}: " + ecus.joinToString(" "))
            status = "поиск блоков: ${if (stopRequested) "остановлен" else "готово"}, " +
                "найдено ${ecus.size}: " + ecus.joinToString(" ") { it.title }
            return true
        }

        /** Кто ответил на сервис [sid]: положительно (sid+40) или отказом (7F sid). */
        private fun responders(reply: String, tx: String, sid: Int): List<String> =
            ObdParser.canMessages(reply).filter { m ->
                m.header != tx && m.data.isNotEmpty() &&
                    (m.data[0] == sid + 0x40 || (m.data[0] == 0x7F && m.data.getOrNull(1) == sid))
            }.map { it.header }.distinct()

        // ---------- перебор DID ----------

        fun sweep(mode: Mode, ecus: List<Ecu>): Boolean {
            cmd("ATST32")
            val total = ecus.size
            for ((n, e) in ecus.withIndex()) {
                if (stopped()) break
                val pages = when (mode) {
                    Mode.QUICK -> quickPages(e)
                    Mode.MEDIUM -> (0x00..0x3F) + (0x60..0x6F) + (0xF0..0xFF)
                    else -> (0x00..0xFF).toList()
                }
                note("блок ${e.title}: страницы " + pages.joinToString(",") { "%02X".format(it) })
                val foundHere = ArrayList<Int>()
                var silent = 0
                val count = pages.size * 256
                var done = 0
                page@ for (page in pages) {
                    for (lo in 0..0xFF) {
                        if (stopped()) break@page
                        val did = (page shl 8) or lo
                        if (done % 16 == 0) {
                            status = "${mode.title}: блок ${n + 1}/$total ${e.title}, DID %04X (%d %%), найдено %d"
                                .format(did, done * 100 / count, foundHere.size)
                        }
                        done++
                        val msgs = ask(e, "22%04X".format(did))
                        if (msgs.isEmpty()) {
                            silent++
                            if (silent >= SILENT_LIMIT) {
                                // Блок замолчал (заснул / погасили зажигание) — проверим.
                                if (ask(e, "3E00").isEmpty()) {
                                    note("блок ${e.title} перестал отвечать на %04X — дальше не сканирую".format(did))
                                    break@page
                                }
                                silent = 0
                            }
                            continue
                        }
                        silent = 0
                        val m = msgs.first().data
                        if (m.size >= 3 && m[0] == 0x62 && ((m[1] shl 8) or m[2]) == did) {
                            foundHere += did
                            found++
                            note("DID ${e.rx} %04X = %s".format(did, m.drop(3).joinToString(" ") { "%02X".format(it) }))
                        } else if (m.size >= 3 && m[0] == 0x7F && m[2] in INTERESTING_NRC) {
                            // Есть, но закрыт (доступ / условия) — тоже стоит знать.
                            note("DID ${e.rx} %04X — отказ %02X (%s)".format(did, m[2], NRC_NAMES[m[2]] ?: "?"))
                        }
                    }
                }
                if (foundHere.isNotEmpty()) addFound(context, e, foundHere)
                note("блок ${e.title}: найдено ${foundHere.size} DID: " + foundHere.joinToString(" ") { "%04X".format(it) })
                header = null
                cmd("ATFCSM0")
                cmd("ATAR")
            }
            status = "${mode.title}: ${if (stopRequested) "остановлен" else "готово"}, ${summary()}"
            return true
        }

        private fun quickPages(e: Ecu): List<Int> {
            val pages = sortedSetOf(0xF1)
            KNOWN_PAGES[e.rx]?.let(pages::addAll)
            knownDids(context)[e]?.forEach { pages += it shr 8 }
            if (pages.size == 1) pages += listOf(0x00, 0x01, 0x02)
            return pages.toList()
        }

        // ---------- запись ----------

        fun record(): Boolean {
            cmd("ATST32")
            val plan = knownDids(context)
            note("запись: PID двигателя ${RECORD_PIDS.joinToString(" ")} + DID " +
                plan.entries.joinToString("; ") { (e, d) -> "${e.title}: ${d.size}" })
            var rounds = 0
            val started = SystemClock.uptimeMillis()
            while (!stopped()) {
                note("круг ${rounds + 1}")
                for (pid in RECORD_PIDS) {
                    if (stopped()) break
                    ask(ECM, pid)
                }
                for ((e, dids) in plan) {
                    var silent = 0
                    for (did in dids) {
                        if (stopped()) break
                        if (ask(e, "22%04X".format(did)).isEmpty()) {
                            // Блок молчит — не тратим на него круг целиком.
                            if (++silent >= 3) break
                        } else silent = 0
                    }
                }
                rounds++
                val min = (SystemClock.uptimeMillis() - started) / 60_000
                status = "запись: круг $rounds, $min мин, запросов $sent" + (if (marks > 0) ", меток $marks" else "")
                if (SystemClock.uptimeMillis() - started > RECORD_MAX_MS) {
                    note("запись остановлена по времени")
                    break
                }
            }
            status = "запись: остановлена, кругов $rounds, запросов $sent"
            return true
        }
    }

    // ---------- файлы ----------

    private fun ecusFile(ctx: Context) = File(File(ctx.getExternalFilesDir(null), DIR), "ecus.txt")
    private fun foundFile(ctx: Context) = File(File(ctx.getExternalFilesDir(null), DIR), "found_dids.txt")

    private fun loadEcus(ctx: Context): List<Ecu> = try {
        ecusFile(ctx).takeIf { it.isFile }?.readLines().orEmpty().mapNotNull { l ->
            val p = l.trim().split('/')
            if (p.size == 2 && p.all { it.length == 3 }) Ecu(p[0], p[1]) else null
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun saveEcus(ctx: Context, ecus: List<Ecu>) {
        runCatching { ecusFile(ctx).writeText(ecus.joinToString("\n") + "\n") }
    }

    private fun loadFound(ctx: Context): List<Pair<Ecu, Int>> = try {
        foundFile(ctx).takeIf { it.isFile }?.readLines().orEmpty().mapNotNull { l ->
            val p = l.trim().split(' ')
            val did = p.getOrNull(1)?.toIntOrNull(16)
            val e = p.getOrNull(0)?.split('/')
            if (did != null && e != null && e.size == 2) Ecu(e[0], e[1]) to did else null
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun addFound(ctx: Context, e: Ecu, dids: List<Int>) {
        runCatching { foundFile(ctx).appendText(dids.joinToString("") { "$e %04X\n".format(it) }) }
    }

    /** Копия на флешку / SD (каталог приложения на съёмном томе), если он есть. */
    private fun copyToRemovable(ctx: Context, file: File) {
        if (!file.isFile) return
        val copies = ctx.getExternalFilesDirs(null).drop(1).filterNotNull().mapNotNull { root ->
            runCatching {
                val d = File(root, DIR).apply { mkdirs() }
                file.copyTo(File(d, file.name), overwrite = true).path
            }.getOrNull()
        }
        val where = (listOf(file.path) + copies).joinToString(", ")
        status = "$status · файл: $where"
    }

    private fun oneLine(r: String) =
        r.replace("\r", " ").replace("\n", " ").replace(ObdParser.PROMPT, ' ').replace(Regex("\\s+"), " ").trim()

    private const val TAG = "ElmScanner"
    private const val DIR = "obd_scan"
    private const val CMD_TIMEOUT_MS = 2_000L
    private const val SLOW_TIMEOUT_MS = 5_000L
    /** Столько DID подряд без ответа — проверяем, жив ли блок. */
    private const val SILENT_LIMIT = 24
    /** Запись не дольше (забыли выключить). */
    private const val RECORD_MAX_MS = 3 * 60 * 60_000L
    private const val NRC_PENDING = 0x78

    private val NRC_NAMES = mapOf(
        0x22 to "условия не выполнены",
        0x33 to "нет доступа (security)",
        0x14 to "ответ слишком длинный",
        0x7E to "не в этом сеансе",
        0x7F to "сервис не в этом сеансе",
    )
    private val INTERESTING_NRC = NRC_NAMES.keys

    private val ECM = Ecu("7E0", "7E8")

    /** Основные PID режима 01 в записи — опорные величины для сопоставления. */
    private val RECORD_PIDS = listOf("010C", "010D", "0104", "0105", "010E", "0111", "010B", "010F")

    private val NAMES = mapOf(
        "7E0" to "двигатель",
        "7E1" to "КПП (OBD)",
        "703" to "КПП",
        "700" to "шлюз?",
        "720" to "ABS/ESP",
        "740" to "TPMS",
        "74B" to "климат",
    )

    /** Блоки Tiggo 7 по логу Car Scanner — пока поиск не запускали. */
    private val DEFAULT_ECUS = listOf(
        Ecu("7E0", "7E8"), Ecu("7E1", "7E9"), Ecu("703", "713"), Ecu("700", "710"),
        Ecu("720", "730"), Ecu("740", "750"), Ecu("74B", "75B"),
    )

    /** Страницы DID (старший байт), где Car Scanner что-то читает. */
    private val KNOWN_PAGES = mapOf(
        "7E8" to listOf(0x01, 0x02), "710" to listOf(0x01, 0x02), "7E9" to listOf(0x0A),
        "713" to listOf(0x0A), "730" to listOf(0x30), "750" to listOf(0x34), "75B" to listOf(0x68),
    )

    private fun dids(vararg r: Any): Set<Int> = r.flatMap {
        when (it) {
            is Int -> listOf(it)
            is IntRange -> it.toList()
            else -> emptyList()
        }
    }.toSet()

    /** DID, которые Car Scanner опрашивает на Tiggo 7 (obd_log.txt), — основа записи. */
    private val SEED_DIDS: Map<Ecu, Set<Int>> = mapOf(
        Ecu("7E0", "7E8") to dids(0x0102, 0x0103, 0x0108, 0x010A, 0x010D, 0x0111, 0x0112, 0x0114, 0x0115,
            0x011A, 0x0122, 0x0125, 0x012E, 0x012F, 0x0132, 0x0136, 0x0141, 0x0148, 0x0149, 0x0156, 0x0166,
            0x016D, 0x0170..0x0177, 0x017C, 0x0181..0x0187, 0x018A, 0x01CA),
        Ecu("7E1", "7E9") to dids(0x0A10, 0x0A15..0x0A1F, 0x0A25..0x0A27, 0x0A2A, 0x0A2D, 0x0A37),
        Ecu("703", "713") to dids(0x0A31, 0x0A43..0x0A4A, 0x0A4F, 0x0A5F, 0x0A67, 0x0A68, 0x0A6A..0x0A6C,
            0x0A72, 0x0A73, 0x0A77, 0x0A78, 0x0A7B..0x0A7F, 0x0A81, 0x0A87..0x0A89, 0x0A8C..0x0A8E,
            0x0A92..0x0A96, 0x0AA4, 0x0AA5, 0x0AA7, 0x0AA8, 0x0AAA..0x0AAC, 0x0AAE, 0x0AC9, 0x0ACE),
        Ecu("720", "730") to dids(0x3003..0x3006, 0x300C, 0x300D, 0x300E, 0x3010, 0x3025, 0x3026, 0x3029, 0x302A),
        Ecu("740", "750") to dids(0x3400..0x340B),
        Ecu("74B", "75B") to dids(0x6801, 0x6802, 0x6803, 0x6807, 0x6808, 0x6809, 0x680A, 0x6813),
    )
}
