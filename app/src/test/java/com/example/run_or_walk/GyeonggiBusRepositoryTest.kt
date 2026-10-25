package com.example.run_or_walk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class GyeonggiBusRepositoryTest {
    private val now = Instant.parse("2026-06-22T07:25:59.000Z").toEpochMilli()
    // Synthetic Worker JSON, not a claim that a real approved key was called.
    private fun json(seconds: Int = 180, source: String = "2026-06-22T07:25:50.000Z"): String =
        """{"provider":"gyeonggi-gbis","stationId":"200000177",
          "fetchedAt":"2026-06-22T07:25:59.000Z",
          "arrivals":[{"stationId":"200000177","routeId":"200000037","staOrder":"19",
          "routeName":"9","direction":"수원역","vehicleId":"123456","sequence":1,
          "arrivalSeconds":$seconds,"observedAt":"$source","status":"RUN",
          "trustState":"VALID","etaPrecision":"seconds"}]}"""
    @Test fun acceptsOnlyMatchingOfficialStation() {
        val items = parseGyeonggiWorkerArrivals(json(), "200000177", now)
        assertEquals(1, items.size)
        assertEquals(ArrivalTrustState.VALID, items[0].trust)
        assertEquals("19", items[0].staOrder)
        assertEquals("200000037", items[0].routeId)
    }
    @Test fun neverTrustsZeroSecondOrFutureClocks() {
        assertEquals(ArrivalTrustState.UNKNOWN,
            parseGyeonggiWorkerArrivals(json(seconds = 0), "200000177", now)[0].trust)
        assertEquals(ArrivalTrustState.STALE,
            parseGyeonggiWorkerArrivals(json(), "200000177", now + 61_000L)[0].trust)
        assertEquals(ArrivalTrustState.FUTURE_TIMESTAMP,
            parseGyeonggiWorkerArrivals(json(source = "2026-06-22T07:30:00.000Z"),
                "200000177", now)[0].trust)
    }
    @Test fun rejectsWrongStationBeforeDisplaying() {
        assertTrue(runCatching {
            parseGyeonggiWorkerArrivals(json(), "200000999", now)
        }.isFailure)
    }
    @Test fun gbisDisplayDoesNotBecomeConfirmedAutoGuidanceWithoutStopMapping() {
        val screen = JourneyScreenState(FeatureMode.ROUTE)
        screen.gyeonggiArrivals = parseGyeonggiWorkerArrivals(json(), "200000177", now)
        assertNull(screen.confirmedServiceIdentity)
        assertNull(screen.activeArrivalSnapshot)
    }
}
