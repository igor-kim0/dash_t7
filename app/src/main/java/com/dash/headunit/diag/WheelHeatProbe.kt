package com.dash.headunit.diag

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.dash.headunit.car.DesayCarReader
import com.dash.headunit.car.VhalReader
import com.dash.headunit.navi.AdbShell
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Проба «Подогрев руля: что меняется при нажатии кнопки». Кнопка в CarDebugActivity.
 *
 * Зачем: виджет пишет HvacID AC_STEER_HEATING (16,70), но ни одно штатное
 * приложение ГУ эту ячейку не пишет, и в снапшотах 29/30_wheel_heat она
 * оставалась 0. Нужно понять, видит ли ГУ нажатие физической кнопки вообще
 * и где (ячейка Desay, VHAL HVAC_STEERING_WHEEL_HEAT, клавиша, системный лог).
 *
 * Фазы (подсказки — тостами и строкой статуса на экране диагностики):
 *   1. [QUIET_MS] — ничего не трогать: так отсеиваются «живые» ячейки (обороты, напряжение…);
 *   2. [PRESS_MS] — нажать физическую кнопку подогрева руля (включить);
 *   3. [PRESS_MS] — нажать её ещё раз (выключить).
 * Каждый проход читает все ячейки Desay (группы 0..[GROUPS)-1, id 0..255,
 * ~1,5 с) + VHAL 0x1140050d и пишет в отчёт изменения с временем и фазой.
 * В конце — сводка: ячейки, менявшиеся только в фазах нажатия (кандидаты),
 * и logcat системы за время пробы, отфильтрованный по руль/подогрев/клавишам.
 * Только чтение — в шину ничего не пишется.
 * Отчёт — files/wheel_heat/probe_<время>.txt (+ строка в logcat, тег [TAG]).
 */
object WheelHeatProbe {

    @Volatile
    var running = false
        private set

    /** Что делать сейчас — показывается на экране диагностики. */
    @Volatile
    var status: String = "не запускалась"
        private set

    private enum class Phase(val title: String) {
        QUIET("1/3 ничего не трогайте"),
        ON("2/3 нажмите кнопку подогрева руля (включить)"),
        OFF("3/3 нажмите кнопку подогрева руля ещё раз (выключить)")
    }

    fun start(context: Context) {
        if (running) return
        running = true
        val app = context.applicationContext
        Thread {
            val verdict = try {
                run(app)
            } catch (e: Throwable) {
                Log.w(TAG, "probe", e)
                "Проба руля: ошибка ${e.javaClass.simpleName}: ${e.message}"
            } finally {
                running = false
            }
            status = verdict
            Log.i(TAG, verdict)
            toast(app, verdict)
        }.start()
    }

    private fun run(app: Context): String {
        val sb = StringBuilder()
        val since = LOGCAT_STAMP.format(Date())
        sb.append("# wheel heat probe ").append(STAMP.format(Date())).append('\n')
        val desay = DesayCarReader(app)
        if (!desay.connect()) {
            sb.append("Desay CarInfoService: НЕТ\n")
            val f = save(app, sb)
            return "Проба руля: нет CarInfoService. Отчёт: ${f.name}"
        }
        val vhal = VhalReader(app)
        sb.append("VHAL HVAC_STEERING_WHEEL_HEAT: ").append(vhalValue(vhal)).append('\n')
        sb.append("Desay 16,70 (AC_STEER_HEATING): ").append(desay.readInt(16, 70)).append('\n')
        sb.append("формат изменений: [время +с фаза] group,id: было → стало (байты)\n")

        val t0 = SystemClock.uptimeMillis()
        var prev = scan(desay)
        var prevVhal = vhalValue(vhal)
        sb.append("ячеек отвечает: ").append(prev.size).append('\n')
        // Сколько раз ячейка менялась в каждой фазе.
        val changes = mutableMapOf<Pair<Int, Int>, IntArray>()
        val phases = Phase.values()
        for (phase in phases) {
            status = phase.title
            toast(app, "Подогрев руля: ${phase.title}")
            sb.append("\n== ").append(clock()).append(' ').append(phase.title).append('\n')
            val dur = if (phase == Phase.QUIET) QUIET_MS else PRESS_MS
            val start = SystemClock.uptimeMillis()
            while (SystemClock.uptimeMillis() - start < dur) {
                val cur = scan(desay)
                val sec = (SystemClock.uptimeMillis() - t0) / 1000
                for ((key, now) in cur) {
                    val was = prev[key]
                    if (was != null && was.contentEquals(now)) continue
                    changes.getOrPut(key) { IntArray(phases.size) }[phase.ordinal]++
                    sb.append("[+").append(sec).append("с ").append(phase.name).append("] ")
                        .append(key.first).append(',').append(key.second).append(": ")
                        .append(was?.let(::bytes) ?: "нет").append(" → ").append(bytes(now)).append('\n')
                }
                for (key in prev.keys - cur.keys) {
                    sb.append("[+").append(sec).append("с ").append(phase.name).append("] ")
                        .append(key.first).append(',').append(key.second).append(": перестала отвечать\n")
                }
                val v = vhalValue(vhal)
                if (v != prevVhal) {
                    sb.append("[+").append(sec).append("с ").append(phase.name).append("] VHAL 0x1140050d: ")
                        .append(prevVhal).append(" → ").append(v).append('\n')
                    prevVhal = v
                }
                prev = cur
                SystemClock.sleep(PAUSE_MS)
            }
        }
        status = "собираю отчёт…"

        sb.append("\n== кандидаты: менялись при нажатиях, но не в покое\n")
        val candidates = changes.filter { (_, c) -> c[Phase.QUIET.ordinal] == 0 && (c[Phase.ON.ordinal] > 0 || c[Phase.OFF.ordinal] > 0) }
            .toSortedMap(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
        if (candidates.isEmpty()) sb.append("(нет — нажатие на шине Desay не видно)\n")
        candidates.forEach { (k, c) ->
            sb.append(k.first).append(',').append(k.second)
                .append(": вкл=").append(c[Phase.ON.ordinal]).append(" выкл=").append(c[Phase.OFF.ordinal])
                .append(" сейчас=").append(prev[k]?.let(::bytes) ?: "нет").append('\n')
        }
        sb.append("\n== шумные (менялись и в покое): ")
            .append(changes.filter { it.value[Phase.QUIET.ordinal] > 0 }.keys
                .sortedWith(compareBy({ it.first }, { it.second }))
                .joinToString(" ") { "${it.first},${it.second}" }.ifEmpty { "нет" })
            .append('\n')

        sb.append("\n== logcat системы за время пробы (руль/подогрев/HVAC/клавиши)\n")
        sb.append(shell(app, "logcat -d -v time -T '$since' | grep -i -E '$LOGCAT_FILTER' | grep -v -E '$LOGCAT_SKIP' | tail -n 400"))

        val file = save(app, sb)
        Log.i(TAG, "кандидаты: " + candidates.keys.joinToString(" ") { "${it.first},${it.second}" }.ifEmpty { "нет" })
        return "Проба руля: кандидатов ${candidates.size}. Отчёт: ${file.absolutePath}"
    }

    /** Все отвечающие ячейки Desay: (group,id) → байты. */
    private fun scan(r: DesayCarReader): Map<Pair<Int, Int>, IntArray> {
        val out = HashMap<Pair<Int, Int>, IntArray>(8192)
        for (g in 0 until GROUPS) for (id in 0 until IDS) {
            r.readRaw(g, id, logErrors = false)?.let { out[g to id] = it }
        }
        return out
    }

    private fun vhalValue(v: VhalReader): String =
        runCatching { v.propertyValue(PROP_WHEEL_HEAT)?.toString() ?: "нет" }.getOrElse { "ошибка ${it.javaClass.simpleName}" }

    private fun bytes(a: IntArray) = a.joinToString(",") { (it and 0xFF).toString() }

    private fun shell(app: Context, cmd: String): String {
        val out = AdbShell.exec(app, cmd) ?: return "(нет adb: ${AdbShell.lastStatus})\n"
        val text = out.ifBlank { "(пусто)" }
        return if (text.endsWith("\n")) text else "$text\n"
    }

    private fun save(app: Context, sb: StringBuilder): File {
        val dir = File(app.getExternalFilesDir(null), "wheel_heat").apply { mkdirs() }
        val file = File(dir, "probe_${FILE_STAMP.format(Date())}.txt")
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

    private const val TAG = "WheelHeatProbe"
    /** VHAL HVAC_STEERING_WHEEL_HEAT (в конфиге ГУ есть, rw, −2..2). */
    private const val PROP_WHEEL_HEAT = 289408269 // 0x1140050d
    private const val GROUPS = 32
    private const val IDS = 256
    private const val QUIET_MS = 20_000L
    private const val PRESS_MS = 25_000L
    private const val PAUSE_MS = 300L
    private const val LOGCAT_FILTER = "steer|wheel|heat|hvac|1140050d|keyevent|keycode|scancode|carinfo"
    private const val LOGCAT_SKIP = "PlatoformUtil-Config|WheelHeatProbe"
}
