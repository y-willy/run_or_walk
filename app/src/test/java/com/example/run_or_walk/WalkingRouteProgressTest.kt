package com.example.run_or_walk

import org.junit.Assert.*
import org.junit.Test

class WalkingRouteProgressTest {
    private val now=1_800_000_000_000L
    private val origin=MapPoint(127.0,37.0)
    private fun point(meters: Double) = MapPoint(origin.x,origin.y+meters/111195.0)
    private fun fix(meters: Double,seconds: Int,speed: Double=1.2) = GuidanceLocation(point(meters),3.0,now+seconds*1000,speed,0.2)
    private fun tracker()=WalkingRouteTracker(origin,KakaoWalkingRoute(600,500,listOf(origin,point(600.0))),now)
    @Test fun realProgressReducesDistanceButWaitingAtSamePointNeverDoes() {
        val tracker=tracker()
        val first=tracker.update(fix(0.0,0,0.0),now)!!
        val moved=tracker.update(fix(10.0,8),now+8000)!!
        val stopped=tracker.update(fix(10.0,60,0.0),now+60000)!!
        assertTrue(moved.remainingMeters < first.remainingMeters)
        assertEquals(moved,stopped)
    }
    @Test fun jitterOldResponsesAndVehicleJumpDoNotAdvanceThePath() {
        val tracker=tracker()
        tracker.update(fix(0.0,0,0.0),now)
        assertEquals(600,tracker.update(fix(1.0,4,0.0),now+4000)!!.remainingMeters)
        assertEquals(600,tracker.update(fix(300.0,8,30.0),now+8000)?.remainingMeters ?: 600)
        assertFalse(shouldRefreshWalkingRoute(tracker,fix(300.0,20,30.0),now+20000,now))
        assertNull(tracker.update(fix(8.0,5),now+9000))
        assertNull(tracker.update(fix(8.0,0),now+31000))
    }
    @Test fun apiRequestsAreThrottledAndTriggeredByMeaningfulMovementOffRouteOrExpiry() {
        val tracker=tracker()
        assertTrue(shouldRefreshWalkingRoute(null,fix(0.0,0),now,0))
        assertFalse(shouldRefreshWalkingRoute(tracker,fix(0.0,4),now+4000,now))
        assertFalse(shouldRefreshWalkingRoute(tracker,fix(3.0,20),now+20000,now))
        assertTrue(shouldRefreshWalkingRoute(tracker,fix(80.0,80),now+80000,now))
        val off=fix(10.0,32).copy(point=MapPoint(origin.x+0.002,origin.y))
        assertTrue(shouldRefreshWalkingRoute(tracker,off,now+32000,now))
        assertFalse(tracker.update(off,now+32000)!!.onRoute)
        assertTrue(shouldRefreshWalkingRoute(tracker,fix(0.0,92),now+92000,now))
        assertNull(tracker.update(fix(0.0,124),now+124000))
    }
    @Test fun changingBoardingRouteStartsWithoutPreviousProgress() {
        val old=tracker(); old.update(fix(0.0,0),now); old.update(fix(20.0,12),now+12000)
        val new=WalkingRouteTracker(point(20.0),KakaoWalkingRoute(300,250,listOf(point(20.0),point(320.0))),now+12000)
        assertEquals(300,new.update(fix(20.0,12,0.0),now+12000)!!.remainingMeters)
        assertTrue(old.update(fix(20.0,16,0.0),now+16000)!!.remainingMeters < 600)
    }
    @Test fun frequentSlowGpsFixesAccumulateMovementInsteadOfBeingMistakenForStandingStill() {
        val tracker=tracker()
        tracker.update(fix(0.0,0,0.0),now)
        tracker.update(fix(1.8,2,0.9),now+2000)
        val progress=tracker.update(fix(3.6,4,0.9),now+4000)!!
        assertTrue(progress.remainingMeters < 600)
    }

}
