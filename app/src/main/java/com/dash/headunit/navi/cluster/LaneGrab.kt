package com.dash.headunit.navi.cluster

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import com.dash.headunit.navi.AdbShell
import java.io.File
import java.util.concurrent.Executors

/**
 * Знак движения по полосам перед перекрёстком. Яндекс 14.8.4 рисует его
 * синей плашкой прямо в карте (GL) над перекрёстком (ГУ 2026-11-02), а
 * #lane_signs_container в карточке манёвра скрыт — узлов с полосами нет.
 * Поэтому: снимок экрана через [AdbShell] (как [ManeuverGrab]), в окне
 * навигатора ищем плашку цвета балуна Яндекса с белыми стрелками внутри
 * ([find]) и отдаём её вырезанной картинкой — приборка рисует её как есть.
 * Если контейнер полос в карточке виден — вырезаем просто его.
 *
 * Только близко к манёвру ([NEAR_M]), снимок не чаще [REFRESH_MS];
 * результат — со следующего опроса окна.
 */
object LaneGrab {

    @Volatile private var lanes: Bitmap? = null
    @Volatile private var key: String? = null
    @Volatile private var grabbedAt = 0L
    @Volatile private var foundAt = 0L
    @Volatile private var busy = false
    @Volatile private var failedAt = 0L

    @Volatile var lastDump: String = "полосы с экрана не снимали"
        private set

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "LaneGrab").apply { isDaemon = true } }

    /**
     * Картинка полос для манёвра на [street] в [distance]; null — нет/не нашли.
     * [window] — окно навигатора на экране, [card] — карточка манёвра (там
     * тот же синий, её не берём), [container] — видимый контейнер полос в карточке.
     */
    fun lanes(ctx: Context, window: Rect, card: Rect?, container: Rect?, street: String?, distance: String?): Bitmap? {
        val m = GuidanceText.meters(distance)
        if (container == null && (m == null || m > NEAR_M)) {
            reset()
            return null
        }
        val k = street.toString()
        if (k != key) {
            key = k
            lanes = null
        }
        val now = SystemClock.uptimeMillis()
        if (now - grabbedAt > REFRESH_MS && !busy && now - failedAt > CHANNEL_BACKOFF_MS) {
            grabbedAt = now
            busy = true
            val app = ctx.applicationContext
            val w = Rect(window)
            val c = card?.let { Rect(it) }
            val box = container?.let { Rect(it) }
            worker.execute {
                try {
                    grab(app, w, c, box, k)
                } finally {
                    busy = false
                }
            }
        }
        // Плашка пропадает после перекрёстка — старую не держим.
        return lanes.takeIf { now - foundAt < 2 * REFRESH_MS + GRAB_MS }
    }

    fun reset() {
        lanes = null
        key = null
    }

    private fun grab(app: Context, window: Rect, card: Rect?, container: Rect?, k: String) {
        val t0 = SystemClock.uptimeMillis()
        val dir = app.externalCacheDir ?: run {
            lastDump = "нет externalCacheDir"
            return
        }
        val file = File(dir, "lanes_scr.png")
        file.delete()
        val out = AdbShell.exec(app, "screencap -p ${file.absolutePath} 2>&1; echo __ok__", authTimeoutMs = 1_000)
        if (out == null || !file.exists()) {
            failedAt = SystemClock.uptimeMillis()
            lastDump = "screencap не вышел: ${out?.trim() ?: "adb-канал: ${AdbShell.lastStatus}"}"
            return
        }
        try {
            val shot = BitmapFactory.decodeFile(file.absolutePath) ?: run {
                lastDump = "снимок не прочитан"
                return
            }
            try {
                val area = Rect(container ?: window)
                if (!area.intersect(0, 0, shot.width, shot.height)) {
                    lastDump = "границы $area вне снимка ${shot.width}x${shot.height}"
                    return
                }
                val box = if (container != null) {
                    area
                } else {
                    val px = IntArray(area.width() * area.height())
                    shot.getPixels(px, 0, area.width(), area.left, area.top, area.width(), area.height())
                    val exclude = card?.let { Box(it.left - area.left, it.top - area.top, it.right - area.left, it.bottom - area.top) }
                    find(px, area.width(), area.height(), exclude)
                        ?.let { Rect(it.l + area.left, it.t + area.top, it.r + area.left, it.b + area.top) }
                }
                val found = box?.let {
                    val r = Rect(it.left - MARGIN, it.top - MARGIN, it.right + MARGIN, it.bottom + MARGIN)
                    r.intersect(0, 0, shot.width, shot.height)
                    Bitmap.createBitmap(shot, r.left, r.top, r.width(), r.height())
                }
                if (key == k) {
                    lanes = found
                    if (found != null) foundAt = SystemClock.uptimeMillis()
                }
                lastDump = "${if (container != null) "карточка" else "карта"} → ${box ?: "нет плашки"}, " +
                    "${SystemClock.uptimeMillis() - t0} мс"
            } finally {
                shot.recycle()
            }
        } catch (e: Exception) {
            Log.w(TAG, "decode", e)
            lastDump = "разбор снимка: ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            file.delete()
        }
        Log.i(TAG, lastDump)
    }

    /**
     * Плашка полос в пикселях ARGB [px] ([w]×[h]) — чистая функция.
     * Связные области цвета балуна Яндекса (по сетке шагом [STEP]) не в
     * [exclude]; подходят по размеру, заполненности и белым стрелкам внутри.
     * Из подходящих — самая крупная. null — нет.
     */
    fun find(px: IntArray, w: Int, h: Int, exclude: Box?): Box? {
        val gw = w / STEP
        val gh = h / STEP
        if (gw < 2 || gh < 2) return null
        val blue = BooleanArray(gw * gh) { i -> isBalloon(px[(i / gw) * STEP * w + (i % gw) * STEP]) }
        val seen = BooleanArray(gw * gh)
        val queue = IntArray(gw * gh)
        var best: Box? = null
        var bestCount = 0
        for (start in blue.indices) {
            if (!blue[start] || seen[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            seen[start] = true
            var x0 = gw; var y0 = gh; var x1 = -1; var y1 = -1
            while (head < tail) {
                val i = queue[head++]
                val x = i % gw
                val y = i / gw
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
                fun push(j: Int) {
                    if (blue[j] && !seen[j]) {
                        seen[j] = true
                        queue[tail++] = j
                    }
                }
                if (x > 0) push(i - 1)
                if (x < gw - 1) push(i + 1)
                if (y > 0) push(i - gw)
                if (y < gh - 1) push(i + gw)
            }
            val count = tail
            if (count <= bestCount) continue
            val r = Box(x0 * STEP, y0 * STEP, (x1 + 1) * STEP, (y1 + 1) * STEP)
            if (!plausible(r, count * STEP * STEP, exclude)) continue
            if (!hasArrows(px, w, r)) continue
            best = r
            bestCount = count
        }
        return best
    }

    private fun plausible(r: Box, area: Int, exclude: Box?): Boolean {
        val rw = r.w
        val rh = r.h
        if (rw < MIN_W || rh < MIN_H || rw > MAX_W || rh > MAX_H) return false
        val aspect = rw.toFloat() / rh
        if (aspect < 0.6f || aspect > 8f) return false
        if (area < MIN_FILL * rw * rh) return false
        return exclude == null || !r.intersects(exclude)
    }

    /** Внутри плашки есть белые стрелки, но она не сплошь белая. */
    private fun hasArrows(px: IntArray, w: Int, r: Box): Boolean {
        var white = 0
        var all = 0
        var y = r.t
        while (y < r.b) {
            var x = r.l
            while (x < r.r) {
                val p = px[y * w + x]
                if (((p shr 16) and 0xFF) > 215 && ((p shr 8) and 0xFF) > 215 && (p and 0xFF) > 215) white++
                all++
                x += 2
            }
            y += 2
        }
        val share = white.toFloat() / all.coerceAtLeast(1)
        return share in 0.03f..0.5f
    }

    /** Прямоугольник [l, r) × [t, b) — без android.graphics.Rect, чтобы [find] проверялся в JVM-тестах. */
    data class Box(val l: Int, val t: Int, val r: Int, val b: Int) {
        val w get() = r - l
        val h get() = b - t
        fun intersects(o: Box) = l < o.r && o.l < r && t < o.b && o.t < b
    }

    /** Цвет балуна Яндекса (ресурсы 14.8.4: balloon*, contextballoon_background*, maneuver_widget_background). */
    fun isBalloon(p: Int): Boolean {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return BALLOON.any { c ->
            maxOf(
                Math.abs(r - ((c shr 16) and 0xFF)),
                Math.abs(g - ((c shr 8) and 0xFF)),
                Math.abs(b - (c and 0xFF))
            ) <= TOLERANCE
        }
    }

    private val BALLOON = intArrayOf(0x1666FE, 0x1682FF, 0x1A6DFF, 0x2154AD, 0x1150C7)
    private const val TOLERANCE = 22
    private const val STEP = 2
    private const val MIN_W = 24
    private const val MIN_H = 20
    private const val MAX_W = 600
    private const val MAX_H = 220
    private const val MIN_FILL = 0.35f
    private const val MARGIN = 4
    /** Ближе этого к манёвру ищем полосы, м. */
    private const val NEAR_M = 700
    private const val REFRESH_MS = 2_500L
    private const val GRAB_MS = 2_000L
    private const val CHANNEL_BACKOFF_MS = 60_000L
    private const val TAG = "LaneGrab"
}
