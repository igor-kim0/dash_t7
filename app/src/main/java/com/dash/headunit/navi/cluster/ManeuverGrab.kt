package com.dash.headunit.navi.cluster

import android.content.Context
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import com.dash.headunit.navi.AdbShell
import java.io.File
import java.util.concurrent.Executors

/**
 * Тип манёвра, когда Яндекс на экране: фонового уведомления со стрелкой
 * тогда нет, а служба спец. возможностей видит только границы картинки
 * стрелки (без описания). Снимаем экран (screencap через [AdbShell] —
 * тот же канал, что у подложки навигатора), вырезаем стрелку по границам
 * и узнаём её [ManeuverMatcher].
 *
 * Снимок дорогой (~0,5 с), поэтому только при смене манёвра: другие
 * границы/улица, выросшее расстояние (начался следующий манёвр) или раз в
 * [REFRESH_MS]. Вызывается из главного потока; результат — со следующего
 * опроса окна.
 */
object ManeuverGrab {

    @Volatile private var cached: Maneuver? = null
    @Volatile private var key: String? = null
    @Volatile private var lastMeters: Int? = null
    @Volatile private var grabbedAt = 0L
    @Volatile private var busy = false
    @Volatile private var failedAt = 0L

    /** Последний разбор — для дампа NAVI_CLUSTER. */
    @Volatile var lastDump: String = "стрелку с экрана не снимали"
        private set

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "ManeuverGrab").apply { isDaemon = true } }

    /**
     * Манёвр стрелки в [bounds] (экранные координаты); null — пока не
     * узнали. [street]/[distance] — тексты карточки, по ним видно смену манёвра.
     */
    fun maneuver(ctx: Context, bounds: Rect, street: String?, distance: String?): Maneuver? {
        if (bounds.width() < MIN_PX || bounds.height() < MIN_PX) return null
        val now = SystemClock.uptimeMillis()
        val k = "${bounds.flattenToString()}|$street"
        val meters = GuidanceText.meters(distance)
        val prev = lastMeters
        lastMeters = meters
        val changed = k != key || (meters != null && prev != null && meters > prev + GROW_M)
        if (changed) cached = null
        val stale = now - grabbedAt > (if (cached == null) RETRY_MS else REFRESH_MS)
        if ((changed || stale) && !busy && now - failedAt > CHANNEL_BACKOFF_MS) {
            key = k
            grabbedAt = now
            busy = true
            val app = ctx.applicationContext
            val r = Rect(bounds)
            worker.execute {
                try {
                    grab(app, r, k)
                } finally {
                    busy = false
                }
            }
        }
        return cached
    }

    /** Маршрут кончился — забыть. */
    fun reset() {
        cached = null
        key = null
        lastMeters = null
    }

    private fun grab(app: Context, r: Rect, k: String) {
        val t0 = SystemClock.uptimeMillis()
        val dir = app.externalCacheDir ?: run {
            lastDump = "нет externalCacheDir"
            return
        }
        val file = File(dir, "maneuver_scr.png")
        file.delete()
        // shell пишет в Android/data приложения (группа ext_data_rw).
        val out = AdbShell.exec(app, "screencap -p ${file.absolutePath} 2>&1; echo __ok__", authTimeoutMs = 1_000)
        if (out == null || !file.exists()) {
            failedAt = SystemClock.uptimeMillis()
            lastDump = "screencap не вышел: ${out?.trim() ?: "adb-канал: ${AdbShell.lastStatus}"}"
            return
        }
        try {
            val dec = BitmapRegionDecoder.newInstance(file.absolutePath, false)
            val crop = try {
                val area = Rect(r)
                if (!area.intersect(0, 0, dec.width, dec.height)) {
                    lastDump = "границы $r вне снимка ${dec.width}x${dec.height}"
                    return
                }
                dec.decodeRegion(area, null)
            } finally {
                dec.recycle()
            }
            val m = ManeuverMatcher.fromScreen(app, crop)
            crop.recycle()
            // Пока снимали, манёвр мог смениться — тогда результат не его.
            if (key == k) cached = m.maneuver
            lastDump = "$r → ${m.maneuver} (${m.describe}), ${SystemClock.uptimeMillis() - t0} мс"
        } catch (e: Exception) {
            Log.w(TAG, "decode", e)
            lastDump = "разбор снимка: ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            file.delete()
        }
        Log.i(TAG, lastDump)
    }

    private const val MIN_PX = 12
    /** Расстояние выросло больше чем на столько — уже следующий манёвр. */
    private const val GROW_M = 50
    private const val REFRESH_MS = 15_000L
    private const val RETRY_MS = 3_000L
    private const val CHANNEL_BACKOFF_MS = 60_000L
    private const val TAG = "ManeuverGrab"
}
