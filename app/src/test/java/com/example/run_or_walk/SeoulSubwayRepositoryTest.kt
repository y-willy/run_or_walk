package com.example.run_or_walk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.OffsetDateTime

class SeoulSubwayRepositoryTest {
    private val station = OfficialSubwayStation("1004000426", "1004", "서울", "4호선")
    private val source = OffsetDateTime.parse("2026-10-25T06:09:50+09:00").toInstant().toEpochMilli()
    private fun fixture() = javaClass.getResourceAsStream("/seoul_public_sample_arrivals_20261024.json")!!
        .bufferedReader().use { it.readText() }
    private fun row() = JSONObject(fixture()).getJSONArray("realtimeArrivalList").getJSONObject(3)
    private fun body(vararg rows: JSONObject) = JSONObject()
        .put("errorMessage", JSONObject().put("status", 200).put("code", "INFO-000"))
        .put("realtimeArrivalList", JSONArray(rows.toList())).toString()

    @Test fun actualPublicFixtureUsesOfficialIdsSecondsAndSourceClock() {
        val result = parseSeoulSubwayArrivals(fixture(), station, source + 5_000).single()
        assertTrue(result.snapshot.reliable)
        assertEquals("4302", result.trainId)
        assertEquals(station.stopId, result.identity.stopId)
        assertEquals(source, result.snapshot.fetchedAtEpochMillis)
        assertEquals(source + 330_000, result.snapshot.firstArrivalAtEpochMillis)
        assertNull(result.snapshot.nextArrivalAtEpochMillis)
    }

    @Test fun sameStationNameOnAnotherLineDoesNotMatch() {
        assertTrue(parseSeoulSubwayArrivals(fixture(), station.copy(stopId = "wrong"), source).isEmpty())
        assertTrue(parseSeoulSubwayArrivals(fixture(), station.copy(routeId = "1002"), source).isEmpty())
    }

    @Test fun zeroImminentDepartedStaleAndFutureCannotProduceCatchability() {
        for (changed in listOf(row().put("barvlDt", "0"), row().put("arvlCd", "0"),
                row().put("arvlCd", "1"), row().put("arvlCd", "2"))) {
            assertFalse(parseSeoulSubwayArrivals(body(changed), station, source).single().snapshot.reliable)
        }
        assertFalse(parseSeoulSubwayArrivals(body(row()), station, source + 60_001).single().snapshot.reliable)
        assertFalse(parseSeoulSubwayArrivals(body(row()), station, source - 1).single().snapshot.reliable)
    }

    @Test fun zeroEtaIsReportedAsUnavailableInsteadOfMistakenForSourceAge() {
        val zero = parseSeoulSubwayArrivals(
            body(row().put("barvlDt", "0")), station, source + 40_000)
        assertTrue(zero.single().issues.contains(SubwayArrivalIssue.ETA_UNAVAILABLE))
        assertTrue(subwayArrivalDiagnosticSummary(zero, source + 40_000).orEmpty().contains("0초"))
    }

    @Test fun nextArrivalMustActuallyExistAndExpireWithItsOwnSource() {
        val next = row().put("btrainNo", "4303").put("barvlDt", "400")
            .put("recptnDt", "2026-10-25 06:09:30")
        val result = parseSeoulSubwayArrivals(body(row(), next), station, source + 1_000)
            .first { it.trainId == "4302" }.snapshot
        assertEquals(source + 380_000, result.nextArrivalAtEpochMillis)
        assertEquals(40_000L, result.validForMillis)
    }

    @Test fun anotherDirectionOrExpressServiceIsNeverTheNextVehicle() {
        val opposite = row().put("btrainNo", "4303").put("barvlDt", "400").put("updnLine", "하행")
        val express = row().put("btrainNo", "4304").put("barvlDt", "500").put("btrainSttus", "급행")
        assertNull(parseSeoulSubwayArrivals(body(row(), opposite, express), station, source)
            .first { it.trainId == "4302" }.snapshot.nextArrivalAtEpochMillis)
    }

    @Test fun trainNumbersKeepLeadingZerosAndArrivalErrorsAreNotSuccesses() {
        val value = row().put("btrainNo", "0004")
        assertEquals("0004", parseSeoulSubwayArrivals(body(value), station, source).single().trainId)
        val keyError = """{"errorMessage":{"code":"INFO-100","developerMessage":"test-only-private-text"}}"""
        val error = assertThrows(SeoulSubwayApiException::class.java) {
            parseSeoulSubwayArrivals(keyError, station, source)
        }
        assertEquals(SeoulSubwayFailure.INVALID_AUTHENTICATION, error.failure)
        assertFalse(error.message.orEmpty().contains("test-only-private-text"))
        assertThrows(SeoulSubwayApiException::class.java) { parseSeoulSubwayArrivals("{}", station, source) }
        assertTrue(parseSeoulSubwayArrivals("""{"errorMessage":{"code":"INFO-200"}}""", station, source).isEmpty())
    }

    @Test fun localCatalogReturnsExplicitLineCandidatesAndPreservesDistinctNames() {
        val stations = parseSeoulSubwayStations("""[
            {"stopId":"one","routeId":"1001","name":"서울","lineName":"1호선"},
            {"stopId":"four","routeId":"1004","name":"서울","lineName":"4호선"},
            {"stopId":"other","routeId":"1063","name":"신촌(경의중앙선)","lineName":"경의중앙선"}
        ]""")
        assertEquals(2, searchOfficialSubwayStations(stations, "서울역").size)
        assertEquals("other", searchOfficialSubwayStations(stations, "신촌(경의중앙선)").single().stopId)
        assertTrue(searchOfficialSubwayStations(stations, "").isEmpty())
    }

    @Test fun clientOnlyAcceptsAnHttpsGatewayAndNeverTheOfficialKeyEndpoint() {
        val token = "unit-test-access-token-not-real-00000"
        assertEquals("https", validateSubwayGatewaySetup("https://subway.example.com", token).scheme)
        for (url in listOf("", "http://subway.example.com", "https://swopenapi.seoul.go.kr",
                "https://user@subway.example.com", "https://subway.example.com?key=not-real")) {
            assertThrows(SeoulSubwayApiException::class.java) { validateSubwayGatewaySetup(url, token) }
        }
        assertEquals("https", validateSubwayGatewaySetup("https://subway.example.com", "").scheme)
    }
}
