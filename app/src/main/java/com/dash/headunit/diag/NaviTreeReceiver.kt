package com.dash.headunit.diag

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dash.headunit.navi.cluster.GuidanceHub
import com.dash.headunit.navi.cluster.NaviAccessibilityService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Снимок дерева окна Яндекс Навигатора (через нашу службу спец. возможностей)
 * по команде с ПК — `uiautomator dump` с Яндексом не работает: карта никогда
 * не «простаивает» («could not get idle state»).
 *
 *   adb shell am broadcast -n com.dash.headunit/.diag.NaviTreeReceiver [--es tag fs] \
 *       [--es ids text_speedlimit,text_statuspanel]
 *
 * ids — ещё и поиск по id: так видны узлы, которых нет в обходе дерева.
 *
 * Файл: /sdcard/Android/data/com.dash.headunit/files/navi_tree/<время>_<tag>.txt,
 * путь — в resultData. Защищён правом DUMP (только adb shell/система).
 */
class NaviTreeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val tag = intent.getStringExtra("tag") ?: "tree"
        val ids = intent.getStringExtra("ids")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        // Чтение окна — синхронные вызовы в процесс навигатора, не в главном потоке.
        Thread {
            val a11y = NaviAccessibilityService.instance
            val text = buildString {
                append("-- источники\n").append(GuidanceHub.sourcesInfo()).append('\n')
                append("-- окна\n").append(a11y?.describeWindows() ?: "служба спец. возможностей не подключена").append('\n')
                if (ids.isNotEmpty()) append("-- по id\n").append(a11y?.probeIds(ids) ?: "-").append('\n')
                append("-- дерево\n").append(a11y?.dumpTree(full = true) ?: "-").append('\n')
            }
            val dir = File(context.getExternalFilesDir(null), "navi_tree").apply { mkdirs() }
            val name = SimpleDateFormat("HHmmss", Locale.US).format(Date()) + "_" + tag + ".txt"
            val f = File(dir, name).apply { writeText(text) }
            pending.resultData = f.absolutePath
            pending.finish()
        }.start()
    }
}
