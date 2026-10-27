package com.dash.headunit.navi.cluster

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * Прямые сообщения приборке через com.desaysv.carlan/.CarLanService.
 *
 * Служба экспортирована (action com.desaysv.carlan.ICarLan) без
 * android:permission, а LanCommonService.writeMessage не проверяет
 * вызывающего (разбор 2026-10-25). Так же шлёт и SVMapService, но
 * DisplayCluster:true — только при TurboDog на переднем плане, поэтому
 * видео дисплея 1 в «Картах» приборки само не включается.
 *
 * Протокол (как TiggoBridge CarLanClient, ГУ Desay той же серии):
 *  - ICarLan ("desaysv.carlan.ICarLan"), транзакция 2 —
 *    getCarLanService(String "common") → IBinder ICommon;
 *  - ICommon ("desaysv.carlan.ICommon"), транзакция 3 —
 *    writeMessage(int type, CommonMessage) → int; CommonMessage в Parcel:
 *    1 (не null), subtype, String json, -1977 (хвост, как у TiggoBridge).
 * Тип 4100 — навигация: подтип 2 — видео/статус ([Json.displayCluster]),
 * 5 — область навигации ([Json.naviArea]), 4 — CameraInfo
 * ([Json.cameraInfo]); у TiggoBridge панель карты появлялась только при
 * всех трёх (2+5+4).
 *
 * Транзакции — в своём потоке. Пока служба не подключена, хранится
 * последнее сообщение каждого подтипа и уходит после подключения.
 */
@SuppressLint("StaticFieldLeak") // только applicationContext
object CarLanClient {

    /**
     * Слать ли в CarLan из маршрута. На ГУ 2026-10-26 подтверждены видео
     * (2+5) и знак лимита (4) — включено; выключить для проверки до
     * перезапуска процесса: adb NAVI_CLUSTER cmd carlan_on --ez on false.
     */
    @Volatile var enabled = true

    private val thread = HandlerThread("CarLan").apply { start() }
    private val worker = Handler(thread.looper)

    private var app: Context? = null
    private var bound = false
    private var common: IBinder? = null
    private val pending = LinkedHashMap<Pair<Int, Int>, String>()

    private val history = mutableListOf<String>()

    /** Последние отправки и ответы — для adb NAVI_CLUSTER dump. */
    fun history(): List<String> = synchronized(history) { history.toList() }

    private fun note(s: String) {
        Log.i(TAG, s)
        synchronized(history) {
            history.add("${System.currentTimeMillis()} $s")
            while (history.size > HISTORY_MAX) history.removeAt(0)
        }
    }

    /** Отправить [json] с типом [type] и подтипом [subtype] (асинхронно). */
    fun send(context: Context, subtype: Int, json: String, type: Int = TYPE_NAVI) {
        val ctx = context.applicationContext
        worker.post {
            app = ctx
            val c = common
            if (c == null) {
                pending[type to subtype] = json
                connect(ctx)
                return@post
            }
            write(c, type, subtype, json)
        }
    }

    /** Видео дисплея 1 в «Картах» приборки: вкл — 2+5, выкл — 2+5 с нулями. */
    fun showNaviArea(context: Context, on: Boolean) {
        send(context, SUB_DISPLAY, Json.displayCluster(on))
        send(context, SUB_AREA, Json.naviArea(if (on) 1 else 0))
    }

    private fun write(c: IBinder, type: Int, subtype: Int, json: String) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ICOMMON)
            data.writeInt(type)
            data.writeInt(1)
            data.writeInt(subtype)
            data.writeString(json)
            data.writeInt(-1977)
            c.transact(TX_WRITE_MESSAGE, data, reply, 0)
            reply.readException()
            note("-> $type/$subtype $json = ${reply.readInt()}")
        } catch (e: Exception) {
            note("-> $type/$subtype ошибка ${e.javaClass.simpleName}: ${e.message}")
            if (!c.isBinderAlive) {
                common = null
                pending[type to subtype] = json
            }
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun connect(ctx: Context) {
        if (bound) return
        val intent = Intent(ACTION).setPackage(PACKAGE)
        bound = try {
            ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            note("bindService: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
        if (!bound) note("CarLanService не привязывается (нет $PACKAGE?)")
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            worker.post {
                common = try {
                    getCommon(service)
                } catch (e: Exception) {
                    note("getCarLanService(common): ${e.javaClass.simpleName}: ${e.message}")
                    null
                }
                val c = common ?: return@post
                note("CarLan подключён")
                try {
                    c.linkToDeath(death, 0)
                } catch (e: Exception) {
                    Log.w(TAG, "linkToDeath", e)
                }
                val todo = pending.toList()
                pending.clear()
                todo.forEach { (k, json) -> write(c, k.first, k.second, json) }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            worker.post {
                note("CarLan отключился")
                common = null
            }
        }
    }

    private val death = IBinder.DeathRecipient {
        worker.post {
            note("CarLan умер")
            common = null
        }
    }

    private fun getCommon(service: IBinder): IBinder? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ICARLAN)
            data.writeString("common")
            service.transact(TX_GET_SERVICE, data, reply, 0)
            reply.readException()
            return reply.readStrongBinder()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** JSON сообщений 4100 (чистые функции — покрыты тестами). */
    object Json {
        /** Подтип 2: видео/статус навигации на приборке. */
        fun displayCluster(on: Boolean): String =
            "{\"DisplayCluster\":\"$on\",\"NaviFrontDeskStatus\":\"$on\",\"Perspective\":0," +
                "\"PerspectiveResult\":\"false\",\"RequestDisplayNaviArea\":\"false\"}"

        /** Подтип 5: область навигации (1 — показать, 0 — убрать). */
        fun naviArea(area: Int): String =
            "{\"NaviDisplayArea\":$area,\"NaviDisplayAreaResult\":\"true\"}"

        /**
         * Подтип 4: CameraInfo. [naviStatus] 3 — ведение, 4 — нет;
         * [speeding] — ограничение км/ч (у TiggoBridge рисуется кольцом-знаком),
         * 0 — нет; [warning] -1 — без предупреждения.
         */
        fun cameraInfo(cameraType: Int, naviStatus: Int, speeding: Int, warning: Int): String =
            "{\"CameraType\":$cameraType,\"NaviStatus\":$naviStatus," +
                "\"SpeedingInfo\":$speeding,\"WarningMessage\":$warning}"
    }

    const val TYPE_NAVI = 4100
    const val SUB_DISPLAY = 2
    const val SUB_CAMERA = 4
    const val SUB_AREA = 5

    private const val ACTION = "com.desaysv.carlan.ICarLan"
    private const val PACKAGE = "com.desaysv.carlan"
    private const val ICARLAN = "desaysv.carlan.ICarLan"
    private const val ICOMMON = "desaysv.carlan.ICommon"
    private const val TX_GET_SERVICE = IBinder.FIRST_CALL_TRANSACTION + 1
    private const val TX_WRITE_MESSAGE = IBinder.FIRST_CALL_TRANSACTION + 2
    private const val HISTORY_MAX = 40
    private const val TAG = "CarLan"
}
