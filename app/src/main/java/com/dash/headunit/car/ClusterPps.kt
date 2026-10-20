package com.dash.headunit.car

import android.os.SystemClock
import android.util.Log
import java.net.Socket
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Данные приборки (QNX PPS), как у Jarvis: ОЖ, скорость, обороты, стрелки,
 * бортовой компьютер (a001).
 *
 * Ни VHAL ГУ (ENGINE_COOLANT_TEMP нет, ENGINE_OIL_TEMP без данных,
 * SV_SEND_TEMP_INFO — температура самого ГУ), ни шина Desay ОЖ не отдают.
 * Её знает приборка: объекты /var/pps/spi_service/a000, a001, a0ff, a5a5
 * читаются через [QnxLauncher] одним долгим sh-процессом — потоком
 * изменений «.all?wait,delta» (a000 со скоростью/оборотами приходит
 * ~10 раз в секунду, запись ГУ 2026-10-14); если поток не пошёл —
 * опросом раз в секунду. Скорость и обороты сразу уходят в [onLive].
 * На T18FL3 градусов ОЖ в них пока не нашли — только стрелку.
 *
 * Разбор кадров — [ClusterParser]. Поток стартует из [CarHub].
 */
object ClusterPps {

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

    /** Последние статусы (unix-время мс + текст) — для «Логи для Claude». */
    fun statusHistory(): List<String> = synchronized(history) { history.toList() }

    private val parser = ClusterParser()

    /** Последние сырые строки каждого объекта — для диагностики. */
    private val lastRaw = mutableMapOf<String, String>()

    fun lastFrames(): Map<String, String> = synchronized(lastRaw) { lastRaw.toMap() }

    /**
     * История циклов чтения: время, байты всех объектов и итог разбора.
     * Пишется только при изменении байтов — по ней видно, какой байт на
     * этой машине реально ОЖ (раскладка T18FL3 может отличаться от T19).
     */
    private val frameHistory = ArrayDeque<String>()
    private var lastCycleBytes = ""
    private val cycleBytes = sortedMapOf<String, String>()

    fun frameHistory(): List<String> = synchronized(frameHistory) { frameHistory.toList() }

    private fun recordCycle() {
        val bytes = cycleBytes.entries.joinToString(" ") { "${it.key}=${it.value}" }
        cycleBytes.clear()
        if (bytes == lastCycleBytes) return
        lastCycleBytes = bytes
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        synchronized(frameHistory) {
            frameHistory.addLast("$time $bytes → ${describe()}")
            while (frameHistory.size > FRAME_HISTORY_MAX) frameHistory.removeFirst()
        }
    }

    /** Температура ОЖ, °C, или null (нет связи / данные устарели). */
    fun coolantC(): Float? = synchronized(parser) { parser.coolant(SystemClock.uptimeMillis()) }

    /** Скорость с приборки, км/ч, или null. */
    fun speedKmh(): Int? = synchronized(parser) { parser.speedKmh(SystemClock.uptimeMillis()) }

    /** Стрелка ОЖ 0..1 (0 = заглушен/нет данных) или null. */
    fun needle(): Float? = synchronized(parser) { parser.needle(SystemClock.uptimeMillis()) }

    /** Обороты с приборки или null. */
    fun rpm(): Int? = synchronized(parser) { parser.rpm(SystemClock.uptimeMillis()) }

    /** Бортовой компьютер приборки или null (нет связи / данные устарели). */
    fun trip(): ClusterTrip? = synchronized(parser) { parser.trip(SystemClock.uptimeMillis()) }

    /** Разобранное состояние одной строкой — для диагностики. */
    fun describe(): String = synchronized(parser) { parser.describe(SystemClock.uptimeMillis()) }

    /**
     * Свежие скорость/обороты (a000/a5a5) — вызывается из потока чтения
     * на каждом кадре. Подписчик ([CarHub]) сам переносит в главный поток.
     */
    @Volatile
    var onLive: (() -> Unit)? = null

    @Volatile
    private var thread: Thread? = null

    /** Поток .all на этой приборке не работает — сразу опрос. */
    @Volatile
    private var streamFailed = false

    /** Запустить чтение приборки (повторный вызов ничего не делает). */
    @Synchronized
    fun start() {
        if (thread != null) return
        thread = Thread({ loop() }, "dash-cluster-pps").apply {
            isDaemon = true
            start()
        }
    }

    private fun loop() {
        var backoff = RETRY_MIN_MS
        while (true) {
            val gotData = try {
                session()
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                Log.w(TAG, "session", e)
                lastStatus = "приборка: ${e.javaClass.simpleName}: ${e.message}"
                false
            }
            // Были данные — переподключаемся быстро; нет — всё реже.
            backoff = if (gotData) RETRY_MIN_MS else (backoff * 2).coerceAtMost(RETRY_MAX_MS)
            try {
                Thread.sleep(backoff)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    /** Одна сессия чтения. Возвращает true, если пришёл хоть один кадр. */
    private fun session(): Boolean {
        if (!QnxLauncher.reachable()) {
            lastStatus = "приборка не отвечает на qconn ${QnxLauncher.HOST}:${QnxLauncher.PORT}"
            return false
        }
        val sh = QnxLauncher.find("sh")
        val cat = QnxLauncher.find("cat")
        val sleep = QnxLauncher.find("sleep")
        if (sh == null || cat == null || sleep == null) {
            lastStatus = "на приборке нет утилит: sh=$sh cat=$cat sleep=$sleep"
            return false
        }
        if (!streamFailed) {
            // Поток изменений всего каталога: сначала все объекты целиком,
            // дальше — каждое изменение («@объект» + строки атрибутов).
            val r = read(sh, "$cat $DIR/.all?wait,delta; exit\n", "поток")
            if (r != null) return r
            streamFailed = true
            lastStatus = "поток .all не дал данных — опрос раз в секунду"
        }
        // ~1 ч (3600 × 1 с), затем процесс выходит и сессия переоткрывается.
        val script = "n=0; while [ \$n -lt 3600 ]; do for f in ${OBJECTS.joinToString(" ")}; do " +
            "if [ -f $DIR/\$f ]; then echo \"@\$f\"; $cat $DIR/\$f; fi; done; " +
            "$sleep 1 || exit; n=\$((n+1)); done; exit\n"
        return read(sh, script, "опрос") == true
    }

    /**
     * Читает вывод [script] до обрыва/тишины. true — были кадры, false — нет,
     * null — соединение живо, но данных нет дольше [NO_DATA_MS] (скрипт не работает).
     */
    private fun read(sh: String, script: String, label: String): Boolean? {
        var frames = 0
        Socket().use { socket ->
            val out = QnxLauncher.start(socket, sh, listOf("sh"))
            socket.soTimeout = READ_SLICE_MS
            Thread.sleep(STDIN_DELAY_MS)
            out.write(script.toByteArray(Charsets.US_ASCII))
            out.flush()
            lastStatus = "подключился к приборке ($label), жду данные"
            val input = socket.getInputStream()
            val partial = StringBuilder()
            var current: String? = null
            val started = SystemClock.uptimeMillis()
            var lastData = started
            var lastCycle = 0L
            while (true) {
                val now = SystemClock.uptimeMillis()
                val line = try {
                    QnxLauncher.readLine(input, partial)
                } catch (e: SocketTimeoutException) {
                    if (frames == 0 && now - started > NO_DATA_MS) return null
                    if (now - lastData > SILENT_MS) {
                        lastStatus = "приборка молчит ${SILENT_MS / 1000} с"
                        break
                    }
                    continue
                } ?: break
                val header = objectHeader(line)
                when {
                    header != null -> current = header
                    line.startsWith(DATA_PREFIX) && current != null -> {
                        val obj = current
                        val body = line.substring(DATA_PREFIX.length)
                        synchronized(lastRaw) { lastRaw[obj] = body }
                        cycleBytes[obj] = ClusterParser.parseData(body)?.joinToString(",") ?: body
                        synchronized(parser) { parser.onFrame(obj, body, now) }
                        frames++
                        lastData = now
                        if (obj == "a000" || obj == "a5a5") onLive?.invoke()
                        // История и статус — не чаще раза в CYCLE_MS (кадры идут ~10/с).
                        if (now - lastCycle >= CYCLE_MS) {
                            lastCycle = now
                            lastStatus = "данные приборки ($label): ${describe()}"
                            recordCycle()
                        }
                    }
                }
            }
        }
        return frames > 0
    }

    /**
     * Заголовок объекта в выводе PPS: «@a000», в потоке .all возможны
     * квалификаторы и флаги изменения: «[n]@a000», «+@a000». Иначе null.
     */
    internal fun objectHeader(line: String): String? =
        HEADER.matchEntire(line.trim())?.groupValues?.get(1)

    private val HEADER = Regex("""^(?:\[[^\]]*\])?[+\-#*]?@([A-Za-z0-9_.]+)$""")

    /**
     * Разовый снимок PPS приборки для «Логи для Claude»: что вообще есть
     * в /var/pps (раскладка может отличаться от Chery T19, под который
     * писал Jarvis) и полное содержимое объектов ОЖ. Блокирующий.
     */
    fun diagnosticsDump(): String {
        if (!QnxLauncher.reachable()) return "qconn ${QnxLauncher.HOST}:${QnxLauncher.PORT}: не отвечает"
        val sb = StringBuilder()
        sb.append("утилиты: ").append(
            listOf("sh", "cat", "ls", "sleep").joinToString { "$it=${QnxLauncher.find(it)}" }
        ).append('\n')
        val cat = QnxLauncher.find("cat") ?: "cat"
        val ls = QnxLauncher.find("ls") ?: "ls"
        val script = "echo @@ls; $ls -l /var/pps; $ls -l $DIR; " +
            OBJECTS.joinToString(" ") { "echo @@$it; $cat $DIR/$it;" } +
            " echo @@search; $ls /var/pps/* 2>/dev/null | head -300"
        sb.append(QnxLauncher.sh(script) ?: "sh не найден")
        return sb.toString()
    }

    private const val TAG = "ClusterPps"
    private const val DIR = "/var/pps/spi_service"
    private val OBJECTS = listOf("a000", "a001", "a0ff", "a5a5")
    private const val DATA_PREFIX = "data:json:"
    private const val HISTORY_MAX = 50
    private const val FRAME_HISTORY_MAX = 200
    private const val STDIN_DELAY_MS = 1300L
    /** Тишина дольше — переподключаемся (a000 при живой приборке идёт постоянно). */
    private const val SILENT_MS = 90_000L
    private const val READ_SLICE_MS = 1000
    /** Сколько ждать первых данных, прежде чем признать скрипт нерабочим. */
    private const val NO_DATA_MS = 20_000L
    /** Период истории кадров и статуса. */
    private const val CYCLE_MS = 10_000L
    private const val RETRY_MIN_MS = 15_000L
    private const val RETRY_MAX_MS = 10 * 60_000L
}

/**
 * Разбор кадров приборки. Раскладка T18FL3 (лог ГУ 2026-10-13, в движении)
 * отличается от Chery T19, под который писал Jarvis:
 * - a000 — стрелки, u16 LE парами (два одинаковых слова подряд):
 *   b0–3 обороты, b4–7 скорость км/ч (как показывает приборка),
 *   b8–11 стрелка ОЖ в тысячных, b12–15 стрелка топлива в тысячных (гипотеза).
 *   «Цифры» ОЖ в b10|b11, как на T19, здесь нет — там вторая копия стрелки;
 *   ВНИМАНИЕ: стрелка ОЖ — «глупая» (дамми): 0 до ~40°, 370 в прогреве,
 *   дальше приклеена на 500/1000 во всём диапазоне 84–100° (сверено
 *   с OBD-метками). Для точной температуры не годится — только «греется».
 * - a5a5 — сырые данные, маркер 0x55AA в b48|b49: b4|b5 скорость км/ч
 *   (на ~1 меньше, чем на приборке), b6|b7 обороты. b2 гуляет 101..165 при
 *   неподвижной стрелке ОЖ — это не ОЖ (у Jarvis для T19 было ОЖ);
 * - a001 — бортовой компьютер, раскладка как у Jarvis (сверено с метками
 *   приборки 2026-10-14, все совпали), LE: b0–3 одометр км; b4–7 пробег
 *   после запуска ×0.1 км; b8–11 пробег после сброса ×0.1 км; b20|b21
 *   средний расход после запуска ×0.1 л/100; b22|b23 средняя скорость после
 *   запуска ×0.1 км/ч; b44|b45 запас хода км. Первые ~100 м после запуска
 *   расход и скорость = 0xFFFF (нет данных); запас сразу после включения
 *   зажигания — мусор (0xF000), отсекаем по диапазону.
 * - a0ff (≥16 байт, на T18FL3 не найден): b14|b15, °C = raw × 0.75 − 48
 *   (−45..150; 0/255/65535 — нет данных) — оставлен как у Jarvis.
 * Градусов ОЖ в a000/a5a5 нет; ищем в остальных объектах через [ClusterRecorder].
 */
class ClusterParser {

    private var trip: ClusterTrip? = null
    private var tripAt = 0L

    private var coolant: Float? = null
    private var coolantAt = 0L

    private var needle: Float? = null
    private var needleAt = 0L

    private var fuelNeedle: Float? = null
    private var fuelNeedleAt = 0L

    private var speed: Int? = null
    private var speedAt = 0L
    private var speedSource = ""

    private var rpm: Int? = null
    private var rpmAt = 0L
    private var rpmSource = ""

    private var a5a5b2 = -1

    /** [body] — текст после «data:json:», внутри должен быть массив data:[…]. */
    fun onFrame(obj: String, body: String, now: Long) {
        val bytes = parseData(body) ?: return
        when (obj) {
            "a000" -> if (bytes.size >= 16) {
                u16(bytes, 8).takeIf { it <= 1000 }?.let {
                    needle = it / 1000f
                    needleAt = now
                }
                u16(bytes, 12).takeIf { it <= 1000 }?.let {
                    fuelNeedle = it / 1000f
                    fuelNeedleAt = now
                }
                // a000 — ровно то, что показывает приборка, и приходит ~10 раз
                // в секунду (a5a5 ~2 раза, на ~1 км/ч меньше) — он главный.
                u16(bytes, 4).takeIf { it <= MAX_SPEED }?.let { setSpeed(it, "a000", now) }
                u16(bytes, 0).takeIf { it <= MAX_RPM }?.let { setRpm(it, "a000", now) }
            }
            "a001" -> if (bytes.size >= 46) {
                trip = ClusterTrip(
                    odometerKm = u32(bytes, 0).takeIf { it in 1..MAX_ODO_KM },
                    tripKm = u32(bytes, 4).takeIf { it <= MAX_ODO_KM * 10L }?.let { it / 10f },
                    tripSinceResetKm = u32(bytes, 8).takeIf { it <= MAX_ODO_KM * 10L }?.let { it / 10f },
                    avgFuelL100 = u16(bytes, 20).takeIf { it <= MAX_AVG_FUEL }?.let { it / 10f },
                    avgSpeedKmh = u16(bytes, 22).takeIf { it <= MAX_SPEED * 10 }?.let { it / 10f },
                    rangeKm = u16(bytes, 44).takeIf { it <= MAX_RANGE_KM },
                )
                tripAt = now
            }
            "a0ff" -> if (bytes.size >= 16) {
                val raw = u16(bytes, 14)
                if (raw != 0 && raw != 255 && raw != 65535) {
                    val c = raw * 0.75f - 48f
                    if (c in -45f..150f) {
                        coolant = c
                        coolantAt = now
                    }
                }
            }
            "a5a5" -> if (bytes.size >= 50 && u16(bytes, 48) == A5A5_MARKER) {
                a5a5b2 = bytes[2]
                // Запасной источник — только если a000 молчит.
                if (speedSource != "a000" || now - speedAt >= FALLBACK_MS) {
                    u16(bytes, 4).takeIf { it <= MAX_SPEED }?.let { setSpeed(it, "a5a5", now) }
                }
                if (rpmSource != "a000" || now - rpmAt >= FALLBACK_MS) {
                    u16(bytes, 6).takeIf { it <= MAX_RPM }?.let { setRpm(it, "a5a5", now) }
                }
            }
        }
    }

    private fun setSpeed(v: Int, src: String, now: Long) {
        speed = v
        speedAt = now
        speedSource = src
    }

    private fun setRpm(v: Int, src: String, now: Long) {
        rpm = v
        rpmAt = now
        rpmSource = src
    }

    /** Температура ОЖ, °C — только прямое значение (a0ff). */
    fun coolant(now: Long): Float? = coolant?.takeIf { now - coolantAt < STALE_MS }

    /** Стрелка ОЖ 0..1; 0 (двигатель заглушен) считаем отсутствием данных. */
    fun needle(now: Long): Float? = needle?.takeIf { now - needleAt < STALE_MS && it > 0f }

    /** Стрелка топлива 0..1 (гипотеза, ждёт подтверждения записью). */
    fun fuelNeedle(now: Long): Float? = fuelNeedle?.takeIf { now - fuelNeedleAt < STALE_MS }

    /** Бортовой компьютер (a001). */
    fun trip(now: Long): ClusterTrip? = trip?.takeIf { now - tripAt < STALE_MS }

    /** Скорость, км/ч. */
    fun speedKmh(now: Long): Int? = speed?.takeIf { now - speedAt < LIVE_MS }

    /** Обороты двигателя. */
    fun rpm(now: Long): Int? = rpm?.takeIf { now - rpmAt < LIVE_MS }

    fun describe(now: Long): String {
        fun permille(v: Float?) = v?.let { "%.0f/1000".format(it * 1000) } ?: "нет"
        return "ОЖ ${coolant(now)?.let { "%.0f° (a0ff)".format(it) } ?: "нет"}, " +
            "стрелка ОЖ ${permille(needle(now))}, " +
            "топливо ${permille(fuelNeedle(now))}, " +
            "скорость ${speedKmh(now)?.let { "$it км/ч ($speedSource)" } ?: "нет"}, " +
            "обороты ${rpm(now)?.let { "$it ($rpmSource)" } ?: "нет"}, " +
            "a5a5[2] ${if (a5a5b2 >= 0) a5a5b2 else "нет"}, " +
            "БК ${trip(now)?.describe() ?: "нет"}"
    }

    companion object {
        private const val STALE_MS = 15 * 60_000L
        /** Скорость/обороты меняются быстро: при опросе раз в 10 с — три пропуска. */
        private const val LIVE_MS = 30_000L
        /** a000 молчит дольше — скорость/обороты берём из a5a5. */
        private const val FALLBACK_MS = 3_000L
        private const val MAX_SPEED = 300
        private const val MAX_RPM = 10_000
        private const val A5A5_MARKER = 0x55AA
        private const val MAX_ODO_KM = 2_000_000L
        /** 0.1 л/100: всё выше 99.9 — «нет данных» (0xFFFF). */
        private const val MAX_AVG_FUEL = 999
        private const val MAX_RANGE_KM = 2000

        private fun u16(b: IntArray, i: Int) = b[i] or (b[i + 1] shl 8)

        private fun u32(b: IntArray, i: Int) = u16(b, i).toLong() or (u16(b, i + 2).toLong() shl 16)

        /** Массив после ключа «data»: "...data...[1, 2, 3]..." → байты 0..255. */
        fun parseData(body: String): IntArray? {
            val key = body.indexOf("data")
            if (key < 0) return null
            val open = body.indexOf('[', key)
            val close = body.indexOf(']', open)
            if (open < 0 || close < 0 || close < open) return null
            return try {
                body.substring(open + 1, close).split(',')
                    .filter { it.isNotBlank() }
                    .map { it.trim().toInt() and 0xFF }
                    .toIntArray()
            } catch (e: NumberFormatException) {
                null
            }
        }
    }
}

/** Бортовой компьютер приборки (a001); null в поле — нет данных. */
data class ClusterTrip(
    val odometerKm: Long?,
    /** Пробег после запуска двигателя. */
    val tripKm: Float?,
    /** Пробег после ручного сброса. */
    val tripSinceResetKm: Float?,
    /** Средний расход после запуска, л/100 км. */
    val avgFuelL100: Float?,
    /** Средняя скорость после запуска, км/ч. */
    val avgSpeedKmh: Float?,
    val rangeKm: Int?,
) {
    fun describe(): String {
        fun f(v: Float?) = v?.let { "%.1f".format(Locale.US, it) } ?: "нет"
        return "одометр ${odometerKm ?: "нет"} км, поездка ${f(tripKm)} км, " +
            "после сброса ${f(tripSinceResetKm)} км, расход ${f(avgFuelL100)} л/100, " +
            "ср. скорость ${f(avgSpeedKmh)} км/ч, запас ${rangeKm ?: "нет"} км"
    }
}
