package com.dash.headunit.music

import android.service.notification.NotificationListenerService

/**
 * Пустой NotificationListenerService — только он даёт право читать
 * активные media-сессии (в т.ч. Bluetooth AVRCP) через MediaSessionManager.
 * Пользователь включает доступ в Настройки → Уведомления → Доступ к уведомлениям.
 */
class MediaListenerService : NotificationListenerService()
