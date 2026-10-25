package com.dash.headunit.car

import android.content.Context
import android.util.Log

    /**
     * Чтение Vehicle HAL напрямую через android.car.Car (рефлексия,
     * как CarCtl из Jarvis, но внутри процесса и без root/ADB).
     *
     * ID по дампу car_service ГУ 703002573AA (конфиг реально есть):
     *   0x11600307 FUEL_LEVEL (float), 0x11600204 PERF_ODOMETER (float),
     *   0x11600703 ENV_OUTSIDE_TEMPERATURE (float),
     *   0x11600304 ENGINE_OIL_TEMP (float), 0x11400409 IGNITION_STATE (int32),
     *   0x21414411 SV_SEND_TEMP_INFO (int32, сотые градуса) — НЕ ОЖ (см. readEngineTemp).
     * AOSP ENGINE_COOLANT_TEMP (0x11600301) в конфиге ГУ НЕТ.
     * Вендорные ID Jarvis (557850663 и др.) в конфиге ГУ НЕТ — оставлены
     * как запасной путь для Chery T19FL3.
     *
     * Снапшот частичный: возвращает все прочитанные поля, null — только
     * если не удалось ничего.
     *
     * На устройствах без android.car или без прав — тихо возвращает null.
     * Все вызовы синхронные, только с фонового потока.
     */
class VhalReader(private val appContext: Context) {

    @Volatile
    private var manager: Any? = null

    private fun ensureManager(): Any? {
        manager?.let { return it }
        return try {
            val carClass = Class.forName("android.car.Car")
            val car = try {
                // Новый вариант (API 29+).
                carClass.getMethod("createCar", Context::class.java)
                    .invoke(null, appContext)
            } catch (e: NoSuchMethodException) {
                // Старый вариант (API 28, как на ГУ): createCar(Context, ServiceConnection).
                val conn = object : android.content.ServiceConnection {
                    override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) = Unit
                    override fun onServiceDisconnected(name: android.content.ComponentName?) = Unit
                }
                carClass.getMethod(
                    "createCar", Context::class.java,
                    android.content.ServiceConnection::class.java
                ).invoke(null, appContext, conn)
            } ?: return null
            val isConnected = carClass.getMethod("isConnected")
                .invoke(car) as? Boolean ?: false
            if (!isConnected) {
                carClass.getMethod("connect").invoke(car)
                var tries = 0
                while (tries < 30) {
                    val connected = carClass.getMethod("isConnected")
                        .invoke(car) as? Boolean ?: false
                    if (connected) break
                    Thread.sleep(100)
                    tries++
                }
            }
            val mgr = carClass.getMethod("getCarManager", String::class.java)
                .invoke(car, "property")
            manager = mgr
            mgr
        } catch (e: Exception) {
            Log.w(TAG, "no car service", e)
            null
        }
    }

    fun getIntProperty(propId: Int, areaId: Int = 0): Int? {
        val mgr = ensureManager() ?: return null
        return try {
            mgr.javaClass.getMethod(
                "getIntProperty",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).invoke(mgr, propId, areaId) as? Int
        } catch (e: Exception) {
            null
        }
    }

    fun getFloatProperty(propId: Int, areaId: Int = 0): Float? {
        val mgr = ensureManager() ?: return null
        return try {
            mgr.javaClass.getMethod(
                "getFloatProperty",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).invoke(mgr, propId, areaId) as? Float
        } catch (e: Exception) {
            null
        }
    }

    fun getBooleanProperty(propId: Int, areaId: Int = 0): Boolean? {
        val mgr = ensureManager() ?: return null
        return try {
            mgr.javaClass.getMethod(
                "getBooleanProperty",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).invoke(mgr, propId, areaId) as? Boolean
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Чтение в общей форме через getProperty + статус CarPropertyValue.
     * Старые getInt/Float/BooleanProperty на Android 9 при отсутствии
     * свойства не бросают, а тихо возвращают 0 — из-за этого «Справочник»
     * показывал нули вместо «—». Здесь null: нет VHAL, свойства нет,
     * статус не AVAILABLE. Численно различает «0 настоящий» от «0 мусор».
     */
    fun propertyValue(propId: Int, areaId: Int = 0): Any? {
        val mgr = ensureManager() ?: return null
        return try {
            val cpv = mgr.javaClass.getMethod(
                "getProperty",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).invoke(mgr, propId, areaId) ?: return null
            val status = cpv.javaClass.getMethod("getStatus").invoke(cpv) as? Int ?: return null
            if (status != STATUS_AVAILABLE) return null
            cpv.javaClass.getMethod("getValue").invoke(cpv)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Пошаговая диагностика для debug-экрана: где именно рвётся VHAL-путь.
     * Вызывать с фонового потока.
     */
    fun debugProbe(): List<String> {
        val out = mutableListOf<String>()
        val carClass = try {
            Class.forName("android.car.Car")
        } catch (e: Exception) {
            out.add("vhal: нет класса android.car.Car")
            return out
        }
        out.add("vhal: класс android.car.Car есть")
        try {
            val methods = carClass.methods
                .filter { it.name.contains("createCar", ignoreCase = true) }
                .map { it.name + "(" + it.parameterTypes.joinToString(",") { p -> p.simpleName } + ")" }
            out.add("vhal: createCar-сигнатуры: ${methods.ifEmpty { listOf("НЕТ") }}")
        } catch (e: Exception) {
            out.add("vhal: методы не перечислены: ${e.message}")
        }
        val car = try {
            carClass.getMethod("createCar", Context::class.java)
                .invoke(null, appContext)
        } catch (e: Exception) {
            out.add("vhal: createCar: ${e.javaClass.simpleName}: ${cause(e)}")
            return out
        }
        if (car == null) {
            out.add("vhal: createCar вернул null")
            return out
        }
        out.add("vhal: createCar ok")
        try {
            val connected = carClass.getMethod("isConnected").invoke(car) as? Boolean ?: false
            if (!connected) {
                carClass.getMethod("connect").invoke(car)
                var tries = 0
                var ok = false
                while (tries < 30) {
                    if ((carClass.getMethod("isConnected").invoke(car) as? Boolean) == true) {
                        ok = true
                        break
                    }
                    Thread.sleep(100)
                    tries++
                }
                out.add("vhal: connect, итог=$ok")
                if (!ok) return out
            } else {
                out.add("vhal: уже connected")
            }
        } catch (e: Exception) {
            out.add("vhal: connect: ${e.javaClass.simpleName}: ${cause(e)}")
            return out
        }
        val mgr = try {
            carClass.getMethod("getCarManager", String::class.java).invoke(car, "property")
        } catch (e: Exception) {
            out.add("vhal: getCarManager: ${e.javaClass.simpleName}: ${cause(e)}")
            return out
        }
        if (mgr == null) {
            out.add("vhal: getCarManager вернул null")
            return out
        }
        out.add("vhal: property manager получен")
        manager = mgr
        // Пробные чтения: что реально отвечает на этом ГУ.
        for ((name, id, isFloat) in listOf(
            Triple("COOLANT", PROP_COOLANT, true),
            Triple("OIL_TEMP", PROP_OIL_TEMP, true),
            Triple("FUEL_AOSP", PROP_FUEL, true),
            Triple("ODO_AOSP", PROP_ODOMETER, true),
            Triple("OUTSIDE", PROP_OUTSIDE, true),
            Triple("IGNITION", PROP_IGNITION, false),
            Triple("TEMP_INFO", PROP_TEMP_INFO, false),
            Triple("FUEL_JARVIS", PROP_FUEL_JARVIS, false),
            Triple("VOLT_JARVIS", PROP_VOLT_JARVIS, true)
        )) {
            try {
                val v = if (isFloat) {
                    getFloatProperty(id)?.toString()
                } else {
                    getIntProperty(id)?.toString()
                }
                out.add("vhal: read $name(0x${id.toUInt().toString(16)}) = $v")
            } catch (e: Exception) {
                out.add("vhal: read $name: ${e.javaClass.simpleName}: ${cause(e)}")
            }
        }
        return out
    }

    private fun cause(e: Exception): String {
        val c = e.cause
        return if (c != null) "${c.javaClass.simpleName}: ${c.message}" else (e.message ?: "—")
    }

    /** Топливо: AOSP float (конфиг ГУ) → вендор Jarvis (T19FL3). */
    private fun readFuelPct(): Float? {
        val f = getFloatProperty(PROP_FUEL)
        if (f != null && f.isFinite() && f >= 0f) {
            val pct = when {
                f <= 1f -> f * 100f       // доля 0..1
                f <= 100f -> f            // проценты
                f <= 1000f -> f / 10f     // десятые процента
                else -> return null
            }
            if (pct in 0f..100f) return pct
        }
        return getIntProperty(PROP_FUEL_JARVIS)?.let {
            CarScaling.vhalFuelPct(it, CarScaling.isCheryT19())
        }
    }

    /**
     * Температура ОЖ — только AOSP ENGINE_COOLANT_TEMP (на прошивках, где
     * он есть). На ГУ его нет: реальная ОЖ приходит с приборки
     * ([ClusterPps]). ENGINE_OIL_TEMP (в дампе ни одного события) и
     * SV_SEND_TEMP_INFO (47..52 °C при любом режиме двигателя, падает от
     * рециркуляции — похоже на температуру самого ГУ) за ОЖ не выдаём.
     */
    private fun readEngineTemp(): Float? {
        // Ноль — флаг «свойство не публикуется» (иначе на ГУ висели бы 0°,
        // см. RetroSpeedometerView): студенные градусы мимо нуля не бывают.
        CarScaling.coolantTemp(getFloatProperty(PROP_COOLANT)
            ?: getIntProperty(PROP_COOLANT)?.toFloat())
            ?.takeIf { it > 0f }
            ?.let { return it }
        return null
    }

    fun snapshot(): CarData? {
        if (ensureManager() == null) return null
        return try {
            val fuel = readFuelPct()
            val range = getIntProperty(PROP_RANGE)?.let { CarScaling.vhalRangeKm(it) }
                ?: getIntProperty(PROP_RANGE_HCU)?.let { CarScaling.vhalRangeKm(it) }
            val volts = getFloatProperty(PROP_VOLT_JARVIS)
                ?.let { CarScaling.vhalVolts(it.toDouble()) }
            val engineTemp = readEngineTemp()
            // ENV_OUTSIDE_TEMPERATURE: как лад Exterior у Jarvis (−50..60), но
            // с проверкой статуса: Android 9 при отсутствии значения тихо
            // отдаёт 0 (getFloatProperty) — ноль прошёл бы валидацию и
            // повис «за бортом 0°». propertyValue отфильтрует не-AVAILABLE.
            val outside = (propertyValue(PROP_OUTSIDE) as? Float)
                ?.takeIf { it.isFinite() && it >= -50f && it <= 60f }
            // Пробег: стандартный PERF_ODOMETER (float, км) — в конфиге ГУ есть.
            val odoRaw = getFloatProperty(PROP_ODOMETER) ?: getIntProperty(PROP_ODOMETER)?.toFloat()
            val odo = odoRaw?.takeIf { it.isFinite() && it >= 0f && it <= 3_000_000f }?.toInt()
            // Скорость: PERF_VEHICLE_SPEED (float, м/с) — подтверждена
            // событиями VHAL на ГУ (9.16→0 м/с при остановке).
            val speed = getFloatProperty(PROP_SPEED)?.let { it * 3.6f }
                ?.takeIf { it.isFinite() && it >= 0f && it <= 300f }
            if (fuel == null && range == null && volts == null &&
                engineTemp == null && outside == null && odo == null && speed == null
            ) {
                return null
            }
            CarData(
                fuelPct = fuel,
                fuelLiters = null, // досчитает хаб по объёму бака
                rangeKm = range,
                voltageV = volts,
                charging = null, // по VHAL достоверно не определяется
                engineTempC = engineTemp,
                gearboxTempC = null, // стандартного свойства нет
                outsideTempC = outside,
                odoKm = odo,
                speedKmh = speed,
                source = CarData.SOURCE_VHAL
            )
        } catch (e: Exception) {
            Log.w(TAG, "snapshot", e)
            null
        }
    }

    companion object {
        private const val TAG = "VhalReader"
        /**
         * CarPropertyValue.STATUS_AVAILABLE — значение с устройства. По AOSP
         * (P, Q и main): AVAILABLE = 0, UNAVAILABLE = 1, ERROR = 2, а не
         * наоборот; читается рефлексией на случай вендорских правок кар-либы.
         */
        private val STATUS_AVAILABLE: Int = try {
            Class.forName("android.car.hardware.CarPropertyValue")
                .getField("STATUS_AVAILABLE").getInt(null)
        } catch (e: Exception) {
            0
        }

        /** AOSP FUEL_LEVEL (float) — в конфиге ГУ 703002573AA есть. */
        const val PROP_FUEL = 291504903            // 0x11600307
        /** AOSP PERF_ODOMETER (float, км) — есть в конфиге ГУ. */
        const val PROP_ODOMETER = 291504644        // 0x11600204
        /** AOSP PERF_VEHICLE_SPEED (float, м/с) — подтверждена событиями на ГУ. */
        const val PROP_SPEED = 291504647         // 0x11600207
        /** AOSP ENV_OUTSIDE_TEMPERATURE (float) — есть в конфиге ГУ. */
        const val PROP_OUTSIDE = 291505923         // 0x11600703
        /** AOSP ENGINE_OIL_TEMP (float) — есть в конфиге ГУ. */
        const val PROP_OIL_TEMP = 291504900        // 0x11600304
        /** AOSP IGNITION_STATE (int32: 2 off, 3 acc, 4 run) — есть в конфиге. */
        const val PROP_IGNITION = 289408009        // 0x11400409
        /** AOSP EV_BATTERY_LEVEL (float) — есть, для SOC. */
        const val PROP_SOC = 291504905             // 0x11600309
        /** AOSP VehiclePropertyIds.ENGINE_COOLANT_TEMP — в конфиге ГУ НЕТ. */
        const val PROP_COOLANT = 291504897         // 0x11600301
        /** Вендор SV_SEND_TEMP_INFO (int32, сотые °C) — не ОЖ, только в debugProbe. */
        const val PROP_TEMP_INFO = 557925393       // 0x21414411
        // Вендорные ID Jarvis (T19FL3) — в конфиге ГУ 703002573AA НЕТ.
        const val PROP_FUEL_JARVIS = 557850663
        const val PROP_RANGE = 557850664
        const val PROP_RANGE_HCU = 557854925
        const val PROP_VOLT_JARVIS = 559947843
        const val PROP_RPM = 557850661
    }
}
