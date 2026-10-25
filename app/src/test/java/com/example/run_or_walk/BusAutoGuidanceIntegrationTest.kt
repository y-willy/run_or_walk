package com.example.run_or_walk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Exercise the Worker contract, actual vehicle selection and walking decision together. */
class BusAutoGuidanceIntegrationTest {
    private val now = Instant.parse("2026-10-25T08:00:10Z").toEpochMilli()
    private val route = OfficialSeoulBusRoute("100000001", "100100001", "100", "12", "서울역 방면", "3")
    private val point = MapPoint(126.98, 37.57)
    private fun response(): String = requireNotNull(javaClass.classLoader)
        .getResourceAsStream("seoul_bus_worker_response.json")!!.bufferedReader().use { it.readText() }

    private fun assessment(arrivals: List<OfficialSeoulBusArrival>, walkingSeconds: Int,
        walkIdentity: OfficialBoardingIdentity = route.identity): AutoGuidanceAssessment {
        val selected = chooseMatchingBusArrival(route.identity, arrivals, now)
        return calculateAutoGuidance(AutoGuidanceInput(
            GuidanceLocation(point, 10.0, now), GuidanceBoarding(route.identity, point, true),
            GuidanceWalkingRoute(point, point, walkIdentity, walkingSeconds, now, 120_000L,
                (walkingSeconds * 1.25).toInt()),
            selected?.snapshot, locationPermissionGranted = true), now)
    }

    @Test fun actualWorkerRowsProduceWalkingFastWalkingAndConfirmedNextBusDecisions() {
        val arrivals = parseWorkerSeoulBusArrivals(response(), route, now)
        assertEquals(2, arrivals.size)
        assertEquals(AutoGuidanceStatus.WALK, assessment(arrivals, 100).status)
        assertEquals(AutoGuidanceStatus.FAST_WALK, assessment(arrivals, 200).status)
        val next = assessment(arrivals, 300)
        assertEquals(AutoGuidanceStatus.NEXT_VEHICLE, next.status)
        assertEquals(Instant.parse("2026-10-25T08:10:00Z").toEpochMilli(), next.nextArrivalAtEpochMillis)
    }

    @Test fun fullOrUnconfirmedNextBusCannotBecomeACatchableNextRecommendation() {
        val body = JSONObject(response())
        body.getJSONArray("arrivals").getJSONObject(1).put("full", true)
        val arrivals = parseWorkerSeoulBusArrivals(body.toString(), route, now)
        val unknown = assessment(arrivals, 300)
        assertEquals(AutoGuidanceStatus.UNKNOWN, unknown.status)
        assertEquals(AutoGuidanceUnknownReason.NEXT_ARRIVAL_UNAVAILABLE, unknown.unknownReason)
        body.getJSONArray("arrivals").getJSONObject(0).put("full", true)
        assertEquals(AutoGuidanceStatus.UNKNOWN,
            assessment(parseWorkerSeoulBusArrivals(body.toString(), route, now), 100).status)
    }

    @Test fun oppositeDirectionWalkAndExpiredSourceNeverAuthorizeBusGuidance() {
        val arrivals = parseWorkerSeoulBusArrivals(response(), route, now)
        assertEquals(AutoGuidanceUnknownReason.WALKING_MISMATCH,
            assessment(arrivals, 100, route.identity.copy(directionId = "45")).unknownReason)
        val old = parseWorkerSeoulBusArrivals(response(), route, now + 60_000L)
        assertNull(chooseMatchingBusArrival(route.identity, old, now + 60_000L))
    }
}
