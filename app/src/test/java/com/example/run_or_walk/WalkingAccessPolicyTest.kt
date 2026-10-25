package com.example.run_or_walk

import org.junit.Assert.*
import org.junit.Test

class WalkingAccessPolicyTest {
    private val origin = MapPoint(127.0, 37.5)
    private val destination = MapPoint(127.001, 37.5)
    private val valid = KakaoWalkingRoute(110, 90, listOf(origin, destination))
    @Test fun acceptsOnlyActualGeometryConnectingGpsToTheSelectedBoardingPoint() {
        assertTrue(validWalkingAccess(valid, origin, destination))
        assertFalse(validWalkingAccess(valid, destination, origin))
        assertFalse(validWalkingAccess(valid.copy(points = listOf(origin)), origin, destination))
        assertFalse(validWalkingAccess(valid.copy(points = listOf(origin, MapPoint(Double.NaN, 37.5))), origin, destination))
    }
    @Test fun zeroOrImplausiblyShortDistancesCannotTeleportToTheBoardingPoint() {
        assertFalse(validWalkingAccess(valid.copy(distanceMeters = 0), origin, destination))
        assertFalse(validWalkingAccess(valid.copy(durationSeconds = 0), origin, destination))
        assertFalse(validWalkingAccess(valid.copy(distanceMeters = 1), origin, destination))
        assertTrue(validWalkingAccess(KakaoWalkingRoute(0, 0, listOf(origin, origin)), origin, origin))
    }
    @Test fun aNearbyButWrongEndpointDoesNotBecomeAGuidanceBinding() {
        val wrong = MapPoint(destination.x, destination.y + .001)
        assertFalse(validWalkingAccess(valid.copy(points = listOf(origin, wrong)), origin, destination))
    }
}
