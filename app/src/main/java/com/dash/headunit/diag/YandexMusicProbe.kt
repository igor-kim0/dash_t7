package com.dash.headunit.diag

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.util.Log
import android.widget.Toast
import com.dash.headunit.music.MediaListenerService
import com.dash.headunit.music.YandexMusicHub
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Проба «Яндекс Музыка: пускает ли браузер и что в дереве». Кнопка в CarDebugActivity.
 *
 * Виджет берёт коллекции из MediaBrowserService Яндекс Музыки, но сервис
 * может отдавать корень только Android Auto / Яндекс.Авто (onGetRoot → null
 * → onConnectionFailed). Здесь: список сервисов пакета, отдельное
 * подключение своим браузером (ждём [CONNECT_MS]), корень и его extras,
 * дети корня и по [MAX_DEEP] детей у первых папок, метаданные активной
 * сессии и сессии браузера (какие ключи обложки заполнены). Состояние
 * [YandexMusicHub] — для сравнения. Отчёт — files/ymusic/probe_<время>.txt.
 */
object YandexMusicProbe {

    @Volatile
    var running = false
        private set

    fun start(context: Context) {
        if (running) return
        running = true
        val app = context.applicationContext
        toast(app, "Проба Яндекс Музыки: ~15 с…")
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            val verdict = try {
                run(app)
            } catch (e: Throwable) {
                Log.w(TAG, "probe", e)
                "Проба Яндекс Музыки: ошибка ${e.javaClass.simpleName}: ${e.message}"
            } finally {
                running = false
            }
            Log.i(TAG, verdict)
            toast(app, verdict)
        }
    }

    private suspend fun run(app: Context): String {
        val sb = StringBuilder()
        sb.append("# yandex music probe ").append(STAMP.format(Date())).append('\n')
        for (pkg in listOf(YandexMusicHub.PKG, YandexMusicHub.PKG_ALT)) {
            val ver = try {
                app.packageManager.getPackageInfo(pkg, 0).versionName
            } catch (e: Exception) {
                null
            }
            sb.append("пакет ").append(pkg).append(": ").append(ver ?: "нет").append('\n')
        }
        val services = YandexMusicHub.findServices(app)
        sb.append("MediaBrowserService: ").append(services.joinToString { it.flattenToShortString() }.ifEmpty { "нет" }).append('\n')
        sb.append("хаб: browser=").append(YandexMusicHub.browserState)
            .append(" fails=").append(YandexMusicHub.browserFailures)
            .append(" controller=").append(YandexMusicHub.hasController)
            .append(" noAccess=").append(YandexMusicHub.noAccess)
            .append(" level=").append(YandexMusicHub.level)
            .append(" collections=").append(YandexMusicHub.collections.size)
            .append(" icons=").append(YandexMusicHub.collections.count { it.icon != null })
            .append('\n')
        sb.append("хаб data: ").append(YandexMusicHub.data?.let { "«${it.title}» art=${it.art?.let { b -> "${b.width}x${b.height}" }} uri=${it.artUri}" } ?: "нет").append('\n')

        sb.append("\n== активные сессии\n")
        try {
            val mgr = app.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val list = mgr.getActiveSessions(ComponentName(app, MediaListenerService::class.java))
            if (list.isEmpty()) sb.append("(нет)\n")
            list.forEach { c ->
                sb.append("- ").append(c.packageName).append(" state=").append(c.playbackState?.state).append('\n')
                if (c.packageName == YandexMusicHub.PKG || c.packageName == YandexMusicHub.PKG_ALT) {
                    describeController(sb, c, "  ")
                }
            }
        } catch (e: SecurityException) {
            sb.append("(нет доступа к уведомлениям)\n")
        } catch (e: Exception) {
            sb.append("(ошибка: ${e.javaClass.simpleName}: ${e.message})\n")
        }

        var verdict = "Яндекс Музыка не установлена"
        val service = services.firstOrNull()
        if (service != null) {
            sb.append("\n== свой браузер ").append(service.flattenToShortString()).append('\n')
            verdict = browse(app, service, sb)
        }
        val file = save(app, sb)
        return "$verdict. Отчёт: ${file.absolutePath}"
    }

    /** Подключиться, пройти корень и первые папки; вернуть вывод одной строкой. */
    private suspend fun browse(app: Context, service: ComponentName, sb: StringBuilder): String {
        var browser: MediaBrowser? = null
        val result = withTimeoutOrNull(CONNECT_MS) {
            suspendCancellableCoroutine<String> { cont ->
                val b = MediaBrowser(app, service, object : MediaBrowser.ConnectionCallback() {
                    override fun onConnected() {
                        if (cont.isActive) cont.resume("connected")
                    }

                    override fun onConnectionFailed() {
                        if (cont.isActive) cont.resume("failed")
                    }

                    override fun onConnectionSuspended() {
                        if (cont.isActive) cont.resume("suspended")
                    }
                }, null)
                browser = b
                b.connect()
            }
        } ?: "timeout"
        val b = browser
        sb.append("подключение: ").append(result).append('\n')
        if (result != "connected" || b == null) {
            try {
                b?.disconnect()
            } catch (e: Exception) {
                // ignore
            }
            return when (result) {
                "failed" -> "Браузер НЕ пускает (onGetRoot → null): коллекций не будет"
                "timeout" -> "Браузер не ответил за ${CONNECT_MS / 1000} с"
                else -> "Браузер: $result"
            }
        }
        try {
            sb.append("root=").append(b.root).append(" extras=").append(bundle(b.extras)).append('\n')
            try {
                describeController(sb, MediaController(app, b.sessionToken), "сессия браузера: ")
            } catch (e: Exception) {
                sb.append("сессия браузера: ошибка ${e.message}\n")
            }
            val root = children(b, b.root)
            sb.append("\n-- корень: ").append(root?.size ?: "нет ответа").append('\n')
            root?.forEach { sb.append(item(it, "  ")) }
            root?.filter { it.isBrowsable }?.take(MAX_FOLDERS)?.forEach { folder ->
                val kids = children(b, folder.mediaId ?: return@forEach)
                sb.append("\n-- «").append(folder.description.title).append("»: ")
                    .append(kids?.size ?: "нет ответа").append('\n')
                kids?.take(MAX_DEEP)?.forEach { sb.append(item(it, "  ")) }
            }
            val n = root?.size ?: 0
            val icons = root?.count { it.description.iconUri != null || it.description.iconBitmap != null } ?: 0
            return "Браузер пускает: в корне $n узлов, с иконками $icons"
        } finally {
            try {
                b.disconnect()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private suspend fun children(b: MediaBrowser, id: String): List<MediaBrowser.MediaItem>? =
        withTimeoutOrNull(CHILDREN_MS) {
            suspendCancellableCoroutine { cont ->
                val cb = object : MediaBrowser.SubscriptionCallback() {
                    override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowser.MediaItem>) {
                        if (cont.isActive) cont.resume(children.toList())
                    }

                    override fun onError(parentId: String) {
                        if (cont.isActive) cont.resume(emptyList())
                    }
                }
                b.subscribe(id, cb)
                cont.invokeOnCancellation { }
            }
        }.also {
            try {
                b.unsubscribe(id)
            } catch (e: Exception) {
                // ignore
            }
        }

    private fun item(it: MediaBrowser.MediaItem, indent: String): String {
        val d = it.description
        val kind = (if (it.isBrowsable) "B" else "") + (if (it.isPlayable) "P" else "")
        return "$indent[$kind] «${d.title}» id=${it.mediaId} sub=${d.subtitle} " +
            "icon=${d.iconUri ?: "-"} bmp=${d.iconBitmap?.let { b -> "${b.width}x${b.height}" } ?: "-"} " +
            "extras=${bundle(d.extras)}\n"
    }

    private fun describeController(sb: StringBuilder, c: MediaController, prefix: String) {
        val st = c.playbackState
        sb.append(prefix).append("state=").append(st?.state).append(" actions=0x")
            .append(java.lang.Long.toHexString(st?.actions ?: 0)).append('\n')
        val md = c.metadata
        if (md == null) {
            sb.append(prefix).append("metadata: нет\n")
            return
        }
        sb.append(prefix).append("«").append(md.getString(MediaMetadata.METADATA_KEY_TITLE)).append("» — ")
            .append(md.getString(MediaMetadata.METADATA_KEY_ARTIST)).append('\n')
        for (k in ART_KEYS) {
            val bm = md.getBitmap(k)
            val s = md.getString(k)
            if (bm != null || s != null) {
                sb.append(prefix).append("  ").append(k).append(" = ")
                    .append(bm?.let { "bitmap ${it.width}x${it.height}" } ?: s).append('\n')
            }
        }
        sb.append(prefix).append("  keys: ").append(md.keySet().sorted().joinToString()).append('\n')
    }

    private fun bundle(b: android.os.Bundle?): String {
        if (b == null || b.isEmpty) return "{}"
        return b.keySet().sorted().joinToString(prefix = "{", postfix = "}") { k ->
            @Suppress("DEPRECATION")
            "$k=${b.get(k)}"
        }
    }

    private fun save(app: Context, sb: StringBuilder): File {
        val dir = File(app.getExternalFilesDir(null), "ymusic").apply { mkdirs() }
        val file = File(dir, "probe_${FILE_STAMP.format(Date())}.txt")
        file.writeText(sb.toString())
        Log.i(TAG, "отчёт: ${file.absolutePath}")
        return file
    }

    private fun toast(app: Context, text: String) =
        Toast.makeText(app, text, Toast.LENGTH_LONG).show()

    private val ART_KEYS = listOf(
        MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
        MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ART_URI,
        MediaMetadata.METADATA_KEY_DISPLAY_ICON, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI
    )
    private val STAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)
    private val FILE_STAMP = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private const val CONNECT_MS = 10_000L
    private const val CHILDREN_MS = 5_000L
    private const val MAX_FOLDERS = 3
    private const val MAX_DEEP = 10
    private const val TAG = "YandexMusicProbe"
}
