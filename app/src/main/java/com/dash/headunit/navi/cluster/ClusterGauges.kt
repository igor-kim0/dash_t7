package com.dash.headunit.navi.cluster

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.dash.headunit.car.CarHub
import com.dash.headunit.car.ClusterPps
import com.dash.headunit.car.QnxLauncher
import com.dash.headunit.car.UitData
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Скрытие штатного слоя приборки под свои темы — ключи модели данных Kanzi
 * через штатную утилиту Desay ([UitData], qconn, без записи в MCU).
 *  - [setWanted] all (любая своя тема): `System.IGNState` 0 — уходит весь
 *    Root/IGNON: скорость, обороты, верхняя и нижняя строки, виньетка,
 *    ТЕКСТОВЫЕ ПРЕДУПРЕЖДЕНИЯ; лампы остаются. Вернуть — 2 (ON).
 *  - иначе: `System.InitialStartUpInt` 0 — только скорость, обороты, дуги.
 *
 * Каждая запись — отдельный короткий sh, как у пробы diag/ClusterGaugesTest
 * («Спрятать всё на 30 с» на ГУ работает). До 2026-11-24 здесь была одна
 * долгая sh-сессия с командами через stdin сокета qconn — на ГУ она не
 * спрятала ничего ни разу.
 *
 * Безопасность — водитель не должен остаться без спидометра:
 *  - сторож на приборке (фоновый sh, [hideScript]): раз в [WD_S] с сверяет
 *    файл сердцебиения [HB]; мы переписываем его каждые [BEAT_MS]. Приложение
 *    умерло / qconn пропал → значение не меняется → сторож возвращает штатное;
 *  - нет скорости ни от приборки, ни от CarHub дольше [NO_SPEED_MS] —
 *    возвращаем штатное, пока данные не появятся;
 *  - убрали своё окно / сменили тему / калибровка / Яндекс на приборке —
 *    [setWanted] false → вернуть;
 *  - в худшем случае (сторож не пережил выход sh) штатное вернёт следующее
 *    включение зажигания. Жив ли сторож — в [state].
 *
 * sva.cluster пишет IGNState при смене питания, а включение зажигания само
 * возвращает приборы — поэтому через [REHIDE_DELAY_MS] после пуска
 * двигателя прячем снова.
 */
object ClusterGauges {

    @Volatile
    private var wanted = false

    @Volatile
    private var worker: Thread? = null

    /** Состояние для калибровки/диагностики. */
    @Volatile
    var state: String = "выкл"
        private set

    private val commands = LinkedBlockingQueue<String>()

    /** Прятать всё штатное (IGNState), а не только приборы. */
    @Volatile
    private var full = false

    /** Куда писать вывод последней записи ([REPORT]). */
    @Volatile
    private var filesDir: File? = null

    fun init(context: Context) {
        if (filesDir == null) filesDir = context.applicationContext.getExternalFilesDir(null)
    }

    /**
     * Нужно ли прятать штатные приборы (своя тема и наше окно видно);
     * [all] — прятать весь штатный слой.
     */
    fun setWanted(on: Boolean, all: Boolean = false) {
        if (on && wanted && full != all) {
            // Сменился режим: вернуть штатное и спрятать по-новому.
            Log.i(TAG, "режим: ${if (all) "всё" else "приборы"}")
            full = all
            commands.offer(CMD_RESTART)
            return
        }
        if (on) full = all
        if (wanted == on) return
        wanted = on
        Log.i(TAG, "нужно прятать: $on${if (all) " (всё)" else ""}")
        if (on) {
            if (worker == null) {
                worker = Thread(::loop, "ClusterGauges").apply {
                    isDaemon = true
                    start()
                }
            }
        } else {
            commands.offer(CMD_STOP)
        }
    }

    private fun setWantedAgain() {
        wanted = false
        setWanted(true, full)
    }

    /** Отправить «спрятать» ещё раз (сменили тему) — если сейчас прячем. */
    fun rehide() {
        if (wanted) commands.offer(CMD_HIDE)
    }

    private fun loop() {
        /** Каким режимом спрятано сейчас; null — штатное видно. */
        var hiddenAll: Boolean? = null
        var noSpeedSince = 0L
        var engineOn = engineRunning()
        var rehideAt = 0L
        var lastBeat = 0L
        var beat = 0L
        /** Сторож хоть раз отозвался живым после последнего «спрятать». */
        var wdSeen = false
        try {
            while (true) {
                val cmd = commands.poll(TICK_MS, TimeUnit.MILLISECONDS)
                if (!wanted) break
                val now = SystemClock.uptimeMillis()

                if (cmd == CMD_RESTART && hiddenAll != null && hiddenAll != full) {
                    show(hiddenAll)
                    hiddenAll = null
                }

                // Скорости нет — штатный спидометр обратно, пока не появится.
                val speedOk = speedKnown()
                if (speedOk) {
                    noSpeedSince = 0L
                } else if (noSpeedSince == 0L) {
                    noSpeedSince = now
                }
                if (hiddenAll == null) {
                    if (!speedOk) {
                        state = "нет скорости — штатные приборы видны"
                        continue
                    }
                    if (!QnxLauncher.reachable()) {
                        state = "qconn не отвечает"
                        sleepQuietly(RETRY_MS)
                        continue
                    }
                    val all = full
                    if (hide(all, ++beat)) {
                        hiddenAll = all
                        wdSeen = false
                        lastBeat = SystemClock.uptimeMillis()
                        engineOn = engineRunning()
                        rehideAt = 0L
                    } else {
                        sleepQuietly(RETRY_MS)
                    }
                    continue
                }
                if (!speedOk && now - noSpeedSince > NO_SPEED_MS) {
                    show(hiddenAll)
                    hiddenAll = null
                    state = "нет скорости — штатные приборы видны"
                    continue
                }

                // Пуск двигателя (после включения зажигания штатное вернулось само).
                val running = engineRunning()
                if (running && !engineOn) rehideAt = now + REHIDE_DELAY_MS
                engineOn = running
                val again = when {
                    cmd == CMD_HIDE -> "смена темы"
                    rehideAt != 0L && now >= rehideAt -> "после пуска двигателя"
                    else -> null
                }
                if (again != null) {
                    rehideAt = 0L
                    hide(hiddenAll, ++beat, again)
                    lastBeat = SystemClock.uptimeMillis()
                    continue
                }

                if (now - lastBeat >= BEAT_MS) {
                    lastBeat = now
                    when (heartbeat(++beat)) {
                        Beat.WD_OK -> {
                            wdSeen = true
                            state = "${hiddenText(hiddenAll)}, сторож жив"
                        }
                        Beat.WD_DEAD -> if (wdSeen) {
                            // Сторож был и сработал (сердцебиение запоздало) — штатное уже видно.
                            Log.w(TAG, "сторож вернул штатное — прячу снова")
                            hide(hiddenAll, ++beat, "сторож сработал")
                            wdSeen = false
                            lastBeat = SystemClock.uptimeMillis()
                        } else {
                            state = "${hiddenText(hiddenAll)}, сторож НЕ держится (вернёт только зажигание)"
                        }
                        Beat.NO_FILE -> state = "${hiddenText(hiddenAll)}, нет файла сердцебиения — сторожа нет"
                        Beat.FAILED -> state = "${hiddenText(hiddenAll)}, сердцебиение не дошло"
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "цикл", e)
        } finally {
            hiddenAll?.let {
                try {
                    show(it)
                } catch (e: Exception) {
                    Log.w(TAG, "вернуть штатное", e)
                }
            }
            worker = null
            state = "выкл"
            // Пока поток завершался, могли снова попросить — поднять заново.
            if (wanted) setWantedAgain()
        }
    }

    private fun hiddenText(all: Boolean) = if (all) "всё штатное спрятано" else "штатные приборы спрятаны"

    /** Спрятать и поднять сторожа, если его нет. true — скрипт отработал. */
    private fun hide(all: Boolean, beat: Long, why: String? = null): Boolean {
        state = (if (all) "прячу всё штатное" else "прячу штатные приборы") + (why?.let { " ($it)" } ?: "")
        val hideArgs = if (all) UitData.HIDE_ALL else UitData.HIDE_GAUGES
        val out = sh(hideScript(all, beat), UitData.timeoutMs(hideArgs.size))
        report("hide${if (all) " all" else ""}${why?.let { " ($it)" } ?: ""}", out)
        val ok = out != null && MARK_HIDDEN in out
        state = when {
            out == null -> "нет sh на приборке"
            ok -> hiddenText(all) + (why?.let { " ($it)" } ?: "")
            else -> "запись не дошла до конца"
        }
        return ok
    }

    /** Остановить сторожа и вернуть штатное. */
    private fun show(all: Boolean) {
        state = "возвращаю штатное"
        val args = if (all) UitData.SHOW_ALL else UitData.SHOW_GAUGES
        val script = "${UitData.ENV}\necho stop > $HB\n${UitData.runs(args)}\necho $MARK_SHOWN"
        val out = sh(script, UitData.timeoutMs(args.size))
        report("show${if (all) " all" else ""}", out)
        state = if (out != null && MARK_SHOWN in out) "штатное возвращено" else "возврат не подтверждён"
    }

    private enum class Beat { WD_OK, WD_DEAD, NO_FILE, FAILED }

    private fun heartbeat(n: Long): Beat {
        val d = '$'
        val out = sh(
            "echo $n > $HB && echo HB_OK\n" +
                "W=; [ -e $WD ] && read W < $WD; [ -n \"${d}W\" ] && kill -0 ${d}W 2>/dev/null && echo WD_OK\n" +
                "echo BEAT_END",
            BEAT_TIMEOUT_MS
        ) ?: return Beat.FAILED
        return when {
            "BEAT_END" !in out -> Beat.FAILED
            "HB_OK" !in out -> Beat.NO_FILE
            "WD_OK" in out -> Beat.WD_OK
            else -> Beat.WD_DEAD
        }
    }

    /**
     * Сторож: раз в [WD_S] с читает [HB]; «stop» — выйти, значение не
     * изменилось — вернуть штатное и выйти. Второго не поднимаем, если жив
     * (pid в [WD]). Свой HUP игнорирует — переживает выход нашего sh, если
     * qconn не снимает его иначе (тогда [heartbeat] это покажет).
     */
    private fun hideScript(all: Boolean, beat: Long): String {
        val d = '$'
        val hideArgs = if (all) UitData.HIDE_ALL else UitData.HIDE_GAUGES
        val showArgs = if (all) UitData.SHOW_ALL else UitData.SHOW_GAUGES
        return """
            ${UitData.ENV}
            echo $beat > $HB
            W=; [ -e $WD ] && read W < $WD
            if [ -z "${d}W" ] || ! kill -0 ${d}W 2>/dev/null; then
            ( trap '' HUP
              L=
              while :; do
                sleep $WD_S
                X=; read X < $HB
                [ "${d}X" = stop ] && exit 0
                [ "${d}X" = "${d}L" ] && break
                L=${d}X
              done
              ${UitData.runs(showArgs, quiet = true).replace("\n", "\n  ")}
            ) </dev/null >/dev/null 2>&1 &
            echo ${d}! > $WD
            fi
            ${UitData.runs(hideArgs)}
            echo $MARK_HIDDEN
        """.trimIndent()
    }

    private fun sh(script: String, timeoutMs: Int): String? = try {
        QnxLauncher.sh(script, timeoutMs)
    } catch (e: Exception) {
        Log.w(TAG, "sh", e)
        null
    }

    /** Вывод последней записи — для разбора с ГУ (только при записи, не периодически). */
    private fun report(what: String, out: String?) {
        val dir = filesDir ?: return
        try {
            File(dir, "cluster").apply { mkdirs() }.resolve(REPORT)
                .writeText("# $what ${java.util.Date()}\n${out ?: "(нет sh)"}\n")
        } catch (e: Exception) {
            Log.w(TAG, "отчёт", e)
        }
    }

    private fun speedKnown(): Boolean =
        ClusterPps.speedKmh() != null || CarHub.data?.speedKmh != null

    private fun engineRunning(): Boolean =
        ((ClusterPps.rpm() ?: CarHub.data?.rpm) ?: 0) > 300

    private fun sleepQuietly(ms: Long) {
        val cmd = commands.poll(ms, TimeUnit.MILLISECONDS)
        if (cmd != null && cmd != CMD_STOP) commands.offer(cmd)
    }

    /** Файлы сторожа на приборке. */
    private const val HB = "/tmp/dash_gauges_hb"
    private const val WD = "/tmp/dash_gauges_wd"
    private const val WD_S = 15
    private const val MARK_HIDDEN = "DASH_HIDDEN"
    private const val MARK_SHOWN = "DASH_SHOWN"
    private const val REPORT = "gauges_theme.txt"

    private const val CMD_STOP = "stop"
    private const val CMD_RESTART = "restart"
    private const val CMD_HIDE = "hide"
    private const val TICK_MS = 1000L
    private const val BEAT_MS = 5000L
    private const val BEAT_TIMEOUT_MS = 5000
    private const val RETRY_MS = 10_000L
    private const val NO_SPEED_MS = 5000L
    private const val REHIDE_DELAY_MS = 4000L
    private const val TAG = "ClusterGauges"
}
