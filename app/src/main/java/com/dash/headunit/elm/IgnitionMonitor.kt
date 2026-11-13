package com.dash.headunit.elm

import android.content.Context
import android.util.Log
import com.dash.headunit.car.CarHub
import java.io.File
import java.util.Locale

/**
 * Система зажигания по ELM (Tiggo 7, ЭБУ двигателя 7E0/7E8) — данные
 * для виджета «Зажигание».
 *
 * - Счётчики пропусков по цилиндрам (UDS 22, накопительные, 2 байта):
 *   Цил.1 = 22018A, Цил.2 = 220181, Цил.3 = 220182, Цил.4 = 220183.
 *   Сверено по трём снимкам (Car Scanner, февраль 2026; наш скан
 *   05.11; CSV Car Scanner 10.11 «[ECMv1] Misfires: Cyl.N»): значения
 *   только растут. Какой DID какому цилиндру — по совпадению величин.
 * - Зона пропусков (220184..87, по байту): мин./макс. обороты ×40,
 *   мин./макс. нагрузка /2.55 — совпало с CSV до цифры (FF/00/FE/00 =
 *   «пропусков не было»).
 * - Откат угла по детонации 220170..73 (по байту, ×0.375° — масштаб
 *   не сверен, на ХХ всегда 0).
 * - Ошибки: ожидающие (07) и сохранённые (03), из них P0300..P0304.
 * - Mode 06 (стандартный монитор пропусков OBD): MID A1 (все цилиндры),
 *   A2..A5 (цилиндры 1..4), TID 0B — средние за 10 циклов, 0C — текущий
 *   цикл. Отвечает ли наш ЭБУ — неизвестно: 06A0 спрашиваем раз за сессию.
 *
 * Сам счётчик о неисправности не говорит — важен прирост. Снимки
 * счётчиков с пробегом копятся в журнале в памяти (`ts;odoKm;ecuMin;c1..c4`,
 * на флеш не пишется), по нему считаются прирост на 1000 км и перекос
 * между цилиндрами.
 *
 * Опрашивает [ElmHub] (при любом профиле, только CAN 11 бит); сюда
 * приходят сырые ответы. Расчёты — чистые функции (тестируются).
 */
object IgnitionMonitor {

    const val CYL = 4

    /** DID счётчиков пропусков, по цилиндрам 1..4. */
    val COUNTER_CMDS = listOf("22018A", "220181", "220182", "220183")

    /** Откат по детонации, по цилиндрам 1..4. */
    val RETARD_CMDS = listOf("220170", "220171", "220172", "220173")

    /** Зона пропусков: мин. об., макс. об., мин. нагрузка, макс. нагрузка. */
    val AREA_CMDS = listOf("220184", "220185", "220186", "220187")

    const val PENDING_CMD = "07"
    const val STORED_CMD = "03"
    const val MODE06_SUPPORT_CMD = "06A0"

    /** MID монитора пропусков: A1 — все, A2..A5 — цилиндры 1..4. */
    val MODE06_CMDS = listOf("06A1", "06A2", "06A3", "06A4", "06A5")

    // ---------- состояние (пишет поток ElmHub, читает UI) ----------

    /** Виджет на экране — откат опрашиваем часто, иначе не опрашиваем вовсе. */
    @Volatile
    var liveWanted = false

    class Snapshot(
        /** Последние счётчики (null — ещё не читали). */
        val counters: IntArray?,
        /** Счётчики на начало поездки (первое чтение после перерыва). */
        val tripBase: IntArray?,
        val retardDeg: DoubleArray?,
        val area: Area?,
        val pending: List<String>?,
        val stored: List<String>?,
        /** null — ещё не спрашивали; пусто — ЭБУ Mode 06 не поддерживает. */
        val mode06: Map<Int, List<Mode06Test>>?,
        val readAt: Long,
    )

    @Volatile
    var snapshot = Snapshot(null, null, null, null, null, null, null, 0L)
        private set

    private val counters = arrayOfNulls<Int>(CYL)
    private val counterAt = LongArray(CYL)
    private var tripBase: IntArray? = null
    private var lastCountersAt = 0L
    private val retard = arrayOfNulls<Double>(CYL)
    private val area = arrayOfNulls<Int>(4)
    private var pending: List<String>? = null
    private var stored: List<String>? = null
    private var mode06Mids: Set<Int>? = null
    private val mode06 = LinkedHashMap<Int, List<Mode06Test>>()
    private var appCtx: Context? = null

    /** Минуты с ответами ЭБУ (≈ моточасы) — накопительно, в prefs. */
    private var ecuMinutes = 0.0
    private var lastAnswerAt = 0L
    /**
     * Журнал снимков — только в памяти (решение пользователя 2026-11-13:
     * без записи на флеш). Переживает сон ГУ (процесс заморожен), но не
     * его перезапуск. Старый файл [FILE] и минуты из prefs, если остались
     * от прежних версий, читаются один раз при старте и больше не пишутся.
     */
    private val rows = ArrayList<Row>()

    private val lock = Any()

    fun init(context: Context) {
        synchronized(lock) {
            if (appCtx != null) return
            appCtx = context.applicationContext
            ecuMinutes = prefs(context).getFloat(KEY_ECU_MIN, 0f).toDouble()
            try {
                file(context).takeIf { it.exists() }?.readText()?.let { rows.addAll(decodeRows(it)) }
            } catch (e: Exception) {
                Log.w(TAG, "старый журнал пропусков", e)
            }
        }
    }

    /** Новая сессия ELM: Mode 06 спросить заново. */
    fun onSession() {
        synchronized(lock) {
            mode06Mids = null
            mode06.clear()
        }
    }

    /** Поддержаны ли MID [cmd] (06Ax) — после ответа на 06A0. */
    fun mode06Wanted(cmd: String): Boolean {
        val mids = mode06Mids ?: return false
        return cmd.substring(2).toIntOrNull(16) in mids
    }

    /** Пора ли спросить 06A0 (ещё не спрашивали в этой сессии). */
    fun mode06ProbeWanted(): Boolean = mode06Mids == null

    /**
     * Ответ ЭБУ на одну из команд зажигания. Возвращает число для
     * [ElmHub.readings] (значение, число кодов…) или null — ответа нет.
     */
    fun accept(cmd: String, reply: String, nowWall: Long = System.currentTimeMillis()): Double? {
        val out: Double?
        synchronized(lock) {
            out = when (cmd) {
                in COUNTER_CMDS -> {
                    val v = didBytes(reply, cmd)?.let { word(it) } ?: return null
                    val i = COUNTER_CMDS.indexOf(cmd)
                    counters[i] = v
                    counterAt[i] = nowWall
                    if (i == CYL - 1) commitCounters(nowWall)
                    v.toDouble()
                }
                in RETARD_CMDS -> {
                    val b = didBytes(reply, cmd)?.firstOrNull() ?: return null
                    val deg = retardDeg(b)
                    retard[RETARD_CMDS.indexOf(cmd)] = deg
                    deg
                }
                in AREA_CMDS -> {
                    val b = didBytes(reply, cmd)?.firstOrNull() ?: return null
                    area[AREA_CMDS.indexOf(cmd)] = b
                    b.toDouble()
                }
                PENDING_CMD, STORED_CMD -> {
                    val codes = parseDtcs(reply, cmd) ?: return null
                    if (cmd == PENDING_CMD) pending = codes else stored = codes
                    codes.size.toDouble()
                }
                MODE06_SUPPORT_CMD -> {
                    val mids = parseMode06Support(reply)
                    // Нет ответа (NO DATA / 7F) — Mode 06 не поддерживается:
                    // в этой сессии больше не спрашиваем.
                    mode06Mids = mids ?: emptySet()
                    mids?.size?.toDouble()
                }
                in MODE06_CMDS -> {
                    val tests = parseMode06(reply, cmd.substring(2).toInt(16)) ?: return null
                    mode06[cmd.substring(2).toInt(16)] = tests
                    tests.size.toDouble()
                }
                else -> null
            }
            if (out != null) countEcuTime(nowWall)
            publish(nowWall)
        }
        return out
    }

    private fun countEcuTime(now: Long) {
        val gap = now - lastAnswerAt
        if (lastAnswerAt > 0 && gap in 1..ECU_GAP_MS) ecuMinutes += gap / 60_000.0
        lastAnswerAt = now
    }

    private fun publish(now: Long) {
        snapshot = Snapshot(
            counters = if (counters.all { it != null }) IntArray(CYL) { counters[it]!! } else null,
            tripBase = tripBase?.copyOf(),
            retardDeg = if (retard.all { it != null }) DoubleArray(CYL) { retard[it]!! } else null,
            area = if (area.all { it != null }) Area.of(area[0]!!, area[1]!!, area[2]!!, area[3]!!) else null,
            pending = pending,
            stored = stored,
            mode06 = mode06Mids?.let { LinkedHashMap(mode06) },
            readAt = now,
        )
    }

    /** Все четыре счётчика свежие — снимок в журнал (не чаще [ROW_MIN_MS], и только при изменении). */
    private fun commitCounters(now: Long) {
        if (counterAt.any { now - it > COUNTERS_FRESH_MS }) return
        if (counters.any { it == null }) return
        val c = IntArray(CYL) { counters[it]!! }
        val newTrip = now - lastCountersAt > TRIP_GAP_MS
        lastCountersAt = now
        val base = tripBase
        if (newTrip || base == null || c.indices.any { c[it] < base[it] }) tripBase = c.copyOf()
        synchronized(rows) {
            val last = rows.lastOrNull()
            val changed = last == null || !last.counters.contentEquals(c)
            if (!newTrip && !changed) return
            if (!newTrip && last != null && now - last.ts < ROW_MIN_MS) return
            rows.add(Row(now, CarHub.data?.odoKm, ecuMinutes, c))
            // Старше [KEEP_MS] и сверх [MAX_ROWS] — долой.
            rows.removeAll { now - it.ts >= KEEP_MS }
            while (rows.size > MAX_ROWS) rows.removeAt(0)
        }
    }

    /** Файл журнала прежних версий (только чтение при старте). */
    private fun file(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, FILE)

    /** Журнал снимков (из памяти), старые сначала. */
    @Suppress("UNUSED_PARAMETER")
    fun journal(context: Context): List<Row> = synchronized(rows) { rows.toList() }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- разбор ответов ----------

    /** Данные ответа на UDS 22 [cmd] (после «62 XX XX») или null. */
    internal fun didBytes(reply: String, cmd: String): List<Int>? {
        val id = "62" + cmd.substring(2)
        val bytes = ObdParser.scanResponse(ObdParser.joinFrames(reply), id) ?: return null
        return bytes.drop(3).takeIf { it.isNotEmpty() }
    }

    private fun word(b: List<Int>): Int? = if (b.size >= 2) b[0] * 256 + b[1] else null

    /** Откат угла, ° (масштаб Bosch 0.375°/бит — не сверен). */
    internal fun retardDeg(raw: Int): Double = raw * 0.375

    /**
     * Коды из ответа на 03/07 (43/47): на CAN за SID идёт число кодов,
     * затем по 2 байта на код (SAE J1979). null — ответа нет.
     * Пустой ответ «43 00» — пустой список.
     */
    internal fun parseDtcs(reply: String, cmd: String): List<String>? {
        val sid = "%02X".format(cmd.toInt(16) + 0x40)
        val bytes = ObdParser.scanResponse(ObdParser.joinFrames(reply), sid) ?: return null
        if (bytes.size < 2) return emptyList()
        val n = bytes[1]
        val body = bytes.drop(2)
        // Без байта числа (не-CAN ответ): пары с первого байта после SID.
        val pairs = if (n * 2 <= body.size) body.take(n * 2) else bytes.drop(1)
        return pairs.chunked(2).filter { it.size == 2 && (it[0] != 0 || it[1] != 0) }.map { dtc(it[0], it[1]) }
    }

    /** Код DTC из двух байт: старшие 2 бита — буква P/C/B/U. */
    internal fun dtc(a: Int, b: Int): String {
        val letter = "PCBU"[a shr 6]
        return letter + "%01X%01X%02X".format((a shr 4) and 3, a and 0x0F, b)
    }

    /** Номера цилиндров (1..4) с кодом пропусков P030N; 0 — P0300 (случайные). */
    fun misfireCylinders(codes: List<String>): List<Int> =
        codes.mapNotNull { c -> if (c.startsWith("P030") && c.length == 5) c[4].digitToIntOrNull() else null }
            .distinct().sorted()

    /** Маска MID из ответа на 06A0: номера A1..C0. null — нет ответа. */
    internal fun parseMode06Support(reply: String): Set<Int>? {
        val bytes = ObdParser.scanResponse(ObdParser.joinFrames(reply), "46A0") ?: return null
        if (bytes.size < 6) return null
        val out = HashSet<Int>()
        for (b in 0 until 4) for (bit in 0 until 8) {
            if (bytes[2 + b] and (0x80 shr bit) != 0) out += 0xA0 + b * 8 + bit + 1
        }
        return out
    }

    class Mode06Test(val tid: Int, val unit: Int, val value: Int, val min: Int, val max: Int)

    /**
     * Ответ на 06[mid] (CAN): «46», затем записи по 9 байт — MID, TID,
     * единица (UASID), значение, мин., макс. (по 2 байта).
     */
    internal fun parseMode06(reply: String, mid: Int): List<Mode06Test>? {
        val bytes = ObdParser.scanResponse(ObdParser.joinFrames(reply), "46%02X".format(mid)) ?: return null
        val out = ArrayList<Mode06Test>()
        var i = 1
        while (i + 9 <= bytes.size) {
            if (bytes[i] != mid) break
            val r = bytes.subList(i, i + 9)
            out += Mode06Test(r[1], r[2], r[3] * 256 + r[4], r[5] * 256 + r[6], r[7] * 256 + r[8])
            i += 9
        }
        return out.takeIf { it.isNotEmpty() }
    }

    // ---------- зона пропусков ----------

    class Area(val rpmMin: Int, val rpmMax: Int, val loadMin: Double, val loadMax: Double) {
        /** FF/00/FE/00 — значения «пусто»: пропусков не было. */
        val empty: Boolean get() = rpmMax == 0 || rpmMin > rpmMax

        companion object {
            fun of(rpmMin: Int, rpmMax: Int, loadMin: Int, loadMax: Int) =
                Area(rpmMin * 40, rpmMax * 40, loadMin / 2.55, loadMax / 2.55)
        }
    }

    // ---------- журнал и расчёты ----------

    class Row(val ts: Long, val odoKm: Int?, val ecuMin: Double, val counters: IntArray)

    internal fun encodeRow(r: Row): String =
        listOf(r.ts.toString(), r.odoKm?.toString().orEmpty(), String.format(Locale.US, "%.1f", r.ecuMin))
            .plus(r.counters.map { it.toString() }).joinToString(";")

    internal fun decodeRow(line: String): Row? {
        val p = line.trim().split(';')
        if (p.size != 3 + CYL) return null
        val ts = p[0].toLongOrNull() ?: return null
        val ecu = p[2].toDoubleOrNull() ?: return null
        val parsed = (0 until CYL).map { p[3 + it].toIntOrNull() ?: return null }
        val c = parsed.toIntArray()
        return Row(ts, p[1].toIntOrNull(), ecu, c)
    }

    internal fun decodeRows(text: String): List<Row> = text.lineSequence().mapNotNull(::decodeRow).toList()

    /**
     * Прирост за окно: по цилиндрам, пробег (км) и время работы ЭБУ (мин).
     * Если счётчик уменьшился (ЭБУ стёр после очистки ошибок), этот шаг
     * не считается — прирост идёт от нового значения.
     */
    class Growth(val perCyl: IntArray, val km: Int?, val ecuMin: Double, val fromTs: Long, val toTs: Long) {
        val total: Int get() = perCyl.sum()

        /** Пропуски на 1000 км по цилиндрам; null — пробега мало. */
        fun per1000km(): DoubleArray? {
            val d = km ?: return null
            if (d < MIN_KM) return null
            return DoubleArray(CYL) { perCyl[it] * 1000.0 / d }
        }

        /** Пропуски на час работы двигателя; null — времени мало. */
        fun perHour(): DoubleArray? {
            if (ecuMin < MIN_ECU_MIN) return null
            return DoubleArray(CYL) { perCyl[it] * 60.0 / ecuMin }
        }
    }

    fun growth(rows: List<Row>, fromTs: Long, toTs: Long = Long.MAX_VALUE): Growth? {
        val win = rows.filter { it.ts in fromTs..toTs }
        if (win.size < 2) return null
        val per = IntArray(CYL)
        var km = 0
        var kmKnown = false
        var ecu = 0.0
        for (k in 1 until win.size) {
            val a = win[k - 1]
            val b = win[k]
            for (i in 0 until CYL) {
                val d = b.counters[i] - a.counters[i]
                if (d > 0) per[i] += d
            }
            if (a.odoKm != null && b.odoKm != null && b.odoKm >= a.odoKm) {
                km += b.odoKm - a.odoKm
                kmKnown = true
            }
            val de = b.ecuMin - a.ecuMin
            if (de > 0) ecu += de
        }
        return Growth(per, if (kmKnown) km else null, ecu, win.first().ts, win.last().ts)
    }

    /**
     * Цилиндр-виновник (0-based) или null: прирост ≥ [SKEW_MIN] и
     * не меньше [SKEW_RATIO] × среднего по остальным. Пороги — подобрать
     * по машине (за 05.11→10.11 росло на 34..79 по цилиндрам).
     */
    fun skewedCylinder(perCyl: IntArray): Int? {
        val i = perCyl.indices.maxByOrNull { perCyl[it] } ?: return null
        val top = perCyl[i]
        if (top < SKEW_MIN) return null
        val others = perCyl.filterIndexed { k, _ -> k != i }
        val avg = others.average().coerceAtLeast(1.0)
        return if (top >= SKEW_RATIO * avg) i else null
    }

    /** Прирост по дням (локальная полночь), свежие сверху: день → Growth. */
    fun daily(rows: List<Row>, dayStart: (Long) -> Long): List<Pair<Long, Growth>> {
        if (rows.size < 2) return emptyList()
        val out = LinkedHashMap<Long, MutableList<Row>>()
        // Строка с предыдущего дня — опорная для первого шага дня.
        var prev: Row? = null
        for (r in rows) {
            val day = dayStart(r.ts)
            val list = out.getOrPut(day) { ArrayList<Row>().also { l -> prev?.let { l += it } } }
            list += r
            prev = r
        }
        return out.entries.mapNotNull { (day, list) -> growth(list, Long.MIN_VALUE)?.let { day to it } }
            .filter { it.second.total > 0 || (it.second.km ?: 0) > 0 }
            .reversed()
    }

    private const val TAG = "IgnitionMonitor"
    const val FILE = "ignition/misfire.csv"
    private const val PREFS = "ignition"
    private const val KEY_ECU_MIN = "ecu_min"

    /** Четыре счётчика должны быть прочитаны в пределах этого окна. */
    private const val COUNTERS_FRESH_MS = 120_000L
    /** Перерыв в чтениях дольше — новая поездка. */
    private const val TRIP_GAP_MS = 10 * 60_000L
    /** Строки журнала не чаще. */
    private const val ROW_MIN_MS = 10 * 60_000L
    /** Строк журнала в памяти (снимок — не чаще раза в 10 мин: это недели езды). */
    private const val MAX_ROWS = 5000
    /** Пауза между ответами ЭБУ дольше — зажигание было выключено, время не считаем. */
    private const val ECU_GAP_MS = 5 * 60_000L
    private const val KEEP_MS = 365L * 24 * 3600_000L

    const val MIN_KM = 50
    const val MIN_ECU_MIN = 60.0
    const val SKEW_MIN = 20
    const val SKEW_RATIO = 2.0
}
