package com.dash.headunit.navi.cluster

import android.content.Context

/**
 * Штатный CameraInfo приборки (CarLan 4100/4, [CarLanClient]) — всегда
 * «навигация выключена» (NaviStatus 4, без лимита).
 *
 * Раньше слали лимит с NaviStatus 3 (так приборка рисует свой знак, ГУ
 * 2026-10-26), но NaviStatus 3 включает и штатную строку подсказки
 * манёвра над «Картами»: без данных TurboDog там висело «0 м Войти» (ГУ
 * 2026-11-01). Свой знак лимита рисует карточка ([ClusterGuidanceView]),
 * а штатный в режиме «Карт» отдельно не виден — поэтому только сброс.
 */
object ClusterWarnings {

    private var last: String? = null

    /** CameraInfo «навигация выключена». Чистая функция. */
    fun json(): String = CarLanClient.Json.cameraInfo(CAMERA_TYPE, NAVI_OFF, 0, -1)

    /** Сбросить штатную навигацию приборки (главный поток); шлёт только раз после [reset]. */
    fun update(ctx: Context) {
        val j = json()
        if (j == last) return
        last = j
        CarLanClient.send(ctx, CarLanClient.SUB_CAMERA, j)
    }

    /** Забыть отправленное — следующий [update] уйдёт заново. */
    fun reset() {
        last = null
    }

    private const val CAMERA_TYPE = 3
    private const val NAVI_OFF = 4
}
