package com.dash.headunit.diag

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dash.headunit.car.DesayCarReader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Срез шины Desay SV по команде с ПК (скрипт tools/hu_dump.sh):
 *
 *   adb shell am broadcast -a com.dash.headunit.CAR_SNAPSHOT \
 *       -n com.dash.headunit/.diag.CarSnapshotReceiver --es label engine_idle
 *
 * Читает transact(3, group, id) для всех group 0..31, id 0..255 и пишет
 * ВСЕ ответы (и нулевые — для сравнения срезов) в
 * /sdcard/Android/data/com.dash.headunit/files/snapshots/<label>.txt.
 * По окончании создаётся <label>.done — скрипт ждёт его.
 *
 * Только чтение. Приёмник защищён правом DUMP: отправить может лишь
 * adb shell/система, обычные приложения — нет.
 */
class CarSnapshotReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val label = (intent.getStringExtra(EXTRA_LABEL) ?: "snapshot")
            .replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                write(app, label)
            } catch (e: Throwable) {
                Log.w(TAG, "snapshot $label", e)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun write(context: Context, label: String) {
        val dir = File(context.getExternalFilesDir(null), "snapshots").apply { mkdirs() }
        val out = File(dir, "$label.txt")
        File(dir, "$label.done").delete()
        val reader = DesayCarReader(context)
        val started = System.currentTimeMillis()
        val sb = StringBuilder()
        sb.append("# label=").append(label).append('\n')
        sb.append("# time=")
            .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date(started)))
            .append('\n')
        sb.append("# desay service: ").append(if (reader.isAvailable()) "есть" else "НЕТ").append('\n')
        sb.append("# формат: group,id: байты (0..255)\n")
        if (!reader.connect()) {
            sb.append("# НЕ ПОДКЛЮЧИЛСЯ к CarInfoService — срез пустой\n")
            out.writeText(sb.toString())
            File(dir, "$label.done").writeText("no-service\n")
            return
        }
        var answered = 0
        var nulls = 0
        for (group in 0 until GROUPS) {
            for (id in 0 until IDS) {
                val raw = reader.readRaw(group, id, logErrors = false)
                if (raw == null) {
                    nulls++
                    continue
                }
                answered++
                sb.append(group).append(',').append(id).append(": ")
                    .append(raw.joinToString(",") { (it and 0xFF).toString() })
                    .append('\n')
            }
        }
        sb.append("# ответов=").append(answered).append(" null=").append(nulls)
            .append(" время=").append(System.currentTimeMillis() - started).append(" мс\n")
        out.writeText(sb.toString())
        File(dir, "$label.done").writeText("ok\n")
        Log.i(TAG, "snapshot $label: ответов=$answered null=$nulls")
    }

    companion object {
        private const val TAG = "CarSnapshot"
        private const val EXTRA_LABEL = "label"
        private const val GROUPS = 32
        private const val IDS = 256
    }
}
