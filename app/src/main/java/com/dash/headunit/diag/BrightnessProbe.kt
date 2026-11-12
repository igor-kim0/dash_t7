package com.dash.headunit.diag

import android.app.Activity
import android.content.Context
import android.provider.Settings
import android.util.Log
import com.dash.headunit.car.DesayCarReader
import com.dash.headunit.car.HuBrightness
import com.dash.headunit.navi.AdbShell
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Проба «Яркость ГУ»: какими путями приложение может менять подсветку экрана.
 * Кнопки — в CarDebugActivity. Лог — на экране и в files/brightness_log.txt.
 *
 * Пути:
 *  1. **Desay, как штатный ползунок** (SystemUI_T1D, widget/ScreenLumSeekBar).
 *     Состояние — getItemValues(5,1) = [режим, день/ночь, день−1, ночь−1]
 *     (уровни на ползунке 1..10, в ячейке 0..9). Запись sendBrightValue(p),
 *     p = уровень−1: setProperty(0x21414410, 0x2000000, [p, ночь−1]), затем
 *     setProperty(0x21404410, area, …): режим 0 → area = день/ночь, иначе
 *     area = 1 (день) / 2 (ночь); данные [p, ночь−1] днём и [день−1, p] ночью.
 *     Повторяем ровно это — общий код в [HuBrightness] (виджет «Яркость», заставка).
 *  2. **Settings.System.screen_brightness** — системная яркость Android
 *     (на ГУ диапазон дисплея 0..9, в настройке лежало 102 — вероятно,
 *     игнорируется). Нужен WRITE_SECURE_SETTINGS (pm grant) — иначе через adb.
 *  3. **Окно** — WindowManager.LayoutParams.screenBrightness (так гасит
 *     «Заставка»); действует только пока экран диагностики на переднем плане.
 *
 * «Вернуть как было» — значения, прочитанные при первом обращении к пробе.
 */
object BrightnessProbe {

    private const val TAG = "BrightnessProbe"

    private val io = Executors.newSingleThreadExecutor()
    private val lines = ArrayDeque<String>()
    private var reader: DesayCarReader? = null
    private var logFile: File? = null

    /** Исходное состояние для «Вернуть как было». */
    private var origDesay: IntArray? = null
    private var origSetting: Int? = null
    private var origRead = false

    /** Текущее состояние (обновляется [refresh]) — показывается на экране. */
    @Volatile
    var state: String = "—"
        private set

    fun logLines(): List<String> = synchronized(lines) { lines.toList() }

    private fun log(msg: String) {
        val line = "${STAMP.format(Date())} $msg"
        Log.i(TAG, msg)
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > 40) lines.removeFirst()
        }
        try {
            logFile?.appendText(line + "\n")
        } catch (_: Exception) {
        }
    }

    private fun desay(app: Context): DesayCarReader =
        reader ?: DesayCarReader(app).also { reader = it }

    private fun init(app: Context) {
        if (logFile == null) logFile = File(app.filesDir, "brightness_log.txt")
        if (!origRead) {
            origRead = true
            origDesay = desay(app).readRaw(5, 1)
            origSetting = readSetting(app)
            log("исходно: desay(5,1)=${origDesay?.contentToString()} setting=$origSetting")
        }
    }

    /** Перечитать состояние (с фонового потока). */
    fun refresh(context: Context) {
        val app = context.applicationContext
        io.execute { state = describe(app) }
    }

    private fun describe(app: Context): String {
        init(app)
        val d = desay(app).readRaw(5, 1)
        val sb = StringBuilder()
        sb.append("Desay (5,1) = ").append(d?.contentToString() ?: "нет")
        if (d != null && d.size >= 4) {
            sb.append("  → режим ${d[0]}, ${if (d[1] == 0) "день" else "ночь(${d[1]})"}, ")
            sb.append("день ${d[2] + 1}/10, ночь ${d[3] + 1}/10")
        }
        sb.append("\nscreen_brightness = ").append(readSetting(app) ?: "нет")
        sb.append(", mode = ").append(
            try {
                Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
            } catch (e: Exception) {
                "нет"
            }
        )
        sb.append("\nWRITE_SECURE_SETTINGS: ").append(if (hasSecure(app)) "есть" else "нет")
        sb.append(", WRITE_SETTINGS: ").append(if (Settings.System.canWrite(app)) "есть" else "нет")
        return sb.toString()
    }

    /** Путь 1: уровень 1..10, как штатный ползунок. */
    fun setDesay(context: Context, level: Int) {
        val app = context.applicationContext
        io.execute {
            init(app)
            val ok = HuBrightness.writeLevel(app, level)
            log("Desay: уровень $level ok=$ok (подробно — HuBrightness)")
            verifyLater(app)
        }
    }

    /** Путь 2: Settings.System.screen_brightness. */
    fun setSetting(context: Context, value: Int) {
        val app = context.applicationContext
        io.execute {
            init(app)
            val before = readSetting(app)
            val res = try {
                Settings.System.putInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
                "API ok"
            } catch (e: Exception) {
                val out = AdbShell.exec(app, "settings put system screen_brightness $value; " +
                    "settings get system screen_brightness")
                "API ${e.javaClass.simpleName}; adb: ${out?.trim() ?: "нет (${AdbShell.lastStatus})"}"
            }
            log("setting: $before → $value: $res, сейчас ${readSetting(app)}")
            verifyLater(app)
        }
    }

    /** Путь 3: яркость окна; value < 0 — сброс (как в системе). */
    fun setWindow(activity: Activity, value: Float) {
        val lp = activity.window.attributes
        lp.screenBrightness = value
        activity.window.attributes = lp
        log("окно: screenBrightness = ${if (value < 0) "сброс" else value}")
        refresh(activity)
    }

    fun restore(activity: Activity) {
        setWindow(activity, -1f)
        val app = activity.applicationContext
        io.execute {
            HuBrightness.State.parse(origDesay)?.let { o ->
                val ok = HuBrightness.writeBoth(app, o.dayLevel, o.nightLevel)
                log("вернуть Desay: день ${o.dayLevel} ночь ${o.nightLevel} ok=$ok")
            }
            origSetting?.let { v ->
                if (readSetting(app) != v) {
                    try {
                        Settings.System.putInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS, v)
                    } catch (e: Exception) {
                        AdbShell.exec(app, "settings put system screen_brightness $v")
                    }
                    log("вернуть setting: $v, сейчас ${readSetting(app)}")
                }
            }
            verifyLater(app)
        }
    }

    /** Через 0,5 и 2 с перечитать (5,1) — приняла ли машина запись. */
    private fun verifyLater(app: Context) {
        for (delay in longArrayOf(500, 2000)) {
            Thread.sleep(if (delay == 500L) 500 else 1500)
            val d = desay(app).readRaw(5, 1)
            log("  +${delay} мс: (5,1)=${d?.contentToString()} setting=${readSetting(app)}")
        }
        state = describe(app)
    }

    private fun readSetting(app: Context): Int? = try {
        Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    } catch (e: Exception) {
        null
    }

    private fun hasSecure(app: Context) =
        app.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private val STAMP = SimpleDateFormat("HH:mm:ss", Locale.US)
}
