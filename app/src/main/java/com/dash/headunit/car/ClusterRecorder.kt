package com.dash.headunit.car

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.Writer
import java.net.Socket
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Запись всего, что публикует приборка (QNX PPS), для поиска скорости,
 * передачи, расхода, ОЖ и прочего на T18FL3.
 *
 * PPS — не поток сообщений, а объекты-файлы в /var/pps: сервисы приборки
 * перезаписывают в них атрибуты. Объект «<каталог>/.all?wait,delta»
 * отдаёт изменения всех объектов каталога по мере их появления — его и
 * читаем `cat` через [QnxLauncher] для каждого каталога /var/pps. Если
 * поток не пошёл (PPS без .all), опрашиваем объекты раз в секунду.
 *
 * Рядом раз в секунду пишутся эталоны, известные ГУ: скорость GPS и VHAL,
 * обороты, передача, топливо, напряжение. Байт PPS, который меняется
 * вместе с эталоном, — искомый. Метки ([mark]) — то, что видно на
 * приборке и чего ГУ не знает (ОЖ, мгновенный расход).
 *
 * Файл: <externalFilesDir>/cluster/cluster_rec_<время>.txt. Только чтение.
 */
object ClusterRecorder {

    @Volatile
    var status: String = "не запущена"
        private set

    @Volatile
    var file: File? = null
        private set

    val running: Boolean get() = thread != null

    @Volatile
    private var thread: Thread? = null
    @Volatile
    private var socket: Socket? = null
    /**
     * Нажат «стоп». Отдельный флаг, а не interrupt(): сокет на interrupt()
     * не реагирует, а закрытый нами сокет иначе выглядит как обрыв связи.
     */
    @Volatile
    private var stopping = false
    private var writer: Writer? = null
    private val lock = Any()

    private val prevBytes = mutableMapOf<String, IntArray>()
    private val prevRaw = mutableMapOf<String, String>()
    private var lines = 0L
    private var changes = 0L

    // Состояние записи; меняет только её поток.
    private var inventoried = false
    /** Поток .all не дал данных — читаем опросом. */
    private var polling = false
    private var reconnects = 0

    private var location: Location? = null
    private var locationListener: LocationListener? = null
    private var locationManager: LocationManager? = null

    /**
     * Начать запись на [minutes] минут (повторный вызов ничего не делает).
     * [into] — каталог файла (по умолчанию <externalFilesDir>/cluster).
     */
    @Synchronized
    fun start(context: Context, minutes: Int = DEFAULT_MINUTES, into: File? = null) {
        if (thread != null) return
        val app = context.applicationContext
        val dir = (into ?: File(app.getExternalFilesDir(null), "cluster")).apply { mkdirs() }
        val f = File(dir, "cluster_rec_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.txt")
        file = f
        synchronized(lock) {
            writer = f.bufferedWriter()
            prevBytes.clear()
            prevRaw.clear()
            lines = 0
            changes = 0
        }
        stopping = false
        startGps(app)
        val until = System.currentTimeMillis() + minutes * 60_000L
        thread = Thread({ run(app, until) }, "dash-cluster-rec").apply {
            isDaemon = true
            start()
        }
    }

    /** Остановить запись. */
    fun stop() {
        val t = thread ?: return
        stopping = true
        status = "останавливаю… · ${progress()}"
        t.interrupt()
        try {
            socket?.close()
        } catch (e: Exception) {
            // Уже закрыт.
        }
    }

    /** Метка с тем, что видно на приборке («ОЖ 65», «расход 8.4»). */
    fun mark(text: String) {
        write("### МЕТКА: $text")
        status = "метка «$text» · ${progress()}"
    }

    private fun run(context: Context, until: Long) {
        val refs = Thread({ refsLoop(context, until) }, "dash-cluster-ref").apply {
            isDaemon = true
            start()
        }
        inventoried = false
        polling = false
        reconnects = 0
        try {
            write("# запись приборки, ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
            write("# формат: время | [объект] сырая строка PPS | Δ объект.атрибут bN: было→стало | REF эталоны ГУ | ### МЕТКА")
            // qconn иногда рвёт соединение — переподключаемся до конца отведённого времени.
            var backoff = RETRY_MIN_MS
            while (System.currentTimeMillis() < until && !stopping) {
                val gotData = try {
                    session(until)
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    // stop() закрывает сокет — это не ошибка связи.
                    if (stopping) break
                    Log.w(TAG, "session", e)
                    write("# связь с приборкой: ${e.javaClass.simpleName}: ${e.message}")
                    false
                }
                if (System.currentTimeMillis() >= until || stopping) break
                // Были данные — переподключаемся быстро; нет — всё реже.
                backoff = if (gotData) RETRY_MIN_MS else (backoff * 2).coerceAtMost(RETRY_MAX_MS)
                reconnects++
                status = "нет связи, переподключение №$reconnects через ${backoff / 1000} с · ${progress()}"
                write("# $status")
                Thread.sleep(backoff)
            }
        } catch (e: InterruptedException) {
            // Стоп.
        } catch (e: Exception) {
            if (!stopping) {
                Log.w(TAG, "record", e)
                status = "ошибка: ${e.javaClass.simpleName}: ${e.message}"
                write("# $status")
            }
        } finally {
            refs.interrupt()
            stopGps()
            synchronized(lock) {
                try {
                    writer?.close()
                } catch (e: Exception) {
                    // Нечего делать.
                }
                writer = null
            }
            if (!status.startsWith("ошибка")) {
                status = "остановлена · ${progress()}" + if (reconnects > 0) ", переподключений: $reconnects" else ""
            }
            thread = null
        }
    }

    /**
     * Одно подключение: опись (один раз за запись), затем поток .all или,
     * если он не дал данных, опрос. true — если пришли данные PPS.
     */
    private fun session(until: Long): Boolean {
        status = "подключаюсь к приборке…"
        if (!QnxLauncher.reachable()) {
            write("# приборка не отвечает (qconn ${QnxLauncher.HOST}:${QnxLauncher.PORT})")
            return false
        }
        if (!inventoried) {
            inventory()
            inventoried = true
        }
        if (!polling) {
            val streamed = stream(until, STREAM_SCRIPT, "поток .all")
            if (streamed != null) return streamed
            write("# поток .all не дал данных — перехожу на опрос раз в секунду")
            polling = true
        }
        return stream(until, POLL_SCRIPT, "опрос") == true
    }

    /** Разовый снимок: какие каталоги и объекты есть, их полное содержимое. */
    private fun inventory() {
        status = "опись /var/pps…"
        write("# ===== ОПИСЬ /var/pps =====")
        val out = QnxLauncher.sh("$PATH_SET; ls -lR /var/pps; " +
            "for f in /var/pps/* /var/pps/*/* /var/pps/*/*/*; do " +
            "[ -f \"\$f\" ] && { echo \"==== \$f\"; cat \"\$f\"; }; done", INVENTORY_TIMEOUT_MS)
        (out ?: "sh на приборке не найден").lines().forEach { write("# $it") }
        write("# ===== КОНЕЦ ОПИСИ =====")
    }

    /**
     * Долгое чтение вывода sh-скрипта до [until], стопа или обрыва связи.
     * true — пришли данные PPS, false — нет, null — данных нет дольше
     * [NO_DATA_MS] при живом соединении (скрипт не работает).
     */
    private fun stream(until: Long, script: String, label: String): Boolean? {
        val sh = QnxLauncher.find("sh") ?: throw IllegalStateException("на приборке нет sh")
        var data = false
        try {
            Socket().use { s ->
                socket = s
                if (stopping) return data
                val out = QnxLauncher.start(s, sh, listOf("sh"))
                s.soTimeout = READ_SLICE_MS
                Thread.sleep(STDIN_DELAY_MS)
                out.write("$PATH_SET; $script\n".toByteArray(Charsets.US_ASCII))
                out.flush()
                write("# ===== $label =====")
                status = "запись ($label)…"
                val input = s.getInputStream()
                val partial = StringBuilder()
                var obj = "?"
                val started = System.currentTimeMillis()
                while (System.currentTimeMillis() < until && !stopping) {
                    val line = try {
                        QnxLauncher.readLine(input, partial)
                    } catch (e: SocketTimeoutException) {
                        if (!data && System.currentTimeMillis() - started > NO_DATA_MS) return null
                        continue
                    }
                    if (line == null) {
                        if (!stopping) write("# приборка закрыла соединение")
                        break
                    }
                    if (line.isEmpty()) continue
                    if (line.startsWith("@")) {
                        obj = line.substring(1).trim()
                        data = true
                        continue
                    }
                    onLine(obj, line)
                    status = "запись ($label) · ${progress()}"
                }
            }
        } finally {
            socket = null
        }
        return data
    }

    private fun onLine(obj: String, line: String) {
        val key = "$obj.${line.substringBefore(':')}"
        // При опросе неизменные строки приходят каждую секунду — пишем только новые.
        if (synchronized(lock) { prevRaw.put(key, line) } == line) return
        write("[$obj] $line")
        val bytes = ClusterParser.parseData(line) ?: return
        val diff = synchronized(lock) {
            val old = prevBytes.put(key, bytes) ?: return
            diffBytes(old, bytes)
        }
        if (diff.isNotEmpty()) {
            changes++
            write("Δ $key $diff")
        }
    }

    /** Эталоны ГУ раз в секунду. */
    private fun refsLoop(context: Context, until: Long) {
        val vhal = VhalReader(context)
        val desay = DesayCarReader(context)
        try {
            while (System.currentTimeMillis() < until) {
                val sb = StringBuilder("REF")
                location?.let {
                    sb.append(" gps=")
                    sb.append(if (it.hasSpeed()) "%.1f".format(Locale.US, it.speed * 3.6f) else "?")
                    sb.append("км/ч age=").append((System.currentTimeMillis() - it.time) / 1000).append("с")
                }
                for ((name, prop) in REF_FLOAT) {
                    vhal.getFloatProperty(prop)?.let { sb.append(' ').append(name).append('=').append(it) }
                }
                for ((name, prop) in REF_INT) {
                    vhal.getIntProperty(prop)?.let { sb.append(' ').append(name).append('=').append(it) }
                }
                for ((name, gi) in REF_DESAY) {
                    desay.readRaw(gi.first, gi.second, logErrors = false)
                        ?.let { sb.append(' ').append(name).append('=').append(it.joinToString(",")) }
                }
                ClusterPps.coolantC()?.let { sb.append(" ОЖ(парсер)=").append(it) }
                ClusterPps.speedKmh()?.let { sb.append(" скорость(парсер)=").append(it) }
                ClusterPps.rpm()?.let { sb.append(" обороты(парсер)=").append(it) }
                write(sb.toString())
                Thread.sleep(REF_PERIOD_MS)
            }
        } catch (e: InterruptedException) {
            // Стоп.
        }
    }

    @SuppressLint("MissingPermission")
    private fun startGps(context: Context) {
        Handler(Looper.getMainLooper()).post {
            try {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val l = object : LocationListener {
                    override fun onLocationChanged(loc: Location) {
                        location = loc
                    }
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                    override fun onProviderEnabled(provider: String) = Unit
                    override fun onProviderDisabled(provider: String) = Unit
                }
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, l)
                locationManager = lm
                locationListener = l
            } catch (e: Exception) {
                write("# GPS недоступен: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun stopGps() {
        val l = locationListener ?: return
        locationListener = null
        Handler(Looper.getMainLooper()).post {
            try {
                locationManager?.removeUpdates(l)
            } catch (e: Exception) {
                // Нечего делать.
            }
        }
    }

    private fun write(s: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        synchronized(lock) {
            val w = writer ?: return
            try {
                w.write(time)
                w.write(" ")
                w.write(s)
                w.write("\n")
                lines++
                if (lines % 50 == 0L) w.flush()
                if ((file?.length() ?: 0) > MAX_BYTES) {
                    w.write("# достигнут предел размера файла\n")
                    stop()
                }
            } catch (e: Exception) {
                Log.w(TAG, "write", e)
            }
        }
    }

    private fun progress(): String {
        val kb = (file?.length() ?: 0) / 1024
        return "$lines строк, $changes изменений, $kb КБ"
    }

    /** «b3: 10→11, b7: 0→2». */
    internal fun diffBytes(old: IntArray, new: IntArray): String {
        val parts = mutableListOf<String>()
        for (i in 0 until maxOf(old.size, new.size)) {
            val a = old.getOrNull(i)
            val b = new.getOrNull(i)
            if (a != b) parts.add("b$i: ${a ?: "-"}→${b ?: "-"}")
        }
        return parts.joinToString(", ")
    }

    private const val TAG = "ClusterRecorder"
    const val DEFAULT_MINUTES = 30
    private const val PATH_SET =
        "PATH=/bin:/proc/boot:/usr/bin:/sbin:/usr/sbin:/usr/local/bin:/mnt/bin:/np/bin; export PATH"
    /** Поток изменений всех каталогов PPS до глубины 3. */
    private const val STREAM_SCRIPT =
        "for d in /var/pps /var/pps/* /var/pps/*/* /var/pps/*/*/*; do " +
            "[ -d \"\$d\" ] && cat \"\$d/.all?wait,delta\" & done; wait; exit"
    /** Запасной вариант: все объекты раз в секунду. */
    private const val POLL_SCRIPT =
        "while :; do for f in /var/pps/* /var/pps/*/* /var/pps/*/*/*; do " +
            "[ -f \"\$f\" ] && cat \"\$f\"; done; sleep 1 || exit; done"
    private const val INVENTORY_TIMEOUT_MS = 40_000
    private const val READ_SLICE_MS = 1000
    private const val NO_DATA_MS = 20_000L
    private const val RETRY_MIN_MS = 2_000L
    private const val RETRY_MAX_MS = 30_000L
    private const val STDIN_DELAY_MS = 1300L
    private const val REF_PERIOD_MS = 1000L
    private const val MAX_BYTES = 200L * 1024 * 1024

    private val REF_FLOAT = listOf(
        "speed" to 291504647,     // PERF_VEHICLE_SPEED 0x11600207, м/с
        "rpm" to 291504901,       // ENGINE_RPM 0x11600305
        "fuelLvl" to 291504903,   // FUEL_LEVEL 0x11600307
        "odo" to 291504644        // PERF_ODOMETER 0x11600204
    )
    private val REF_INT = listOf(
        "gearSel" to 289408000,   // GEAR_SELECTION 0x11400400
        "gear" to 289408001,      // CURRENT_GEAR 0x11400401
        "ign" to 289408009,       // IGNITION_STATE 0x11400409
        "rpmJ" to 557850661,      // Desay/Jarvis vendor RPM
        "fuelJ" to 557850663,
        "rangeJ" to 557850664
    )
    /** Шина Desay: обороты (4,62), напряжение (4,76), одометр (4,71), заряд (0,27). */
    private val REF_DESAY = listOf(
        "d_rpm" to (4 to 62),
        "d_volt" to (4 to 76),
        "d_odo" to (4 to 71),
        "d_chg" to (0 to 27)
    )
}
