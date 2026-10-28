package com.example.run_or_walk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class OfficialTimetableTest {
    private val date = LocalDate.of(2026, 10, 25)
    private val json = """
        {
          "schemaVersion": 1,
          "identityAuthority": "seoul",
          "source": {
            "authority": "seoulMetro", "dataset": "OA-22522", "version": "20250930",
            "publishedAtEpochMillis": 1759190400000,
            "scheduleAsOfDate": "2025-09-30", "timeZone": "Asia/Seoul"
          },
          "calendar": [{"serviceDate": "2026-10-25", "dayType": "HOLIDAY", "sourceVersion": "official-calendar-2026"}],
          "trips": [{
            "trainNo": "K6175", "serviceDate": "2026-10-25", "dayType": "HOLIDAY",
            "routeId": "1075", "directionId": "하행", "serviceVariant": "고색행",
            "operationType": "LOCAL", "routePatternId": "wangsimni-gosaek-local",
            "stops": [
              {"sequence": 1, "stopId": "sanggal", "stopName": "상갈", "arrivalTime": "24:00:00", "departureTime": "24:00:30"},
              {"sequence": 2, "stopId": "mangpo", "stopName": "망포", "arrivalTime": "24:09:00", "departureTime": "24:09:30"}
            ]
          }]
        }
    """.trimIndent()

    @Test fun normalizedPublicationPreservesOfficialProvenanceAndAbsoluteServiceDayTimes() {
        val timetable = parseOfficialTimetableJson(json)!!
        assertEquals("seoul", timetable.identityAuthority)
        assertEquals("seoulMetro", timetable.source.authority)
        assertNull(timetable.source.validThroughServiceDate)
        assertEquals(LocalDate.of(2025, 9, 30), timetable.source.scheduleAsOfDate)
        assertEquals("official-calendar-2026", timetable.calendar.single().sourceVersion)
        assertEquals("K6175", timetable.trips.single().trainNo)
        assertEquals(date, timetable.trips.single().serviceDate)
        assertEquals(officialTimetableTimeAt(date.plusDays(1), "00:09:00"),
            timetable.trips.single().stops.last().arrivalAtEpochMillis)
    }

    @Test fun serviceDateRolloverIsExplicitAndMidnightIsNotGuessed() {
        assertEquals(86_400_000L,
            officialTimetableTimeAt(date, "24:10:00")!! - officialTimetableTimeAt(date, "00:10:00")!!)
        assertEquals(officialTimetableTimeAt(date.plusDays(1), "01:00:00"),
            officialTimetableTimeAt(date, "25:00:00"))
        listOf("48:00:00", "9:00:00", "24:60:00", "00:00", "-1:00:00", "24:00:00Z").forEach {
            assertNull(officialTimetableTimeAt(date, it))
        }
    }

    @Test fun absoluteAndServiceTimeFormsMustAgreeAndRemainInTheirServiceDay() {
        val root = JSONObject(json)
        val stop = root.getJSONArray("trips").getJSONObject(0).getJSONArray("stops").getJSONObject(0)
        stop.put("arrivalAtEpochMillis", officialTimetableTimeAt(date, "24:00:00"))
        assertNotNull(parseOfficialTimetableJson(root.toString()))
        stop.put("arrivalAtEpochMillis", officialTimetableTimeAt(date, "00:00:00"))
        assertNull(parseOfficialTimetableJson(root.toString()))
        stop.remove("arrivalTime")
        stop.put("arrivalAtEpochMillis", officialTimetableTimeAt(date.minusDays(1), "23:00:00"))
        assertNull(parseOfficialTimetableJson(root.toString()))
    }

    @Test fun parserRejectsMissingCalendarConflictsMalformedRowsAndGuessedTypes() {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("schemaVersion", 2) },
            { it.put("schemaVersion", "1") },
            { it.getJSONObject("source").remove("version") },
            { it.getJSONObject("source").put("publishedAtEpochMillis", "1759190400000") },
            { it.getJSONObject("source").put("timeZone", "UTC") },
            { it.getJSONArray("calendar").put(it.getJSONArray("calendar").getJSONObject(0)) },
            { it.getJSONArray("calendar").getJSONObject(0).put("dayType", "SUNDAY") },
            { it.getJSONArray("calendar").getJSONObject(0).put("dayType", "WEEKDAY") },
            { it.getJSONArray("trips").getJSONObject(0).put("directionId", "unknown") },
            { it.getJSONArray("trips").getJSONObject(0).put("trainNo", "6175\n") },
            { it.getJSONArray("trips").getJSONObject(0).getJSONArray("stops").getJSONObject(1).put("sequence", 1) },
            { it.getJSONArray("trips").getJSONObject(0).getJSONArray("stops").getJSONObject(1).put("arrivalTime", "23:59:00") }
        )
        mutations.forEach { mutate ->
            val root = JSONObject(json)
            mutate(root)
            assertNull(root.toString(), parseOfficialTimetableJson(root.toString()))
        }
        assertNull(parseOfficialTimetableJson("not JSON"))
    }

    @Test fun explicitValidityAcceptsOnlyDatesWithinThePublishedRange() {
        val root = JSONObject(json)
        val source = root.getJSONObject("source")
        source.put("validFromServiceDate", "2026-10-01")
        source.put("validThroughServiceDate", "2026-10-31")
        assertNotNull(parseOfficialTimetableJson(root.toString()))
        source.put("validThroughServiceDate", "2026-10-24")
        assertNull(parseOfficialTimetableJson(root.toString()))
    }
}
