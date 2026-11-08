package com.dash.headunit.car

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Журнал команд климата в файл — для разбора на ГУ без adb logcat:
 * files/climate_log/climate_<дата>.txt, в файле не больше [MAX_LINES] строк.
 * Пишут [ClimateControl] (команды, проверка, дожим «выкл») и [HvacShade] (hold/wake).
 */
object ClimateLog {

    private const val TAG = "ClimateLog"
    private const val DIR = "climate_log"
    private const val MAX_LINES = 1000

    private val lock = Any()
    private val day = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun write(context: Context, line: String) {
        try {
            synchronized(lock) {
                val now = Date()
                val dir = File(context.applicationContext.filesDir, DIR).apply { mkdirs() }
                val file = File(dir, "climate_${day.format(now)}.txt")
                file.appendText("${time.format(now)} $line\n")
                if (file.length() > MAX_LINES * 160L) {
                    val lines = file.readLines()
                    if (lines.size > MAX_LINES) file.writeText(lines.takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "write", e)
        }
    }
}
