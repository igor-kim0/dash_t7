package com.dash.headunit.elm

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Связь с адаптером ELM327 по Bluetooth (классический SPP/RFCOMM)
 * и опрос OBD-II: обороты (010C), нагрузка (0104), температура ОЖ
 * (0105 — её нет ни на Desay-шине, ни у приборки, ни в VHAL ГУ),
 * напряжение бортовой сети (ATRV, меряет сам адаптер), температура
 * впускного воздуха (010F), дроссель (0111), топливные балансировки
 * B1 (0106/0107), давление впускного коллектора (010B) и уровень
 * топлива в баке (012F). Чего нет в маске ЭБУ (0100/0120), не спрашиваем;
 * прочие не ответившие величины отпадают сами.
 *
 * Работает как [com.dash.headunit.car.ClusterPps]: один фоновый поток
 * на весь процесс, статусы для debug-экрана. Адрес адаптера выбирается
 * в CarDebugActivity (список спаренных устройств) и хранится в prefs.
 *
 * Сокет: перебираем secure SPP, insecure SPP и скрытый RFCOMM-канал 1
 * (на части ГУ штатный SPP не открывается); удачный способ запоминаем
 * и в следующий раз пробуем первым.
 *
 * Если данных с ELM нет — значения null, и виджет показывает «—»:
 * на остальные виджеты ELM не влияет.
 */
object ElmHub {

    /** Стандартный UUID SPP, который поднимает ELM327. */
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    @Volatile
    var lastStatus: String = "не запускался"
        private set(value) {
            if (field == value) return
            field = value
            synchronized(history) {
                history.add("${System.currentTimeMillis()} $value")
                while (history.size > HISTORY_MAX) history.removeAt(0)
            }
        }

    private val history = mutableListOf<String>()

    fun statusHistory(): List<String> = synchronized(history) { history.toList() }

    /** Последние обмены «команда → ответ» с адаптером (для панели диагностики без adb). */
    private val exchanges = ArrayDeque<String>()

    fun trace(): List<String> = synchronized(exchanges) { exchanges.toList() }

    private fun traceExchange(cmd: String, reply: String) {
        synchronized(exchanges) {
            exchanges.addLast("$cmd → ${shortReply(reply).ifEmpty { "(тишина)" }}")
            while (exchanges.size > TRACE_MAX) exchanges.removeFirst()
        }
    }

    private val lock = Any()

    @Volatile
    private var savedAddress: String? = null
    @Volatile
    private var savedName: String? = null
    @Volatile
    private var savedMethod: String? = null
    private var appCtx: Context? = null

    fun address(): String? = savedAddress
    fun name(): String? = savedName

    fun setDevice(context: Context, address: String, name: String?) {
        if (appCtx == null) appCtx = context.applicationContext
        synchronized(lock) {
            if (savedAddress != address) savedMethod = null
            savedAddress = address
            savedName = name
            save()
        }
        lastStatus = "адаптер выбран: $address (${name ?: "?"})"
    }

    fun clearDevice(context: Context) {
        if (appCtx == null) appCtx = context.applicationContext
        synchronized(lock) {
            savedAddress = null
            savedName = null
            savedMethod = null
            save()
        }
        lastStatus = "адаптер сброшен"
    }

    // ---------- данные ----------

    /** Опрашиваемые величины: идентификатор, команда ELM, разбор ответа, срок годности. */
    private enum class Pid(
        val id: String,
        val cmd: String,
        val staleMs: Long,
        val slow: Boolean,
        val parse: (String) -> Float?
    ) {
        RPM("rpm", "010C", LIVE_STALE_MS, slow = false, parse = ObdParser::rpm),
        LOAD("load", "0104", LIVE_STALE_MS, slow = false, parse = ObdParser::engineLoad),
        COOLANT("coolant", "0105", COOLANT_STALE_MS, slow = true, parse = ObdParser::coolant),
        VOLTAGE("volt", "ATRV", LIVE_STALE_MS, slow = true, parse = ObdParser::voltage),
        INTAKE_AIR("iat", "010F", COOLANT_STALE_MS, slow = true, parse = ObdParser::intakeAir),
        THROTTLE("throttle", "0111", LIVE_STALE_MS, slow = false, parse = ObdParser::throttle),
        TRIM_SHORT("trim_short", "0106", LIVE_STALE_MS, slow = true, parse = ObdParser::fuelTrimShort),
        TRIM_LONG("trim_long", "0107", LIVE_STALE_MS, slow = true, parse = ObdParser::fuelTrimLong),
        MAP_KPA("map", "010B", LIVE_STALE_MS, slow = true, parse = ObdParser::mapPressure),
        FUEL_LEVEL("fuel_level", "012F", COOLANT_STALE_MS, slow = true, parse = ObdParser::fuelLevel);
    }

    private class Value(val v: Float, val at: Long)

    private val values = HashMap<Pid, Value>()

    private fun value(pid: Pid, now: Long): Float? = synchronized(values) {
        values[pid]?.takeIf { now - it.at < pid.staleMs }?.v
    }

    /**
     * Свежие показания OBD по идентификатору величины ([Pid.id]).
     * Величины, не отвечавшие дольше [Pid.staleMs] (или не поддерживаемые
     * ЭБУ вовсе), в карте отсутствуют.
     */
    fun readings(now: Long = SystemClock.uptimeMillis()): Map<String, Float> =
        synchronized(values) {
            values.entries.mapNotNull { (pid, v) ->
                if (now - v.at < pid.staleMs) pid.id to v.v else null
            }.toMap()
        }

    /** Температура ОЖ по ELM (PID 0105), °C, или null. */
    fun coolantC(now: Long = SystemClock.uptimeMillis()): Float? = value(Pid.COOLANT, now)

    /** Есть ли сейчас открытая сессия с адаптером. */
    @Volatile
    var connected: Boolean = false
        private set

    /** Каким способом открылся сокет в последний раз (для диагностики). */
    fun method(): String? = savedMethod

    // ---------- поток ----------

    @Volatile
    private var thread: Thread? = null

    /** Запускает чтение (повторный вызов ничего не делает). */
    @Synchronized
    fun start(context: Context) {
        if (appCtx == null) appCtx = context.applicationContext
        load()
        if (thread != null) return
        thread = Thread({ loop() }, "dash-elm-obd").apply {
            isDaemon = true
            start()
        }
    }

    private fun loop() {
        var backoff = RETRY_MIN_MS
        while (true) {
            val addr = synchronized(lock) { savedAddress }
            if (addr == null) {
                lastStatus = "адаптер ELM не выбран"
                sleep(IDLE_NO_DEVICE_MS)
                backoff = RETRY_MIN_MS
                continue
            }
            val ok = try {
                session(addr)
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                Log.w(TAG, "session", e)
                lastStatus = "ELM: ${e.javaClass.simpleName}: ${e.message}"
                false
            }
            // Были данные — переподключаемся быстро; нет — всё реже.
            backoff = if (ok) RETRY_MIN_MS else (backoff * 2).coerceAtMost(RETRY_MAX_MS)
            sleep(backoff)
        }
    }

    /**
     * Одна сессия: connect (перебор способов) → инициализация (ATZ, ATE0,
     * ATL0, ATS0, ATH0, поиск протокола и маски PID) → цикл опроса. Быстрые величины (обороты, нагрузка)
     * — каждый круг, медленные (ОЖ, напряжение) — каждый [SLOW_EVERY]-й.
     * Величина, не ответившая [MISS_LIMIT] раз подряд (ЭБУ её не отдаёт),
     * дальше спрашивается лишь раз в [RETRY_UNSUPPORTED] кругов.
     *
     * Протокол шины ищется до опроса ([findProtocol]): авто-поиск ELM идёт
     * дольше обычного таймаута ответа, а любой символ, пришедший во время
     * поиска, его прерывает («STOPPED») — раньше следующий PID через 5 с
     * обрывал поиск, и шина не находилась никогда (отвечал только ATRV).
     *
     * ATRV (напряжение) отвечает сам адаптер без шины, поэтому успех этого
     * PID ничего не говорит о связи с ЭБУ: молчание шинных PID считается
     * отдельно — через [PROTOCOL_SWITCH_MS] протокол ищется заново,
     * через [NO_ANSWER_RECONNECT_MS] без единого ответа — переподключение.
     * true — получен хоть один ответ.
     */
    @SuppressLint("MissingPermission")
    private fun session(address: String): Boolean {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            lastStatus = "Bluetooth выключен"
            return false
        }
        val remote = adapter.getRemoteDevice(address) // IllegalArgumentException → в [loop]
        if (adapter.isDiscovering) adapter.cancelDiscovery()
        val socket = open(remote) ?: return false
        var answers = 0
        socket.use { sock ->
            connected = true
            try {
                lastStatus = "подключился к ELM ($savedMethod), инициализация…"
                val input = sock.getInputStream()
                val output = sock.getOutputStream()
                // ATZ сбрасывает адаптер (на некоторых клонах banner идёт
                // до 2 с и может догнать после следующей команды — его
                // вычистит drain в [command]), затем выключаем эхо, а также
                // построчные/пробельные вставки (дефолты клонов плавают)
                // и ставим авто-протокол. ATH0 — без CAN-заголовка («7E8…»):
                // клон BT051525 по умолчанию его шлёт (парсер переживёт
                // и заголовок, но без него ответы короче).
                command(input, output, "ATZ")
                sleep(INIT_DELAY_MS)
                if (command(input, output, "ATE0") == null) return false
                command(input, output, "ATL0")
                command(input, output, "ATS0")
                command(input, output, "ATH0")
                var protocol = findProtocol(input, output) ?: return false
                lastStatus = if (protocol.isNotEmpty()) "ELM: протокол $protocol, читаю данные…"
                else noBusStatus()
                val misses = HashMap<Pid, Int>()
                var round = 0L
                var lastAnswerAt = SystemClock.uptimeMillis()
                /** Последний успешный шинный (не ATRV) PID — признак живой шины. */
                var lastDataAt = lastAnswerAt
                /** Статус смены протокола — держится, пока шинные PID не ответят. */
                var protoBanner: String? = if (protocol.isEmpty()) noBusStatus() else null
                while (true) {
                    for (pid in Pid.values()) {
                        if (pid.slow && round % SLOW_EVERY != 0L) continue
                        if (!supportedByEcu(pid)) continue
                        val miss = misses[pid] ?: 0
                        if (miss >= MISS_LIMIT && round % RETRY_UNSUPPORTED != 0L) continue
                        val reply = command(input, output, pid.cmd) ?: return answers > 0 // сокет умер
                        val v = pid.parse(reply)
                        if (v == null) {
                            misses[pid] = miss + 1
                            if (pid != Pid.VOLTAGE) lastBusReply = shortReply(reply)
                            Log.d(TAG, "${pid.cmd}: нет ответа «${reply.trim().takeLast(60)}»")
                        } else {
                            misses[pid] = 0
                            val now = SystemClock.uptimeMillis()
                            synchronized(values) { values[pid] = Value(v, now) }
                            lastAnswerAt = now
                            answers++
                            if (pid != Pid.VOLTAGE) lastDataAt = now
                            protoBanner = null
                        }
                    }
                    val now = SystemClock.uptimeMillis()
                    if (now - lastDataAt > PROTOCOL_SWITCH_MS) {
                        // Шина молчит дольше минуты (не нашли протокол,
                        // зажигание погасили/включили) — ищем заново.
                        // ATRV сюда не попадает — он шину не трогает.
                        lastStatus = "ELM: шина молчит — ищу протокол заново…"
                        protocol = findProtocol(input, output) ?: return answers > 0
                        misses.clear()
                        lastDataAt = SystemClock.uptimeMillis()
                        protoBanner = if (protocol.isEmpty()) noBusStatus() else null
                        continue
                    }
                    if (now - lastAnswerAt > NO_ANSWER_RECONNECT_MS) {
                        lastStatus = "ELM молчит ${NO_ANSWER_RECONNECT_MS / 1000} с — переподключаюсь"
                        return answers > 0
                    }
                    lastStatus = protoBanner ?: if (now - lastDataAt > PROTOCOL_SWITCH_MS / 2) {
                        "ELM: только напряжение — шина молчит (${lastBusReply.ifEmpty { "нет ответа" }})"
                    } else if (answers > 0) "ELM: данные идут (протокол ${protocol.ifEmpty { "?" }})"
                    else "ELM: ЭБУ не отвечает (зажигание?)"
                    round++
                    sleep(POLL_MS)
                }
            } finally {
                connected = false
            }
        }
        @Suppress("UNREACHABLE_CODE")
        return answers > 0
    }

    /**
     * Найти протокол шины: сначала авто-поиск ELM (ATSP0 + 0100 с долгим
     * ожиданием — поиск нельзя прерывать новыми командами), затем явные
     * [PROTOCOLS]. Возвращает номер протокола по ATDPN («A6», «6»…),
     * "" — ЭБУ не ответил ни на одном, null — сокет умер.
     */
    private fun findProtocol(input: InputStream, output: OutputStream): String? {
        supportedPids = emptySet()
        supportedUpTo = 0
        for (proto in PROTOCOLS) {
            lastStatus = if (proto == "0") "ELM: ищу протокол шины (авто)…"
            else "ELM: пробую протокол ATSP$proto…"
            command(input, output, "ATSP$proto") ?: return null
            val timeout = if (proto == "0") SEARCH_TIMEOUT_MS else PROBE_TIMEOUT_MS
            val reply = command(input, output, "0100", timeout) ?: return null
            val mask = ObdParser.supportedPids(reply, 0x00)
            if (mask != null) {
                val dpn = command(input, output, "ATDPN") ?: return null
                val name = dpn.lines().map { it.trim().trimStart(ObdParser.PROMPT).trim() }
                    .lastOrNull { it.isNotEmpty() && it != "OK" } ?: proto
                Log.i(TAG, "протокол: ATSP$proto → $name")
                readSupported(input, output, mask) ?: return null
                return name
            }
            lastBusReply = shortReply(reply)
            Log.d(TAG, "ATSP$proto: 0100 → «$lastBusReply»")
        }
        // Ничего не нашли — оставляем авто, чтобы ELM сам пробовал при опросе.
        command(input, output, "ATSP0") ?: return null
        return ""
    }

    /**
     * Запомнить маску PID из ответа на 0100 ([mask]) и, если ЭБУ
     * поддерживает 0120, дочитать маску 0x21..0x40 (там 012F — бак).
     * null — сокет умер.
     */
    private fun readSupported(input: InputStream, output: OutputStream, mask: Set<Int>): Unit? {
        var pids = mask
        var upTo = 0x20
        if (0x20 in mask) {
            val reply = command(input, output, "0120") ?: return null
            ObdParser.supportedPids(reply, 0x20)?.let {
                pids = pids + it
                upTo = 0x40
            }
        }
        supportedPids = pids
        supportedUpTo = upTo
        Log.i(TAG, "PID ЭБУ: " + pids.sorted().joinToString(" ") { "%02X".format(it) })
        return Unit
    }

    /**
     * Поддерживает ли ЭБУ величину по маске 0100/0120. PID вне прочитанных
     * масок (или если маски нет) и ATRV опрашиваем всегда.
     */
    private fun supportedByEcu(pid: Pid): Boolean {
        if (!pid.cmd.startsWith("01") || pid.cmd.length != 4) return true
        val num = pid.cmd.substring(2).toIntOrNull(16) ?: return true
        return num > supportedUpTo || num in supportedPids
    }

    private fun noBusStatus(): String =
        "ELM: ЭБУ не отвечает ни на одном протоколе (${lastBusReply.ifEmpty { "нет ответа" }}) — зажигание?"

    private fun shortReply(reply: String): String =
        reply.replace('\r', ' ').replace('\n', ' ').replace(ObdParser.PROMPT, ' ')
            .replace(Regex("\\s+"), " ").trim().take(40)

    /** Открыть сокет: сначала удачный в прошлый раз способ, затем остальные. */
    @Suppress("MissingPermission")
    private fun open(device: BluetoothDevice): BluetoothSocket? {
        val order = METHODS.sortedBy { if (it == savedMethod) 0 else 1 }
        val errors = mutableListOf<String>()
        for (m in order) {
            lastStatus = "подключаюсь к ELM ${device.name ?: "?"} ($m)…"
            val sock = try {
                when (m) {
                    M_SECURE -> device.createRfcommSocketToServiceRecord(SPP_UUID)
                    M_INSECURE -> device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                    else -> device.javaClass
                        .getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                        .invoke(device, 1) as BluetoothSocket
                }
            } catch (e: Exception) {
                errors += "$m: ${e.javaClass.simpleName}"
                continue
            }
            try {
                sock.connect()
            } catch (e: IOException) {
                runCatching { sock.close() }
                errors += "$m: ${e.message}"
                continue
            }
            if (savedMethod != m) {
                savedMethod = m
                save()
            }
            return sock
        }
        lastStatus = "ELM-сокет: " + errors.joinToString("; ")
        return null
    }

    /**
     * Отправить команду, прочитать ответ до приглашения «>».
     * Полный текст ответа или null (сбой сокета).
     * Перед записью входной поток вычищается от опоздавшего мусора
     * (banner после ATZ, хвост «SEARCHING…»), чтобы он не приклеился
     * к ответу этой команды.
     */
    private fun command(
        input: InputStream,
        output: OutputStream,
        cmd: String,
        timeoutMs: Long = RESPONSE_TIMEOUT_MS,
    ): String? {
        return try {
            val pending = input.available()
            if (pending > 0) {
                val junk = ByteArray(pending)
                input.read(junk)
                Log.d(TAG, "буфер: убрано «${String(junk, Charsets.US_ASCII).trim().take(60)}»")
            }
            output.write((cmd + '\r').toByteArray(Charsets.US_ASCII))
            output.flush()
            val sb = StringBuilder()
            val buffer = ByteArray(512)
            val deadline = System.currentTimeMillis() + timeoutMs
            // У BluetoothSocket нет soTimeout: read() блокируется навсегда,
            // если адаптер замолчал. Поэтому читаем только когда есть что
            // читать (available()), между чтениями — короткий сон, а полный
            // таймаут контролирует deadline.
            while (System.currentTimeMillis() < deadline) {
                val n = if (input.available() > 0) input.read(buffer) else 0
                if (n <= 0) {
                    if (System.currentTimeMillis() >= deadline) break
                    sleep(READ_POLL_MS)
                    continue
                }
                sb.append(String(buffer, 0, n, Charsets.US_ASCII))
                if (sb.isNotEmpty() && sb.last() == ObdParser.PROMPT) break
            }
            traceExchange(cmd, sb.toString())
            sb.toString()
        } catch (e: IOException) {
            lastStatus = "ELM сокет: ${e.javaClass.simpleName}"
            null
        }
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // ---------- prefs ----------

    private fun load() {
        val ctx = appCtx ?: return
        val addr = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ADDRESS, null) ?: return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(lock) {
            if (savedAddress == null) {
                savedAddress = addr
                savedName = prefs.getString(KEY_NAME, null)
            }
            if (savedMethod == null) savedMethod = prefs.getString(KEY_METHOD, null)
        }
    }

    private fun save() {
        val ctx = appCtx ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ADDRESS, savedAddress)
            .putString(KEY_NAME, savedName)
            .putString(KEY_METHOD, savedMethod)
            .apply()
    }

    private const val TAG = "ElmHub"
    private const val PREFS = "elm_settings"
    private const val KEY_ADDRESS = "address"
    private const val KEY_NAME = "name"
    private const val KEY_METHOD = "method"

    private const val M_SECURE = "SPP secure"
    private const val M_INSECURE = "SPP insecure"
    private const val M_CH1 = "RFCOMM ch1"
    private val METHODS = listOf(M_SECURE, M_INSECURE, M_CH1)
    private const val HISTORY_MAX = 50
    private const val TRACE_MAX = 12

    /** ОЖ меняется медленно — последнее значение показываем долго. */
    private const val COOLANT_STALE_MS = 15 * 60_000L
    /** Обороты/нагрузка/напряжение — только свежие. */
    private const val LIVE_STALE_MS = 10_000L
    /** Пауза между кругами опроса. */
    private const val POLL_MS = 500L
    /** ОЖ и напряжение — раз в столько кругов. */
    private const val SLOW_EVERY = 5L
    /** Столько промахов подряд — величину считаем неподдерживаемой. */
    private const val MISS_LIMIT = 3
    /** Неподдерживаемую величину перепроверяем раз в столько кругов. */
    private const val RETRY_UNSUPPORTED = 60L
    /** Ни одного ответа столько времени — переподключение. */
    private const val NO_ANSWER_RECONNECT_MS = 60_000L
    /** Шина молчит столько — пробуем следующий явный протокол. */
    private const val PROTOCOL_SWITCH_MS = 60_000L
    /**
     * Порядок поиска протокола: 0 — авто, затем явные: 6/7 — CAN 500 кбит
     * 11/29 бит, 8/9 — CAN 250 кбит, 3/4/5 — K-line, 1/2 — J1850.
     */
    private val PROTOCOLS = listOf("0", "6", "7", "8", "9", "3", "4", "5", "1", "2")
    /** PID режима 01 из масок 0100/0120 последнего найденного протокола. */
    @Volatile
    private var supportedPids: Set<Int> = emptySet()
    /** До какого номера PID маска прочитана (0 — маски нет). */
    @Volatile
    private var supportedUpTo = 0
    /** Последний ответ шинного PID, не давший данных (для статуса). */
    @Volatile
    private var lastBusReply = ""
    /** Ожидание ответа на один запрос. */
    private const val RESPONSE_TIMEOUT_MS = 5_000L
    /** Авто-поиск протокола ELM (перебор всех, K-line по 2–3 с). */
    private const val SEARCH_TIMEOUT_MS = 30_000L
    /** Проверка одного явного протокола (K-line init до ~5 с). */
    private const val PROBE_TIMEOUT_MS = 8_000L
    /** Пауза между проверками available() в ожидании ответа. */
    private const val READ_POLL_MS = 25L
    /** Задержка после ATZ — узнаём banner адаптера. */
    private const val INIT_DELAY_MS = 1_000L
    private const val RETRY_MIN_MS = 15_000L
    private const val RETRY_MAX_MS = 10 * 60_000L
    private const val IDLE_NO_DEVICE_MS = 30_000L
}
