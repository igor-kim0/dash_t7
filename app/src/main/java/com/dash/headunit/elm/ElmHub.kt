package com.dash.headunit.elm

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.UUID

/**
 * Связь с адаптером ELM327 по Bluetooth (классический SPP/RFCOMM)
 * и опрос OBD-II PID 0105 — источник честной температуры ОЖ,
 * которой нет ни на Desay-шине, ни у приборки, ни в VHAL ГУ.
 *
 * Работает как [com.dash.headunit.car.ClusterPps]: один фоновый поток
 * на весь процесс, статусы для debug-экрана. Адрес адаптера выбирается
 * в CarDebugActivity (список спаренных устройств) и хранится в prefs.
 *
 * Если данных с ELM нет — [coolantC] возвращает null, и виджет
 * показывает «—»: на остальные виджеты ELM не влияет.
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

    private val lock = Any()

    @Volatile
    private var savedAddress: String? = null
    @Volatile
    private var savedName: String? = null
    private var appCtx: Context? = null

    fun address(): String? = savedAddress
    fun name(): String? = savedName

    fun setDevice(context: Context, address: String, name: String?) {
        synchronized(lock) {
            savedAddress = address
            savedName = name
            save()
        }
        lastStatus = "адаптер выбран: $address (${name ?: "?"})"
    }

    fun clearDevice(context: Context) {
        synchronized(lock) {
            savedAddress = null
            savedName = null
            save()
        }
        lastStatus = "адаптер сброшен"
    }

    // ---------- данные ----------

    private val dataLock = Any()
    private var coolant: Float? = null
    private var coolantAt = 0L

    /**
     * Температура ОЖ по ELM (PID 0105), °C, или null — нет связи /
     * давно не было ответа.
     */
    fun coolantC(now: Long = SystemClock.uptimeMillis()): Float? = synchronized(dataLock) {
        coolant?.takeIf { now - coolantAt < STALE_MS }
    }

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
     * Одна сессия: connect SPP → инициализация (ATZ, ATE0, ATSP0) →
     * опрос «0105» раз в [POLL_MS]. true — получен хоть один ответ ОЖ.
     */
    private fun session(address: String): Boolean {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            lastStatus = "Bluetooth выключен"
            return false
        }
        val remote = adapter.getRemoteDevice(address) // IllegalArgumentException → в [loop]
        lastStatus = "подключаюсь к ELM ${remote.name ?: "?"}…"
        val socket = remote.createRfcommSocketToServiceRecord(SPP_UUID)
        var answers = 0
        socket.use { sock ->
            try {
                sock.connect()
            } catch (e: IOException) {
                lastStatus = "ELM-сокет: ${e.message}"
                return false
            }
            lastStatus = "подключился к ELM, инициализация…"
            val input = sock.getInputStream()
            val output = sock.getOutputStream()
            // ATZ сбрасывает адаптер (может отвечать до секунды), затем
            // выключаем эхо и ставим авто-протокол.
            command(input, output, "ATZ")
            sleep(INIT_DELAY_MS)
            if (command(input, output, "ATE0") == null) return false
            if (command(input, output, "ATSP0") == null) return false
            lastStatus = "ELM настроен, читаю ОЖ…"
            while (true) {
                val reply = command(input, output, "0105") ?: break // сокет умер/нет ответа
                val coolant = ObdParser.coolant(reply)
                if (coolant == null) {
                    lastStatus = "0105: ответа нет («${reply.trim().takeLast(60)}»)"
                } else {
                    synchronized(dataLock) {
                        this.coolant = coolant
                        coolantAt = SystemClock.uptimeMillis()
                    }
                    lastStatus = "ELM: ОЖ %.1f°C".format(coolant)
                    answers++
                }
                sleep(POLL_MS)
            }
        }
        return answers > 0
    }

    /**
     * Отправить команду, прочитать ответ до приглашения «>».
     * Полный текст ответа или null (сбой сокета).
     */
    private fun command(input: InputStream, output: OutputStream, cmd: String): String? {
        return try {
            output.write((cmd + '\r').toByteArray(Charsets.US_ASCII))
            output.flush()
            val sb = StringBuilder()
            val buffer = ByteArray(512)
            val deadline = System.currentTimeMillis() + RESPONSE_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val n = try {
                    input.read(buffer)
                } catch (e: SocketTimeoutException) {
                    -1
                }
                if (n <= 0) continue
                sb.append(String(buffer, 0, n, Charsets.US_ASCII))
                if (sb.isNotEmpty() && sb.last() == ObdParser.PROMPT) break
            }
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
        synchronized(lock) {
            if (savedAddress == null) {
                savedAddress = addr
                savedName = appCtx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    ?.getString(KEY_NAME, null)
            }
        }
    }

    private fun save() {
        val ctx = appCtx ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ADDRESS, savedAddress)
            .putString(KEY_NAME, savedName)
            .apply()
    }

    private const val TAG = "ElmHub"
    private const val PREFS = "elm_settings"
    private const val KEY_ADDRESS = "address"
    private const val KEY_NAME = "name"
    private const val HISTORY_MAX = 50

    /** Столько нет ответов — данные считаются устаревшими. */
    private const val STALE_MS = 15 * 60_000L
    /** Период опроса ОЖ — BT-радио и аккумулятор не прожигаем. */
    private const val POLL_MS = 2_000L
    /** Ожидание ответа на один запрос. */
    private const val RESPONSE_TIMEOUT_MS = 5_000
    /** Таймаут одного chunked read — SocketTimeoutException. */
    private const val INIT_DELAY_MS = 1_000L
    private const val RETRY_MIN_MS = 15_000L
    private const val RETRY_MAX_MS = 10 * 60_000L
    private const val IDLE_NO_DEVICE_MS = 30_000L
}
