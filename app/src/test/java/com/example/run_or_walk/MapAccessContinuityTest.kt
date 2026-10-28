package com.example.run_or_walk

import org.junit.Assert.*
import org.junit.Test

class MapAccessContinuityTest {
    @Test fun expiredGeometryRemainsReferenceDuringRefreshButCannotBecomeNewWalkingEvidence() {
        val screen=JourneyScreenState(FeatureMode.ROUTE)
        val from=MapPoint(127.0,37.0);val to=MapPoint(127.001,37.001)
        val paths=listOf(listOf(from,to))
        screen.mapAccessOrigin=from;screen.mapAccessDestination=to;screen.mapAccessSegments=paths
        screen.mapAccessRequests.begin(to,0,true);screen.mapAccessRequests.complete(true,1)
        screen.updateMapAccessPresentation(false,from,121001)
        assertEquals(paths,screen.mapAccessSegments);assertTrue(screen.mapAccessIsReference)
        assertNull(screen.guidanceWalkingRoute);assertNull(screen.walkFetchedAtEpochMillis)
        assertEquals(1L,screen.mapAccessRequests.lastSuccessElapsed)
        screen.mapAccessRequests.begin(to,121001,true)
        assertEquals(paths,screen.mapAccessSegments)
        screen.mapAccessRequests.complete(false,122000)
        screen.updateMapAccessPresentation(false,from,122000)
        assertEquals(paths,screen.mapAccessSegments)
        screen.updateMapAccessPresentation(false,from,300002)
        assertTrue(screen.mapAccessSegments.isEmpty());assertFalse(screen.mapAccessIsReference)
    }
    @Test fun leavingTheOldGeometryInvalidatesReferenceImmediately() {
        val screen=JourneyScreenState(FeatureMode.ROUTE)
        val from=MapPoint(127.0,37.0)
        screen.mapAccessOrigin=from;screen.mapAccessSegments=listOf(listOf(from,from.copy(y=37.001)))
        screen.updateMapAccessPresentation(false,from.copy(y=37.01),1)
        assertTrue(screen.mapAccessSegments.isEmpty());assertFalse(screen.mapAccessIsReference)
    }
}
