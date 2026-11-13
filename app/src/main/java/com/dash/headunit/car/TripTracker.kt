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
 * Накопительные моточасы (в движении / холостой ход) живут отдельно от
 * поездки и сбрасываются только вручную.
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

    /**
     * Накопительные моточасы. Всего — сумма [movingMillis] и [idleMillis];
     * отдельного поля нет, чтобы разбивка не разошлась с итогом.
     */
    data class EngineHours(
        /** Время работы двигателя в движении, мс. */
        val movingMillis: Long = 0,
        /** Время работы двигателя на холостом ходу, мс. */
        val idleMillis: Long = 0
    ) {
        val totalMillis: Long get() = movingMillis + idleMillis
    }

    /** Сохраняемое состояние накопителя моточасов (переживает перезапуск). */
    internal class EngineClock(
        var movingMillis: Long = 0,
        var idleMillis: Long = 0,
        var lastTs: Long = 0,
        var lastOn: Boolean = false,
        var lastOdoKm: Int? = null
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

    // Состояние между тиками держим в памяти (источник правды), а в prefs —
    // только копию для перезапуска процесса: раньше каждые 5 с (24/7, в
    // главном потоке) были 4–6 записей с полной перезаписью XML вместе с
    // журналом. Теперь — только при переходах (см. [FlushKey]): пуск,
    // глушение, «ослепли»/данные вернулись, смена базы (решение 2026-11-13),
    // плюс [persist] при засыпании ГУ / уходе дашборда с экрана. Если
    // процесс убьют на ходу без этих событий, теряется текущая поездка.
    private var memTrip: Period? = null
    private var memClock: EngineClock? = null
    private var memLastOnTs = -1L
    private var memLastOdo = -1
    private var memLastFuel = -1f
    private var memLoaded = false
    private var flushedKey: FlushKey? = null
    private var prunedAt = 0L
    private var prunedKeep = 0

    /** Изменилось что-то из этого — пишем состояние в prefs (иначе не пишем вовсе). */
    private data class FlushKey(
        val prevEngineOn: Boolean?,
        val blindSince: Long,
        val lastEngine: Boolean,
        val hasData: Boolean,
        val odoStartKm: Int?,
        val fuelPctStart: Float?,
        val startTs: Long,
    )

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
     * Один шаг накопителя моточасов. Прибавляем интервал только тогда,
     * когда двигатель работал и на прошлом, и на текущем свежем снимке:
     * стоянка, сон ГУ и пауза в данных в счёт не идут. Разрыв больше
     * [GAP_MS] — это уже новая сессия (иначе утренний пуск добавил бы ночь).
     * Чистая — тестируется.
     */
    internal fun accumulateEngineHours(
        clock: EngineClock,
        data: CarData?,
        engineOn: Boolean,
        now: Long,
        gapMs: Long = GAP_MS
    ): EngineHours {
        if (data == null) {
            return EngineHours(clock.movingMillis, clock.idleMillis)
        }
        val dt = (now - clock.lastTs).coerceAtLeast(0L)
        if (clock.lastOn && engineOn && clock.lastTs > 0L && dt <= gapMs) {
            if (engineMoving(data, clock.lastOdoKm)) {
                clock.movingMillis += dt
            } else {
                clock.idleMillis += dt
            }
        }
        clock.lastTs = now
        clock.lastOn = engineOn
        data.odoKm?.let { clock.lastOdoKm = it }
        return EngineHours(clock.movingMillis, clock.idleMillis)
    }

    /**
     * Двигатель работает «в движении»: скорость есть — по ней; скорости нет
     * (шина/приборка молчат) — по приросту одометра. Иначе холостой ход.
     */
    private fun engineMoving(data: CarData, lastOdoKm: Int?): Boolean {
        val speed = data.speedKmh
        if (speed != null && speed.isFinite()) return speed >= ENGINE_MOVING_KMH
        val odo = data.odoKm
        return odo != null && lastOdoKm != null && odo > lastOdoKm
    }

    /** Накопленные моточасы (всего и разбивка). */
    @Synchronized
    fun engineHours(context: Context): EngineHours {
        memClock?.let { return EngineHours(it.movingMillis.coerceAtLeast(0L), it.idleMillis.coerceAtLeast(0L)) }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return EngineHours(
            movingMillis = prefs.getLong(KEY_EH_MOVING, 0L).coerceAtLeast(0L),
            idleMillis = prefs.getLong(KEY_EH_IDLE, 0L).coerceAtLeast(0L)
        )
    }

    /**
     * Обнулить накопитель моточасов. [EngineClock.lastOn] сбрасываем, чтобы
     * первый тик после сброса не добавил интервал, накопленный до нажатия.
     */
    @Synchronized
    fun resetEngineHours(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_EH_MOVING, 0L)
            .putLong(KEY_EH_IDLE, 0L)
            .putLong(KEY_EH_LAST_TS, 0L)
            .putBoolean(KEY_EH_LAST_ON, false)
            .apply()
        // Следующий тик перечитает сброшенное из prefs.
        memClock = null
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
     * Synchronized: читает и пишет prefs целиком, а сброс моточасов и
     * очистка журнала приходят с UI-потока — иначе тик затрёт сброс.
     */
    @Synchronized
    fun update(
        context: Context,
        data: CarData?,
        tankLiters: Int,
        cluster: ClusterTrip? = null,
        now: Long = System.currentTimeMillis(),
        keepDays: Int = 0
    ): TripView {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!memLoaded) {
            migrate(prefs)
            memLastOnTs = prefs.getLong("last_on_ts", 0L)
            memLastOdo = prefs.getInt("last_odo", -1)
            memLastFuel = prefs.getFloat("last_fuel", -1f)
            memLoaded = true
        }
        // Смена настройки / новый день: старые записи уходят ещё до любых
        // переходов поездок (журнал разбираем не чаще [PRUNE_MS]).
        pruneIfDue(prefs, keepDays, now, force = false)
        val t = memTrip ?: load(prefs, PREFIX).also { memTrip = it }
        val engineClock = memClock ?: loadEngineClock(prefs).also { memClock = it }

        val on = engineOn(data)
        accumulateEngineHours(engineClock, data, on, now)
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
        val lastOnTs = memLastOnTs
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

        if (data != null && on) memLastOnTs = now
        if (data != null) {
            // last_odo/last_fuel — база слепой финализации; при ослеплении
            // не портим их пустыми значениями.
            memLastOdo = data.odoKm ?: -1
            memLastFuel = data.fuelPct ?: -1f
        }
        val key = FlushKey(
            prevEngineOn, blindSince, blindOpen || on, data != null,
            t.odoStartKm, t.fuelPctStart, t.startTs
        )
        lastEngineFlag = blindOpen || on
        lastHasData = data != null
        lastOn = on
        if (key != flushedKey) {
            writeState(prefs, t, engineClock)
            flushedKey = key
        }
        return v
    }

    // Флаги последнего тика — для [persist] вне тика.
    private var lastEngineFlag = false
    private var lastHasData = false
    private var lastOn = false

    /**
     * Сохранить текущее состояние сейчас: ГУ засыпает (экран гаснет) или
     * дашборд уходит с экрана. Сон с зажиганием бывает раньше, чем мы
     * увидим «заглушили», а замороженный процесс могут и убить — без этой
     * записи поездка с утра закрылась бы по данным на момент пуска.
     * Ничего не пишет, если с прошлой записи ничего не менялось.
     */
    @Synchronized
    fun persist(context: Context) {
        val t = memTrip ?: return
        val clock = memClock ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong("${PREFIX}_drive", -1L) == t.driveMillis &&
            prefs.getLong(KEY_EH_MOVING, -1L) == clock.movingMillis &&
            prefs.getLong(KEY_EH_IDLE, -1L) == clock.idleMillis &&
            prefs.getInt("last_odo", -2) == memLastOdo
        ) return
        writeState(prefs, t, clock)
    }

    /** Одна запись: состояние, «слепой» момент, снимок приборки, моточасы. */
    private fun writeState(prefs: android.content.SharedPreferences, t: Period, engineClock: EngineClock) {
        val edit = prefs.edit()
        putPeriod(edit, PREFIX, t)
        edit.putLong("blind_ts", blindSince)
            .putBoolean("last_engine", lastEngineFlag)
            .putBoolean("has_data", lastHasData)
            .putLong("last_on_ts", memLastOnTs)
            .putInt("last_odo", memLastOdo)
            .putFloat("last_fuel", memLastFuel)
            .putLong(KEY_EH_MOVING, engineClock.movingMillis)
            .putLong(KEY_EH_IDLE, engineClock.idleMillis)
            .putLong(KEY_EH_LAST_TS, engineClock.lastTs)
            .putBoolean(KEY_EH_LAST_ON, engineClock.lastOn)
            .putInt(KEY_EH_LAST_ODO, engineClock.lastOdoKm ?: -1)
        // lastClusterOn, а не cluster: на тике пуска его уже сбросили
        // (снимок приборки от прошлой поездки сохранять нельзя).
        lastClusterOn?.takeIf { lastOn }?.let { edit.putString(KEY_CLUSTER, encodeCluster(it)) }
        edit.apply()
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
        val lastOdo = memLastOdo
        val dist = if (lastOdo >= 0 && t.odoStartKm != null) {
            (lastOdo - t.odoStartKm!!).toFloat().takeIf { it >= 0 }
        } else null
        val lastFuel = memLastFuel
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
    private fun encodeCluster(c: ClusterTrip): String =
        "${c.tripKm ?: ""};${c.avgFuelL100 ?: ""};${c.avgSpeedKmh ?: ""}"

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

    @Synchronized
    fun clearJournal(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_JOURNAL).apply()
    }

    /**
     * Удалить из журнала записи старше [keepDays] дней (0 — ничего не трогать,
     * журнал хранится целиком). Вызывается из цикла опроса и сразу после
     * смены настройки.
     */
    @Synchronized
    fun applyJournalKeep(
        context: Context,
        keepDays: Int,
        now: Long = System.currentTimeMillis(),
        force: Boolean = true
    ) {
        if (keepDays <= 0) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        pruneIfDue(prefs, keepDays, now, force)
    }

    /**
     * Обрезка журнала: при смене срока / [force] — сразу, иначе не чаще
     * [PRUNE_MS] (срок в днях, раз в час хватает; раньше журнал разбирался
     * дважды каждые 5 с).
     */
    private fun pruneIfDue(
        prefs: android.content.SharedPreferences,
        keepDays: Int,
        now: Long,
        force: Boolean
    ) {
        if (keepDays <= 0) return
        if (!force && keepDays == prunedKeep && now - prunedAt in 0 until PRUNE_MS) return
        prunedKeep = keepDays
        prunedAt = now
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

    /** Состояние накопителя моточасов из prefs. */
    private fun loadEngineClock(prefs: android.content.SharedPreferences): EngineClock =
        EngineClock(
            movingMillis = prefs.getLong(KEY_EH_MOVING, 0L),
            idleMillis = prefs.getLong(KEY_EH_IDLE, 0L),
            lastTs = prefs.getLong(KEY_EH_LAST_TS, 0L),
            lastOn = prefs.getBoolean(KEY_EH_LAST_ON, false),
            lastOdoKm = prefs.getInt(KEY_EH_LAST_ODO, -1).takeIf { it >= 0 }
        )

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
        putPeriod(prefs.edit(), prefix, p).apply()
    }

    private fun putPeriod(
        edit: android.content.SharedPreferences.Editor,
        prefix: String,
        p: Period
    ): android.content.SharedPreferences.Editor =
        edit.putInt("${prefix}_odo", p.odoStartKm ?: -1)
            .putFloat("${prefix}_fuel", p.fuelPctStart ?: -1f)
            .putLong("${prefix}_drive", p.driveMillis)
            .putLong("${prefix}_ts", p.lastTs)
            .putLong("${prefix}_start", p.startTs)

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
    /** Не чаще этого журнал обрезается по сроку из цикла опроса. */
    private const val PRUNE_MS = 3_600_000L
    /** Моточасы: накопитель и его последний тик (переживают перезапуск). */
    private const val KEY_EH_MOVING = "eh_moving"
    private const val KEY_EH_IDLE = "eh_idle"
    private const val KEY_EH_LAST_TS = "eh_last_ts"
    private const val KEY_EH_LAST_ON = "eh_last_on"
    private const val KEY_EH_LAST_ODO = "eh_last_odo"
    /** Ниже этой скорости двигатель считается работающим на холостом ходу. */
    private const val ENGINE_MOVING_KMH = 1f

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
            out.add(
                "trip: engine hours total=${prefs.getLong(KEY_EH_MOVING, 0L) + prefs.getLong(KEY_EH_IDLE, 0L)} " +
                    "moving=${prefs.getLong(KEY_EH_MOVING, 0L)} " +
                    "idle=${prefs.getLong(KEY_EH_IDLE, 0L)} " +
                    "last_on=${prefs.getBoolean(KEY_EH_LAST_ON, false)}"
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
