package com.example.run_or_walk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GyeonggiBusLookupTest {
    private val point = MapPoint(127.05, 37.25)
    private fun route(name: String = "5100", stop: String = "영통역") =
        KakaoTransitChoice(800, 0, name, stop, point,
            BoardingPointSource.ESTIMATED, "BUS")
    private val official = GyeonggiNearbyStop("200000177", "영통역", point, 12.0)
    private val valid = GyeonggiStopRoute("200000037", "5100", "19", "강남")

    @Test fun routeSelectionAutomaticallyMatchesOneOfficialStopAndLine() {
        val candidates = gyeonggiStopCandidates(route(), listOf(official))
        assertEquals(listOf(official), candidates)
        assertEquals(listOf(official.copy(name = "영통역.경희대")), gyeonggiStopCandidates(
            route(), listOf(official.copy(name = "영통역.경희대"))))
        val match = resolveGyeonggiBusMatch(route(), listOf(official to valid))
        assertEquals("200000177", match?.stationId)
        assertEquals("200000037", match?.routeId)
        assertEquals("19", match?.identity?.directionId)
    }
    @Test fun matchedOfficialDestinationResolvesOppositeDirectionStops() {
        val opposite = official.copy(stationId = "200000178")
        val otherDirection = valid.copy(staOrder = "20", destination = "광교")
        val rows = listOf(opposite to otherDirection, official to valid)
        assertEquals(official.stationId,
            resolveGyeonggiBusMatch(route().copy(directionName = "강남방면"), rows)?.stationId)
        assertEquals(opposite.stationId,
            resolveGyeonggiBusMatch(route().copy(directionName = "광교행"), rows)?.stationId)
        assertNull(resolveGyeonggiBusMatch(route().copy(directionName = "판교방면"), rows))
    }
    @Test fun sameNumberAtTwoOrdersNeverChoosesOneArbitrarily() {
        val alternate = valid.copy(staOrder = "20")
        assertNull(resolveGyeonggiBusMatch(route(),
            listOf(official to valid, official to alternate)))
    }
    @Test fun rejectsWrongLineAndFarAwayOrDifferentName() {
        assertNull(resolveGyeonggiBusMatch(route("3"), listOf(official to valid)))
        assertEquals(emptyList<GyeonggiNearbyStop>(), gyeonggiStopCandidates(route(),
            listOf(official.copy(point = MapPoint(127.10, 37.25)))))
        assertEquals(emptyList<GyeonggiNearbyStop>(), gyeonggiStopCandidates(route(),
            listOf(official.copy(name = "다른정류장"))))
    }
}
