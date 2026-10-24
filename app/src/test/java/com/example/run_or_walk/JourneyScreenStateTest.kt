package com.example.run_or_walk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyScreenStateTest {
    private val point = MapPoint(126.9726, 37.5547)
    private val place = JourneyPlace("place-1", "서울역", "서울 용산구", point)

    @Test
    fun switchingFeatureAndProviderKeepsEachJourneyIndependent() {
        val routeKakao = JourneyScreenState(FeatureMode.ROUTE, SearchProvider.KAKAO)
        val stopKakao = JourneyScreenState(FeatureMode.STOP, SearchProvider.KAKAO)
        val routeOdsay = JourneyScreenState(FeatureMode.ROUTE, SearchProvider.ODSAY)
        val stopOdsay = JourneyScreenState(FeatureMode.STOP, SearchProvider.ODSAY)
        routeKakao.origin.query = "강남역"
        routeKakao.origin.selected = place
        routeKakao.arrivalMinutes = "12"
        routeKakao.walkRoute = WalkRoute(400, 300)
        stopKakao.stop.query = "시청역"
        routeOdsay.origin.query = "수원역"
        stopOdsay.stop.query = "광화문"

        stopOdsay.editQuery(SearchField.STOP, "종각")

        assertEquals("강남역", routeKakao.origin.query)
        assertSame(place, routeKakao.origin.selected)
        assertEquals("12", routeKakao.arrivalMinutes)
        assertEquals(WalkRoute(400, 300), routeKakao.walkRoute)
        assertEquals("시청역", stopKakao.stop.query)
        assertEquals("수원역", routeOdsay.origin.query)
        assertEquals("종각", stopOdsay.stop.query)
        assertNull(stopKakao.walkRoute)
        assertNull(routeOdsay.origin.selected)
    }

    @Test
    fun editingOriginInvalidatesOldRouteAndWalkingGuidance() {
        val state = JourneyScreenState(FeatureMode.ROUTE, SearchProvider.KAKAO)
        val route = JourneyTransitRoute(
            id = "route-1",
            kakao = KakaoTransitChoice(600, 1, "1호선", "서울역", point)
        )
        state.origin.selected = place
        state.origin.options = listOf(place)
        state.origin.searched = true
        state.destination.selected = place.copy(id = "destination", name = "동탄역")
        state.routes = listOf(route)
        state.routeSearchCompleted = true
        state.selectedRoute = route
        state.boarding = place
        state.boardingCandidates = listOf(place)
        state.candidatesSearched = true
        state.walkRoute = WalkRoute(400, 300)
        state.walkingPoints = listOf(point)
        state.assessment = calculateUrgency(10.0, 2.0, 300)
        state.mapCenter = point

        state.editQuery(SearchField.ORIGIN, "시청역")

        assertNull(state.origin.selected)
        assertTrue(state.origin.options.isEmpty())
        assertFalse(state.origin.searched)
        assertTrue(state.routes.isEmpty())
        assertFalse(state.routeSearchCompleted)
        assertNull(state.selectedRoute)
        assertNull(state.boarding)
        assertTrue(state.boardingCandidates.isEmpty())
        assertFalse(state.candidatesSearched)
        assertNull(state.walkRoute)
        assertTrue(state.walkingPoints.isEmpty())
        assertNull(state.assessment)
        assertNull(state.mapCenter)
        // The unrelated destination selection remains useful for the next search.
        assertEquals("동탄역", state.destination.selected?.name)
    }

    @Test
    fun changingStopInvalidatesPendingWorkAndPreservesManualTimePreferences() {
        val state = JourneyScreenState(FeatureMode.STOP, SearchProvider.ODSAY)
        state.stop.selected = place
        state.arrivalMinutes = "8.5"
        state.bufferMinutes = "1.5"
        val oldRequest = state.beginRequest("현재 위치 확인 중")
        state.waitingForPermission = true
        val oldRevision = state.revision

        state.editQuery(SearchField.STOP, "신촌역")

        assertNotEquals(oldRequest, state.requestId)
        assertNotEquals(oldRevision, state.revision)
        assertFalse(state.busy)
        assertNull(state.stop.selected)
        assertEquals("8.5", state.arrivalMinutes)
        assertEquals("1.5", state.bufferMinutes)
    }

    @Test
    fun aNewRequestMakesThePreviousResponseStale() {
        val state = JourneyScreenState(FeatureMode.ROUTE, SearchProvider.KAKAO)
        val firstRequest = state.beginRequest("첫 검색")
        val secondRequest = state.beginRequest("새 검색")

        assertNotEquals(firstRequest, secondRequest)
        assertEquals(secondRequest, state.requestId)
        assertTrue(state.loading)
        assertEquals("새 검색", state.status)
    }

    @Test
    fun uncertainBoardingCoordinatesAlwaysKeepAWarning() {
        val state = JourneyScreenState(FeatureMode.ROUTE, SearchProvider.KAKAO)
        state.boardingSource = BoardingPointSource.ESTIMATED
        val estimated = requireNotNull(state.boardingNotice)
        assertTrue(estimated.contains("추정"))
        assertFalse(estimated.contains("검증된"))
        assertFalse(estimated.contains("확정"))

        state.boardingSource = BoardingPointSource.UNKNOWN
        val unknown = requireNotNull(state.boardingNotice)
        assertTrue(unknown.contains("미확인"))
        assertTrue(unknown.contains("후보"))
        assertFalse(unknown.contains("검증된"))
        assertFalse(unknown.contains("확정"))

        state.boardingChosenByUser = true
        val chosen = requireNotNull(state.boardingNotice)
        assertTrue(chosen.contains("직접 선택"))
        assertTrue(chosen.contains("확인"))
        assertFalse(chosen.contains("검증된"))
    }

    @Test
    fun odsayRouteDoesNotInventMissingTransferInformation() {
        val stop = TransitStop(1, "서울역", 1, point, "1호선")
        val route = JourneyTransitRoute(
            id = "odsay-route",
            odsay = TransitPlan(20, stop, "1호선", "청량리 방면")
        )

        assertEquals(20, route.durationMinutes)
        assertTrue(route.detail.contains("청량리 방면"))
        assertFalse(route.detail.contains("환승"))
    }
}
