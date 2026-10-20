package com.dash.headunit.navi.cluster

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.dash.headunit.music.MediaListenerService
import com.dash.headunit.navi.AdbShell

/**
 * Права для маршрута на приборке — выдаём сами через свой adb-канал
 * ([AdbShell], uid shell), на ГУ нет экранов Настроек для них:
 *  - оверлей (appops SYSTEM_ALERT_WINDOW) — окно на дисплее приборки;
 *  - служба спец. возможностей [NaviAccessibilityService] — ограничения скорости;
 *  - доступ к уведомлениям [MediaListenerService] — стрелки и расстояния.
 * Все вызовы блокирующие — только из фонового потока.
 */
object ClusterSetup {

    /** Выдать всё, что ещё не выдано; возвращает отчёт по пунктам. */
    fun ensureAll(context: Context): String {
        val ctx = context.applicationContext
        val out = mutableListOf<String>()
        out += "оверлей: " + if (Settings.canDrawOverlays(ctx)) "есть" else grantOverlay(ctx)
        out += "спец. возможности: " + if (isA11yEnabled(ctx)) "есть" else enableA11y(ctx)
        out += "уведомления: " + if (isListenerEnabled(ctx)) "есть" else enableListener(ctx)
        return out.joinToString("; ")
    }

    fun grantOverlay(context: Context): String =
        shell(context, "appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")

    fun isA11yEnabled(context: Context): Boolean =
        enabledA11y(context).any { ComponentName.unflattenFromString(it) == a11yComponent(context) }

    fun enableA11y(context: Context): String {
        val mine = a11yComponent(context).flattenToString()
        val list = (enabledA11y(context) + mine).distinct().joinToString(":")
        return shell(
            context,
            "settings put secure enabled_accessibility_services '$list' && " +
                "settings put secure accessibility_enabled 1"
        )
    }

    fun isListenerEnabled(context: Context): Boolean =
        Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?.split(':')?.any { ComponentName.unflattenFromString(it) == listenerComponent(context) } == true

    fun enableListener(context: Context): String =
        shell(context, "cmd notification allow_listener ${listenerComponent(context).flattenToString()}")

    private fun enabledA11y(context: Context): List<String> =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.filter { it.isNotBlank() } ?: emptyList()

    private fun a11yComponent(context: Context) =
        ComponentName(context, NaviAccessibilityService::class.java)

    private fun listenerComponent(context: Context) =
        ComponentName(context, MediaListenerService::class.java)

    private fun shell(context: Context, cmd: String): String {
        val r = AdbShell.exec(context, cmd) ?: return "нет adb-канала (${AdbShell.lastStatus})"
        return r.trim().ifEmpty { "выдано" }
    }
}
