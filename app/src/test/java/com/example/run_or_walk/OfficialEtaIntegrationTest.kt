package com.example.run_or_walk

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate

class OfficialEtaIntegrationTest {
    private val root = File("..")
    private val now = Instant.parse("2026-10-25T13:55:58.715Z").toEpochMilli()
    private val match = GyeonggiOfficialMatch("200000001", "234000001", "12", "M5107", "서울역", MapPoint(127.0, 37.2))
    private fun bus(seconds: Int, sourceAge: Long? = null) = GyeonggiBusArrivalDisplay(
        match.stationId, match.routeId, "", "M5107", "", "", seconds, now,
        sourceAge?.let { now - it }, ArrivalTrustState.VALID, if (seconds == 240) 1 else 2,
        ArrivalTimePrecision.MINUTES, sourceAge != null, now)

    @Test fun officialMinutesWithoutVehicleOrOrderAreImmediatelyUsable() {
        val selected = chooseMatchingGyeonggiArrival(match, listOf(bus(240), bus(480)), now)!!
        assertEquals(now + 240_000, selected.snapshot.firstArrivalAtEpochMillis)
        assertEquals(now + 480_000, selected.snapshot.nextArrivalAtEpochMillis)
        assertEquals(ArrivalTimePrecision.MINUTES, selected.snapshot.etaPrecision)
        assertFalse(selected.snapshot.sourceTimestampAvailable)
        assertTrue(selected.snapshot.hasUsableOfficialEta(now))
        assertFalse(selected.snapshot.hasUsableOfficialEta(now + 60_001))
        assertEquals("official-slot:1", selected.snapshot.vehicleIdentityFallback)
        assertEquals(now + 150_000, chooseMatchingGyeonggiArrival(match,
            listOf(bus(240, 90_000)), now)!!.snapshot.firstArrivalAtEpochMillis)
        assertNull(chooseMatchingGyeonggiArrival(match, listOf(bus(-1)), now))
        assertNull(chooseMatchingGyeonggiArrival(match, listOf(bus(240).copy(routeId="234000002")), now))
    }

    @Test fun realMangpoPositionAndOfficialSuinBundangTimetableReachSuwon() = runBlocking {
        val repository = OfficialTimetableRepository.fromAssets { File(root, "app/src/main/assets/$it").inputStream() }
        val station = OfficialSubwayStation("1075075241", "1075", "망포", "수인분당선")
        val route = KakaoTransitChoice(600, 0, "수인분당선", "망포", null,
            firstTransitType="SUBWAY", firstLegStopNames=listOf("망포", "수원"), alightingStopName="수원")
        val rows = parseWorkerSubwayArrivals(File(root,
            "app/src/test/resources/mangpo_operation_response_20261025.json").readText(), station, now)
        val result = SubwayTimetableAnalyzer(repository).analyze(station, route, rows, now,
            LocalDate.of(2026,10,25), OfficialServiceDayType.HOLIDAY, "government-2026")
        assertTrue(result.toString(), result.directionVerified)
        assertTrue(result.matchingArrivals.isNotEmpty())
        assertTrue(result.matchingArrivals.all { it.identity.directionId == "하행" })
        assertTrue(result.toString(), result.estimates.isNotEmpty())
        assertTrue(result.estimates.all { !it.estimate.isRealtime })
    }

    @Test fun positiveSubwayEtaDoesNotRequireAnOptionalTrainNumber() = runBlocking {
        val repository = OfficialTimetableRepository.fromAssets { File(root, "app/src/main/assets/$it").inputStream() }
        val station = OfficialSubwayStation("1075075241", "1075", "망포", "수인분당선")
        val route = KakaoTransitChoice(600, 0, "수인분당선", "망포", null,
            firstTransitType="SUBWAY", firstLegStopNames=listOf("망포", "수원"), alightingStopName="수원")
        val raw = JSONObject().put("fetchedAt", Instant.ofEpochMilli(now).toString())
            .put("arrivals", org.json.JSONArray().put(JSONObject()
                .put("statnId",station.stopId).put("subwayId",station.routeId).put("updnLine","하행")
                .put("trainLineNm","고색행 - 매탄권선방면").put("barvlDt","240")))
        val rows = parseWorkerSubwayArrivals(raw.toString(),station,now)
        assertTrue(rows.single().snapshot.hasUsableOfficialEta(now))
        val result = SubwayTimetableAnalyzer(repository).analyze(station, route, rows, now,
            LocalDate.of(2026,10,25), OfficialServiceDayType.HOLIDAY, "government-2026")
        assertTrue(result.toString(), result.directionVerified)
        assertEquals(1,result.matchingArrivals.size)
    }
}
