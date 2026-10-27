package com.dash.headunit.navi.cluster

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dash.headunit.settings.AppSettings

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
 * 2026-10-21. На ГУ 2026-10-25: приборка рисует это в верхней строке поверх
 * любого вида (стрелка, расстояние, улица), от любого отправителя.
 *
 * Основной путь режима «родной интерфейс» ([ClusterOverlay]). В режиме
 * своего окна — только по [enabled]: вместе с 205→207 на ГУ «Карты»
 * опустели (2026-10-22); adb NAVI_CLUSTER cmd tbt --ez on true.
 */
object ClusterTbt {

    /** Режим своего окна: слать ли и туда подсказки (до перезапуска процесса). */
    @Volatile var enabled = false

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var active = false
    /** Последний 201 — для повтора (вариант 5). */
    private var lastIntent: Intent? = null
    private var last: Triple<Int, Int, String?>? = null
    /** Последний узнанный манёвр и его улица — пока стрелку не узнали заново. */
    private var known: Pair<Int, String?>? = null

    /** Новое состояние ведения (главный поток). */
    fun update(ctx: Context, g: Guidance) {
        if (!g.onRoute) return
        app = ctx.applicationContext
        val v = variant(ctx)
        if (!active) {
            active = true
            last = null
            send(ctx, Intent(ACTION).putExtra("CODE", 202).putExtra("DATA", 1))
        }
        val turn = if (v.unknownAsMinusOne) turnType(g.maneuver) else sendTurn(g)
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
        if (v.keepAlive) send(ctx, Intent(ACTION).putExtra("CODE", 202).putExtra("DATA", 1))
        send(ctx, i)
        lastIntent = i
        main.removeCallbacks(keepAlive)
        if (v.keepAlive) main.postDelayed(keepAlive, KEEP_ALIVE_MS)
    }

    /** Вариант 5: 202 и последний 201 заново, даже если ничего не поменялось. */
    private val keepAlive = object : Runnable {
        override fun run() {
            val ctx = app ?: return
            val i = lastIntent ?: return
            if (!active || !variant(ctx).keepAlive) return
            send(ctx, Intent(ACTION).putExtra("CODE", 202).putExtra("DATA", 1))
            send(ctx, Intent(i))
            main.postDelayed(this, KEEP_ALIVE_MS)
        }
    }

    /**
     * Вариант для подбора на ГУ ([AppSettings.clusterVariant]): ведение
     * работало, только пока Яндекс в фоне; на экране отличаются тип манёвра
     * (неизвестен) и знак лимита (есть только с экрана) — варианты разделяют
     * эти причины.
     *  1 — неизвестный манёвр как -1, знак лимита шлём (как было);
     *  2 — неизвестный → последний узнанный / «прямо», знак лимита шлём;
     *  3 — -1, без знака лимита;
     *  4 — «прямо», без знака лимита;
     *  5 — «прямо», знак лимита, 202 перед каждым 201 и повтор раз в 2 с.
     */
    class Variant(val unknownAsMinusOne: Boolean, val limit: Boolean, val keepAlive: Boolean)

    fun variant(ctx: Context): Variant = when (AppSettings.clusterVariant(ctx)) {
        1 -> Variant(unknownAsMinusOne = true, limit = true, keepAlive = false)
        3 -> Variant(unknownAsMinusOne = true, limit = false, keepAlive = false)
        4 -> Variant(unknownAsMinusOne = false, limit = false, keepAlive = false)
        5 -> Variant(unknownAsMinusOne = false, limit = true, keepAlive = true)
        else -> Variant(unknownAsMinusOne = false, limit = true, keepAlive = false)
    }

    /** Идёт ли ведение на приборке (после 202). */
    val isActive: Boolean get() = active

    fun stop(ctx: Context) {
        if (!active) return
        active = false
        last = null
        known = null
        lastIntent = null
        main.removeCallbacks(keepAlive)
        send(ctx, Intent(ACTION).putExtra("CODE", 203))
    }

    /**
     * Наш манёвр → TURN_TYPE TurboDog (mapservice переводит в RoadIcon:
     * 0→9 прямо, 1→15 финиш, 2/3→8/19 разворот, 7/8→4/5 плавно,
     * 11/12→2/3 налево/направо, 13/14→6/7 резко, 16→25 кольцо).
     * Неизвестный — [UNKNOWN] (-1, вне таблицы; в 201 не уходит, см. [sendTurn]).
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
        null -> UNKNOWN
    }

    /**
     * TURN_TYPE для 201. Тип манёвра неизвестен, когда Яндекс на экране
     * (уведомления со стрелкой нет, стрелку узнаём по снимку экрана — со
     * следующего опроса или вовсе нет). С -1, похоже, приборка не рисует: на ГУ
     * подсказки были, только пока Яндекс в фоне. Поэтому вместо -1 —
     * последний узнанный манёвр той же улицы, иначе «прямо»: стрелка
     * может быть неточной, но расстояние и улица на приборке остаются.
     */
    private fun sendTurn(g: Guidance): Int {
        val t = turnType(g.maneuver)
        if (t != UNKNOWN) {
            known = t to g.street
            return t
        }
        return known?.takeIf { it.second == g.street }?.first ?: turnType(Maneuver.STRAIGHT)
    }

    private fun send(ctx: Context, i: Intent) {
        try {
            ctx.sendBroadcast(i)
            Log.i(TAG, "-> mapservice ${i.extras}")
        } catch (e: Exception) {
            Log.w(TAG, "send", e)
        }
    }

    private const val UNKNOWN = -1
    private const val KEEP_ALIVE_MS = 2_000L
    private const val ACTION = "turbodog.navigation.system.message"
    private const val TAG = "ClusterTbt"
}
