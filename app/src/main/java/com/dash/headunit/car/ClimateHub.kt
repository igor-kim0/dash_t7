package com.dash.headunit.car

import android.content.Context
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
 * Климат-хаб: чтение и запись состояния HVAC через штатный Desay SV
 * (com.desaysv.svcarinfo/CarInfoService, transact(3, чтение) / transact(4, запись)).
 *
 * Карта свойств (группа 16) выведена диффом сценарных снапшотов реального ГУ:
 *   16,1   A/C вкл            1=вкл
 *   16,3   SYNC зон          инверсия: 0=вкл (как в Jarvis)
 *   16,4   AUTO               1=вкл
 *   16,6   режим обдува       1=лицо, 2=ноги, 5=лобовое
 *   16,8   рециркуляция       инверсия: 0=рецирк.вкл, 1=улица
 *   16,10  уставка темп вод.  шаги (9→10→11…), 0.5°C?
 *   16,12  скорость вентилят. 0..7 (5 = авто, 0 = выкл)
 *   16,13  уставка темп пасс. синхронна с водителем
 *   16,18  климат активен     1=работает
 *   16,24  обдув лобового     1=вкл (вместе с 16,6→5 и 16,1→1)
 *   16,65  обогрев лобового   1=вкл (электрообогрев, как в Jarvis)
 *   0,95   подогрев сиденья вод. 0..3
 *
 * Доп. канальные значения (чтение): 4,15 дверь, 0,100..102 окна,
 * 4,79 аварийка, 4,75 ручник, 0,27 двигатель запущен.
 *
 * ВНИМАНИЕ: write() пишет в CAN шины комфорта. Значения сверяются
 * write-back'ом (read-after-write); расхождение логируется.
 */
object ClimateHub {

    /** Состояние HVAC, нормализованное к человекочитаемым величинам. */
    data class ClimateState(
        /** Температура водителя, °C (16,10 → 16..32). null = нет данных. */
        val tempDriverC: Float?,
        /** Температура пассажира, °C. */
        val tempPassC: Float?,
        /** Скорость вентилятора 0..7. */
        val fanSpeed: Int?,
        /** AUTO вкл. */
        val auto: Boolean?,
        /** A/C вкл. */
        val ac: Boolean?,
        /** Рециркуляция вкл. */
        val recirc: Boolean?,
        /** Климат включён (не OFF). */
        val power: Boolean?,
        /** Режим обдува: 1 лицо, 2 ноги, 5 лобовое. */
        val fanDirection: Int?,
        /** Обдув лобового. */
        val defrostFront: Boolean?,
        /** Дверь водителя открыта. */
        val doorDriverOpen: Boolean?,
        /** Окна: [водитель, пассажир, ЗЛ] — true = открыто. */
        val windows: List<Boolean?>?,
        /** Аварийка. */
        val hazard: Boolean?,
        /** Ручник. */
        val parkingBrake: Boolean?,
        /** Двигатель запущен. */
        val engineOn: Boolean?,
        /** Синхронизация зон (16,3, инверсия: 0 = SYNC вкл). */
        val sync: Boolean? = null,
        /** Обогрев лобового стекла (16,65). */
        val windshieldHeat: Boolean? = null,
        /** Подогрев сиденья водителя 0..3 (0,95). */
        val seatHeatDriver: Int? = null
    ) {
        companion object {
            val EMPTY = ClimateState(
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null
            )
        }
    }

    private const val TAG = "ClimateHub"
    private const val G = 16 // группа HVAC

    private var desay: DesayCarReader? = null
    private var appContext: Context? = null

    @Volatile
    var data: ClimateState? = null
        private set

    @Volatile
    var lastWriteOk: Boolean? = null
        private set

    private val listeners = mutableSetOf<() -> Unit>()
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollJob: Job? = null

    fun init(context: Context) {
        if (appContext == null) {
            val app = context.applicationContext
            appContext = app
            desay = DesayCarReader(app)
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
            pollJob = scope.launch {
                while (true) {
                    refresh()
                    delay(POLL_MS)
                }
            }
        }
    }

    fun refresh() {
        val desay = desay ?: return
        scope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                runCatching { read(desay) }.getOrNull()
            }
            if (snapshot != null) {
                data = snapshot
                notifyListeners()
            }
        }
    }

    // ---------- чтение ----------

    private fun read(desay: DesayCarReader): ClimateState {
        // Инверсные флаги: 1 = «выкл», поэтому логическое значение — инверсия.
        val powerRaw = desay.readInt(G, 18) // 1 = активен
        val recircRaw = desay.readInt(G, 8) // 0 = рецирк вкл
        val defrost = desay.readInt(G, 24)
        val engineOn = boolOr(desay.readInt(0, 27))
        return ClimateState(
            tempDriverC = scaleTemp(desay.readInt(G, 10)),
            tempPassC = scaleTemp(desay.readInt(G, 13)),
            fanSpeed = desay.readInt(G, 12).takeIf { it in 0..7 },
            auto = boolOr(desay.readInt(G, 4)),
            ac = boolOr(desay.readInt(G, 1)),
            recirc = boolOr(recircRaw)?.not(),
            power = boolOr(powerRaw),
            fanDirection = desay.readInt(G, 6).takeIf { it in listOf(1, 2, 5) },
            defrostFront = boolOr(defrost),
            // 4,15 = 1 и при заглушенном двигателе, и при открытой двери
            // водителя (снапшоты) — без работающего двигателя считаем
            // состояние неизвестным, чтобы не врать про открытую дверь.
            doorDriverOpen = if (engineOn == true) boolOr(desay.readInt(4, 15)) else null,
            windows = listOf(
                desay.readInt(0, 100).let { openState(it) },
                desay.readInt(0, 101).let { openState(it) },
                desay.readInt(0, 102).let { openState(it) }
            ),
            hazard = boolOr(desay.readInt(4, 79)),
            parkingBrake = boolOr(desay.readInt(4, 75)),
            engineOn = engineOn,
            // Jarvis: SYNC читается с 16,3 (0 = вкл), обогрев лобового — 16,65.
            sync = boolOr(desay.readInt(G, 3))?.not(),
            windshieldHeat = boolOr(desay.readInt(G, 65)),
            seatHeatDriver = desay.readInt(0, 95).takeIf { it in 0..3 }
        )
    }

    /**
     * Сырое значение уставки → °C. В конфиге VHAL HVAC_TEMPERATURE_SET
     * 16..32 °C, в дампе raw 9→10→11 за один тап «+0.5°» штатного
     * климата → °C = 16 + raw/2 (шаг 0.5, raw 0 = 16 °C).
     * Калибровка подтверждается на ГУ: «Климат: состояние» + тап «+»
     * на штатном пульте — raw должен расти на 1 при +0.5 °C.
     */
    private fun scaleTemp(raw: Int): Float? {
        if (raw < 0 || raw > 32) return null
        return 16f + raw / 2f
    }

    private fun boolOr(raw: Int): Boolean? = if (raw < 0) null else raw != 0

    /** 2 = закрыто, 4 = открыто (по снапшотам). */
    private fun openState(raw: Int): Boolean? = when (raw) {
        2 -> false
        4 -> true
        else -> null
    }

    // ---------- запись ----------

    /**
     * Записать уставку температуры водителя [celsius] (16..32).
     * Возвращает true, если write ушёл; фактическое значение сверяется
     * следующим опросом (write-back).
     */
    fun setTempDriver(celsius: Float): Boolean = writeTemp(celsius)

    private fun writeTemp(celsius: Float): Boolean {
        val raw = tempToRaw(celsius)
        val d = desay ?: return false
        val ok = withWriteGuard { d.write(G, 10, raw) }
        if (ok) {
            // Пассажирская зона синхронизируется — пишем и её.
            d.write(G, 13, raw)
        }
        return ok
    }

    private fun tempToRaw(celsius: Float): Int =
        ((celsius - 16f) * 2f).toInt().coerceIn(0, 32)

    /** Вентилятор 0..7. */
    fun setFanSpeed(speed: Int): Boolean {
        val d = desay ?: return false
        val v = speed.coerceIn(0, 7)
        return withWriteGuard { d.write(G, 12, v) }
    }

    fun setAc(on: Boolean): Boolean = writeFlag(1, if (on) 1 else 0)

    fun setAuto(on: Boolean): Boolean = writeFlag(4, if (on) 1 else 0)

    fun setRecirc(on: Boolean): Boolean = writeFlag(8, if (on) 0 else 1)

    /**
     * Климат вкл/выкл через 16,18 (1 = активен). 16,3 больше не трогаем:
     * по Jarvis это SYNC (см. [setSync]), а не питание.
     */
    fun setPower(on: Boolean): Boolean {
        val d = desay ?: return false
        return withWriteGuard { d.write(G, 18, if (on) 1 else 0) }
    }

    /** Синхронизация зон (16,3, инверсия: вкл = 0). */
    fun setSync(on: Boolean): Boolean = writeFlag(3, if (on) 0 else 1)

    /** Обогрев лобового стекла (16,65, 1 = вкл). */
    fun setWindshieldHeat(on: Boolean): Boolean = writeFlag(65, if (on) 1 else 0)

    /** Режим обдува: 1 лицо, 2 ноги, 5 лобовое. */
    fun setFanDirection(direction: Int): Boolean {
        val d = desay ?: return false
        val v = direction.coerceIn(1, 5)
        return withWriteGuard { d.write(G, 6, v) }
    }

    fun setDefrostFront(on: Boolean): Boolean {
        val d = desay ?: return false
        val ok1 = withWriteGuard { d.write(G, 24, if (on) 1 else 0) }
        if (on) {
            // По сценарию defrost: режим→5 и A/C форсируется.
            d.write(G, 6, 5)
            d.write(G, 1, 1)
        }
        return ok1
    }

    private fun writeFlag(id: Int, value: Int): Boolean {
        val d = desay ?: return false
        return withWriteGuard { d.write(G, id, value) }
    }

    private inline fun withWriteGuard(block: () -> Boolean): Boolean =
        try {
            val ok = block()
            lastWriteOk = ok
            ok
        } catch (e: Throwable) {
            Log.w(TAG, "write", e)
            lastWriteOk = false
            false
        }

    // ---------- окна/двери (группа 0/4) ----------

    /**
     * Открыть/закрыть окно водителя. Команда — VR-ячейка (3,120), как у
     * штатного исполнителя голосовых команд (см. ActionLearner.Known):
     * 1/2 водитель, 3/4 пассажир. Состояние читается из 0,100/0,101
     * (2 закрыто, 6 едет, 3 приоткрыто, 4 открыто) — туда не пишем.
     */
    fun setWindowDriver(open: Boolean): Boolean {
        val d = desay ?: return false
        return withWriteGuard { d.write(3, 120, if (open) 1 else 2) }
    }

    fun setWindowPass(open: Boolean): Boolean {
        val d = desay ?: return false
        return withWriteGuard { d.write(3, 120, if (open) 3 else 4) }
    }

    fun setHazard(on: Boolean): Boolean {
        val d = desay ?: return false
        return withWriteGuard { d.write(4, 79, if (on) 1 else 0) }
    }

    /**
     * Подогрев сиденья водителя (0,95): выкл + 3 ступени (как в штатном:
     * 3 риски-индикатора). Видели 0→2→0 по одиночным нажатиям.
     */
    fun setSeatHeatDriver(level: Int): Boolean {
        val d = desay ?: return false
        return withWriteGuard { d.write(0, 95, level.coerceIn(0, 3)) }
    }

    fun resetForTests() {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        listeners.clear()
        data = null
        lastWriteOk = null
        pollJob = null
        appContext = null
        desay = null
    }

    private const val POLL_MS = 4000L
}
