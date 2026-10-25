package com.dash.headunit.diag

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.dash.headunit.navi.AdbShell
import com.dash.headunit.navi.NaviUnderlay
import com.dash.headunit.navi.cluster.NaviAccessibilityService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Проверка «почему служба спец. возможностей не видит окно навигатора в блоке»
 * одной кнопкой (CarDebugActivity), без ПК.
 *
 * Гипотеза: Android 9 считает окно перекрытым по описанному прямоугольнику
 * зоны касаний дашборда, а он — весь экран, хоть под навигатор и вырезано.
 * Тест: снимок окон службы → [NaviUnderlay.peek] (зона касаний кончается над
 * нижней строкой выреза) → снимок во время peek (+ dumpsys accessibility
 * через adb-канал) → снимок после. Навигатор должен стоять в блоке под
 * дашбордом, лучше с построенным маршрутом.
 *
 * Отчёт: files/navi/peek_<время>.txt, вердикт — тостом.
 */
object NaviPeekTest {

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
                Log.w(TAG, "peek test", e)
                "Проверка окна навигатора: ошибка ${e.javaClass.simpleName}: ${e.message}"
            } finally {
                running = false
            }
            Log.i(TAG, verdict)
            main.post { Toast.makeText(app, verdict, Toast.LENGTH_LONG).show() }
        }.start()
    }

    private fun run(app: Context): String {
        toast(app, "Проверка окна навигатора: ~${(SETTLE_MS + PEEK_SEC * 1000L + AFTER_MS) / 1000} с, не трогайте экран")
        // Дашборд возвращается на экран, навигатор — в блок под ним.
        Thread.sleep(SETTLE_MS)
        val sb = StringBuilder()
        sb.append("# peek test ").append(STAMP.format(Date())).append('\n')

        val service = NaviAccessibilityService.instance
            ?: return "Проверка окна навигатора: служба спец. возможностей не подключена"

        val before = service.describeWindows()
        sb.append("\n== до peek: окна службы\n").append(before).append('\n')

        var started = false
        val lock = Object()
        main.post {
            started = NaviUnderlay.peek(PEEK_SEC)
            synchronized(lock) { lock.notifyAll() }
        }
        synchronized(lock) { lock.wait(2_000) }
        if (!started) {
            save(app, sb.append("\npeek не запустился: выреза нет\n"))
            return "Проверка окна навигатора: выреза под навигатор нет — включите навигатор в блоке под дашбордом"
        }

        Thread.sleep(DURING_MS)
        val during = service.describeWindows()
        sb.append("\n== во время peek: окна службы\n").append(during).append('\n')
        sb.append("\n== во время peek: дерево навигатора\n").append(service.dumpTree()).append('\n')
        val dumpsys = AdbShell.exec(app, "dumpsys accessibility", authTimeoutMs = 3_000)
        sb.append("\n== во время peek: dumpsys accessibility\n")
            .append(dumpsys ?: "adb-канал недоступен: ${AdbShell.lastStatus}").append('\n')

        Thread.sleep(PEEK_SEC * 1000L - DURING_MS + AFTER_MS)
        val after = service.describeWindows()
        sb.append("\n== после peek: окна службы\n").append(after).append('\n')

        val naviBefore = NAVI in before
        val naviDuring = NAVI in during
        val naviAfter = NAVI in after
        val verdict = when {
            naviDuring && !naviBefore && !naviAfter ->
                "ГИПОТЕЗА ПОДТВЕРДИЛАСЬ: окно навигатора видно только во время peek"
            naviDuring && !naviBefore ->
                "окно навигатора появилось во время peek и осталось после — скорее подтвердилась"
            naviBefore ->
                "окно навигатора видно и без peek — постоянный вырез строки работает"
            else -> "НЕ ПОДТВЕРДИЛАСЬ: окна навигатора нет и во время peek"
        }
        sb.append("\n== вывод\n").append(verdict).append('\n')
        val file = save(app, sb)
        return "Проверка окна навигатора: $verdict. Отчёт: ${file.name}"
    }

    private fun save(app: Context, sb: StringBuilder): File {
        val dir = File(app.getExternalFilesDir(null), "navi").apply { mkdirs() }
        val file = File(dir, "peek_${FILE_STAMP.format(Date())}.txt")
        file.writeText(sb.toString())
        Log.i(TAG, "отчёт: ${file.absolutePath}")
        return file
    }

    private fun toast(app: Context, text: String) =
        main.post { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }

    private val main = Handler(Looper.getMainLooper())
    private val STAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)
    private val FILE_STAMP = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private const val NAVI = "pkg=ru.yandex.yandexnavi"
    private const val TAG = "NaviPeekTest"
    /** Сколько ждать, пока дашборд вернётся на экран. */
    private const val SETTLE_MS = 4_000L
    private const val PEEK_SEC = 15
    /** Снимок во время peek — через столько после начала (окна обновляются не сразу). */
    private const val DURING_MS = 4_000L
    /** Снимок после — через столько после конца peek. */
    private const val AFTER_MS = 2_000L
}
