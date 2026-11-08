package com.dash.headunit.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Устанавливает скачанный APK.
 *
 * Основной путь — [PackageInstaller] (сессия установки, системный диалог
 * подтверждения, работает на API 21+). Фолбэк — стандартный установщик
 * пакетов через [Intent.ACTION_VIEW] с [FileProvider].
 */
class UpdateInstaller(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val workerThread = HandlerThread("ota-installer").apply { start() }
    private val workerHandler = Handler(workerThread.looper)

    companion object {
        /** APK authority FileProvider — должно совпадать с манифестом. */
        private const val FILE_PROVIDER_AUTHORITY = "com.dash.headunit.fileprovider"
    }

    /**
     * Устанавливает [apk]. [onResult] вызывается в главном потоке:
     * true — сессия запущена/интент отправлен, false — ошибка.
     */
    fun install(apk: File, onResult: (Boolean) -> Unit) {
        workerHandler.post {
            val success = try {
                installPackage(apk)
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
            mainHandler.post { onResult(success) }
        }
    }

    private fun installPackage(apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tryInstallViaPackageInstaller(apk)?.let { return }
        }
        // Фолбэк на старых устройствах или если PackageInstaller недоступен.
        installViaIntent(apk)
    }

    @Suppress("DEPRECATION")
    private fun tryInstallViaPackageInstaller(apk: File): Boolean {
        val packageInstaller: PackageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )

        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)

        // Пишем APK в сессию установки.
        val apkFileSize = apk.length()
        val out = session.openWrite("app.apk", 0, apkFileSize)
        copyFile(FileInputStream(apk), out)
        out.close()

        val intent = Intent(context.applicationContext, InstallReceiver::class.java)
        val pendingIntent =
            android.app.PendingIntent.getBroadcast(
                context.applicationContext, sessionId, intent,
                android.app.PendingIntent.FLAG_IMMUTABLE
            )
        session.commit(pendingIntent.intentSender)
        session.close()
        return true
    }

    @Suppress("DEPRECATION")
    private fun installViaIntent(apk: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, FILE_PROVIDER_AUTHORITY, apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        } else {
            throw IOException("Нет приложения для установки APK")
        }
    }

    private fun copyFile(input: java.io.InputStream, output: OutputStream) {
        val buffer = ByteArray(8192)
        var read: Int
        while (true) {
            read = input.read(buffer)
            if (read == -1) break
            output.write(buffer, 0, read)
        }
    }

    fun release() {
        workerThread.quit()
    }
}

/** Приёмник результата сессии PackageInstaller (заглушка — результат приходит в системном диалоге). */
class InstallReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
        // Сессия коммитится системой; пользователю показывается диалог подтверждения установки.
    }
}
