package com.example.run_or_walk

import org.junit.Assert.*
import org.junit.Test

class MapDeparturePathRegressionTest {
    private val now=1_800_000_000_000L
    private val gps=MapPoint(127.0,37.0)
    private val departure=MapPoint(127.001,37.001)
    private val boarding=MapPoint(127.0012,37.0011)
    private val corner=MapPoint(126.999,37.002)
    private val destination=MapPoint(127.05,37.1)

    @Test fun blueAccessCoordinatesRemainInTheViewportWhenBoardingAndGreenItineraryExist() {
        val access=listOf(listOf(gps,corner,departure))
        val green=listOf(listOf(departure,boarding,destination))
        val platformWalk=listOf(gps,boarding)
        // This was the previous map invocation: boarding made it ignore access geometry.
        assertFalse(walkingMapViewportPoints(platformWalk,gps,boarding,access+green).contains(corner))
        val bounds=walkingMapViewportPoints(platformWalk,gps,boarding,green,access,departure)
        assertTrue(bounds.containsAll(access.flatten()))
        assertTrue(bounds.contains(boarding))
        assertFalse("Keep the access leg readable instead of zooming out to a remote final destination",bounds.contains(destination))
    }

    @Test fun departurePinIsIncludedWhileTheActualAccessRouteIsStillLoading() {
        val bounds=walkingMapViewportPoints(emptyList(),gps,boarding,emptyList(),emptyList(),departure)
        assertTrue(bounds.containsAll(listOf(gps,departure,boarding)))
        assertTrue(journeyMapPaths(departure,emptyList(),emptyList(),emptyList()).isEmpty())
    }

    @Test fun overlappingGreenRouteCannotCoverTheBlueAccessRoute() {
        val shared=listOf(gps,departure)
        val lines=journeyMapPaths(departure,emptyList(),listOf(shared),listOf(shared))
        val green=lines.single {it.color==0xFF16A34A.toInt()}
        val blue=lines.single {it.color==0xFF2563EB.toInt()}
        assertTrue(blue.zOrder>green.zOrder)
        assertTrue("Both colours remain visible on a shared road",blue.width<green.width)
        assertEquals(shared,blue.points)
        assertEquals(shared,green.points)
    }

    private fun walkingScreen(source:KakaoWalkingRoute)=JourneyScreenState(FeatureMode.ROUTE).apply {
        val place=JourneyPlace("departure","출발지","",this@MapDeparturePathRegressionTest.departure)
        this.boarding=place;this.departure.selected=place
        location=GuidanceLocation(gps,5.0,now)
        assertTrue(storeWalkingResult(location!!,place,null,source,JourneySettings(),null,now-10_000,now))
    }

    @Test fun realRequestBoundSnappedGeometryIsReusedWithoutAnotherQueryOrFakeConnector() {
        val snapped=departure.copy(y=departure.y+0.000485) // about 54m, rejected by the old 20m check
        val source=KakaoWalkingRoute(250,200,listOf(gps,corner,snapped),gps,departure)
        val screen=walkingScreen(source)
        assertTrue(pedestrianDistance(source.points.last(),departure)>20.0)
        assertTrue(screen.reuseWalkingForMapAccess(screen.location!!,departure,now))
        assertEquals(listOf(source.points),screen.mapAccessSegments)
        assertEquals(now-10_000,screen.mapAccessWalking!!.fetchedAt)
        assertFalse(screen.mapAccessIsReference)
        assertEquals(snapped,screen.mapAccessSegments.single().last())
        assertFalse(screen.mapAccessSegments.single().contains(departure))
        assertNull(screen.guidanceWalkingRoute)
    }

    @Test fun anotherDepartureExpiredRouteOrDistantGpsCannotReuseTheOldBlueGeometry() {
        val screen=walkingScreen(KakaoWalkingRoute(250,200,listOf(gps,corner,departure),gps,departure))
        assertFalse(screen.reuseWalkingForMapAccess(screen.location!!,destination,now))
        assertFalse(screen.reuseWalkingForMapAccess(screen.location!!.copy(observedAtEpochMillis=now+121_000),departure,now+121_000))
        assertFalse(screen.reuseWalkingForMapAccess(screen.location!!.copy(point=gps.copy(x=127.01)),departure,now))
        assertTrue(screen.mapAccessSegments.isEmpty())
        assertNull(screen.mapAccessWalking)
    }

    @Test fun walkingToAnotherBoardingStopDoesNotBecomeTheGpsToDepartureRoute() {
        val screen=walkingScreen(KakaoWalkingRoute(250,200,listOf(gps,corner,departure),gps,departure))
        screen.departure.selected=JourneyPlace("other","다른 출발지","",destination)
        assertFalse(screen.reuseWalkingForMapAccess(screen.location!!,destination,now))
        assertTrue(screen.mapAccessSegments.isEmpty())
    }

    @Test fun stopOnlyMapKeepsItsExistingWalkingLineAndInvalidGeometryDoesNotCreateLines() {
        val walk=listOf(gps,boarding)
        val lines=journeyMapPaths(null,walk,emptyList(),emptyList())
        assertEquals(1,lines.size)
        assertEquals(walk,lines.single().points)
        assertEquals(0xFF14B8A6.toInt(),lines.single().color)
        assertTrue(journeyMapPaths(departure,walk,listOf(listOf(gps)),emptyList()).isEmpty())
    }
}
