package com.dash.headunit.diag

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.dash.headunit.car.QnxLauncher
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Проба «спрятать штатные шкалы приборки» (docs/research/cluster_theme.md,
 * 2026-11-21). Кнопка в CarDebugActivity, только на стоянке.
 *
 * Приборку рисует Kanzi (uit.uid.cluster-kanzi) по модели данных uit.svc.data.
 * Ключ System.InitialStartUpInt sva.cluster пишет один раз после самотеста
 * стрелок; 0 → состояние HMITrigerAni State_0_init → cluster.ThemeInOutStateInt=0:
 * скорость/обороты прозрачные, нижняя полоса уезжает, лампы остаются.
 * Пишем штатной утилитой Desay uit.test.data-controller через qconn (без MCU).
 *
 * Через [HOLD_MS] — обратно 1 (и в любом случае при ошибке / «Вернуть сейчас»).
 * Если приложение упадёт посередине — вернётся само при следующем включении
 * зажигания (EnteringIGNONTrigerAni State_2_ON ставит 1).
 * Отчёт с выводом утилиты — files/cluster/gauges_<режим>_<время>.txt.
 *
 * Режим [Mode.ALL] (разбор kzb 2026-11-22): после [Mode.GAUGES] на ГУ остались
 * затемнения по краям (виньетка Prefab_Navi — от Navigation.NaviModeInt,
 * sva.cluster пишет его каждый цикл) и нижняя строка (TopBottom на
 * InitialStartUpInt не реагирует, её Out — только от IGNState).
 * System.IGNState (Global.MarkEnum Default/OFF/ON) sva.cluster пишет только при
 * смене питания; 0 прячет весь Root/IGNON — навигационный слой с виньеткой и
 * TBT, приборы, верх, низ и ТЕКСТОВЫЕ ПРЕДУПРЕЖДЕНИЯ; остаются лампы
 * (Root/Indicator) и наше видео. ON возвращает (с анимацией включения).
 * Enum утилита разбирает через stoi — только номер (0 Default, 2 ON).
 */
object ClusterGaugesTest {

    /** Что прятать: [hide] — аргументы утилиты по порядку, [restore] — чем вернуть. */
    enum class Mode(val key: String, val title: String, val hide: List<String>, val restore: List<String>) {
        GAUGES(
            "gauges", "Шкалы",
            listOf("--setint=System.InitialStartUpInt#0"),
            listOf("--setint=System.InitialStartUpInt#1")
        ),
        ALL(
            "all", "Всё штатное",
            // --setenum — только номер (stoi): 0 Default, 2 ON.
            listOf("--setenum=System.IGNState#0"),
            listOf("--setenum=System.IGNState#2")
        )
    }

    @Volatile
    var running = false
        private set

    @Volatile
    private var worker: Thread? = null

    fun start(context: Context, mode: Mode = Mode.GAUGES) {
        if (running) return
        running = true
        val app = context.applicationContext
        worker = Thread {
            val verdict = try {
                run(app, mode)
            } catch (e: Throwable) {
                Log.w(TAG, "gauges test", e)
                "${mode.title}: ошибка ${e.javaClass.simpleName}: ${e.message}"
            } finally {
                running = false
                worker = null
            }
            Log.i(TAG, verdict)
            toast(app, verdict)
        }.apply { start() }
    }

    /** Вернуть шкалы, не дожидаясь [HOLD_MS]. */
    fun restoreNow() {
        worker?.interrupt()
    }

    private fun run(app: Context, mode: Mode): String {
        val sb = StringBuilder()
        sb.append("# cluster gauges test, mode ${mode.key} ").append(STAMP.format(Date())).append('\n')
        if (!QnxLauncher.reachable()) {
            return "${mode.title}: qconn не отвечает".also { save(app, mode, sb.append("qconn: нет\n")) }
        }

        sb.append("\n== help\n").append(controller("--help"))
        var hidden = false
        try {
            toast(app, "${mode.title}: прячу на приборке — сфотографируйте. Вернётся через ${HOLD_MS / 1000} с")
            for (arg in mode.hide) {
                sb.append("\n== hide $arg\n").append(controller(arg))
                hidden = true
            }
            try {
                Thread.sleep(HOLD_MS)
            } catch (e: InterruptedException) {
                sb.append("\n(возврат по кнопке)\n")
            }
        } finally {
            // Возвращаем всегда, даже если запись «не прошла» — вдруг прошла.
            Thread.interrupted()
            for (arg in mode.restore) sb.append("\n== restore $arg\n").append(controller(arg))
        }
        val file = save(app, mode, sb)
        return "${mode.title}: ${if (hidden) "возвращено" else "проба не дошла до записи"}. Отчёт: ${file.name}"
    }

    /**
     * Запуск утилиты с окружением uit (библиотеки в /emmc/uit/lib); если она
     * не завершается сама — снимаем через [RUN_S] с.
     */
    private fun controller(arg: String): String {
        val script = """
            export LD_LIBRARY_PATH=$UIT/lib:${'$'}LD_LIBRARY_PATH
            cd $UIT/etc
            $UIT/bin/uit.test.data-controller '$arg' 2>&1 &
            P=${'$'}!
            sleep $RUN_S
            kill ${'$'}P 2>/dev/null && echo '(снята по таймауту)'
            echo '(конец)'
        """.trimIndent()
        return QnxLauncher.sh(script, (RUN_S + 6) * 1000) ?: "(нет sh на QNX)\n"
    }

    private fun save(app: Context, mode: Mode, sb: StringBuilder): File {
        val dir = File(app.getExternalFilesDir(null), "cluster").apply { mkdirs() }
        val file = File(dir, "gauges_${mode.key}_${FILE_STAMP.format(Date())}.txt")
        file.writeText(sb.toString())
        Log.i(TAG, "отчёт: ${file.absolutePath}")
        return file
    }

    private fun toast(app: Context, text: String) =
        main.post { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val STAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)
    private val FILE_STAMP = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private const val UIT = "/emmc/uit"
    private const val RUN_S = 4
    private const val HOLD_MS = 30_000L
    private const val TAG = "ClusterGaugesTest"
}
