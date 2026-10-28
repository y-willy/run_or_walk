package com.example.run_or_walk

import org.junit.Assert.*
import org.junit.Test

class WalkingAccessRangeRegressionTest {
    private val now=1_800_000_000_000L
    private val origin=MapPoint(127.0,37.0)
    private val boarding=MapPoint(127.0,37.04)
    private val id=OfficialBoardingIdentity("seoul-bus","105900050","105900003","4","105900003")
    @Test fun fourKilometersMustNotBeRejectedBeforeRequestingTheWalkingProvider() {
        assertFalse(requiresTransitAccess(origin,boarding))
    }
    @Test fun longWalkingTimeWithinThirtyKilometersMustNotBeTreatedAsMissingRoute() {
        assertFalse(requiresTransitAccess(origin,origin.copy(y=37.18),walkingSeconds=18000))
        assertTrue(requiresTransitAccess(origin,origin.copy(y=37.28)))
    }
    @Test fun receivedWalkingDistanceAndTimeMustReachForegroundAndBackgroundCalculator() {
        val request=JourneyMonitoringRequest("승차점",boarding,id,
            busStop=OfficialSeoulBusStop(id.stopId,"06510","승차점",boarding),
            busRoute=OfficialSeoulBusRoute(id.stopId,id.routeId,"동대문01","4","회기역","2"))
        val eta=RealtimeArrivalSnapshot(id,now+300000,fetchedAtEpochMillis=now,validForMillis=600000,receivedAtEpochMillis=now)
        val location=GuidanceLocation(origin,5.0,now)
        val walk=GuidanceWalkingRoute(origin,boarding,id,3600,now,120000,5000)
        val assessment=calculateMovementGuidance(AutoGuidanceInput(location,GuidanceBoarding(id,boarding,true),walk,eta,true),now,request.settings)
        assertEquals(AutoGuidanceStatus.NEXT_VEHICLE,assessment.status)
        val background=monitoringStateWithSettings(JourneyMonitoringState(true,request,location=location,walkingRoute=walk,
            walkingDistanceMeters=5000,providerWalkingSeconds=3600,arrivals=eta),request,now,true)
        assertEquals(AutoGuidanceStatus.NEXT_VEHICLE,background.assessment!!.status)
        assertNotNull(background.assessment.walkingSeconds)
    }

    private fun screen():JourneyScreenState = JourneyScreenState(FeatureMode.ROUTE).apply {
        boarding=JourneyPlace("boarding","승차점","",this@WalkingAccessRangeRegressionTest.boarding)
        departure.selected=boarding
        confirmedServiceIdentity=id
        location=GuidanceLocation(origin,5.0,now)
        mapAccessDestination=boarding!!.point
        mapAccessOrigin=origin
        mapAccessWalking=WalkingRouteTracker(origin,KakaoWalkingRoute(5000,3600,listOf(origin,boarding!!.point)),now-10000)
    }
    @Test fun actualMapWalkingMetricsAreReusedWithoutRenewingTheirReceiptTime() {
        val screen=screen()
        assertTrue(screen.reuseMapWalkingForBoarding(screen.location!!,JourneySettings(),null,now))
        assertEquals(5000,screen.walkRoute!!.distanceMeters)
        assertEquals(3600,screen.walkRoute!!.durationSeconds)
        assertEquals(now-10000,screen.guidanceWalkingRoute!!.fetchedAtEpochMillis)
        assertEquals(JourneySettings().walkingSeconds(5000,3600),screen.guidanceWalkingRoute!!.durationSeconds)
    }
    @Test fun bluePathToAnotherDepartureCannotBecomeWalkingTimeToTheActualBoardingStop() {
        val screen=screen()
        screen.boarding=screen.boarding!!.copy(point=boarding.copy(y=37.041))
        assertFalse(screen.reuseMapWalkingForBoarding(screen.location!!,JourneySettings(),null,now))
        assertNull(screen.walkRoute);assertNull(screen.guidanceWalkingRoute)
    }
    @Test fun staleOrReferenceMapGeometryMustNotReappearAsFreshWalkingEvidence() {
        val screen=screen()
        screen.mapAccessIsReference=true
        assertFalse(screen.reuseMapWalkingForBoarding(screen.location!!,JourneySettings(),null,now))
        screen.mapAccessIsReference=false
        assertFalse(screen.reuseMapWalkingForBoarding(screen.location!!.copy(observedAtEpochMillis=now+121000),JourneySettings(),null,now+121000))
        assertNull(screen.walkRoute)
    }
    @Test fun departureChangeCannotReuseThePreviousBluePathsWalkingMetrics() {
        val screen=screen()
        screen.departure.selected=screen.departure.selected!!.copy(point=boarding.copy(y=37.042))
        assertFalse(screen.reuseMapWalkingForBoarding(screen.location!!,JourneySettings(),null,now))
    }
}
