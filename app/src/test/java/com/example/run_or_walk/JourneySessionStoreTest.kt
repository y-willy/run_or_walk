package com.example.run_or_walk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class JourneySessionStoreTest {
    private val subwayPoint = MapPoint(127.0276, 37.4979)
    private val subwayPlace = JourneyPlace("kakao-subway", "강남역 2호선", "서울 강남구", subwayPoint, "SW8")
    private val destination = JourneyPlace("kakao-destination", "서울시청", "서울 중구", MapPoint(126.978, 37.566))
    private val subwayStation = OfficialSubwayStation("1002000222", "1002", "강남", "2호선")
    private val subwayIdentity = OfficialBoardingIdentity("seoul", subwayStation.stopId,
        subwayStation.routeId, "내선", "실제 확인한 운행 종류·종착·방면")
    private val busPoint = MapPoint(126.98, 37.57)
    private val busPlace = JourneyPlace("seoul-bus-100000001", "종로2가", "정류장 번호 01001", busPoint,
        categoryName = "버스정류장")
    private val busStop = OfficialSeoulBusStop("100000001", "01001", "종로2가", busPoint)
    private val busRoute = OfficialSeoulBusRoute(busStop.stopId, "100100001", "100", "12", "서울역 방면", "3")

    private fun selectedScreens(): Map<FeatureMode, JourneyScreenState> = restoreJourneyScreens(null).apply {
        getValue(FeatureMode.ROUTE).apply {
            departure.query = "서울역"
            departure.selected = subwayPlace
            destination.query = this@JourneySessionStoreTest.destination.name
            destination.selected = this@JourneySessionStoreTest.destination
            selectedRoute = JourneyTransitRoute("kakao-2", KakaoTransitChoice(1_200, 1,
                "2호선", "강남", subwayPoint, BoardingPointSource.ESTIMATED, "SUBWAY", 300,
                boardingStopId = "kakao-provider-id", transitLineId = "provider-route"))
            boarding = subwayPlace
            boardingChosenByUser = true
            boardingSource = BoardingPointSource.VERIFIED
            selectedStation = subwayStation
            confirmedServiceIdentity = subwayIdentity
            alertsEnabled = true
            detailsExpanded = true
            stationQuery = "강남"
        }
        getValue(FeatureMode.STOP).apply {
            stop.query = busPlace.name
            stop.selected = busPlace
            boarding = busPlace
            boardingMatchedAutomatically = true
            boardingSource = BoardingPointSource.VERIFIED
            busStopQuery = "종로2가"
            selectedBusStop = busStop
            selectedBusRoute = busRoute
            confirmedServiceIdentity = busRoute.identity
        }
    }

    private fun roundTrip(screens: Map<FeatureMode, JourneyScreenState> = selectedScreens()) =
        decodeJourneySession(encodeJourneySession(captureJourneySession(FeatureMode.STOP, screens)))!!

    @Test fun restartRestoresChosenItineraryPlaceStationDirectionAndBusOccurrence() {
        val snapshot = roundTrip()
        val restored = restoreJourneyScreens(snapshot)
        assertEquals(FeatureMode.STOP, snapshot.mode)
        val route = restored.getValue(FeatureMode.ROUTE)
        assertEquals(subwayPlace, route.departure.selected)
        assertEquals("서울역", route.departure.query)
        assertEquals(destination, route.destination.selected)
        assertEquals(destination.name, route.destination.query)
        assertEquals("kakao-2", route.selectedRoute?.id)
        assertEquals(listOf(route.selectedRoute), route.routes)
        assertEquals(subwayPlace, route.boarding)
        assertEquals(subwayStation, route.selectedStation)
        assertEquals(subwayIdentity, route.confirmedServiceIdentity)
        assertTrue(route.boardingConfirmed)
        assertTrue(route.alertsEnabled)
        assertTrue(route.detailsExpanded)
        val stop = restored.getValue(FeatureMode.STOP)
        assertEquals(busPlace, stop.stop.selected)
        assertEquals(busStop, stop.selectedBusStop)
        assertEquals("01001", stop.selectedBusStop?.arsId)
        assertEquals(busRoute, stop.selectedBusRoute)
        assertEquals(busRoute.identity, stop.confirmedServiceIdentity)
        assertTrue(stop.boardingMatchedAutomatically)
        assertFalse(stop.alertsEnabled)
    }

    @Test fun realtimeGpsWalkingFailuresAndRequestsNeverSurviveRestartOrEnterTheSavedPayload() {
        val screens = selectedScreens()
        val state = screens.getValue(FeatureMode.ROUTE)
        state.location = GuidanceLocation(subwayPoint, 5.0, 1_000L)
        state.guidanceWalkingRoute = GuidanceWalkingRoute(subwayPoint, subwayPoint, subwayIdentity, 100, 1_000L, 120_000L)
        state.walkRoute = WalkRoute(200, 100)
        state.walkingPoints = listOf(subwayPoint, busPoint)
        state.selectedArrival = OfficialSubwayArrival(subwayIdentity, "내선", "성수행", "live-vehicle-123",
            RealtimeArrivalSnapshot(subwayIdentity, 999_000L, fetchedAtEpochMillis = 1_000L, validForMillis = 60_000L))
        state.arrivalOptions = listOf(requireNotNull(state.selectedArrival))
        state.beginRequest("위치 확인")
        state.beginLiveRequest()
        state.liveRetryAtEpochMillis = 1_000_000L
        state.error = "temporary network error"
        val encoded = encodeJourneySession(captureJourneySession(FeatureMode.ROUTE, screens))
        listOf("live-vehicle-123", "temporary network error", "fetchedAt", "arrivalOptions",
            "walkRoute", "walkingPoints", "location", "liveRetryAt").forEach { assertFalse(encoded.contains(it)) }
        val restored = restoreJourneyScreens(decodeJourneySession(encoded)).getValue(FeatureMode.ROUTE)
        assertNull(restored.location)
        assertNull(restored.walkRoute)
        assertNull(restored.guidanceWalkingRoute)
        assertNull(restored.selectedArrival)
        assertTrue(restored.arrivalOptions.isEmpty())
        assertTrue(restored.walkingPoints.isEmpty())
        assertNull(restored.liveRetryAtEpochMillis)
        assertNull(restored.error)
        assertFalse(restored.busy)
        assertFalse(restored.liveLoading)
    }

    @Test fun unknownSchemaCorruptJsonExcessiveInputAndDuplicateModesDoNotRestore() {
        assertNull(decodeJourneySession(null))
        assertNull(decodeJourneySession("not-json"))
        assertNull(decodeJourneySession("x".repeat(40_001)))
        val original = encodeJourneySession(captureJourneySession(FeatureMode.ROUTE, selectedScreens()))
        assertNull(decodeJourneySession(JSONObject(original).put("version", 2).toString()))
        val duplicate = JSONObject(original)
        duplicate.getJSONArray("screens").put(1, duplicate.getJSONArray("screens").getJSONObject(0))
        assertNull(decodeJourneySession(duplicate.toString()))
    }

    @Test fun malformedCoordinatesAndProviderIdsCannotRestoreConfirmedBoarding() {
        val original = encodeJourneySession(captureJourneySession(FeatureMode.ROUTE, selectedScreens()))
        val malformed = JSONObject(original)
        val row = malformed.getJSONArray("screens").getJSONObject(0)
        row.getJSONObject("boarding").getJSONObject("point").put("x", 181.0)
        val restored = restoreJourneyScreens(decodeJourneySession(malformed.toString())).getValue(FeatureMode.ROUTE)
        assertNull(restored.boarding)
        assertFalse(restored.boardingConfirmed)
        assertNull(restored.selectedStation)
        assertNull(restored.confirmedServiceIdentity)
        val badBusId = JSONObject(original)
        badBusId.getJSONArray("screens").getJSONObject(1).getJSONObject("busStop").put("stopId", "01001")
        val bus = restoreJourneyScreens(decodeJourneySession(badBusId.toString())).getValue(FeatureMode.STOP)
        assertNull(bus.selectedBusStop)
        assertNull(bus.selectedBusRoute)
        assertNull(bus.confirmedServiceIdentity)
    }

    @Test fun changedStopOrderOrWrongPhysicalStopCannotKeepAnOldBusBinding() {
        val original = encodeJourneySession(captureJourneySession(FeatureMode.STOP, selectedScreens()))
        val wrongOrder = JSONObject(original)
        wrongOrder.getJSONArray("screens").getJSONObject(1).getJSONObject("busRoute").put("stopOrder", "45")
        val changed = restoreJourneyScreens(decodeJourneySession(wrongOrder.toString())).getValue(FeatureMode.STOP)
        assertEquals("45", changed.selectedBusRoute?.stopOrder)
        assertNull(changed.confirmedServiceIdentity)
        val wrongPoint = JSONObject(original)
        wrongPoint.getJSONArray("screens").getJSONObject(1).getJSONObject("busStop")
            .getJSONObject("point").put("x", 127.5)
        val moved = restoreJourneyScreens(decodeJourneySession(wrongPoint.toString())).getValue(FeatureMode.STOP)
        assertNull(moved.selectedBusStop)
        assertNull(moved.selectedBusRoute)
        assertNull(moved.confirmedServiceIdentity)
    }

    @Test fun aRestoredConsentDoesNotRestoreAVehicleOrAuthorizeAnAutomaticAlert() {
        val restored = restoreJourneyScreens(roundTrip()).getValue(FeatureMode.ROUTE)
        assertTrue(restored.alertsEnabled)
        assertNull(restored.activeArrivalIdentity)
        assertFalse(restored.claimAutomaticAlert(AutoGuidanceStatus.WALK, 1_000L))
        val result = calculateAutoGuidance(AutoGuidanceInput(restored.location, null,
            restored.guidanceWalkingRoute, restored.activeArrivalSnapshot, locationPermissionGranted = true), 1_000L)
        assertEquals(AutoGuidanceStatus.UNKNOWN, result.status)
    }

    @Test fun immutableSnapshotCannotObserveLaterChangesToTheMutableScreen() {
        val screens = selectedScreens()
        val snapshot = captureJourneySession(FeatureMode.ROUTE, screens)
        screens.getValue(FeatureMode.ROUTE).editQuery(SearchField.DESTINATION, "다른 장소")
        val restored = restoreJourneyScreens(decodeJourneySession(encodeJourneySession(snapshot)))
            .getValue(FeatureMode.ROUTE)
        assertEquals(destination, restored.destination.selected)
        assertEquals(subwayIdentity, restored.confirmedServiceIdentity)
        assertEquals("kakao-2", restored.selectedRoute?.id)
    }

    @Test fun restoresSubwayNextStopEvidenceAndGbisSelectionWithoutRealtimeData() {
        val screens = selectedScreens()
        val state = screens.getValue(FeatureMode.ROUTE)
        val next = state.selectedRoute!!.copy(kakao = state.selectedRoute!!.kakao.copy(nextStopName = "역삼"))
        state.selectedRoute = next
        assertEquals("역삼", restoreJourneyScreens(roundTrip(screens)).getValue(FeatureMode.ROUTE).selectedRoute?.kakao?.nextStopName)

        val match = GyeonggiOfficialMatch("200000177", "200000037", "19", "5100", "강남", MapPoint(127.05, 37.25))
        state.clearRoutesAndBoarding()
        state.selectedRoute = JourneyTransitRoute("gbis-trip", KakaoTransitChoice(900, 0, "5100", "영통역",
            match.point, BoardingPointSource.ESTIMATED, "BUS", directionName = "강남행"))
        state.boarding = JourneyPlace("route-bus", "영통역", "공식 정류장", match.point, categoryName = "버스정류장")
        state.boardingMatchedAutomatically = true
        state.boardingSource = BoardingPointSource.VERIFIED
        state.selectedGyeonggiMatch = match
        state.confirmedServiceIdentity = match.identity
        val snapshot = roundTrip(screens)
        val restored = restoreJourneyScreens(snapshot).getValue(FeatureMode.ROUTE)
        assertEquals(match, restored.selectedGyeonggiMatch)
        assertEquals(match.identity, restored.confirmedServiceIdentity)
        assertEquals(match.stationId, restored.gyeonggiStationId)
        assertTrue(restored.gyeonggiArrivals.isEmpty())
        assertNull(restored.activeArrivalSnapshot)
        val broken = snapshot.copy(screens = snapshot.screens.map {
            if (it.mode == FeatureMode.ROUTE) it.copy(gyeonggiMatch = match.copy(staOrder = "20")) else it
        })
        assertNull(restoreJourneyScreens(broken).getValue(FeatureMode.ROUTE).confirmedServiceIdentity)
    }
}
