package com.dash.headunit.car

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Климат-хаб: только чтение состояния HVAC через штатный Desay SV
 * (com.desaysv.svcarinfo/CarInfoService, transact(3, чтение)).
 *
 * Карта свойств (группа 16) выведена диффом сценарных снапшотов реального ГУ:
 *   16,1   A/C вкл            1=вкл
 *   16,3   SYNC зон          инверсия: 0=вкл (как в Jarvis)
 *   16,4   AUTO               1=вкл
 *   16,6   режим обдува       1=лицо, 2=ноги, 5=лобовое
 *   16,8   рециркуляция       инверсия: 0=рецирк.вкл, 1=улица
 *   16,10  уставка темп вод.  raw/2 + 17.5 (raw 9=22°, 1°=0.5°HvacID)
 *   16,12  скорость вентилят. 0..7 (5 = авто, 0 = выкл)
 *   16,13  уставка темп пасс. синхронна с водителем
 *   16,22  SET уставка вод.   Request-ячейка svhvac
 *   16,23  SET уставка пасс.  Request-ячейка svhvac
 *   16,18  климат активен     1=работает
 *   16,24  обдув лобового     1=вкл (вместе с 16,6→5 и 16,1→1)
 *   16,65  обогрев лобового   1=вкл (электрообогрев, как в Jarvis)
 *   0,95   подогрев сиденья вод. 0..3
 *
 * Доп. канальные значения (чтение): 4,15 дверь, 0,100..102 окна,
 * 4,79 аварийка, 4,75 ручник, 0,27 двигатель запущен.
 *
 * Записи здесь нет (команды в шину — в тех виджетах, которым они нужны):
 * - Request-ячейки (16,22/16,23, SET уставка svhvac) подтверждаются
 *   read-back по 16,10/16,13.
 * - Подогрев сиденья/лобового пишет HeatWidgetView напрямую.
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
     * Сырое значение уставки → °C. Калибровка Jarvis (режим
     * «климат-контроль», x9): °C = (raw + 35) / 2, шаг 0.5, raw 0 = LO
     * (17.5), raw ≥ 30 = HI; снапшоты 16,10: 9→10→11 за тапы «+0.5°»
     * (22.0→22.5→23.0), дефолт Jarvis 9 = 22 °C.
     */
    internal fun scaleTemp(raw: Int): Float? {
        if (raw < 0) return null
        return (raw.coerceIn(0, 29) + 35f) / 2f
    }

    private fun boolOr(raw: Int): Boolean? = if (raw < 0) null else raw != 0

    /** 2 = закрыто, 4 = открыто (по снапшотам). */
    private fun openState(raw: Int): Boolean? = when (raw) {
        2 -> false
        4 -> true
        else -> null
    }


    fun resetForTests() {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        listeners.clear()
        data = null
        pollJob = null
        appContext = null
        desay = null
    }

    private const val POLL_MS = 4000L
}
