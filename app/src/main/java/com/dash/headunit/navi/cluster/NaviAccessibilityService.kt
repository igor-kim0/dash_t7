package com.dash.headunit.navi.cluster

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.dash.headunit.settings.Autostart

/**
 * Читает окно Яндекс Навигатора (только чтение, ничего не нажимает):
 * ограничение скорости (layout_map_speedlimit → text_speedlimit), камеры
 * (view_notification_speedcamera_*), остаток маршрута (textview_eta_*) и
 * карточку манёвра (contextmaneuverview: text_maneuverballoon_distance/metrics,
 * text_nextstreet). Так есть данные, даже когда навигатор на экране и
 * фонового уведомления ведения нет. Тип манёвра — по снимку стрелки
 * ([ManeuverGrab]).
 *
 * Яндекс 14.8 (эмулятор, 2026-10-21): карточка-view видна не всегда — часто
 * манёвр (с полосами) рисуется прямо в карте, тогда его тут нет; у картинки
 * стрелки нет описания, так что тип манёвра обычно неизвестен — стрелка
 * есть только из фонового уведомления.
 *
 * Включается в enabled_accessibility_services — см. [ClusterSetup].
 * Окно навигатора не обязательно активное (он под дашбордом в блоке),
 * поэтому ищем его среди всех окон (flagRetrieveInteractiveWindows).
 */
class NaviAccessibilityService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private var lastScan = 0L
    private var scheduled = false
    private val scan = Runnable {
        scheduled = false
        scanNow()
    }
    private val poll = Runnable { scanNow() }

    override fun onServiceConnected() {
        instance = this
        ClusterOverlay.init(this)
        Autostart.onServiceBound(this, "спец. возможности")
        main.post(scan)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != YANDEX_NAVI) return
        // Навигатор шлёт события пачками (карта перерисовывается) — не чаще
        // раза в SCAN_MS.
        if (scheduled) return
        scheduled = true
        val wait = (lastScan + SCAN_MS - SystemClock.uptimeMillis()).coerceAtLeast(0)
        main.postDelayed(scan, wait)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        main.removeCallbacks(scan)
        main.removeCallbacks(poll)
        scheduled = false
        if (instance === this) instance = null
        GuidanceHub.setFromA11y(null)
        super.onDestroy()
    }

    private fun scanNow() {
        lastScan = SystemClock.uptimeMillis()
        // Перечитываем и без событий: пока тексты навигатора не меняются
        // (стоим в пробке, навигатор под дашбордом), событий нет, а данные
        // в [GuidanceHub] через A11Y_FRESH_MS протухают — маршрут пропадал.
        main.removeCallbacks(poll)
        val root = naviRoot()
        if (root == null) {
            GuidanceHub.setFromA11y(null)
            main.postDelayed(poll, IDLE_POLL_MS)
            return
        }
        val g = try {
            read(root)
        } catch (e: Exception) {
            Log.w(TAG, "scan", e)
            null
        }
        GuidanceHub.setFromA11y(g)
        main.postDelayed(poll, if (g != null) ROUTE_POLL_MS else IDLE_POLL_MS)
    }

    private fun naviRoot(): AccessibilityNodeInfo? {
        try {
            for (w in windows) {
                val r = w.root ?: continue
                if (r.packageName?.toString() == YANDEX_NAVI) return r
            }
        } catch (e: Exception) {
            Log.w(TAG, "windows", e)
        }
        return rootInActiveWindow?.takeIf { it.packageName?.toString() == YANDEX_NAVI }
    }

    private fun read(root: AccessibilityNodeInfo): Guidance? {
        fun node(id: String): AccessibilityNodeInfo? =
            root.findAccessibilityNodeInfosByViewId("$YANDEX_NAVI:id/$id")
                ?.firstOrNull { it.isVisibleToUser }

        fun text(id: String): String? =
            node(id)?.let { it.text ?: it.contentDescription }?.toString()?.trim()?.takeIf { it.isNotEmpty() }

        val limit = SPEED_LIMIT_IDS.firstNotNullOfOrNull { GuidanceText.speedLimit(text(it)) }
        val dist = text("text_maneuverballoon_distance")
        val metrics = text("text_maneuverballoon_metrics")
        val arrow = node("image_maneuverballoon_maneuver")
        val arrowDesc = arrow?.contentDescription?.toString()
        val street = text("text_nextstreet")
        // Описания у стрелки нет — узнаём её по снимку экрана.
        val maneuver = GuidanceText.classify(arrowDesc) ?: arrow?.let {
            val r = android.graphics.Rect().also { b -> it.getBoundsInScreen(b) }
            ManeuverGrab.maneuver(this, r, street, dist)
        }
        val camDist = text("view_notification_speedcamera_distance")
        val camLabel = text("view_notification_speedcamera_distance_label")
        val g = Guidance(
            remainingDistance = text("textview_eta_distance"),
            remainingTime = text("textview_eta_time"),
            arrival = text("textview_eta_arrival"),
            maneuver = maneuver,
            distance = dist?.let { if (metrics != null) "$it $metrics" else it },
            street = street,
            speedLimit = limit,
            cameraDistance = camDist?.let { if (camLabel != null) "$it $camLabel" else it },
            cameraLimit = GuidanceText.speedLimit(text("view_notification_speedcamera_limit")),
            overspeed = node("view_speedlimit_alarm") != null,
        )
        return g.takeIf { it.onRoute || it.speedLimit != null || it.cameraDistance != null }
    }

    /** Окна, которые система отдаёт службе (заголовок, пакет, границы), — для диагностики. */
    fun describeWindows(): String = try {
        windows.joinToString("\n") { w ->
            val b = android.graphics.Rect().also { w.getBoundsInScreen(it) }
            "id=${w.id} type=${w.type} layer=${w.layer} ${w.title ?: "-"} " +
                "pkg=${w.root?.packageName ?: "?"} $b"
        }.ifEmpty { "(пусто)" }
    } catch (e: Exception) {
        "ошибка: ${e.javaClass.simpleName}: ${e.message}"
    }

    /** Дерево окна навигатора текстом — для диагностики. */
    fun dumpTree(): String {
        val root = naviRoot() ?: return "окна $YANDEX_NAVI нет среди окон"
        val sb = StringBuilder()
        var count = 0
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (count++ > MAX_DUMP_NODES) return
            sb.append("  ".repeat(depth)).append(n.className?.toString()?.substringAfterLast('.'))
            n.viewIdResourceName?.let { sb.append(" #").append(it.substringAfter(":id/")) }
            n.text?.let { sb.append(" \"").append(it).append('"') }
            n.contentDescription?.let { sb.append(" desc=\"").append(it).append('"') }
            if (!n.isVisibleToUser) sb.append(" (не виден)")
            sb.append('\n')
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        return sb.toString()
    }

    companion object {
        @Volatile
        var instance: NaviAccessibilityService? = null
            private set

        private val SPEED_LIMIT_IDS = listOf(
            "text_speedlimit", "speedlimitview_guidance", "view_projected_speed_limit", "speed_limit_text",
        )
        private const val SCAN_MS = 500L
        /** Перечитывание окна без событий: идёт ведение / навигатора нет или стоит без маршрута. */
        private const val ROUTE_POLL_MS = 1_000L
        private const val IDLE_POLL_MS = 3_000L
        private const val MAX_DUMP_NODES = 600
        private const val TAG = "NaviA11y"
    }
}
