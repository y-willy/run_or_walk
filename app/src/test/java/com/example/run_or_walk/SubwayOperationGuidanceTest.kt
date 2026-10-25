package com.example.run_or_walk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class SubwayOperationGuidanceTest {
    private val now = 1_790_000_000_000L
    private val station = OfficialSubwayStation("1075075241", "1075", "망포", "수인분당선")
    private val identity = OfficialBoardingIdentity("seoul", station.stopId, station.routeId, "하행", "고색행")
    private val point = MapPoint(127.0568, 37.2459)
    private fun row(source: Long = now - 5_000L, message: String = "[3]번째 전역 (상갈)") =
        OfficialSubwayArrival(identity, "하행 · 고색행 - 매탄권선방면", "고색행", "6175",
            RealtimeArrivalSnapshot(identity, source, fetchedAtEpochMillis = source, validForMillis = 60_000,
                reliable = false, trustState = ArrivalTrustState.UNKNOWN),
            gatewayFetchedAtEpochMillis = now, officialArrivalMessage = message,
            sourceReceivedAtEpochMillis = source, gatewayContract = SubwayGatewayContract.RAW_FIELDS,
            issues = setOf(SubwayArrivalIssue.ETA_UNAVAILABLE), sourceEtaSeconds = 0)

    private fun guidance(rows: List<OfficialSubwayArrival> = listOf(row()), at: Long = now,
        failed: Boolean = false) = subwayOperationGuidance(station, null, null, rows, at, failed)

    @Test fun aZeroEtaOfficialPositionBecomesPrimaryGuidanceWithoutInventingTime() {
        val source = row()
        val result = guidance()!!
        assertEquals("열차 위치 안내 중", result.title)
        assertTrue(result.message.contains("[3]번째 전역 (상갈)"))
        assertEquals(source.officialArrivalMessage, result.arrivalInformation)
        assertTrue(result.hasCurrentObservation)
        assertEquals(source.sourceReceivedAtEpochMillis, result.sourceAtEpochMillis)
        assertFalse(result.message.contains("자동 판단 제외"))
        assertEquals(0L, source.sourceEtaSeconds)
        assertFalse(source.snapshot.reliable)
        assertNull(chooseMatchingSubwayArrival(identity, listOf(source), now))
    }

    @Test fun lateMessagesAreUsedAsDatedPreviousPositionsAndNeverFreshened() {
        val old = row(now - 108_000L)
        val result = guidance(listOf(old))!!
        assertEquals("마지막 수신 열차 위치", result.title)
        assertTrue(result.message.contains("원본 생성 108초 전"))
        assertTrue(result.message.contains("마지막 수신 위치"))
        assertFalse(result.hasCurrentObservation)
        assertEquals(now - 108_000L, result.sourceAtEpochMillis)
        assertFalse(guidance(listOf(old), now + 30_000L)!!.hasCurrentObservation)
    }

    @Test fun actualMangpoWorkerResponseIsReflectedInBothDirectionMessagesDespiteAllZeroEta() {
        val payload = javaClass.classLoader!!.getResource("mangpo_operation_response_20261025.json")!!.readText()
        val fetched = Instant.parse(JSONObject(payload).getString("fetchedAt")).toEpochMilli()
        val arrivals = parseWorkerSubwayArrivals(payload, station, fetched)
        assertEquals(4, arrivals.size)
        assertTrue(arrivals.all { it.sourceEtaSeconds == 0L && !it.snapshot.reliable })
        val result = guidance(arrivals, fetched)!!
        assertTrue(result.message.contains("[5]번째 전역 (고색)"))
        assertTrue(result.message.contains("[3]번째 전역 (상갈)"))
        assertTrue(result.message.contains("상행"))
        assertTrue(result.message.contains("하행"))
        assertTrue(result.message.contains("망포 도착"))
        assertFalse(result.hasCurrentObservation)
        assertNull(sourceSubwayEstimate(arrivals.first(), fetched))
    }

    @Test fun wrongStationLineDirectionAndConfirmedServiceAreStillExcluded() {
        val source = row()
        listOf(identity.copy(stopId = "1075075240"), identity.copy(routeId = "1001"),
            identity.copy(authority = "other"), identity.copy(directionId = "unknown")).forEach {
            assertNull(guidance(listOf(source.copy(identity = it, snapshot = source.snapshot.copy(identity = it)))))
        }
        assertNull(guidance(listOf(source.copy(snapshot = source.snapshot.copy(identity = identity.copy(directionId = "상행"))))))
        assertNull(subwayOperationGuidance(station, null, identity.copy(directionId = "상행"), listOf(source), now))
        val route = KakaoTransitChoice(900, 0, "수인분당선", "망포역", point,
            firstTransitType = "SUBWAY", nextStopName = "매탄권선")
        assertNotNull(subwayOperationGuidance(station, route, null, listOf(source), now))
        assertNull(subwayOperationGuidance(station, route.copy(nextStopName = "영통"), null, listOf(source), now))
        assertNull(subwayOperationGuidance(station, route.copy(firstTransitType = "BUS"), null, listOf(source), now))
    }

    @Test fun missingOptionalTrainMetadataDoesNotDiscardTheOfficialPositionMessage() {
        val source = row().copy(trainId = "", trainLabel = "", issues = setOf(
            SubwayArrivalIssue.ETA_UNAVAILABLE, SubwayArrivalIssue.MISSING_SERVICE_IDENTITY))
        assertNotNull(guidance(listOf(source)))
        assertNull(chooseMatchingSubwayArrival(identity, listOf(source), now))
    }

    @Test fun futureClocksUnknownSourcesAndFailedRefreshesCannotMasqueradeAsSuccessfulPositionGuidance() {
        listOf(row().copy(sourceReceivedAtEpochMillis = now + 1),
            row().copy(gatewayFetchedAtEpochMillis = now + 1),
            row().copy(sourceReceivedAtEpochMillis = null),
            row().copy(gatewayContract = SubwayGatewayContract.LEGACY_ALIASES),
            row(message = ""), row(message = "[3]번째\n전역")).forEach { assertNull(guidance(listOf(it))) }
        assertNull(guidance(failed = true))
        assertNull(subwayOperationGuidance(null, null, null, listOf(row()), now))
    }

    @Test fun aPositionCountOrArrivedMessageNeverAuthorizesAWalkCatchOrApproachAlert() {
        listOf("[1]번째 전역 (영통)", "망포 도착", "망포 진입", "망포 출발").forEach { message ->
            val source = row(message = message)
            assertNotNull(guidance(listOf(source)))
            val assessment = calculateAutoGuidance(AutoGuidanceInput(GuidanceLocation(point, 10.0, now),
                GuidanceBoarding(identity, point, true),
                GuidanceWalkingRoute(point, point, identity, 0, now, 120_000, 0), source.snapshot, true), now)
            assertEquals(AutoGuidanceStatus.UNKNOWN, assessment.status)
            assertNull(assessment.firstArrivalAtEpochMillis)
            assertNull(JourneyMonitoringAlertGate().claim(identity, source.trainId, assessment, source.snapshot, now))
        }
    }

    @Test fun destinationChangesClearThePositionContextBeforeLateArrivalsCanBeShown() {
        val screen = JourneyScreenState(FeatureMode.ROUTE)
        screen.selectedStation = station
        screen.arrivalOptions = listOf(row())
        val generation = screen.beginLiveRequest()
        assertNotNull(subwayOperationGuidance(screen.selectedStation, null, null, screen.arrivalOptions, now))
        screen.editQuery(SearchField.DESTINATION, "다른 목적지")
        assertFalse(screen.acceptsLiveResponse(generation))
        assertNull(subwayOperationGuidance(screen.selectedStation, null, null, screen.arrivalOptions, now))
    }

    @Test fun monitoringUsesTheSameReceivedPositionAndSettingsChangesPreserveOriginalSourceAge() {
        val request = JourneyMonitoringRequest("망포역", point, identity, subwayStation = station)
        val source = row(now - 108_000L)
        val state = JourneyMonitoringState(running = true, request = request,
            assessment = AutoGuidanceAssessment(AutoGuidanceStatus.UNKNOWN), lastUpdatedEpochMillis = now,
            subwayObservations = listOf(source))
        val result = monitoringStateWithSettings(state, request.copy(settings = JourneySettings(false, 3.0, 180)), now + 5_000, true)
        assertTrue(result.message.contains("[3]번째 전역 (상갈)"))
        assertTrue(result.message.contains("원본 생성 113초 전"))
        assertEquals(listOf(source), result.subwayObservations)
        assertEquals(now, result.lastUpdatedEpochMillis)
        assertNull(result.arrivals)
        assertEquals(AutoGuidanceStatus.UNKNOWN, result.assessment?.status)
    }
}
