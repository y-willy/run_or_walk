package com.example.run_or_walk

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class OfficialArrivalDisplayTest {
    private val now = 1_790_000_000_000L
    private val identity = OfficialBoardingIdentity("seoul", "1075075240", "1075", "하행", "인천행 - 망포방면")
    private fun arrival(seconds: Long = 180) = OfficialSubwayArrival(identity, "하행 · 인천행 - 망포방면",
        "인천행", "6567", RealtimeArrivalSnapshot(identity, now + seconds * 1_000,
            fetchedAtEpochMillis = now, validForMillis = 60_000, reliable = false,
            trustState = ArrivalTrustState.UNKNOWN), gatewayFetchedAtEpochMillis = now,
        sourceReceivedAtEpochMillis = now, gatewayContract = SubwayGatewayContract.RAW_FIELDS,
        issues = setOf(SubwayArrivalIssue.MISSING_SERVICE_IDENTITY), sourceEtaSeconds = seconds)

    @Test fun receivedEtaIsVisibleEvenWhenOptionalMetadataCannotAuthorizeCatchGuidance() {
        val value = arrival()
        assertEquals("원본 도착예정 약 3분 0초", sourceSubwayEstimate(value, now))
        assertEquals("원본 도착예정 약 2분 30초", sourceSubwayEstimate(value, now + 30_000))
        assertFalse(value.snapshot.reliable)
        val route = KakaoTransitChoice(900, 0, "수인분당선", "영통역", MapPoint(127.05, 37.25),
            firstTransitType = "SUBWAY", nextStopName = "망포")
        assertEquals(listOf(value), subwayRouteArrivalRows(route, listOf(value)))
        assertNull(resolveAutomaticSubwayIdentity(route, listOf(value), now))
        assertTrue(subwayRouteArrivalRows(route.copy(nextStopName = "청명"), listOf(value)).isEmpty())
    }
    @Test fun zeroOldFutureAndDepartedSourceNeverBecomeNewPositiveCountdowns() {
        assertNull(sourceSubwayEstimate(arrival(0), now))
        assertNull(sourceSubwayEstimate(arrival(), now + 60_001))
        assertNull(sourceSubwayEstimate(arrival(), now - 1))
        assertNull(sourceSubwayEstimate(arrival().copy(issues = setOf(SubwayArrivalIssue.ARRIVAL_STATE_UNCERTAIN)), now))
        assertNull(sourceSubwayEstimate(arrival().copy(gatewayContract = SubwayGatewayContract.LEGACY_ALIASES), now))
    }
    @Test fun actualWorkerParserPreservesSourceEtaWhenTrainTypeMetadataIsMissing() {
        val kst = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Seoul"))
            .format(Instant.ofEpochMilli(now))
        val payload = """{"station":"영통","fetchedAt":"${Instant.ofEpochMilli(now)}","arrivals":[{
            "statnId":"1075075240","subwayId":"1075","statnNm":"영통","updnLine":"하행",
            "trainLineNm":"인천행 - 망포방면","btrainNo":"6567","bstatnId":"1075075271",
            "bstatnNm":"인천","statnTid":"1075075241","barvlDt":"180","arvlCd":"99",
            "recptnDt":"$kst","arvlMsg2":"3분 후","trustState":"UNKNOWN"}]}"""
        val row = parseWorkerSubwayArrivals(payload,
            OfficialSubwayStation(identity.stopId, identity.routeId, "영통", "수인분당선"), now).single()
        assertEquals(180L, row.sourceEtaSeconds)
        assertTrue(SubwayArrivalIssue.MISSING_SERVICE_IDENTITY in row.issues)
        assertFalse(row.snapshot.reliable)
        assertEquals("원본 도착예정 약 3분 0초", sourceSubwayEstimate(row, now))
    }
    @Test fun capacityUnknownDoesNotHideARealBusEtaOrAuthorizeAutomaticBoarding() {
        val busIdentity = identity.copy(authority = "seoul-bus")
        val value = OfficialSeoulBusArrival(busIdentity, "100001001", "9401", "서울역",
            RealtimeArrivalSnapshot(busIdentity, now + 180_000, fetchedAtEpochMillis = now,
                validForMillis = 60_000, reliable = false, trustState = ArrivalTrustState.UNKNOWN),
            gatewayFetchedAtEpochMillis = now, issues = setOf("CAPACITY_UNKNOWN"))
        assertEquals("원본 도착예정 약 3분 0초", sourceBusEstimate(value, now))
        assertFalse(value.isUsableBusArrival(now))
        assertNull(sourceBusEstimate(value, now + 60_001))
    }
}
