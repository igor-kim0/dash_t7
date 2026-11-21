package com.dash.headunit.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.dash.headunit.car.CarHub
import com.dash.headunit.car.ClusterPps
import com.dash.headunit.navi.AdbShell
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Проба GPS (экран диагностики): можно ли поправлять встроенный GPS и на
 * сколько он отстаёт. Отчёт — files/gps_probe/gps_<время>.txt.
 *
 *  1. adb: права на /dev/ttySC1 (порт модуля Teseo), какие провайдеры
 *     запрашивает Яндекс Навигатор (dumpsys location).
 *  2. NMEA с порта при работающем штатном GPS (3 с).
 *  3. Подмена gps на 6 с: идёт ли NMEA с порта, когда штатный GPS
 *     остановлен (от этого зависит поправка встроенного GPS).
 *  4. [RECORD_MS] — фиксы штатного gps и скорость машины: частота фиксов
 *     и оценка задержки ([GpsLead.estimateLag]). Нужно ехать с разгонами
 *     и торможениями.
 */
object GpsProbe {

    @Volatile var running = false
        private set
    @Volatile var status = ""
        private set

    fun start(context: Context) {
        if (running) return
        running = true
        val app = context.applicationContext
        Thread({ run(app) }, "GpsProbe").start()
    }

    private fun run(app: Context) {
        val sb = StringBuilder()
        sb.append("# gps probe ").append(STAMP.format(Date())).append('\n')
        GpsInject.pause()
        try {
            step("права и провайдеры (adb)…")
            sb.append("\n== adb\n")
            sb.append(sh(app, "id; ls -lZ ${TtyNmeaSource.TTY} 2>&1; getprop | grep -i -E 'gps|gnss' | head -20"))
            sb.append(sh(app, "appops get ${app.packageName} android:mock_location 2>&1"))
            sb.append("\n== dumpsys location (провайдеры, запросы)\n")
            sb.append(
                sh(
                    app,
                    "dumpsys location | grep -i -E 'yandex|Provider|Reciever|UpdateRecord|gps:|fused|network' | head -60"
                )
            )

            step("NMEA с порта при штатном GPS (3 с)…")
            sb.append("\n== NMEA с ${TtyNmeaSource.TTY}, штатный GPS работает\n")
            sb.append(readTty(app, 3_000))

            step("подмена gps на 6 с: идёт ли NMEA, когда штатный GPS остановлен…")
            sb.append("\n== NMEA при подмене gps (штатный GPS остановлен)\n")
            val perm = AdbShell.exec(app, "appops set ${app.packageName} android:mock_location allow; echo ok")
            sb.append("appops mock_location: ").append(if (perm?.contains("ok") == true) "выдано" else "нет ($perm)").append('\n')
            val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val mocked = try {
                runCatching { lm.removeTestProvider(LocationManager.GPS_PROVIDER) }
                @Suppress("DEPRECATION")
                lm.addTestProvider(
                    LocationManager.GPS_PROVIDER, false, true, false, false, true, true, true,
                    Criteria.POWER_HIGH, Criteria.ACCURACY_FINE
                )
                lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
                true
            } catch (e: Exception) {
                sb.append("addTestProvider: ${e.javaClass.simpleName}: ${e.message}\n")
                false
            }
            if (mocked) {
                SystemClock.sleep(1_000)
                sb.append(readTty(app, 5_000))
                runCatching { lm.removeTestProvider(LocationManager.GPS_PROVIDER) }
                sb.append("подмена снята\n")
            }

            step("запись фиксов gps и скорости машины ${RECORD_MS / 1000} с — едьте с разгонами и торможениями")
            sb.append(record(app, lm))
        } catch (e: Throwable) {
            Log.w(TAG, "probe", e)
            sb.append("\nОШИБКА: ").append(Log.getStackTraceString(e))
        } finally {
            val file = runCatching { save(app, sb) }.getOrNull()
            GpsInject.resume(app)
            status = "готово: ${file?.absolutePath ?: "отчёт не записан"}\n" + status
            running = false
        }
    }

    /** Прочитать порт [ms] мс через adb: сколько строк и верных NMEA. */
    private fun readTty(app: Context, ms: Long): String {
        val raw = StringBuilder()
        var stop: (() -> Unit)? = null
        val t = Thread {
            SystemClock.sleep(ms)
            stop?.invoke()
        }
        t.start()
        val end = AdbShell.stream(app, "cat ${TtyNmeaSource.TTY} 2>&1", onOpen = { stop = it }) {
            raw.append(it)
            raw.length < 200_000
        }
        t.join()
        val lines = raw.lines().filter { it.isNotBlank() }
        val parsed = lines.mapNotNull { Nmea.parse(it) }
        val rmc = parsed.filterIsInstance<Nmea.Rmc>()
        val sb = StringBuilder()
        sb.append("конец: ${end ?: "нет adb (${AdbShell.lastStatus})"}; байт ${raw.length}, строк ${lines.size}, ")
            .append("верных NMEA ${parsed.size} (RMC ${rmc.size}, с фиксом ${rmc.count { it.valid }})\n")
        lines.take(12).forEach { sb.append("  ").append(it.take(100)).append('\n') }
        return sb.toString()
    }

    @SuppressLint("MissingPermission")
    private fun record(app: Context, lm: LocationManager): String {
        val gps = ArrayList<Pair<Long, Float>>()
        val car = ArrayList<Pair<Long, Float>>()
        val fixLines = ArrayList<String>()
        val intervals = ArrayList<Long>()
        var lastAt = 0L
        val ht = HandlerThread("GpsProbeLoc").apply { start() }
        val listener = object : LocationListener {
            override fun onLocationChanged(l: Location) {
                val now = SystemClock.elapsedRealtime()
                synchronized(gps) {
                    if (lastAt > 0) intervals += now - lastAt
                    lastAt = now
                    gps += now to l.speed * 3.6f
                    // Возраст фикса по его собственной метке (если HAL ставит честно).
                    val age = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000
                    if (fixLines.size < 400) {
                        fixLines += "%d  %.6f %.6f  v=%.1f  bear=%.0f  acc=%.0f  возраст=%d мс  car=%s".format(
                            Locale.US, now, l.latitude, l.longitude, l.speed * 3.6f, l.bearing, l.accuracy, age,
                            carSpeed()?.let { "%.0f".format(Locale.US, it) } ?: "—"
                        )
                    }
                }
            }
            override fun onStatusChanged(p: String?, s: Int, e: Bundle?) = Unit
            override fun onProviderEnabled(p: String) = Unit
            override fun onProviderDisabled(p: String) = Unit
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, listener, ht.looper)
        } catch (e: Exception) {
            ht.quitSafely()
            return "\n== запись\nrequestLocationUpdates: ${e.javaClass.simpleName}: ${e.message}\n"
        }
        val end = SystemClock.elapsedRealtime() + RECORD_MS
        while (SystemClock.elapsedRealtime() < end) {
            carSpeed()?.let { car += SystemClock.elapsedRealtime() to it }
            val left = (end - SystemClock.elapsedRealtime()) / 1000
            if (left % 5 == 0L) {
                status = "запись ещё $left с: фиксов ${synchronized(gps) { gps.size }}, " +
                    "скорость машины ${carSpeed()?.let { "%.0f".format(Locale.US, it) } ?: "нет"}"
            }
            SystemClock.sleep(100)
        }
        lm.removeUpdates(listener)
        ht.quitSafely()
        val sb = StringBuilder("\n== запись ${RECORD_MS / 1000} с\n")
        synchronized(gps) {
            sb.append("фиксов ${gps.size}, замеров скорости машины ${car.size}\n")
            if (intervals.isNotEmpty()) {
                val sorted = intervals.sorted()
                sb.append("интервал фиксов: медиана ${sorted[sorted.size / 2]} мс, мин ${sorted.first()}, макс ${sorted.last()}\n")
            }
            val lag = GpsLead.estimateLag(gps, car)
            sb.append("оценка задержки GPS: ").append(lag?.let { "$it мс" } ?: "нет (мало данных или скорость почти не менялась)").append('\n')
            status = "задержка GPS: ${lag?.let { "$it мс" } ?: "не определена"}"
            sb.append("\nфиксы (время, координаты, скорость GPS, курс, точность, возраст, скорость машины):\n")
            fixLines.forEach { sb.append(it).append('\n') }
        }
        return sb.toString()
    }

    private fun carSpeed(): Float? = ClusterPps.speedKmh()?.toFloat() ?: CarHub.data?.speedKmh

    private fun step(s: String) {
        status = s
        Log.i(TAG, s)
    }

    private fun sh(app: Context, cmd: String): String {
        val out = AdbShell.exec(app, cmd) ?: return "(нет adb: ${AdbShell.lastStatus})\n"
        return if (out.endsWith("\n")) out else "$out\n"
    }

    private fun save(app: Context, sb: StringBuilder): File {
        val dir = File(app.getExternalFilesDir(null), "gps_probe").apply { mkdirs() }
        return File(dir, "gps_${FILE_STAMP.format(Date())}.txt").also { it.writeText(sb.toString()) }
    }

    private val STAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)
    private val FILE_STAMP = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private const val TAG = "GpsProbe"
    const val RECORD_MS = 120_000L
}
