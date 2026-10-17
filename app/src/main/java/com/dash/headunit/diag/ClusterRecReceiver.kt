package com.dash.headunit.diag

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dash.headunit.car.ClusterRecorder

/**
 * Запись приборки ([ClusterRecorder]) по команде с ПК:
 *
 *   adb shell am broadcast -a com.dash.headunit.CLUSTER_REC \
 *       -n com.dash.headunit/.diag.ClusterRecReceiver --es cmd start --ei minutes 30
 *   … --es cmd mark --es text "ОЖ 65"
 *   … --es cmd stop
 *
 * Файл: /sdcard/Android/data/com.dash.headunit/files/cluster/. Защищён
 * правом DUMP (как [CarSnapshotReceiver]) — только adb shell/система.
 */
class ClusterRecReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getStringExtra("cmd")) {
            "start" -> ClusterRecorder.start(
                context, intent.getIntExtra("minutes", ClusterRecorder.DEFAULT_MINUTES)
            )
            "stop" -> ClusterRecorder.stop()
            "mark" -> ClusterRecorder.mark(intent.getStringExtra("text") ?: "метка")
        }
        resultData = "${ClusterRecorder.status} ${ClusterRecorder.file?.absolutePath ?: ""}"
    }
}
