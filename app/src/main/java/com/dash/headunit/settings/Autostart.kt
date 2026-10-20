package com.dash.headunit.settings

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.dash.headunit.MainActivity

/**
 * «Запускать при старте ГУ».
 *
 * BOOT_COMPLETED на ГУ Desay до нас не доходит: в прошивке белый список
 * статических приёмников (logcat: «BroadcastQueue: Skip static receiver,
 * white list not found package name» — так же режется Jarvis). Зато
 * система сама привязывает при загрузке включённые службы — слушатель
 * уведомлений (MediaListenerService) и службу спец. возможностей
 * (NaviAccessibilityService); Jarvis поднимается так же. Из них и
 * открываем дашборд — один раз за загрузку.
 */
object Autostart {

    private val main = Handler(Looper.getMainLooper())
    private var scheduled = false

    /** Служба привязана системой: если это свежая загрузка — открыть дашборд. */
    fun onServiceBound(context: Context, source: String) {
        val app = context.applicationContext
        main.post {
            if (scheduled || !AppSettings.autostart(app)) return@post
            val boot = bootId(app)
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getString(KEY_BOOT, null) == boot) return@post
            val uptime = SystemClock.elapsedRealtime()
            if (uptime > MAX_UPTIME_MS) {
                // Службу перепривязали посреди работы (обновление, включение
                // доступа) — это не загрузка; загрузку считаем обработанной.
                prefs.edit().putString(KEY_BOOT, boot).apply()
                return@post
            }
            scheduled = true
            // Даём штатному лаунчеру подняться, иначе он перекроет дашборд.
            val delay = (LAUNCH_AT_UPTIME_MS - uptime).coerceAtLeast(MIN_DELAY_MS)
            Log.i(TAG, "загрузка ($source, аптайм ${uptime / 1000} с) — дашборд через ${delay / 1000} с")
            main.postDelayed({
                prefs.edit().putString(KEY_BOOT, boot).apply()
                launch(app, source)
            }, delay)
        }
    }

    /** Запуск из BOOT_COMPLETED (на прошивках, где он доходит). */
    fun onBootCompleted(context: Context) = onServiceBound(context, "BOOT_COMPLETED")

    private fun launch(context: Context, source: String) {
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Log.i(TAG, "дашборд открыт ($source)")
        } catch (e: Exception) {
            Log.w(TAG, "start", e)
        }
    }

    /** Номер загрузки; без него — момент загрузки с точностью до минуты. */
    private fun bootId(context: Context): String {
        val count = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        } catch (e: Exception) {
            -1
        }
        if (count >= 0) return "n$count"
        val bootAt = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        return "t${bootAt / 60_000}"
    }

    private const val PREFS = "autostart"
    private const val KEY_BOOT = "last_boot"
    /** Позже этого после загрузки привязка службы — уже не загрузка. */
    private const val MAX_UPTIME_MS = 5 * 60_000L
    /** Штатный лаунчер поднимается ~на 20-й секунде — открываемся после. */
    private const val LAUNCH_AT_UPTIME_MS = 30_000L
    private const val MIN_DELAY_MS = 2_000L
    private const val TAG = "Autostart"
}
