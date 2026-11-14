package com.dash.headunit.car

/**
 * Демо-данные для виджетов, когда до машины не добраться
 * (эмулятор, нет сервиса Desay/VHAL). Подставляются только пока
 * реальные данные не пришли: как только [CarHub]
 * получит снимок, настоящие значения вытесняют демо.
 */
object DemoData {

    /** Снимок для виджета «Автомобиль». */
    fun car(): CarData = CarData(
        fuelPct = 65f,
        fuelLiters = 42f,
        rangeKm = 560,
        voltageV = 13.8f,
        charging = false,
        engineTempC = 90f,
        gearboxTempC = null,
        outsideTempC = 14f,
        outsideRaw = 138, // (14 + 55) × 2, как с шины
        odoKm = null,
        rpm = 800,
        speedKmh = 46f,
        gearRaw = 8, // 4-я передача (сырое = 4 + передача)
        tirePressure = TirePressures(fl = 2.4f, fr = 2.3f, rl = 2.4f, rr = 2.2f),
        source = CarData.SOURCE_DEMO
    )

    /** Вид поездки для «Бортового компьютера». */
    fun trip(): TripTracker.TripView = TripTracker.TripView(
        distKm = 12.6f,
        avgCons = 7.4f,
        avgSpeedKmh = 46f,
        driveMillis = 72L * 60_000
    )
}
