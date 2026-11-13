package com.dash.headunit.radio

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject

/**
 * Сколько и когда слушали станции — для карточки «О станции»
 * ([com.dash.headunit.widgets.RadioStationCard]).
 *
 * Отрезок прослушивания — пока радио играет одну частоту и перебора нет.
 * Отрезки короче [MIN_SEGMENT_MS] не считаются (пролистывание станций).
 * На флеш пишем только в конце отрезка: смена станции, пауза, перебор.
 *
 * Ключ — [RadioStations.keyOf], так что учёт идёт и для станций, которых
 * нет в своём списке.
 */
object RadioStats {

    /** [last] — когда слушали (мс, время на часах), [totalMs] — сколько всего. */
    data class Entry(val last: Long, val totalMs: Long)

    /** Не короче — иначе это не прослушивание, а перебор станций. */
    const val MIN_SEGMENT_MS = 10_000L

    private var app: Context? = null

    /** Текущий отрезок: ключ станции и начало (elapsedRealtime). */
    private var segKey: String? = null
    private var segStart = 0L

    /** Кэш хранилища — читаем один раз. */
    private var cache: MutableMap<String, Entry>? = null

    /**
     * Состояние радио изменилось. Зовётся из [RadioHub] (его поток).
     * [walking] — идёт перебор: это не прослушивание.
     */
    @Synchronized
    fun onState(context: Context, state: RadioHub.State, walking: Boolean) {
        app = context.applicationContext
        val freq = RadioHub.Json.parseFrequency(state.frequency)
        val key = if (state.playing == true && freq != null && !walking) {
            RadioStations.keyOf(state.band, freq)
        } else {
            null
        }
        if (key == segKey) return
        closeSegment()
        segKey = key
        segStart = SystemClock.elapsedRealtime()
    }

    /** Записать текущий отрезок (если он не короткий) и закрыть его. */
    private fun closeSegment() {
        val key = segKey ?: return
        val ctx = app ?: return
        val dur = SystemClock.elapsedRealtime() - segStart
        segKey = null
        if (dur < MIN_SEGMENT_MS) return
        val map = entries(ctx)
        map[key] = added(map[key], dur, System.currentTimeMillis())
        prefs(ctx).edit().putString(KEY, serialize(map)).apply()
    }

    /**
     * Статистика станции. Если она играет прямо сейчас, к общему времени
     * добавлен текущий отрезок, а [Entry.last] — сейчас.
     */
    @Synchronized
    fun get(context: Context, band: String, freq: Double): Entry? {
        val key = RadioStations.keyOf(band, freq)
        val saved = entries(context)[key]
        if (key != segKey) return saved
        val live = SystemClock.elapsedRealtime() - segStart
        return added(saved, live, System.currentTimeMillis())
    }

    private fun entries(context: Context): MutableMap<String, Entry> =
        cache ?: parse(prefs(context).getString(KEY, null)).toMutableMap().also { cache = it }

    // ---------- чистые функции (покрыты тестами) ----------

    /** Добавить отрезок [durMs], закончившийся в [now]. */
    fun added(old: Entry?, durMs: Long, now: Long): Entry =
        Entry(last = now, totalMs = (old?.totalMs ?: 0L) + durMs.coerceAtLeast(0L))

    fun parse(raw: String?): Map<String, Entry> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            val o = JSONObject(raw)
            o.keys().asSequence().mapNotNull { k ->
                val e = o.optJSONObject(k) ?: return@mapNotNull null
                k to Entry(e.optLong("l"), e.optLong("t"))
            }.toMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun serialize(map: Map<String, Entry>): String {
        val o = JSONObject()
        map.forEach { (k, e) -> o.put(k, JSONObject().put("l", e.last).put("t", e.totalMs)) }
        return o.toString()
    }

    /**
     * Когда слушали: «сегодня в 14:05», «вчера в 09:12», «12 ноября»
     * (в этом году) или «12.11.2025».
     */
    fun formatLast(last: Long, now: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): String {
        val ru = java.util.Locale("ru")
        val a = java.util.Calendar.getInstance(zone).apply { timeInMillis = last }
        val b = java.util.Calendar.getInstance(zone).apply { timeInMillis = now }
        val time = java.text.SimpleDateFormat("HH:mm", ru).apply { timeZone = zone }.format(a.time)
        fun dayIndex(c: java.util.Calendar) =
            c.get(java.util.Calendar.YEAR) * 1000 + c.get(java.util.Calendar.DAY_OF_YEAR)
        val sameYear = a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR)
        val yesterday = (b.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }
        return when {
            dayIndex(a) == dayIndex(b) -> "сегодня в $time"
            dayIndex(a) == dayIndex(yesterday) -> "вчера в $time"
            sameYear -> "${a.get(java.util.Calendar.DAY_OF_MONTH)} ${MONTHS[a.get(java.util.Calendar.MONTH)]}"
            else -> java.text.SimpleDateFormat("dd.MM.yyyy", ru).apply { timeZone = zone }.format(a.time)
        }
    }

    private val MONTHS = arrayOf(
        "января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря"
    )

    /** «3 ч 12 мин», «25 мин», «меньше минуты». */
    fun formatDuration(ms: Long): String {
        val min = ms / 60_000
        val h = min / 60
        val m = min % 60
        return when {
            h > 0 && m > 0 -> "$h ч $m мин"
            h > 0 -> "$h ч"
            m > 0 -> "$m мин"
            else -> "меньше минуты"
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "radio_stats"
    private const val KEY = "stats"
}
