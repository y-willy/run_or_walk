package com.example.run_or_walk

import org.junit.Assert.*
import org.junit.Test

class MapAccessDisplayRegressionTest {
    private val now=1_800_000_000_000L
    private val start=MapPoint(126.985,37.569)
    private val end=MapPoint(126.9876,37.5686)
    private val path=KakaoWalkingRoute(300,240,listOf(start,start.copy(y=37.5691),end),start,end)
    private val fix=GuidanceLocation(start,5.0,now)
    private val stop=OfficialSeoulBusStop("100000004","01004","종로2가.삼일교",end)
    private val route=OfficialSeoulBusRoute(stop.stopId,"100100585","N37","81","복정역환승센터","15")
    private fun screen()=JourneyScreenState(FeatureMode.ROUTE).apply {
        boarding=this@MapAccessDisplayRegressionTest.stop.toJourneyPlace();departure.selected=boarding
        boardingMatchedAutomatically=true;confirmedServiceIdentity=route.identity
    }
    @Test fun aSelectedMapLegCanFetchWhileAnUnrelatedWalkingRequestIsLoading() {
        val screen=screen()
        screen.loading=true
        assertTrue(screen.busy)
        assertTrue(screen.canFetchMapAccess())
        screen.accessTransitLoading=true
        assertFalse("Reuse the in-flight feeder query rather than duplicating it",screen.canFetchMapAccess())
    }
    @Test fun anUnselectedOrRetiredDepartureDoesNotTriggerAnAccessRequest() {
        val screen=screen()
        screen.boarding=null
        assertFalse(screen.canFetchMapAccess())
        screen.boarding=this.stop.toJourneyPlace();screen.departure.selected=null
        assertFalse(screen.canFetchMapAccess())
    }
    @Test fun aValidWalkingResponseIsDrawnBeforeTheSeparateMapJobRuns() {
        val screen=screen()
        screen.location=fix
        screen.storeWalkingResult(fix,screen.boarding!!,route.identity,path,JourneySettings(),null,now,now)
        screen.loading=true
        assertTrue(screen.mapAccessSegments.isEmpty())
        assertEquals(listOf(path.points),screen.mapAccessSegmentsForDisplay(fix,end,now))
        assertTrue("Display must not fabricate a new response or alter the map cache lifetime",screen.mapAccessSegments.isEmpty())
        assertEquals(now,screen.walkFetchedAtEpochMillis)
    }
    @Test fun foregroundToServiceHandoverDoesNotRemoveTheBlueAccessCoordinates() {
        val screen=screen()
        val request=JourneyMonitoringRequest(stop.name,end,route.identity,busStop=stop,busRoute=route)
        val service=JourneyMonitoringState(true,request,location=fix,walkingGeometry=WalkingRouteTracker(start,path,now))
        assertNull(screen.walkingTracker)
        assertEquals(listOf(path.points),screen.mapAccessSegmentsForDisplay(fix,end,now,service))
        assertTrue(journeyMapPaths(end,emptyList(),screen.mapAccessSegmentsForDisplay(fix,end,now,service),listOf(listOf(end,end.copy(y=37.50))))
            .any {it.color==0xFF2563EB.toInt()})
    }
    @Test fun aDifferentMonitoredJourneyCannotSupplyBlueGeometry() {
        val screen=screen()
        val wrongRoute=route.copy(stopOrder="82")
        val request=JourneyMonitoringRequest(stop.name,end,wrongRoute.identity,busStop=stop,busRoute=wrongRoute)
        val service=JourneyMonitoringState(true,request,location=fix,walkingGeometry=WalkingRouteTracker(start,path,now))
        assertTrue(screen.mapAccessSegmentsForDisplay(fix,end,now,service).isEmpty())
    }
    @Test fun expiredServiceGeometryAndPreviousDepartureCoordinatesStayExcluded() {
        val screen=screen()
        val request=JourneyMonitoringRequest(stop.name,end,route.identity,busStop=stop,busRoute=route)
        val service=JourneyMonitoringState(true,request,location=fix,walkingGeometry=WalkingRouteTracker(start,path,now-120001))
        assertTrue(screen.mapAccessSegmentsForDisplay(fix,end,now,service).isEmpty())
        screen.mapAccessSegments=listOf(path.points);screen.mapAccessDestination=start
        assertTrue(screen.mapAccessSegmentsForDisplay(fix,end,now).isEmpty())
    }
    @Test fun matchingCachedAccessCoordinatesSurviveARefreshWithoutCreatingAReplacement() {
        val screen=screen()
        screen.mapAccessSegments=listOf(path.points);screen.mapAccessDestination=end
        screen.mapAccessIsReference=true
        assertEquals(listOf(path.points),screen.mapAccessSegmentsForDisplay(null,end,now))
        assertTrue(screen.mapAccessIsReference)
        assertNull(screen.walkFetchedAtEpochMillis)
    }
}
