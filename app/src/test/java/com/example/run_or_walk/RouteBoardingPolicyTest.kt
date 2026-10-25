package com.example.run_or_walk

import org.junit.Assert.*
import org.junit.Test

class RouteBoardingPolicyTest {
    private fun route(point: MapPoint?, name: String = "영통역", type: String = "SUBWAY") =
        JourneyTransitRoute("sample", KakaoTransitChoice(
            totalSeconds = 600, transfers = 0, serviceName = "수인분당선",
            boardingStopName = name, boardingPoint = point,
            boardingSource = BoardingPointSource.ESTIMATED, firstTransitType = type))

    @Test fun selectedRouteAutomaticallySetsItsOwnFirstBoardingPin() {
        val point = MapPoint(127.071, 37.251)
        val chosen = routeBoardingPlace(route(point))
        assertNotNull(chosen)
        assertEquals(point, chosen!!.point)
        assertEquals("영통역", chosen.name)
        assertTrue(chosen.detail.contains("승강장"))
    }

    @Test fun missingAndInvalidCoordinatesCannotBeInvented() {
        assertNull(routeBoardingPlace(route(null)))
        assertNull(routeBoardingPlace(route(MapPoint(Double.NaN, 37.251))))
        assertNull(routeBoardingPlace(route(MapPoint(127.0, 99.0))))
        assertNull(routeBoardingPlace(route(MapPoint(127.0, 37.0), name = " ")))
        assertNull(routeBoardingPlace(route(MapPoint(127.0, 37.0), type = "WALKING")))
    }

    @Test fun differentRoutesDoNotReuseThePreviousBoardingPin() {
        val first = routeBoardingPlace(route(MapPoint(127.1, 37.2)))!!
        val second = routeBoardingPlace(route(MapPoint(127.2, 37.3)))!!
        assertNotEquals(first.point, second.point)
    }
}
