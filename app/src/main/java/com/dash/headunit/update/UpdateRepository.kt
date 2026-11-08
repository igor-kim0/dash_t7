package com.dash.headunit.update

import android.content.Context
import android.util.JsonReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Получает информацию о последнем релизе из GitHub Releases API.
 *
 * Ищет ассет с именем [APK_NAME] и возвращает его прямую ссылку на скачивание,
 * версию (из tag_name), changelog и URL релиза.
 */
class UpdateRepository private constructor(
    private val owner: String,
    private val repo: String,
    private val apkName: String
) {

    companion object {
        /** Репозиторий этого проекта на GitHub. */
        private const val OWNER = "igor-kim0"
        private const val REPO = "dash_t7"
        /** Имя релизного APK (совпадает с archivesName в build.gradle.kts + -release.apk). */
        private const val APK_NAME = "tenet_t7_dash-release.apk"

        private val instance = UpdateRepository(OWNER, REPO, APK_NAME)

        fun get(context: Context): UpdateRepository = instance
    }

    private val apiUrl =
        "https://api.github.com/repos/$owner/$repo/releases/latest"

    /**
     * Запрашивает latest release и возвращает [UpdateInfo], если нашелся подходящий ассет.
     * Возвращает null, если релиза нет, ассета нет или сеть недоступна.
     */
    suspend fun latestRelease(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL(apiUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Tenet-t7-dash")
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                null
            } else {
                try {
                    val reader = JsonReader(InputStreamReader(connection.inputStream, "UTF-8"))
                    parseRelease(reader)
                } finally {
                    connection.disconnect()
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseRelease(reader: JsonReader): UpdateInfo? {
        reader.beginObject()
        var tagName: String? = null
        var body: String? = null
        var htmlUrl: String? = null
        var assets: List<Map<String, String>>? = null

        while (reader.hasNext()) {
            val name = reader.nextName()
            when (name) {
                "tag_name" -> tagName = reader.nextString()
                "body" -> body = reader.nextString()
                "html_url" -> htmlUrl = reader.nextString()
                "assets" -> assets = parseAssets(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        val versionName = tagName?.removePrefix("v") ?: return null
        val versionCode = parseVersionCode(versionName)
        val asset = assets?.firstOrNull { it["name"] == apkName }
        val apkUrl = asset?.get("browser_download_url") ?: return null

        return UpdateInfo(
            versionName = versionName,
            versionCode = versionCode,
            apkUrl = apkUrl,
            changelog = body?.takeIf { it.isNotEmpty() },
            releaseNotesUrl = htmlUrl?.takeIf { it.isNotEmpty() }
        )
    }

    private fun parseAssets(reader: JsonReader): List<Map<String, String>> {
        val result = mutableListOf<Map<String, String>>()
        reader.beginArray()
        while (reader.hasNext()) {
            reader.beginObject()
            val map = mutableMapOf<String, String>()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (name == "name" || name == "browser_download_url") {
                    map[name] = reader.nextString()
                } else {
                    reader.skipValue()
                }
            }
            reader.endObject()
            result += map
        }
        reader.endArray()
        return result
    }

    /** X.Y.Z → X·10000 + Y·100 + Z (как в build.gradle.kts). */
    private fun parseVersionCode(versionName: String): Int {
        val parts = versionName.split(".").map { it.trim() }
        if (parts.size < 3) return 0
        return (parts[0].toIntOrNull() ?: 0) * 10000 +
            (parts[1].toIntOrNull() ?: 0) * 100 +
            (parts[2].toIntOrNull() ?: 0)
    }
}
