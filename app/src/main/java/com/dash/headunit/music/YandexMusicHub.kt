package com.dash.headunit.music

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
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
 * Контроллер — два источника: активная сессия из [MediaSessionManager]
 * (нужен доступ к уведомлениям) или токен сессии подключённого браузера
 * ([MediaBrowser.getSessionToken]). Второй есть и когда Яндекс Музыка ещё
 * ничего не играет, и без доступа к уведомлениям — через него коллекцию
 * можно запустить «с нуля». Медиа-клавиши без контроллера не шлём — они
 * ушли бы в BT-сессию с телефона.
 *
 * Дерево: подписываемся на корень, показываем узлы («Моя волна», плейлисты…)
 * с иконками; папки раскрываются ([navigate]) и закрываются ([up]), тап по
 * листовому узлу запускает его ([playCollection]). Если сервис не пускает
 * (Яндекс может пускать только Android Auto) — переподключение с растущей
 * паузой, состояние видно в [browserState]. Картинки — [ArtLoader].
 */
object YandexMusicHub {

    /** Пакет Яндекс Музыки на ГУ. */
    const val PKG = "ru.yandex.music"

    /** Второй вариант пакета (старые сборки Яндекс Музыки). */
    const val PKG_ALT = "ru.yandex.music.android"

    /** Узел дерева коллекций: id для playFromMediaId, название, папка ли, иконка. */
    data class CollectionNode(
        val mediaId: String,
        val title: String,
        val browsable: Boolean,
        val iconUri: String? = null,
        val icon: Bitmap? = null
    )

    /** Состояние подключения к MediaBrowserService Яндекс Музыки. */
    enum class BrowserState { NO_SERVICE, CONNECTING, CONNECTED, FAILED }

    /** Снимок сессии Яндекс Музыки (null — приложение не воспроизводит). */
    var data: MusicData? = null
        private set

    /** Дети текущего уровня дерева (папки и проигрываемые коллекции). */
    var collections: List<CollectionNode> = emptyList()
        private set

    /** Сколько папок открыто (0 — корень дерева). */
    var level: Int = 0
        private set

    /** Название открытой папки (null — корень). */
    val folderTitle: String? get() = path.lastOrNull()?.title

    /** true — ждём детей открытой папки (после [navigate]/[up]/подключения). */
    var loading: Boolean = false
        private set

    var browserState: BrowserState = BrowserState.NO_SERVICE
        private set

    /** Подряд неудачных подключений браузера (сброс при успехе). */
    var browserFailures: Int = 0
        private set

    /** true — приложение установлено (найден MediaBrowserService). */
    val installed: Boolean get() = browserState != BrowserState.NO_SERVICE

    /** true — есть чем управлять: активная сессия или сессия браузера. */
    val hasController: Boolean get() = (sessionController ?: browserController) != null

    /** true — нет доступа к уведомлениям (активные сессии не читаются). */
    var noAccess: Boolean = false
        private set

    private var appContext: Context? = null
    private var manager: MediaSessionManager? = null

    @Volatile
    private var sessionController: MediaController? = null

    @Volatile
    private var browserController: MediaController? = null
    private var browser: MediaBrowser? = null
    private var retryJob: Job? = null

    /** Открытая навигация: путь папок от корня до текущего уровня. */
    private val path = mutableListOf<CollectionNode>()

    /** Корень дерева после подключения. */
    private var rootId: String? = null

    /** На что подписаны сейчас (отписываемся при смене папки). */
    private var subscribedId: String? = null

    private val listeners = mutableSetOf<() -> Unit>()
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollJob: Job? = null

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
                var tick = 0
                while (true) {
                    refresh()
                    // Приложение могли поставить после старта — ищем сервис заново.
                    if (browserState == BrowserState.NO_SERVICE && ++tick % RESCAN_TICKS == 0) {
                        connectBrowser()
                    }
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
            if (snapshot != data) {
                data = snapshot
                changed = true
            }
            if (changed) notifyListeners()
        }
    }

    // ---------- сессия ----------

    /**
     * Снимок: активная сессия пакета Яндекс Музыки, а если её не видно
     * (нет доступа или сессия не активна) — сессия браузера.
     */
    private fun readSession(): MusicData? {
        sessionController = findActiveSession()
        val c = sessionController ?: browserController ?: return null
        return try {
            snapshotOf(c)
        } catch (e: Exception) {
            Log.w(TAG, "snapshot", e)
            null
        }
    }

    private fun findActiveSession(): MediaController? {
        val ctx = appContext ?: return null
        return try {
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
                return null
            }
            noAccess = false
            controllers.firstOrNull { it.packageName == PKG || it.packageName == PKG_ALT }
        } catch (e: Exception) {
            Log.w(TAG, "read", e)
            null
        }
    }

    private fun snapshotOf(c: MediaController): MusicData? {
        val md = c.metadata ?: return null
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            ?: md.description?.title?.toString()?.takeIf { it.isNotBlank() }
            ?: return null
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
        val album = md.getString(MediaMetadata.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }
        val artUri = (
            md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                ?: md.getString(MediaMetadata.METADATA_KEY_ART_URI)
                ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
                ?: md.description?.iconUri?.toString()
            )?.takeIf { it.isNotBlank() }
        var art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        // Яндекс Музыка часто присылает только ссылку — грузим фоном.
        if (art == null && artUri != null) {
            art = ArtLoader.cached(artUri)
            if (art == null) requestArt(artUri)
        }
        val state = try {
            c.playbackState
        } catch (e: Exception) {
            null
        }
        val playing = state?.state == PlaybackState.STATE_PLAYING ||
            state?.state == PlaybackState.STATE_BUFFERING
        return MusicData(
            title = title,
            artist = artist,
            album = album,
            art = art,
            sourceLabel = PKG,
            playing = playing,
            positionMs = state?.let { currentPosition(it) } ?: -1,
            durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
                .takeIf { it > 0 } ?: -1,
            artUri = artUri
        )
    }

    /** Позиция с учётом времени с последнего обновления состояния. */
    private fun currentPosition(s: PlaybackState): Long {
        if (s.position < 0) return -1
        if (s.state != PlaybackState.STATE_PLAYING || s.lastPositionUpdateTime <= 0) return s.position
        val dt = android.os.SystemClock.elapsedRealtime() - s.lastPositionUpdateTime
        return s.position + (dt * s.playbackSpeed).toLong().coerceAtLeast(0)
    }

    private fun requestArt(uri: String) {
        val ctx = appContext ?: return
        ArtLoader.request(ctx, uri, scope) { bm ->
            val d = data
            if (d != null && d.art == null && d.artUri == uri) {
                data = d.copy(art = bm)
                notifyListeners()
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
            browserState = BrowserState.NO_SERVICE
            return
        }
        browserState = BrowserState.CONNECTING
        val b = try {
            MediaBrowser(ctx, service, object : MediaBrowser.ConnectionCallback() {
                override fun onConnected() {
                    val br = browser ?: return
                    browserState = BrowserState.CONNECTED
                    browserFailures = 0
                    rootId = br.root
                    subscribedId = null
                    browserController = try {
                        MediaController(ctx, br.sessionToken)
                    } catch (e: Exception) {
                        Log.w(TAG, "browser controller", e)
                        null
                    }
                    Log.i(TAG, "ya browser connected: root=${br.root}")
                    reSubscribe()
                    refresh()
                    notifyListeners()
                }

                override fun onConnectionSuspended() {
                    Log.i(TAG, "ya browser suspended")
                    dropBrowser(failed = false)
                }

                override fun onConnectionFailed() {
                    // Сервис есть, но onGetRoot вернул null — не пускает наш пакет.
                    Log.w(TAG, "ya browser connect failed (${browserFailures + 1})")
                    dropBrowser(failed = true)
                }
            }, null)
        } catch (e: Exception) {
            Log.w(TAG, "ya browser create", e)
            dropBrowser(failed = true)
            return
        }
        browser = b
        try {
            b.connect()
        } catch (e: Exception) {
            Log.w(TAG, "ya browser connect", e)
            dropBrowser(failed = true)
        }
    }

    private fun dropBrowser(failed: Boolean) {
        try {
            browser?.disconnect()
        } catch (e: Exception) {
            // уже отключён
        }
        browser = null
        browserController = null
        subscribedId = null
        loading = false
        if (failed) {
            browserFailures++
            browserState = BrowserState.FAILED
        } else {
            browserState = BrowserState.CONNECTING
        }
        notifyListeners()
        if (retryJob?.isActive == true) return
        val wait = retryDelayMs(browserFailures)
        retryJob = scope.launch {
            delay(wait)
            connectBrowser()
        }
    }

    /** Пауза перед переподключением: 10 с, дальше вдвое, но не больше 2 мин. */
    internal fun retryDelayMs(failures: Int): Long {
        if (failures <= 1) return RETRY_MS
        val shift = (failures - 1).coerceAtMost(4)
        return (RETRY_MS shl shift).coerceAtMost(MAX_RETRY_MS)
    }

    /** Компонент MediaBrowserService в пакете Яндекс Музыки (Android 9 без ограничений). */
    fun findService(ctx: Context): ComponentName? = findServices(ctx).firstOrNull()

    /** Все MediaBrowserService пакетов Яндекс Музыки (для диагностики). */
    fun findServices(ctx: Context): List<ComponentName> = try {
        ctx.packageManager.queryIntentServices(Intent(SERVICE_ACTION), 0).orEmpty()
            .mapNotNull { it?.serviceInfo }
            .filter { it.packageName == PKG || it.packageName == PKG_ALT }
            .map { ComponentName(it.packageName, it.name) }
    } catch (e: Exception) {
        Log.w(TAG, "find service", e)
        emptyList()
    }

    const val SERVICE_ACTION = "android.media.browse.MediaBrowserService"

    /** Подписаться на текущую папку (или корень), сняв прежнюю подписку. */
    private fun reSubscribe() {
        val br = browser ?: return
        if (browserState != BrowserState.CONNECTED) return
        val target = path.lastOrNull()?.mediaId ?: rootId ?: br.root
        val old = subscribedId
        if (old != null && old != target) {
            try {
                br.unsubscribe(old)
            } catch (e: Exception) {
                // уже снята
            }
        }
        subscribedId = target
        loading = true
        try {
            br.subscribe(target, childCallback)
        } catch (e: Exception) {
            Log.w(TAG, "subscribe $target", e)
            loading = false
        }
    }

    private val childCallback = object : MediaBrowser.SubscriptionCallback() {
        override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowser.MediaItem>) {
            // Дети приходят только для той папки, где мы сейчас.
            if (parentId != subscribedId) return
            loading = false
            collections = children.mapNotNull { nodeOf(it) }
            collections.forEach { n ->
                if (n.icon == null && n.iconUri != null) requestIcon(n.iconUri)
            }
            notifyListeners()
        }

        override fun onError(parentId: String) {
            Log.w(TAG, "children error $parentId")
            if (parentId == subscribedId) {
                loading = false
                notifyListeners()
            }
        }
    }

    private fun nodeOf(item: MediaBrowser.MediaItem): CollectionNode? {
        val d = item.description
        val title = d.title?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val mediaId = item.mediaId ?: return null
        val browsable = item.isBrowsable
        if (!browsable && !item.isPlayable) return null
        val iconUri = d.iconUri?.toString()?.takeIf { it.isNotBlank() }
        val icon = d.iconBitmap ?: iconUri?.let { ArtLoader.cached(it) }
        return CollectionNode(mediaId, title, browsable, iconUri, icon)
    }

    private fun requestIcon(uri: String) {
        val ctx = appContext ?: return
        ArtLoader.request(ctx, uri, scope) { bm ->
            var hit = false
            collections = collections.map {
                if (it.icon == null && it.iconUri == uri) {
                    hit = true
                    it.copy(icon = bm)
                } else {
                    it
                }
            }
            if (hit) notifyListeners()
        }
    }

    /** Открыть папку (например, «Плейлисты») или запустить коллекцию. */
    fun navigate(node: CollectionNode) {
        if (!node.browsable) {
            playCollection(node.mediaId)
            return
        }
        path.add(node)
        level = path.size
        collections = emptyList()
        reSubscribe()
        notifyListeners()
    }

    /** Подняться на уровень выше (изнутри папки). */
    fun up() {
        path.removeLastOrNull() ?: return
        level = path.size
        collections = emptyList()
        reSubscribe()
        notifyListeners()
    }

    /**
     * Запустить коллекцию. Контроллер — активная сессия или сессия браузера;
     * если нет ни того, ни другого (браузер не пускает) — просто открываем
     * приложение, чтобы пользователь выбрал там.
     */
    fun playCollection(mediaId: String) {
        scope.launch {
            try {
                val c = sessionController ?: browserController
                    ?: withContext(Dispatchers.IO) { findActiveSession() }
                if (c != null) {
                    c.transportControls.playFromMediaId(mediaId, null)
                    delay(400)
                    refresh()
                } else {
                    openApp()
                }
            } catch (e: Exception) {
                Log.w(TAG, "play $mediaId", e)
            }
        }
    }

    /** Открыть приложение Яндекс Музыки. */
    fun openApp() {
        val ctx = appContext ?: return
        try {
            val pm = ctx.packageManager
            val intent = pm.getLaunchIntentForPackage(PKG) ?: pm.getLaunchIntentForPackage(PKG_ALT)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { ctx.startActivity(it) }
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
        if (data?.playing == true) control { it.pause() } else control { it.play() }
    }

    private fun control(action: (MediaController.TransportControls) -> Unit) {
        scope.launch {
            try {
                val c = sessionController ?: browserController
                    ?: withContext(Dispatchers.IO) { findActiveSession() }
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
        sessionController = null
        browserController = null
        retryJob = null
        listeners.clear()
        ArtLoader.clear()
        path.clear()
        collections = emptyList()
        level = 0
        loading = false
        data = null
        noAccess = false
        browserState = BrowserState.NO_SERVICE
        browserFailures = 0
        pollJob = null
        rootId = null
        subscribedId = null
        appContext = null
        manager = null
    }

    private const val POLL_MS = 2000L
    private const val RESCAN_TICKS = 15
    private const val RETRY_MS = 10_000L
    private const val MAX_RETRY_MS = 120_000L
    private const val TAG = "YandexMusicHub"
}
