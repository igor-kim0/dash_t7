package com.dash.headunit.diag

import android.content.Context
import com.dash.headunit.navi.cluster.ClusterOverlay
import com.dash.headunit.navi.cluster.GuidanceHub
import com.dash.headunit.navi.cluster.NaviAccessibilityService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Снимок ведения для диагностики приборки: откуда сейчас данные
 * ([GuidanceHub.sourcesInfo]), состояние [ClusterOverlay], окна, которые
 * видит служба спец. возможностей, и дерево окна Яндекс Навигатора.
 *
 * Файл: /sdcard/Android/data/com.dash.headunit/files/navi_tree/<время>_<tag>.txt.
 * Звать не из главного потока: чтение окна — синхронные вызовы в навигатор.
 */
object NaviSnapshot {

    fun write(context: Context, tag: String, ids: List<String> = emptyList()): File {
        val a11y = NaviAccessibilityService.instance
        val text = buildString {
            append("-- источники\n").append(GuidanceHub.sourcesInfo()).append('\n')
            append("чтение на паузе: ").append(GuidanceHub.paused).append('\n')
            append("-- приборка\n").append(ClusterOverlay.debugState()).append('\n')
            append("-- окна\n").append(a11y?.describeWindows() ?: "служба спец. возможностей не подключена").append('\n')
            if (ids.isNotEmpty()) append("-- по id\n").append(a11y?.probeIds(ids) ?: "-").append('\n')
            append("-- дерево\n").append(a11y?.dumpTree(full = true) ?: "-").append('\n')
        }
        val dir = File(context.getExternalFilesDir(null), "navi_tree").apply { mkdirs() }
        val name = SimpleDateFormat("HHmmss", Locale.US).format(Date()) + "_" + tag + ".txt"
        return File(dir, name).apply { writeText(text) }
    }
}
