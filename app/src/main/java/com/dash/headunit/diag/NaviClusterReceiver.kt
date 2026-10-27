package com.dash.headunit.diag

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.dash.headunit.navi.NaviUnderlay
import com.dash.headunit.navi.cluster.CarLanClient
import com.dash.headunit.navi.cluster.ClusterOverlay
import com.dash.headunit.navi.cluster.ClusterSetup
import com.dash.headunit.navi.cluster.ClusterTbt
import com.dash.headunit.navi.cluster.ClusterWarnings
import com.dash.headunit.navi.cluster.Guidance
import com.dash.headunit.navi.cluster.GuidanceHub
import com.dash.headunit.navi.cluster.Maneuver
import com.dash.headunit.navi.cluster.ManeuverGrab
import com.dash.headunit.navi.cluster.NaviAccessibilityService
import com.dash.headunit.navi.cluster.YandexNotificationParser
import com.dash.headunit.settings.AppSettings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Маршрут на приборке — проверка с ПК:
 *
 *   adb shell am broadcast -a com.dash.headunit.NAVI_CLUSTER \
 *       -n com.dash.headunit/.diag.NaviClusterReceiver --es cmd <команда>
 *
 * Команды:
 *  - dump [--es label x] — состояние, дисплеи, права, разобранное уведомление
 *    навигатора и дерево его окна в
 *    /sdcard/Android/data/com.dash.headunit/files/navi/<label>.txt (+ .done);
 *  - setup — выдать права (оверлей, спец. возможности, уведомления);
 *  - demo [--ei sec 30] — демо-маршрут на приборке (без навигатора);
 *  - show / hide — окно на приборке + переключение вида (10019);
 *    hide оставляет пустое чёрное окно, remove убирает его совсем
 *    (на дисплее 1 вернётся зеркало главного экрана);
 *  - tbt --ez on true|false — родные подсказки приборки [ClusterTbt]
 *    (по умолчанию выключены, до перезапуска процесса);
 *  - carlan_on --ez on true|false — CarLan из маршрута [CarLanClient]:
 *    видео дисплея 1 (4100/2+5) и лимит/камеры (4100/4); по умолчанию
 *    выключено, до перезапуска процесса;
 *  - carlan_video --ez on true|false — разово 4100/2+5+4 (без маршрута);
 *  - carlan_cam [--ei cam 3] [--ei st 3] [--ei spd 60] [--ei warn 1] —
 *    разово CameraInfo 4100/4; cam и warn запоминаются для [ClusterWarnings];
 *  - carlan --ei sub N --es json '{…}' [--ei type 4100] — сырое сообщение;
 *    ответы CarLan — в dump, раздел «CarLan»;
 *  - width --ei px 1100 — ширина центральной полосы картинки;
 *  - peek [--ei sec 20] — проверка: на sec секунд зона касаний дашборда
 *    кончается над нижней строкой выреза под навигатор (касания ниже
 *    блока не работают); в это время снять dump и dumpsys accessibility.
 *
 * Защищён правом DUMP: отправить может только adb shell/система.
 */
class NaviClusterReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        ClusterOverlay.init(app)
        when (val cmd = intent.getStringExtra("cmd") ?: "dump") {
            "show" -> ClusterOverlay.show(announce = true)
            "hide" -> ClusterOverlay.hide(announce = true)
            "remove" -> ClusterOverlay.remove()
            "tbt" -> {
                ClusterTbt.enabled = intent.getBooleanExtra("on", true)
                if (!ClusterTbt.enabled) ClusterTbt.stop(app)
                Log.i(TAG, "родные подсказки приборки: ${ClusterTbt.enabled}")
            }
            "carlan_on" -> {
                CarLanClient.enabled = intent.getBooleanExtra("on", true)
                Log.i(TAG, "CarLan из маршрута: ${CarLanClient.enabled}")
                if (GuidanceHub.current != null) ClusterOverlay.show(announce = true)
            }
            "carlan_video" -> {
                val on = intent.getBooleanExtra("on", true)
                CarLanClient.showNaviArea(app, on)
                CarLanClient.send(app, CarLanClient.SUB_CAMERA, ClusterWarnings.json(GuidanceHub.current ?: DEMO.takeIf { on }))
            }
            "carlan_cam" -> {
                ClusterWarnings.cameraType = intent.getIntExtra("cam", ClusterWarnings.cameraType)
                ClusterWarnings.warnShow = intent.getIntExtra("warn", ClusterWarnings.warnShow)
                CarLanClient.send(app, CarLanClient.SUB_CAMERA, CarLanClient.Json.cameraInfo(
                    ClusterWarnings.cameraType, intent.getIntExtra("st", 3),
                    intent.getIntExtra("spd", 60), ClusterWarnings.warnShow,
                ))
            }
            "carlan" -> {
                val json = intent.getStringExtra("json")
                if (json == null) Log.w(TAG, "carlan: нет --es json")
                else CarLanClient.send(app, intent.getIntExtra("sub", 0), json, intent.getIntExtra("type", CarLanClient.TYPE_NAVI))
            }
            "width" -> ClusterOverlay.setContentWidth(app, intent.getIntExtra("px", 1100))
            "peek" -> {
                val sec = intent.getIntExtra("sec", 20)
                if (!NaviUnderlay.peek(sec)) Log.w(TAG, "peek: выреза нет (дашборд не создан?)")
            }
            "demo" -> {
                // Родной режим: демо идёт подсказками приборки сами (GuidanceHub → ClusterOverlay).
                val overlay = !ClusterOverlay.isNative(app)
                GuidanceHub.setDemo(DEMO)
                if (overlay) ClusterOverlay.show(announce = true)
                Handler(Looper.getMainLooper()).postDelayed({
                    GuidanceHub.setDemo(null)
                    if (overlay && GuidanceHub.current == null) ClusterOverlay.hide(announce = true)
                }, intent.getIntExtra("sec", 30) * 1000L)
            }
            "setup", "dump" -> {
                val label = (intent.getStringExtra("label") ?: "navi")
                    .replace(Regex("[^A-Za-z0-9_.-]"), "_")
                val pending = goAsync()
                Thread {
                    try {
                        if (cmd == "setup") Log.i(TAG, "setup: " + ClusterSetup.ensureAll(app))
                        else write(app, label)
                    } catch (e: Throwable) {
                        Log.w(TAG, cmd, e)
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
            else -> Log.w(TAG, "неизвестная команда $cmd")
        }
    }

    private fun write(context: Context, label: String) {
        val dir = File(context.getExternalFilesDir(null), "navi").apply { mkdirs() }
        File(dir, "$label.done").delete()
        val sb = StringBuilder()
        sb.append("# time=")
            .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date()))
            .append('\n')
        sb.append("настройка «на приборке»: ").append(AppSettings.clusterNavi(context))
            .append(", режим: ").append(if (ClusterOverlay.isNative(context)) "родной интерфейс" else "своё окно")
            .append(", TBT идёт: ").append(ClusterTbt.isActive)
            .append(", вариант: ").append(AppSettings.clusterVariant(context))
            .append(", ширина ").append(AppSettings.clusterWidth(context)).append('\n')
        sb.append("оверлей: ").append(Settings.canDrawOverlays(context))
            .append(", спец. возможности: ").append(ClusterSetup.isA11yEnabled(context))
            .append(" (служба ").append(if (NaviAccessibilityService.instance != null) "подключена" else "НЕ подключена")
            .append("), уведомления: ").append(ClusterSetup.isListenerEnabled(context)).append('\n')
        sb.append("окно на приборке: ").append(ClusterOverlay.isShown).append('\n')

        sb.append("\n== дисплеи\n")
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        for (d in dm.displays) {
            val p = android.graphics.Point().also { d.getRealSize(it) }
            sb.append(d.displayId).append(" \"").append(d.name).append("\" ")
                .append(p.x).append('x').append(p.y).append(" flags=0x")
                .append(Integer.toHexString(d.flags)).append(" state=").append(d.state).append('\n')
        }
        sb.append("приборка: ").append(ClusterOverlay.clusterDisplay(context)?.displayId).append('\n')

        sb.append("\n== ведение сейчас\n")
        sb.append(GuidanceHub.current?.let(YandexNotificationParser::describe) ?: "нет").append('\n')
        sb.append("\n== стрелка с экрана\n").append(ManeuverGrab.lastDump).append('\n')
        sb.append("\n== последнее уведомление навигатора\n").append(GuidanceHub.lastNotificationDump).append('\n')
        sb.append("\n== окна у службы спец. возможностей\n")
            .append(NaviAccessibilityService.instance?.describeWindows() ?: "служба не подключена").append('\n')
        sb.append("\n== окно навигатора (спец. возможности)\n")
            .append(NaviAccessibilityService.instance?.dumpTree() ?: "служба не подключена").append('\n')
        sb.append("\n== история\n")
        ClusterOverlay.statusHistory().forEach { sb.append(it).append('\n') }
        sb.append("\n== CarLan (из маршрута: ").append(CarLanClient.enabled).append(")\n")
        CarLanClient.history().forEach { sb.append(it).append('\n') }

        File(dir, "$label.txt").writeText(sb.toString())
        File(dir, "$label.done").writeText("ok\n")
        Log.i(TAG, "дамп: ${dir.absolutePath}/$label.txt")
    }

    private companion object {
        const val TAG = "NaviCluster"
        val DEMO = Guidance(
            maneuver = Maneuver.RIGHT,
            distance = "300 м",
            street = "ул. Ленина",
            remainingDistance = "12 км",
            remainingTime = "18 мин",
            arrival = "14:35",
            speedLimit = 60,
            cameraDistance = "350 м",
            cameraLimit = 60,
        )
    }
}
