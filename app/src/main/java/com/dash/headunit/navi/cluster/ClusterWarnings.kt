package com.dash.headunit.navi.cluster

import android.content.Context

/**
 * Ограничение скорости и камеры навигатора → штатный CameraInfo приборки
 * (CarLan 4100/4, [CarLanClient]). ISA через mapservice на этой ГУ закрыт
 * (part.number), а в TBT-канале 201 полей лимита нет — остаётся CarLan.
 *
 * SpeedingInfo — лимит камеры, иначе лимит участка (у TiggoBridge приборка
 * рисует его кольцом-знаком); WarningMessage [warnShow] при лимите, -1 без
 * него. Значения CameraType/WarningMessage на этой приборке не проверены —
 * подбирать adb NAVI_CLUSTER cmd carlan_cam.
 */
object ClusterWarnings {

    /** CameraType, как у SVMapService/TiggoBridge. */
    @Volatile var cameraType = 3
    /** WarningMessage при известном лимите (TiggoBridge: 1). */
    @Volatile var warnShow = 1

    private var last: String? = null

    /** CameraInfo для ведения [g] (null — маршрута нет). Чистая функция. */
    fun json(g: Guidance?): String {
        if (g == null) return CarLanClient.Json.cameraInfo(cameraType, NAVI_OFF, 0, -1)
        val limit = g.cameraLimit ?: g.speedLimit ?: 0
        return CarLanClient.Json.cameraInfo(cameraType, NAVI_ON, limit, if (limit > 0) warnShow else -1)
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

    private const val NAVI_ON = 3
    private const val NAVI_OFF = 4
}
