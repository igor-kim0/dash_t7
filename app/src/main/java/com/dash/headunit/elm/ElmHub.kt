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
 * на весь процесс, статусы для виджета и debug-экрана. Адрес адаптера
 * выбирается в настройках виджета «OBD (ELM)» / панели диагностики
 * и хранится в prefs.
 *
 * Сокет: перебираем secure SPP, insecure SPP и скрытый RFCOMM-канал 1
 * (на части ГУ штатный SPP не открывается); удачный способ запоминаем
 * и в следующий раз пробуем первым.
 *
 * Если выбран JSON-профиль автомобиля ([ObdProfile], [ProfileStore]),
 * вместо стандартного набора опрашиваются его параметры: перед командой
 * ставится CAN-заголовок блока (ATSH), протокол из профиля пробуется
 * первым, VIN и прочие сведения читаются раз за сессию. Температура ОЖ
 * ([coolantC] — калибровка стрелки приборки) остаётся и с профилем:
 * параметр профиля с командой 0105 дублируется в «coolant», а без него
 * 0105 спрашивается отдельно (на CAN 11 бит — двигателю 7E0, иначе 7DF).
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

    /**
     * Опрашиваемая величина: идентификатор, команда ELM, CAN-заголовок
     * блока (null — не трогать, ответит кто угодно) и ID его ответа
     * (null — авто-фильтр ELM), разбор ответа, срок годности значения.
     */
    private class Poll(
        val id: String,
        val cmd: String,
        val header: String?,
        val rxHeader: String?,
        val staleMs: Long,
        val slow: Boolean,
        val parse: (String) -> Double?,
        /** Второй ключ того же значения в [readings] (0105 профиля → «coolant»). */
        val alias: String? = null,
    )

    private fun builtin(id: String, cmd: String, staleMs: Long, slow: Boolean, parse: (String) -> Float?) =
        Poll(id, cmd, null, null, staleMs, slow, parse = { parse(it)?.toDouble() })

    /** Стандартный набор OBD-II — когда профиль не выбран. */
    private val BUILTIN = listOf(
        builtin("rpm", "010C", LIVE_STALE_MS, false, ObdParser::rpm),
        builtin("load", "0104", LIVE_STALE_MS, false, ObdParser::engineLoad),
        builtin(COOLANT_ID, COOLANT_CMD, COOLANT_STALE_MS, true, ObdParser::coolant),
        builtin(VOLT_ID, "ATRV", LIVE_STALE_MS, true, ObdParser::voltage),
        builtin("iat", "010F", COOLANT_STALE_MS, true, ObdParser::intakeAir),
        builtin("throttle", "0111", LIVE_STALE_MS, false, ObdParser::throttle),
        builtin("trim_short", "0106", LIVE_STALE_MS, true, ObdParser::fuelTrimShort),
        builtin("trim_long", "0107", LIVE_STALE_MS, true, ObdParser::fuelTrimLong),
        builtin("map", "010B", LIVE_STALE_MS, true, ObdParser::mapPressure),
        builtin("fuel_level", "012F", COOLANT_STALE_MS, true, ObdParser::fuelLevel),
    )

    /** Ключ величины профиля в [readings]. */
    fun profileKey(p: ObdProfile.Param) = "p:" + p.key

    /**
     * Величины профиля по порядку из JSON; напряжение адаптера (ATRV)
     * опрашиваем всегда — это проверка, что сам ELM жив.
     */
    private fun profilePolls(profile: ObdProfile): List<Poll> {
        var hasCoolant = false
        val polls = profile.params.map { p ->
            val coolant = !hasCoolant && p.command == COOLANT_CMD
            if (coolant) hasCoolant = true
            Poll(profileKey(p), p.command, p.header, p.rxHeader, PROFILE_STALE_MS, slow = false, parse = p::decode,
                alias = if (coolant) COOLANT_ID else null)
        }
        // ОЖ нужна калибровке стрелки приборки и без профиля: если профиль
        // её не спрашивает — 0105 отдельно (на CAN — двигателю 7E0, иначе 7DF), раз в SLOW_EVERY
        // кругов. Заголовок нужен, лишь если профиль переключает блоки.
        val extra = BUILTIN.filter { it.id == VOLT_ID || (!hasCoolant && it.id == COOLANT_ID) }.map {
            if (it.id == COOLANT_ID && profile.params.any { p -> p.header != null })
                Poll(it.id, it.cmd, BROADCAST_HEADER, null, it.staleMs, it.slow, it.parse) else it
        }
        return polls + extra
    }

    private class Value(val v: Double, val at: Long, val staleMs: Long)

    private val values = HashMap<String, Value>()

    /**
     * Свежие показания по идентификатору величины ([Poll.id]; для профиля —
     * [profileKey]). Величины, не отвечавшие дольше своего срока (или не
     * поддерживаемые ЭБУ вовсе), в карте отсутствуют.
     */
    fun readings(now: Long = SystemClock.uptimeMillis()): Map<String, Double> =
        synchronized(values) {
            values.entries.mapNotNull { (id, v) -> if (now - v.at < v.staleMs) id to v.v else null }.toMap()
        }

    /** Температура ОЖ по ELM (PID 0105), °C, или null. */
    fun coolantC(now: Long = SystemClock.uptimeMillis()): Float? = readings(now)[COOLANT_ID]?.toFloat()

    /** Величины, переставшие отвечать ([MISS_LIMIT] промахов подряд), — экран пишет «н/д». */
    @Volatile
    var silent: Set<String> = emptySet()
        private set

    // ---------- профиль ----------

    @Volatile
    private var profile: ObdProfile? = null
    @Volatile
    private var profileId: String? = null
    @Volatile
    private var vehicleInfo: Map<String, String> = emptyMap()
    /** Растёт при смене профиля — текущая сессия начинается заново. */
    @Volatile
    private var profileGen = 0

    fun profile(): ObdProfile? = profile
    fun profileId(): String? = profileId

    /** Сведения, прочитанные по профилю за сессию (заголовок → значение: «VIN» → …). */
    fun vehicleInfo(): Map<String, String> = vehicleInfo

    /**
     * Выбрать профиль (null — стандартный OBD-II). Загружает JSON,
     * запоминает выбор и перезапускает сессию с адаптером.
     * Ошибка разбора — исключение, выбор не меняется.
     */
    fun setProfile(context: Context, id: String?) {
        if (appCtx == null) appCtx = context.applicationContext
        val p = id?.let { ProfileStore.load(context, it) }
        ProfileStore.select(context, id)
        applyProfile(id, p)
        lastStatus = if (p == null) "профиль: стандартный OBD-II" else "профиль: ${p.name}"
    }

    private fun applyProfile(id: String?, p: ObdProfile?) {
        profileId = id
        profile = p
        vehicleInfo = emptyMap()
        silent = emptySet()
        synchronized(values) { values.clear() }
        profileGen++
    }

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
        if (profileId == null) {
            ProfileStore.selectedId(context)?.let { id ->
                try {
                    applyProfile(id, ProfileStore.load(context, id))
                } catch (e: Exception) {
                    Log.w(TAG, "профиль $id", e)
                    if (id.startsWith("asset:") &&
                        context.assets.list("profiles").orEmpty().none { it == id.substringAfter(':') }) {
                        // встроенный профиль убран из сборки — опрашиваем стандартный OBD-II
                        ProfileStore.select(context, null)
                    } else {
                        lastStatus = "профиль $id не читается: ${e.message}"
                    }
                }
            }
        }
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
                val gen = profileGen
                val until = SystemClock.uptimeMillis() + IDLE_NO_DEVICE_MS
                while (SystemClock.uptimeMillis() < until && gen == profileGen && savedAddress == null) sleep(500)
                backoff = RETRY_MIN_MS
                continue
            }
            val gen = profileGen
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
            if (gen != profileGen) {
                backoff = RETRY_MIN_MS
                continue // сменили профиль — сразу новая сессия
            }
            // Ждём по кусочку, чтобы смена профиля не ждала конца паузы.
            val until = SystemClock.uptimeMillis() + backoff
            while (SystemClock.uptimeMillis() < until && gen == profileGen) sleep(500)
            if (gen != profileGen) backoff = RETRY_MIN_MS
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
                // Внешний цикл — одна итерация на профиль: смена профиля
                // переинициализирует адаптер на том же сокете.
                while (true) {
                    command(input, output, "ATZ")
                    sleep(INIT_DELAY_MS)
                    if (command(input, output, "ATE0") == null) return answers > 0
                    command(input, output, "ATL0")
                    command(input, output, "ATS0")
                    command(input, output, "ATH0")
                    val gen = profileGen
                    val prof = profile
                    val polls = prof?.let(::profilePolls) ?: BUILTIN
                    var protocol = findProtocol(input, output, prof?.elmProtocol) ?: return answers > 0
                    lastStatus = if (protocol.isNotEmpty()) "ELM: протокол $protocol, читаю данные…"
                    else noBusStatus()
                    /** Текущий CAN-заголовок (ATSH); null — по умолчанию адаптера. */
                    var header: String? = null
                    /** Текущий фильтр ответа (ATCRA); null — авто (ATAR). */
                    var rxHeader: String? = null
                    /**
                     * Переключить заголовок (и фильтр ответа) под блок; false — сокет умер.
                     * Блоки вне 7E0..7E7 отвечают с ID, который ELM сам не ловит
                     * (740 → 750), — для них профиль задаёт ATCRA.
                     */
                    fun target(h: String?, rx: String?): Boolean {
                        if (h == null || protocol.isEmpty()) return true
                        if (h != header) {
                            command(input, output, "ATSH$h") ?: return false
                            header = h
                        }
                        if (rx != rxHeader) {
                            command(input, output, if (rx == null) "ATAR" else "ATCRA$rx") ?: return false
                            rxHeader = rx
                        }
                        return true
                    }
                    val timeout = prof?.timeoutMs?.coerceIn(500L, RESPONSE_TIMEOUT_MS) ?: RESPONSE_TIMEOUT_MS
                    if (prof != null && protocol.isNotEmpty()) {
                        // VIN и прочие сведения — один раз за сессию.
                        val info = LinkedHashMap<String, String>()
                        for (item in prof.info) {
                            if (!target(item.header, item.rxHeader)) return answers > 0
                            val reply = command(input, output, item.command, INFO_TIMEOUT_MS) ?: return answers > 0
                            item.decode(reply)?.let { info[item.title] = it }
                        }
                        vehicleInfo = info
                    }
                    val misses = HashMap<String, Int>()
                    /**
                     * CAN 11 бит: OBD-запросы без своего блока (стандартный набор,
                     * ОЖ для стрелки) — двигателю (7E0), а не всем (7DF): иначе
                     * первой отвечает КПП (7E9) со своими значениями
                     * (дроссель 0 %, ОЖ −40 °C — лог Car Scanner, Tiggo 7).
                     */
                    var can11 = isCan11(protocol)
                    fun headerOf(poll: Poll): String? = when {
                        poll.cmd.startsWith("AT") || !can11 -> poll.header
                        poll.header == null || poll.header == BROADCAST_HEADER -> ECM_HEADER
                        else -> poll.header
                    }
                    var round = 0L
                    var lastAnswerAt = SystemClock.uptimeMillis()
                    /** Последний успешный шинный (не ATRV) PID — признак живой шины. */
                    var lastDataAt = lastAnswerAt
                    /** Статус смены протокола — держится, пока шинные PID не ответят. */
                    var protoBanner: String? = if (protocol.isEmpty()) noBusStatus() else null
                    poll@ while (true) {
                        if (gen != profileGen) {
                            // Сокет не рвём: клоны ELM часто не принимают
                            // повторное подключение сразу после разрыва, и
                            // опрос вставал на минуты (backoff). Сбрасываем
                            // адаптер (ATZ) и начинаем с нового профиля.
                            lastStatus = "ELM: профиль сменился — переинициализация…"
                            break@poll
                        }
                        val scan = ElmScanner.takePending()
                        if (scan != null) {
                            // Сканер работает на этом же сокете; после него
                            // адаптер переинициализируется (ATZ) — опрос дальше.
                            lastStatus = "ELM: сканер — ${scan.mode.title}…"
                            val ctx = appCtx
                            if (ctx != null && !ElmScanner.run(ctx, scan, protocol) { c, t -> command(input, output, c, t) }) {
                                return answers > 0
                            }
                            break@poll
                        }
                        /** Ответы этого круга: одна команда (0134, 0101) — один запрос. */
                        val replies = HashMap<String, String>()
                        for (poll in polls) {
                            if (gen != profileGen || ElmScanner.hasPending) break
                            if (poll.slow && round % SLOW_EVERY != 0L) continue
                            if (!supportedByEcu(poll.cmd)) continue
                            val miss = misses[poll.id] ?: 0
                            if (miss >= MISS_LIMIT && round % RETRY_UNSUPPORTED != 0L) continue
                            val h = headerOf(poll)
                            val replyKey = "$h|${poll.rxHeader}|${poll.cmd}"
                            val reply = replies[replyKey] ?: run {
                                if (!target(h, poll.rxHeader)) return answers > 0
                                val r = command(input, output, poll.cmd, timeout) ?: return answers > 0 // сокет умер
                                replies[replyKey] = r
                                r
                            }
                            val v = poll.parse(reply)
                            if (v == null) {
                                misses[poll.id] = miss + 1
                                if (poll.id != VOLT_ID) lastBusReply = shortReply(reply)
                                Log.d(TAG, "${poll.cmd}: нет ответа «${reply.trim().takeLast(60)}»")
                            } else {
                                misses[poll.id] = 0
                                val now = SystemClock.uptimeMillis()
                                synchronized(values) {
                                    values[poll.id] = Value(v, now, poll.staleMs)
                                    poll.alias?.let { values[it] = Value(v, now, COOLANT_STALE_MS) }
                                }
                                lastAnswerAt = now
                                answers++
                                if (poll.id != VOLT_ID) lastDataAt = now
                                protoBanner = null
                            }
                        }
                        silent = misses.filterValues { it >= MISS_LIMIT }.keys.toSet()
                        val now = SystemClock.uptimeMillis()
                        if (now - lastDataAt > PROTOCOL_SWITCH_MS) {
                            // Шина молчит дольше минуты (не нашли протокол,
                            // зажигание погасили/включили) — ищем заново.
                            // ATRV сюда не попадает — он шину не трогает.
                            lastStatus = "ELM: шина молчит — ищу протокол заново…"
                            // 0100 поиска должен уйти всем (7DF) и ответ — пройти
                            // фильтр: заголовок и ATCRA последнего блока мешают.
                            if (header != null) command(input, output, "ATSH7DF") ?: return answers > 0
                            if (rxHeader != null) command(input, output, "ATAR") ?: return answers > 0
                            protocol = findProtocol(input, output, prof?.elmProtocol) ?: return answers > 0
                            can11 = isCan11(protocol)
                            header = null
                            rxHeader = null
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
    private fun findProtocol(input: InputStream, output: OutputStream, preferred: String? = null): String? {
        supportedPids = emptySet()
        supportedUpTo = 0
        // Протокол из профиля пробуем первым — явный CAN находится за секунду.
        val order = if (preferred == null) PROTOCOLS else listOf(preferred) + PROTOCOLS.filter { it != preferred }
        for (proto in order) {
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
     * Запомнить маску PID из ответа на 0100 ([mask]) и дочитать
     * следующие маски по цепочке: бит 0x20 → 0120 (там 012F — бак),
     * бит 0x40 → 0140 (0142 напряжение ЭБУ, 0143–014C — профиль) и т. д.
     * null — сокет умер.
     */
    private fun readSupported(input: InputStream, output: OutputStream, mask: Set<Int>): Unit? {
        var pids = mask
        var upTo = 0x20
        while (upTo < 0xC0 && upTo in pids) {
            val reply = command(input, output, "01%02X".format(upTo)) ?: return null
            val next = ObdParser.supportedPids(reply, upTo) ?: break
            pids = pids + next
            upTo += 0x20
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
    private fun supportedByEcu(cmd: String): Boolean {
        if (!cmd.startsWith("01") || cmd.length != 4) return true
        val num = cmd.substring(2).toIntOrNull(16) ?: return true
        return num > supportedUpTo || num in supportedPids
    }

    /** ATDPN «6»/«A6» (500 кбит) или «8»/«A8» (250 кбит) — CAN 11 бит, где ЭБУ двигателя = 7E0. */
    private fun isCan11(protocol: String): Boolean = protocol.removePrefix("A") in setOf("6", "8")

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
    private const val VOLT_ID = "volt"
    private const val COOLANT_ID = "coolant"
    private const val COOLANT_CMD = "0105"
    /** Функциональный (всем ЭБУ) CAN-заголовок OBD-II. */
    private const val BROADCAST_HEADER = "7DF"
    /** Физический адрес ЭБУ двигателя на CAN 11 бит (ISO 15765-4). */
    private const val ECM_HEADER = "7E0"
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
    /**
     * Величины профиля: круг из десятков команд идёт секунды, поэтому
     * значение живёт дольше, чем у стандартного набора.
     */
    private const val PROFILE_STALE_MS = 30_000L
    /** Ожидание многокадрового ответа (VIN). */
    private const val INFO_TIMEOUT_MS = 3_000L
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
    /** PID режима 01 из масок 0100/0120/0140… последнего найденного протокола. */
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
