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
 * Сигнал светофора впереди. В Яндексе 14.8.4 это кнопка карты
 * #button_trafficlight (ru.yandex.yandexnavi.ui.buttons.TrafficLightButton):
 * цвет (RED/YELLOW/GREEN, ещё DISABLED/UNAVAILABLE/LOADING — серая) и
 * секунды она рисует на холсте — ни текста, ни описания у узла нет. Узел
 * есть и тогда, когда светофора нет (кнопка сидит в одном контейнере с
 * кнопкой парковок), поэтому светофор = цветной пиксель в её границах:
 * снимок экрана через [AdbShell], как у [ManeuverGrab].
 *
 * Только пока кнопка видна на экране (Яндекс на весь экран); в блоке под
 * дашбордом кнопки у Яндекса не рисуются — светофора не узнать.
 * Снимок не чаще [REFRESH_MS]; результат — со следующего опроса окна.
 */
object TrafficLightGrab {

    @Volatile private var light: TrafficLight? = null
    @Volatile private var grabbedAt = 0L
    @Volatile private var busy = false
    @Volatile private var failedAt = 0L

    @Volatile var lastDump: String = "светофор с экрана не снимали"
        private set

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "TrafficLightGrab").apply { isDaemon = true } }

    /** Сигнал светофора в кнопке с границами [bounds]; null — нет/не узнали. */
    fun light(ctx: Context, bounds: Rect): TrafficLight? {
        if (bounds.width() < MIN_PX || bounds.height() < MIN_PX) return null
        val now = SystemClock.uptimeMillis()
        if (now - grabbedAt > REFRESH_MS && !busy && now - failedAt > CHANNEL_BACKOFF_MS) {
            grabbedAt = now
            busy = true
            val app = ctx.applicationContext
            val r = Rect(bounds)
            worker.execute {
                try {
                    grab(app, r)
                } finally {
                    busy = false
                }
            }
        }
        // Старый результат не держим дольше двух периодов — сигнал меняется.
        return light.takeIf { now - grabbedAt < 2 * REFRESH_MS + GRAB_MS }
    }

    /** Кнопки на экране нет — забыть. */
    fun reset() {
        light = null
    }

    private fun grab(app: Context, r: Rect) {
        val t0 = SystemClock.uptimeMillis()
        val dir = app.externalCacheDir ?: run {
            lastDump = "нет externalCacheDir"
            return
        }
        val file = File(dir, "trafficlight_scr.png")
        file.delete()
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
                    light = null
                    return
                }
                dec.decodeRegion(area, null)
            } finally {
                dec.recycle()
            }
            val px = IntArray(crop.width * crop.height)
            crop.getPixels(px, 0, crop.width, 0, 0, crop.width, crop.height)
            crop.recycle()
            val c = classify(px)
            light = c.light
            lastDump = "$r → ${c.light} (${c.describe}), ${SystemClock.uptimeMillis() - t0} мс"
        } catch (e: Exception) {
            Log.w(TAG, "decode", e)
            lastDump = "разбор снимка: ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            file.delete()
        }
        Log.i(TAG, lastDump)
    }

    class Result(val light: TrafficLight?, val describe: String)

    /**
     * Цвет светофора по пикселям ARGB кнопки (чистая функция). Цвета
     * Яндекса: красный #E55245, жёлтый #FFDF61, зелёный #85CC3D, серый
     * (нет данных) #646670. Насыщенные яркие пиксели раскладываем по тону;
     * побеждает цвет, которого не меньше [MIN_SHARE] и вдвое больше любого другого.
     */
    fun classify(argb: IntArray): Result {
        if (argb.isEmpty()) return Result(null, "пусто")
        var red = 0
        var yellow = 0
        var green = 0
        for (p in argb) {
            if ((p ushr 24) < 128) continue
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            if (max < 115 || max - min < 0.45f * max) continue
            val d = (max - min).toFloat()
            var hue = when (max) {
                r -> 60f * (((g - b) / d) % 6f)
                g -> 60f * ((b - r) / d + 2f)
                else -> 60f * ((r - g) / d + 4f)
            }
            if (hue < 0) hue += 360f
            when {
                hue < 20f || hue >= 340f -> red++
                hue in 35f..65f -> yellow++
                hue in 75f..150f -> green++
            }
        }
        val counts = listOf(TrafficLight.RED to red, TrafficLight.YELLOW to yellow, TrafficLight.GREEN to green)
        val best = counts.maxByOrNull { it.second }!!
        val second = counts.filter { it !== best }.maxOf { it.second }
        val ok = best.second >= MIN_SHARE * argb.size && best.second >= 2 * second
        return Result(best.first.takeIf { ok }, "к=$red ж=$yellow з=$green из ${argb.size}")
    }

    private const val MIN_PX = 12
    private const val MIN_SHARE = 0.03f
    private const val REFRESH_MS = 3_000L
    private const val GRAB_MS = 2_000L
    private const val CHANNEL_BACKOFF_MS = 60_000L
    private const val TAG = "TrafficLightGrab"
}
