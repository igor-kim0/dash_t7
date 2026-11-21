package com.dash.headunit.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.FileProvider
import com.dash.headunit.BuildConfig
import com.dash.headunit.navi.AdbShell
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Обновление приложения из GitHub Releases (без магазина).
 *
 * Проверка — `releases/latest` репозитория (предрелизы `-dev` туда не
 * попадают), ассет [APK_NAME] — его публикует release.yml. Скачанный APK
 * проверяется ([verify]): наш пакет, версия новее, та же подпись — иначе
 * установка стёрла бы настройки (чужая подпись) или ничего не дала.
 *
 * Установка ([install]):
 *  1. свой adb-канал ([AdbShell]) — `pm install -r` без вопросов, затем
 *     `am start` (процесс при установке убивается — команда идёт через
 *     setsid, чтобы пережить обрыв adb-сессии). Основной путь на ГУ: экрана
 *     «Неизвестные источники» там нет;
 *  2. иначе — системный установщик (ACTION_INSTALL_PACKAGE через
 *     FileProvider); без разрешения на установку система его не покажет.
 *
 * Все вызовы, кроме [install], блокирующие — только из фонового потока.
 */
object Updater {

    data class Release(
        val versionName: String,
        val versionCode: Int,
        val apkUrl: String,
        val notes: String?
    )

    /** Последний релиз или исключение с понятным текстом. */
    fun latest(): Release {
        val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IllegalStateException("GitHub ответил $code")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val name = json.optString("tag_name").removePrefix("v")
            val vc = versionCode(name) ?: throw IllegalStateException("непонятная версия «$name»")
            val assets = json.optJSONArray("assets")
            var url: String? = null
            for (i in 0 until (assets?.length() ?: 0)) {
                val a = assets!!.getJSONObject(i)
                if (a.optString("name") == APK_NAME) url = a.optString("browser_download_url")
            }
            url ?: throw IllegalStateException("в релизе $name нет $APK_NAME")
            return Release(name, vc, url, json.optString("body").takeIf { it.isNotBlank() })
        } finally {
            conn.disconnect()
        }
    }

    /** X.Y.Z → X·10000 + Y·100 + Z, как в build.gradle.kts; иначе null. */
    fun versionCode(name: String): Int? {
        val p = name.trim().split('.')
        if (p.size != 3) return null
        val (x, y, z) = p.map { it.toIntOrNull() ?: return null }
        if (y !in 0..99 || z !in 0..99) return null
        return x * 10000 + y * 100 + z
    }

    /**
     * Текст релиза (кусок CHANGELOG.md) для диалога: строки-продолжения
     * пунктов склеиваются (в файле перенос по ширине), «### Раздел» → «Раздел:»,
     * «- » → «• », хвост «**Full Changelog**…» от GitHub убирается.
     */
    fun formatNotes(md: String): String {
        val out = ArrayList<String>()
        for (raw in md.replace("\r", "").lines()) {
            val line = raw.trimEnd()
            val t = line.trim()
            when {
                t.isEmpty() -> if (out.isNotEmpty() && out.last().isNotEmpty()) out.add("")
                t.startsWith("**Full Changelog**") || t.startsWith("## What's Changed") -> break
                t.startsWith("#") -> {
                    if (out.isNotEmpty() && out.last().isNotEmpty()) out.add("")
                    out.add(t.trimStart('#').trim() + ":")
                }
                t.startsWith("- ") || t.startsWith("* ") -> out.add("• " + t.substring(2).trim())
                line.startsWith(" ") && out.isNotEmpty() && out.last().isNotEmpty() ->
                    out[out.lastIndex] = out.last() + " " + t
                else -> out.add(t)
            }
        }
        return out.joinToString("\n").trim()
    }

    fun isNewer(r: Release): Boolean = r.versionCode > BuildConfig.VERSION_CODE

    /**
     * Скачать APK в files/ota; [onProgress] — 0..100 (или −1, если размер
     * неизвестен). [cancelled] опрашивается между блоками.
     */
    fun download(context: Context, r: Release, cancelled: () -> Boolean, onProgress: (Int) -> Unit): File {
        val dir = otaDir(context)
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "tenet_t7_dash-${r.versionName}.apk")
        var url = r.apkUrl
        // GitHub отдаёт ассет редиректом на другой хост — ведём вручную (https→https).
        repeat(5) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 10_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    url = conn.getHeaderField("Location") ?: throw IllegalStateException("редирект без адреса")
                    return@repeat
                }
                if (code != HttpURLConnection.HTTP_OK) throw IllegalStateException("сервер ответил $code")
                val total = conn.contentLength.toLong()
                var done = 0L
                var lastPct = -2
                conn.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            if (cancelled()) throw InterruptedException("отменено")
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val pct = if (total > 0) (done * 100 / total).toInt() else -1
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
                if (total > 0 && done != total) throw IllegalStateException("файл скачан не полностью")
                return file
            } catch (e: Exception) {
                file.delete()
                throw e
            } finally {
                conn.disconnect()
            }
        }
        throw IllegalStateException("слишком много редиректов")
    }

    /** null — APK годится; иначе причина отказа. */
    fun verify(context: Context, apk: File): String? {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val info = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES)
            ?: return "файл не похож на APK"
        if (info.packageName != context.packageName) return "чужой пакет ${info.packageName}"
        @Suppress("DEPRECATION")
        val newCode = info.versionCode
        if (newCode <= BuildConfig.VERSION_CODE) return "версия ${info.versionName} не новее установленной"
        @Suppress("DEPRECATION")
        val mine = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        @Suppress("DEPRECATION")
        val theirs = info.signatures
        if (mine == null || theirs == null || !mine.contentEquals(theirs)) {
            return "подпись не совпадает с установленной версией (её ставили не из релиза?) — обновите вручную"
        }
        return null
    }

    /**
     * Запустить установку [apk] (проверенного [verify]). Вызывать из фона:
     * adb-путь сетевой. Возвращает, что произошло: при успехе adb-пути процесс
     * скоро будет убит установкой.
     */
    fun install(context: Context, apk: File): Result {
        if (AdbShell.exec(context, "echo ok")?.trim() == "ok") {
            val activity = "${context.packageName}/.MainActivity"
            AdbShell.exec(context, "rm -f $TMP_LOG")
            val started = AdbShell.spawn(
                context,
                "cp \"${apk.absolutePath}\" $TMP_APK && pm install -r $TMP_APK > $TMP_LOG 2>&1; " +
                    "rm -f $TMP_APK; am start -n $activity > /dev/null 2>&1"
            )
            if (!started) return Result.Failed("не удалось запустить pm install через adb")
            Log.i(TAG, "pm install запущен: ${apk.name}")
            // Удачная установка убивает процесс раньше; дожили — читаем, что сказал pm.
            val until = SystemClock.uptimeMillis() + ADB_WAIT_MS
            while (SystemClock.uptimeMillis() < until) {
                SystemClock.sleep(3_000)
                val log = AdbShell.exec(context, "cat $TMP_LOG 2>/dev/null")?.trim().orEmpty()
                if (log.contains("Failure") || log.contains("Error") || log.contains("Exception")) {
                    return Result.Failed("pm install: ${log.lines().last()}")
                }
            }
            return Result.Failed("установка через adb не завершилась за ${ADB_WAIT_MS / 1000} с")
        }
        // Запасной путь: системный установщик (нужно разрешение «неизвестные источники»).
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        @Suppress("DEPRECATION")
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setData(uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(Intent.EXTRA_RETURN_RESULT, false)
        return try {
            context.startActivity(intent)
            Result.SystemInstaller(context.packageManager.canRequestPackageInstalls())
        } catch (e: Exception) {
            Result.Failed("нет adb и системного установщика: ${e.javaClass.simpleName}")
        }
    }

    sealed class Result {
        /** Открыт системный установщик; [allowed] — есть ли у нас право на установку. */
        data class SystemInstaller(val allowed: Boolean) : Result()
        data class Failed(val reason: String) : Result()
    }

    fun networkUp(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        return cm.activeNetworkInfo?.isConnected == true
    }

    private fun otaDir(context: Context) =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "ota").apply { mkdirs() }

    private const val TAG = "Updater"
    private const val API_URL = "https://api.github.com/repos/igor-kim0/dash_t7/releases/latest"
    const val APK_NAME = "tenet_t7_dash-release.apk"
    private const val USER_AGENT = "tenet-t7-dash"
    private const val TMP_APK = "/data/local/tmp/dash_ota.apk"
    private const val TMP_LOG = "/data/local/tmp/dash_ota.log"
    private const val ADB_WAIT_MS = 90_000L
}
