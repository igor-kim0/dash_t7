package com.dash.headunit.car

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Зонд событийной подписки на шину Desay SV ([вариант 2] ускорения данных).
 *
 * Вопрос, на который отвечает зонд: умеет ли штатный сервис
 * `com.desaysv.svcarinfo/.CarInfoService` сам звать подписчика при
 * изменении ячейки шины (как это делает Jarvis для климата) — тогда
 * передачу/обороты можно показывать без 5-секундного цикла [CarHub].
 *
 * Протокол (вытащен из Jarvis, `f2.i()` / `e2.java`):
 * - регистрация: `transact(1)` по `ICarInfo`; в parcel — токен интерфейса,
 *   `writeInt(firstInt)`, `writeStrongBinder(callback)`, `writeIntArray(ids)`,
 *   флаг ONEWAY. Jarvis передавал `firstInt=16` и набор климата;
 * - callback — свой [Binder] с дескриптором `ICarInfoCallBack`; сервис
 *   присылает транзакцию `1` с ДВУМЯ int. Jarvis смотрел только первый
 *   (`== 16`). Семантика пары (a, b) неизвестна — вероятно, эхо подписки и
 *   id изменившейся ячейки, но это и проверяем;
 * - отмены подписки в Jarvis нет: переподписка при смене binder.
 *
 * Зонд только читает (подписка + `transact(3)`), ничего не пишет в CAN.
 * Регистрируется матрицей «firstInt × набор id» — каждый со своим callback,
 * чтобы по тегу видеть, какая комбинация реально приносит события.
 * Данные нигде не сохраняются: экран виджета + logcat (тег `DesayPush`).
 */
object DesayPushProbe {

    /** Событие callback: [tag] — комбинация подписки, [a]/[b] — два int сервиса. */
    data class Event(val wallMs: Long, val tag: String, val a: Int, val b: Int)

    /** Последнее прочитанное значение ячейки шины. */
    data class CellVal(val raw: Int, val atMs: Long, val latencyMs: Long)

    /** Набор подписок (что регистрируем). */
    enum class Preset {
        /** Максимум данных: {16, 4, 0} × id 0..255. */
        MAX,

        /** Передача и обороты: {16, 4} × {26, 62}. */
        GEAR,

        /** Как в Jarvis: 16 × набор климата. */
        JARVIS;

        val label: String
            get() = when (this) {
                MAX -> "Максимум"
                GEAR -> "Передача/обороты"
                JARVIS -> "Набор Jarvis"
            }
    }

    @Volatile
    var preset: Preset = Preset.MAX

    @Volatile
    var status: String = "не запущена"
        private set

    @Volatile
    var registeredTags: List<String> = emptyList()
        private set

    @Volatile
    var notifications: Long = 0L
        private set

    /** Задержка «callback → перечитанные значения», мс (последнее событие). */
    @Volatile
    var lastEventLatencyMs: Long = -1L
        private set

    private val events = ArrayDeque<Event>()
    private val idCounts = HashMap<Int, Int>()
    private val cells = HashMap<String, CellVal>()
    private val callbacks = mutableListOf<Callback>()

    @Volatile
    private var context: Context? = null

    @Volatile
    private var connection: ServiceConnection? = null

    @Volatile
    private var binder: IBinder? = null

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "dash-push-probe").apply { isDaemon = true }
    }
    private val pendingRead = AtomicBoolean(false)

    @Volatile
    private var polling = false
    private var pollThread: Thread? = null

    val running: Boolean get() = connection != null

    /**
     * Запустить подписку на [context] (повторный вызов ничего не делает).
     * Не блокирует: вызывается из UI-потока, а [ServiceConnection] приходит
     * в тот же главный поток — ждать его синхронно нельзя (всегда тайм-аут).
     * Регистрация идёт в [onServiceConnected] на фоновом [worker].
     */
    @Synchronized
    fun start(context: Context) {
        if (connection != null) return
        val app = context.applicationContext
        if (!serviceAvailable(app)) {
            status = "сервис Desay не найден (эмулятор?)"
            return
        }
        this.context = app
        clearData()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (connection !== this) return
                binder = service
                worker.execute { onBound(this, service) }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                binder = null
                if (connection === this) status = "сервис отключился, ждём переподключения"
            }
        }
        connection = conn
        val bound = try {
            app.bindService(
                Intent().setClassName(SERVICE_PKG, SERVICE_CLASS),
                conn,
                Context.BIND_AUTO_CREATE
            )
        } catch (e: Exception) {
            Log.w(TAG, "bind", e)
            false
        }
        if (!bound) {
            status = "bind не удался"
            stopInternal()
            return
        }
        status = "подключаемся к сервису…"
    }

    /** Сервис (пере)подключён — регистрируем подписки (фоновый поток). */
    private fun onBound(conn: ServiceConnection, b: IBinder?) {
        if (connection !== conn) return
        if (b == null || !b.isBinderAlive) {
            status = "сервис вернул пустой binder"
            return
        }
        registerAll(b)
        startPolling()
        status = "подписок ${registeredTags.size}/${specs().size}, событий $notifications"
        readCells(0L)
    }

    /** Остановить подписку и отвязаться от сервиса. */
    fun stop() {
        stopInternal()
        status = "остановлена"
    }

    /** Сбросить журнал (подписки не трогает). */
    fun reset() {
        clearData()
        status = "сброшено, подписок ${registeredTags.size}"
    }

    fun events(): List<Event> = synchronized(events) { events.toList() }

    /** Индексы-кандидаты из второго int callback: (id, сколько раз пришёл). */
    fun topIds(limit: Int = 8): List<Pair<Int, Int>> =
        synchronized(idCounts) { idCounts.entries.sortedByDescending { it.value }.take(limit).map { it.key to it.value } }

    /** Ключи интересных ячеек, по которым снимаем значения. */
    val cellKeys: List<String> get() = INTERESTING.map { "${it.first},${it.second}" }

    fun cell(key: String): CellVal? = synchronized(cells) { cells[key] }

    /** Человекочитаемое значение ячейки (сырое + разбор для известных). */
    fun cellText(key: String): String {
        val v = cell(key) ?: return "—"
        val parsed = when (key) {
            "4,26" -> "P/R/N/1..7"
            "4,62" -> CarScaling.rpmDesay(v.raw)?.let { "об $it" } ?: "?"
            "4,68" -> CarScaling.desayFuelPct(v.raw)?.let { "%.1f%%".format(it) } ?: "?"
            "16,19" -> CarScaling.outsideTemp(v.raw)?.let { "%.1f°".format(it) } ?: "?"
            "0,27" -> if (v.raw == 1) "работает" else "нет"
            else -> null
        }
        return if (parsed == null) "${v.raw}" else "${v.raw} ($parsed)"
    }

    // --- подписки ---------------------------------------------------------

    private fun registerAll(binder: IBinder) {
        callbacks.clear()
        val ok = mutableListOf<String>()
        for ((firstInt, ids, name) in specs()) {
            val tag = "$firstInt/$name"
            val cb = Callback(tag)
            val sent = try {
                val data = Parcel.obtain()
                try {
                    data.writeInterfaceToken(IFACE)
                    data.writeInt(firstInt)
                    data.writeStrongBinder(cb)
                    data.writeIntArray(ids)
                    binder.transact(TRANSACT_REGISTER, data, null, IBinder.FLAG_ONEWAY)
                    true
                } finally {
                    data.recycle()
                }
            } catch (e: Exception) {
                Log.w(TAG, "register $tag", e)
                false
            }
            if (sent) {
                callbacks.add(cb)
                ok.add(tag)
            }
        }
        registeredTags = ok
        Log.i(TAG, "зарегистрировано: $ok")
    }

    private fun specs(): List<Triple<Int, IntArray, String>> {
        val broad = IntArray(256) { it }
        return when (preset) {
            Preset.MAX -> listOf(16, 4, 0).flatMap { first ->
                listOf(Triple(first, broad, "0..255"))
            }
            Preset.GEAR -> listOf(16, 4).flatMap { first ->
                listOf(Triple(first, intArrayOf(26, 62), "26,62"))
            }
            Preset.JARVIS -> listOf(Triple(16, intArrayOf(10, 12, 18, 4, 3, 1, 8, 24, 65), "climate"))
        }
    }

    /** Пришло событие от сервиса (binder-поток сервиса — не блокируем его). */
    private fun onEvent(tag: String, a: Int, b: Int) {
        val wall = System.currentTimeMillis()
        synchronized(events) {
            events.addLast(Event(wall, tag, a, b))
            while (events.size > EVENT_MAX) events.removeFirst()
        }
        synchronized(idCounts) { idCounts[b] = (idCounts[b] ?: 0) + 1 }
        notifications += 1
        Log.i(TAG, "EVENT tag=$tag a=$a b=$b (#$notifications)")
        status = "подписок ${registeredTags.size}/${specs().size}, событий $notifications"
        requestRead(wall)
    }

    /** Перечитать интересные ячейки один раз (запросы схлопываются). */
    private fun requestRead(eventWall: Long) {
        if (!pendingRead.compareAndSet(false, true)) return
        worker.execute {
            readCells(eventWall)
            pendingRead.set(false)
        }
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        pollThread = Thread({
            while (polling) {
                readCells(0L)
                try {
                    Thread.sleep(POLL_MS)
                } catch (e: InterruptedException) {
                    return@Thread
                }
            }
        }, "dash-push-poll").apply {
            isDaemon = true
            start()
        }
    }

    private fun readCells(eventWall: Long) {
        val b = binder?.takeIf { it.isBinderAlive } ?: return
        for ((group, id) in INTERESTING) {
            val raw = readInt(b, group, id) ?: continue
            val now = System.currentTimeMillis()
            val latency = if (eventWall > 0L) now - eventWall else -1L
            synchronized(cells) {
                cells["$group,$id"] = CellVal(raw, now, latency)
            }
        }
        if (eventWall > 0L) lastEventLatencyMs = System.currentTimeMillis() - eventWall
    }

    private fun readInt(binder: IBinder, group: Int, id: Int): Int? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IFACE)
            data.writeInt(group)
            data.writeInt(id)
            binder.transact(TRANSACT_READ, data, reply, 0)
            reply.readException()
            CarScaling.bytesToInt(reply.createIntArray())
        } catch (e: Exception) {
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun stopInternal() {
        polling = false
        pollThread?.interrupt()
        pollThread = null
        val ctx = context
        val conn = connection
        if (ctx != null && conn != null) {
            runCatching { ctx.unbindService(conn) }
        }
        connection = null
        binder = null
        context = null
        callbacks.clear()
        registeredTags = emptyList()
    }

    private fun clearData() {
        synchronized(events) { events.clear() }
        synchronized(idCounts) { idCounts.clear() }
        synchronized(cells) { cells.clear() }
        notifications = 0L
        lastEventLatencyMs = -1L
    }

    private fun serviceAvailable(context: Context): Boolean = try {
        context.packageManager.getServiceInfo(ComponentName(SERVICE_PKG, SERVICE_CLASS), 0)
        true
    } catch (e: Exception) {
        false
    }

    /** Callback-приёмник сервиса: логируем КАЖДЫЙ пришедший int. */
    private class Callback(private val tag: String) : Binder() {
        init {
            attachInterface(null, CB_IFACE)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != TRANSACT_REGISTER) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(CB_IFACE)
            val a = data.readInt()
            val b = data.readInt()
            onEvent(tag, a, b)
            reply?.writeNoException()
            return true
        }
    }

    private const val TAG = "DesayPush"
    private const val SERVICE_PKG = "com.desaysv.svcarinfo"
    private const val SERVICE_CLASS = "com.desaysv.svcarinfo.CarInfoService"
    private const val IFACE = "com.desaysv.libsvcarinfo.ICarInfo"
    private const val CB_IFACE = "com.desaysv.libsvcarinfo.ICarInfoCallBack"
    private const val TRANSACT_REGISTER = 1
    private const val TRANSACT_READ = 3
    private const val EVENT_MAX = 200
    private const val POLL_MS = 1000L

    /** Ячейки шины, которые снимаем при событии/по таймеру. */
    private val INTERESTING = listOf(
        4 to 26,  // селектор/передача
        4 to 62,  // обороты
        4 to 68,  // топливо, десятые %
        16 to 19, // забортная
        0 to 27   // двигатель работает
    )
}
