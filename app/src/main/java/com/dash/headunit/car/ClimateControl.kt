package com.dash.headunit.car

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors

/**
 * Команды климата в шину Desay (CarInfoService, transact 4).
 *
 * Ячейки записи — HvacID$Request / CarSettingID$Request (дамп SVCarInfoService),
 * пары запись→чтение для тогглов совпадают с c6.a/c6.b Jarvis:
 *
 *   питание     16,0  → 16,18 (1 = вкл)
 *   AUTO        16,1  → 16,4
 *   A/C         16,10 → 16,1   (AC_MODE)
 *   рециркул.   16,9  → 16,8   (инверсия: 0 = рецирк. вкл)
 *   SYNC        16,30 → 16,3   (инверсия: 0 = SYNC вкл)
 *   MAX обдув   16,44 → 16,24
 *   обогрев лоб 16,53 → 16,65
 *   руль        16,70 → 16,70
 *   уставка     16,22 / 16,23 → 16,10 / 16,13 (сырое «1°», см. [ClimateHub.scaleTemp])
 *   вентилятор  16,24 → 16,12 (0..7)
 *   обдув       16,2  → 16,6  (запись 0..3 = чтение 1..4; 5 = лобовое, это MAX)
 *   сиденья     0,83 / 0,84 (запасные 0,135 / 0,136) → 0,95 / 0,96, значение = уровень + 1
 *
 * Значения — из штатного SVHvac (класс команд com.desaysv.svhvac.g.c, разбор
 * quickened cdex 2026-10-28): тогглы питание/AUTO/A/C/рецирк./SYNC/MAX/обогрев
 * лобового пишут 1 = включить, 2 = выключить (по текущему состоянию); уставка —
 * то же сырое «1°», что читается, шаг ±1 в пределах 0..16; вентилятор — уровень
 * 1..7; режим обдува — 0..3 (четыре кнопки панели), лобовое — через MAX.
 * Руль svhvac не пишет вовсе (16,70 — только по таблице Request).
 *
 * На случай другой комплектации у каждой команды есть запасные кодировки
 * ([Variant]): пишем, через [VERIFY_MS] перечитываем; совпало с ожидаемым —
 * кодировка запоминается в prefs и в следующий раз идёт первой. Логи — tag [TAG].
 *
 * Все записи — по одной на потоке [WRITER]; повторные тапы по той же команде
 * схлопываются до последнего значения.
 */
object ClimateControl {

    private const val TAG = "ClimateControl"
    private const val PREFS = "climate_control"
    private const val VERIFY_MS = 1500L

    /** Как записать логическое значение: ячейка + кодировка. */
    class Variant(val group: Int, val id: Int, val encode: (Int) -> Int)

    /**
     * Команда: логическое значение (вкл = 1/выкл = 0, уровень, сырое «1°»)
     * → варианты записи; [expect] — какое сырое чтение ячейки [read] значит успех.
     */
    enum class Cmd(
        val title: String,
        val read: Pair<Int, Int>,
        val variants: List<Variant>,
        val expect: (Int) -> Int = { it }
    ) {
        POWER("питание", 16 to 18, toggle(16, 0)),
        AUTO("AUTO", 16 to 4, toggle(16, 1)),
        AC("A/C", 16 to 1, toggle(16, 10)),
        RECIRC("рециркуляция", 16 to 8, toggle(16, 9), { 1 - it }),
        SYNC("SYNC", 16 to 3, toggle(16, 30), { 1 - it }),
        MAX_DEFROST("MAX обдув", 16 to 24, toggle(16, 44)),
        WINDSHIELD_HEAT("обогрев лобового", 16 to 65, toggle(16, 53)),
        WHEEL_HEAT("руль", 16 to 70, toggle(16, 70)),
        TEMP_DRIVER("уставка вод.", 16 to 10, temp(22)),
        TEMP_PASS("уставка пасс.", 16 to 13, temp(23)),
        FAN("вентилятор", 16 to 12, listOf(Variant(16, 24) { it }, Variant(16, 24) { it + 1 })),
        MODE("обдув", 16 to 6, listOf(Variant(16, 2) { it - 1 }, Variant(16, 2) { it })),
        SEAT_DRIVER("сиденье вод.", 0 to 95, listOf(Variant(0, 83) { it + 1 }, Variant(0, 135) { it + 1 })),
        SEAT_PASS("сиденье пасс.", 0 to 96, listOf(Variant(0, 84) { it + 1 }, Variant(0, 136) { it + 1 }))
    }

    private val WRITER = Executors.newSingleThreadExecutor()
    private val pending = mutableMapOf<Cmd, Int>()
    private var reader: DesayCarReader? = null

    /** Записать [value] команды [cmd]; [onDone] — на потоке записи, после проверки. */
    fun send(context: Context, cmd: Cmd, value: Int, onDone: (() -> Unit)? = null) {
        val app = context.applicationContext
        HvacShade.prepare(app)
        val queued = synchronized(pending) { pending.put(cmd, value) != null }
        if (queued) return // задача уже в очереди — возьмёт новое значение
        WRITER.execute {
            val v = synchronized(pending) { pending.remove(cmd) } ?: return@execute
            try {
                val r = reader ?: DesayCarReader(app).also { reader = it }
                if (r.isAvailable()) sendVerified(app, r, cmd, v)
                else Log.w(TAG, "${cmd.title}: CarInfoService недоступен")
            } catch (e: Exception) {
                Log.w(TAG, "send ${cmd.title}", e)
            }
            onDone?.invoke()
            ClimateHub.refresh()
        }
    }

    private fun sendVerified(app: Context, r: DesayCarReader, cmd: Cmd, value: Int) {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val learned = prefs.getInt(cmd.name, 0).coerceIn(0, cmd.variants.lastIndex)
        val order = listOf(learned) + cmd.variants.indices.filter { it != learned }
        val expected = cmd.expect(value)
        val (rg, rid) = cmd.read
        val log = StringBuilder("${cmd.title} → $value:")
        for (i in order) {
            val v = cmd.variants[i]
            val raw = v.encode(value)
            val before = runCatching { r.readInt(rg, rid) }.getOrNull()
            if (before == expected) {
                // Уже в нужном состоянии: если ячейка — «нажатие», запись бы его сбросила.
                log.append(" уже $before")
                break
            }
            HvacShade.beforeWrite(app)
            r.write(v.group, v.id, raw)
            HvacShade.afterWrite(app)
            SystemClock.sleep(VERIFY_MS)
            val after = runCatching { r.readInt(rg, rid) }.getOrNull()
            log.append(" ${v.group},${v.id}=$raw · $rg,$rid $before→$after")
            if (after == expected) {
                if (i != learned) prefs.edit().putInt(cmd.name, i).apply()
                log.append(" ок")
                break
            }
        }
        Log.i(TAG, log.toString())
    }

    /** Тоггл: вкл/выкл = 1/2 (как SVHvac), запасные — 2/1 и 1/0. */
    private fun toggle(group: Int, id: Int) = listOf(
        Variant(group, id) { if (it != 0) 1 else 2 },
        Variant(group, id) { if (it != 0) 2 else 1 },
        Variant(group, id) { if (it != 0) 1 else 0 }
    )

    /**
     * Уставка: логическое = сырое «1°» чтения (0 LO, 1..15 = 16..30 °C, 16 HI).
     * Запись — та же шкала (SVHvac g.c.B/C: ±1, max 16), запасная — «0,5°» Jarvis
     * (°C = (w+35)/2 → w = 2·raw − 5).
     */
    private fun temp(id: Int) = listOf(
        Variant(16, id) { it },
        Variant(16, id) { (2 * it - 5).coerceIn(0, 30) }
    )

    /** Сырое «1°» уставки из °C ([ClimateHub.TEMP_LO]/[ClimateHub.TEMP_HI] → 0/16). */
    fun tempRaw(c: Float): Int = when {
        c <= ClimateHub.TEMP_LO -> 0
        c >= ClimateHub.TEMP_HI -> 16
        else -> (c - 15f).toInt().coerceIn(1, 15)
    }
}
