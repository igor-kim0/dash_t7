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
 * (view_notification_speedcamera_*) и плашку манёвра (layout_maneuverballoon:
 * text_maneuverballoon_distance/metrics, text_nextstreet). Так есть данные,
 * даже когда навигатор на экране и фонового уведомления ведения нет.
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
        scheduled = false
        if (instance === this) instance = null
        GuidanceHub.setFromA11y(null)
        super.onDestroy()
    }

    private fun scanNow() {
        lastScan = SystemClock.uptimeMillis()
        val root = naviRoot()
        if (root == null) {
            GuidanceHub.setFromA11y(null)
            return
        }
        try {
            GuidanceHub.setFromA11y(read(root))
        } catch (e: Exception) {
            Log.w(TAG, "scan", e)
        }
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
        val arrowDesc = node("image_maneuverballoon_maneuver")?.contentDescription?.toString()
        val street = text("text_nextstreet")
        val camDist = text("view_notification_speedcamera_distance")
        val camLabel = text("view_notification_speedcamera_distance_label")
        val g = Guidance(
            maneuver = GuidanceText.classify(arrowDesc),
            distance = dist?.let { if (metrics != null) "$it $metrics" else it },
            street = street,
            speedLimit = limit,
            cameraDistance = camDist?.let { if (camLabel != null) "$it $camLabel" else it },
            cameraLimit = GuidanceText.speedLimit(text("view_notification_speedcamera_limit")),
        )
        return g.takeIf { it.hasManeuver || it.speedLimit != null || it.cameraDistance != null }
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
        private const val MAX_DUMP_NODES = 600
        private const val TAG = "NaviA11y"
    }
}
