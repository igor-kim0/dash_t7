package com.dash.headunit.diag

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.dash.headunit.car.ClusterParser
import com.dash.headunit.car.DesayCarReader
import com.dash.headunit.car.QnxLauncher
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SLA, путь В: знак на приборке через штатный «超速报警» (порог превышения).
 * Кнопка в CarDebugActivity.
 *
 * Из реверса sva.cluster (RcMTopBottomSpeedLimitUpdate): камеры TSR на машине
 * нет (FCM в a005 всегда пуст), но при бите a00d[7].7 приборка рисует знак с
 * числом = порог превышения из a00f[27]: км/ч = (raw + 5) × 5 (на машине 21 → 130).
 * Порог пишется Desay CarSetting 38 (ответ — 13), значение — тот же raw (63 = выкл);
 * штатное меню разрешает менять его только < 10 км/ч — проверяем, держит ли это машина.
 *
 * Тест: запоминает raw, ставит порог [TEST_KMH], [TICKS] с читает a00f[27] и a00d[7],
 * возвращает прежний raw. Запускать на ходу быстрее [TEST_KMH] (кнопку жмёт пассажир)
 * — тогда должен появиться знак «30». Отчёт — files/navi/overspeed_<время>.txt.
 */
object OverspeedSignTest {

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
                Log.w(TAG, "overspeed test", e)
                "Порог превышения: ошибка ${e.javaClass.simpleName}: ${e.message}"
            } finally {
                running = false
            }
            Log.i(TAG, verdict)
            toast(app, verdict)
        }.start()
    }

    private fun run(app: Context): String {
        val sb = StringBuilder()
        sb.append("# overspeed test ").append(STAMP.format(Date())).append('\n')
        if (!QnxLauncher.reachable()) return "Порог превышения: qconn не отвечает"
        val cat = QnxLauncher.find("cat")
        val sleep = QnxLauncher.find("sleep")
        if (cat == null || sleep == null) return "Порог превышения: на QNX нет cat/sleep"

        val desay = DesayCarReader(app)
        val before = readA00f(cat) ?: return "Порог превышения: a00f не прочитан"
        val original = before[RAW_BYTE]
        val desay13 = desay.readRaw(0, RESP_OVERSPEED)?.joinToString(",")
        sb.append("до: a00f[27]=").append(original).append(" (").append(kmh(original))
            .append(" км/ч), Desay(0,13)=").append(desay13).append('\n')

        val testRaw = TEST_KMH / 5 - 5
        toast(app, "Порог превышения → $TEST_KMH км/ч на $TICKS с, смотрите на приборку")
        sb.append("пишу (0,38,").append(testRaw).append("): ").append(desay.write(0, REQ_OVERSPEED, testRaw)).append('\n')

        val out = try {
            val script = "n=0; while [ \$n -lt $TICKS ]; do echo \"@@t\$n\"; " +
                "echo '@a00f'; $cat $DIR/a00f; echo '@a00d'; $cat $DIR/a00d; $sleep 1; n=\$((n+1)); done\n"
            QnxLauncher.sh(script, timeoutMs = (TICKS + 10) * 1000) ?: ""
        } finally {
            sb.append("возврат (0,38,").append(original).append("): ")
                .append(desay.write(0, REQ_OVERSPEED, original)).append('\n')
        }

        // Тики: порог и бит «превышение» a00d[7].7.
        var obj: String? = null
        var accepted = false
        var signBit = false
        for (line in out.lines().map { it.trim() }) {
            when {
                line.startsWith("@@") -> sb.append(line).append(": ")
                line.startsWith("@") -> obj = line.substring(1)
                line.startsWith("data:json:") -> {
                    val b = ClusterParser.parseData(line) ?: continue
                    if (obj == "a00f" && b.size > RAW_BYTE) {
                        sb.append("a00f[27]=").append(b[RAW_BYTE]).append(' ')
                        if (b[RAW_BYTE] == testRaw) accepted = true
                    }
                    if (obj == "a00d" && b.size > 15) {
                        sb.append("a00d[5,6,7,9,15]=").append(listOf(b[5], b[6], b[7], b[9], b[15])).append('\n')
                        if (b[7] and 0x80 != 0) signBit = true
                    }
                }
            }
        }
        Thread.sleep(1500)
        val after = readA00f(cat)?.get(RAW_BYTE)
        sb.append("после возврата: a00f[27]=").append(after).append('\n')
        val file = save(app, sb)
        val restored = if (after == original) "возвращён" else "НЕ возвращён ($after, было $original)"
        return "Порог превышения: машина ${if (accepted) "приняла" else "НЕ приняла"} $TEST_KMH км/ч, " +
            "бит превышения ${if (signBit) "был" else "не было"}, порог $restored. " +
            "Скажите, был ли знак. Отчёт: ${file.name}"
    }

    private fun readA00f(cat: String): IntArray? {
        val out = QnxLauncher.sh("$cat $DIR/a00f") ?: return null
        val line = out.lines().firstOrNull { it.trim().startsWith("data:json:") } ?: return null
        return ClusterParser.parseData(line)?.takeIf { it.size > RAW_BYTE }
    }

    private fun kmh(raw: Int) = if (raw == RAW_OFF) "выкл" else ((raw + 5) * 5).toString()

    private fun save(app: Context, sb: StringBuilder): File {
        val dir = File(app.getExternalFilesDir(null), "navi").apply { mkdirs() }
        val file = File(dir, "overspeed_${FILE_STAMP.format(Date())}.txt")
        file.writeText(sb.toString())
        Log.i(TAG, "отчёт: ${file.absolutePath}")
        return file
    }

    private fun toast(app: Context, text: String) =
        main.post { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }

    private val main = Handler(Looper.getMainLooper())
    private val STAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)
    private val FILE_STAMP = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private const val TAG = "OverspeedSign"
    private const val DIR = "/var/pps/spi_service"
    private const val TICKS = 15
    private const val TEST_KMH = 30
    /** a00f: байт порога превышения (RcDataMgr::getOverSpeed). */
    private const val RAW_BYTE = 27
    private const val RAW_OFF = 63
    /** CarSettingID: Request CAR_OVER_SPEED_ALRMING / Response. */
    private const val REQ_OVERSPEED = 38
    private const val RESP_OVERSPEED = 13
}
