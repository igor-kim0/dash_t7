package com.dash.headunit.navi.cluster

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.dash.headunit.settings.AppSettings
import com.dash.headunit.settings.Autostart

/**
 * Читает окно Яндекс Навигатора (нажимает только кнопку голоса — [clickVoice]):
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
 *
 * Чтение окна — синхронные вызовы в процесс навигатора: пока он занят
 * (холодный старт), каждый ждёт до нескольких секунд. Поэтому сканирование
 * идёт в своём потоке — в главном оно морозило дашборд и откладывало
 * «пинок» навигатора на старте (эмулятор 2026-10-27: до 4 с).
 */
class NaviAccessibilityService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("NaviA11y").apply { start() }
    private val bg = Handler(thread.looper)
    @Volatile private var lastScan = 0L
    @Volatile private var scheduled = false
    private val scan = Runnable {
        scheduled = false
        scanNow()
    }
    private val poll = Runnable { scanNow() }

    /**
     * Чужие узкие окна поверх экрана (полоса климата, systemui) — для
     * [com.dash.headunit.navi.NaviUnderlay.uncovered]. Большие (шторка
     * климата, диалоги) не берём: они временные.
     */
    private val updateBars = Runnable {
        try {
            val bars = windows.mapNotNull { w ->
                val pkg = w.root?.packageName?.toString() ?: return@mapNotNull null
                if (pkg == packageName || pkg == YANDEX_NAVI) return@mapNotNull null
                val b = android.graphics.Rect().also { w.getBoundsInScreen(it) }
                b.takeIf { !it.isEmpty && minOf(it.width(), it.height()) <= MAX_BAR_PX }
            }
            com.dash.headunit.navi.NaviUnderlay.setSystemBars(bars)
        } catch (e: Exception) {
            Log.w(TAG, "bars", e)
        }
    }

    /** Id знака ограничения, давший значение в последний раз (поток [bg]). */
    private var limitId: String? = null

    override fun onServiceConnected() {
        instance = this
        ClusterOverlay.init(this)
        Autostart.onServiceBound(this, "спец. возможности")
        // Диагностика борткомпьютера: процесс могли поднять без дашборда.
        com.dash.headunit.car.TripLog.init(this)
        com.dash.headunit.car.TripLog.write { "служба привязана (спец. возможности): ${com.dash.headunit.car.TripLog.processInfo(this)}" }
        bg.post(scan)
        bg.post(updateBars)
        // Алиса по кнопке руля (колбэк key_policy — в процессе, где бы он ни был поднят).
        com.dash.headunit.navi.WheelKeys.init(this)
        com.dash.headunit.location.GpsInject.init(this)
    }

    /**
     * Фильтр клавиш — только для пробы кнопок руля
     * ([com.dash.headunit.navi.WheelKeyProbe]): на HU кнопки руля сюда не
     * приходят, Алиса по кнопке слушает key_policy. Возможность объявлена
     * в XML (canRequestFilterKeyEvents), флаг ставится только на время пробы.
     */
    fun setKeyFilter(on: Boolean) {
        val info = serviceInfo ?: return
        val flag = android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        val want = if (on) info.flags or flag else info.flags and flag.inv()
        if (want == info.flags) return
        info.flags = want
        serviceInfo = info
    }

    /** Для пробы кнопок руля: включён ли фильтр клавиш на самом деле. */
    fun keyFilterState(): String {
        val info = serviceInfo ?: return "serviceInfo нет"
        val flag = (info.flags and android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) != 0
        val cap = (info.capabilities and android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS) != 0
        return "служба привязана, флаг фильтра клавиш=${if (flag) "да" else "НЕТ"}, " +
            "право фильтра (canRequestFilterKeyEvents)=${if (cap) "да" else "НЕТ"}, flags=0x${Integer.toHexString(info.flags)}"
    }

    override fun onKeyEvent(event: android.view.KeyEvent): Boolean =
        try {
            if (com.dash.headunit.navi.WheelKeyProbe.running) com.dash.headunit.navi.WheelKeyProbe.onA11yKey(event)
            false
        } catch (e: Exception) {
            Log.w("NaviA11y", "onKeyEvent", e)
            false
        }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) bg.post(updateBars)
        if (event?.packageName?.toString() != YANDEX_NAVI) return
        if (GuidanceHub.paused) return
        // Навигатор шлёт события пачками (карта перерисовывается) — не чаще
        // раза в SCAN_MS.
        if (scheduled) return
        scheduled = true
        val wait = (lastScan + SCAN_MS - SystemClock.uptimeMillis()).coerceAtLeast(0)
        bg.postDelayed(scan, wait)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        bg.removeCallbacks(scan)
        bg.removeCallbacks(poll)
        thread.quitSafely()
        scheduled = false
        if (instance === this) instance = null
        main.post { GuidanceHub.setFromA11y(null) }
        super.onDestroy()
    }

    /** В потоке [bg]; результат — в [GuidanceHub] через главный поток. */
    private fun scanNow() {
        lastScan = SystemClock.uptimeMillis()
        // Перечитываем и без событий: пока тексты навигатора не меняются
        // (стоим в пробке, навигатор под дашбордом), событий нет, а данные
        // в [GuidanceHub] через A11Y_FRESH_MS протухают — маршрут пропадал.
        bg.removeCallbacks(poll)
        // Навигатор на приборке — не сканируем и не опрашиваем; снова
        // запустит [resume].
        if (GuidanceHub.paused) return
        val root = naviRoot()
        if (root == null) {
            publish(null)
            // Окна навигатора нет — его появление и так придёт событием.
            bg.postDelayed(poll, NO_NAVI_POLL_MS)
            return
        }
        val g = try {
            read(root)
        } catch (e: Exception) {
            Log.w(TAG, "scan", e)
            null
        }
        publish(g)
        bg.postDelayed(poll, if (g != null) ROUTE_POLL_MS else IDLE_POLL_MS)
    }

    /** Чтение возобновили ([GuidanceHub.setPaused]) — запустить сканирование заново. */
    fun resume() {
        bg.removeCallbacks(scan)
        scheduled = false
        bg.post(scan)
    }

    /**
     * Нажать кнопку голоса (Алиса) навигатора — единственное, что служба
     * нажимает сама. Навигатор к этому моменту должен быть поднят наверх
     * ([com.dash.headunit.navi.NaviUnderlay.aliceSession]). Поиск — в потоке
     * [bg] (вызовы в процесс навигатора), [done] — в главном: что вышло.
     */
    /**
     * Нажать кнопку голоса Яндекса. Окно поднятого навигатора появляется
     * у службы не сразу (ГУ 2026-11-24: одна попытка через 600 мс — «кнопки
     * голоса нет», сессия обрывалась) — пробовать каждые [VOICE_RETRY_MS]
     * до [timeoutMs]. Не вышло — снимок окон и дерева в files/navi_tree
     * (…_alice.txt), путь в причине.
     */
    fun clickVoice(timeoutMs: Long = VOICE_FIND_MS, done: (Boolean, String) -> Unit) {
        val until = android.os.SystemClock.uptimeMillis() + timeoutMs
        var tries = 0
        val attempt = object : Runnable {
            override fun run() {
                tries++
                val (ok, why) = try {
                    clickVoiceNow()
                } catch (e: Exception) {
                    false to "${e.javaClass.simpleName}: ${e.message}"
                }
                if (!ok && android.os.SystemClock.uptimeMillis() < until) {
                    bg.postDelayed(this, VOICE_RETRY_MS)
                    return
                }
                // timeoutMs = 0 — просто проверка «навигатор уже наверху?», без снимка.
                val tail = if (ok || timeoutMs <= 0) "" else snapshotAlice()
                main.post { done(ok, "$why (попыток $tries)$tail") }
            }
        }
        bg.post(attempt)
    }

    /** Снимок при ненайденной кнопке голоса (поток [bg]); « → файл» или пусто. */
    private fun snapshotAlice(): String = try {
        " → " + com.dash.headunit.diag.NaviSnapshot.write(this, "alice").name
    } catch (e: Exception) {
        Log.w(TAG, "alice snapshot", e)
        ""
    }

    private fun clickVoiceNow(): Pair<Boolean, String> {
        val root = naviRoot() ?: return false to "окна навигатора нет"
        val nodes = root.findAccessibilityNodeInfosByViewId("$YANDEX_NAVI:id/$VOICE_ID")
            .ifEmpty { voiceByDescription(root) }
        val node = nodes.firstOrNull { it.isVisibleToUser } ?: nodes.firstOrNull()
            ?: return false to "кнопки голоса нет (ни #$VOICE_ID, ни описания «Алиса»)"
        // Сама картинка может быть не clickable — жмём ближайшего родителя.
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 4) {
            if (n.isClickable) {
                val ok = n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return ok to "ACTION_CLICK (уровень $depth, visible=${node.isVisibleToUser}) → $ok"
            }
            n = n.parent
            depth++
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return ok to "ACTION_CLICK по не-clickable узлу → $ok"
    }

    /** Другие версии навигатора: кнопка голоса без id, но с описанием. */
    private fun voiceByDescription(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val found = mutableListOf<AccessibilityNodeInfo>()
        var seen = 0
        fun walk(n: AccessibilityNodeInfo) {
            if (seen++ > MAX_DUMP_NODES) return
            val d = n.contentDescription?.toString()?.lowercase().orEmpty()
            if (VOICE_DESC.any { d.contains(it) }) found.add(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        walk(root)
        return found
    }

    /** Скан, закончившийся после onDestroy, не перетирает сброс. */
    private fun publish(g: Guidance?) {
        main.post { if (instance === this) GuidanceHub.setFromA11y(g) }
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
        // В блоке под дашбордом (ГУ 2026-10-31) кнопки карты с карточкой
        // манёвра и лимитом у Яндекса «не видны» (isVisibleToUser = false у
        // всей ветки от buttons_container), хотя тексты в них есть, а
        // уведомления ведения в это время нет. Поэтому видимый узел — если
        // есть, иначе любой.
        fun nodes(id: String): List<AccessibilityNodeInfo> =
            root.findAccessibilityNodeInfosByViewId("$YANDEX_NAVI:id/$id").orEmpty()

        fun visible(id: String): AccessibilityNodeInfo? = nodes(id).firstOrNull { it.isVisibleToUser }

        fun node(id: String): AccessibilityNodeInfo? = nodes(id).let { l -> l.firstOrNull { it.isVisibleToUser } ?: l.firstOrNull() }

        fun text(id: String): String? =
            node(id)?.let { it.text ?: it.contentDescription }?.toString()?.trim()?.takeIf { it.isNotEmpty() }

        // Каждый id — поиск по всему дереву навигатора (IPC в его главный
        // поток): сработавший раньше спрашиваем первым.
        val limitIds = limitId?.let { id -> listOf(id) + (SPEED_LIMIT_IDS - id) } ?: SPEED_LIMIT_IDS
        var limit: Int? = null
        for (id in limitIds) {
            limit = GuidanceText.speedLimit(text(id)) ?: continue
            limitId = id
            break
        }
        val dist = text("text_maneuverballoon_distance")
        val metrics = text("text_maneuverballoon_metrics")
        val arrow = node("image_maneuverballoon_maneuver")
        val arrowDesc = arrow?.contentDescription?.toString()
        val street = text("text_nextstreet")
        // Описания у стрелки нет — узнаём её по снимку экрана. В блоке под
        // дашбордом стрелка «не видна» (ГУ 2026-10-31), а уведомления нет —
        // других источников стрелки тогда нет вовсе (ГУ 2026-11-02: пустая
        // плитка). Поэтому снимаем и «невидимую», если у неё есть место на
        // экране, только реже: не нарисована — на снимке карта, и
        // [ManeuverMatcher] её не узнает (порог уверенности).
        val maneuver = GuidanceText.classify(arrowDesc) ?: arrow?.let {
            val r = android.graphics.Rect().also { b -> it.getBoundsInScreen(b) }
            val onScreen = !r.isEmpty && r.left >= 0 && r.top >= 0
            when {
                it.isVisibleToUser -> ManeuverGrab.maneuver(this, r, street, dist)
                onScreen -> ManeuverGrab.maneuver(this, r, street, dist, ManeuverGrab.HIDDEN_RETRY_MS)
                else -> ManeuverGrab.known(street)
            }
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
            overspeed = visible("view_speedlimit_alarm") != null,
            // Строка состояния внизу карты: без маршрута — улица, по которой едем.
            currentStreet = GuidanceText.currentStreet(text("text_statuspanel")),
        )
        return g.takeIf { it.onRoute || it.hasWarnings || it.currentStreet != null }
    }

    /**
     * Окна, которые система отдаёт службе (заголовок, пакет, границы, фокус,
     * класс корня — DecorView активности или диалога), — для диагностики.
     */
    fun describeWindows(): String = try {
        windows.joinToString("\n") { w ->
            val b = android.graphics.Rect().also { w.getBoundsInScreen(it) }
            val root = w.root
            "id=${w.id} type=${w.type} layer=${w.layer} ${w.title ?: "-"} " +
                "pkg=${root?.packageName ?: "?"} $b" +
                (if (w.isFocused) " focused" else "") + (if (w.isActive) " active" else "") +
                " root=${root?.className?.toString()?.substringAfterLast('.') ?: "?"}" +
                "/${root?.childCount ?: 0}"
        }.ifEmpty { "(пусто)" }
    } catch (e: Exception) {
        "ошибка: ${e.javaClass.simpleName}: ${e.message}"
    }

    /**
     * Поиск узлов по id (findAccessibilityNodeInfosByViewId) — находит и те,
     * что обход getChild не показывает; для диагностики.
     */
    fun probeIds(ids: List<String>): String {
        val root = naviRoot() ?: return "окна $YANDEX_NAVI нет среди окон"
        val sb = StringBuilder()
        for (id in ids) {
            val found = root.findAccessibilityNodeInfosByViewId("$YANDEX_NAVI:id/$id").orEmpty()
            for (n in found) {
                sb.append('#').append(id).append(' ').append(n.className?.toString()?.substringAfterLast('.'))
                n.text?.let { sb.append(" \"").append(it).append('"') }
                n.contentDescription?.let { sb.append(" desc=\"").append(it).append('"') }
                if (!n.isVisibleToUser) sb.append(" (не виден)")
                sb.append(' ').append(android.graphics.Rect().also { n.getBoundsInScreen(it) }.toShortString())
                sb.append(" дети=").append(n.childCount).append('\n')
            }
        }
        return sb.toString().ifEmpty { "(ничего)" }
    }

    /** Дерево окна навигатора текстом — для диагностики; [full] — границы у каждого узла. */
    fun dumpTree(full: Boolean = false): String {
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
            if (full || n.viewIdResourceName?.endsWith("_maneuver") == true) {
                sb.append(' ').append(android.graphics.Rect().also { n.getBoundsInScreen(it) }.toShortString())
            }
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
        /** Не чаще — скан по событиям (каждый — десяток IPC в навигатор). */
        private const val SCAN_MS = 1_000L
        /** Окно уже этого (px) по одной из сторон — полоса поверх экрана (климат 20, systemui 18). */
        private const val MAX_BAR_PX = 120
        /** Перечитывание окна без событий: идёт ведение (свежесть в GuidanceHub — 5 с) / стоит без маршрута. */
        private const val ROUTE_POLL_MS = 2_000L
        private const val IDLE_POLL_MS = 3_000L
        /** Окна навигатора нет вовсе. */
        private const val NO_NAVI_POLL_MS = 10_000L
        private const val MAX_DUMP_NODES = 600
        private const val TAG = "NaviA11y"
        /** Кнопка голоса на карте (a11y-дампы навигатора 2026-10-25/30). */
        private const val VOICE_ID = "voicebutton"
        private val VOICE_DESC = listOf("алис", "alice") // не «голос»: это и кнопка подсказок
        /** Сколько искать кнопку голоса после подъёма навигатора и как часто. */
        const val VOICE_FIND_MS = 5_000L
        private const val VOICE_RETRY_MS = 300L
    }
}
