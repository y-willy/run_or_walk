package com.example.run_or_walk

import org.junit.Assert.*
import org.junit.Test

class GuidanceUnknownReproductionTest {
    private val now=1_800_000_000_000L
    private val origin=MapPoint(127.0,37.0)
    private fun point(north:Double=0.0,east:Double=0.0)=MapPoint(
        origin.x+east/(111195.0*kotlin.math.cos(Math.toRadians(origin.y))),origin.y+north/111195.0)
    private val end=point(420.0)
    private val id=OfficialBoardingIdentity("seoul","1001000133","1001","상행","종각")
    private val settings=JourneySettings(useProviderWalkingTime=true,gpsAutoCalibrationEnabled=false)
    private fun fix(seconds:Int=0,north:Double=0.0,east:Double=0.0,accuracy:Double=3.0,speed:Double=0.0)=
        GuidanceLocation(point(north,east),accuracy,now+seconds*1000,speed,0.2)
    private fun tracker()=WalkingRouteTracker(origin,KakaoWalkingRoute(420,300,listOf(origin,end),origin,end),now)
    private fun prior()=LiveWalkingEvidence(fix(),GuidanceWalkingRoute(origin,end,id,300,now,120000,420),420,300,listOf(origin,end),now)

    @Test fun gpsUncertaintyOverlappingTheRouteDoesNotEraseValidWalk() {
        val tracker=tracker();tracker.update(fix(),now)
        val moved=updateLiveWalkingEvidence(prior(),tracker,fix(60,north=60.0,speed=1.0),id,end,settings,null,now+60000)
        val result=updateLiveWalkingEvidence(moved,tracker,fix(64,north=60.0,east=30.0,accuracy=15.0),id,end,settings,null,now+64000,false)
        assertNotNull("30m offset with 15m uncertainty does not prove a 20m route departure",result.walking)
        assertEquals(moved.distanceMeters,result.distanceMeters)
        assertEquals(moved.walking!!.durationSeconds,result.walking!!.durationSeconds)
    }

    @Test fun rejectedGpsSpikeCannotPoisonTheFollowingGoodFix() {
        val tracker=tracker();tracker.update(fix(),now)
        assertNull(tracker.update(fix(4,east=100.0,speed=1.3),now+4000))
        val recovered=tracker.update(fix(8,north=10.4,speed=1.3),now+8000)
        assertNotNull("Compare with the last accepted fix, not a rejected spike",recovered)
        assertTrue(recovered!!.onRoute)
    }

    @Test fun samePlatformDifferentTrainDestinationBindsPhysicalWalkWithoutWaitingForAnotherGps() {
        val screen=JourneyScreenState(FeatureMode.STOP).apply {
            boarding=JourneyPlace("boarding","종각역","",end,categoryGroupCode="SW8")
            boardingChosenByUser=true;confirmedServiceIdentity=id
            location=fix();walkRoute=WalkRoute(420,300);walkOrigin=origin;walkFetchedAtEpochMillis=now
            walkingTracker=tracker();walkingPoints=listOf(origin,end)
        }
        val nextTrain=id.copy(serviceVariant="소요산")
        assertNotNull(screen.walkingDisplay(screen.location,settings,null,now))
        assertNotNull("Physical access is unchanged for another train on the same platform",
            screen.guidanceWalkingAt(screen.location,nextTrain,settings,null,now))
        assertNull(screen.guidanceWalkingAt(screen.location,nextTrain.copy(directionId="하행"),settings,null,now))
    }

    @Test fun staleTrackerFromAnotherBoardingCannotPopulateCurrentWalkingEvidence() {
        val other=end.copy(x=end.x+0.001)
        val result=updateLiveWalkingEvidence(prior(),tracker(),fix(4),id.copy(stopId="other"),other,settings,null,now+4000)
        assertNull("A geometry endpoint within 150m alone is not a current request binding",result.walking)
    }

    @Test fun authoritativeDistanceAndTimeCannotBeDisplayedWhileTheCalculatorGetsANullDistance() {
        val stop=OfficialSubwayStation(id.stopId,id.routeId,"종각","1호선")
        val request=JourneyMonitoringRequest("종각",end,id,subwayStation=stop,settings=settings)
        val previous=JourneyMonitoringState(true,request,location=fix(),
            walkingRoute=prior().walking!!.copy(distanceMeters=null),walkingDistanceMeters=420,providerWalkingSeconds=300,
            arrivals=RealtimeArrivalSnapshot(id,now+420000,fetchedAtEpochMillis=now,validForMillis=60000,receivedAtEpochMillis=now))
        val result=monitoringStateWithSettings(previous,request,now,true)
        assertEquals(420,result.walkingRoute!!.distanceMeters)
        assertNotEquals(AutoGuidanceStatus.UNKNOWN,result.assessment!!.status)
    }
}
