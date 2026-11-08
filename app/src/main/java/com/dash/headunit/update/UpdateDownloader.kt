package com.dash.headunit.update

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Скачивает APK обновления в каталог приложения (без дополнительных разрешений:
 * внешние файлы приложения доступны на API 28 без WRITE_EXTERNAL_STORAGE).
 *
 * [onProgress] вызывается с долей прогресса 0f..1f. Возвращает File скачанного APK.
 */
class UpdateDownloader private constructor() {

    companion object {
        private val instance = UpdateDownloader()
        fun get(): UpdateDownloader = instance

        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val BUFFER_SIZE = 16 * 1024
    }

    /**
     * @param apkUrl прямая ссылка на APK.
     * @param destinationDir куда сохранить файл (обычно context.getExternalFilesDir(null)).
     * @param fileName имя итогового файла.
     * @param onProgress доля прогресса (0..1), может быть null.
     * @return File со скачанным APK.
     */
    suspend fun download(
        apkUrl: String,
        destinationDir: File,
        fileName: String,
        onProgress: ((Float) -> Unit)? = null
    ): File = withContext(Dispatchers.Default) {
        destinationDir.mkdirs()
        val file = File(destinationDir, fileName)

        val connection = URL(apkUrl).openConnection() as HttpURLConnection
        connection.apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", "Tenet-t7-dash")
        }

        val contentLength = connection.contentLength
        var downloaded = 0L

        try {
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var read: Int
                    while (true) {
                        read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read.toLong()
                        onProgress?.invoke(calculateProgress(downloaded, contentLength.toLong()))
                    }
                    output.flush()
                }
            }
            file
        } catch (e: CancellationException) {
            file.delete()
            throw e
        } catch (e: Exception) {
            file.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    private fun calculateProgress(downloaded: Long, contentLength: Long): Float =
        if (contentLength > 0) (downloaded.toFloat() / contentLength).coerceIn(0f, 1f) else 0f

    /** Возвращает каталог внешних файлов приложения (без разрешений на запись во внешнюю память). */
    fun appExternalFilesDir(context: Context): File {
        val external = context.getExternalFilesDir(null)
        return if (external != null) external else context.cacheDir
    }
}
