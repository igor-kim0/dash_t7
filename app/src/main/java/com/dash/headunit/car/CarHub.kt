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
 * Общий источник автомобильных данных. Опрашивает все доступные
 * провайдеры (Desay SV binder → VHAL) и склеивает лучший снимок:
 * каждое поле берётся из первого провайдера, где оно есть.
 *
 * Объём бака — настройка (по умолчанию 51 л, как в Jarvis для Chery).
 */
object CarHub {

    private var desay: DesayCarReader? = null
    private var vhal: VhalReader? = null
    private var appContext: Context? = null

    var data: CarData? = null
        private set

    /** Момент последнего УСПЕШНОГО чтения провайдеров (wall ms). */
    @Volatile
    private var lastFreshTs = 0L

    /** Последний вид поездки ([TripTracker.update] в цикле опроса). */
    var tripView: TripTracker.TripView? = null
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
        // ОЖ есть только на приборке — читаем её отдельным потоком.
        ClusterPps.start()
        if (pollJob == null) {
            pollJob = scope.launch {
                while (true) {
                    refresh()
                    delay(POLL_MS)
                }
            }
        }
    }

    /**
     * Данные можно верить: снимок молодой. Пока шина спит, старый снимок
     * («двигатель работает») остаётся для показа виджетам, но расчётам
     * борткомпьютера он не годится — иначе «заглушили» не наступит никогда.
     */
    fun freshData(): CarData? {
        val stale = System.currentTimeMillis() - lastFreshTs > FRESH_MS
        return data?.takeIf { !stale }
    }

    fun refresh() {
        val desay = desay ?: return
        val vhal = vhal ?: return
        scope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                readMerged(desay, vhal)
            }
            if (snapshot != null) {
                data = snapshot
                lastFreshTs = System.currentTimeMillis()
            }
            // Уведомляем и при пустом снимке: тики борткомпьютера идут
            // даже когда шина спит / провайдеры молчат.
            notifyListeners()
            tickTripTracker(snapshot)
        }
    }

    /**
     * Счётчик и журнал поездок качаются прямо в цикле опроса — независимо
     * от того, какие виджеты на экране. Снимок отдаём только свежий: старый
     * «работающий» двигатель завешал бы переход «заглушили».
     */
    private fun tickTripTracker(freshSnapshot: CarData?) {
        val ctx = appContext ?: return
        try {
            tripView = TripTracker.update(
                ctx,
                freshSnapshot,
                tankLiters(),
                ClusterPps.trip()
            )
        } catch (e: Exception) {
            android.util.Log.w(TAG, "trip tick", e)
        }
    }

    private fun readMerged(desay: DesayCarReader, vhal: VhalReader): CarData? {
        val tank = tankLiters()
        var merged: CarData? = null
        // Порядок — от приоритетного: сначала Desay (родной для ГУ Chery).
        val parts = listOf(
            runCatching { desay.snapshot() }.getOrNull(),
            runCatching { vhal.snapshot() }.getOrNull()
        )
        for (p in parts) {
            if (p == null) continue
            merged = CarData(
                fuelPct = merged?.fuelPct ?: p.fuelPct,
                fuelLiters = null, // досчитаем ниже
                rangeKm = merged?.rangeKm ?: p.rangeKm,
                voltageV = merged?.voltageV ?: p.voltageV,
                charging = merged?.charging ?: p.charging,
                engineTempC = merged?.engineTempC ?: p.engineTempC,
                gearboxTempC = merged?.gearboxTempC ?: p.gearboxTempC,
                outsideTempC = merged?.outsideTempC ?: p.outsideTempC,
                odoKm = merged?.odoKm ?: p.odoKm,
                rpm = merged?.rpm ?: p.rpm,
                speedKmh = merged?.speedKmh ?: p.speedKmh,
                brakePressed = merged?.brakePressed ?: p.brakePressed,
                gearRaw = merged?.gearRaw ?: p.gearRaw,
                source = if (merged == null) p.source else CarData.SOURCE_MERGED
            )
        }
        // ОЖ с приборки точнее всего остального — приоритет над провайдерами.
        val coolant = ClusterPps.coolantC()
        if (merged == null) {
            if (coolant == null) return null
            return CarData(
                fuelPct = null, fuelLiters = null, rangeKm = null, voltageV = null,
                charging = null, engineTempC = coolant, gearboxTempC = null,
                outsideTempC = null, odoKm = null, source = CarData.SOURCE_MERGED
            )
        }
        return merged.copy(
            fuelLiters = CarScaling.fuelLiters(merged.fuelPct, tank),
            engineTempC = coolant ?: merged.engineTempC,
            // VHAL в приложении без прав (CAR_VENDOR_EXTENSION denied),
            // а Desay-ячейка может не читаться: дублируем с приборки (PPS),
            // которая на ГУ proven working (скорость/обороты в логах).
            speedKmh = merged.speedKmh ?: ClusterPps.speedKmh()?.toFloat(),
            rpm = merged.rpm ?: ClusterPps.rpm()
        )
    }

    fun tankLiters(): Int {
        val ctx = appContext ?: return DEFAULT_TANK_L
        return try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_TANK, DEFAULT_TANK_L)
        } catch (e: Exception) {
            DEFAULT_TANK_L
        }
    }

    fun setTankLiters(liters: Int) {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putInt(KEY_TANK, liters)?.apply()
    }

    /** Для тестов. */
    fun resetForTests() {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        listeners.clear()
        data = null
        lastFreshTs = 0L
        tripView = null
        pollJob = null
        appContext = null
        desay = null
        vhal = null
    }

    private const val POLL_MS = 5000L
    /** Данные устарели: шина спит/сервис отвалился — двигателю не верим. */
    private const val FRESH_MS = 25_000L
    private const val TAG = "CarHub"
    private const val PREFS = "car_prefs"
    private const val KEY_TANK = "tankLiters"
    const val DEFAULT_TANK_L = 51
}
