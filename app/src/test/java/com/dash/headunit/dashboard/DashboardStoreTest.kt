package com.dash.headunit.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardStoreTest {

    private fun w(id: String, type: WidgetType) = WidgetSpec(id, type, 0, 0, 10, 10)

    @Test
    fun parsePagesDefaultsToFirst() {
        assertEquals(listOf(0), DashboardStore.parsePages(null))
        assertEquals(listOf(0), DashboardStore.parsePages(""))
        assertEquals(listOf(0, 2, 5), DashboardStore.parsePages("0,2, 5,2,x"))
    }

    @Test
    fun pageAfterRemove() {
        assertEquals(2, DashboardStore.pageAfterRemove(listOf(0, 1, 2), 1))
        assertEquals(1, DashboardStore.pageAfterRemove(listOf(0, 1, 2), 2))
        assertEquals(1, DashboardStore.pageAfterRemove(listOf(0, 1, 2), 0))
        assertNull(DashboardStore.pageAfterRemove(listOf(0), 0))
        assertNull(DashboardStore.pageAfterRemove(listOf(0, 1), 7))
    }

    @Test
    fun nextWidgetIdIsUniqueAcrossPages() {
        val all = listOf(
            w("heat_1", WidgetType.HEAT),
            w("heat_3", WidgetType.HEAT),
            w("climate_control_1", WidgetType.CLIMATE_CONTROL),
            w("combo1", WidgetType.COMBO)
        )
        assertEquals("heat_4", DashboardStore.nextWidgetId(WidgetType.HEAT, all))
        // «climate_control_1» не считается виджетом «climate».
        assertEquals("climate_1", DashboardStore.nextWidgetId(WidgetType.CLIMATE, all))
        assertEquals("combo_1", DashboardStore.nextWidgetId(WidgetType.COMBO, all))
        assertEquals("trip_1", DashboardStore.nextWidgetId(WidgetType.TRIP, emptyList()))
    }
}
