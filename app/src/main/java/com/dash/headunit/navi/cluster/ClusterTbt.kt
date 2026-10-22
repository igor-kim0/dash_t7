package com.dash.headunit.navi.cluster

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Родные подсказки манёвров приборки (TBT) — через com.desaysv.mapservice.
 *
 * На этой ГУ в mapservice активен адаптер TurboDog (MapAdapterFactory,
 * тип 4; AutoNavi-адаптер не создаётся — 10019/10001 игнорируются). Он
 * динамически, без прав и проверки отправителя, слушает
 * `turbodog.navigation.system.message` с int CODE и пересылает данные
 * через CarLan (тип 4100) на QNX приборки:
 *  - 202 + DATA=1 — ведение началось (CameraInfo NaviStatus=3);
 *  - 201 — манёвр: TURN_TYPE int (TurboDog, см. [turnType]), TURN_DIST int
 *    (м), NEXT_ROAD String, REMAINING_TIME int → RoadIcon/SegRemainDis/
 *    RoadName/TimeLeft; REMAINING_DIST до приборки не доходит;
 *  - 203 — ведение закончилось (NaviStatus=4).
 * Разобрано по SVMapService (TurboDogAdapter$3/$5, NaviUtil.formatTurboNaviArrow)
 * 2026-10-21; на ГУ не проверено — в каких видах приборка рисует TBT, неизвестно.
 * Выключено по умолчанию: вместе с 205→207 на ГУ «Карты» опустели
 * (2026-10-22) — включать для проверки adb NAVI_CLUSTER cmd tbt --ez on true.
 */
object ClusterTbt {

    /** Слать ли подсказки в mapservice (до перезапуска процесса). */
    @Volatile var enabled = false

    private var active = false
    private var last: Triple<Int, Int, String?>? = null

    /** Новое состояние ведения (главный поток). */
    fun update(ctx: Context, g: Guidance) {
        if (!g.onRoute) return
        if (!active) {
            active = true
            last = null
            send(ctx, Intent(ACTION).putExtra("CODE", 202).putExtra("DATA", 1))
        }
        val turn = turnType(g.maneuver)
        val dist = GuidanceText.meters(g.distance) ?: 0
        val key = Triple(turn, dist, g.street)
        if (key == last) return
        last = key
        val i = Intent(ACTION)
            .putExtra("CODE", 201)
            .putExtra("TURN_TYPE", turn)
            .putExtra("TURN_DIST", dist)
            .putExtra("NEXT_ROAD", g.street ?: "")
        GuidanceText.seconds(g.remainingTime)?.let { i.putExtra("REMAINING_TIME", it) }
        GuidanceText.meters(g.remainingDistance)?.let { i.putExtra("REMAINING_DIST", it) }
        send(ctx, i)
    }

    fun stop(ctx: Context) {
        if (!active) return
        active = false
        last = null
        send(ctx, Intent(ACTION).putExtra("CODE", 203))
    }

    /**
     * Наш манёвр → TURN_TYPE TurboDog (mapservice переводит в RoadIcon:
     * 0→9 прямо, 1→15 финиш, 2/3→8/19 разворот, 7/8→4/5 плавно,
     * 11/12→2/3 налево/направо, 13/14→6/7 резко, 16→25 кольцо).
     * Неизвестный — -1: вне таблицы, уходит как есть (надеемся, без стрелки).
     */
    fun turnType(m: Maneuver?): Int = when (m) {
        Maneuver.STRAIGHT -> 0
        Maneuver.FINISH -> 1
        Maneuver.UTURN_LEFT -> 2
        Maneuver.UTURN_RIGHT -> 3
        Maneuver.SLIGHT_LEFT, Maneuver.KEEP_LEFT -> 7
        Maneuver.SLIGHT_RIGHT, Maneuver.KEEP_RIGHT -> 8
        Maneuver.LEFT -> 11
        Maneuver.RIGHT -> 12
        Maneuver.SHARP_LEFT -> 13
        Maneuver.SHARP_RIGHT -> 14
        Maneuver.ROUNDABOUT -> 16
        null -> -1
    }

    private fun send(ctx: Context, i: Intent) {
        try {
            ctx.sendBroadcast(i)
            Log.i(TAG, "-> mapservice ${i.extras}")
        } catch (e: Exception) {
            Log.w(TAG, "send", e)
        }
    }

    private const val ACTION = "turbodog.navigation.system.message"
    private const val TAG = "ClusterTbt"
}
