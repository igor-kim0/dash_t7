package com.dash.headunit.navi

import com.dash.headunit.navi.cluster.CarLanClient
import com.dash.headunit.navi.cluster.ClusterWarnings
import com.dash.headunit.navi.cluster.Guidance
import org.junit.Assert.assertEquals
import org.junit.Test

class CarLanJsonTest {

    @Test
    fun displayCluster() {
        assertEquals(
            "{\"DisplayCluster\":\"true\",\"NaviFrontDeskStatus\":\"true\",\"Perspective\":0," +
                "\"PerspectiveResult\":\"false\",\"RequestDisplayNaviArea\":\"false\"}",
            CarLanClient.Json.displayCluster(true),
        )
        assertEquals(true, CarLanClient.Json.displayCluster(false).startsWith("{\"DisplayCluster\":\"false\""))
    }

    @Test
    fun naviArea() {
        assertEquals("{\"NaviDisplayArea\":1,\"NaviDisplayAreaResult\":\"true\"}", CarLanClient.Json.naviArea(1))
    }

    @Test
    fun warningsAlwaysNaviOff() {
        // NaviStatus 3 включал штатную строку манёвра «0 м Войти» (ГУ 2026-11-01).
        assertEquals(
            "{\"CameraType\":3,\"NaviStatus\":4,\"SpeedingInfo\":0,\"WarningMessage\":-1}",
            ClusterWarnings.json(),
        )
    }

    @Test
    fun overspeedMerges() {
        val a = Guidance(distance = "300 м")
        val b = Guidance(overspeed = true)
        assertEquals(true, a.orElse(b).overspeed)
        assertEquals(false, a.orElse(null).overspeed)
    }

    @Test
    fun naviAreaRequest() {
        assertEquals(1, CarLanClient.Json.naviAreaRequest("{\"RequestNaviAreaDisplay\":1}"))
        assertEquals(0, CarLanClient.Json.naviAreaRequest("{ \"RequestNaviAreaDisplay\" : \"0\" }"))
        assertEquals(null, CarLanClient.Json.naviAreaRequest("{\"NaviDisplayArea\":1}"))
        assertEquals(null, CarLanClient.Json.naviAreaRequest(null))
    }
}
