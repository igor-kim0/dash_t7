package com.dash.headunit.music

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Общий источник музыкальных данных: активные media-сессии
 * (Bluetooth AVRCP с телефона, локальные плееры).
 *
 * Bluetooth-сессию берём напрямую у A2dpMediaBrowserService через
 * [MediaBrowser] — доступ к уведомлениям для неё не нужен (на ГУ его не
 * включить: NMS принимает только system/shell). Остальные плееры — через
 * [MediaListenerService], если доступ всё же включён.
 * Опрос каждые 2 с: выбирается играющая сессия, иначе первая с метаданными.
 * Управление — transport controls; без контроллера — медиа-клавиши
 * через AudioManager (их получает media button session, т.е. BT).
 */
object MusicHub {

    var data: MusicData? = null
        private set
    /** true если нет доступа к уведомлениям (сессии не читаются). */
    var noAccess: Boolean = false
        private set

    private var appContext: Context? = null
    private var manager: MediaSessionManager? = null
    private var controller: MediaController? = null

    /** Подключение к A2dpMediaBrowserService (BT AVRCP) — без доступа к уведомлениям. */
    private var btBrowser: MediaBrowser? = null
    @Volatile
    private var btController: MediaController? = null
    private var btRetryJob: Job? = null

    private val listeners = mutableSetOf<() -> Unit>()
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollJob: Job? = null

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
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
            connectBluetooth()
            // Сразу пробуем доступ, чтобы показать подсказку если его нет.
            probeAccess()
            pollJob = scope.launch {
                while (true) {
                    refresh()
                    delay(POLL_MS)
                }
            }
        }
    }

    /** true — показывать подсказку про доступ (нет ни сессий, ни BT-браузера). */
    fun needsAccess(context: Context): Boolean =
        btController == null && (noAccess || !isListenerEnabled(context))

    // ---------- Bluetooth (A2dpMediaBrowserService) ----------

    /** Вызывать с главного потока: MediaBrowser требует Looper. */
    private fun connectBluetooth() {
        val ctx = appContext ?: return
        if (btBrowser != null) return
        val browser = try {
            MediaBrowser(ctx, BT_BROWSER, object : MediaBrowser.ConnectionCallback() {
                override fun onConnected() {
                    val b = btBrowser ?: return
                    btController = try {
                        MediaController(ctx, b.sessionToken)
                    } catch (e: Exception) {
                        Log.w(TAG, "bt controller", e)
                        null
                    }
                    Log.i(TAG, "bt browser connected: ${btController != null}")
                    refresh()
                }

                override fun onConnectionSuspended() {
                    Log.i(TAG, "bt browser suspended")
                    dropBluetooth()
                }

                override fun onConnectionFailed() {
                    Log.w(TAG, "bt browser connect failed")
                    dropBluetooth()
                }
            }, null)
        } catch (e: Exception) {
            Log.w(TAG, "bt browser", e)
            return
        }
        btBrowser = browser
        try {
            browser.connect()
        } catch (e: Exception) {
            Log.w(TAG, "bt connect", e)
            dropBluetooth()
        }
    }

    /** Сброс и переподключение позже (BT-стек мог перезапуститься). */
    private fun dropBluetooth() {
        try {
            btBrowser?.disconnect()
        } catch (e: Exception) {
            // уже отключён
        }
        btBrowser = null
        btController = null
        if (btRetryJob?.isActive == true) return
        btRetryJob = scope.launch {
            delay(BT_RETRY_MS)
            connectBluetooth()
        }
    }

    private fun probeAccess() {
        scope.launch {
            withContext(Dispatchers.IO) { readSessions() }
        }
    }

    fun refresh() {
        scope.launch {
            val accessBefore = noAccess
            val snapshot = withContext(Dispatchers.IO) { readSessions() }
            var changed = noAccess != accessBefore
            if (snapshot != null || data?.playing != true) {
                // Пустой снимок при играющем треке игнорируем (мимолётный лаг сессии).
                if (snapshot != null) {
                    data = snapshot
                    changed = true
                } else if (data != null) {
                    data = null
                    changed = true
                }
            }
            // Уведомляем и при смене флага доступа: иначе виджет с
            // подсказкой «Включить доступ» не обновится после включения.
            if (changed) notifyListeners()
        }
    }

    private fun readSessions(): MusicData? {
        val ctx = appContext ?: return null
        try {
            var mgr = manager
            if (mgr == null) {
                mgr = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                    ?: return null
                manager = mgr
            }
            val component = ComponentName(ctx, MediaListenerService::class.java)
            val controllers = mutableListOf<MediaController>()
            try {
                mgr.getActiveSessions(component)?.let { controllers.addAll(it) }
                noAccess = false
            } catch (e: SecurityException) {
                noAccess = true
            }
            // BT-сессия из MediaBrowser — если её уже нет среди активных.
            btController?.let { bt ->
                if (controllers.none { it.sessionToken == bt.sessionToken }) controllers.add(bt)
            }
            if (controllers.isEmpty()) {
                controller = null
                return null
            }
            // Приоритет — играющей сессии, иначе первой с названием трека.
            var pick: MediaController? = null
            var fallback: MediaController? = null
            for (c in controllers) {
                try {
                    val state = c.playbackState?.state
                    if (state == PlaybackState.STATE_PLAYING) {
                        pick = c
                        break
                    }
                    if (fallback == null &&
                        !c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank()
                    ) {
                        fallback = c
                    }
                } catch (e: Exception) {
                    // Битый контроллер — пропускаем.
                }
            }
            val active = pick ?: fallback ?: controllers.first()
            controller = active
            return try {
                snapshotOf(active)
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
        val state = try {
            c.playbackState
        } catch (e: Exception) {
            null
        }
        val playing = state?.state == PlaybackState.STATE_PLAYING
        val pos = state?.position ?: -1
        val dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val pkg = try {
            c.packageName
        } catch (e: Exception) {
            null
        }
        return MusicData(
            title = title,
            artist = artist,
            album = album,
            art = art,
            sourceLabel = pkg,
            playing = playing,
            positionMs = pos,
            durationMs = if (dur > 0) dur else -1
        )
    }

    // ---------- транспорт ----------

    private fun control(keyCode: Int, action: (MediaController.TransportControls) -> Unit) {
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val c = controller ?: readSessions()?.let { controller } ?: btController
                    if (c != null) {
                        c.transportControls.let(action)
                    } else {
                        // Нет сессии в руках — медиа-клавиша уйдёт в media button session (BT).
                        sendMediaKey(keyCode)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "control", e)
                }
            }
            // Состояние обновится следующим опросом; подтолкнём сразу.
            delay(400)
            refresh()
        }
    }

    private fun sendMediaKey(keyCode: Int) {
        val am = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val now = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    fun next() = control(KeyEvent.KEYCODE_MEDIA_NEXT) { it.skipToNext() }
    fun prev() = control(KeyEvent.KEYCODE_MEDIA_PREVIOUS) { it.skipToPrevious() }
    fun toggle() {
        val d = data
        val key = when {
            d == null -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            d.playing -> KeyEvent.KEYCODE_MEDIA_PAUSE
            else -> KeyEvent.KEYCODE_MEDIA_PLAY
        }
        val playing = d?.playing == true
        control(key) { if (playing) it.pause() else it.play() }
    }

    /**
     * Надёжная проверка доступа: строка enabled_notification_listeners
     * в Settings.Secure (не зависит от поведения getActiveSessions).
     */
    fun isListenerEnabled(context: Context): Boolean {
        return try {
            val flat = android.provider.Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners"
            ) ?: return false
            flat.split(":").any { part ->
                if (part.isBlank()) return@any false
                try {
                    val cn = android.content.ComponentName.unflattenFromString(part)
                    cn != null && cn.packageName == context.packageName
                } catch (e: Exception) {
                    part == ComponentName(context, MediaListenerService::class.java)
                        .flattenToString()
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Есть ли право самому писать в Settings.Secure (выдаётся через adb pm grant). */
    fun hasSecureWrite(context: Context): Boolean =
        context.checkCallingOrSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * Самостоятельно включить доступ, дописав наш сервис в
     * enabled_notification_listeners (так же делают Настройки системы;
     * система сама подхватит изменение и свяжет сервис).
     * Возвращает null — нет права, иначе успех.
     */
    fun enableSelf(context: Context): Boolean? {
        if (!hasSecureWrite(context)) return null
        return try {
            val cr = context.contentResolver
            val my = ComponentName(context, MediaListenerService::class.java)
            // NMS на Android 9 хранит список в managed-services.xml и Settings
            // не читает; запись в Settings имеет смысл только если система
            // при следующей загрузке мигрирует список оттуда (или на
            // модифицированной прошивке). Проверяем результат сразу.
            val flat = android.provider.Settings.Secure.getString(
                cr, "enabled_notification_listeners"
            ) ?: ""
            val parts = flat.split(":").filter { it.isNotBlank() }
            if (parts.none { it == my.flattenToString() }) {
                android.provider.Settings.Secure.putString(
                    cr, "enabled_notification_listeners",
                    (parts + my.flattenToString()).joinToString(":")
                )
            }
            noAccess = false
            readSessions()
            if (noAccess) {
                Log.w(TAG, "enableSelf: NMS не подтвердил доступ")
                false
            } else {
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "enableSelf", e)
            false
        }
    }

    /** Для тестов. */
    fun resetForTests() {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            btBrowser?.disconnect()
        } catch (e: Exception) {
            // ignore
        }
        btBrowser = null
        btController = null
        btRetryJob = null
        listeners.clear()
        data = null
        noAccess = false
        pollJob = null
        appContext = null
        manager = null
        controller = null
    }

    /**
     * Диагностика для debug-экрана: есть ли доступ к сессиям и что видно.
     * Вызывать с фонового потока.
     */
    fun debugLines(): List<String> {
        val out = mutableListOf<String>()
        val ctx = appContext
        if (ctx == null) {
            out.add("media: hub не инициализирован")
            return out
        }
        out.add("media: noAccess=$noAccess cached=${data != null}")
        val bt = btController
        out.add(
            "media: btBrowser=${if (bt != null) "connected" else "нет"}" +
                (bt?.let { " state=${it.playbackState?.state} title=${it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "—"}" } ?: "")
        )
        out.add(
            "media: listenerEnabled=${isListenerEnabled(ctx)} " +
                "secureWrite=${hasSecureWrite(ctx)}"
        )
        val mgr = try {
            ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        } catch (e: Exception) {
            out.add("media: нет MediaSessionManager: ${e.message}")
            return out
        }
        if (mgr == null) {
            out.add("media: MediaSessionManager null")
            return out
        }
        val component = ComponentName(ctx, MediaListenerService::class.java)
        val controllers = try {
            mgr.getActiveSessions(component)
        } catch (e: SecurityException) {
            out.add("media: ДОСТУП К УВЕДОМЛЕНИЯМ НЕ ВКЛЮЧЁН (${e.message})")
            return out
        } catch (e: Exception) {
            out.add("media: ошибка getActiveSessions: ${e.message}")
            return out
        }
        if (controllers.isNullOrEmpty()) {
            out.add("media: активных сессий НЕТ (0)")
            return out
        }
        out.add("media: сессий: ${controllers.size}")
        for (c in controllers) {
            try {
                val pkg = try {
                    c.packageName
                } catch (e: Exception) {
                    "?"
                }
                val md = try {
                    c.metadata
                } catch (e: Exception) {
                    null
                }
                val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
                val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                val state = try {
                    c.playbackState?.state?.toString()
                } catch (e: Exception) {
                    "?"
                }
                out.add("media: pkg=$pkg state=$state title=${title ?: "—"} artist=${artist ?: "—"}")
            } catch (e: Exception) {
                out.add("media: битый контроллер: ${e.message}")
            }
        }
        return out
    }

    private const val POLL_MS = 2000L
    private const val BT_RETRY_MS = 10_000L
    private val BT_BROWSER = ComponentName(
        "com.android.bluetooth",
        "com.android.bluetooth.a2dpsink.mbs.A2dpMediaBrowserService"
    )
    private const val TAG = "MusicHub"
}
