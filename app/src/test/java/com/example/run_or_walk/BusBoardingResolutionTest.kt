package com.example.run_or_walk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BusBoardingResolutionTest {
    private val point = MapPoint(127.0276, 37.4979)
    private val boarding = JourneyPlace("kakao-123", "강남역·역삼세무서 버스정류장", "서울 강남구", point,
        categoryName = "교통,수송 > 교통시설 > 버스정류장")
    private val stop = OfficialSeoulBusStop("122000038", "23287", "강남역.역삼세무서", point)
    private val route = OfficialSeoulBusRoute(stop.stopId, "100100124", "140", "21", "내곡IC 방향", "3")

    @Test
    fun exactNearbyOfficialStopResolvesButItsArsCodeCannotStandInForAnOfficialId() {
        assertSame(stop, resolveOfficialSeoulBusStop(listOf(stop, stop), boarding))
        assertNull(resolveOfficialSeoulBusStop(listOf(stop.copy(stopId = stop.arsId)), boarding))
        assertNull(resolveOfficialSeoulBusStop(listOf(stop), boarding.copy(categoryName = "음식점 > 카페")))
        assertNull(resolveOfficialSeoulBusStop(listOf(stop), boarding.copy(name = "강남역")))
    }

    @Test
    fun OppositeSideAndConflictingStopRecordsDoNotResolveByNearestDistance() {
        val opposite = stop.copy(stopId = "122000039", point = MapPoint(point.x + .0002, point.y))
        assertNull(resolveOfficialSeoulBusStop(listOf(stop, opposite), boarding))
        assertNull(resolveOfficialSeoulBusStop(listOf(opposite, stop), boarding))
        assertNull(resolveOfficialSeoulBusStop(listOf(stop, stop.copy(arsId = "23288")), boarding))
        assertNull(resolveOfficialSeoulBusStop(listOf(stop.copy(name = "강남역.역삼세무서(중)")), boarding))
    }

    @Test
    fun MissingInvalidAndDistantCoordinatesDoNotAutoConfirmAStop() {
        assertNull(resolveOfficialSeoulBusStop(listOf(stop.copy(point = null)), boarding))
        assertNull(resolveOfficialSeoulBusStop(listOf(stop.copy(point = MapPoint(Double.NaN, 37.5))), boarding))
        assertNull(resolveOfficialSeoulBusStop(listOf(stop.copy(point = MapPoint(127.03, 37.5))), boarding))
        assertNull(resolveOfficialSeoulBusStop(listOf(stop), boarding.copy(point = MapPoint(200.0, 37.5))))
    }

    @Test
    fun RouteLabelResolvesOnlyInsideConfirmedStopAndDoesNotMatchSubstringOrUnsupportedRoutes() {
        assertSame(route, resolveOfficialSeoulBusRoute(listOf(route, route.copy(name = "1401")), stop.stopId, " 140 "))
        assertNull(resolveOfficialSeoulBusRoute(listOf(route), "122000039", "140"))
        assertNull(resolveOfficialSeoulBusRoute(listOf(route), stop.stopId, "14"))
        assertNull(resolveOfficialSeoulBusRoute(listOf(route.copy(supported = false)), stop.stopId, "140"))
        assertNull(resolveOfficialSeoulBusRoute(listOf(route.copy(stopOrder = "0")), stop.stopId, "140"))
        assertNull(resolveOfficialSeoulBusRoute(listOf(route.copy(routeId = "140")), stop.stopId, "140"))
    }

    @Test
    fun SameBusRouteWithMultipleLoopOccurrencesOrOfficialIdsRequiresChoice() {
        assertSame(route, resolveOfficialSeoulBusRoute(listOf(route, route), stop.stopId, "140"))
        assertNull(resolveOfficialSeoulBusRoute(listOf(route, route.copy(stopOrder = "41")), stop.stopId, "140"))
        assertNull(resolveOfficialSeoulBusRoute(listOf(route, route.copy(routeId = "100100125")), stop.stopId, "140"))
        val night = route.copy(name = "N26")
        assertSame(night, resolveOfficialSeoulBusRoute(listOf(night), stop.stopId, "n26"))
    }

    @Test fun routeDirectionMustAgreeEvenWhenOnlyOneOfficialBusIsReturned() {
        assertSame(route, resolveOfficialSeoulBusRoute(listOf(route), stop.stopId, "140", "내곡IC 방면"))
        assertNull(resolveOfficialSeoulBusRoute(listOf(route), stop.stopId, "140", "서울역 방면"))
    }

    private fun busArrival(vehicle: String = "100000001") = OfficialSeoulBusArrival(
        route.identity, vehicle, route.name, route.direction,
        RealtimeArrivalSnapshot(route.identity, 601_000L, fetchedAtEpochMillis = 1_000L, validForMillis = 60_000L))

    private fun busState(): JourneyScreenState = JourneyScreenState(FeatureMode.STOP).apply {
        this.boarding = this@BusBoardingResolutionTest.boarding
        selectedBusStop = this@BusBoardingResolutionTest.stop
        busStopOptions = listOf(this@BusBoardingResolutionTest.stop)
        busStopsSearched = true
        busStopQuery = this@BusBoardingResolutionTest.stop.name
        selectedBusRoute = route
        busRouteOptions = listOf(route)
        selectedBusArrival = busArrival()
        busArrivalOptions = listOf(requireNotNull(selectedBusArrival))
        confirmedServiceIdentity = route.identity
    }

    @Test
    fun BusSelectionUsesItsOwnIdentityAndNeverFallsBackToRetainedSubwayData() {
        val state = busState()
        val oldSubway = OfficialBoardingIdentity("seoul", "1002000222", "1002", "내선", "성수행")
        state.selectedArrival = OfficialSubwayArrival(oldSubway, "내선", "2호선", "subway-1",
            RealtimeArrivalSnapshot(oldSubway, 501_000L, fetchedAtEpochMillis = 1_000L, validForMillis = 60_000L))
        assertTrue(state.isBusJourney)
        assertSame(state.selectedBusArrival?.identity, state.activeArrivalIdentity)
        assertSame(state.selectedBusArrival?.snapshot, state.activeArrivalSnapshot)
        state.selectedBusRoute = route.copy(stopOrder = "41")
        assertNull(state.activeArrivalIdentity)
        assertNull(state.activeArrivalSnapshot)
        assertNull(state.activeArrivalVehicleId)
    }

    @Test
    fun EditingBusStopInvalidatesLateResponsesAndAllOldProviderIdentities() {
        val state = busState()
        val request = state.beginRequest("도보 경로")
        val live = state.beginLiveRequest()
        state.guidanceWalkingRoute = GuidanceWalkingRoute(point, point, route.identity, 120, 1_000L, 120_000L)
        state.editBusStopQuery("다른 정류장")
        assertFalse(state.acceptsResponse(request))
        assertFalse(state.acceptsLiveResponse(live))
        assertNull(state.selectedBusStop)
        assertNull(state.selectedBusRoute)
        assertNull(state.selectedBusArrival)
        assertNull(state.confirmedServiceIdentity)
        assertNull(state.guidanceWalkingRoute)
        assertTrue(state.busStopOptions.isEmpty())
        assertTrue(state.busArrivalOptions.isEmpty())
        assertFalse(state.busStopsSearched)
    }

    @Test
    fun ChangingBusRoutePreservesStopAndCandidatesButInvalidatesVehicleAndWalkingBinding() {
        val state = busState()
        val pending = state.beginLiveRequest()
        state.clearSelectedBusRoute()
        assertFalse(state.acceptsLiveResponse(pending))
        assertSame(stop, state.selectedBusStop)
        assertTrue(state.busRouteOptions.contains(route))
        assertTrue(state.busStopOptions.contains(stop))
        assertNull(state.selectedBusRoute)
        assertNull(state.selectedBusArrival)
        assertNull(state.confirmedServiceIdentity)
        assertNull(state.activeArrivalSnapshot)
    }

    @Test
    fun DestinationEditClearsBusSelectionsAndSharedAlertGateUsesActualBusVehicle() {
        val gate = AutomaticAlertGate()
        val first = JourneyScreenState(FeatureMode.ROUTE, gate)
        val second = JourneyScreenState(FeatureMode.STOP, gate)
        listOf(first, second).forEach {
            it.boarding = boarding
            it.selectedBusStop = stop
            it.selectedBusRoute = route
            it.selectedBusArrival = busArrival()
            it.alertsEnabled = true
        }
        assertTrue(first.claimAutomaticAlert(AutoGuidanceStatus.WALK, 1_000L))
        assertFalse(second.claimAutomaticAlert(AutoGuidanceStatus.WALK, 31_000L))
        second.selectedBusArrival = busArrival("100000002")
        assertTrue(second.claimAutomaticAlert(AutoGuidanceStatus.WALK, 61_000L))
        second.busStopQuery = stop.name
        second.editQuery(SearchField.DESTINATION, "다른 목적지")
        assertNull(second.selectedBusStop)
        assertNull(second.selectedBusRoute)
        assertNull(second.activeArrivalIdentity)
        assertTrue(second.busStopQuery.isEmpty())
    }
}
