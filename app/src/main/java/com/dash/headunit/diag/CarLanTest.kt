package com.dash.headunit.diag

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.dash.headunit.navi.cluster.CarLanClient
import com.dash.headunit.navi.cluster.ClusterOverlay
import com.dash.headunit.navi.cluster.ClusterWarnings
import com.dash.headunit.navi.cluster.Guidance
import com.dash.headunit.navi.cluster.GuidanceHub
import com.dash.headunit.navi.cluster.Maneuver
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Проверки CarLan с кнопок CarDebugActivity (то же, что adb NAVI_CLUSTER
 * cmd carlan_video / carlan_cam). Приборку заранее переключить в «Карты».
 *
 *  - [startVideo]: демо-маршрут в окне на дисплее 1 + 4100/2+5+4 на
 *    [VIDEO_SEC] с, потом выключение — видна ли наша картинка на приборке;
 *  - [startLimit]: панель навигации (2+5) и шаги CameraInfo (4100/4) по
 *    [STEP_SEC] с — на каком шаге приборка рисует знак лимита.
 * Ответы CarLan (результат writeMessage) — в отчёте
 * files/navi/carlan_<время>.txt.
 */
object CarLanTest {

    @Volatile
    var running = false
        private set

    fun startVideo(context: Context) = start(context, "видео") { app, sb ->
        ClusterOverlay.init(app)
        GuidanceHub.setDemo(DEMO)
        ClusterOverlay.show(announce = false)
        // Демо может само вызвать 207 (настройка «на приборке»), а mapservice
        // отвечает на него DisplayCluster:false — наше «вкл» шлём после.
        Thread.sleep(1500)
        toast(app, "CarLan: видео вкл на $VIDEO_SEC с — смотрите в «Карты» приборки")
        sb.append("вкл: 4100/2+5+4\n")
        CarLanClient.showNaviArea(app, true)
        CarLanClient.send(app, CarLanClient.SUB_CAMERA, ClusterWarnings.json(DEMO))
        Thread.sleep(VIDEO_SEC * 1000L)
        sb.append("выкл\n")
        CarLanClient.showNaviArea(app, false)
        CarLanClient.send(app, CarLanClient.SUB_CAMERA, ClusterWarnings.json(null))
        GuidanceHub.setDemo(null)
        "CarLan видео: скажите, была ли картинка на приборке"
    }

    fun startLimit(context: Context) = start(context, "лимит") { app, sb ->
        CarLanClient.showNaviArea(app, true)
        STEPS.forEachIndexed { i, s ->
            val json = CarLanClient.Json.cameraInfo(s[0], s[1], s[2], s[3])
            val label = "шаг ${i + 1}/${STEPS.size}: CameraType=${s[0]} SpeedingInfo=${s[2]} WarningMessage=${s[3]}"
            sb.append(label).append('\n')
            toast(app, label)
            CarLanClient.send(app, CarLanClient.SUB_CAMERA, json)
            Thread.sleep(STEP_SEC * 1000L)
        }
        CarLanClient.send(app, CarLanClient.SUB_CAMERA, ClusterWarnings.json(null))
        CarLanClient.showNaviArea(app, false)
        "CarLan лимит: скажите, на каком шаге был знак"
    }

    /** CarLan от реального маршрута (до перезапуска процесса). */
    fun toggleFromRoute(context: Context): Boolean {
        CarLanClient.enabled = !CarLanClient.enabled
        ClusterOverlay.init(context.applicationContext)
        if (CarLanClient.enabled && GuidanceHub.current != null) ClusterOverlay.show(announce = true)
        return CarLanClient.enabled
    }

    private fun start(context: Context, name: String, body: (Context, StringBuilder) -> String) {
        if (running) return
        running = true
        val app = context.applicationContext
        Thread {
            val t0 = System.currentTimeMillis()
            val sb = StringBuilder("# CarLan $name ${STAMP.format(Date())}\n")
            val verdict = try {
                body(app, sb)
            } catch (e: Throwable) {
                Log.w(TAG, name, e)
                "CarLan $name: ошибка ${e.javaClass.simpleName}: ${e.message}"
            } finally {
                running = false
            }
            Thread.sleep(1000) // дождаться ответов CarLan
            sb.append("\n== ответы CarLan\n")
            val replies = CarLanClient.history().filter { (it.substringBefore(' ').toLongOrNull() ?: 0) >= t0 }
            replies.forEach { sb.append(it).append('\n') }
            val file = save(app, sb)
            val errors = replies.count { it.contains("ошибка") || it.contains("не привязывается") }
            val tail = if (replies.isEmpty()) "CarLan не ответил" else "ответов ${replies.size}, ошибок $errors"
            toast(app, "$verdict ($tail). Отчёт: ${file.name}")
        }.start()
    }

    private fun save(app: Context, sb: StringBuilder): File {
        val dir = File(app.getExternalFilesDir(null), "navi").apply { mkdirs() }
        val file = File(dir, "carlan_${FILE_STAMP.format(Date())}.txt")
        file.writeText(sb.toString())
        Log.i(TAG, "отчёт: ${file.absolutePath}")
        return file
    }

    private fun toast(app: Context, text: String) =
        main.post { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }

    private val main = Handler(Looper.getMainLooper())
    private val STAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)
    private val FILE_STAMP = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    private val DEMO = Guidance(
        maneuver = Maneuver.RIGHT,
        distance = "300 м",
        street = "ул. Ленина",
        remainingDistance = "12 км",
        remainingTime = "18 мин",
        arrival = "14:35",
        speedLimit = 60,
    )

    /** CameraType, NaviStatus, SpeedingInfo, WarningMessage. */
    private val STEPS = listOf(
        intArrayOf(3, 3, 60, 1), // как TiggoBridge
        intArrayOf(3, 3, 90, 1),
        intArrayOf(3, 3, 90, 0),
        intArrayOf(0, 3, 90, 1),
        intArrayOf(1, 3, 90, 2),
    )
    private const val VIDEO_SEC = 30
    private const val STEP_SEC = 6
    private const val TAG = "CarLanTest"
}
