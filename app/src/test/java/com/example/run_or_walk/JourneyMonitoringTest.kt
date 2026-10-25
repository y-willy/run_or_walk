package com.example.run_or_walk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class JourneyMonitoringTest {
    private val point = MapPoint(126.98, 37.57)
    private val stop = OfficialSeoulBusStop("100000001", "01001", "종로2가", point)
    private val route = OfficialSeoulBusRoute(stop.stopId, "100100001", "100", "12", "서울역 방면", "3")
    private val busRequest = JourneyMonitoringRequest(stop.name, point, route.identity,
        busStop = stop, busRoute = route, settings = JourneySettings(false, 3.5, 180), vibrationEnabled = true)
    private val station = OfficialSubwayStation("1002000222", "1002", "강남", "2호선")
    private val subwayIdentity = OfficialBoardingIdentity("seoul", station.stopId, station.routeId, "외선", "성수행")

    @Test fun staticBusAndSubwayRequestsRoundTripWithoutGpsEtaOrCredentialPayloads() {
        listOf(busRequest, JourneyMonitoringRequest("강남역", point, subwayIdentity, subwayStation = station)).forEach {
            val json = JourneyMonitoringRequestCodec.encode(it)
            assertEquals(it, JourneyMonitoringRequestCodec.decode(json))
            listOf("KAKAO", "SEOUL_BUS_KEY", "observedAt", "fetchedAt", "arrivalSeconds", "vehicleId", "walkingRoute")
                .forEach { forbidden -> assertFalse(json.contains(forbidden)) }
        }
    }

    @Test fun oppositeSequenceProviderMixAndUnknownBoardingCannotStartMonitoring() {
        assertFalse(busRequest.copy(identity = route.identity.copy(directionId = "45")).isValid())
        assertFalse(busRequest.copy(subwayStation = station).isValid())
        assertFalse(busRequest.copy(boardingPoint = MapPoint(Double.NaN, point.y)).isValid())
        assertFalse(busRequest.copy(busRoute = route.copy(supported = false)).isValid())
        assertFalse(busRequest.copy(busRoute = route.copy(routeType = "8")).isValid())
        assertFalse(busRequest.copy(busStop = stop.copy(point = null)).isValid())
        assertFalse(busRequest.copy(identity = route.identity.copy(authority = "kakao")).isValid())
        assertNull(JourneyMonitoringRequestCodec.decode("{}"))
        assertNull(JourneyMonitoringRequestCodec.decode("not-json"))
        assertNull(JourneyMonitoringRequestCodec.decode(JSONObject(JourneyMonitoringRequestCodec.encode(busRequest))
            .put("version", 99).toString()))
    }

    @Test fun settingsChangesKeepSameConfirmedJourneyButDirectionOrStopChangesDoNot() {
        assertTrue(busRequest.isSameJourney(busRequest.copy(settings = JourneySettings(), vibrationEnabled = false)))
        assertTrue(busRequest.matches(route.identity, point))
        assertFalse(busRequest.matches(route.identity.copy(directionId = "45"), point))
        assertFalse(busRequest.isSameJourney(busRequest.copy(boardingPoint = MapPoint(127.0, point.y))))
        assertFalse(busRequest.isSameJourney(busRequest.copy(busRoute = route.copy(stopOrder = "45"))))
    }

    private val now = 1_790_000_000_000L
    private fun snapshot(after: Long = 300_000L, sourceAt: Long = now) = RealtimeArrivalSnapshot(
        route.identity, now + after, fetchedAtEpochMillis = sourceAt, validForMillis = 60_000L)
    private fun assessment(status: AutoGuidanceStatus = AutoGuidanceStatus.WALK) = AutoGuidanceAssessment(status)

    @Test fun countdownUpdatesAndUnknownRecoveryDoNotRepeatMeaningfulGuidanceAlerts() {
        val gate = JourneyMonitoringAlertGate()
        assertEquals(JourneyMonitoringAlertKind.GUIDANCE_CHANGED,
            gate.claim(route.identity, "100001001", assessment(), snapshot(), now)?.kind)
        assertNull(gate.claim(route.identity, "100001001", assessment(), snapshot(280_000), now + 20_000))
        assertNull(gate.claim(route.identity, "100001001", assessment(AutoGuidanceStatus.UNKNOWN), null, now + 30_000))
        assertNull(gate.claim(route.identity, "100001001", assessment(), snapshot(240_000, now + 40_000), now + 40_000))
        assertEquals(JourneyMonitoringAlertKind.GUIDANCE_CHANGED,
            gate.claim(route.identity, "100001002", assessment(), snapshot(300_000, now + 70_000), now + 70_000)?.kind)
    }

    @Test fun anActualVehicleGetsOneApproachAlertEvenWhenWalkingCannotBeConfirmed() {
        val gate = JourneyMonitoringAlertGate()
        val unknown = assessment(AutoGuidanceStatus.UNKNOWN)
        assertNull(gate.claim(route.identity, "100001001", unknown, snapshot(61_000), now))
        val first = gate.claim(route.identity, "100001001", unknown, snapshot(60_000), now)
        assertEquals(JourneyMonitoringAlertKind.ARRIVING, first?.kind)
        assertEquals(AutoGuidanceStatus.UNKNOWN, first?.status)
        assertNull(gate.claim(route.identity, "100001001", unknown, snapshot(40_000), now + 20_000))
        assertEquals(JourneyMonitoringAlertKind.ARRIVING,
            gate.claim(route.identity, "100001002", unknown, snapshot(40_000), now + 20_000)?.kind)
    }

    @Test fun staleFutureWrongIdentityAndExpiredVehiclesNeverTriggerApproachOrCatchClaims() {
        val invalid = listOf(snapshot(30_000).copy(reliable = false),
            snapshot(30_000).copy(trustState = ArrivalTrustState.UNKNOWN),
            snapshot(30_000, now - 60_001), snapshot(30_000, now + 1), snapshot(-1),
            snapshot(30_000).copy(identity = route.identity.copy(directionId = "45")))
        invalid.forEach { value ->
            assertNull(JourneyMonitoringAlertGate().claim(route.identity, "100001001", assessment(), value, now))
        }
        assertNull(JourneyMonitoringAlertGate().claim(route.identity, "", assessment(), snapshot(30_000), now))
    }

    @Test fun applyingPersonalSettingsReusesDistanceWithoutRefreshingOldGpsOrArrivalTimes() {
        val original = busRequest.copy(settings = JourneySettings())
        val walking = GuidanceWalkingRoute(point, point, route.identity, 200, now, 120_000L)
        val state = JourneyMonitoringState(true, original, assessment(), GuidanceLocation(point, 10.0, now),
            walking, snapshot(500_000), "걸어가세요", now, walkingDistanceMeters = 300,
            providerWalkingSeconds = 200)
        val changed = original.copy(settings = JourneySettings(false, 3.0, 180))
        val updated = monitoringStateWithSettings(state, changed, now + 1_000, true)
        assertEquals(360, updated.walkingRoute?.durationSeconds)
        assertEquals(180, updated.assessment?.bufferSeconds)
        assertEquals(now, updated.lastUpdatedEpochMillis)
        assertEquals(now, updated.walkingRoute?.fetchedAtEpochMillis)
        assertEquals(now, updated.arrivals?.fetchedAtEpochMillis)
        assertEquals(AutoGuidanceStatus.UNKNOWN,
            monitoringStateWithSettings(updated, changed, now + 61_000, true).assessment?.status)
        assertEquals(AutoGuidanceStatus.UNKNOWN,
            monitoringStateWithSettings(updated, changed, now + 1_000, false).assessment?.status)
    }

    @Test fun arrivalAlertsExpireAtTheirActualVehicleTimeOrSourceExpiryWhicheverComesFirst() {
        val arriving = JourneyMonitoringAlert(JourneyMonitoringAlertKind.ARRIVING, AutoGuidanceStatus.UNKNOWN)
        assertEquals(10_000L, monitoringAlertLifetimeMillis(arriving, snapshot(10_000), now))
        assertEquals(5_000L, monitoringAlertLifetimeMillis(arriving, snapshot(30_000, now - 55_000), now))
        val next = JourneyMonitoringAlert(JourneyMonitoringAlertKind.GUIDANCE_CHANGED, AutoGuidanceStatus.NEXT_TRAIN)
        assertEquals(50_000L, monitoringAlertLifetimeMillis(next,
            snapshot(10_000, now - 10_000).copy(nextArrivalAtEpochMillis = now + 70_000), now))
        assertEquals(0L, monitoringAlertLifetimeMillis(arriving, snapshot(-1), now))
    }
}
