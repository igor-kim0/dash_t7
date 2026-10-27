package com.dash.headunit.music

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

    /** Ключ уведомления, в котором сейчас ведение по маршруту. */
    private var guidanceKey: String? = null

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
        guidanceKey = null
        GuidanceHub.setFromNotification(null)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName != YANDEX_NAVI) return
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
        if (sbn != null && sbn.key == guidanceKey) {
            guidanceKey = null
            GuidanceHub.setFromNotification(null)
        }
    }

    private companion object {
        const val TAG = "MediaListener"
    }
}
