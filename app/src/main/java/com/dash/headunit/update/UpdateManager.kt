package com.dash.headunit.update

import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.dash.headunit.R
import com.dash.headunit.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * Оркестратор OTA-обновлений: проверка релиза, скачивание APK, установка.
 *
 * Экземпляр привязан к жизненному циклу экрана настроек (создаётся в
 * [com.dash.headunit.MainActivity.showSettingsDialog] и освобождается при закрытии диалога).
 */
class UpdateManager(private val context: Context) {

    private val repo = UpdateRepository.get(context)
    private val downloader = UpdateDownloader.get()
    private val updateJob = Job()
    private val scope = CoroutineScope(Dispatchers.Default + updateJob)

    private var installer: UpdateInstaller? = null
    private var progressDialog: ProgressDialog? = null
    private var currentApk: File? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Проверяет наличие нового релиза на GitHub.
     * [onResult] — true если обновление доступно (с [UpdateInfo]), false если нет/ошибка.
     */
    fun checkForUpdates(onResult: (Boolean, UpdateInfo?) -> Unit) {
        scope.launch {
            val info = repo.latestRelease(context)
            mainHandler.post {
                if (info != null && info.isNewerThan(currentVersionCode(context))) {
                    onResult(true, info)
                } else {
                    onResult(false, null)
                }
            }
        }
    }

    /**
     * Показывает диалог с предложением обновиться. При согласии — скачивает APK
     * и устанавливает. [changelog] — текст релиза для отображения.
     */
    fun offerUpdate(activity: androidx.fragment.app.FragmentActivity, info: UpdateInfo) {
        val skipped = AppSettings.skippedUpdateVersion(context)
        if (skipped == info.versionName) {
            // Пользователь уже пропускал эту версию — не надоедаем.
            return
        }

        val builder = AlertDialog.Builder(activity)
        builder.setTitle(R.string.ota_update_available)
        builder.setMessage(
            context.getString(
                R.string.ota_update_message,
                info.versionName,
                info.changelog ?: context.getString(R.string.ota_no_changelog)
            )
        )
        builder.setPositiveButton(R.string.ota_download) { _, _ ->
            downloadAndInstall(activity, info)
        }
        builder.setNegativeButton(R.string.ota_skip) { _, _ ->
            AppSettings.setSkippedUpdateVersion(context, info.versionName)
        }
        builder.setOnCancelListener {
            AppSettings.setSkippedUpdateVersion(context, info.versionName)
        }
        builder.show()
    }

    private fun downloadAndInstall(activity: androidx.fragment.app.FragmentActivity, info: UpdateInfo) {
        progressDialog = ProgressDialog(activity)
        progressDialog!!.setTitle(R.string.ota_downloading)
        progressDialog!!.setMessage(context.getString(R.string.ota_downloading_apk, info.versionName))
        progressDialog!!.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
        progressDialog!!.setMax(100)
        progressDialog!!.setCancelable(false)
        progressDialog!!.show()

        scope.launch {
            try {
                val dir = downloader.appExternalFilesDir(context)
                val fileName = "tenet_t7_dash-${info.versionName}-release.apk"
                currentApk = downloader.download(
                    apkUrl = info.apkUrl,
                    destinationDir = dir,
                    fileName = fileName,
                    onProgress = { progress ->
                        mainHandler.post {
                            progressDialog!!.setProgress((progress * 100).toInt())
                        }
                    }
                )
                mainHandler.post {
                    progressDialog!!.dismiss()
                    progressDialog = null
                }
                installApk(activity, currentApk!!)
            } catch (e: Exception) {
                e.printStackTrace()
                mainHandler.post {
                    progressDialog?.dismiss()
                    progressDialog = null
                    Toast.makeText(
                        context,
                        context.getString(R.string.ota_download_failed, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun installApk(activity: androidx.fragment.app.FragmentActivity, apk: File) {
        installer = UpdateInstaller(context)
        installer!!.install(apk) { success ->
            mainHandler.post {
                if (success) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.ota_installing),
                        Toast.LENGTH_LONG
                    ).show()
                    // При установке поверх своей же подписи система предложит
                    // подтверждение; после успешной установки процесс будет
                    // перезапущен системой.
                } else {
                    Toast.makeText(
                        context,
                        R.string.ota_install_failed,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /** Освободить ресурсы (вызывать при закрытии экрана настроек). */
    fun release() {
        updateJob.cancel()
        installer?.release()
        installer = null
        progressDialog?.dismiss()
        progressDialog = null
        currentApk = null
    }

    /** Текущая версия-код приложения. */
    private fun currentVersionCode(context: Context): Int {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
        } catch (e: Exception) {
            0
        }
    }
}
