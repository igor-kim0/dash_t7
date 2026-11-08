package com.dash.headunit.update

import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
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

    /** Есть ли активное сетевое соединение (Wi-Fi/мобильный/ETHERNET). */
    fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork
            if (network == null) return false
            val capabilities = cm.getNetworkCapabilities(network)
            return capabilities != null && (
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            )
        }
        @Suppress("DEPRECATION")
        val networkInfo = cm.activeNetworkInfo
        return networkInfo != null && networkInfo.isConnected
    }

    /** Результат проверки обновлений. */
    sealed class UpdateCheckResult {
        /** Обновлений нет — установлена последняя версия. */
        object NoUpdate : UpdateCheckResult()
        /** Доступно новое обновление. */
        data class UpdateAvailable(val info: UpdateInfo) : UpdateCheckResult()
        /** Ошибка при проверке (сеть, API, парсинг). */
        data class Error(val message: String?) : UpdateCheckResult()
    }

    /**
     * Проверяет наличие нового релиза на GitHub.
     * [onResult] вызывается в главном потоке с результатом проверки.
     */
    fun checkForUpdates(onResult: (UpdateCheckResult) -> Unit) {
        scope.launch {
            val info = repo.latestRelease(context)
            mainHandler.post {
                when {
                    info != null && info.isNewerThan(currentVersionCode(context)) ->
                        onResult(UpdateCheckResult.UpdateAvailable(info))
                    info == null ->
                        onResult(UpdateCheckResult.Error(null))
                    else ->
                        onResult(UpdateCheckResult.NoUpdate)
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
