package com.dash.headunit.radio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.round

/**
 * Список станций и избранное виджета «Радио»
 * ([com.dash.headunit.widgets.RadioWidgetView]).
 *
 * Штатное радио ГУ наружу отдаёт только текущую частоту и RDS-имя: список
 * найденных станций и избранное живут внутри SVMedia и наружу не выходят
 * (docs/research/radio-aidl.md). Поэтому виджет ведёт свой список:
 * станции, найденные перебором диапазона (теми же стрелками «вперёд/назад»,
 * которыми ходят по списку в штатном радио), вручную добавленные и избранное —
 * не больше [MAX_FAVORITES] штук.
 */
object RadioStations {

    /** Столько избранных станций виджет держит. */
    const val MAX_FAVORITES = 6

    /**
     * Станция.
     *
     * @param band "fm" или "am"
     * @param freq частота: МГц для FM, кГц для AM
     * @param name своё название — задаёт пользователь
     * @param rds название, которое сообщил тюнер (RDS)
     * @param favorite в избранном
     */
    data class Station(
        val band: String,
        val freq: Double,
        val name: String? = null,
        val rds: String? = null,
        val favorite: Boolean = false
    ) {
        /** Название для показа: своё → RDS → частота. */
        val title: String
            get() = name?.takeIf { it.isNotBlank() }
                ?: rds?.takeIf { it.isNotBlank() }
                ?: formatFreq(band, freq)
    }

    // ---------- хранилище ----------

    /** Все известные станции: найденные, добавленные вручную и избранные. */
    fun load(context: Context): List<Station> =
        parse(prefs(context).getString(KEY_LIST, null))

    fun save(context: Context, stations: List<Station>) {
        prefs(context).edit().putString(KEY_LIST, serialize(stations)).apply()
        notifyChanged()
    }

    // ---------- наблюдатели ----------

    private val listeners = mutableSetOf<() -> Unit>()

    /** Создаём лениво: в тестах (без Android) до него не доходим. */
    private val handler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /** Список изменился — виджет перерисовывает избранное. */
    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    private fun notifyChanged() {
        handler.post { listeners.toList().forEach { it() } }
    }

    /**
     * Добавить станцию в список (найденную тюнером или вручную).
     * Своё название и избранное у уже известной станции сохраняются,
     * RDS-имя обновляется, если тюнер его назвал.
     */
    fun upsert(
        context: Context,
        band: String,
        freq: Double,
        rds: String? = null
    ): List<Station> {
        val list = upsertIn(load(context), band, freq, rds)
        save(context, list)
        return list
    }

    /** Чистая часть [upsert]: список с добавленной или обновлённой станцией. */
    fun upsertIn(
        list: List<Station>,
        band: String,
        freq: Double,
        rds: String? = null
    ): List<Station> {
        val out = list.toMutableList()
        val key = keyOf(band, freq)
        val i = out.indexOfFirst { keyOf(it.band, it.freq) == key }
        if (i >= 0) {
            val old = out[i]
            out[i] = old.copy(
                rds = rds?.takeIf { it.isNotBlank() } ?: old.rds,
                name = old.name
            )
        } else {
            out.add(Station(band = band, freq = normalize(band, freq), rds = rds))
        }
        out.sortBy { if (it.band == RadioHub.AM) 1 else 0 }
        return out
    }

    /**
     * Добавить или убрать станцию из избранного.
     * @return false, если избранное уже заполнено ([MAX_FAVORITES]).
     */
    fun setFavorite(context: Context, band: String, freq: Double, favorite: Boolean): Boolean {
        val list = favoriteIn(load(context), band, freq, favorite) ?: return false
        save(context, list)
        return true
    }

    /**
     * Чистая часть [setFavorite]: список с обновлённым избранным.
     * null — мест нет, избранное заполнено.
     */
    fun favoriteIn(
        list: List<Station>,
        band: String,
        freq: Double,
        favorite: Boolean
    ): List<Station>? {
        val out = list.toMutableList()
        val key = keyOf(band, freq)
        val i = out.indexOfFirst { keyOf(it.band, it.freq) == key }
        if (i < 0) return null
        if (favorite && !out[i].favorite && favorites(out).size >= MAX_FAVORITES) return null
        out[i] = out[i].copy(favorite = favorite)
        return out
    }

    /** Своё название станции (пустое имя — убрать). */
    fun rename(context: Context, band: String, freq: Double, name: String?) {
        save(context, renamedIn(load(context), band, freq, name))
    }

    /** Чистая часть [rename]. */
    fun renamedIn(list: List<Station>, band: String, freq: Double, name: String?): List<Station> {
        val out = list.toMutableList()
        val key = keyOf(band, freq)
        val i = out.indexOfFirst { keyOf(it.band, it.freq) == key }
        if (i < 0) return out
        out[i] = out[i].copy(name = name?.trim()?.takeIf { it.isNotEmpty() })
        return out
    }

    /** Убрать станцию из списка. */
    fun remove(context: Context, band: String, freq: Double) {
        save(context, removedIn(load(context), band, freq))
    }

    /** Чистая часть [remove]. */
    fun removedIn(list: List<Station>, band: String, freq: Double): List<Station> {
        val key = keyOf(band, freq)
        return list.filterNot { keyOf(it.band, it.freq) == key }
    }

    /** Найти станцию по диапазону и частоте. */
    fun find(stations: List<Station>, band: String, freq: Double): Station? {
        val key = keyOf(band, freq)
        return stations.firstOrNull { keyOf(it.band, it.freq) == key }
    }

    /** Избранные станции — в порядке добавления. */
    fun favorites(stations: List<Station>): List<Station> = stations.filter { it.favorite }

    /**
     * Поиск по названию и частоте. Пустой запрос — все станции.
     * «101,5» и «101.5» — одно и то же.
     */
    fun search(stations: List<Station>, query: String): List<Station> {
        val q = query.trim().replace(',', '.').lowercase()
        if (q.isEmpty()) return stations
        return stations.filter { s ->
            s.title.lowercase().contains(q) ||
                s.rds?.lowercase()?.contains(q) == true ||
                formatFreq(s.band, s.freq).lowercase().contains(q) ||
                freqText(s.band, s.freq).contains(q)
        }
    }

    // ---------- частоты ----------

    /** Ключ станции: диапазон + частота с шагом канала. */
    fun keyOf(band: String, freq: Double): String =
        if (band == RadioHub.AM) "am:${round(freq).toInt()}" else "fm:${round(freq * 10).toInt()}"

    /** Частота с шагом канала: FM — 0.1 МГц, AM — 1 кГц. */
    fun normalize(band: String, freq: Double): Double =
        if (band == RadioHub.AM) round(freq).toDouble() else round(freq * 10) / 10.0

    /** Частота строкой без единицы: «101.5», «999». */
    fun freqText(band: String, freq: Double): String {
        val f = normalize(band, freq)
        return if (band == RadioHub.AM) f.toInt().toString() else {
            val s = String.format(java.util.Locale.US, "%.1f", f)
            if (s.endsWith(".0")) s.dropLast(2) else s
        }
    }

    /** Частота с единицей: «101.5 МГц», «999 кГц». */
    fun formatFreq(band: String, freq: Double): String =
        if (band == RadioHub.AM) "${freqText(band, freq)} кГц" else "${freqText(band, freq)} МГц"

    /** Частоты совпадают (с допуском на шаг канала). */
    fun sameFreq(band: String, a: Double, b: Double): Boolean {
        val tol = if (band == RadioHub.AM) 0.5 else 0.06
        return abs(normalize(band, a) - normalize(band, b)) <= tol
    }

    // ---------- JSON (чистые функции — покрыты тестами) ----------

    fun parse(raw: String?): List<Station> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val band = o.optString("b").takeIf { it == RadioHub.FM || it == RadioHub.AM }
                    ?: return@mapNotNull null
                val freq = o.optDouble("f", Double.NaN)
                if (freq.isNaN()) return@mapNotNull null
                Station(
                    band = band,
                    freq = normalize(band, freq),
                    name = o.optString("n").takeIf { it.isNotBlank() },
                    rds = o.optString("r").takeIf { it.isNotBlank() },
                    favorite = o.optBoolean("v")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun serialize(stations: List<Station>): String {
        val arr = JSONArray()
        stations.forEach { s ->
            arr.put(
                JSONObject()
                    .put("b", s.band)
                    .put("f", s.freq)
                    .put("n", s.name ?: "")
                    .put("r", s.rds ?: "")
                    .put("v", s.favorite)
            )
        }
        return arr.toString()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "radio_stations"
    private const val KEY_LIST = "list"
}
