package com.dash.headunit.music

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Обложки треков и иконки коллекций по URI для [YandexMusicHub].
 *
 * Яндекс Музыка присылает картинки по-разному: `https://…`, `content://…`
 * (провайдер приложения, как для Android Auto), `android.resource://…` или
 * шаблон `avatars.yandex.net/get-music-content/…/%%` без схемы, где `%%` —
 * размер. [normalize] приводит всё к открываемому виду; [request] грузит
 * фоном (не больше [PARALLEL] сразу), уменьшает до [MAX_PX] и кладёт в LRU.
 * После неудачи тот же URI не трогаем [RETRY_MS] — иначе опрос раз в 2 с
 * дёргал бы сеть без конца.
 */
object ArtLoader {

    private val cache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) =
            size > MAX_CACHE
    }
    private val failedAt = HashMap<String, Long>()
    private val inFlight = HashSet<String>()
    private val lock = Any()
    private val permits = Semaphore(PARALLEL)

    fun cached(uri: String): Bitmap? = synchronized(lock) { cache[uri] }

    /**
     * Загрузить [uri], если его нет в кэше, он не грузится сейчас и не падал
     * недавно. [onLoaded] зовётся в [scope] (главный поток у хаба).
     */
    fun request(ctx: Context, uri: String, scope: CoroutineScope, onLoaded: (Bitmap) -> Unit) {
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (cache.containsKey(uri) || uri in inFlight) return
            val failed = failedAt[uri]
            if (failed != null && now - failed < RETRY_MS) return
            inFlight.add(uri)
        }
        val app = ctx.applicationContext
        scope.launch {
            val bm = try {
                permits.withPermit { withContext(Dispatchers.IO) { load(app, uri) } }
            } catch (e: CancellationException) {
                synchronized(lock) { inFlight.remove(uri) }
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "art $uri", e)
                null
            }
            synchronized(lock) {
                inFlight.remove(uri)
                if (bm != null) {
                    cache[uri] = bm
                    failedAt.remove(uri)
                } else {
                    failedAt[uri] = SystemClock.elapsedRealtime()
                }
            }
            if (bm != null) onLoaded(bm)
        }
    }

    /** Блокирующая загрузка (IO-поток): байты → уменьшенный bitmap. */
    private fun load(ctx: Context, raw: String): Bitmap? {
        val uri = normalize(raw) ?: return null
        val bytes = when (Uri.parse(uri).scheme?.lowercase()) {
            "http", "https" -> {
                val conn = URL(uri).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 8_000
                    conn.readTimeout = 8_000
                    conn.useCaches = true
                    conn.instanceFollowRedirects = true
                    conn.inputStream.use { readCapped(it) }
                } finally {
                    conn.disconnect()
                }
            }
            ContentResolver.SCHEME_CONTENT,
            ContentResolver.SCHEME_ANDROID_RESOURCE,
            ContentResolver.SCHEME_FILE ->
                ctx.contentResolver.openInputStream(Uri.parse(uri))?.use { readCapped(it) }
            else -> null
        } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_PX)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun readCapped(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_BYTES) return null
        }
        return out.toByteArray()
    }

    /**
     * Привести URI к открываемому виду: `%%` → `400x400`, `//host/…` и
     * `avatars.yandex.net/…` без схемы → `https://…`. Пустой — null.
     */
    fun normalize(raw: String, size: Int = MAX_PX): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        s = s.replace("%%", "${size}x$size")
        if (s.startsWith("//")) return "https:$s"
        if (!s.contains("://")) {
            // Без схемы: похоже на хост (avatars.yandex.net/…) — значит https.
            val host = s.substringBefore('/')
            return if (host.contains('.')) "https://$s" else null
        }
        return s
    }

    /** Степень двойки, при которой большая сторона не меньше [maxPx], но ближе к ней. */
    fun sampleSize(w: Int, h: Int, maxPx: Int): Int {
        var sample = 1
        val side = maxOf(w, h)
        while (side / (sample * 2) >= maxPx) sample *= 2
        return sample
    }

    /** Для тестов. */
    fun clear() = synchronized(lock) {
        cache.clear()
        failedAt.clear()
        inFlight.clear()
    }

    private const val MAX_PX = 400
    private const val MAX_CACHE = 48
    private const val MAX_BYTES = 4 * 1024 * 1024
    private const val PARALLEL = 2
    private const val RETRY_MS = 60_000L
    private const val TAG = "ArtLoader"
}
