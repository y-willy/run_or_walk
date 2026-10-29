package com.example.run_or_walk

import org.junit.Assert.*
import org.junit.Test

class ImmediateGuidanceBindingTest {
    private val now = 1_800_000_000_000L
    private val start = MapPoint(127.0, 37.0)
    private val end = MapPoint(127.0, 37.0027)
    private val route = OfficialSeoulBusRoute("105900050", "105900003", "동대문01", "4", "회기역", "2")
    private val settings = JourneySettings()
    private fun screen() = JourneyScreenState(FeatureMode.ROUTE).apply {
        boarding = JourneyPlace("boarding", "승차점", "", end, categoryName = "버스정류장")
        boardingMatchedAutomatically = true
        location = GuidanceLocation(start, 30.0, now, 0.0, 0.2)
        walkRoute = WalkRoute(300, 250)
        walkOrigin = start; walkFetchedAtEpochMillis = now; walkingPoints = listOf(start, end)
        selectedBusStop = OfficialSeoulBusStop(route.stopId, "06510", "승차점", end)
        selectedBusRoute = route
    }

    @Test fun officialIdentityCompletingUsesDisplayedWalkImmediatelyWithoutAnotherGpsOrHttp() {
        val screen = screen()
        assertNotNull(screen.walkingDisplay(screen.location, settings, null, now))
        assertNull(screen.guidanceWalkingRoute)
        assertNull(screen.guidanceWalkingAt(screen.location, route.identity, settings, null, now))
        screen.confirmedServiceIdentity = route.identity
        val walking = screen.guidanceWalkingAt(screen.location, route.identity, settings, null, now)!!
        assertNull(screen.guidanceWalkingRoute) // Pure binding does not wait for/store an effect result.
        assertEquals(231, walking.durationSeconds)
        val arrivals = RealtimeArrivalSnapshot(route.identity, now + 600_000L,
            fetchedAtEpochMillis = now, validForMillis = 60_000L, receivedAtEpochMillis = now)
        val assessment = calculateMovementGuidance(AutoGuidanceInput(screen.location,
            GuidanceBoarding(route.identity, end, true), walking, arrivals, true), now, settings)
        assertEquals(AutoGuidanceStatus.RELAXED, assessment.status)
        // A position usable for guidance may be too imprecise for geometry progress.
        // Rejecting progress must not remove its already validated distance/time.
        val retained = updateLiveWalkingEvidence(LiveWalkingEvidence(screen.location, walking,
            300, 250, screen.walkingPoints), null, screen.location, route.identity, end, settings, null, now)
        assertEquals(walking, retained.walking)
        assertEquals(300, retained.distanceMeters)
    }

    @Test fun changingDirectionOrExpiredGeometryCannotBeReboundToTheCurrentService() {
        val screen = screen().apply { confirmedServiceIdentity = route.identity }
        assertNull(screen.guidanceWalkingAt(screen.location, route.identity.copy(directionId = "5"), settings, null, now))
        assertNull(screen.guidanceWalkingAt(screen.location, route.identity, settings, null, now + 121_000))
        screen.boarding = screen.boarding!!.copy(point = end.copy(x = end.x + 0.01))
        assertNull(screen.guidanceWalkingAt(screen.location, route.identity, settings, null, now))
    }

    @Test fun missingBoardingConnectionExplainsWhyInsteadOfUsingTheGenericTitle() {
        assertEquals("승차 장소·방향을 확인해 주세요",
            unknownGuidanceTitle(AutoGuidanceUnknownReason.BOARDING_UNCONFIRMED, "이동 정보 확인 필요"))
    }
}
