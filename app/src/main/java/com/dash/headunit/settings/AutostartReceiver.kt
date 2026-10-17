package com.dash.headunit.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dash.headunit.MainActivity

/**
 * «Запускать при старте»: после загрузки ГУ открыть дашборд. На Android 9
 * (targetSdk 28) запуск activity из BOOT_COMPLETED ещё разрешён.
 */
class AutostartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!AppSettings.autostart(context)) return
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.w("AutostartReceiver", "start", e)
        }
    }
}
