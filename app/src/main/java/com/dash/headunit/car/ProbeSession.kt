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
import com.dash.headunit.navi.AdbShell
import com.dash.headunit.navi.NaviUnderlay
import java.io.File
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Сессия поиска величин, которых ещё нет в дашборде (ОЖ, мгновенный
 * расход, бортовой компьютер «после запуска», давление в шинах,
 * забортная): пишет в один каталог всё, что ГУ видит о машине, и метки —
 * что в тот момент показывают приборка/ГУ. Значение, которое меняется
 * вместе с меткой, — искомое. Только чтение.
 *
 * Источники (каждый пишет только изменения):
 * - шина Desay SV — все 32×256 ячеек каждые [SCAN_PERIOD_MS] (~2 с скан);
 * - VHAL — последние события всех свойств из `dumpsys car_service`
 *   (через adb-канал [AdbShell], если он разрешён);
 * - приборка (QNX PPS) — [ClusterRecorder], отдельным файлом рядом;
 * - эталоны: GPS-скорость, время с начала записи.
 *
 * Каталог: <externalFilesDir>/claude/<время>/ (bus.txt + cluster_rec_*.txt).
 */
object ProbeSession {

    @Volatile
    var status: String = "не запущена"
        private set

    @Volatile
    var dir: File? = null
        private set

    val running: Boolean get() = thread != null

    /** Секунд с начала записи (0 — не пишем). */
    val elapsedSec: Long
        get() = if (running) (System.currentTimeMillis() - startedAt) / 1000 else 0

    /** Сколько меток поставлено в текущей сессии. */
    @Volatile
    var marks = 0
        private set

    @Volatile
    private var thread: Thread? = null
    @Volatile
    private var stopping = false
    private var startedAt = 0L
    private var writer: Writer? = null
    private val lock = Any()

    private val desayPrev = mutableMapOf<Int, String>()
    private val vhalPrev = mutableMapOf<String, String>()
    private var desayChanges = 0L
    private var vhalChanges = 0L
    private var scans = 0L

    private var location: Location? = null
    private var locationListener: LocationListener? = null
    private var locationManager: LocationManager? = null

    /** Начать сессию на [minutes] минут (повторный вызов ничего не делает). */
    @Synchronized
    fun start(context: Context, minutes: Int = DEFAULT_MINUTES) {
        if (thread != null) return
        val app = context.applicationContext
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val d = File(app.getExternalFilesDir(null), "claude/$stamp").apply { mkdirs() }
        dir = d
        synchronized(lock) {
            writer = File(d, "bus.txt").bufferedWriter().apply {
                write("# сессия поиска, ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}\n")
                write("# формат: время | D группа,ид: было→стало (байты) | V свойство: было→стало | REF | ### МЕТКА\n")
                flush()
            }
            desayPrev.clear()
            vhalPrev.clear()
            desayChanges = 0
            vhalChanges = 0
            scans = 0
        }
        marks = 0
        stopping = false
        startedAt = System.currentTimeMillis()
        startGps(app)
        // Приборка — своим рекордером в тот же каталог, метки дублируем туда.
        ClusterRecorder.start(app, minutes, into = d)
        val until = startedAt + minutes * 60_000L
        thread = Thread({ run(app, until) }, "dash-probe").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        val t = thread ?: return
        stopping = true
        status = "останавливаю…"
        t.interrupt()
        ClusterRecorder.stop()
    }

    /** Метка «что показывает машина»: [key] — величина, [value] — значение. */
    fun mark(key: String, value: String) {
        val text = if (value.isBlank()) key else "$key=$value"
        write("### МЕТКА: $text")
        ClusterRecorder.mark(text)
        marks++
    }

    private fun run(context: Context, until: Long) {
        val desay = DesayCarReader(context)
        try {
            write("# desay: ${if (desay.isAvailable()) "есть" else "НЕТ"}; adb-канал разрешён: ${NaviUnderlay.isAuthorized(context)}")
            while (System.currentTimeMillis() < until && !stopping) {
                val started = System.currentTimeMillis()
                ref()
                scanDesay(desay)
                scanVhal(context)
                scans++
                status = "запись · ${progress()}"
                val left = SCAN_PERIOD_MS - (System.currentTimeMillis() - started)
                if (left > 0) Thread.sleep(left)
            }
        } catch (e: InterruptedException) {
            // Стоп.
        } catch (e: Exception) {
            Log.w(TAG, "probe", e)
            status = "ошибка: ${e.javaClass.simpleName}: ${e.message}"
            write("# $status")
        } finally {
            stopGps()
            synchronized(lock) {
                try {
                    writer?.close()
                } catch (e: Exception) {
                    // Нечего делать.
                }
                writer = null
            }
            if (!status.startsWith("ошибка")) status = "остановлена · ${progress()}"
            thread = null
        }
    }

    /** Все ячейки Desay; первый проход пишет всё, дальше — только изменения. */
    private fun scanDesay(desay: DesayCarReader) {
        if (!desay.isAvailable()) return
        val first = scans == 0L
        for (group in 0 until DESAY_GROUPS) {
            for (id in 0 until DESAY_IDS) {
                if (stopping) return
                val raw = desay.readRaw(group, id, logErrors = false) ?: continue
                val now = raw.joinToString(",") { (it and 0xFF).toString() }
                val key = group * DESAY_IDS + id
                val old = desayPrev.put(key, now)
                if (first) {
                    // Нули в описи не нужны — их и так видно по изменениям.
                    if (raw.any { it != 0 }) write("D $group,$id: $now")
                } else if (old != now) {
                    desayChanges++
                    write("D $group,$id: $old→$now")
                }
            }
        }
    }

    /**
     * Последние события всех свойств VHAL из car_service (в т.ч. сырые
     * SV_CAN_RAW_DATA / SV_GENERAL_RAW_DATA). Без adb-канала — пропуск.
     */
    private fun scanVhal(context: Context) {
        // Канал разрешали раньше — AdbShell подключится сам, без запроса на экране.
        if (NaviUnderlay.shellReady != true && !NaviUnderlay.isAuthorized(context)) return
        val out = AdbShell.exec(context, VHAL_CMD, authTimeoutMs = 3000) ?: return
        for (line in out.lines()) {
            val m = EVENT_RE.find(line) ?: continue
            val prop = m.groupValues[1]
            val value = "f=${m.groupValues[2]} i=${m.groupValues[3]} l=${m.groupValues[4]} b=${m.groupValues[5]} s=${m.groupValues[6].trim()}"
            val old = vhalPrev.put(prop, value)
            if (old == null) {
                write("V $prop: $value")
            } else if (old != value) {
                vhalChanges++
                write("V $prop: $old→$value")
            }
        }
    }

    private fun ref() {
        val sb = StringBuilder("REF t=").append(elapsedSec).append("с")
        location?.let {
            sb.append(" gps=")
            sb.append(if (it.hasSpeed()) "%.1f".format(Locale.US, it.speed * 3.6f) else "?")
            sb.append("км/ч age=").append((System.currentTimeMillis() - it.time) / 1000).append("с")
        }
        ClusterPps.coolantC()?.let { sb.append(" ОЖ(парсер)=").append(it) }
        ClusterPps.speedKmh()?.let { sb.append(" скорость(приборка)=").append(it) }
        ClusterPps.rpm()?.let { sb.append(" обороты(приборка)=").append(it) }
        write(sb.toString())
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
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, l)
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
                // Метки и редкие строки не должны теряться при выключении ГУ.
                w.flush()
            } catch (e: Exception) {
                Log.w(TAG, "write", e)
            }
        }
    }

    private fun progress(): String {
        val kb = (dir?.let { File(it, "bus.txt").length() } ?: 0) / 1024
        return "сканов $scans, Desay Δ$desayChanges, VHAL Δ$vhalChanges, меток $marks, $kb КБ"
    }

    private const val TAG = "ProbeSession"
    const val DEFAULT_MINUTES = 60
    private const val SCAN_PERIOD_MS = 3000L
    private const val DESAY_GROUPS = 32
    private const val DESAY_IDS = 256
    private const val VHAL_CMD =
        "dumpsys car_service | sed -n '/All Events/,/Property handlers/p'"
    /** «lastEvent:Property:0x…,timestamp:…,zone:…,floatValues: […],int32Values: […],int64Values: […],bytes: […],string: …». */
    private val EVENT_RE = Regex(
        "lastEvent:Property:(0x[0-9a-fA-F]+),timestamp:\\d+,zone:\\S+?," +
            "floatValues: \\[([^\\]]*)],int32Values: \\[([^\\]]*)],int64Values: \\[([^\\]]*)],bytes: \\[([^\\]]*)],string: (.*)"
    )
}
