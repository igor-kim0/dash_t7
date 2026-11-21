package com.dash.headunit.location

import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.dash.headunit.car.CarHub
import com.dash.headunit.car.ClusterPps
import com.dash.headunit.navi.AdbShell
import com.dash.headunit.settings.AppSettings

/**
 * Координаты для навигатора: подмена провайдера `gps` (test provider)
 * своими точками — из встроенного модуля ([TtyNmeaSource]) или внешнего
 * GPS по Bluetooth ([BtNmeaSource]), со сдвигом вперёд на опережение
 * ([GpsLead]). Точки отдаём [EMIT_MS] — между фиксами (обычно 1 Гц) метка
 * едет плавно по скорости машины.
 *
 * Пока подмена стоит, штатный GPS отключён у всех (навигатор, Android Auto,
 * T-Box получают наши точки). Поэтому: источник молчит дольше [STALE_MS] —
 * подмену снимаем, штатный GPS возвращается; при старте процесса старую
 * подмену (процесс мог умереть с ней) всегда снимаем.
 *
 * Разрешение: appops android:mock_location (выдаём через adb-канал).
 */
object GpsInject {

    @Volatile var status: String = "выключено"
        private set

    /** Изменения для открытого диалога. */
    var onChanged: (() -> Unit)? = null

    private var appContext: Context? = null
    private val thread by lazy { HandlerThread("GpsInject").apply { start() } }
    private val h by lazy { Handler(thread.looper) }

    private var mode = AppSettings.GPS_STOCK
    private var leadMs = 0L
    private var btAddress = ""
    private var source: GpsSource? = null
    private var installed = false
    private var cleaned = false
    private var permitted: Boolean? = null

    @Volatile private var lastFix: GpsFix? = null
    private var prevFix: GpsFix? = null
    private var lastBearing: Float? = null
    private var startedAt = 0L
    private var lastChanged = 0L

    /** Последний фикс источника — для диалога. */
    fun lastFix(): GpsFix? = lastFix

    /** Прочитать настройки и привести работу в соответствие (любой поток). */
    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        val m = AppSettings.gpsMode(app)
        val lead = AppSettings.gpsLeadMs(app).toLong()
        val bt = AppSettings.gpsBtAddress(app)
        h.post { apply(app, m, lead, bt) }
    }

    /** Остановить подмену (проба GPS слушает настоящий модуль). [resume] — вернуть по настройкам. */
    fun pause() = h.post { stopAll("приостановлено пробой GPS") }

    fun resume(context: Context) {
        h.post { mode = -1 } // заставить перезапуститься
        init(context)
    }

    private fun apply(app: Context, m: Int, lead: Long, bt: String) {
        if (!cleaned) {
            cleaned = true
            // Процесс мог умереть с поставленной подменой — снять.
            removeProvider(app, force = true)
        }
        val sameSource = m == mode && bt == btAddress
        leadMs = lead
        if (sameSource && source != null) {
            changed()
            return
        }
        mode = m
        btAddress = bt
        stopAll(null)
        val src = when (m) {
            AppSettings.GPS_INTERNAL -> TtyNmeaSource(app)
            AppSettings.GPS_BT -> if (bt.isEmpty()) null else BtNmeaSource(bt)
            else -> null
        }
        if (src == null) {
            status = if (m == AppSettings.GPS_BT) "не выбрано Bluetooth-устройство" else "выключено (штатный GPS)"
            changed()
            return
        }
        source = src
        startedAt = SystemClock.elapsedRealtime()
        status = "запуск: ${src.label}"
        src.start { fix -> h.post { onFix(app, fix) } }
        h.post(tick)
        changed()
    }

    private fun stopAll(why: String?) {
        h.removeCallbacks(tick)
        source?.stop()
        source = null
        lastFix = null
        prevFix = null
        appContext?.let { removeProvider(it, force = false) }
        if (why != null) {
            status = why
            changed()
        }
    }

    private fun onFix(app: Context, fix: GpsFix) {
        val prev = lastFix
        prevFix = prev
        lastFix = fix
        // Курс: свой у фикса на ходу, иначе — по перемещению.
        val sp = fix.speedMps ?: 0f
        lastBearing = when {
            fix.bearing != null && sp >= GpsLead.MIN_SPEED_MPS -> fix.bearing
            prev != null && distanceM(prev, fix) > 3.0 -> GpsLead.bearing(prev.lat, prev.lon, fix.lat, fix.lon)
            else -> lastBearing
        }
        if (!installed) installProvider(app)
    }

    private val tick = object : Runnable {
        override fun run() {
            val app = appContext ?: return
            val src = source ?: return
            val fix = lastFix
            val now = SystemClock.elapsedRealtime()
            val age = fix?.let { now - it.receivedAt }
            when {
                fix == null || age!! > STALE_MS -> {
                    if (installed) {
                        removeProvider(app, force = false)
                        if (src is TtyNmeaSource && fix != null) {
                            // Модуль замолчал, как только штатный GPS отключили.
                            src.stop()
                            source = null
                            status = "встроенный модуль молчит при подмене — штатный GPS возвращён; " +
                                "поправка встроенного GPS на этом ГУ не работает"
                            changed()
                            return
                        }
                    }
                    status = "${src.label}: ${src.status}" +
                        (if (fix == null) "" else " (нет данных ${age!! / 1000} с — штатный GPS)")
                }
                else -> {
                    if (installed) emit(app, fix, age)
                    status = "${src.label}: ${fix.satellites?.let { "спутников $it, " } ?: ""}" +
                        "фиксов ${src.fixes}, опережение ${leadMs} мс" +
                        (if (installed) "" else " — подмена не поставлена: $permitText")
                }
            }
            if (now - lastChanged >= 1_000L) {
                lastChanged = now
                changed()
            }
            h.postDelayed(this, EMIT_MS)
        }
    }

    private var permitText = ""

    /** Скорость для сдвига: машина (CAN) → GPS. */
    private fun speedMps(fix: GpsFix): Float {
        val car = ClusterPps.speedKmh()?.toFloat() ?: CarHub.data?.speedKmh
        return car?.div(3.6f) ?: fix.speedMps ?: 0f
    }

    private fun emit(app: Context, fix: GpsFix, ageMs: Long) {
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val speed = speedMps(fix)
        val bearing = lastBearing
        val (lat, lon) = if (bearing != null) {
            GpsLead.project(fix.lat, fix.lon, bearing, GpsLead.distance(speed, leadMs, ageMs))
        } else {
            fix.lat to fix.lon
        }
        val loc = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = lat
            longitude = lon
            accuracy = fix.hdop?.let { (it * 5f).coerceIn(3f, 50f) } ?: 5f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            this.speed = speed
            bearing?.let { this.bearing = it }
            fix.altitude?.let { altitude = it }
            fix.satellites?.let { extras = Bundle().apply { putInt("satellites", it) } }
        }
        try {
            lm.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc)
        } catch (e: Exception) {
            Log.w(TAG, "setTestProviderLocation", e)
            installed = false
            permitText = "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun installProvider(app: Context) {
        if (permitted != true) {
            // appops через adb (блокирует — мы в своём потоке).
            val out = AdbShell.exec(app, "appops set ${app.packageName} android:mock_location allow; echo ok")
            permitted = out?.contains("ok") == true
            if (permitted != true) {
                permitText = "нет adb-канала для разрешения подмены (${AdbShell.lastStatus})"
                return
            }
        }
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            runCatching { lm.removeTestProvider(LocationManager.GPS_PROVIDER) }
            @Suppress("DEPRECATION")
            lm.addTestProvider(
                LocationManager.GPS_PROVIDER, false, true, false, false, true, true, true,
                Criteria.POWER_HIGH, Criteria.ACCURACY_FINE
            )
            lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            installed = true
            permitText = ""
            Log.i(TAG, "подмена gps поставлена")
        } catch (e: Exception) {
            Log.w(TAG, "addTestProvider", e)
            permitText = "${e.javaClass.simpleName}: ${e.message}"
            permitted = null
        }
    }

    private fun removeProvider(app: Context, force: Boolean) {
        if (!installed && !force) return
        installed = false
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            lm.removeTestProvider(LocationManager.GPS_PROVIDER)
            Log.i(TAG, "подмена gps снята")
        } catch (e: Exception) {
            // Подмены не было / нет разрешения — штатный GPS и так на месте.
        }
    }

    private fun distanceM(a: GpsFix, b: GpsFix): Double {
        val r = FloatArray(1)
        Location.distanceBetween(a.lat, a.lon, b.lat, b.lon, r)
        return r[0].toDouble()
    }

    private fun changed() {
        onChanged?.let { android.os.Handler(android.os.Looper.getMainLooper()).post(it) }
    }

    private const val TAG = "GpsInject"
    private const val EMIT_MS = 100L
    private const val STALE_MS = 10_000L
}
