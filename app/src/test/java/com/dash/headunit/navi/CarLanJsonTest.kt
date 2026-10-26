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
    fun warningsNoRoute() {
        assertEquals(
            "{\"CameraType\":3,\"NaviStatus\":4,\"SpeedingInfo\":0,\"WarningMessage\":-1}",
            ClusterWarnings.json(null),
        )
    }

    @Test
    fun warningsLimitAndCamera() {
        val road = Guidance(remainingTime = "5 мин", speedLimit = 90)
        assertEquals(
            "{\"CameraType\":3,\"NaviStatus\":3,\"SpeedingInfo\":90,\"WarningMessage\":1}",
            ClusterWarnings.json(road),
        )
        // Лимит камеры важнее лимита участка.
        assertEquals(
            "{\"CameraType\":3,\"NaviStatus\":3,\"SpeedingInfo\":60,\"WarningMessage\":1}",
            ClusterWarnings.json(road.copy(cameraLimit = 60)),
        )
        assertEquals(
            "{\"CameraType\":3,\"NaviStatus\":3,\"SpeedingInfo\":0,\"WarningMessage\":-1}",
            ClusterWarnings.json(Guidance(remainingTime = "5 мин")),
        )
    }

    @Test
    fun overspeedMerges() {
        val a = Guidance(distance = "300 м")
        val b = Guidance(overspeed = true)
        assertEquals(true, a.orElse(b).overspeed)
        assertEquals(false, a.orElse(null).overspeed)
    }
}
