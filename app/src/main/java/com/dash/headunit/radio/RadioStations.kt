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
    const val MAX_FAVORITES = 12

    /**
     * Цвета значков станций. Без своего цвета станция берёт цвет по
     * частоте ([colorOf]) — он не меняется при переименовании.
     */
    val PALETTE = intArrayOf(
        0xFF4DA3FF.toInt(), // синий
        0xFF26C6DA.toInt(), // бирюзовый
        0xFF66BB6A.toInt(), // зелёный
        0xFFC0CA33.toInt(), // салатовый
        0xFFFFB300.toInt(), // янтарный
        0xFFFF7043.toInt(), // оранжевый
        0xFFEF5350.toInt(), // красный
        0xFFEC407A.toInt(), // розовый
        0xFFAB47BC.toInt(), // фиолетовый
        0xFF7E57C2.toInt(), // сиреневый
        0xFF8D6E63.toInt(), // коричневый
        0xFF78909C.toInt()  // серый
    )

    /**
     * Станция.
     *
     * @param band "fm" или "am"
     * @param freq частота: МГц для FM, кГц для AM
     * @param name своё название — задаёт пользователь
     * @param rds название, которое сообщил тюнер (RDS)
     * @param favorite в избранном
     * @param color свой цвет значка (null — по частоте, см. [colorOf])
     * @param favOrder место в избранном (меньше — левее)
     * @param missing не нашлась при последнем полном сканировании диапазона
     */
    data class Station(
        val band: String,
        val freq: Double,
        val name: String? = null,
        val rds: String? = null,
        val favorite: Boolean = false,
        val color: Int? = null,
        val favOrder: Int = 0,
        val missing: Boolean = false
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
            // Станцию видели (или добавили снова) — она уже не «не найдена».
            out[i] = old.copy(
                rds = rds?.takeIf { it.isNotBlank() } ?: old.rds,
                name = old.name,
                missing = false
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
        if (out[i].favorite == favorite) return out
        if (favorite && favorites(out).size >= MAX_FAVORITES) return null
        // Новое избранное — в конец ряда.
        val order = if (favorite) (favorites(out).maxOfOrNull { it.favOrder } ?: 0) + 1 else 0
        out[i] = out[i].copy(favorite = favorite, favOrder = order)
        return out
    }

    /** Сдвинуть станцию в ряду избранного на [delta] мест (−1 — влево). */
    fun moveFavorite(context: Context, band: String, freq: Double, delta: Int) {
        save(context, movedIn(load(context), band, freq, delta))
    }

    /**
     * Чистая часть [moveFavorite]. Места избранного сначала нумеруются
     * заново (у старых записей их нет), потом станция меняется местами
     * с соседом.
     */
    fun movedIn(list: List<Station>, band: String, freq: Double, delta: Int): List<Station> {
        val favs = favorites(list).toMutableList()
        val key = keyOf(band, freq)
        val from = favs.indexOfFirst { keyOf(it.band, it.freq) == key }
        if (from < 0) return list
        val to = (from + delta).coerceIn(0, favs.lastIndex)
        if (to != from) favs.add(to, favs.removeAt(from))
        val order = favs.mapIndexed { i, s -> keyOf(s.band, s.freq) to i + 1 }.toMap()
        return list.map { s ->
            order[keyOf(s.band, s.freq)]?.let { s.copy(favOrder = it) } ?: s
        }
    }

    /** Свой цвет значка станции (null — по частоте). Станции нет — добавим. */
    fun setColor(context: Context, band: String, freq: Double, color: Int?) {
        val list = upsertIn(load(context), band, freq)
        save(context, coloredIn(list, band, freq, color))
    }

    /** Чистая часть [setColor]. */
    fun coloredIn(list: List<Station>, band: String, freq: Double, color: Int?): List<Station> {
        val key = keyOf(band, freq)
        return list.map { if (keyOf(it.band, it.freq) == key) it.copy(color = color) else it }
    }

    /** Цвет значка: свой или по частоте из [PALETTE]. */
    fun colorOf(band: String, freq: Double, own: Int? = null): Int {
        if (own != null) return own
        val h = keyOf(band, freq).hashCode()
        return PALETTE[((h % PALETTE.size) + PALETTE.size) % PALETTE.size]
    }

    fun colorOf(station: Station): Int = colorOf(station.band, station.freq, station.color)

    /**
     * Буквы для значка станции: «Дорожное радио» → «ДР», «Маяк» → «М»,
     * «Europa Plus» → «EP». Без названия — пусто (значок покажет частоту).
     * «Радио» в начале пропускается, если после него что-то есть:
     * «Радио Рекорд» → «Р».
     */
    fun initials(title: String?): String {
        val words = (title ?: "").split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        if (words.isEmpty() || words.all { w -> w.all { it.isDigit() } }) return ""
        val skip = setOf("радио", "radio")
        val meaningful = if (words.size > 1 && words[0].lowercase() in skip) words.drop(1) else words
        return meaningful.take(2).joinToString("") { it.take(1).uppercase() }
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

    /** Избранные станции — в порядке ряда ([Station.favOrder], затем порядок в списке). */
    fun favorites(stations: List<Station>): List<Station> =
        stations.filter { it.favorite }.sortedBy { it.favOrder }

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

    // ---------- сканирование ----------

    /**
     * Полное сканирование диапазона [band] закончено: станции, которые
     * тюнер нашёл ([found] — ключи [keyOf]), отмечаются найденными,
     * остальные этого диапазона — «не найдена».
     */
    fun markScan(context: Context, band: String, found: Set<String>) {
        save(context, markedIn(load(context), band, found))
    }

    /** Чистая часть [markScan]. */
    fun markedIn(list: List<Station>, band: String, found: Set<String>): List<Station> =
        list.map { s ->
            if (s.band != band) s else s.copy(missing = keyOf(s.band, s.freq) !in found)
        }

    /** Убрать из списка ненайденные станции диапазона [band] (избранные остаются). */
    fun removeMissing(context: Context, band: String?) {
        save(context, load(context).filterNot { it.missing && !it.favorite && (band == null || it.band == band) })
    }

    // ---------- сортировка и соседние станции ----------

    const val SORT_FREQ = "freq"
    const val SORT_NAME = "name"
    const val SORT_LISTENED = "listened"

    /**
     * Станции по [mode]: по частоте, по названию или по времени
     * прослушивания ([listened] — мс по ключу [keyOf]), при равенстве — по
     * частоте. AM всегда после FM.
     */
    fun sorted(
        stations: List<Station>,
        mode: String,
        listened: Map<String, Long> = emptyMap()
    ): List<Station> {
        val byFreq = compareBy<Station>({ if (it.band == RadioHub.AM) 1 else 0 }, { it.freq })
        return when (mode) {
            SORT_NAME -> stations.sortedWith(
                compareBy<Station> { it.title.lowercase() }.then(byFreq)
            )
            SORT_LISTENED -> stations.sortedWith(
                compareByDescending<Station> { listened[keyOf(it.band, it.freq)] ?: 0L }.then(byFreq)
            )
            else -> stations.sortedWith(byFreq)
        }
    }

    /** Режим кнопок ⏮ ⏭: поиск станции тюнером. */
    const val SKIP_SEEK = "seek"
    /** ⏮ ⏭ — по ряду избранного (с переходом между диапазонами). */
    const val SKIP_FAVORITES = "favorites"
    /** ⏮ ⏭ — по своему списку станций текущего диапазона, по частоте. */
    const val SKIP_LIST = "list"

    /**
     * Соседняя станция для ⏮ ([dir] = −1) / ⏭ (+1) в режиме [mode].
     * [band]/[freq] — откуда считать (текущая станция или цель идущего
     * переключения). null — переходить некуда (список пуст или в нём только
     * эта станция) — тогда кнопка ищет станцию тюнером.
     */
    fun neighbor(
        stations: List<Station>,
        mode: String,
        band: String,
        freq: Double?,
        dir: Int
    ): Station? {
        val key = freq?.let { keyOf(band, it) }
        if (mode == SKIP_FAVORITES) {
            val favs = favorites(stations)
            if (favs.isEmpty()) return null
            val i = favs.indexOfFirst { keyOf(it.band, it.freq) == key }
            val next = when {
                i >= 0 -> favs[((i + dir) % favs.size + favs.size) % favs.size]
                dir > 0 -> favs.first()
                else -> favs.last()
            }
            return next.takeIf { keyOf(it.band, it.freq) != key }
        }
        if (mode == SKIP_LIST) {
            val list = stations.filter { it.band == band && !it.missing }.sortedBy { it.freq }
            if (list.isEmpty()) return null
            val next = if (freq == null) {
                if (dir > 0) list.first() else list.last()
            } else if (dir > 0) {
                list.firstOrNull { it.freq > freq && !sameFreq(band, it.freq, freq) } ?: list.first()
            } else {
                list.lastOrNull { it.freq < freq && !sameFreq(band, it.freq, freq) } ?: list.last()
            }
            return next.takeIf { keyOf(it.band, it.freq) != key }
        }
        return null
    }

    // ---------- частоты ----------

    /** Станция по ключу [keyOf]: «fm:1015» → FM 101.5. null — ключ не наш. */
    fun fromKey(key: String): Station? {
        val band = key.substringBefore(':', "")
        val n = key.substringAfter(':', "").toIntOrNull() ?: return null
        return when (band) {
            RadioHub.FM -> Station(band, n / 10.0)
            RadioHub.AM -> Station(band, n.toDouble())
            else -> null
        }
    }

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
                    favorite = o.optBoolean("v"),
                    color = if (o.has("c")) o.optInt("c") else null,
                    favOrder = o.optInt("o", 0),
                    missing = o.optBoolean("m")
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
                    .apply {
                        s.color?.let { put("c", it) }
                        if (s.favorite && s.favOrder > 0) put("o", s.favOrder)
                        if (s.missing) put("m", true)
                    }
            )
        }
        return arr.toString()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "radio_stations"
    private const val KEY_LIST = "list"
}
