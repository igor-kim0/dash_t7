package com.dash.headunit.music

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.withLock
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Источник данных и управления для виджета «Яндекс Музыка».
 *
 * Отличие от [MusicHub]: та берёт любую играющую сессию (обычно BT AVRCP
 * с телефона), а здесь нужна именно сессия приложения Яндекс Музыки на
 * самом ГУ — только у неё бывают обложка (bitmap или URI из метаданных) и
 * дерево коллекций через MediaBrowser (Android Auto-интерфейс приложения).
 *
 * Дерево: подписываемся на корень, показываем узлы («Моя волна», плейлисты…);
 * папки можно раскрывать ([navigate]) и закрывать ([up]), тап по листовому
 * узлу запускает его ([playCollection]). Транспорт — через контроллер
 * сессии; без контроллера медиа-клавиши не шлём — они ушли бы в BT-сессию
 * с телефона. Опрос каждые 2 с, как в [MusicHub]; обложка по URI тянется
 * фоном один раз и остаётся в кэше.
 */
object YandexMusicHub {

    /** Пакет Яндекс Музыки на ГУ. */
    const val PKG = "ru.yandex.music"

    /** Второй вариант пакета (старые сборки Яндекс Музыки). */
    const val PKG_ALT = "ru.yandex.music.android"

    /** Узел дерева коллекций: id для playFromMediaId, название, есть ли вложенные. */
    data class CollectionNode(val mediaId: String, val title: String, val browsable: Boolean)

    /** Снимок сессии Яндекс Музыки (null — приложение не воспроизводит). */
    var data: MusicData? = null
        private set

    /** Дети текущего уровня дерева (папки и проигрываемые коллекции). */
    var collections: List<CollectionNode> = emptyList()
        private set

    /** Сколько папок открыто (0 — корень дерева). */
    var level: Int = 0
        private set

    /** true — приложение установлено (найден MediaBrowserService, браузер подключается). */
    var installed: Boolean = false
        private set

    /** true — браузер подключён и есть контроллер сессии. */
    var connected: Boolean = false
        private set

    /** true — нет доступа к уведомлениям (сессии не читаются). */
    var noAccess: Boolean = false
        private set

    private var appContext: Context? = null
    private var manager: MediaSessionManager? = null
    private var controller: MediaController? = null
    private var browser: MediaBrowser? = null
    private var retryJob: Job? = null
    private var artJob: Job? = null

    /** Открытая навигация: путь папок от корня до текущего уровня. */
    private val path = mutableListOf<CollectionNode>()

    private val listeners = mutableSetOf<() -> Unit>()
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollJob: Job? = null
    private val artCache = mutableMapOf<String, Bitmap>()
    private val artLock = ReentrantLock()

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
        listener()
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        listeners.toList().forEach { it() }
    }

    fun ensurePolling() {
        if (pollJob == null) {
            connectBrowser()
            pollJob = scope.launch {
                while (true) {
                    refresh()
                    delay(POLL_MS)
                }
            }
        }
    }

    fun refresh() {
        scope.launch {
            val accessBefore = noAccess
            val snapshot = withContext(Dispatchers.IO) { readSession() }
            var changed = noAccess != accessBefore
            if (snapshot == null) {
                if (data != null) {
                    data = null
                    changed = true
                }
            } else if (snapshot != data) {
                data = snapshot
                changed = true
            }
            if (changed) notifyListeners()
        }
    }

    // ---------- сессия ----------

    /** Сессия строго пакета Яндекс Музыки; без неё контроллер сбрасывается. */
    private fun readSession(): MusicData? {
        val ctx = appContext ?: return null
        try {
            var mgr = manager
            if (mgr == null) {
                mgr = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                    ?: return null
                manager = mgr
            }
            val component = ComponentName(ctx, MediaListenerService::class.java)
            val controllers = try {
                mgr.getActiveSessions(component).orEmpty()
            } catch (e: SecurityException) {
                noAccess = true
                controller = null
                connected = false
                return null
            }
            noAccess = false
            val ya = controllers
                .filter { it.packageName == PKG || it.packageName == PKG_ALT }
                .firstOrNull() ?: run {
                controller = null
                connected = false
                return null
            }
            controller = ya
            connected = true
            return try {
                snapshotOf(ya)
            } catch (e: Exception) {
                Log.w(TAG, "snapshot", e)
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "read", e)
            return null
        }
    }

    private fun snapshotOf(c: MediaController): MusicData? {
        val md = c.metadata ?: return null
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            ?: return null
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
        val album = md.getString(MediaMetadata.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }
        var art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ART)
        // Яндекс Музыка часто присылает только ссылку — грузим фоном.
        val artUri = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_ART_URI)
        var artBitmap = art
        if (artBitmap == null && !artUri.isNullOrBlank()) {
            artBitmap = artLock.withLock { artCache[artUri] }
            if (artBitmap == null) fetchArt(artUri)
        }
        val state = try {
            c.playbackState
        } catch (e: Exception) {
            null
        }
        val playing = state?.state == PlaybackState.STATE_PLAYING
        return MusicData(
            title = title,
            artist = artist,
            album = album,
            art = artBitmap,
            sourceLabel = PKG,
            playing = playing,
            positionMs = state?.position ?: -1,
            durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
                .takeIf { it > 0 } ?: -1,
            artUri = artUri?.takeIf { it.isNotBlank() }
        )
    }

    /** Обложка по URI: кэш → HttpURLConnection → обновление снимка. */
    private fun fetchArt(uri: String) {
        if (artJob?.isActive == true) return
        artJob = scope.launch {
            val bm = withContext(Dispatchers.IO) {
                try {
                    val conn = URL(uri).openConnection() as HttpURLConnection
                    conn.connectTimeout = 8_000
                    conn.readTimeout = 8_000
                    conn.useCaches = true
                    conn.inputStream.use { BitmapFactory.decodeStream(it) }.also {
                        if (it != null) {
                            artLock.withLock {
                                if (artCache.size > MAX_ART_CACHE) artCache.clear()
                                artCache[uri] = it
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "art $uri", e)
                    null
                }
            }
            if (bm != null) {
                val d = data
                if (d != null && d.art == null && d.artUri == uri) {
                    data = d.copy(art = bm)
                    notifyListeners()
                }
                // После загрузки не ждём 2 с — подтолкнём отрисовку сразу.
                refresh()
            }
        }
    }

    // ---------- MediaBrowser: дерево коллекций ----------

    /** Найти MediaBrowserService Яндекс Музыки и подключиться к нему. */
    private fun connectBrowser() {
        val ctx = appContext ?: return
        if (browser != null) return
        val service = findService(ctx)
        if (service == null) {
            installed = false
            return
        }
        installed = true
        val b = try {
            MediaBrowser(ctx, service, object : MediaBrowser.ConnectionCallback() {
                override fun onConnected() {
                    val br = browser ?: return
                    subscribedRoot = br.root
                    reSubscribe()
                    Log.i(TAG, "ya browser connected: ${br.root}")
                    notifyListeners()
                }

                override fun onConnectionSuspended() {
                    Log.i(TAG, "ya browser suspended")
                    dropBrowser(reschedule = true)
                }

                override fun onConnectionFailed() {
                    Log.w(TAG, "ya browser connect failed")
                    dropBrowser(reschedule = true)
                }
            }, null)
        } catch (e: Exception) {
            Log.w(TAG, "ya browser create", e)
            return
        }
        browser = b
        try {
            b.connect()
        } catch (e: Exception) {
            Log.w(TAG, "ya browser connect", e)
            dropBrowser(reschedule = true)
        }
    }

    private fun dropBrowser(reschedule: Boolean) {
        try {
            browser?.disconnect()
        } catch (e: Exception) {
            // уже отключён
        }
        browser = null
        connected = false
        if (!reschedule) return
        if (retryJob?.isActive == true) return
        retryJob = scope.launch {
            delay(BT_RETRY_MS)
            connectBrowser()
        }
    }

    /** Компонент MediaBrowserService в пакете Яндекс Музыки (Android 9 без ограничений). */
    private fun findService(ctx: Context): ComponentName? {
        val pm = ctx.packageManager
        val xml = Intent(SERVICE_ACTION)
        try {
            val services = pm.queryIntentServices(xml, 0) ?: return null
            for (ri in services) {
                if (ri != null && ri.serviceInfo != null) {
                    val pkg = ri.serviceInfo.packageName
                    if (pkg == PKG || pkg == PKG_ALT) {
                        return ComponentName(pkg, ri.serviceInfo.name)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "find service", e)
        }
        return null
    }

    private const val SERVICE_ACTION = "android.media.browse.MediaBrowserService"

    /** Корень дерева после подключения (заменяет подписку по умолчанию). */
    private var subscribedRoot: String? = null

    /**
     * Переподписаться после подключения: root (нет навигации) или
     * текущая папка.
     */
    private fun reSubscribe() {
        val br = browser ?: return
        val current = path.lastOrNull()
        val subscribeId = current?.mediaId ?: subscribedRoot ?: br.root
        try {
            br.subscribe(subscribeId, childCallback)
        } catch (e: Exception) {
            Log.w(TAG, "subscribe $subscribeId", e)
        }
    }

    private val childCallback = object : MediaBrowser.SubscriptionCallback() {
        override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowser.MediaItem>) {
            // Дети приходят только для той папки, где мы сейчас.
            val expected = path.lastOrNull()?.mediaId ?: subscribedRoot
            if (parentId == expected) {
                collections = children.mapNotNull { item ->
                    val title = item.description.title?.toString() ?: return@mapNotNull null
                    val mediaId = item.mediaId ?: return@mapNotNull null
                    val browsable = (item.flags and MediaBrowser.MediaItem.FLAG_BROWSABLE) != 0
                    val playable = (item.flags and MediaBrowser.MediaItem.FLAG_PLAYABLE) != 0
                    if (browsable || playable) CollectionNode(mediaId, title, browsable) else null
                }
                notifyListeners()
            }
        }

        override fun onError(parentId: String) {
            Log.w(TAG, "children error $parentId")
        }
    }

    /** Открыть папку (например, «Плейлисты») — уходит на уровень глубже. */
    fun navigate(node: CollectionNode) {
        if (!node.browsable) {
            playCollection(node.mediaId)
            return
        }
        path.add(node)
        level = path.size
        reSubscribe()
    }

    /** Подняться на уровень выше (изнутри папки). */
    fun up() {
        val popped = path.removeLastOrNull() ?: return
        level = path.size
        // Дети предыдущего уровня уже могли быть считаны — подписка заново.
        reSubscribe()
        notifyListeners()
    }

    /** Запустить коллекцию: контроллеру сессии Яндекс Музыки. */
    fun playCollection(mediaId: String) {
        scope.launch {
            try {
                val c = controller ?: (readSession()?.let { controller })
                if (c != null) {
                    c.transportControls.playFromMediaId(mediaId, null)
                    delay(400)
                    refresh()
                } else {
                    playFromAppStart(mediaId)
                }
            } catch (e: Exception) {
                Log.w(TAG, "play $mediaId", e)
            }
        }
    }

    /**
     * Если контроллера нет — открываем приложение с deep-ссылкой на
     * коллекцию: yandexmusic:// из mediaId (плеер обычно умеет сам).
     */
    private fun playFromAppStart(mediaId: String) {
        val ctx = appContext ?: return
        try {
            val uri = "yandexmusic://play?media_id=$mediaId"
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                ctx.startActivity(intent)
            } catch (e: Exception) {
                // Ссылка не распознана — просто запускаем приложение.
                ctx.packageManager.getLaunchIntentForPackage(PKG)?.let {
                    ctx.startActivity(it)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "start app", e)
        }
    }

    fun next() {
        control { it.skipToNext() }
    }

    fun prev() {
        control { it.skipToPrevious() }
    }

    fun toggle() {
        val d = data
        val playing = d?.playing == true
        if (playing) control { it.pause() } else control { it.play() }
    }

    private fun control(action: (MediaController.TransportControls) -> Unit) {
        scope.launch {
            try {
                val c = controller ?: readSession()?.let { controller }
                c?.transportControls?.let(action)
                delay(400)
                refresh()
            } catch (e: Exception) {
                Log.w(TAG, "control", e)
            }
        }
    }

    /** Для тестов: полный сброс состояния. */
    fun resetForTests() {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            browser?.disconnect()
        } catch (e: Exception) {
            // ignore
        }
        browser = null
        controller = null
        retryJob = null
        artJob = null
        listeners.clear()
        artLock.withLock { artCache.clear() }
        path.clear()
        collections = emptyList()
        level = 0
        data = null
        noAccess = false
        connected = false
        installed = false
        pollJob = null
        subscribedRoot = null
        appContext = null
        manager = null
    }

    private const val POLL_MS = 2000L
    private const val BT_RETRY_MS = 10_000L
    private const val MAX_ART_CACHE = 32
    private const val TAG = "YandexMusicHub"
}
