package com.dash.headunit.car

import android.content.Context

/**
 * Бортовой компьютер: один счётчик текущей поездки
 * (автосброс при запуске двигателя) + журнал завершённых поездок.
 *
 * Источники: одометр (Desay 4,71), топливо %, время работы двигателя
 * (charging или напряжение >= 13 В). Расход = сожжённые литры / км.
 * Если доступен бортовой компьютер приборки ([ClusterTrip], a001), пробег,
 * расход и средняя скорость берутся с него (те же цифры, что на приборке,
 * и тоже «после запуска»); свой расчёт — запасной вариант.
 * Всё состояние переживает перезапуск (SharedPreferences).
 */
object TripTracker {

    data class TripView(
        /** Пройдено, км. */
        val distKm: Float?,
        /** Средний расход, л/100 км. */
        val avgCons: Float?,
        /** Средняя скорость, км/ч. */
        val avgSpeedKmh: Float?,
        /** Время в движении, мс. */
        val driveMillis: Long,
        /** Данные приборки, если пробег/расход/скорость взяты с неё. */
        val cluster: ClusterTrip? = null
    )

    /** Одна завершённая поездка в журнале. */
    data class TripEntry(
        /** Старт поездки (пуск двигателя или запуск приложения), wall ms. */
        val startTs: Long,
        /** Финиш (глушение двигателя), wall ms. */
        val endTs: Long,
        /** Пройдено, км. */
        val distKm: Float,
        /** Время поездки (от пуска двигателя до глушения), мс. */
        val driveMillis: Long,
        /** Средний расход, л/100 км (может отсутствовать). */
        val avgCons: Float?,
        /** Средняя скорость, км/ч (может отсутствовать). */
        val avgSpeedKmh: Float? = null
    )

    data class Period(
        var odoStartKm: Int? = null,
        var fuelPctStart: Float? = null,
        var driveMillis: Long = 0,
        var lastTs: Long = 0,
        var startTs: Long = 0
    )

    private var prevEngineOn: Boolean? = null

    /** Последний снимок приборки при работающем двигателе — для журнала. */
    private var lastClusterOn: ClusterTrip? = null

    /**
     * «Ослепли» при работающем двигателе (шина уснула / сервис отвалился):
     * с этого момента не считаем двигатель отключённым по отсутствию
     * данных — ждём либо свежий снимок (ложная тревога), либо тайм-аут
     * [BLIND_GRACE_MS], после которого поездка закрывается по сохранённому
     * пробегу (update с data=null ничего в счётчик не дописал).
     */
    private var blindSince: Long = 0L

    /**
     * Двигатель работает. Любой надёжный сигнал «заглушен» сильнее любого
     * «работает»: обороты Desay (4,62/4) ниже холостых ИЛИ честный флаг
     * Desay (0,27) через [CarData.charging] → «заглушено» — контраглэнды
     * (например 4,62 может висеть >0 после глушения) не порывают финал поездки.
     * Если «заглушено» не видно, а есть обороты выше холостых / флаг / бортсеть
     * >= 13 В (альтернатор) — работаем.
     */
    fun engineOn(data: CarData?): Boolean {
        if (data == null) return false
        data.rpm?.let { if (it <= 400) return false }
        data.charging?.let { if (!it) return false }
        data.rpm?.let { return it > 400 }
        data.charging?.let { return it }
        return (data.voltageV ?: 0f) >= 13.0f
    }

    /**
     * Обновить счётчик новым снимком. Возвращает вид текущей поездки.
     * При переходе «работает ↔ заглушили» завершённая поездка дописывается
     * в журнал. Данные сюда передаются от [CarHub.freshData]: старый
     * снимок «работающего» двигателя может завесить переход «заглушили».
     *
     * Переходы:
     * - свежий снимок, работает → база/счёт, всё как раньше;
     * - свежий снимок, заглушили → поездка в журнал немедленно;
     * - снимок пропал при работающем двигателе → ловим момент: возврат
     *   данных в пределах grace — ложная тревога (короткая заминка шины),
     *   после тайм-аута поездка финализируется по сохранённому пробегу
     *   и топливу;
     * - процесс перезапускается на парковке (двигатель погашен давно) —
     *   в журнал ничего лишнего не пишем.
     *
     * Чистая относительно prefs часть вынесена в [compute] (тестируется).
     */
    fun update(
        context: Context,
        data: CarData?,
        tankLiters: Int,
        cluster: ClusterTrip? = null,
        now: Long = System.currentTimeMillis(),
        keepDays: Int = 0
    ): TripView {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        migrate(prefs)
        if (keepDays > 0) {
            // Смена настройки / новый день: старые записи уходят сразу,
            // ещё до любых переходов поездок.
            prunePrefs(prefs, keepDays, now)
        }
        val t = load(prefs, PREFIX)

        val on = engineOn(data)
        // prevEngineOn живёт только в памяти: после перезапуска процесса
        // (перезагрузка ГУ, убийство) восстанавливаем из prefs, иначе
        // переход «заглушили» потеряется и поездка не попадёт в журнал.
        if (prevEngineOn == null) {
            prevEngineOn = prefs.getBoolean("last_engine", false)
        }
        // Незакрытый «слепой» интервал из прошлой жизни процесса.
        if (blindSince == 0L && prevEngineOn == true) {
            blindSince = prefs.getLong("blind_ts", 0L)
        }
        if (lastClusterOn == null && prevEngineOn == true) {
            lastClusterOn = loadCluster(prefs)
        }
        // Поездка началась до запуска процесса (ГУ проснулось в движении):
        // время поездки — от запуска приложения, момент пуска не знаем.
        if (prevEngineOn == true && t.startTs <= 0L) {
            t.startTs = now
        }

        // ГУ засыпает вместе с зажиганием: процесс замирает (или его
        // убивают), и ни переход «заглушили», ни «слепой» тайм-аут не
        // наступают — утром первый же снимок «работает» продолжил бы
        // вчерашнюю поездку. Разрыв с последнего живого снимка работающего
        // двигателя дольше [GAP_MS] = поездка закончилась тогда.
        val lastOnTs = prefs.getLong("last_on_ts", 0L)
        if (prevEngineOn == true && data != null &&
            isGap(lastOnTs, now)
        ) {
            finalizeBlind(prefs, t, tankLiters, lastOnTs)
            prevEngineOn = false
            blindSince = 0L
        }

        val v = withCluster(compute(t, data, tankLiters, on, now), cluster)
        if (on && cluster != null) {
            lastClusterOn = cluster
            saveCluster(prefs, cluster)
        }

        var blindOpen = false
        if (blindSince > 0L) {
            if (data == null) {
                if (now - blindSince >= BLIND_GRACE_MS) {
                    // Шина молчит достаточно долго — закрываем поездку тем,
                    // что успели сохранить: пробег = last_odo − база одометра.
                    finalizeBlind(prefs, t, tankLiters, blindSince)
                    prevEngineOn = false
                    blindSince = 0L
                } else {
                    blindOpen = true
                }
            } else {
                // Данные вернулись.
                val wasBlind = blindSince
                blindSince = 0L
                if (!on) {
                    // Заглушили, пока мы ослепли; снимок живой — считаем
                    // по нему, как при живом переходе.
                    finalizeLive(prefs, t, v, cluster, now, wasBlind)
                    prevEngineOn = false
                }
                // on — ложная тревога (заминка шины в движении или утренний
                // запуск): счётчик продолжается, ничего не закрываем.
            }
        } else if (prevEngineOn == true && data != null) {
            if (!on) {
                // Живой наблюдаемый переход «заглушили». Приборка после
                // выключения может обнулить a001, поэтому берём её последний
                // снимок с работающим двигателем.
                finalizeLive(prefs, t, v, cluster, now, now)
            }
            prevEngineOn = on
        } else if (prevEngineOn == true && data == null) {
            // Двигатель, по последним данным, работал, но снимок пропал:
            // не считаем «заглушили» — ловим момент (grace), счётчик живёт.
            blindSince = now
            blindOpen = true
        } else {
            if (prevEngineOn == false && on) {
                // Запуск двигателя — счётчик с нуля.
                reset(t)
                t.startTs = now
                lastClusterOn = null
                prefs.edit().remove(KEY_CLUSTER).apply()
            }
            prevEngineOn = on
        }

        save(prefs, PREFIX, t)
        saveBlind(prefs, blindSince)
        val edit = prefs.edit()
            .putBoolean("last_engine", blindOpen || on)
            .putBoolean("has_data", data != null)
        if (data != null && on) edit.putLong("last_on_ts", now)
        if (data != null) {
            // last_odo/last_fuel — база слепой финализации; при ослеплении
            // не портим их пустыми значениями.
            edit.putInt("last_odo", data.odoKm ?: -1)
            edit.putFloat("last_fuel", data.fuelPct ?: -1f)
        }
        edit.apply()
        return v
    }

    /** Запомнить момент ослепновения/выхода из него (переносит рестарт процесса). */
    private fun saveBlind(prefs: android.content.SharedPreferences, ts: Long) {
        prefs.edit().putLong("blind_ts", ts).apply()
    }

    /** Живой переход «заглушили» (со снимком): цифры из текущего расчёта/приборки. */
    private fun finalizeLive(
        prefs: android.content.SharedPreferences,
        t: Period,
        v: TripView,
        cluster: ClusterTrip?,
        now: Long,
        endFallback: Long
    ) {
        val start = if (t.startTs > 0) t.startTs else now - v.driveMillis
        val final = withCluster(v.copy(cluster = null), lastClusterOn ?: cluster)
        finalizeTrip(start, endFallback, final)?.let { pushEntry(prefs, it) }
        lastClusterOn = null
        prefs.edit().remove(KEY_CLUSTER).apply()
    }

    /**
     * Слепая финализация: данных нет, но прошли grace — двигатель точно глушён.
     * Пробег и расход — от последнего сохранённого снимка (last_odo/last_fuel).
     */
    private fun finalizeBlind(
        prefs: android.content.SharedPreferences,
        t: Period,
        tankLiters: Int,
        end: Long
    ) {
        val lastOdo = prefs.getInt("last_odo", -1)
        val dist = if (lastOdo >= 0 && t.odoStartKm != null) {
            (lastOdo - t.odoStartKm!!).toFloat().takeIf { it >= 0 }
        } else null
        val lastFuel = prefs.getFloat("last_fuel", -1f)
        val cons = if (dist != null && dist >= MIN_DIST_KM &&
            t.fuelPctStart != null && lastFuel >= 0
        ) {
            val used = (t.fuelPctStart!! - lastFuel) / 100f * tankLiters
            (used / dist * 100f).takeIf { it >= 0 && it <= tankLiters }
        } else null
        // Приборка точнее одометра в целых км — её последний снимок
        // с работающим двигателем (переживает рестарт процесса).
        val v = withCluster(TripView(dist, cons, null, t.driveMillis), lastClusterOn)
            .copy(cluster = null)
        val start = if (t.startTs > 0) t.startTs else end - v.driveMillis
        finalizeTrip(start, end, v)?.let { pushEntry(prefs, it) }
        lastClusterOn = null
        prefs.edit().remove(KEY_CLUSTER).apply()
        reset(t)
        t.startTs = 0
        save(prefs, PREFIX, t)
    }

    /**
     * Разрыв между живыми снимками работающего двигателя: ГУ спало.
     * Скачок часов назад (синхронизация времени после загрузки) — не разрыв.
     * Чистая — тестируется.
     */
    internal fun isGap(lastOnTs: Long, now: Long): Boolean =
        lastOnTs > 0L && now - lastOnTs >= GAP_MS

    /** Снимок приборки «км;расход;скорость» — переживает рестарт процесса. */
    private fun saveCluster(prefs: android.content.SharedPreferences, c: ClusterTrip) {
        prefs.edit().putString(
            KEY_CLUSTER,
            "${c.tripKm ?: ""};${c.avgFuelL100 ?: ""};${c.avgSpeedKmh ?: ""}"
        ).apply()
    }

    private fun loadCluster(prefs: android.content.SharedPreferences): ClusterTrip? {
        val p = (prefs.getString(KEY_CLUSTER, null) ?: return null).split(';')
        if (p.size != 3) return null
        fun f(x: String) = x.ifBlank { null }?.toFloatOrNull()
        val km = f(p[0]) ?: return null
        return ClusterTrip(null, km, null, f(p[1]), f(p[2]), null)
    }

    /** Запись в журнал по итогам поездки; пустые «поездки» отбрасываются. */
    private fun finalizeTrip(startTs: Long, endTs: Long, v: TripView): TripEntry? =
        finalizeEntry(startTs, endTs, v)

    /**
     * Подменить пробег/расход/скорость данными приборки; чего на приборке
     * нет (первые ~100 м расход и скорость = «нет данных») — остаётся своё.
     * Чистая — тестируется.
     */
    internal fun withCluster(v: TripView, cluster: ClusterTrip?): TripView {
        if (cluster?.tripKm == null) return v
        return v.copy(
            distKm = cluster.tripKm,
            avgCons = cluster.avgFuelL100 ?: v.avgCons,
            avgSpeedKmh = cluster.avgSpeedKmh ?: v.avgSpeedKmh,
            cluster = cluster
        )
    }

    /**
     * Запись в журнал по итогам поездки. Время поездки — от пуска
     * двигателя (или от запуска приложения, если пуск не заметили):
     * интервал между [startTs] и [endTs]. Если приборка не дала свою
     * среднюю скорость — считаем пробег за время поездки.
     * Чистая — тестируется.
     */
    internal fun finalizeEntry(startTs: Long, endTs: Long, v: TripView): TripEntry? {
        val dist = v.distKm ?: 0f
        if (dist < 0.1f && v.driveMillis < 60_000L) return null
        val duration = (endTs - startTs).coerceAtLeast(0L)
        val tripMillis = maxOf(v.driveMillis, duration).coerceAtMost(MAX_DT_MS)
        val hours = tripMillis / 3600000.0f
        val avgSpeed = v.avgSpeedKmh
            ?: (dist / hours).takeIf { hours > 1.0f / 60 && dist > 0 }
        return TripEntry(startTs, endTs, dist, tripMillis, v.avgCons, avgSpeed)
    }

    /** Журнал завершённых поездок, свежие сверху. */
    fun journal(context: Context): List<TripEntry> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return decodeJournal(prefs.getString(KEY_JOURNAL, "") ?: "")
    }

    fun clearJournal(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_JOURNAL).apply()
    }

    /**
     * Удалить из журнала записи старше [keepDays] дней (0 — ничего не трогать,
     * журнал хранится целиком). Вызывается из цикла опроса и сразу после
     * смены настройки.
     */
    fun applyJournalKeep(
        context: Context,
        keepDays: Int,
        now: Long = System.currentTimeMillis()
    ) {
        if (keepDays <= 0) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prunePrefs(prefs, keepDays, now)
    }

    /** Обрезка журнала под [keepDays] дней, если записи уже есть. */
    private fun prunePrefs(
        prefs: android.content.SharedPreferences,
        keepDays: Int,
        now: Long
    ) {
        val raw = prefs.getString(KEY_JOURNAL, "").takeUnless { it.isNullOrBlank() }
            ?: return
        val list = decodeJournal(raw)
        val pruned = pruneJournal(list, keepDays, now)
        if (pruned.size != list.size) {
            prefs.edit().putString(KEY_JOURNAL, encodeJournal(pruned)).apply()
        }
    }

    private fun pushEntry(
        prefs: android.content.SharedPreferences,
        e: TripEntry
    ) {
        val list = (listOf(e) + decodeJournal(prefs.getString(KEY_JOURNAL, "") ?: ""))
            .take(MAX_JOURNAL)
        prefs.edit().putString(KEY_JOURNAL, encodeJournal(list)).apply()
    }

    /**
     * Сериализация журнала: «s,e,d,m,c,sp;…», расход и скорость могут
     * быть пустыми. Старые записи из 5 полей читаются (без скорости).
     * Тестируется.
     */
    internal fun encodeJournal(list: List<TripEntry>): String =
        list.joinToString(";") {
            "${it.startTs},${it.endTs},${it.distKm},${it.driveMillis}," +
                "${it.avgCons ?: ""},${it.avgSpeedKmh ?: ""}"
        }

    /**
     * Оставить в [list] только поездки не старше [keepDays] дней от [now]
     * (0 — не ограничивать). Свежие записи идут первыми, порядок сохраняется.
     * Записи считаются по времени завершения. Тестируется.
     */
    internal fun pruneJournal(
        list: List<TripEntry>,
        keepDays: Int,
        now: Long
    ): List<TripEntry> =
        if (keepDays <= 0) {
            list
        } else {
            val cutoff = now - keepDays * DAY_MS
            list.filter { it.endTs >= cutoff }
        }

    /** Разбор [encodeJournal]; битые записи пропускаются. Тестируется. */
    internal fun decodeJournal(raw: String): List<TripEntry> {
        if (raw.isBlank()) return emptyList()
        return raw.split(';').mapNotNull { rec ->
            try {
                val p = rec.split(',')
                if (p.size !in 5..6) return@mapNotNull null
                val s = p[0].toLong()
                val e = p[1].toLong()
                val d = p[2].toFloat()
                val m = p[3].toLong()
                if (s <= 0 || e < s || d < 0 || m < 0) return@mapNotNull null
                val c = p[4].ifBlank { null }?.toFloat()
                val sp = p.getOrNull(5)?.ifBlank { null }?.toFloat()
                TripEntry(s, e, d, m, c, sp)
            } catch (ex: Exception) {
                null
            }
        }
    }

    /** Переезд со старого двухсчётчикового формата: «a» становится текущим. */
    private fun migrate(prefs: android.content.SharedPreferences) {
        if (prefs.contains("${PREFIX}_odo") || !prefs.contains("a_odo")) return
        prefs.edit()
            .putInt("${PREFIX}_odo", prefs.getInt("a_odo", -1))
            .putFloat("${PREFIX}_fuel", prefs.getFloat("a_fuel", -1f))
            .putLong("${PREFIX}_drive", prefs.getLong("a_drive", 0))
            .putLong("${PREFIX}_ts", prefs.getLong("a_ts", 0))
            .apply()
    }

    private fun reset(p: Period) {
        p.odoStartKm = null
        p.fuelPctStart = null
        p.driveMillis = 0
        p.lastTs = 0
        p.startTs = 0
    }

    /**
     * Один шаг счётчика. Видимая для тестов.
     */
    fun compute(
        state: Period,
        data: CarData?,
        tankLiters: Int,
        engineOn: Boolean,
        now: Long
    ): TripView {
        val odo = data?.odoKm
        val fuel = data?.fuelPct
        // Без одометра пробег не посчитать вовсе.
        if (odo == null || odo < 0) {
            return TripView(null, null, null, state.driveMillis)
        }
        // Заправка (топливо выросло заметно) — новая база, пробег с начала.
        if (fuel != null && state.fuelPctStart != null && fuel > state.fuelPctStart!! + REFUEL_PCT) {
            val dt = (now - state.lastTs).coerceIn(0, MAX_DT_MS)
            if (engineOn) state.driveMillis += dt
            state.odoStartKm = odo
            state.fuelPctStart = fuel
            state.lastTs = now
            return TripView(0f, null, null, state.driveMillis)
        }
        // Первая база: одометр сразу, топливо — как только появится
        // (тогда пробег/скорость работают и без источника топлива).
        if (state.odoStartKm == null || (fuel != null && state.fuelPctStart == null)) {
            state.odoStartKm = odo
            state.fuelPctStart = fuel
            state.lastTs = now
            return TripView(0f, null, null, state.driveMillis)
        }
        val dt = (now - state.lastTs).coerceIn(0, MAX_DT_MS)
        state.lastTs = now
        // Проверка скачка одометра относительно реально прошедшего времени
        // (максимум 300 км/ч + запас 5 км на короткие интервалы).
        val maxJump = maxOf(5.0, dt / 3600000.0 * 300.0)
        var dist = odo - (state.odoStartKm ?: odo)
        if (dist < 0 || dist > maxJump) {
            // Сброс/глюк одометра — перебазируемся, время сохраняем.
            state.odoStartKm = odo
            state.fuelPctStart = fuel
            dist = 0
        }
        if (engineOn) {
            state.driveMillis += dt
        }
        val fuelStart = state.fuelPctStart
        val fuelUsed = if (fuel != null && fuelStart != null) {
            (fuelStart - fuel) / 100f * tankLiters
        } else {
            null
        }
        val avgCons = if (
            dist >= MIN_DIST_KM && fuelUsed != null &&
            fuelUsed >= 0 && fuelUsed <= tankLiters
        ) {
            fuelUsed / dist * 100f
        } else {
            null
        }
        val hours = state.driveMillis / 3600000.0
        val avgSpeed = if (hours > 1.0 / 60 && dist > 0) {
            (dist / hours).toFloat()
        } else {
            null
        }
        return TripView(dist.toFloat(), avgCons, avgSpeed, state.driveMillis)
    }

    private fun load(
        prefs: android.content.SharedPreferences,
        prefix: String
    ): Period = Period(
        odoStartKm = prefs.getInt("${prefix}_odo", -1).takeIf { it >= 0 },
        fuelPctStart = prefs.getFloat("${prefix}_fuel", -1f).takeIf { it >= 0 },
        driveMillis = prefs.getLong("${prefix}_drive", 0),
        lastTs = prefs.getLong("${prefix}_ts", 0),
        startTs = prefs.getLong("${prefix}_start", 0)
    )

    private fun save(
        prefs: android.content.SharedPreferences,
        prefix: String,
        p: Period
    ) {
        prefs.edit()
            .putInt("${prefix}_odo", p.odoStartKm ?: -1)
            .putFloat("${prefix}_fuel", p.fuelPctStart ?: -1f)
            .putLong("${prefix}_drive", p.driveMillis)
            .putLong("${prefix}_ts", p.lastTs)
            .putLong("${prefix}_start", p.startTs)
            .apply()
    }

    private const val PREFS = "trip_prefs"
    /** Префикс единственного (автоматического) счётчика. */
    private const val PREFIX = "t"
    private const val KEY_JOURNAL = "journal"
    /** Сутки в мс — для обрезки журнала по сроку хранения. */
    private const val DAY_MS = 24L * 3600_000L
    /** Сколько завершённых поездок храним. */
    private const val MAX_JOURNAL = 30
    private const val MAX_DT_MS = 24 * 3600_000L
    private const val MIN_DIST_KM = 1
    /** Рост топлива больше этого (п.п.) — заправка, база расхода новая. */
    private const val REFUEL_PCT = 5f
    /**
     * Сколько ждём возвращения шины после пропажи снимка при работающем
     * двигателе, прежде чем закрывать поездку «вслепую».
     */
    private const val BLIND_GRACE_MS = 60_000L
    /**
     * Перерыв в живых снимках работающего двигателя, после которого
     * считаем, что ГУ спало и поездка закончилась (опрос — раз в 5 с).
     */
    private const val GAP_MS = 120_000L
    private const val KEY_CLUSTER = "last_cluster"

    /** Диагностика для debug-экрана. */
    fun debugLines(context: Context): List<String> {
        val out = mutableListOf<String>()
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            out.add(
                "trip: has_data=${prefs.getBoolean("has_data", false)} " +
                    "last_odo=${prefs.getInt("last_odo", -1)} " +
                    "last_fuel=${prefs.getFloat("last_fuel", -1f)} " +
                    "last_engine=${prefs.getBoolean("last_engine", false)} " +
                    "last_on_ts=${prefs.getLong("last_on_ts", 0L)}"
            )
            for (prefix in listOf("t")) {
                out.add(
                    "trip: $prefix odoStart=${prefs.getInt("${prefix}_odo", -1)} " +
                        "fuelStart=${prefs.getFloat("${prefix}_fuel", -1f)} " +
                        "driveMs=${prefs.getLong("${prefix}_drive", 0)} " +
                        "ts=${prefs.getLong("${prefix}_ts", 0)} " +
                        "start=${prefs.getLong("${prefix}_start", 0)}"
                )
            }
            val journal = decodeJournal(prefs.getString(KEY_JOURNAL, "") ?: "")
            out.add("trip: journal n=${journal.size}")
            out.add(
                "trip: blind_ts=${prefs.getLong("blind_ts", 0L)} " +
                    "last_on_saved=${prefs.getBoolean("last_engine", false)}"
            )
            val hub = CarHub.data
            out.add(
                "trip: hub fuel=${hub?.fuelPct} odo=${hub?.odoKm} " +
                    "volt=${hub?.voltageV} charging=${hub?.charging} " +
                    "src=${hub?.source}"
            )
        } catch (e: Exception) {
            out.add("trip: err ${e.message}")
        }
        return out
    }
}
