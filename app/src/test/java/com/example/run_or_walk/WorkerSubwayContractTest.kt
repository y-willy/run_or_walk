package com.example.run_or_walk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.OffsetDateTime

class WorkerSubwayContractTest {
    private val station = OfficialSubwayStation("1004000426", "1004", "서울", "4호선")
    private val source = OffsetDateTime.parse("2026-10-25T06:09:50+09:00").toInstant().toEpochMilli()
    private fun resource(name: String) = javaClass.getResourceAsStream("/$name")!!.bufferedReader().use { it.readText() }
    private fun row(): JSONObject = JSONObject(resource("seoul_public_sample_arrivals_20261024.json"))
        .getJSONArray("realtimeArrivalList").getJSONObject(3)
    private fun body(vararg rows: JSONObject, fetched: Long = source + 5_000): String = JSONObject()
        .put("station", "서울").put("fetchedAt", Instant.ofEpochMilli(fetched).toString())
        .put("realtime", true).put("arrivals", JSONArray(rows.toList())).toString()

    @Test fun providedGangnamPayloadKeepsLinesSeparateAndDoesNotInventTrainIds() {
        val payload = resource("worker_current_gangnam_response.json")
        val now = Instant.parse("2026-10-24T21:47:51.056Z").toEpochMilli()
        val line2 = OfficialSubwayStation("1002000222", "1002", "강남", "2호선")
        val result = parseWorkerSubwayArrivals(payload, line2, now)
        assertEquals(4, result.size)
        assertEquals(setOf("내선", "외선"), result.map { it.identity.directionId }.toSet())
        assertTrue(result.all { it.trainId.isEmpty() && !it.snapshot.reliable })
        assertTrue(result.all { it.snapshot.trustState == ArrivalTrustState.UNKNOWN })
        assertTrue(result.all { it.snapshot.nextArrivalAtEpochMillis == null })
    }

    @Test fun providedFutureClockMismatchStaysQuarantinedEvenAfterDeviceCatchesUp() {
        val payload = resource("worker_current_gangnam_response.json")
        val fetched = Instant.parse("2026-10-24T21:47:51.056Z").toEpochMilli()
        val shin = OfficialSubwayStation("1077000687", "1077", "강남", "신분당선")
        val result = parseWorkerSubwayArrivals(payload, shin, fetched)
        assertEquals(3, result.size)
        assertEquals(266_944L, result.first { it.identity.directionId == "상행" }
            .snapshot.fetchedAtEpochMillis - fetched)
        assertTrue(result.all { it.snapshot.trustState == ArrivalTrustState.FUTURE_TIMESTAMP })
        assertTrue(parseWorkerSubwayArrivals(payload, shin, fetched + 600_000)
            .all { it.snapshot.trustState == ArrivalTrustState.FUTURE_TIMESTAMP })
    }

    @Test fun validOriginalFieldsPreserveAbsoluteEtaAndElapsedSeconds() {
        val result = parseWorkerSubwayArrivals(body(row()), station, source + 15_000).single()
        assertEquals(ArrivalTrustState.VALID, result.snapshot.trustState)
        assertTrue(result.snapshot.reliable)
        assertEquals(source, result.snapshot.fetchedAtEpochMillis)
        assertEquals(source + 5_000, result.gatewayFetchedAtEpochMillis)
        assertEquals(315_000L, result.snapshot.firstArrivalAtEpochMillis - (source + 15_000))
    }

    @Test fun zeroWithPreviousStationMessageIsUnknownNotArrivalNow() {
        val value = row().put("barvlDt", "0").put("arvlMsg2", "[2]번째 전역 (양재시민의숲)")
        val result = parseWorkerSubwayArrivals(body(value), station, source + 5_000).single()
        assertEquals(ArrivalTrustState.UNKNOWN, result.snapshot.trustState)
        assertFalse(result.snapshot.reliable)
    }

    @Test fun malformedEtaNumbersCannotBecomeValidCountdowns() {
        for (invalid in listOf("-1", "NaN", "Infinity", "1.5", "86401")) {
            val result = parseWorkerSubwayArrivals(body(row().put("barvlDt", invalid)), station, source + 5_000).single()
            assertEquals(invalid, ArrivalTrustState.INVALID_ETA, result.snapshot.trustState)
            assertFalse(result.snapshot.reliable)
        }
    }

    @Test fun staleFutureAndDepartedRowsOverrideSuccessfulTransportAndServerValid() {
        assertEquals(ArrivalTrustState.STALE, parseWorkerSubwayArrivals(
            body(row().put("trustState", "VALID")), station, source + 60_001).single().snapshot.trustState)
        val future = row().put("recptnDt", "2026-10-25 06:14:37")
        assertEquals(ArrivalTrustState.FUTURE_TIMESTAMP,
            parseWorkerSubwayArrivals(body(future), station, source + 5_000).single().snapshot.trustState)
        for (code in listOf("0", "1", "2")) {
            assertFalse(parseWorkerSubwayArrivals(body(row().put("arvlCd", code)), station, source + 5_000)
                .single().snapshot.reliable)
        }
    }

    @Test fun unknownDirectionAndMissingServiceFieldsCannotAuthorizeMatching() {
        for (field in listOf("btrainNo", "bstatnId", "bstatnNm", "btrainSttus", "statnTid")) {
            val value = row().apply { remove(field) }
            assertEquals(ArrivalTrustState.UNKNOWN,
                parseWorkerSubwayArrivals(body(value), station, source + 5_000).single().snapshot.trustState)
        }
        assertEquals(ArrivalTrustState.UNKNOWN, parseWorkerSubwayArrivals(
            body(row().put("updnLine", "알 수 없음")), station, source + 5_000).single().snapshot.trustState)
    }

    @Test fun workerMalformedJsonErrorsAndMissingFetchedAtAreNotDataSuccesses() {
        for (bad in listOf("{}", "not json", """{"error":{"code":"UPSTREAM_BUDGET_EXHAUSTED"}}""",
                """{"arrivals":[],"fetchedAt":"invalid"}""")) {
            assertThrows(SeoulSubwayApiException::class.java) { parseWorkerSubwayArrivals(bad, station, source) }
        }
    }

    @Test fun globalRetryAfterCannotBeResetByStationTabOrShorterResponse() {
        val gate = WorkerRequestBackoff()
        val limit = SeoulSubwayApiException("limited", SeoulSubwayFailure.REQUEST_LIMIT, 20_000)
        gate.record(limit)
        gate.record(SeoulSubwayApiException("shorter", SeoulSubwayFailure.REQUEST_LIMIT, 5_000))
        assertSame(limit, assertThrows(SeoulSubwayApiException::class.java) { gate.check(2_000) })
        assertSame(limit, assertThrows(SeoulSubwayApiException::class.java) { gate.check(19_999) })
        gate.check(20_000)
        gate.check(20_001)
    }

    @Test fun legacyGatewayIsDiagnosedSeparatelyFromNetworkAndOldSourceData() {
        val payload = resource("worker_current_gangnam_response.json")
        val fetched = Instant.parse("2026-10-24T21:47:51.056Z").toEpochMilli()
        val line2 = OfficialSubwayStation("1002000222", "1002", "강남", "2호선")
        val freshRows = parseWorkerSubwayArrivals(payload, line2, fetched)
        assertTrue(freshRows.all { it.gatewayContract == SubwayGatewayContract.LEGACY_ALIASES })
        assertTrue(freshRows.all { SubwayArrivalIssue.LEGACY_GATEWAY_FIELDS in it.issues })
        assertTrue(freshRows.any { it.officialArrivalMessage == "전역 도착" })
        assertTrue(subwayArrivalDiagnosticSummary(freshRows, fetched)!!.contains("Worker 업데이트"))
        assertFalse(subwayArrivalDiagnosticSummary(freshRows, fetched)!!.contains("네트워크"))
        // Even when the original ages, the missing server contract remains actionable.
        val oldRows = parseWorkerSubwayArrivals(payload, line2, fetched + 600_000)
        assertTrue(oldRows.all { SubwayArrivalIssue.SOURCE_STALE in it.issues })
        assertTrue(subwayArrivalDisplayStatus(oldRows.first(), fetched + 600_000).contains("중계 서버 업데이트"))
        assertTrue(oldRows.all { !it.snapshot.reliable })
    }

    @Test fun originalSourceStalenessAndExpiredCountdownHaveDistinctDiagnoses() {
        val old = parseWorkerSubwayArrivals(body(row(), fetched = source + 61_000), station, source + 61_000).single()
        assertEquals(SubwayGatewayContract.RAW_FIELDS, old.gatewayContract)
        assertTrue(SubwayArrivalIssue.SOURCE_STALE in old.issues)
        assertTrue(subwayArrivalDisplayStatus(old, source + 61_000).contains("원본 정보 갱신 대기"))
        assertTrue(subwayArrivalDiagnosticSummary(listOf(old), source + 61_000)!!.contains("60초"))
        val expired = parseWorkerSubwayArrivals(body(row().put("barvlDt", "3")), station, source + 5_000).single()
        assertTrue(SubwayArrivalIssue.COUNTDOWN_ELAPSED in expired.issues)
        assertFalse(SubwayArrivalIssue.SOURCE_STALE in expired.issues)
        assertTrue(subwayArrivalDisplayStatus(expired, source + 5_000).contains("예정시각 경과"))
    }

    @Test fun zeroEtaKeepsOfficialMessageWithoutInventingAnArrivalTimeForUi() {
        val original = "[2]번째 전역 (양재시민의숲)"
        val zero = parseWorkerSubwayArrivals(body(row().put("barvlDt", "0").put("arvlMsg2", original)),
            station, source + 5_000).single()
        assertEquals(original, zero.officialArrivalMessage)
        assertTrue(SubwayArrivalIssue.ETA_UNAVAILABLE in zero.issues)
        assertTrue(subwayArrivalDisplayStatus(zero, source + 5_000).contains("미제공"))
        assertFalse(zero.snapshot.reliable)
    }

    @Test fun sourceFutureAndDeviceClockBehindAreDifferentDiagnostics() {
        val sourceAhead = parseWorkerSubwayArrivals(body(row().put("recptnDt", "2026-10-25 06:14:37")),
            station, source + 5_000).single()
        assertTrue(SubwayArrivalIssue.SOURCE_FUTURE in sourceAhead.issues)
        assertFalse(SubwayArrivalIssue.DEVICE_CLOCK_BEHIND in sourceAhead.issues)
        assertTrue(subwayArrivalDisplayStatus(sourceAhead, source + 5_000).contains("원본 생성 시각"))
        val deviceBehind = parseWorkerSubwayArrivals(body(row()), station, source - 60_000).single()
        assertTrue(SubwayArrivalIssue.DEVICE_CLOCK_BEHIND in deviceBehind.issues)
        assertFalse(SubwayArrivalIssue.SOURCE_FUTURE in deviceBehind.issues)
        assertTrue(subwayArrivalDisplayStatus(deviceBehind, source - 60_000).contains("자동 날짜·시간"))
        assertFalse(deviceBehind.snapshot.reliable)
    }

    @Test fun gatewayFailuresKeepSafeMachineCauseAndSpecificRetryDeadline() {
        val now = source
        val warmup = parseWorkerHttpFailure(429,
            """{"error":{"code":"BUDGET_WARMUP","retryAfterSeconds":86400}}""", "60", now)
        assertEquals(SeoulSubwayFailure.SERVER_WARMUP, warmup.failure)
        assertEquals("BUDGET_WARMUP", warmup.serverCode)
        assertEquals(now + 86_400_000, warmup.retryAfterEpochMillis)
        assertTrue(warmup.message!!.contains("최초 요청량 보호"))
        val denied = parseWorkerHttpFailure(403, "<html>private-proxy-token</html>", null, now)
        assertEquals(SeoulSubwayFailure.GATEWAY_ACCESS_DENIED, denied.failure)
        assertFalse(denied.message!!.contains("서울시 실시간 지하철 전용 인증키"))
        assertFalse(denied.message!!.contains("private-proxy-token"))
        val missing = parseWorkerHttpFailure(503,
            """{"error":{"code":"SERVICE_NOT_CONFIGURED","detail":"private-secret"}}""", null, now)
        assertEquals(SeoulSubwayFailure.SETUP_REQUIRED, missing.failure)
        assertFalse(missing.message!!.contains("private-secret"))
        val unknown = parseWorkerHttpFailure(502,
            """{"error":{"code":"https://example.com/private-secret"}}""", null, now)
        assertNull(unknown.serverCode)
        assertFalse(unknown.message!!.contains("private-secret"))
    }

    @Test fun retryAfterHttpDateIsHonoredAndInvalidDeadlinesAreBounded() {
        val now = Instant.parse("2026-10-24T21:00:00Z").toEpochMilli()
        val dated = parseWorkerHttpFailure(429, null, "Sat, 24 Oct 2026 21:01:00 GMT", now)
        assertEquals(now + 60_000, dated.retryAfterEpochMillis)
        val malformed = parseWorkerHttpFailure(429,
            """{"error":{"code":"IP_RATE_LIMITED","retryAfterSeconds":999999999999}}""", "-1", now)
        assertEquals(now + 30_000, malformed.retryAfterEpochMillis)
    }
}
