package com.dash.headunit.diag

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.dash.headunit.car.QnxLauncher
import com.dash.headunit.navi.AdbShell
import com.dash.headunit.navi.cluster.CarLanClient
import com.dash.headunit.navi.cluster.ClusterOverlay
import com.dash.headunit.navi.cluster.Guidance
import com.dash.headunit.navi.cluster.GuidanceHub
import com.dash.headunit.navi.cluster.NaviAccessibilityService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Проба «маршрут на приборке, когда Яндекс на экране». Кнопка в CarDebugActivity.
 *
 * С Яндексом в фоне «Карты» показывают карточку, с Яндексом на экране —
 * «маленькие приборы, по центру пусто» (ГУ 2026-10-29). Так выглядят два
 * разных случая:
 *  - H1: видео дисплея 1 выключено — SVMapService прислал 4100/2
 *    DisplayCluster:false позже нашего 2+5 (по его коду — на запрос
 *    приборки RequestNaviAreaDisplay=0 и при смене режима питания), или
 *    приборка просила навигацию (=1), а ответить было некому;
 *  - H2: видео включено, но наше окно чёрное — ведения нет.
 *
 * Три фазы по [PHASE_MS] (тост — что сделать): Яндекс в блоке дашборда,
 * Яндекс на весь экран, Яндекс свёрнут. Каждые [SAMPLE_MS] — верхнее
 * приложение (adb shell), состояние [ClusterOverlay] и [GuidanceHub], PPS
 * приборки (ivi_service/0402 — DisplayCluster, как его получила приборка).
 * В середине фаз 1 и 2 — повтор 2+5: если картинка/PPS меняются, это H1.
 * В конце — logcat mapservice/carlan/meterinteraction и наших тегов за время
 * пробы и последние входящие CarLan от приборки ([CarLanClient.incomingLog]).
 * Отчёт — files/cluster/navi_probe_<время>.txt.
 */
object ClusterNaviProbe {

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
                Log.w(TAG, "probe", e)
                "Проба маршрута: ошибка ${e.javaClass.simpleName}: ${e.message}"
            } finally {
                running = false
            }
            Log.i(TAG, verdict)
            toast(app, verdict)
        }.start()
    }

    private fun run(app: Context): String {
        val sb = StringBuilder()
        // Время начала для logcat -T: часы ГУ общие для нас и logcat.
        val since = LOGCAT_STAMP.format(Date())
        sb.append("# cluster navi probe ").append(STAMP.format(Date())).append('\n')
        val qnx = QnxLauncher.reachable()
        sb.append("qconn: ").append(if (qnx) "есть" else "нет").append('\n')
        sb.append("adb shell: ").append(AdbShell.exec(app, "echo ok")?.trim() ?: "нет (${AdbShell.lastStatus})").append('\n')

        phase(app, sb, qnx, "1/3 Яндекс в блоке дашборда — вернитесь на дашборд", repeat = true)
        phase(app, sb, qnx, "2/3 откройте Яндекс на весь экран", repeat = true)
        phase(app, sb, qnx, "3/3 сверните Яндекс (дашборд без него или другое приложение)", repeat = false)

        toast(app, "Проба: собираю логи…")
        sb.append("\n== logcat за время пробы\n")
        for (pkg in SYSTEM_PKGS) {
            val pid = AdbShell.exec(app, "pidof $pkg")?.trim().orEmpty()
            sb.append("\n-- $pkg (pid ${pid.ifEmpty { "?" }})\n")
            if (pid.isNotEmpty()) {
                sb.append(shell(app, "logcat -d -v time -T '$since' --pid=$pid | grep -v PlatoformUtil-Config | tail -n 400"))
            }
        }
        sb.append("\n-- наши теги\n")
        sb.append(shell(app, "logcat -d -v time -T '$since' -s ${OUR_TAGS.joinToString(" ")} | tail -n 400"))

        sb.append("\n== входящие CarLan от приборки (последние)\n")
        CarLanClient.incomingLog().ifEmpty { listOf("(нет)") }.forEach { sb.append(it).append('\n') }

        val file = save(app, sb)
        return "Проба маршрута: готово. Отчёт: ${file.name}"
    }

    private fun phase(app: Context, sb: StringBuilder, qnx: Boolean, title: String, repeat: Boolean) {
        toast(app, title)
        sb.append("\n== ").append(title).append('\n')
        val start = SystemClock.uptimeMillis()
        var repeated = !repeat
        var windowsTaken = 0
        while (true) {
            val t = SystemClock.uptimeMillis() - start
            if (t >= PHASE_MS) break
            sample(app, sb, qnx, t)
            if (windowsTaken < WINDOWS_AT_MS.size && t >= WINDOWS_AT_MS[windowsTaken]) {
                windowsTaken++
                windows(app, sb)
            }
            if (!repeated && t >= PHASE_MS / 2) {
                repeated = true
                sb.append("-- ").append(clock()).append(" ПОВТОР 2+5 (DisplayCluster:true, область 1)\n")
                CarLanClient.showNaviArea(app, true)
                toast(app, "Панель на приборке поднята заново — появилось изображение?")
            }
            val spent = SystemClock.uptimeMillis() - start - t
            if (spent < SAMPLE_MS) Thread.sleep(SAMPLE_MS - spent)
        }
    }

    /**
     * Окна у службы спец. возможностей и у WMS. Android 9 отдаёт службе окно,
     * только если его не накрывают зоны касаний окон выше; в фазе 1 (ГУ
     * 2026-10-30) навигатора в списке не было, а над окном дашборда с вырезом
     * лежало ещё одно полноэкранное окно com.dash.headunit — dumpsys покажет,
     * что это (тип, флаги, зона касаний).
     */
    private fun windows(app: Context, sb: StringBuilder) {
        val a11y = NaviAccessibilityService.instance
        sb.append("-- ").append(clock()).append(" окна у службы\n")
            .append(a11y?.describeWindows() ?: "(служба спец. возможностей не подключена)").append('\n')
        sb.append("-- дерево навигатора\n").append(a11y?.dumpTree()?.lines()?.take(40)?.joinToString("\n") ?: "-").append('\n')
        sb.append("-- окна WMS\n").append(shell(app,
            "dumpsys window windows | grep -E 'Window #|mAttrs=|touchable region|mFrame=|isOnScreen=|mHasSurface='"))
    }

    private fun sample(app: Context, sb: StringBuilder, qnx: Boolean, t: Long) {
        sb.append("\n[").append(clock()).append(" +").append(t / 1000).append(" с]\n")
        val top = AdbShell.exec(app, "dumpsys activity activities | grep -E 'mResumedActivity|mFocusedActivity'")
        sb.append("верх: ").append(top?.trim()?.replace(Regex("\\s+"), " ") ?: "(нет adb)").append('\n')
        sb.append("оверлей: ").append(ClusterOverlay.debugState()).append('\n')
        sb.append("источники: ").append(GuidanceHub.sourcesInfo()).append('\n')
        sb.append("ведение: ").append(brief(GuidanceHub.current)).append('\n')
        if (qnx) sb.append(readPps())
    }

    private fun brief(g: Guidance?): String = if (g == null) "нет" else listOfNotNull(
        g.maneuver?.name,
        "иконка".takeIf { g.maneuverIcon != null },
        g.distance, g.street, g.remainingDistance, g.remainingTime, g.arrival,
        g.speedLimit?.let { "лимит $it" },
    ).joinToString(" · ")

    private fun readPps(): String {
        val cat = QnxLauncher.find("cat") ?: return "(на QNX нет cat)\n"
        val script = PPS.joinToString("") { "echo '@$it'; $cat /var/pps/$it\n" }
        return try {
            QnxLauncher.sh(script) ?: "(нет sh на QNX)\n"
        } catch (e: Exception) {
            "(PPS: ${e.javaClass.simpleName}: ${e.message})\n"
        }
    }

    private fun shell(app: Context, cmd: String): String {
        val out = AdbShell.exec(app, cmd) ?: return "(нет adb: ${AdbShell.lastStatus})\n"
        val text = out.ifBlank { "(пусто)" }
        return if (text.endsWith("\n")) text else "$text\n"
    }

    private fun save(app: Context, sb: StringBuilder): File {
        val dir = File(app.getExternalFilesDir(null), "cluster").apply { mkdirs() }
        val file = File(dir, "navi_probe_${FILE_STAMP.format(Date())}.txt")
        file.writeText(sb.toString())
        Log.i(TAG, "отчёт: ${file.absolutePath}")
        return file
    }

    private fun clock() = CLOCK.format(Date())

    private fun toast(app: Context, text: String) =
        main.post { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val STAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)
    private val FILE_STAMP = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val LOGCAT_STAMP = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val CLOCK = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val PPS = listOf("ivi_service/0402", "ivi_service/0405", "hmi/status")
    private val SYSTEM_PKGS = listOf("com.desaysv.mapservice", "com.desaysv.carlan", "com.desaysv.meterinteraction")
    private val OUR_TAGS = listOf("ClusterOverlay", "CarLan", "NaviA11y", "NaviNotif", "ManeuverGrab", "LaneGrab", "TrafficLightGrab", "NaviUnderlay")

    private const val TAG = "ClusterNaviProbe"
    private const val PHASE_MS = 30_000L
    private const val SAMPLE_MS = 2_000L
    /** Когда снимать окна: до «пинка» навигатора и после него. */
    private val WINDOWS_AT_MS = longArrayOf(8_000L, 18_000L, 26_000L)
}
