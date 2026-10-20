package com.dash.headunit.car

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Обучение кнопкам машины по шине Desay SV (стёкла, люк, замки…).
 *
 * Сценарий (виджет «Обучение кнопкам»):
 *  1. фон — [BASELINE_MS] ничего не трогаем, сканируем все 32×256 ячеек
 *     (~1,5 с проход): что меняется само — шум;
 *  2. «нажмите кнопку» — сканируем, пока пользователь не нажмёт «Готово»
 *     (или [RECORD_MAX_MS]), плюс [SETTLE_SCANS] прохода на успокоение;
 *  3. разбор ([analyze]): ячейки, изменившиеся во время действия и не
 *     шумевшие в фоне. «Состояние» — осталась в новом значении, «импульс» —
 *     вернулась (держали кнопку);
 *  4. «попробовать» — записать в ячейку-кандидата значение «после»
 *     (для импульса — нажать и отпустить) и [VERIFY_MS] следить, придут ли
 *     ячейки состояния действия в выученные значения. Сработало — действие
 *     запоминается и выполняется кнопкой.
 *
 * Гипотеза для записи — как у климата: команда пишется в ту же ячейку,
 * из которой читается состояние (16,10 и т.п.). Каждая запись — только
 * по явному подтверждению и на стоящей машине.
 *
 * Всё пишется в <externalFilesDir>/learn/<время>_<действие>.txt.
 */
object ActionLearner {

    /**
     * Действие для обучения: [press] — что сказать пользователю.
     * [canWrite] = false — только наблюдение (круиз: управление движением
     * из ГУ не пишем, найденные ячейки нужны для отображения).
     */
    data class Action(
        val key: String,
        val title: String,
        val press: String,
        val canWrite: Boolean = true,
        /** Команда из прошивки ГУ — пробуется без обучения ([tryKnown]). */
        val known: Known? = null,
    )

    /**
     * Команда, найденная в штатном ПО ГУ: запись [value] в ([group],[id]);
     * сработала ли — по изменению ячеек состояния [verify] (группа 0).
     * [sure] = false — значение угадано (виджет помечает «гипотеза»).
     *
     * Откуда (com.desaysv.vehiclesetting, VrControlMgr — исполнитель
     * голосовых команд «открой окно»): группа 3 = ModuleID.ID_VR,
     * VrID.Request: VR_CAR_WINDOW=120, VR_SUN_ROOF_STATUS=122,
     * VR_SUN_ROOF_POSITION=123, VR_SUNSHADE=124. Для окон (левый руль):
     * 1/2 водитель откр/закр, 3/4 пассажир, 5/6 зад. левое, 7/8 зад. правое,
     * 9/10 передние, 11/12 задние, 13/14 все. Состояния — CarSettingID.Response:
     * 0,100..103 окна, 0,5/0,6 люк, 0,10 шторка. Ячейки 0,x — только чтение:
     * запись туда (первая попытка обучения) ничего не делает.
     */
    data class Known(val group: Int, val id: Int, val value: Int, val verify: List<Int>, val sure: Boolean = true) {
        val cell: String get() = "$group,$id"
    }

    /**
     * Изменившаяся ячейка. [before]/[after] — значения в фоне и в конце,
     * [timeline] — все значения по ходу «+сек:значение», [firstMs] — когда
     * впервые изменилась (от начала записи).
     */
    data class Change(
        val group: Int,
        val id: Int,
        val before: String,
        val after: String,
        val firstMs: Long,
        val pulse: Boolean,
        val pulseValue: String?,
        val timeline: List<String>,
    ) {
        val cell: String get() = "$group,$id"
    }

    enum class Phase { IDLE, BASELINE, RECORDING, SETTLING, ANALYZED, TRYING }

    @Volatile var phase = Phase.IDLE
        private set
    @Volatile var status = "выберите действие и нажмите «Старт»"
        private set
    @Volatile var action: Action? = null
        private set
    @Volatile var candidates: List<Change> = emptyList()
        private set
    /** Кандидаты, совпавшие с прошлым обучением того же действия. */
    @Volatile var repeated: Set<String> = emptySet()
        private set
    @Volatile var file: File? = null
        private set

    /** Результаты попыток по ячейкам: «сработало …» / «не сработало …». */
    val tryResults: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()

    @Volatile private var finishRequested = false
    @Volatile private var cancelRequested = false
    @Volatile private var thread: Thread? = null
    private val previousRun = mutableMapOf<String, Set<String>>()
    private var writer: java.io.Writer? = null

    val ACTIONS = listOf(
        Action("win_drv_down", "Окно водителя: опустить", "Опустите стекло водителя полностью (кнопкой на двери)", known = Known(3, 120, 1, listOf(100))),
        Action("win_drv_up", "Окно водителя: поднять", "Поднимите стекло водителя полностью", known = Known(3, 120, 2, listOf(100))),
        Action("win_pass_down", "Окно пассажира: опустить", "Опустите переднее пассажирское стекло полностью", known = Known(3, 120, 3, listOf(101))),
        Action("win_pass_up", "Окно пассажира: поднять", "Поднимите переднее пассажирское стекло полностью", known = Known(3, 120, 4, listOf(101))),
        Action("win_rl_down", "Заднее левое: опустить", "Опустите заднее левое стекло полностью", known = Known(3, 120, 5, listOf(102))),
        Action("win_rl_up", "Заднее левое: поднять", "Поднимите заднее левое стекло полностью", known = Known(3, 120, 6, listOf(102))),
        Action("win_rr_down", "Заднее правое: опустить", "Опустите заднее правое стекло полностью", known = Known(3, 120, 7, listOf(103))),
        Action("win_rr_up", "Заднее правое: поднять", "Поднимите заднее правое стекло полностью", known = Known(3, 120, 8, listOf(103))),
        Action("sunroof_open", "Люк: открыть", "Откройте люк полностью", known = Known(3, 122, 1, listOf(5, 6), sure = false)),
        Action("sunroof_close", "Люк: закрыть", "Закройте люк полностью", known = Known(3, 122, 2, listOf(5, 6), sure = false)),
        Action("shade_open", "Шторка люка: открыть", "Откройте шторку люка полностью", known = Known(3, 124, 1, listOf(10), sure = false)),
        Action("shade_close", "Шторка люка: закрыть", "Закройте шторку люка полностью", known = Known(3, 124, 2, listOf(10), sure = false)),
        Action("lock", "Замки: закрыть", "Заблокируйте двери кнопкой замка в салоне"),
        Action("unlock", "Замки: открыть", "Разблокируйте двери кнопкой замка в салоне"),
        Action("mirrors_fold", "Зеркала: сложить", "Сложите зеркала кнопкой"),
        Action("mirrors_unfold", "Зеркала: разложить", "Разложите зеркала кнопкой"),
        // Климат — штатными кнопками/экраном климата (по одному нажатию).
        Action("ac_on", "Климат: A/C вкл", "Включите кондиционер (A/C)"),
        Action("ac_off", "Климат: A/C выкл", "Выключите кондиционер (A/C)"),
        Action("climate_auto", "Климат: AUTO", "Нажмите AUTO"),
        Action("climate_off", "Климат: выключить", "Выключите климат кнопкой OFF"),
        Action("climate_on", "Климат: включить", "Включите климат (кнопкой питания климата)"),
        Action("fan_up", "Климат: вентилятор +", "Прибавьте вентилятор на одну ступень"),
        Action("fan_down", "Климат: вентилятор −", "Убавьте вентилятор на одну ступень"),
        Action("temp_drv_up", "Климат: водитель теплее", "Прибавьте температуру водителя на один шаг"),
        Action("temp_drv_down", "Климат: водитель холоднее", "Убавьте температуру водителя на один шаг"),
        Action("temp_pass_up", "Климат: пассажир теплее", "Прибавьте температуру пассажира на один шаг"),
        Action("temp_pass_down", "Климат: пассажир холоднее", "Убавьте температуру пассажира на один шаг"),
        Action("sync", "Климат: SYNC/DUAL", "Нажмите SYNC (или DUAL) — синхронизация зон"),
        Action("recirc", "Климат: рециркуляция", "Переключите рециркуляцию"),
        Action("defrost_front", "Климат: обдув лобового", "Включите обдув лобового стекла"),
        Action("defrost_rear", "Климат: обогрев заднего", "Включите обогрев заднего стекла"),
        Action("mode_face", "Климат: поток в лицо", "Переключите поток воздуха в лицо"),
        Action("mode_feet", "Климат: поток в ноги", "Переключите поток воздуха в ноги"),
        Action("seat_heat_pass", "Подогрев сиденья пассажира", "Включите подогрев сиденья пассажира"),
        Action("seat_vent_drv", "Вентиляция сиденья водителя", "Включите вентиляцию сиденья водителя"),
        // Круиз — кнопки на руле. Только наблюдение; SET/RES/CANCEL работают
        // на ходу — виджет пусть нажимает пассажир.
        Action("cruise_on", "Круиз: вкл/выкл", "Нажмите кнопку включения круиза на руле (можно стоя)", canWrite = false),
        Action("cruise_set", "Круиз: SET/−", "На ходу (виджет — пассажиру): нажмите SET/−", canWrite = false),
        Action("cruise_res", "Круиз: RES/+", "На ходу (виджет — пассажиру): нажмите RES/+", canWrite = false),
        Action("cruise_cancel", "Круиз: CANCEL", "На ходу (виджет — пассажиру): нажмите CANCEL (или тормоз)", canWrite = false),
        Action("cruise_dist", "Круиз: дистанция", "Нажмите кнопку дистанции адаптивного круиза (если есть)", canWrite = false),
    )

    /** Следующее действие по списку (по кругу) — для «Пропустить». */
    fun next(a: Action): Action = ACTIONS[(ACTIONS.indexOf(a) + 1) % ACTIONS.size]

    // ---------- обучение ----------

    fun start(context: Context, a: Action) {
        if (thread != null) return
        val app = context.applicationContext
        action = a
        candidates = emptyList()
        repeated = emptySet()
        tryResults.clear()
        finishRequested = false
        cancelRequested = false
        thread = Thread({
            try {
                learn(app, a)
            } catch (e: Throwable) {
                Log.w(TAG, "learn", e)
                status = "ошибка: ${e.javaClass.simpleName}: ${e.message}"
                phase = Phase.IDLE
            } finally {
                closeLog()
                thread = null
            }
        }, "dash-learn").apply { start() }
    }

    /** Пользователь закончил действие. */
    fun finish() {
        finishRequested = true
    }

    fun cancel() {
        cancelRequested = true
    }

    val busy: Boolean get() = thread != null

    private fun learn(context: Context, a: Action) {
        val desay = DesayCarReader(context)
        if (!desay.connect()) {
            status = "нет связи с шиной Desay (com.desaysv.svcarinfo)"
            phase = Phase.IDLE
            return
        }
        openLog(context, a)
        log("# действие: ${a.title} (${a.key})")

        // 1. Фон.
        phase = Phase.BASELINE
        status = "Ничего не трогайте ${BASELINE_MS / 1000} с — запоминаю фон…"
        val noise = HashSet<Int>()
        var base = scan(desay)
        val bStart = SystemClock.uptimeMillis()
        var bScans = 1
        while (SystemClock.uptimeMillis() - bStart < BASELINE_MS || bScans < MIN_BASELINE_SCANS) {
            if (cancelRequested) return cancelled()
            val next = scan(desay)
            next.forEach { (k, v) -> if (base[k] != v) noise.add(k) }
            base = next
            bScans++
        }
        log("# фон: $bScans проходов, шумят ${noise.size} ячеек: " +
            noise.sorted().joinToString(" ") { cellName(it) })

        // 2. Действие.
        phase = Phase.RECORDING
        status = "${a.press}. Когда закончите — «Готово»."
        val rStart = SystemClock.uptimeMillis()
        val timeline = mutableListOf<Pair<Long, Map<Int, String>>>()
        var prev = base
        while (!finishRequested && SystemClock.uptimeMillis() - rStart < RECORD_MAX_MS) {
            if (cancelRequested) return cancelled()
            val t = SystemClock.uptimeMillis() - rStart
            val s = scan(desay)
            timeline.add(t to s)
            logDiff("+%.1f с".format(Locale.US, t / 1000f), prev, s)
            prev = s
        }
        phase = Phase.SETTLING
        status = "Секунду — дочитываю…"
        repeat(SETTLE_SCANS) {
            val t = SystemClock.uptimeMillis() - rStart
            val s = scan(desay)
            timeline.add(t to s)
            logDiff("+%.1f с (успокоение)".format(Locale.US, t / 1000f), prev, s)
            prev = s
        }

        // 3. Разбор.
        val result = analyze(base, noise + BLACKLIST, timeline)
        candidates = result
        val prevKeys = previousRun[a.key]
        repeated = if (prevKeys == null) emptySet() else result.map { it.cell }.filter { it in prevKeys }.toSet()
        previousRun[a.key] = result.map { it.cell }.toSet()
        log("# кандидаты (${result.size}):")
        result.forEach { log("#   ${describe(it)}  [${it.timeline.joinToString(" ")}]") }
        status = when {
            result.isEmpty() -> "Ничего не изменилось вне шума. Повторите медленнее (держите кнопку дольше)."
            else -> "Нашёл ${result.size}. Верните как было (обратным действием) и нажмите «Попробовать» у кандидата."
        }
        phase = Phase.ANALYZED
    }

    private fun cancelled() {
        status = "отменено"
        phase = Phase.IDLE
    }

    /**
     * Ячейки, изменившиеся во время действия, кроме [ignore]. Порядок:
     * сначала «состояния» (в конце другое значение), потом импульсы; внутри —
     * кто раньше изменился. Чистая — тестируется.
     */
    internal fun analyze(
        base: Map<Int, String>,
        ignore: Set<Int>,
        timeline: List<Pair<Long, Map<Int, String>>>,
    ): List<Change> {
        if (timeline.isEmpty()) return emptyList()
        val final = timeline.last().second
        val out = mutableListOf<Change>()
        for ((key, before) in base) {
            if (key in ignore) continue
            var first = -1L
            var pulseValue: String? = null
            val seq = mutableListOf<String>()
            var last = before
            for ((t, scan) in timeline) {
                val v = scan[key] ?: continue
                if (v != last) {
                    seq.add("+%.1f:%s".format(Locale.US, t / 1000f, v))
                    last = v
                }
                if (v != before) {
                    if (first < 0) first = t
                    if (pulseValue == null) pulseValue = v
                }
            }
            if (first < 0) continue
            val after = final[key] ?: before
            val pulse = after == before
            out.add(Change(key / 256, key % 256, before, after, first, pulse, if (pulse) pulseValue else null, seq))
        }
        return out.sortedWith(compareBy<Change> { it.pulse }.thenBy { it.firstMs })
    }

    // ---------- попытка выполнить ----------

    /**
     * Записать кандидата [c] и проверить, сработало ли. Скорость > [MAX_SPEED_KMH]
     * — отказ. Результат — в [tryResults] и в лог.
     */
    fun tryCandidate(context: Context, c: Change) {
        if (thread != null) return
        if (action?.canWrite == false) {
            tryResults[c.cell] = "отказ: это действие только наблюдаем"
            return
        }
        val speed = CarHub.data?.speedKmh
        if (speed != null && speed > MAX_SPEED_KMH) {
            tryResults[c.cell] = "отказ: машина едет (${speed.toInt()} км/ч)"
            return
        }
        val app = context.applicationContext
        val a = action
        val stateCells = candidates.filter { !it.pulse }
        cancelRequested = false
        thread = Thread({
            try {
                attempt(app, a, c, stateCells)
            } catch (e: Throwable) {
                Log.w(TAG, "try", e)
                tryResults[c.cell] = "ошибка: ${e.javaClass.simpleName}"
                phase = Phase.ANALYZED
            } finally {
                closeLog()
                thread = null
            }
        }, "dash-learn-try").apply { start() }
    }

    private fun attempt(context: Context, a: Action?, c: Change, stateCells: List<Change>) {
        val desay = DesayCarReader(context)
        if (!desay.connect()) {
            tryResults[c.cell] = "нет связи с шиной"
            return
        }
        phase = Phase.TRYING
        appendLog(context)
        val before = scan(desay)
        val value = toInt(if (c.pulse) c.pulseValue ?: c.after else c.after)
        status = "Пишу (${c.cell}) = $value, слежу ${VERIFY_MS / 1000} с…"
        log("# ПОПЫТКА ${a?.key}: write(${c.cell}) = $value" +
            if (c.pulse) " (импульс, потом ${toInt(c.before)})" else "")
        val ok = desay.write(c.group, c.id, value)
        if (c.pulse) {
            Thread.sleep(PULSE_MS)
            desay.write(c.group, c.id, toInt(c.before))
        }
        // Следим: пришли ли ячейки состояния в выученные значения.
        val start = SystemClock.uptimeMillis()
        var prev = before
        var reached = emptyList<String>()
        while (SystemClock.uptimeMillis() - start < VERIFY_MS) {
            val s = scan(desay)
            logDiff("попытка +%.1f с".format(Locale.US, (SystemClock.uptimeMillis() - start) / 1000f), prev, s)
            prev = s
            reached = stateCells.filter { s[key(it.group, it.id)] == it.after }
                .filter { before[key(it.group, it.id)] != it.after }
                .map { it.cell }
            if (reached.isNotEmpty() && !c.pulse && reached.size == stateCells.size) break
        }
        val res = when {
            !ok -> "запись не ушла (сервис не принял)"
            reached.isNotEmpty() -> "СРАБОТАЛО: ${reached.joinToString()} пришли в выученное"
            stateCells.isEmpty() -> "состояний не выучено — проверить нечем, смотрите на машину и лог"
            stateCells.all { before[key(it.group, it.id)] == it.after } ->
                "не проверить: состояние уже «после» — сначала верните как было"
            else -> "не сработало: состояние не изменилось"
        }
        tryResults[c.cell] = res
        log("# итог: $res")
        if (reached.isNotEmpty() && a != null) {
            saveLearned(context, a.key, c.group, c.id, value, if (c.pulse) toInt(c.before) else null)
        }
        status = res
        phase = Phase.ANALYZED
    }

    /** Результат пробы команды из прошивки, по ключу действия. */
    val knownResults: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()

    /**
     * Записать команду из прошивки [Action.known] и [VERIFY_MS] следить за
     * ячейками состояния. Изменились — команда запоминается как выученная.
     */
    fun tryKnown(context: Context, a: Action) {
        val k = a.known ?: return
        if (thread != null) return
        val speed = CarHub.data?.speedKmh
        if (speed != null && speed > MAX_SPEED_KMH) {
            knownResults[a.key] = "отказ: машина едет (${speed.toInt()} км/ч)"
            return
        }
        val app = context.applicationContext
        action = a
        thread = Thread({
            try {
                phase = Phase.TRYING
                val d = DesayCarReader(app)
                if (!d.connect()) {
                    knownResults[a.key] = "нет связи с шиной"
                    return@Thread
                }
                openLog(app, a)
                fun states() = k.verify.associateWith { id ->
                    d.readRaw(0, id, logErrors = false)?.joinToString(",") ?: "?"
                }
                val before = states()
                status = "Пишу (${k.cell}) = ${k.value}, слежу за ${k.verify.joinToString { "0,$it" }}…"
                log("# КОМАНДА ИЗ ПРОШИВКИ ${a.key}: write(${k.cell}) = ${k.value}; до: $before")
                val ok = d.write(k.group, k.id, k.value)
                val start = SystemClock.uptimeMillis()
                var changed = emptyMap<Int, String>()
                var last = before
                var lastChange = start
                while (SystemClock.uptimeMillis() - start < VERIFY_MS) {
                    Thread.sleep(VERIFY_POLL_MS)
                    val now = states()
                    if (now != last) {
                        log("+%.1f с: %s".format(Locale.US, (SystemClock.uptimeMillis() - start) / 1000f, now))
                        last = now
                        lastChange = SystemClock.uptimeMillis()
                    }
                    changed = now.filter { (id, v) -> before[id] != v }
                    // Доехало: изменилось и уже [SETTLED_MS] стоит на месте.
                    if (changed.isNotEmpty() && SystemClock.uptimeMillis() - lastChange > SETTLED_MS) break
                }
                val res = when {
                    !ok -> "запись не ушла (сервис не принял)"
                    changed.isNotEmpty() -> "СРАБОТАЛО: " + changed.entries.joinToString { (id, v) -> "0,$id: ${before[id]} → $v" }
                    else -> "не сработало: состояние ${before.entries.joinToString { (id, v) -> "0,$id=$v" }} не изменилось" +
                        " (если уже было «как надо» — сначала верните обратно)"
                }
                knownResults[a.key] = res
                log("# итог: $res")
                if (changed.isNotEmpty()) saveLearned(app, a.key, k.group, k.id, k.value, null)
                status = res
            } catch (e: Throwable) {
                Log.w(TAG, "tryKnown", e)
                knownResults[a.key] = "ошибка: ${e.javaClass.simpleName}"
            } finally {
                phase = Phase.IDLE
                closeLog()
                thread = null
            }
        }, "dash-learn-known").apply { start() }
    }

    // ---------- выученные действия ----------

    /** Выученное: запись [value] в (group,id), для импульса затем [release]. */
    data class Learned(val group: Int, val id: Int, val value: Int, val release: Int?)

    fun learned(context: Context): Map<String, Learned> {
        val raw = prefs(context).getString(KEY_LEARNED, null) ?: return emptyMap()
        return try {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { k ->
                val x = o.getJSONObject(k)
                Learned(x.getInt("g"), x.getInt("id"), x.getInt("v"),
                    if (x.has("r")) x.getInt("r") else null)
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun saveLearned(context: Context, key: String, g: Int, id: Int, v: Int, release: Int?) {
        val raw = prefs(context).getString(KEY_LEARNED, null)
        val o = try { JSONObject(raw ?: "{}") } catch (e: Exception) { JSONObject() }
        o.put(key, JSONObject().put("g", g).put("id", id).put("v", v).apply { release?.let { put("r", it) } })
        prefs(context).edit().putString(KEY_LEARNED, o.toString()).apply()
    }

    /** Выполнить выученное действие (фоновый поток). */
    fun execute(context: Context, key: String, done: (Boolean) -> Unit) {
        if (ACTIONS.firstOrNull { it.key == key }?.canWrite == false) return done(false)
        val l = learned(context)[key] ?: return done(false)
        val speed = CarHub.data?.speedKmh
        if (speed != null && speed > MAX_SPEED_KMH) return done(false)
        val app = context.applicationContext
        Thread {
            val d = DesayCarReader(app)
            var ok = d.connect() && d.write(l.group, l.id, l.value)
            if (ok && l.release != null) {
                Thread.sleep(PULSE_MS)
                ok = d.write(l.group, l.id, l.release)
            }
            done(ok)
        }.start()
    }

    fun forget(context: Context, key: String) {
        val raw = prefs(context).getString(KEY_LEARNED, null) ?: return
        val o = try { JSONObject(raw) } catch (e: Exception) { return }
        o.remove(key)
        prefs(context).edit().putString(KEY_LEARNED, o.toString()).apply()
    }

    // ---------- служебное ----------

    private fun scan(desay: DesayCarReader): Map<Int, String> {
        val m = HashMap<Int, String>(8192)
        for (g in 0 until GROUPS) for (id in 0 until IDS) {
            val raw = desay.readRaw(g, id, logErrors = false) ?: continue
            m[key(g, id)] = raw.joinToString(",") { (it and 0xFF).toString() }
        }
        return m
    }

    private fun key(g: Int, id: Int) = g * IDS + id
    private fun cellName(k: Int) = "${k / IDS},${k % IDS}"

    /** Значение ячейки «4» / «1,57» → int для записи (как climate: BE-байты). */
    internal fun toInt(v: String): Int {
        val bytes = v.split(',').mapNotNull { it.trim().toIntOrNull() }
        return CarScaling.bytesToInt(bytes.toIntArray()).coerceAtLeast(0)
    }

    fun describe(c: Change): String =
        "(${c.cell}) ${c.before} → ${c.after}" +
            (if (c.pulse) " импульс ${c.pulseValue}" else " состояние") +
            " через %.1f с".format(Locale.US, c.firstMs / 1000f)

    private fun logDiff(label: String, a: Map<Int, String>, b: Map<Int, String>) {
        val diff = b.filter { (k, v) -> a[k] != v && k !in BLACKLIST }
        if (diff.isEmpty()) return
        log("$label: " + diff.entries.sortedBy { it.key }
            .joinToString(" ") { "${cellName(it.key)}=${a[it.key]}→${it.value}" })
    }

    private fun openLog(context: Context, a: Action) {
        val dir = File(context.getExternalFilesDir(null), "learn").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f = File(dir, "${stamp}_${a.key}.txt")
        file = f
        writer = f.bufferedWriter()
    }

    private fun appendLog(context: Context) {
        val f = file ?: return
        writer = java.io.FileWriter(f, true).buffered()
    }

    private fun log(line: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        try {
            writer?.apply { write("$time $line\n"); flush() }
        } catch (e: Exception) {
            Log.w(TAG, "log", e)
        }
    }

    private fun closeLog() {
        try { writer?.close() } catch (e: Exception) {}
        writer = null
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("learned_actions", Context.MODE_PRIVATE)

    /**
     * Телеметрия, которая меняется сама по себе и в фоне может не успеть
     * пошуметь: обороты, напряжение, шины, топливо, одометр, запас,
     * мгновенный расход и тепловое 4,69.
     */
    private val BLACKLIST = setOf(
        4 * 256 + 62, 4 * 256 + 76, 4 * 256 + 8, 4 * 256 + 10, 4 * 256 + 68,
        4 * 256 + 69, 4 * 256 + 70, 4 * 256 + 71, 4 * 256 + 72,
    )

    private const val GROUPS = 32
    private const val IDS = 256
    private const val BASELINE_MS = 6_000L
    private const val MIN_BASELINE_SCANS = 4
    private const val RECORD_MAX_MS = 40_000L
    private const val SETTLE_SCANS = 2
    private const val VERIFY_MS = 10_000L
    private const val PULSE_MS = 600L
    private const val VERIFY_POLL_MS = 500L
    private const val SETTLED_MS = 2_000L
    private const val MAX_SPEED_KMH = 3f
    private const val KEY_LEARNED = "learned"
    private const val TAG = "ActionLearner"
}
