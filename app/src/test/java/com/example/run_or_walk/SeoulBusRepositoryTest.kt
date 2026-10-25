package com.example.run_or_walk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class SeoulBusRepositoryTest {
    private val source = Instant.parse("2026-10-25T08:00:00Z").toEpochMilli()
    private val stop = OfficialSeoulBusStop("100000001", "01001", "종로2가", MapPoint(126.98, 37.57))
    private val route = OfficialSeoulBusRoute(stop.stopId, "100100001", "100", "12", "서울역 방면", "3")
    private fun row(vehicle: String = "100001001", eta: Any = 300, observed: Long = source): JSONObject =
        JSONObject().put("stopId", stop.stopId).put("routeId", route.routeId).put("stopOrder", route.stopOrder)
            .put("routeName", route.name).put("direction", "서울역")
            .put("vehicleId", vehicle).put("arrivalSeconds", eta).put("observedAt", Instant.ofEpochMilli(observed).toString())
            .put("message", "5분 후").put("full", false).put("last", false).put("trustState", "VALID")
            .put("routeType", "3").put("raw", JSONObject().put("isArrive", "0").put("deTourAt", "00"))
    private fun body(vararg rows: JSONObject, fetched: Long = source + 5_000): String = JSONObject()
        .put("provider", "seoul-bus").put("fetchedAt", Instant.ofEpochMilli(fetched).toString())
        .put("stopId", stop.stopId).put("routeId", route.routeId).put("stopOrder", route.stopOrder)
        .put("arrivals", JSONArray(rows.toList())).toString()
    private fun routesBody(vararg rows: JSONObject): String = JSONObject()
        .put("provider", "seoul-bus").put("fetchedAt", Instant.ofEpochMilli(source).toString())
        .put("stopId", stop.stopId).put("routes", JSONArray(rows.toList())).toString()
    private fun routeRow(order: String = route.stopOrder) = JSONObject().put("routeId", route.routeId)
        .put("name", route.name).put("stopOrder", order).put("direction", route.direction)
        .put("routeType", "3").put("supported", true)

    @Test fun gatewayRequiresHttpsAndDoesNotAcceptDirectOfficialOrCredentialBearingUrls() {
        assertEquals("https", validateSeoulBusGateway("https://example.workers.dev", "").scheme)
        listOf("http://example.workers.dev", "https://token@example.workers.dev",
            "https://example.workers.dev?secret=x", "https://example.workers.dev/path",
            "https://ws.bus.go.kr", "https://apis.data.go.kr").forEach { url ->
            assertThrows(SeoulBusApiException::class.java) { validateSeoulBusGateway(url, "") }
        }
    }

    @Test fun stopsKeepOfficialAndArsIdsDistinctAndRejectConflictingDuplicates() {
        val good = JSONObject().put("stopId", stop.stopId).put("arsId", "01001").put("name", stop.name)
            .put("x", 126.98).put("y", 37.57)
        fun payload(vararg values: JSONObject) = JSONObject().put("provider", "seoul-bus")
            .put("fetchedAt", Instant.ofEpochMilli(source).toString()).put("stops", JSONArray(values.toList())).toString()
        val parsed = parseWorkerSeoulBusStops(payload(good, good)).single()
        assertEquals(stop.stopId, parsed.stopId)
        assertEquals("01001", parsed.arsId)
        assertEquals(stop.point, parsed.point)
        assertTrue(parseWorkerSeoulBusStops(payload(good, JSONObject(good.toString()).put("name", "다른 정류소"))).isEmpty())
        assertTrue(parseWorkerSeoulBusStops(payload(JSONObject(good.toString()).put("stopId", "kakao-place"))).isEmpty())
    }

    @Test fun routeSequenceDistinguishesLoopOccurrencesAndForeignRoutesRemainUnsupported() {
        val parsed = parseWorkerSeoulBusRoutes(routesBody(routeRow("12"), routeRow("45"),
            routeRow("46").put("routeType", "8").put("supported", false)), stop)
        assertEquals(3, parsed.size)
        assertNotEquals(parsed[0].identity, parsed[1].identity)
        assertTrue(parsed.take(2).all { it.supported })
        assertFalse(parsed.last().supported)
        assertEquals(route.identity, parseWorkerSeoulBusRoutes(routesBody(routeRow().put("direction", "서울역")), stop).single().identity)
        assertThrows(SeoulBusApiException::class.java) {
            parseWorkerSeoulBusRoutes(routesBody(routeRow()), stop.copy(stopId = "999000001"))
        }
    }

    @Test fun conflictingOfficialRouteMetadataNeverUsesFirstRow() {
        assertTrue(parseWorkerSeoulBusRoutes(routesBody(routeRow(), routeRow().put("direction", "반대 방면")), stop).isEmpty())
    }

    @Test fun validActualVehiclesPreserveSourceTimeAndOnlyRealNextBusIsPaired() {
        val parsed = parseWorkerSeoulBusArrivals(body(row(), row("100001002", 600)), route, source + 15_000)
        val first = chooseMatchingBusArrival(route.identity, parsed, source + 15_000)!!
        assertTrue(first.snapshot.reliable)
        assertEquals(source, first.snapshot.fetchedAtEpochMillis)
        assertEquals(source + 300_000, first.snapshot.firstArrivalAtEpochMillis)
        assertEquals(source + 600_000, first.snapshot.nextArrivalAtEpochMillis)
        assertEquals(285_000L, first.snapshot.firstArrivalAtEpochMillis - (source + 15_000))
        assertEquals("서울역", first.directionLabel)
        assertEquals("5분 후", first.officialArrivalMessage)
    }

    @Test fun nextSnapshotExpiryUsesOlderSourceEvenWhenNextBusIsFreshEnough() {
        val first = parseWorkerSeoulBusArrivals(body(row(), row("100001002", 600, source - 20_000)), route,
            source + 5_000).first()
        assertEquals(40_000L, first.snapshot.validForMillis)
        assertNotNull(chooseMatchingBusArrival(route.identity, listOf(first), source + 39_000))
        assertNull(chooseMatchingBusArrival(route.identity, listOf(first), source + 41_000))
    }

    @Test fun wrongStopRouteOrSequenceCannotBecomeSelectedDirection() {
        listOf("stopId", "routeId", "stopOrder").forEach { field ->
            assertTrue(parseWorkerSeoulBusArrivals(body(row().put(field, "999")), route, source + 5_000).isEmpty())
        }
        val parsed = parseWorkerSeoulBusArrivals(body(row()), route, source + 5_000)
        assertNull(chooseMatchingBusArrival(route.copy(stopOrder = "45").identity, parsed, source + 5_000))
        assertThrows(SeoulBusApiException::class.java) {
            parseWorkerSeoulBusArrivals(JSONObject(body(row())).put("stopOrder", "45").toString(), route, source + 5_000)
        }
    }

    @Test fun zeroInvalidMissingVehicleAndMissingSourceStayUncertainDespiteServerValid() {
        val values = listOf(row(eta = 0), row(eta = -1), row(eta = "NaN"), row(eta = "Infinity"),
            row().apply { remove("arrivalSeconds") }, row(vehicle = "0"), row(vehicle = ""),
            row().apply { remove("observedAt") }, row().apply { remove("full") },
            row().apply { getJSONObject("raw").put("isArrive", "1") }, row().put("routeType", "8"))
        values.forEach { value ->
            val result = parseWorkerSeoulBusArrivals(body(value), route, source + 5_000).single()
            assertFalse(result.snapshot.reliable)
            assertNull(chooseMatchingBusArrival(route.identity, listOf(result), source + 5_000))
        }
    }

    @Test fun sourceFutureAtOriginalQueryCannotBecomeValidAfterTimeCatchesUp() {
        val payload = body(row(observed = source + 20_000))
        val result = parseWorkerSeoulBusArrivals(payload, route, source + 30_000).single()
        assertEquals(ArrivalTrustState.FUTURE_TIMESTAMP, result.snapshot.trustState)
        assertFalse(result.snapshot.reliable)
    }

    @Test fun oldOrElapsedAndServerUnknownRemainUnusable() {
        val old = parseWorkerSeoulBusArrivals(body(row()), route, source + 61_000).single()
        assertEquals(ArrivalTrustState.STALE, old.snapshot.trustState)
        val elapsed = parseWorkerSeoulBusArrivals(body(row(eta = 10)), route, source + 11_000).single()
        assertEquals(ArrivalTrustState.STALE, elapsed.snapshot.trustState)
        val restricted = parseWorkerSeoulBusArrivals(body(row().put("trustState", "UNKNOWN")), route, source + 5_000).single()
        assertFalse(restricted.snapshot.reliable)
    }

    @Test fun actualNineAndTenDigitVehicleIdsAreAcceptedButPlaceholdersAreNot() {
        listOf("100001001", "1000001001").forEach { vehicle ->
            val parsed = parseWorkerSeoulBusArrivals(body(row(vehicle)), route, source + 5_000).single()
            assertEquals(vehicle, chooseMatchingBusArrival(route.identity, listOf(parsed), source + 5_000)?.vehicleId)
        }
        listOf("0", "10010010", "10000010010", "kakao-100001001", "madeup", "000000001").forEach { vehicle ->
            val parsed = parseWorkerSeoulBusArrivals(body(row(vehicle)), route, source + 5_000).single()
            assertFalse(parsed.snapshot.reliable)
            assertNull(chooseMatchingBusArrival(route.identity, listOf(parsed), source + 5_000))
        }
    }

    @Test fun endedOrWaitingMessagesAndUncertaintyIssuesOverrideContradictoryValidFlag() {
        listOf("운행종료", "운행 종료", "출발대기", "출발 대기", "회차대기", "회차 대기", "정보없음", "정보 없음")
            .forEach { message ->
                val parsed = parseWorkerSeoulBusArrivals(body(row().put("message", message)), route, source + 5_000).single()
                assertEquals(message, parsed.officialArrivalMessage)
                assertEquals(ArrivalTrustState.UNKNOWN, parsed.snapshot.trustState)
                assertFalse(parsed.snapshot.reliable)
                assertNull(chooseMatchingBusArrival(route.identity, listOf(parsed), source + 5_000))
            }
        listOf("SERVICE_STATE_UNCERTAIN", "VEHICLE_STATE_UNCERTAIN", "DETOUR", "CAPACITY_UNKNOWN",
            "DUPLICATE_VEHICLE", "FUTURE_UNKNOWN_ISSUE").forEach { issue ->
            val parsed = parseWorkerSeoulBusArrivals(body(row().put("issues", JSONArray(listOf(issue)))),
                route, source + 5_000).single()
            assertEquals(setOf(issue), parsed.issues)
            assertFalse(parsed.snapshot.reliable)
            assertNull(chooseMatchingBusArrival(route.identity, listOf(parsed), source + 5_000))
        }
        // The selection guard also rejects uncertainty on an independently restored model.
        val valid = parseWorkerSeoulBusArrivals(body(row()), route, source + 5_000).single()
        assertNull(chooseMatchingBusArrival(route.identity, listOf(valid.copy(officialArrivalMessage = "운행종료")), source + 5_000))
        assertNull(chooseMatchingBusArrival(route.identity, listOf(valid.copy(issues = setOf("SERVICE_STATE_UNCERTAIN"))), source + 5_000))
    }

    @Test fun missingVehicleOrDetourStateAndZeroSourceCannotAuthorizeGuidance() {
        val uncertainRows = listOf(
            row().apply { getJSONObject("raw").remove("isArrive") },
            row().apply { getJSONObject("raw").remove("deTourAt") },
            row().apply { getJSONObject("raw").put("deTourAt", "01") },
            row(observed = 0L))
        uncertainRows.forEach { value ->
            val parsed = parseWorkerSeoulBusArrivals(body(value), route, source + 5_000).single()
            assertFalse(parsed.snapshot.reliable)
            assertNull(chooseMatchingBusArrival(route.identity, listOf(parsed), source + 5_000))
        }
        val valid = parseWorkerSeoulBusArrivals(body(row()), route, source + 5_000).single()
        val zeroSource = valid.copy(snapshot = valid.snapshot.copy(fetchedAtEpochMillis = 0L,
            firstArrivalAtEpochMillis = 300_000L))
        assertNull(chooseMatchingBusArrival(route.identity, listOf(zeroSource), 5_000L))
    }

    @Test fun fullFirstVehicleCannotAuthorizeGuidanceAndActualNonFullNextMayBeSelected() {
        val parsed = parseWorkerSeoulBusArrivals(body(row().put("full", true).put("message", "만차"),
            row("100001002", 600).put("last", true)), route, source + 5_000)
        val full = parsed.single { it.isFull == true }
        assertFalse(full.snapshot.reliable)
        assertTrue(seoulBusArrivalDisplayStatus(full, source + 5_000).contains("만차"))
        assertEquals("100001002", chooseMatchingBusArrival(route.identity, parsed, source + 5_000)?.vehicleId)
        assertTrue(parsed.single { it.isLast == true }.snapshot.nextArrivalAtEpochMillis == null)
    }

    @Test fun duplicateVehicleConflictOrTiedArrivalDoesNotChooseByInputOrder() {
        val conflicted = parseWorkerSeoulBusArrivals(body(row(), row(eta = 320)), route, source + 5_000)
        assertEquals(1, conflicted.size)
        assertFalse(conflicted.single().snapshot.reliable)
        val tied = parseWorkerSeoulBusArrivals(body(row(), row("100001002", 300)), route, source + 5_000)
        assertNull(chooseMatchingBusArrival(route.identity, tied, source + 5_000))
        val repeated = parseWorkerSeoulBusArrivals(body(row(), row()), route, source + 5_000)
        assertEquals(1, repeated.size)
        assertNotNull(chooseMatchingBusArrival(route.identity, repeated, source + 5_000))
    }

    @Test fun knownErrorCodesAreSafeAndRetryBackoffIsSharedAcrossStations() {
        val failure = parseSeoulBusHttpFailure(429,
            """{"error":{"code":"BUS_UPSTREAM_BUDGET_EXHAUSTED","retryAfterSeconds":120,"message":"secret https://upstream"}}""",
            "60", source)
        assertEquals(source + 120_000, failure.retryAfterEpochMillis)
        assertEquals(SeoulBusFailure.REQUEST_LIMIT, failure.failure)
        assertFalse(failure.message.orEmpty().contains("secret"))
        assertNull(failure.cause)
        val upstreamLimit = parseSeoulBusHttpFailure(429,
            """{"error":{"code":"BUS_UPSTREAM_RATE_LIMITED","retryAfterSeconds":3600}}""", "3600", source)
        assertEquals(SeoulBusFailure.REQUEST_LIMIT, upstreamLimit.failure)
        assertEquals("BUS_UPSTREAM_RATE_LIMITED", upstreamLimit.serverCode)
        assertEquals(source + 3_600_000L, upstreamLimit.retryAfterEpochMillis)
        assertTrue(upstreamLimit.message.orEmpty().contains("원본 API의 요청 한도"))
        val backoff = SeoulBusRequestBackoff()
        backoff.record(failure)
        assertSame(failure, assertThrows(SeoulBusApiException::class.java) { backoff.check(source + 60_000) })
        backoff.check(source + 120_000)
        val setup = parseSeoulBusHttpFailure(503, """{"error":{"code":"SEOUL_BUS_NOT_CONFIGURED"}}""", null, source)
        assertEquals(SeoulBusFailure.SETUP_REQUIRED, setup.failure)
        assertTrue(setup.message.orEmpty().contains("SEOUL_BUS_KEY"))
        val rejected = parseSeoulBusHttpFailure(502,
            """{"error":{"code":"BUS_UPSTREAM_KEY_REJECTED"}}""", null, source)
        assertEquals(SeoulBusFailure.ACCESS_DENIED, rejected.failure)
        assertTrue(rejected.message.orEmpty().contains("401"))
        val forbidden = parseSeoulBusHttpFailure(502,
            """{"error":{"code":"BUS_UPSTREAM_ACCESS_FORBIDDEN"}}""", null, source)
        assertEquals(SeoulBusFailure.ACCESS_DENIED, forbidden.failure)
        assertTrue(forbidden.message.orEmpty().contains("403"))
        val unknown = parseSeoulBusHttpFailure(500, """{"error":{"code":"secret-value"}}""", null, source)
        assertNull(unknown.serverCode)
        assertFalse(unknown.message.orEmpty().contains("secret-value"))
    }
}
