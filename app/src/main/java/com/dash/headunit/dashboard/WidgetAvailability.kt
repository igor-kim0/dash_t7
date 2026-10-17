package com.dash.headunit.dashboard

import com.dash.headunit.BuildConfig

/**
 * Доступность виджетов в текущем варианте поставки.
 *
 * Сборка `dev` (`PROD_BUILD = false`) показывает все виджеты.
 * Сборка `prod` (`PROD_BUILD = true`) — только утверждённый набор:
 * часы, погода, автомобиль, музыка, навигатор, бортовой компьютер, спидометр.
 */
object WidgetAvailability {

    private val PROD: Set<WidgetType> = setOf(
        WidgetType.COMBO,
        WidgetType.WEATHER,
        WidgetType.CAR,
        WidgetType.MUSIC,
        WidgetType.APP,
        WidgetType.TRIP,
        WidgetType.SPEED
    )

    /** Можно ли показывать виджет [type] в текущей сборке. */
    fun isEnabled(type: WidgetType): Boolean =
        !BuildConfig.PROD_BUILD || type in PROD
}
