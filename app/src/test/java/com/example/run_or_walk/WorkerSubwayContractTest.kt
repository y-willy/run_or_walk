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
}
