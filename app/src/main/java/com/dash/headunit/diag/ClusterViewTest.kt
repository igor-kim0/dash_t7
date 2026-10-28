package com.dash.headunit.diag

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.dash.headunit.car.QnxLauncher
import com.dash.headunit.navi.cluster.CarLanClient
import com.dash.headunit.navi.cluster.ClusterOverlay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Проверка «другой вид навигации на приборке». Кнопка в CarDebugActivity.
 *
 * Разбор sva.cluster (2026-10-27, RcMswitchToNaviPageByIHUUpdate): CarLan 4100/5
 * NaviDisplayArea=1 → страница 0x12 wholeSceneNavi (наши «Карты»),
 * NaviDisplayArea=2 → страница 0x13 arNavi (в cluster.kzb — Prefab_AR, другая
 * раскладка). Мы всегда слали только 1.
 *
 * Тест: включает калибровочную рамку (сетка 1920×720 видна целиком), по шагам
 * шлёт область 1 → 2 → 1, на каждом шаге тост (успеть сфотографировать
 * приборку) и чтение PPS через qconn (hmi/status, ivi_service/0402, 0405).
 * Пишется только штатное сообщение навигации, как у SVMapService.
 * Отчёт — files/cluster/view_<время>.txt.
 */
object ClusterViewTest {

    @Volatile
    var running = false
        private set

    fun start(context: Context) {
        if (running) return
        running = true
        val app = context.applicationContext
        Thread {
            val verdict = try {
                run(app)
            } catch (e: Throwable) {
                Log.w(TAG, "view test", e)
                "Вид приборки: ошибка ${e.javaClass.simpleName}: ${e.message}"
            } finally {
                running = false
            }
            Log.i(TAG, verdict)
            toast(app, verdict)
        }.start()
    }

    private fun run(app: Context): String {
        val sb = StringBuilder()
        sb.append("# cluster view test ").append(STAMP.format(Date())).append('\n')
        val qnx = QnxLauncher.reachable()
        sb.append("qconn: ").append(if (qnx) "есть" else "нет").append('\n')

        val calibrationWas = ClusterOverlay.calibration
        if (!calibrationWas) onMain { ClusterOverlay.toggleCalibration() }
        step(app, sb, qnx, "1/3 область 1 («Карты», стр. 0x12) — сфотографируйте приборку", 1)
        step(app, sb, qnx, "2/3 область 2 (AR, стр. 0x13) — сфотографируйте приборку", 2)
        step(app, sb, qnx, "3/3 снова область 1 — вернулась ли раскладка?", 1)
        if (!calibrationWas) onMain { ClusterOverlay.toggleCalibration() }

        val file = save(app, sb)
        return "Вид приборки: готово, пришлите фото шагов 1–3. Отчёт: ${file.name}"
    }

    private fun step(app: Context, sb: StringBuilder, qnx: Boolean, title: String, area: Int) {
        toast(app, title)
        sb.append("\n== ").append(title).append('\n')
        CarLanClient.send(app, CarLanClient.SUB_DISPLAY, CarLanClient.Json.displayCluster(true))
        CarLanClient.send(app, CarLanClient.SUB_AREA, CarLanClient.Json.naviArea(area))
        Thread.sleep(READ_AFTER_MS)
        sb.append(if (qnx) readPps() else "(qconn нет)\n")
        Thread.sleep(STEP_MS - READ_AFTER_MS)
    }

    private fun readPps(): String {
        val cat = QnxLauncher.find("cat") ?: return "(на QNX нет cat)\n"
        val script = PPS.joinToString("") { "echo '@$it'; $cat /var/pps/$it\n" }
        return QnxLauncher.sh(script) ?: "(нет sh на QNX)\n"
    }

    private fun onMain(block: () -> Unit) {
        main.post(block)
        Thread.sleep(SETTLE_MS)
    }

    private fun save(app: Context, sb: StringBuilder): File {
        val dir = File(app.getExternalFilesDir(null), "cluster").apply { mkdirs() }
        val file = File(dir, "view_${FILE_STAMP.format(Date())}.txt")
        file.writeText(sb.toString())
        Log.i(TAG, "отчёт: ${file.absolutePath}")
        return file
    }

    private fun toast(app: Context, text: String) =
        main.post { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val STAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)
    private val FILE_STAMP = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val PPS = listOf("hmi/status", "ivi_service/0402", "ivi_service/0405")
    private const val TAG = "ClusterViewTest"
    private const val STEP_MS = 15_000L
    private const val READ_AFTER_MS = 4_000L
    private const val SETTLE_MS = 2_000L
}
