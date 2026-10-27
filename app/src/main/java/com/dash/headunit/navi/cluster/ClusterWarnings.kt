package com.dash.headunit.navi.cluster

import android.content.Context

/**
 * Ограничение скорости и камеры навигатора → штатный CameraInfo приборки
 * (CarLan 4100/4, [CarLanClient]). ISA через mapservice на этой ГУ закрыт
 * (part.number), а в TBT-канале 201 полей лимита нет — остаётся CarLan.
 *
 * SpeedingInfo — лимит камеры, иначе лимит участка; приборка рисует его
 * знаком при CameraType 3 и WarningMessage 1 (ГУ 2026-10-26), без лимита
 * WarningMessage -1. Знак виден, пока поднята панель навигации (2+5).
 */
object ClusterWarnings {

    private var last: String? = null

    /** CameraInfo для ведения [g] (null — маршрута нет). Чистая функция. */
    fun json(g: Guidance?): String {
        if (g == null) return CarLanClient.Json.cameraInfo(CAMERA_TYPE, NAVI_OFF, 0, -1)
        val limit = g.cameraLimit ?: g.speedLimit ?: 0
        return CarLanClient.Json.cameraInfo(CAMERA_TYPE, NAVI_ON, limit, if (limit > 0) WARN_SHOW else -1)
    }

    /** Новое состояние ведения (главный поток); шлёт только изменения. */
    fun update(ctx: Context, g: Guidance?) {
        val j = json(g)
        if (j == last) return
        last = j
        CarLanClient.send(ctx, CarLanClient.SUB_CAMERA, j)
    }

    /** Забыть отправленное — следующий [update] уйдёт заново. */
    fun reset() {
        last = null
    }

    private const val CAMERA_TYPE = 3
    private const val WARN_SHOW = 1
    private const val NAVI_ON = 3
    private const val NAVI_OFF = 4
}
