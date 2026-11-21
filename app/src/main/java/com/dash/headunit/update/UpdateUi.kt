package com.dash.headunit.update

import android.app.Activity
import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.widget.Toast
import com.dash.headunit.BuildConfig
import com.dash.headunit.R
import com.dash.headunit.settings.AppSettings
import com.dash.headunit.ui.DashDialog

/**
 * Диалоги обновления ([Updater]): ручная проверка из настроек, тихая
 * автопроверка при старте (не чаще [AUTO_GAP_MS]), предложение версии,
 * скачивание с процентом, установка.
 */
class UpdateUi(private val activity: Activity) {

    private val main = Handler(Looper.getMainLooper())

    /** «Проверить обновления»: результат — всегда виден. */
    fun checkManual() {
        if (!Updater.networkUp(activity)) return toast(activity.getString(R.string.ota_no_network))
        toast(activity.getString(R.string.ota_checking), short = true)
        check(manual = true)
    }

    /** При старте: молча, только если есть новая непропущенная версия. */
    fun checkAuto() {
        if (!AppSettings.otaAutoCheck(activity) || busy) return
        val now = System.currentTimeMillis()
        if (now - AppSettings.otaLastCheckTime(activity) < AUTO_GAP_MS) return
        if (!Updater.networkUp(activity)) return
        check(manual = false)
    }

    private fun check(manual: Boolean) {
        if (busy) return
        busy = true
        Thread({
            val result = runCatching { Updater.latest() }
            busy = false
            main.post {
                if (activity.isFinishing) return@post
                val r = result.getOrElse {
                    if (manual) toast(activity.getString(R.string.ota_check_failed, it.message ?: it.javaClass.simpleName))
                    return@post
                }
                AppSettings.setOtaLastCheckTime(activity, System.currentTimeMillis())
                when {
                    !Updater.isNewer(r) ->
                        if (manual) toast(activity.getString(R.string.ota_up_to_date, BuildConfig.VERSION_NAME))
                    !manual && AppSettings.skippedUpdateVersion(activity) == r.versionName -> Unit
                    else -> offer(r, manual)
                }
            }
        }, "ota-check").start()
    }

    private fun offer(r: Updater.Release, manual: Boolean) {
        val notes = r.notes?.let(Updater::formatNotes)?.takeIf { it.isNotEmpty() }?.let { if (it.length > NOTES_MAX) it.take(NOTES_MAX) + "…" else it }
            ?: activity.getString(R.string.ota_no_changelog)
        val b = DashDialog.Builder(activity)
            .setIcon(R.drawable.ic_cloud)
            .setTitle(activity.getString(R.string.ota_update_available, r.versionName))
            .setMessage(activity.getString(R.string.ota_update_message, BuildConfig.VERSION_NAME, notes))
            .setPositiveButton(R.string.ota_download) { _, _ -> download(r) }
            .setNegativeButton(R.string.ota_later, null)
        if (!manual) {
            b.setNeutralButton(R.string.ota_skip) { _, _ -> AppSettings.setSkippedUpdateVersion(activity, r.versionName) }
        }
        b.showFullScreen()
    }

    private fun download(r: Updater.Release) {
        if (busy) return
        busy = true
        var cancelled = false
        val text = TextView(activity).apply {
            textSize = 20f
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
            text = activity.getString(R.string.ota_downloading, r.versionName, 0)
        }
        val dialog: Dialog = DashDialog.Builder(activity)
            .setIcon(R.drawable.ic_cloud)
            .setTitle(activity.getString(R.string.ota_update_available, r.versionName))
            .setView(text)
            .setNegativeButton(R.string.dialog_cancel) { _, _ -> cancelled = true }
            .showFullScreen()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener { cancelled = true }
        fun say(s: String) = main.post { text.text = s }
        Thread({
            try {
                val apk = Updater.download(activity, r, { cancelled }) { pct ->
                    say(
                        if (pct >= 0) activity.getString(R.string.ota_downloading, r.versionName, pct)
                        else activity.getString(R.string.ota_downloading_nosize, r.versionName)
                    )
                }
                Updater.verify(activity, apk)?.let { throw IllegalStateException(it) }
                say(activity.getString(R.string.ota_installing))
                when (val res = Updater.install(activity, apk)) {
                    is Updater.Result.SystemInstaller -> main.post {
                        dialog.dismiss()
                        if (!res.allowed) toast(activity.getString(R.string.ota_need_permission))
                    }
                    is Updater.Result.Failed -> throw IllegalStateException(res.reason)
                }
            } catch (e: Exception) {
                main.post {
                    dialog.dismiss()
                    if (!cancelled) toast(activity.getString(R.string.ota_failed, e.message ?: e.javaClass.simpleName))
                }
            } finally {
                busy = false
            }
        }, "ota-download").start()
    }

    private fun toast(s: String, short: Boolean = false) =
        Toast.makeText(activity, s, if (short) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()

    companion object {
        @Volatile private var busy = false
        private const val AUTO_GAP_MS = 20 * 60 * 60 * 1000L
        private const val NOTES_MAX = 1500
    }
}
