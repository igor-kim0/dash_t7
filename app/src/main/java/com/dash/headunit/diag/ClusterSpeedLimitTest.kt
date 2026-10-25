package com.dash.headunit.diag

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import android.widget.Toast
import com.dash.headunit.navi.AdbShell
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Проверка «покажет ли приборка знак ограничения скорости, если послать его
 * штатным путём навигатора» (не через окно в режиме «Карты»). Кнопка в
 * CarDebugActivity, без ПК.
 *
 * Пути найдены в SVMeterInteraction (logs_1111111/dex), на ГУ не проверены:
 *  А. SOME/IP: служба по action [SOMEIP_ACTION], интерфейс [SOMEIP_DESCRIPTOR],
 *     notifyCurrentRouteInformation = транзакция 9, CurrentRouteInformationStruct
 *     с полями speedLimitToggle/speedLimit (порядок полей — из writeToParcel).
 *  Б. ClusterManager: broadcast [CLUSTER_ACTION], cluster_cmd = 420
 *     (ACU_NAVI_SPEED_LIMIT), cluster_bundle {sender, state, integer}.
 * Каждый путь шлётся [PHASE_SEC] с раз в секунду, перед ним — тост, чтобы
 * было видно, какой сработал. Отчёт (хосты, ответы, logcat) — files/navi/sla_<время>.txt.
 */
object ClusterSpeedLimitTest {

    @Volatile
    var running = false
        private set

    fun start(context: Context, limit: Int = DEFAULT_LIMIT) {
        if (running) return
        running = true
        val app = context.applicationContext
        Thread {
            val verdict = try {
                run(app, limit)
            } catch (e: Throwable) {
                Log.w(TAG, "sla test", e)
                "Знак на приборку: ошибка ${e.javaClass.simpleName}: ${e.message}"
            } finally {
                running = false
            }
            Log.i(TAG, verdict)
            toast(app, verdict)
        }.start()
    }

    private fun run(app: Context, limit: Int): String {
        val sb = StringBuilder()
        sb.append("# sla test ").append(STAMP.format(Date())).append(", limit=").append(limit).append('\n')

        val someip = runSomeIp(app, limit, sb)
        val broadcast = runBroadcast(app, limit, sb)

        sb.append("\n== dumpsys: кто слушает ").append(CLUSTER_ACTION).append('\n')
            .append(shell(app, "dumpsys activity broadcasts | grep -B2 -A6 cluster.music")).append('\n')
        sb.append("\n== logcat (ClusterManager/SOME-IP/навигация)\n")
            .append(shell(app, "logcat -d -t 400 | grep -i -E 'ClusterManager|someip|Navigation|speedlimit|cluster_cmd'"))
            .append('\n')

        val file = save(app, sb)
        return "Знак на приборку: А (SOME/IP) — $someip; Б (broadcast 420) — $broadcast. " +
            "Скажите, появился ли знак и на каком этапе. Отчёт: ${file.name}"
    }

    /** Путь А. Возвращает краткий итог для тоста. */
    private fun runSomeIp(app: Context, limit: Int, sb: StringBuilder): String {
        sb.append("\n== А. SOME/IP ").append(SOMEIP_ACTION).append('\n')
        val hosts = app.packageManager.queryIntentServices(Intent(SOMEIP_ACTION), 0)
        sb.append("службы: ").append(
            hosts.joinToString { "${it.serviceInfo.packageName}/${it.serviceInfo.name} " +
                "exported=${it.serviceInfo.exported} perm=${it.serviceInfo.permission}" }
                .ifEmpty { "не найдены" }
        ).append('\n')
        val host = hosts.firstOrNull()?.serviceInfo ?: return "службы нет"

        val bound = CountDownLatch(1)
        var binder: IBinder? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                binder = service
                bound.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) {
                sb.append("служба отключилась\n")
            }
        }
        val intent = Intent(SOMEIP_ACTION).setClassName(host.packageName, host.name)
        val ok = try {
            app.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            sb.append("bindService: SecurityException ${e.message}\n")
            return "нет прав на подключение"
        }
        sb.append("bindService=").append(ok).append('\n')
        if (!ok || !bound.await(5, TimeUnit.SECONDS)) {
            runCatching { app.unbindService(conn) }
            return "не подключилась"
        }
        val b = binder!!
        sb.append("подключено, descriptor=").append(runCatching { b.interfaceDescriptor }.getOrNull()).append('\n')

        toast(app, "Этап А (SOME/IP): шлю знак $limit на приборку ${PHASE_SEC} с — смотрите на приборку")
        var result = "отправлено"
        for (i in 0 until PHASE_SEC) {
            val r = notifyRoute(b, app.packageName, limit, toggle = 1)
            if (i == 0 || r != "ok") sb.append("notify #").append(i).append(": ").append(r).append('\n')
            if (r != "ok") { result = r; break }
            Thread.sleep(1_000)
        }
        // Выключаем знак, чтобы не висел на приборке.
        sb.append("выкл: ").append(notifyRoute(b, app.packageName, 0, toggle = 0)).append('\n')
        runCatching { app.unbindService(conn) }
        return result
    }

    /** Одна транзакция notifyCurrentRouteInformation; "ok" или текст ошибки. */
    private fun notifyRoute(b: IBinder, sender: String, limit: Int, toggle: Int): String {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(SOMEIP_DESCRIPTOR)
            data.writeInt(1) // структура не null
            data.writeInt(1) // naviRouteState: ведение (значения не разобраны)
            data.writeInt(0) // vehicleDirection
            data.writeString("") // eta
            data.writeString("") // dtd
            data.writeString("") // currentRoadName
            data.writeInt(0) // currentLane
            data.writeInt(toggle) // speedLimitToggle
            data.writeInt(limit) // speedLimit
            data.writeInt(0) // naviArrow
            data.writeString("") // nextRoadName
            data.writeString("") // distanceNextRoad
            data.writeInt(0) // numberOfTurnsToNextRoad
            data.writeString(sender)
            if (!b.transact(TXN_NOTIFY_ROUTE, data, reply, 0)) return "transact=false"
            reply.readException()
            "ok"
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Путь Б. */
    private fun runBroadcast(app: Context, limit: Int, sb: StringBuilder): String {
        sb.append("\n== Б. broadcast ").append(CLUSTER_ACTION).append(" cmd=").append(CMD_SPEED_LIMIT).append('\n')
        val static = app.packageManager.queryBroadcastReceivers(Intent(CLUSTER_ACTION), 0)
        sb.append("статические приёмники: ").append(
            static.joinToString { "${it.activityInfo.packageName}/${it.activityInfo.name}" }.ifEmpty { "нет" }
        ).append('\n')
        toast(app, "Этап Б (broadcast 420): шлю знак $limit на приборку ${PHASE_SEC} с — смотрите на приборку")
        for (i in 0 until PHASE_SEC) {
            app.sendBroadcast(clusterIntent(app, limit))
            Thread.sleep(1_000)
        }
        app.sendBroadcast(clusterIntent(app, 0))
        sb.append("отправлено ").append(PHASE_SEC).append(" раз + выкл (0)\n")
        return "отправлено"
    }

    private fun clusterIntent(app: Context, limit: Int) = Intent(CLUSTER_ACTION)
        .putExtra("cluster_cmd", CMD_SPEED_LIMIT)
        .putExtra("cluster_bundle", Bundle().apply {
            putString("sender", app.packageName)
            // Ключ значения для 420 не разобран: sendCommand(int,int) кладёт "state",
            // для чисел есть Key.CLUSTER_INTEGER = "integer".
            putInt("state", limit)
            putInt("integer", limit)
        })

    private fun shell(app: Context, cmd: String): String =
        AdbShell.exec(app, cmd, authTimeoutMs = 3_000) ?: "adb-канал недоступен: ${AdbShell.lastStatus}"

    private fun save(app: Context, sb: StringBuilder): File {
        val dir = File(app.getExternalFilesDir(null), "navi").apply { mkdirs() }
        val file = File(dir, "sla_${FILE_STAMP.format(Date())}.txt")
        file.writeText(sb.toString())
        Log.i(TAG, "отчёт: ${file.absolutePath}")
        return file
    }

    private fun toast(app: Context, text: String) =
        main.post { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }

    private val main = Handler(Looper.getMainLooper())
    private val STAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)
    private val FILE_STAMP = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private const val TAG = "ClusterSla"
    private const val DEFAULT_LIMIT = 60
    private const val PHASE_SEC = 12

    private const val SOMEIP_ACTION = "com.desay_svautomotive.NavigationService"
    private const val SOMEIP_DESCRIPTOR =
        "com.desay_svautomotive.svsomeiplibrary.aidl.INavigationServerAidlInterface"
    private const val TXN_NOTIFY_ROUTE = IBinder.FIRST_CALL_TRANSACTION + 8 // = 9

    private const val CLUSTER_ACTION = "com.desay_sv.cluster.music.action"
    private const val CMD_SPEED_LIMIT = 420 // ClusterManager.Cmd.ACU_NAVI_SPEED_LIMIT
}
