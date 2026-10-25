package com.example.run_or_walk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutomaticSubwayIdentityTest {
    private val now = 1_700_000_000_000L
    private val point = MapPoint(127.027, 37.498)
    private fun route(direction: String?, nextStop: String? = null) = KakaoTransitChoice(
        totalSeconds = 1_200, transfers = 0, serviceName = "2호선",
        boardingStopName = "강남역", boardingPoint = point,
        firstTransitType = "SUBWAY", directionName = direction, nextStopName = nextStop)
    private fun row(direction: String, trainId: String = "T-001",
        variant: String = "confirmed", trust: ArrivalTrustState = ArrivalTrustState.VALID
    ): OfficialSubwayArrival {
        val identity = OfficialBoardingIdentity("seoul", "1002000222", "1002", direction, variant)
        val snapshot = RealtimeArrivalSnapshot(identity, now + 180_000L,
            fetchedAtEpochMillis = now - 10_000L, validForMillis = 60_000,
            reliable = trust == ArrivalTrustState.VALID, trustState = trust)
        val next = if (direction == "외선") "역삼" else "교대"
        return OfficialSubwayArrival(identity, direction + " · 성수행 - ${next}방면",
            "성수행", trainId, snapshot)
    }
    @Test fun onlyOneExactTrustedDirectionIsResolved() {
        val out = resolveAutomaticSubwayIdentity(route("외선"),
            listOf(row("외선"), row("내선", trainId = "T-002")), now)
        assertEquals(row("외선").identity, out)
    }
    @Test fun nextStationOrTerminalMayVerifyRouteDirectionWithoutManualSelection() {
        val rows = listOf(row("내선", "T-002"), row("외선"))
        assertNull(resolveAutomaticSubwayIdentity(route(null), rows, now))
        assertEquals(row("외선").identity,
            resolveAutomaticSubwayIdentity(route(null, "역삼역"), rows, now))
        assertEquals(row("내선", "T-002").identity,
            resolveAutomaticSubwayIdentity(route(null, "교대역"), rows, now))
        assertEquals(row("외선").identity,
            resolveAutomaticSubwayIdentity(route("성수행"), rows.filter { it.identity.directionId == "외선" }, now))
        assertNull(resolveAutomaticSubwayIdentity(route(null, "선릉역"), rows, now))
    }
    @Test fun multipleServiceVariantsNeedManualSelection() {
        assertNull(resolveAutomaticSubwayIdentity(route("외선"),
            listOf(row("외선"), row("외선", "T-003", "different")), now))
    }
    @Test fun invalidAndStaleRowsDoNotAuthorizeAutoBinding() {
        assertNull(resolveAutomaticSubwayIdentity(route("외선"),
            listOf(row("외선", trust = ArrivalTrustState.STALE)), now))
    }
}
