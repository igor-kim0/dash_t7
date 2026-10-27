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
 * Карта свойств (группа 16) выведена диффом сценарных снапшотов реального ГУ
 * и сверена с константами HvacID$Request/Response штатного сервиса
 * (дамп SVCarInfoService, logs/logs_1111111):
 *   16,1   A/C компрессор     1=вкл (AC_COMPRESSOR_STATE)
 *   16,3   SYNC зон          инверсия: 0=вкл (AC_DUAL_SYNC_MODE, как в Jarvis)
 *   16,4   AUTO               1=вкл (AC_AUTO_STATE)
 *   16,6   режим обдува       1=лицо, 2=ноги, 5=лобовое (AC_WIND_MODE)
 *   16,8   рециркуляция       инверсия: 0=рецирк.вкл, 1=улица (AC_CIRCULATION_MODE)
 *   16,10  уставка темп вод.  raw (AC_DRIVER_TEMP), см. [scaleTemp]
 *   16,12  скорость вентилят. 0..7, 5=авто (AC_WIND_SPEED)
 *   16,13  уставка темп пасс. (AC_PASSENGER_TEMP)
 *   16,18  климат активен     1=работает (AC_SWITCH)
 *   16,24  обдув лобового     1=вкл (AC_MAX_DEFROST_STATE)
 *   16,65  обогрев лобового   1=вкл (AC_FRONT_WINDSHIELD_HEATING, как в Jarvis)
 *   0,95   подогрев сиденья вод. 0..3
 *
 * Температура: PRIMARY — Desay raw 16,10/16,13 со шкалой [scaleTemp]
 * («1°»: raw 10 = 25 °C, сверено с панелью на машине). Фолбэк — VHAL
 * HVAC_TEMPERATURE_SET (0x15600503, float °C, зоны 31/44), если Desay
 * недоступен. Прежняя шкала «0,5°» ((raw+35)/2) занижала: панель 25° →
 * виджет 22,5°, шаг панели 1° → виджет 0,5°.
 *
 * Доп. канальные значения (чтение): 4,15 дверь, 0,100..102 окна,
 * 4,79 аварийка, 4,75 ручник, 0,27 двигатель запущен.
 *
 * Записи здесь нет (команды в шину — в тех виджетах, которым они нужны):
 * - Request-ячейки (16,22/16,23 AC_DRIVER/PASSENGER_TEMP_SET, 16,2 WIND_MODE,
 *   16,24 WIND_LEVEL_SET скорость, 16,1 AUTO, 16,0 AC_SWITCH, 16,45/46
 *   BLOWER_OPEN/CLOSE) подтверждаются read-back по Response-ячейкам.
 * - Подогрев сиденья/лобового пишет HeatWidgetView напрямую.
 */
object ClimateHub {

    /** Откуда взята температура уставки (для debug-сверки с панелью). */
    const val TEMP_SOURCE_VHAL = "VHAL"
    const val TEMP_SOURCE_DESAY = "Desay"

    /** VHAL HVAC_TEMPERATURE_SET: float °C, зоны 31 (вод.) / 44 (пасс.). */
    const val VHAL_HVAC_TEMP_SET = 358614275 // 0x15600503
    private const val AREA_DRIVER = 31
    private const val AREA_PASSENGER = 44

    /** Границы конфига VHAL (dumpsys car service: f min 16.0, f max 32.0). */
    private const val VHAL_TEMP_MIN = 15.5f
    private const val VHAL_TEMP_MAX = 32.5f


    /** Состояние HVAC, нормализованное к человекочитаемым величинам. */
    data class ClimateState(
        /** Температура водителя, °C (VHAL зона 31 / Desay 16,10). null = нет данных. */
        val tempDriverC: Float?,
        /** Температура пассажира, °C (VHAL зона 44 / Desay 16,13). */
        val tempPassC: Float?,
        /** Источник температуры: [TEMP_SOURCE_VHAL] / [TEMP_SOURCE_DESAY]. */
        val tempSource: String?,
        /** Скорость вентилятора 0..7 (Desay 16,12, 5 = авто). */
        val fanSpeed: Int?,
        /** AUTO вкл. */
        val auto: Boolean?,
        /** A/C вкл. */
        val ac: Boolean?,
        /** Рециркуляция вкл. */
        val recirc: Boolean?,
        /** Климат включён (не OFF). */
        val power: Boolean?,
        /** Режим обдува: 1 лицо, 2 ноги, 5 лобовое (Desay 16,6). */
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
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null
            )
        }
    }

    private const val TAG = "ClimateHub"
    private const val G = 16 // группа HVAC

    private var desay: DesayCarReader? = null
    private var vhal: VhalReader? = null
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
            vhal = VhalReader(app)
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
        // Температура: сначала Desay raw со шкалой [scaleTemp] (сверена с
        // панелью), при недоступности — VHAL (float °C).
        val desayDriver = scaleTemp(desay.readInt(G, 10))
        val desayPass = scaleTemp(desay.readInt(G, 13))
        val desayOk = desayDriver != null || desayPass != null
        val tempDriver = if (desayOk) desayDriver else vhalTemp(AREA_DRIVER)
        val tempPass = if (desayOk) desayPass else vhalTemp(AREA_PASSENGER)
        val tempSource = when {
            desayOk -> TEMP_SOURCE_DESAY
            tempDriver != null || tempPass != null -> TEMP_SOURCE_VHAL
            else -> null
        }
        return ClimateState(
            tempDriverC = tempDriver,
            tempPassC = tempPass,
            tempSource = tempSource,
            fanSpeed = desay.readInt(G, 12).takeIf { it in 0..7 },
            auto = boolOr(desay.readInt(G, 4)),
            ac = boolOr(desay.readInt(G, 1)),
            recirc = boolOr(recircRaw)?.not(),
            power = boolOr(powerRaw),
            fanDirection = desay.readInt(G, 6).takeIf { it in 1..5 },
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
     * Сырое значение уставки → °C. Шкала «1°» (как вариант Jarvis «1° (LO,
     * 16..30, HI)»): raw 0 = LO, raw 1..15 = 16..30 °C, raw ≥ 16 = HI.
     * Сверено на машине: панель 25° при raw 10, шаг панели 1° = шаг raw 1.
     * LO/HI возвращаются как [TEMP_LO]/[TEMP_HI] (вне 16..30).
     */
    internal fun scaleTemp(raw: Int): Float? = when {
        raw < 0 -> null
        raw == 0 -> TEMP_LO
        raw >= 16 -> TEMP_HI
        else -> raw + 15f
    }

    /** Уставка «LO» / «HI» (виджет рисует текстом). */
    const val TEMP_LO = 15f
    const val TEMP_HI = 31f

    /** Уставка зоны из VHAL: float °C или null (нет свойства/статуса). */
    private fun vhalTemp(areaId: Int): Float? =
        (vhal?.propertyValue(VHAL_HVAC_TEMP_SET, areaId) as? Float)
            ?.takeIf { it.isFinite() && it in VHAL_TEMP_MIN..VHAL_TEMP_MAX }

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
        vhal = null
    }

    private const val POLL_MS = 4000L
}
