package com.dash.headunit.radio

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Штатное FM/AM-радио ГУ (com.desaysv.mediaapp, модуль moduleradio).
 *
 * Служба `com.desaysv.moduleradio.service.RadioAidlService` экспортирована
 * без android:permission, вызывающего не проверяет; к ней же привязаны
 * SVMediaService и приборка (SVMeterInteraction). Интерфейс восстановлен
 * разбором SVMedia.vdex (2026-11-10, docs/research/radio-aidl.md):
 *
 * `com.desaysv.localmediasdk.IAIDLMediaManager` (все аргументы — String):
 *  1 sendCommand(source, command) — play / pause / play_or_pause /
 *    next (поиск вперёд, SEEK_FORWARD) / pre (поиск назад) / change_collect;
 *  2 getSelectInfo(source, type) → JSON — play_info / play_status / collect_status;
 *  5 registerMediaInfoCallback(packageName, IAIDLMediaInfoCallback);
 *  6 unregisterMediaInfoCallback(packageName, callback).
 * source — "fm" или "am" (иное служба отвергает).
 *
 * `IAIDLMediaInfoCallback`: 1 onMediaInfoChange(source, type, json),
 * 2 onMediaPicChange(source, byte[]). JSON — Gson от MediaInfoBean:
 * mTitle — частота с единицей (getCalculateFrequencyAndUnit), mAlbum —
 * имя станции RDS (только FM), mPlayStatus, mCollectStatus.
 *
 * Сделать радио источником / переключить диапазон — startService
 * OutSideControlService с action startSource, key_start_source = fm|am.
 *
 * Транзакции — в своём потоке, слушатели зовутся в главном.
 */
@SuppressLint("StaticFieldLeak") // только applicationContext
object RadioHub {

    /** Состояние радио; поля null — служба ещё не сообщала. */
    data class State(
        val band: String = FM,
        /** Частота как её отдаёт служба, например «101.5MHz». */
        val frequency: String? = null,
        val station: String? = null,
        val playing: Boolean? = null,
        val favorite: Boolean? = null
    )

    @Volatile
    var state: State = State()
        private set

    /** Служба привязана и интерфейс отвечает. */
    @Volatile
    var connected = false
        private set

    /** Привязаться не удалось (нет SVMedia — эмулятор, другая прошивка). */
    @Volatile
    var unavailable = false
        private set

    private val thread = HandlerThread("Radio").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    private var app: Context? = null
    private var bound = false
    private var service: IBinder? = null
    private val listeners = mutableSetOf<() -> Unit>()
    private val log = ArrayDeque<String>()

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    /** Привязаться к службе (повторно — без эффекта). */
    fun init(context: Context) {
        val ctx = context.applicationContext
        worker.post {
            app = ctx
            if (service == null) connect(ctx)
        }
    }

    /** Перечитать состояние из службы. */
    fun refresh() {
        worker.post { service?.let { pull(it) } }
    }

    fun toggle() = command(CMD_PLAY_OR_PAUSE)
    fun seekUp() = command(CMD_NEXT)
    fun seekDown() = command(CMD_PRE)
    fun toggleFavorite() = command(CMD_COLLECT)

    /** Переключить FM ↔ AM (служба радио сделает его источником). */
    fun switchBand(context: Context) = startSource(context, if (state.band == AM) FM else AM)

    /** Сделать радио текущим источником звука, открыв штатный экран. */
    fun open(context: Context) = startSource(context, state.band, foreground = true)

    /**
     * Отдать звук другому источнику SVMedia ([source]: `bt_music`, `usb0`…):
     * штатный менеджер источников иначе держит радио, и play чужого плеера
     * (BT-сессии) звука не даёт. Радио, если играет, сначала ставим на паузу.
     */
    fun handOver(context: Context, source: String) {
        val ctx = context.applicationContext
        worker.post {
            if (service != null && state.playing == true) sendNow(CMD_PAUSE)
            startSource(ctx, source)
        }
    }

    /** Поставить радио на паузу, если оно играет (звук забирает другой плеер). */
    fun pauseIfPlaying() {
        worker.post { if (service != null && state.playing == true) sendNow(CMD_PAUSE) }
    }

    /** Текущая частота числом: МГц для FM, кГц для AM. */
    fun currentFrequency(): Double? = Json.parseFrequency(state.frequency)

    // ---------- перебор диапазона ----------

    /**
     * Перебор диапазона: команда «вперёд» переходит на следующую станцию из
     * списка, который тюнер собрал сам (AST у SVMedia), — так же, как стрелки
     * в штатном радио. Каждый шаг — отдельная настройка, поэтому ждём, пока
     * служба сообщит новую частоту.
     *
     * Наружу SVMedia список не отдаёт (docs/research/radio-aidl.md), поэтому
     * виджет собирает его сам — тем же перебором.
     *
     * [onStep] — найденная станция; [done] — обход закончен (true: круг
     * пройден) или список станций пуст (false).
     */
    fun scanStations(onStep: (RadioStations.Station) -> Unit, done: (Boolean) -> Unit) {
        startWalk(null) { gen ->
            walk(
                gen = gen,
                forward = true,
                target = null,
                onStep = { s -> main.post { onStep(s) } },
                done = { ok -> main.post { done(ok) } }
            )
        }
    }

    /**
     * Настроиться на частоту: перебором списка тюнера (вперёд или назад — к
     * ближайшей по частоте). Станции нет в списке тюнера — [done] вернёт false.
     */
    fun tuneTo(band: String, freq: Double, done: (Boolean) -> Unit) {
        startWalk(RadioStations.Station(band, freq)) { gen ->
            if (state.band != band) {
                app?.let { startSource(it, band) }
                Thread.sleep(BAND_SWITCH_MS)
            }
            val current = currentFrequency()
            // Уже на нужной станции — перебор не нужен.
            if (current != null && RadioStations.sameFreq(band, current, freq)) {
                main.post { done(true) }
                return@startWalk
            }
            val forward = current == null || freq >= current
            walk(
                gen = gen,
                forward = forward,
                target = freq,
                onStep = {},
                done = { ok -> main.post { done(ok) } }
            )
        }
    }

    /** Остановить перебор диапазона. */
    fun cancelWalk() {
        walkGen++
        walkingNow = false
        walkTarget = null
        walkChanged()
    }

    /** Перебор идёт прямо сейчас. */
    val walking: Boolean
        get() = walkingNow

    /**
     * Куда настраиваемся ([tuneTo]); null — настройки нет (или идёт
     * сканирование всего диапазона). Виджет показывает «Переключаю на…».
     */
    @Volatile
    var walkTarget: RadioStations.Station? = null
        private set

    @Volatile
    private var walkingNow = false

    /**
     * Номер текущего перебора: новый перебор отменяет прежний (шаг за шагом
     * их не смешать — команды тюнеру идут в общий канал).
     */
    @Volatile
    private var walkGen = 0

    private var walkThread: Thread? = null

    private fun startWalk(target: RadioStations.Station?, block: (Int) -> Unit) {
        val gen = ++walkGen
        walkTarget = target
        walkingNow = true
        walkChanged()
        walkThread = Thread({
            try {
                block(gen)
            } catch (e: Exception) {
                Log.w(TAG, "перебор диапазона", e)
            } finally {
                // Перебор сменили новым — флаги уже его.
                if (walkGen == gen) {
                    walkingNow = false
                    walkTarget = null
                    walkChanged()
                }
            }
        }, "RadioWalk").apply { start() }
    }

    /** Перебор начался или кончился: виджетам и учёту прослушивания. */
    private fun walkChanged() {
        app?.let { RadioStats.onState(it, state, walkingNow) }
        notifyListeners()
    }

    /**
     * Шаг перебора: команда «вперёд»/«назад» и ожидание новой частоты.
     * Частота не изменилась — станций в списке тюнера нет.
     *
     * Список тюнера кольцевой: когда он пройден целиком, мы снова попадаем
     * на первую станцию — на этом обход и заканчивается. Для настройки на
     * частоту это же означает, что такой станции в списке нет.
     */
    private fun walk(
        gen: Int,
        forward: Boolean,
        target: Double?,
        onStep: (RadioStations.Station) -> Unit,
        done: (Boolean) -> Unit
    ) {
        var steps = 0
        // Первая станция, на которую попали, — начало кольца.
        var first: Double? = null
        while (steps < MAX_STEPS) {
            if (walkGen != gen) break
            val before = currentFrequency()
            sendNow(if (forward) CMD_NEXT else CMD_PRE)
            if (!waitChange(before)) break
            val now = currentFrequency() ?: break
            steps++
            onStep(RadioStations.Station(state.band, now, rds = state.station))
            if (target != null && RadioStations.sameFreq(state.band, now, target)) {
                done(true)
                return
            }
            if (first == null) {
                first = now
            } else if (RadioStations.sameFreq(state.band, now, first!!)) {
                // Кольц пройден: нужной станции в списке нет.
                done(target == null)
                return
            }
        }
        done(false)
    }

    /**
     * Ждать новой частоты: тюнер настраивается не мгновенно, а счётчик
     * может сначала показать промежуточную частоту — ждём, пока значение
     * устоится.
     */
    private fun waitChange(before: Double?): Boolean {
        val deadline = System.currentTimeMillis() + STEP_TIMEOUT_MS
        var last: Double? = null
        var stable = 0
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(STEP_POLL_MS)
            val f = currentFrequency() ?: continue
            if (last != null && sameFreq(f, last)) {
                stable++
                if (stable >= 2 && (before == null || !sameFreq(f, before))) return true
            } else {
                stable = 0
                last = f
            }
        }
        return false
    }

    private fun sameFreq(a: Double, b: Double): Boolean =
        RadioStations.sameFreq(state.band, a, b)

    /** Последние события службы — для экрана отладки. */
    fun logLines(): List<String> = synchronized(log) { log.toList() }

    // ---------- служба ----------

    private fun command(cmd: String) {
        worker.post { sendNow(cmd) }
    }

    private fun sendNow(cmd: String) {
        val s = service
        if (s == null) {
            note("команда $cmd: служба не подключена")
            app?.let { connect(it) }
            return
        }
        transactStrings(s, TX_SEND_COMMAND, state.band, cmd, readString = false)
        note("-> $cmd (${state.band})")
        // Колбэк придёт сам; подстрахуемся чтением.
        worker.postDelayed({ service?.let { pull(it) } }, 600)
    }

    private fun startSource(context: Context, band: String, foreground: Boolean = false) {
        val intent = Intent(ACTION_START_SOURCE)
            .setComponent(ComponentName(PACKAGE, OUTSIDE_SERVICE))
            .putExtra("key_start_source", band)
            .putExtra("key_is_foreground", foreground)
            .putExtra("key_is_request_focus", true)
            .putExtra("key_open_reason", "other_app_start")
        try {
            context.applicationContext.startService(intent)
            note("startSource $band fg=$foreground")
        } catch (e: Exception) {
            note("startSource $band: ${e.javaClass.simpleName}: ${e.message}")
            try {
                context.applicationContext.startForegroundService(intent)
            } catch (e2: Exception) {
                note("startForegroundService: ${e2.javaClass.simpleName}: ${e2.message}")
            }
        }
        worker.postDelayed({ service?.let { pull(it) } }, 1500)
    }

    private fun connect(ctx: Context) {
        if (bound) return
        val intent = Intent().setComponent(ComponentName(PACKAGE, RADIO_SERVICE))
        bound = try {
            ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            note("bindService: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
        if (!bound) {
            note("служба радио не привязывается (нет $PACKAGE?)")
            unavailable = true
            notifyListeners()
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            worker.post {
                service = binder
                connected = true
                unavailable = false
                note("служба радио подключена")
                try {
                    binder.linkToDeath(death, 0)
                } catch (e: Exception) {
                    Log.w(TAG, "linkToDeath", e)
                }
                register(binder)
                pull(binder)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            worker.post { lost("отключилась") }
        }
    }

    private val death = IBinder.DeathRecipient { worker.post { lost("умерла") } }

    private fun lost(why: String) {
        note("служба радио $why")
        service = null
        connected = false
        notifyListeners()
        // BIND_AUTO_CREATE переподключит сам, когда SVMedia поднимется.
    }

    private fun register(s: IBinder) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(IMANAGER)
            data.writeString(app?.packageName ?: "com.dash.headunit")
            data.writeStrongBinder(callback)
            s.transact(TX_REGISTER, data, reply, 0)
            reply.readException()
            note("колбэк зарегистрирован")
        } catch (e: Exception) {
            note("registerMediaInfoCallback: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * Прочитать станцию, статус и избранное текущего диапазона. Какой
     * диапазон играет, служба по запросу не говорит — до первого колбэка
     * считаем FM.
     */
    private fun pull(s: IBinder) {
        val band = state.band
        transactStrings(s, TX_GET_SELECT_INFO, band, TYPE_PLAY_INFO)?.let { apply(band, TYPE_PLAY_INFO, it) }
        transactStrings(s, TX_GET_SELECT_INFO, band, TYPE_PLAY_STATUS)?.let { apply(band, TYPE_PLAY_STATUS, it) }
        transactStrings(s, TX_GET_SELECT_INFO, band, TYPE_COLLECT_STATUS)?.let { apply(band, TYPE_COLLECT_STATUS, it) }
    }

    private fun transactStrings(s: IBinder, code: Int, a: String, b: String, readString: Boolean = true): String? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IMANAGER)
            data.writeString(a)
            data.writeString(b)
            s.transact(code, data, reply, 0)
            reply.readException()
            if (readString) reply.readString() else null
        } catch (e: Exception) {
            note("tx $code($a,$b): ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private val callback = object : Binder() {
        init {
            attachInterface(null, ICALLBACK)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                TX_ON_INFO -> {
                    data.enforceInterface(ICALLBACK)
                    val source = data.readString()
                    val type = data.readString()
                    val json = data.readString()
                    reply?.writeNoException()
                    worker.post { apply(source, type, json) }
                    return true
                }
                TX_ON_PIC -> {
                    data.enforceInterface(ICALLBACK)
                    reply?.writeNoException()
                    return true
                }
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private fun apply(source: String?, type: String?, json: String?) {
        val next = Json.merge(state, source, type, json)
        note("<- $source/$type $json")
        if (next != state) {
            state = next
            app?.let { RadioStats.onState(it, next, walkingNow) }
            notifyListeners()
        }
    }

    private fun notifyListeners() {
        main.post { listeners.toList().forEach { it() } }
    }

    /** Строка в лог debug-экрана (память + logcat; на диск не пишем — решение 2026-11-13). */
    private fun note(s: String) {
        Log.i(TAG, s)
        val line = "${STAMP.format(java.util.Date())} $s"
        synchronized(log) {
            log.addLast(line)
            while (log.size > LOG_MAX) log.removeFirst()
        }
    }

    /** Разбор JSON службы (чистые функции — покрыты тестами). */
    object Json {
        /** Слить сообщение типа [type] от источника [source] в [old]. */
        fun merge(old: State, source: String?, type: String?, json: String?): State {
            val o = try {
                if (json.isNullOrBlank()) null else JSONObject(json)
            } catch (e: Exception) {
                null
            }
            val src = (source?.takeIf { it.isNotEmpty() } ?: o?.optString("source"))
                ?.lowercase()?.takeIf { it == FM || it == AM }
            // Диапазон меняют только станция и статус; «избранное» служба шлёт
            // и про другой диапазон (onAMCollectListChange при игре FM) — его пропускаем.
            if (type == TYPE_COLLECT_STATUS && src != null && src != old.band) return old
            var s = if (src != null && type != TYPE_COLLECT_STATUS) old.copy(band = src) else old
            if (o == null) return s
            when (type) {
                TYPE_PLAY_INFO -> {
                    val freq = o.str("mTitle")
                    // Смена частоты — прежнее имя станции уже не её.
                    val station = o.str("mAlbum")?.takeIf { it != UNKNOWN_STATION }
                    s = s.copy(frequency = freq ?: s.frequency, station = station)
                    if (o.has("mPlayStatus")) s = s.copy(playing = o.optBoolean("mPlayStatus"))
                    if (o.has("mCollectStatus")) s = s.copy(favorite = o.optBoolean("mCollectStatus"))
                }
                TYPE_PLAY_STATUS ->
                    if (o.has("mPlayStatus")) s = s.copy(playing = o.optBoolean("mPlayStatus"))
                TYPE_COLLECT_STATUS ->
                    if (o.has("mCollectStatus")) s = s.copy(favorite = o.optBoolean("mCollectStatus"))
            }
            return s
        }

        private fun JSONObject.str(key: String): String? =
            if (has(key) && !isNull(key)) optString(key).trim().takeIf { it.isNotEmpty() } else null

        /**
         * Частота «101.5MHz» → «101.5» и «MHz»; без единицы — всё в число.
         */
        fun splitFrequency(f: String?): Pair<String, String> {
            if (f.isNullOrBlank()) return "—" to ""
            val m = Regex("""^\s*([\d.,]+)\s*(\D*)\s*$""").find(f) ?: return f.trim() to ""
            return m.groupValues[1] to m.groupValues[2]
        }

        /**
         * Частота числом: «101.5MHz» → 101.5, «999 kHz» → 999.0.
         */
        fun parseFrequency(f: String?): Double? {
            if (f.isNullOrBlank()) return null
            val m = Regex("""\d+[.,]?\d*""").find(f) ?: return null
            return m.groupValues[0].replace(',', '.').toDoubleOrNull()
        }
    }

    const val FM = "fm"
    const val AM = "am"

    const val TYPE_PLAY_INFO = "play_info"
    const val TYPE_PLAY_STATUS = "play_status"
    const val TYPE_COLLECT_STATUS = "collect_status"

    /** Источник SVMedia «музыка по Bluetooth» (для [handOver]). */
    const val SOURCE_BT_MUSIC = "bt_music"

    private const val CMD_PLAY_OR_PAUSE = "play_or_pause"
    private const val CMD_PAUSE = "pause"
    private const val CMD_NEXT = "next"
    private const val CMD_PRE = "pre"
    private const val CMD_COLLECT = "change_collect"
    private const val UNKNOWN_STATION = "Unknown Station"

    /** Шагов перебора за один проход (список станций тюнера короче). */
    private const val MAX_STEPS = 64
    /** Сколько ждём новую частоту после команды (тюнер настраивается не мгновенно). */
    private const val STEP_TIMEOUT_MS = 4000L
    /** Как часто смотрим частоту во время ожидания. */
    private const val STEP_POLL_MS = 200L
    /** Пауза после смены диапазона — служба переключает источник. */
    private const val BAND_SWITCH_MS = 2500L

    private const val PACKAGE = "com.desaysv.mediaapp"
    private const val RADIO_SERVICE = "com.desaysv.moduleradio.service.RadioAidlService"
    private const val OUTSIDE_SERVICE = "com.desaysv.mediaapp.service.OutSideControlService"
    private const val ACTION_START_SOURCE = "com.desaysv.mediaapp.action.startSource"
    private const val IMANAGER = "com.desaysv.localmediasdk.IAIDLMediaManager"
    private const val ICALLBACK = "com.desaysv.localmediasdk.IAIDLMediaInfoCallback"
    private const val TX_SEND_COMMAND = IBinder.FIRST_CALL_TRANSACTION
    private const val TX_GET_SELECT_INFO = IBinder.FIRST_CALL_TRANSACTION + 1
    private const val TX_REGISTER = IBinder.FIRST_CALL_TRANSACTION + 4
    private const val TX_ON_INFO = IBinder.FIRST_CALL_TRANSACTION
    private const val TX_ON_PIC = IBinder.FIRST_CALL_TRANSACTION + 1
    private const val LOG_MAX = 80
    private val STAMP = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
    private const val TAG = "RadioHub"
}
