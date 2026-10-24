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
    private val place = JourneyPlace("kakao-place-1", "서울역", "서울 용산구", point)
    private val identity = OfficialBoardingIdentity("seoul-subway", "1001000133", "1001", "상행", "소요산행")
    private fun arrival(train: String = "123", direction: OfficialBoardingIdentity = identity,
        fetchedAt: Long = 1_000L) = OfficialSubwayArrival(
        direction, "상행 · 소요산행", "1호선", train,
        RealtimeArrivalSnapshot(direction, 601_000L, fetchedAtEpochMillis = fetchedAt,
            validForMillis = 60_000L))

    @Test
    fun twoFeatureStatesKeepSearchesAndResultsIndependentWithoutDefaultDestinations() {
        val route = JourneyScreenState(FeatureMode.ROUTE)
        val stop = JourneyScreenState(FeatureMode.STOP)
        assertEquals("", route.destination.query)
        assertEquals("", stop.stop.query)
        route.destination.query = "강남역"
        route.destination.selected = place
        route.walkRoute = WalkRoute(400, 300)
        stop.stop.query = "시청역"

        stop.editQuery(SearchField.STOP, "종각역")

        assertEquals("강남역", route.destination.query)
        assertSame(place, route.destination.selected)
        assertEquals(WalkRoute(400, 300), route.walkRoute)
        assertEquals("종각역", stop.stop.query)
        assertNull(stop.walkRoute)
        assertEquals(2, FeatureMode.entries.size)
    }

    @Test
    fun editingDestinationInvalidatesRoutesBoardingLiveSelectionAndWalking() {
        val state = JourneyScreenState(FeatureMode.ROUTE)
        val route = JourneyTransitRoute("route-1", KakaoTransitChoice(600, 1, "1호선", "서울역", point))
        state.destination.selected = place
        state.destination.options = listOf(place)
        state.destination.searched = true
        state.routes = listOf(route)
        state.routeSearchCompleted = true
        state.selectedRoute = route
        state.boarding = place
        state.boardingChosenByUser = true
        state.boardingCandidates = listOf(place)
        state.selectedStation = OfficialSubwayStation(identity.stopId, identity.routeId, "서울", "1호선")
        state.selectedArrival = arrival()
        state.arrivalOptions = listOf(arrival())
        state.walkRoute = WalkRoute(400, 300)
        state.walkingPoints = listOf(point)
        state.guidanceWalkingRoute = GuidanceWalkingRoute(point, point, identity, 300, 1_000L, 120_000L)
        state.location = GuidanceLocation(point, 10.0, 1_000L)
        state.mapCenter = point
        val pending = state.beginLiveRequest()

        state.editQuery(SearchField.DESTINATION, "시청역")

        assertNull(state.destination.selected)
        assertTrue(state.destination.options.isEmpty())
        assertFalse(state.destination.searched)
        assertTrue(state.routes.isEmpty())
        assertFalse(state.routeSearchCompleted)
        assertNull(state.selectedRoute)
        assertNull(state.boarding)
        assertFalse(state.boardingChosenByUser)
        assertNull(state.selectedStation)
        assertNull(state.selectedArrival)
        assertTrue(state.arrivalOptions.isEmpty())
        assertNull(state.walkRoute)
        assertTrue(state.walkingPoints.isEmpty())
        assertNull(state.guidanceWalkingRoute)
        assertNull(state.location)
        assertNull(state.mapCenter)
        assertFalse(state.acceptsLiveResponse(pending))
    }

    @Test
    fun editingStopInvalidatesPermissionAndAllPendingResponses() {
        val state = JourneyScreenState(FeatureMode.STOP)
        state.stop.selected = place
        val request = state.beginRequest("現在位置")
        val live = state.beginLiveRequest()
        state.waitingForPermission = true
        val revision = state.revision

        state.editQuery(SearchField.STOP, "신촌역")

        assertFalse(state.acceptsResponse(request))
        assertFalse(state.acceptsLiveResponse(live))
        assertNotEquals(revision, state.revision)
        assertFalse(state.busy)
        assertFalse(state.liveLoading)
        assertNull(state.stop.selected)
    }

    @Test
    fun newerSearchAndLiveGenerationsRejectOlderResponses() {
        val state = JourneyScreenState(FeatureMode.ROUTE)
        val search = state.beginRequest("첫 검색")
        val newerSearch = state.beginRequest("새 검색")
        val live = state.beginLiveRequest()
        val newerLive = state.beginLiveRequest()
        assertFalse(state.acceptsResponse(search))
        assertTrue(state.acceptsResponse(newerSearch))
        assertFalse(state.acceptsLiveResponse(live))
        assertTrue(state.acceptsLiveResponse(newerLive))
    }

    @Test
    fun leavingTabRejectsResponsesButRetainsItsSelectedPlace() {
        val state = JourneyScreenState(FeatureMode.STOP)
        state.stop.selected = place
        val request = state.beginRequest("검색")
        val live = state.beginLiveRequest()

        state.invalidateRequests()

        assertFalse(state.acceptsResponse(request))
        assertFalse(state.acceptsLiveResponse(live))
        assertSame(place, state.stop.selected)
        assertFalse(state.busy)
    }

    @Test
    fun routeCoordinateNeverConfirmsAnOfficialBoardingIdentity() {
        val state = JourneyScreenState(FeatureMode.ROUTE)
        state.boarding = place
        state.boardingSource = BoardingPointSource.ESTIMATED
        assertFalse(state.boardingChosenByUser)
        assertNull(state.selectedStation)
        assertNull(state.selectedArrival)
        assertTrue(requireNotNull(state.boardingNotice).contains("추정"))
        state.boardingSource = BoardingPointSource.UNKNOWN
        assertTrue(requireNotNull(state.boardingNotice).contains("확인 필요"))
    }

    @Test
    fun liveUpdatesNeverSubstituteAnotherTrainOrDirection() {
        val state = JourneyScreenState(FeatureMode.STOP)
        val selected = arrival()
        state.selectedArrival = selected
        val differentDirection = identity.copy(directionId = "하행", serviceVariant = "인천행")

        val match = state.applyArrivalOptions(listOf(arrival("999"), arrival(direction = differentDirection)))

        assertNull(match)
        assertSame(selected, state.selectedArrival)
        assertTrue(state.arrivalRequestFailed)
        val refreshed = arrival(fetchedAt = 31_000L)
        assertSame(refreshed, state.applyArrivalOptions(listOf(refreshed)))
        assertSame(refreshed, state.selectedArrival)
        assertFalse(state.arrivalRequestFailed)
    }

    @Test
    fun automaticVibrationRequiresOptInAndIgnoresRepeatedEtasAndUnknownRecovery() {
        val state = JourneyScreenState(FeatureMode.STOP)
        state.selectedArrival = arrival()
        assertFalse(state.alertsEnabled)
        assertFalse(state.claimAutomaticAlert(AutoGuidanceStatus.RUN, 1_000L))
        state.alertsEnabled = true
        assertTrue(state.claimAutomaticAlert(AutoGuidanceStatus.RUN, 1_000L))
        state.selectedArrival = arrival(fetchedAt = 31_000L)
        assertFalse(state.claimAutomaticAlert(AutoGuidanceStatus.RUN, 31_000L))
        assertFalse(state.claimAutomaticAlert(AutoGuidanceStatus.UNKNOWN, 61_000L))
        assertFalse(state.claimAutomaticAlert(AutoGuidanceStatus.RUN, 91_000L))
        state.invalidateRequests() // Switching tabs or pausing must not reset consent/deduplication.
        assertFalse(state.claimAutomaticAlert(AutoGuidanceStatus.RUN, 121_000L))
        assertTrue(state.claimAutomaticAlert(AutoGuidanceStatus.NEXT, 151_000L))
    }

    @Test
    fun sharedGatePreventsDuplicateTabAlertsAndDistinguishesActualVehicles() {
        val gate = AutomaticAlertGate()
        val first = JourneyScreenState(FeatureMode.ROUTE, gate)
        val second = JourneyScreenState(FeatureMode.STOP, gate)
        first.alertsEnabled = true
        second.alertsEnabled = true
        first.selectedArrival = arrival()
        second.selectedArrival = arrival()
        assertTrue(first.claimAutomaticAlert(AutoGuidanceStatus.RUN, 1_000L))
        assertFalse(second.claimAutomaticAlert(AutoGuidanceStatus.RUN, 2_000L))
        assertFalse(second.claimAutomaticAlert(AutoGuidanceStatus.RUN, 32_000L))
        second.selectedArrival = arrival("different-vehicle")
        assertTrue(second.claimAutomaticAlert(AutoGuidanceStatus.RUN, 33_000L))
    }
}
