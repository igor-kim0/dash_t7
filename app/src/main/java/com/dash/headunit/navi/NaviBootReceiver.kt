package com.dash.headunit.navi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * policy_control (и overscan старых версий) система хранит между
 * перезагрузками. Если приложение убили в режиме навигатора (обновление,
 * force-stop, нехватка памяти), после загрузки ГУ навигатор остался бы без
 * системных панелей — сбрасываем сразу.
 */
class NaviBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        Thread {
            try {
                // adbd поднимается не мгновенно — несколько попыток.
                repeat(ATTEMPTS) {
                    if (NaviUnderlay.resetNow(context)) return@Thread
                    Thread.sleep(RETRY_MS)
                }
            } finally {
                pending.finish()
            }
        }.start()
    }

    private companion object {
        const val ATTEMPTS = 3
        const val RETRY_MS = 2000L
    }
}
