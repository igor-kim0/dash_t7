package com.dash.headunit.navi.cluster

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Текущее ведение по маршруту Яндекс Навигатора. Два источника:
 *  - уведомление ведения ([YandexNotificationParser], через
 *    MediaListenerService) — стрелка, расстояние, улица, ETA;
 *  - служба спец. возможностей ([NaviAccessibilityService]) — ограничение
 *    скорости, камеры и запасные тексты манёвра, когда уведомления нет
 *    (навигатор на переднем плане).
 * Уведомление важнее; данные спец. возможностей считаются свежими
 * [A11Y_FRESH_MS]. Слушатели вызываются в главном потоке.
 */
object GuidanceHub {

    fun interface Listener {
        fun onGuidance(guidance: Guidance?)
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<Listener>()

    @Volatile private var fromNotification: Guidance? = null
    @Volatile private var fromA11y: Guidance? = null
    @Volatile private var a11yAt = 0L
    @Volatile private var demo: Guidance? = null

    /** Склеенное состояние; null — маршрута нет. */
    @Volatile
    var current: Guidance? = null
        private set

    /** Сырые разборы для диагностики (adb NAVI_CLUSTER dump). */
    @Volatile var lastNotificationDump: String = "уведомлений навигатора не было"
    @Volatile var lastA11yDump: String = "окно навигатора не читали"

    fun addListener(l: Listener) {
        main.post {
            listeners.add(l)
            l.onGuidance(current)
        }
    }

    fun removeListener(l: Listener) {
        main.post { listeners.remove(l) }
    }

    /** Уведомление ведения появилось/обновилось (null — снято). */
    fun setFromNotification(g: Guidance?) {
        fromNotification = g
        publish()
    }

    /** Свежие данные из окна навигатора (null — ведения на экране нет). */
    fun setFromA11y(g: Guidance?) {
        fromA11y = g
        a11yAt = SystemClock.uptimeMillis()
        publish()
        // Если окно перестанет присылать события — состояние протухнет.
        main.removeCallbacks(expire)
        if (g != null) main.postDelayed(expire, A11Y_FRESH_MS + 100)
    }

    /** Демо-маршрут для проверки картинки на приборке (null — выключить). */
    fun setDemo(g: Guidance?) {
        demo = g
        publish()
    }

    private val expire = Runnable { publish() }

    private fun publish() {
        val a11y = fromA11y?.takeIf { SystemClock.uptimeMillis() - a11yAt < A11Y_FRESH_MS }
        val notif = fromNotification
        val merged = demo ?: when {
            notif != null -> notif.orElse(a11y)
            a11y != null && a11y.hasManeuver -> a11y
            else -> null
        }
        current = merged
        main.post { listeners.toList().forEach { it.onGuidance(merged) } }
    }

    private const val A11Y_FRESH_MS = 5_000L
}
