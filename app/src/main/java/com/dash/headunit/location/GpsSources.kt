package com.dash.headunit.location

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.dash.headunit.navi.AdbShell
import java.util.UUID

/** Фикс от источника. [receivedAt] — SystemClock.elapsedRealtime() прихода. */
data class GpsFix(
    val lat: Double,
    val lon: Double,
    val speedMps: Float?,
    val bearing: Float?,
    val altitude: Double?,
    val satellites: Int?,
    val hdop: Float?,
    val receivedAt: Long,
)

/** Источник координат для [GpsInject]: поток со своим переподключением. */
abstract class GpsSource(val label: String) {
    @Volatile var status: String = "не запущен"
        protected set
    @Volatile protected var running = false
    private var thread: Thread? = null
    protected var onFix: (GpsFix) -> Unit = {}

    /** Всего принятых фиксов / строк NMEA — для состояния. */
    @Volatile var fixes = 0L
        protected set
    @Volatile var sentences = 0L
        protected set

    fun start(onFix: (GpsFix) -> Unit) {
        if (running) return
        this.onFix = onFix
        running = true
        thread = Thread({
            while (running) {
                try {
                    runOnce()
                } catch (e: Throwable) {
                    Log.w(TAG, "$label", e)
                    status = "ошибка: ${e.javaClass.simpleName}: ${e.message}"
                }
                if (running) SystemClock.sleep(RETRY_MS)
            }
        }, "GpsSource-$label").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        abort()
        thread?.interrupt()
        thread = null
    }

    /** Одна сессия: подключиться и читать, пока идёт. */
    protected abstract fun runOnce()

    /** Прервать блокирующее чтение. */
    protected abstract fun abort()

    /** Склейка строк NMEA из кусков потока; GGA дополняет следующий RMC. */
    protected inner class NmeaFeed {
        private val buf = StringBuilder()
        private var gga: Nmea.Gga? = null

        fun feed(chunk: String) {
            buf.append(chunk)
            while (true) {
                val nl = buf.indexOf('\n')
                if (nl < 0) break
                val line = buf.substring(0, nl)
                buf.delete(0, nl + 1)
                line(line)
            }
            if (buf.length > 4096) buf.setLength(0) // мусор без переводов строк
        }

        private fun line(s: String) {
            when (val m = Nmea.parse(s)) {
                is Nmea.Gga -> { sentences++; gga = m }
                is Nmea.Rmc -> {
                    sentences++
                    if (!m.valid) {
                        status = "$label: нет фикса (спутников ${gga?.satellites ?: "?"})"
                        return
                    }
                    fixes++
                    val g = gga
                    onFix(
                        GpsFix(
                            m.lat, m.lon, m.speedMps, m.bearing, g?.altitude, g?.satellites, g?.hdop,
                            SystemClock.elapsedRealtime()
                        )
                    )
                }
            }
        }
    }

    companion object {
        const val TAG = "GpsSource"
        const val RETRY_MS = 5_000L
    }
}

/**
 * Внешний GPS по Bluetooth SPP: BT-GPS-приёмник или телефон с приложением,
 * которое шлёт NMEA (например, «Bluetooth GPS Output»). Мы — клиент SPP.
 */
class BtNmeaSource(private val address: String) : GpsSource("Bluetooth") {
    @Volatile private var socket: BluetoothSocket? = null

    @SuppressLint("MissingPermission")
    override fun runOnce() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            status = "Bluetooth выключен"
            return
        }
        val dev = adapter.getRemoteDevice(address)
        status = "подключаюсь к ${dev.name ?: address}…"
        val sock = open(dev) ?: return
        socket = sock
        status = "подключено к ${dev.name ?: address}, жду NMEA"
        val feed = NmeaFeed()
        val input = sock.inputStream
        val b = ByteArray(1024)
        try {
            while (running) {
                val n = input.read(b)
                if (n < 0) break
                feed.feed(String(b, 0, n, Charsets.ISO_8859_1))
            }
            status = "${dev.name ?: address}: поток закрыт"
        } finally {
            runCatching { sock.close() }
            socket = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun open(dev: BluetoothDevice): BluetoothSocket? {
        val errors = ArrayList<String>()
        BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
        for (way in 0..2) {
            val s = try {
                when (way) {
                    0 -> dev.createRfcommSocketToServiceRecord(SPP)
                    1 -> dev.createInsecureRfcommSocketToServiceRecord(SPP)
                    else -> dev.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                        .invoke(dev, 1) as BluetoothSocket
                }
            } catch (e: Exception) {
                errors += e.javaClass.simpleName
                continue
            }
            try {
                s.connect()
                return s
            } catch (e: Exception) {
                runCatching { s.close() }
                errors += e.message ?: e.javaClass.simpleName
            }
        }
        status = "не подключиться к ${dev.name ?: address}: ${errors.joinToString("; ")}"
        return null
    }

    override fun abort() {
        runCatching { socket?.close() }
    }

    private companion object {
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}

/**
 * Встроенный GPS ГУ напрямую: NMEA модуля Teseo с /dev/ttySC1 через наш
 * adb-канал (uid shell). Нужен потому, что при подмене провайдера gps
 * штатный GnssLocationProvider останавливается и нам фиксы не отдаёт.
 * Доступ к порту и то, шлёт ли модуль данные при остановленном HAL, —
 * не проверено (GpsProbe).
 */
class TtyNmeaSource(private val context: Context) : GpsSource("встроенный") {
    @Volatile private var stopStream: (() -> Unit)? = null

    override fun runOnce() {
        status = "читаю $TTY через adb…"
        val feed = NmeaFeed()
        var head = StringBuilder()
        val end = AdbShell.stream(context, "cat $TTY 2>&1", onOpen = { stopStream = it }) { chunk ->
            if (head.length < 200) {
                head.append(chunk)
                if (head.contains("Permission denied") || head.contains("No such file")) {
                    status = "$TTY: ${head.toString().trim().take(120)}"
                    return@stream false
                }
            } else if (head.isNotEmpty()) {
                head = StringBuilder()
            }
            feed.feed(chunk)
            running
        }
        stopStream = null
        if (end == null) status = "нет adb-канала (${AdbShell.lastStatus})"
        else if (!status.startsWith(TTY)) status = "$TTY: $end"
    }

    override fun abort() {
        stopStream?.invoke()
    }

    companion object {
        /** Из /etc/gps.conf ГУ (Teseo, 115200). */
        const val TTY = "/dev/ttySC1"
    }
}
