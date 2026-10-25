package com.dash.headunit.car

/**
 * Снимок автомобильных данных для виджета.
 * Все поля nullable: отсутствие значения рисуется как "—".
 */
data class CarData(
    /** Уровень топлива, % (0..100). */
    val fuelPct: Float?,
    /** Литров в баке (вычисляется по объёму бака из настроек). */
    val fuelLiters: Float?,
    /** Запас хода, км (напрямую с CAN). */
    val rangeKm: Int?,
    /** Напряжение бортовой сети, В. */
    val voltageV: Float?,
    /** Признак зарядки АКБ (по данным CAN). */
    val charging: Boolean?,
    /** Температура двигателя (ОЖ), °C. */
    val engineTempC: Float?,
    /** Температура коробки передач, °C (если доступна). */
    val gearboxTempC: Float?,
    /** Забортная температура, °C (Desay шина 16 id 19). */
    val outsideTempC: Float?,
    /** Сырое значение ячейки 16,19 (dash: raw−120; Jarvis: прямые °C). */
    val outsideRaw: Int? = null,
    /** Одометр, км (Desay шина 4 id 71) — для борткомпьютера. */
    val odoKm: Int?,
    /** Обороты двигателя, об/мин (Desay 4,62, сырое значение / 4). */
    val rpm: Int? = null,
    /** Скорость, км/ч (VHAL PERF_VEHICLE_SPEED) — для спидометра. */
    val speedKmh: Float? = null,
    /** Педаль тормоза нажата (Desay 15,17, гипотеза) — для селектора P/1. */
    val brakePressed: Boolean? = null,
    /** Селектор КПП, сырой код Desay (4,26): 1=P, 2=R, 3=N?, 5+=4+передача. */
    val gearRaw: Int? = null,
    /** Источник данных (для диагностики). */
    val source: String = SOURCE_UNKNOWN
) {
    companion object {
        const val SOURCE_UNKNOWN = "none"
        const val SOURCE_DEMO = "demo"
        const val SOURCE_DESAY = "desay"
        const val SOURCE_VHAL = "vhal"
        const val SOURCE_MERGED = "merged"
    }
}
