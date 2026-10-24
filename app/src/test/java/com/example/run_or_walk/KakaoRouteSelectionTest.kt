package com.example.run_or_walk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KakaoRouteSelectionTest {
    private fun route(
        service: String,
        seconds: Int = 900,
        transfers: Int? = 0,
        walking: Int? = null
    ) = KakaoTransitChoice(
        totalSeconds = seconds,
        transfers = transfers,
        serviceName = service,
        boardingStopName = "서울역",
        boardingPoint = MapPoint(126.9726, 37.5547),
        firstTransitType = "SUBWAY",
        totalWalkingSeconds = walking
    )

    @Test
    fun returnsAtMostThreeRoutesRankedByTimeThenKnownTransfers() {
        val choices = listOf(
            route("unknown", transfers = null),
            route("slow", seconds = 1200),
            route("transfer", transfers = 2),
            route("direct", transfers = 0),
            route("fast", seconds = 600)
        )

        assertEquals(listOf("fast", "direct", "transfer"),
            selectTopTransitChoices(choices).map { it.serviceName })
    }

    @Test
    fun tieOrderIsStableRegardlessOfProviderResultOrder() {
        val a = route("1호선", walking = 300)
        val b = route("2호선", walking = 120)
        val c = route("3호선", walking = null)

        assertEquals(listOf(b, a, c), selectTopTransitChoices(listOf(c, a, b)))
        assertEquals(selectTopTransitChoices(listOf(c, a, b)),
            selectTopTransitChoices(listOf(b, c, a)))
    }

    @Test
    fun excludesInvalidCountsAndDuplicateResults() {
        val valid = route("valid")
        val choices = listOf(valid, valid, route("zero", seconds = 0),
            route("negative time", seconds = -1), route("negative transfer", transfers = -1),
            route("negative walk", walking = -1))

        assertEquals(listOf(valid), selectTopTransitChoices(choices))
    }

    @Test
    fun liveBoardingFeasibilityCanOverrideShortestDuration() {
        val missed = route("missed", seconds = 300)
        val unknown = route("unknown", seconds = 600)
        val reachable = route("reachable", seconds = 900)
        val choices = listOf(missed, unknown, reachable)

        // Evidence must come from the freshness/identity-checked guidance domain.
        val ranked = selectTopTransitChoices(choices) {
            when (it) {
                missed -> false
                reachable -> true
                else -> null
            }
        }

        assertEquals(listOf(reachable, unknown, missed), ranked)
        assertEquals(listOf(missed, unknown, reachable), selectTopTransitChoices(choices))
    }

    @Test
    fun missingWalkingLegTimesDoNotBecomeZero() {
        assertNull(sumKnownWalkingSeconds(emptyList()))
        assertNull(sumKnownWalkingSeconds(listOf(null)))
        assertNull(sumKnownWalkingSeconds(listOf(120, null, 180)))
        assertNull(sumKnownWalkingSeconds(listOf(120, -1)))
        assertNull(sumKnownWalkingSeconds(listOf(Int.MAX_VALUE, 1)))
        assertEquals(300, sumKnownWalkingSeconds(listOf(120, 180)))
        assertEquals(0, sumKnownWalkingSeconds(listOf(0)))
    }

    @Test
    fun stationCandidatesRequireActualCategoryMetadata() {
        fun place(name: String, code: String? = null, category: String? = null) =
            KakaoPlace("1", name, "서울", MapPoint(126.97, 37.55), code, category)

        assertTrue(place("서울역", code = "SW8").isTransitStop)
        assertTrue(place("경희대학교", category = "교통,수송 > 교통시설 > 버스정류장").isTransitStop)
        assertTrue(place("시청역", category = "교통,수송 > 지하철역,전철역").isTransitStop)
        assertFalse(place("서울역 카페", category = "음식점 > 카페").isTransitStop)
        assertFalse(place("서울역 버스정류장").isTransitStop)
    }

    @Test
    fun providerMetadataDoesNotInventOfficialStopIdentity() {
        val choice = route("1호선").copy(boardingStopId = "kakao-stop", transitLineId = "kakao-line")

        assertNull(choice.officialBoardingStopId)
        assertNull(choice.directionName)
        assertNull(choice.totalWalkingSeconds)
        assertEquals("kakao-stop", choice.boardingStopId)
    }
}
