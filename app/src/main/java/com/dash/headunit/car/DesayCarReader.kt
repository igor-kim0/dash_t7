package com.dash.headunit.car

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Чтение CAN через штатный сервис Desay SV (поставки ГУ Chery, в т.ч. 703002573AA):
 * сервис com.desaysv.svcarinfo/CarInfoService, интерфейс
 * com.desaysv.libsvcarinfo.ICarInfo, transact(3, group, id) -> int[] (сырые байты).
 *
 * Используемые свойства шины 4 (по коду Jarvis + снапшоты new_logs/snapshots):
 * 68 — топливо, десятые %; 69 — температура двигателя, десятые °C;
 * 72 — запас хода, км; 76 — напряжение, десятые В;
 * 62/74 — признак зарядки.
 *
 * Root не нужен — обычное bindService. Все вызовы синхронные,
 * вызывать только с фонового потока.
 */
class DesayCarReader(private val appContext: Context) {

    @Volatile
    private var binder: IBinder? = null

    fun isAvailable(): Boolean {
        return try {
            appContext.packageManager.getServiceInfo(
                ComponentName(
                    "com.desaysv.svcarinfo",
                    "com.desaysv.svcarinfo.CarInfoService"
                ),
                0
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Подключиться к сервису заранее; false — сервиса нет или bind не удался. */
    fun connect(): Boolean = ensureBound()

    private fun ensureBound(): Boolean {
        val b = binder
        if (b != null && b.isBinderAlive) return true
        return try {
            val latch = CountDownLatch(1)
            var fresh: IBinder? = null
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    fresh = service
                    latch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    binder = null
                }
            }
            val ok = appContext.bindService(
                Intent().setClassName(
                    "com.desaysv.svcarinfo",
                    "com.desaysv.svcarinfo.CarInfoService"
                ),
                conn,
                Context.BIND_AUTO_CREATE
            )
            if (!ok) {
                Log.w(TAG, "bindService вернул false")
                return false
            }
            latch.await(4, TimeUnit.SECONDS)
            binder = fresh?.takeIf { it.isBinderAlive }
            binder != null
        } catch (e: Exception) {
            Log.w(TAG, "bind", e)
            false
        }
    }

    /**
     * Сырые байты свойства (group, id) или null.
     * [logErrors] = false — для массового сканирования (не спамить logcat).
     */
    fun readRaw(group: Int, id: Int, logErrors: Boolean = true): IntArray? {
        if (!ensureBound()) return null
        val binder = binder ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken("com.desaysv.libsvcarinfo.ICarInfo")
            data.writeInt(group)
            data.writeInt(id)
            binder.transact(3, data, reply, 0)
            reply.readException()
            return reply.createIntArray()
        } catch (e: Exception) {
            if (logErrors) Log.w(TAG, "read($group,$id)", e)
            return null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    fun readInt(group: Int, id: Int): Int =
        CarScaling.bytesToInt(readRaw(group, id))

    /**
     * Запись свойства: transact(4, group, id, int[]) oneway — как c2 в Jarvis.
     * Возвращает true если транзакция ушла без исключений (сервис принял).
     * ВНИМАНИЕ: пишет в CAN шины комфорта — вызывать только осознанно,
     * значения сверять с прочитанными (write-back).
     */
    fun write(group: Int, id: Int, vararg values: Int): Boolean {
        if (!ensureBound()) return false
        val binder = binder ?: return false
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken("com.desaysv.libsvcarinfo.ICarInfo")
            data.writeInt(group)
            data.writeInt(id)
            data.writeIntArray(values)
            binder.transact(4, data, null, IBinder.FLAG_ONEWAY)
            return true
        } catch (e: Exception) {
            Log.w(TAG, "write($group,$id)", e)
            return false
        } finally {
            data.recycle()
        }
    }

    /**
     * Частичный снимок (снапшоты new_logs/snapshots + test_rec с ГУ):
     * 4,68 топливо (десятые %; проверено: −1.5% за 11 км ≈ 7 л/100),
     * 4,72 запас хода (км), 4,76 напряжение (десятые В, 14.2–14.8),
     * 0,27 двигатель работает, 4,69 тепловое значение (десятые °C,
     * 781→784→810→811; НЕ подтверждено как ECT — OBD ECT 98-99,
     * кандидаты: КПП/выход радиатора/масло, нужен холодный пуск),
     * 16,19 забортная (сырое минус 120: 125→5 при погоде 4–9 °C),
     * 4,71 одометр (4 байта). Плюс известные ячейки (не все читаем здесь):
     * 0,100..102 окна (2 закрыто/4 открыто — подтверждено),
     * 0,5+0,6 люк (закрыт 2,1 / открыт 1,12) + 0,10 (1/6, с лагом),
     * 0,92+4,19 багажник, 0,95 подогрев сиденья водителя (0→2→0),
     * 4,26 селектор/передача: 1=P, 2=R (точные переходы), дальше
     * значение = 4 + передача в D (реальная 1-я читается как 5; 8 на
     * 42 км/ч = 4-я, дауншифты 8→5 к остановке, 5 при 0 км/ч = D1 —
     * прошлая теория «сырое=передача» требовала невозможного:
     * 8-я на 42 и 5-я на месте). N=3 — вывод по порядку, в записях
     * не встречалась: включить N — должна загореться синяя N.
     * 4,62 обороты (сырое/4, сверено с PPS-парсером: 250→1000, 197→788),
     * 0,52[0]/15,17/18,0 — связаны с R/движением, точно не разложены,
     * 4,74 и 4,75 флаги работы, 4,75=0 на паркинге (значение «паркинг»
     * неоднозначно: 1=отпущен vs 1=затянут — нужен целевой тест),
     * 4,79 аварийка, 16,9 компрессор A/C (циклирует сам),
     * 4,8 = [флаги 0–3, младший байт 77–91 растёт за день — масло?
     * нагрузка вентилятора?], 4,62 дрожит (нагрузка?), 4,66 ≈2040–2099
     * с провалами (давление топлива?), 4,67 всплески при электрнагрузке.
     * Не найдены на шине: поворотники (4,79 только аварийка),
     * центральный замок, подогрев руля, температура КПП, ECT (98–99
     * только по OBD).
     *
     * [CarData.charging] = двигатель работает (0,27 из дампа: 0→1 при
     * старте, 1→0 при остановке) — им же считается наработка поездки.
     * Счётчик 4,62 для этого не годится: остаётся >0 и после остановки.
     */
    fun snapshot(): CarData? {
        if (!isAvailable()) return null
        // Топливо может не читаться — не должны теряться пробег/напряжение.
        val fuel = CarScaling.desayFuelPct(readInt(4, 68))
        val range = CarScaling.desayRangeKm(readInt(4, 72))
        val volts = CarScaling.desayVolts(readInt(4, 76))
        val engineRunning = readInt(0, 27) == 1
        val outside = CarScaling.outsideTemp(readInt(16, 19))
        val odo = readInt(4, 71).takeIf { it in 0..3_000_000 }
        // 4,69 как ECT НЕ используем: это не ОЖ (OBD ECT 98–99, а 4,69
        // стабильно 81.0–81.1). Как в Jarvis: температуру двигателя берём
        // только с прямых источников — a0ff или VHAL, иначе «нет данных».
        val rpm = CarScaling.rpmDesay(readInt(4, 62))
        // Тормоз (15,17, гипотеза: 1 при нажатой педали — сходится везде,
        // кроме возможно пропущенного тапа в снапшотах; нужен 5-сек. тест).
        val brake = readInt(15, 17).takeIf { it >= 0 }?.let { it == 1 }
        // Селектор (4,26): 1=P, 2=R точно; 3–9 растут/падают со скоростью.
        val gear = readInt(4, 26).takeIf { it >= 0 }
        if (fuel == null && range == null && volts == null && outside == null && odo == null) {
            return null
        }
        return CarData(
            fuelPct = fuel,
            fuelLiters = null, // досчитает хаб по объёму бака
            rangeKm = range,
            voltageV = volts,
            charging = engineRunning,
            engineTempC = null, // ОЖ в Desay-шине нет (см. KDoc про 4,69)
            gearboxTempC = null,
            outsideTempC = outside,
            odoKm = odo,
            rpm = rpm,
            brakePressed = brake,
            gearRaw = gear,
            source = CarData.SOURCE_DESAY
        )
    }

    companion object {
        private const val TAG = "DesayCar"
    }
}
