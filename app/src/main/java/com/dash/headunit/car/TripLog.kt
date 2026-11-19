package com.dash.headunit.car

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Диагностический лог борткомпьютера (поездки, моточасы, жизнь процесса) —
 * разбор «иногда не считается». Включается и выключается кнопкой на экране
 * диагностики ([CarDebugActivity]); пока выключен — на диск не пишется ничего.
 * Флаг в prefs: переживает убийство процесса, чтобы в логе было видно и то,
 * что случилось после перезапуска.
 *
 * Файлы: <externalFilesDir>/trip_log/trip_<дата>.txt
 * (adb pull /sdcard/Android/data/com.dash.headunit/files/trip_log/),
 * в файле не больше [MAX_LINES] строк.
 */
object TripLog {

    private const val TAG = "TripLog"
    private const val PREFS = "trip_log"
    private const val KEY_ON = "on"
    private const val DIR = "trip_log"
    private const val MAX_LINES = 5000

    @Volatile
    private var app: Context? = null

    @Volatile
    var enabled = false
        private set

    private val lock = Any()
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "TripLog").apply { isDaemon = true } }
    private val day = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Прочитать флаг (один раз за процесс). */
    fun init(context: Context) {
        if (app != null) return
        val a = context.applicationContext
        enabled = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)
        app = a
    }

    fun isOn(context: Context): Boolean {
        init(context)
        return enabled
    }

    fun setEnabled(context: Context, on: Boolean) {
        val app = context.applicationContext
        init(app)
        if (!on) write("=== лог выключен ===")
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).commit()
        enabled = on
        if (on) {
            write("=== лог включён: ${processInfo(app)} ===")
            exec.execute {
                // debugLines читает prefs — не в главном потоке.
                runCatching { TripTracker.debugLines(app) }.getOrNull()?.forEach { append(app, it) }
            }
        }
    }

    /**
     * Строка в лог (асинхронно). Выключен или [init] ещё не было (юнит-тесты) —
     * ничего. Строку собирать только под `if (TripLog.enabled)` / лямбдой.
     */
    fun write(line: String) {
        if (!enabled) return
        val app = app ?: return
        val stamped = stamp(line)
        try {
            exec.execute { appendRaw(app, stamped) }
        } catch (e: Exception) {
            Log.w(TAG, "queue", e)
        }
    }

    /** То же, строка считается только при включённом логе. */
    inline fun write(line: () -> String) {
        if (enabled) write(line())
    }

    /** Синхронная запись — для падения процесса (очередь может не успеть). */
    fun writeNow(line: String) {
        if (!enabled) return
        appendRaw(app ?: return, stamp(line))
    }

    /** pid, аптайм, загрузка — шапка «процесс поднят». */
    fun processInfo(context: Context): String {
        val boot = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        } catch (e: Exception) {
            -1
        }
        return "pid=${Process.myPid()} boot=$boot uptime=${SystemClock.elapsedRealtime() / 1000}s " +
            "awake=${SystemClock.uptimeMillis() / 1000}s"
    }

    /** Последние [n] строк сегодняшнего (или последнего) файла. Блокирующий. */
    fun tail(context: Context, n: Int): List<String> = try {
        synchronized(lock) {
            val f = files(context).lastOrNull() ?: return emptyList()
            f.readLines().takeLast(n)
        }
    } catch (e: Exception) {
        listOf("ошибка чтения: ${e.message}")
    }

    /** Сколько строк во всех файлах и где они лежат. Блокирующий. */
    fun summary(context: Context): String = try {
        synchronized(lock) {
            val fs = files(context)
            "${fs.size} файл(ов), ${fs.sumOf { it.length() } / 1024} КБ — ${dir(context).absolutePath}"
        }
    } catch (e: Exception) {
        "—"
    }

    /** Удалить все файлы лога. */
    fun clear(context: Context) {
        val app = context.applicationContext
        exec.execute {
            synchronized(lock) { files(app).forEach { it.delete() } }
        }
    }

    private fun stamp(line: String): String =
        synchronized(time) { "${time.format(Date())} [${Process.myPid()}] $line" }

    private fun append(context: Context, line: String) = appendRaw(context, stamp(line))

    private fun appendRaw(context: Context, stamped: String) {
        try {
            synchronized(lock) {
                val dir = dir(context).apply { mkdirs() }
                val name = synchronized(day) { day.format(Date()) }
                val file = File(dir, "trip_$name.txt")
                file.appendText("$stamped\n")
                if (file.length() > MAX_LINES * 200L) {
                    val lines = file.readLines()
                    if (lines.size > MAX_LINES) {
                        file.writeText(lines.takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "write", e)
        }
    }

    private fun dir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, DIR)

    private fun files(context: Context): List<File> =
        dir(context).listFiles { f -> f.name.startsWith("trip_") && f.name.endsWith(".txt") }
            ?.sortedBy { it.name } ?: emptyList()
}
