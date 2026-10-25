package com.dash.headunit.diag

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.dash.headunit.car.ClusterParser
import com.dash.headunit.car.QnxLauncher
import com.dash.headunit.elm.ElmHub
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Проверка «ОЖ в °C от приборки через инженерное меню». Кнопка в CarDebugActivity.
 *
 * Из реверса sva.cluster (QNX-хост 192.168.2.1, образ qnx_system из OTA Cherry
 * Inside, см. «tenet t7/cherry inside info/07_qnx/QNX_SYSTEM.md»):
 *  - в a000 у ОЖ только стрелка (на этой машине «цифровой» слот = стрелке);
 *  - °C есть в a0ff (ETM MCU data 2: b14|b15 × 0.75 − 48), но MCU шлёт его только
 *    после «входа в ETM»: RcDataSendETMEntryState(1) → в /var/pps/spi_service/tx
 *    атрибут xA101 = [0, 0xA1, 0x01, 52, 0] + rc_msg_send_t{u32 0x37, 1, state, 0…}.
 *
 * Тест: показывает текущий tx (сверка формата с тем, что пишет sva.cluster),
 * шлёт вход, [TICKS] с читает a0fe/a0ff, шлёт выход. Это запись в MCU — только
 * осознанно. Отчёт — files/cluster/etm_<время>.txt.
 */
object CoolantEtmTest {

    @Volatile
    var running = false
        private set

    fun start(context: Context) {
        if (running) return
        running = true
        val app = context.applicationContext
        toast(app, "ОЖ через ETM: шлю вход в инженерное меню, ~${TICKS + 10} с")
        Thread {
            val verdict = try {
                run(app)
            } catch (e: Throwable) {
                Log.w(TAG, "etm test", e)
                "ОЖ через ETM: ошибка ${e.javaClass.simpleName}: ${e.message}"
            } finally {
                running = false
            }
            Log.i(TAG, verdict)
            toast(app, verdict)
        }.start()
    }

    private fun run(app: Context): String {
        val sb = StringBuilder()
        sb.append("# etm test ").append(STAMP.format(Date())).append('\n')
        if (!QnxLauncher.reachable()) return "ОЖ через ETM: qconn ${QnxLauncher.HOST}:${QnxLauncher.PORT} не отвечает"
        val cat = QnxLauncher.find("cat")
        val sleep = QnxLauncher.find("sleep")
        if (cat == null || sleep == null) return "ОЖ через ETM: на QNX нет cat/sleep ($cat, $sleep)"

        val script = buildString {
            append("echo '@@tx'; $cat $DIR/tx\n")
            append("echo '${entry(1)}' >> $DIR/tx\n")
            append("n=0; while [ \$n -lt $TICKS ]; do echo \"@@t\$n\"; ")
            for (obj in listOf("a0fe", "a0ff")) append("[ -f $DIR/$obj ] && { echo '@$obj'; $cat $DIR/$obj; }; ")
            append("$sleep 1; n=\$((n+1)); done\n")
            append("echo '${entry(0)}' >> $DIR/tx\n")
            append("echo '@@tx2'; $cat $DIR/tx\n")
        }
        sb.append("== скрипт\n").append(script).append('\n')
        val out = QnxLauncher.sh(script, timeoutMs = (TICKS + 15) * 1000) ?: return "ОЖ через ETM: нет sh на QNX"
        sb.append("== вывод\n").append(out).append('\n')

        // Разбор: последний кадр a0ff → °C.
        var obj: String? = null
        var frames = 0
        var lastC: Float? = null
        var a0fe = false
        for (line in out.lines().map { it.trim() }) {
            when {
                line.startsWith("@") && !line.startsWith("@@") -> obj = line.substring(1)
                line.startsWith("data:json:") && obj == "a0fe" -> a0fe = true
                line.startsWith("data:json:") && obj == "a0ff" -> {
                    val b = ClusterParser.parseData(line) ?: continue
                    frames++
                    if (b.size >= 16) lastC = (b[14] or (b[15] shl 8)) * 0.75f - 48f
                }
            }
        }
        val elm = ElmHub.coolantC()
        sb.append("\na0ff кадров: ").append(frames).append(", a0fe: ").append(a0fe)
            .append(", ОЖ a0ff: ").append(lastC).append(", ОЖ ELM: ").append(elm).append('\n')
        val file = save(app, sb)
        val res = when {
            lastC != null -> "a0ff есть, ОЖ %.1f °C".format(Locale.US, lastC) +
                (elm?.let { " (ELM %.0f)".format(Locale.US, it) } ?: "")
            a0fe -> "пришёл только a0fe, a0ff нет"
            else -> "a0fe/a0ff не появились"
        }
        return "ОЖ через ETM: $res. Отчёт: ${file.name}"
    }

    /** Строка PPS для tx: вход (state=1) / выход (0) из ETM. */
    internal fun entry(state: Int): String {
        val msg = IntArray(MSG_LEN)
        msg[0] = MSG_ETM_ENTRY
        msg[4] = 1
        msg[5] = state
        val data = intArrayOf(0, CMD shr 8, CMD and 0xFF, MSG_LEN, 0) + msg
        return "xA101:json:{\"data\":[${data.joinToString(",")}]}"
    }

    private fun save(app: Context, sb: StringBuilder): File {
        val dir = File(app.getExternalFilesDir(null), "cluster").apply { mkdirs() }
        val file = File(dir, "etm_${FILE_STAMP.format(Date())}.txt")
        file.writeText(sb.toString())
        Log.i(TAG, "отчёт: ${file.absolutePath}")
        return file
    }

    private fun toast(app: Context, text: String) =
        main.post { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val STAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)
    private val FILE_STAMP = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private const val TAG = "CoolantEtm"
    private const val DIR = "/var/pps/spi_service"
    private const val TICKS = 10

    /** Атрибут xA101 = команда 0xA101 (RcDataSendThread, тип сообщения < 0x200). */
    private const val CMD = 0xA101
    /** sizeof(rc_msg_send_t). */
    private const val MSG_LEN = 52
    /** rc_msg_send_type для RcDataSendETMEntryState. */
    private const val MSG_ETM_ENTRY = 0x37
}
