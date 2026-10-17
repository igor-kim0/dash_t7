package com.dash.headunit.car

/**
 * Демо-данные для виджетов, когда до машины не добраться
 * (эмулятор, нет сервиса Desay/VHAL). Подставляются только пока
 * реальные данные не пришли: как только [CarHub] или [ClimateHub]
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
        odoKm = null,
        rpm = 800,
        speedKmh = 46f,
        gearRaw = 8, // 4-я передача (сырое = 4 + передача)
        source = CarData.SOURCE_DEMO
    )

    /** Вид поездки для «Бортового компьютера». */
    fun trip(): TripTracker.TripView = TripTracker.TripView(
        distKm = 12.6f,
        avgCons = 7.4f,
        avgSpeedKmh = 46f,
        driveMillis = 72L * 60_000
    )

    /** Состояние для виджета «Климат». */
    fun climate(): ClimateHub.ClimateState = ClimateHub.ClimateState(
        tempDriverC = 21f,
        tempPassC = 21f,
        fanSpeed = 3,
        auto = true,
        ac = false,
        recirc = false,
        power = true,
        fanDirection = 1,
        defrostFront = false,
        doorDriverOpen = false,
        windows = listOf(false, false, false),
        hazard = false,
        parkingBrake = false,
        engineOn = false,
        sync = true,
        windshieldHeat = false,
        seatHeatDriver = 0
    )
}
