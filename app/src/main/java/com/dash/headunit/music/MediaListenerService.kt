package com.dash.headunit.music

import android.os.Handler
import android.os.HandlerThread
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.dash.headunit.navi.cluster.ClusterOverlay
import com.dash.headunit.navi.cluster.ClusterSetup
import com.dash.headunit.navi.cluster.GuidanceHub
import com.dash.headunit.navi.cluster.YANDEX_NAVI
import com.dash.headunit.navi.cluster.YandexNotificationParser
import com.dash.headunit.settings.Autostart

/**
 * NotificationListenerService — даёт право читать активные media-сессии
 * (в т.ч. Bluetooth AVRCP) через MediaSessionManager.
 * Пользователь включает доступ в Настройки → Уведомления → Доступ к уведомлениям.
 *
 * Заодно читает уведомление ведения Яндекс Навигатора — маршрут для
 * приборки ([com.dash.headunit.navi.cluster.ClusterOverlay]).
 */
class MediaListenerService : NotificationListenerService() {

    /** Ключ уведомления, в котором сейчас ведение по маршруту (поток [bg]). */
    private var guidanceKey: String? = null

    /**
     * Разбор ведения — не в главном потоке (там рисуется дашборд): раздувание
     * чужих layout'ов и рендер стрелки занимали десятки мс ~раз в секунду.
     * HandlerThread — у потока есть Looper (нужен части view).
     */
    private val thread = HandlerThread("NaviNotif").apply { start() }
    private val bg = Handler(thread.looper)

    /** Уведомления навигатора, ждущие разбора: по ключу только последнее. */
    private val pending = LinkedHashMap<String, StatusBarNotification>()
    private val drain = Runnable {
        while (true) {
            val sbn = synchronized(pending) {
                val it = pending.values.iterator()
                if (!it.hasNext()) return@Runnable
                it.next().also { _ -> it.remove() }
            }
            parse(sbn)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ClusterOverlay.init(this)
        // При загрузке система сама привязывает слушатель — заодно
        // возвращаем сброшенные права для маршрута на приборке.
        ClusterSetup.restoreOnStart(this)
        // Система привязывает слушатель при загрузке — это и есть автозапуск
        // (BOOT_COMPLETED на ГУ Desay режется белым списком).
        Autostart.onServiceBound(this, "слушатель уведомлений")
    }

    override fun onListenerConnected() {
        try {
            activeNotifications?.forEach { onNotificationPosted(it) }
        } catch (e: Exception) {
            Log.w(TAG, "activeNotifications", e)
        }
    }

    override fun onListenerDisconnected() {
        synchronized(pending) { pending.clear() }
        bg.post {
            guidanceKey = null
            GuidanceHub.setFromNotification(null)
        }
    }

    override fun onDestroy() {
        thread.quitSafely()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName != YANDEX_NAVI) return
        // Навигатор сам на приборке / маршрут на приборке выключен — не разбираем.
        if (GuidanceHub.paused) return
        val first = synchronized(pending) {
            val was = pending.isEmpty()
            pending[sbn.key] = sbn
            was
        }
        if (first) bg.post(drain)
    }

    /** Поток [bg]. */
    private fun parse(sbn: StatusBarNotification) {
        if (GuidanceHub.paused) return
        val r = YandexNotificationParser.parse(this, sbn)
        if (r.guidance != null) {
            guidanceKey = sbn.key
            GuidanceHub.setFromNotification(r.guidance)
        } else if (sbn.key == guidanceKey) {
            guidanceKey = null
            GuidanceHub.setFromNotification(null)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName != YANDEX_NAVI) return
        synchronized(pending) { pending.remove(sbn.key) }
        bg.post {
            if (sbn.key == guidanceKey) {
                guidanceKey = null
                GuidanceHub.setFromNotification(null)
            }
        }
    }

    private companion object {
        const val TAG = "MediaListener"
    }
}
