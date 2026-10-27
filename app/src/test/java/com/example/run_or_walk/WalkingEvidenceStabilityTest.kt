package com.example.run_or_walk

import org.junit.Assert.*
import org.junit.Test

class WalkingEvidenceStabilityTest {
    private val now=1_800_000_000_000L
    private val point=MapPoint(127.0,37.0)
    private val end=MapPoint(127.0,37.0054)
    private val id=OfficialBoardingIdentity("seoul-bus","105900050","105900003","4","105900003")
    private fun fix(seconds: Int=0,meters:Double=0.0,accuracy:Double=3.0)=
        GuidanceLocation(point.copy(y=point.y+meters/111195.0),accuracy,now+seconds*1000,1.3,0.2)
    private fun tracker()=WalkingRouteTracker(point,KakaoWalkingRoute(600,500,listOf(point,end)),now)
    private fun previous()=LiveWalkingEvidence(fix(),GuidanceWalkingRoute(point,end,id,462,now,120000,600),600,500,listOf(point,end))
    private fun update(old:LiveWalkingEvidence,tracker:WalkingRouteTracker?,sample:GuidanceLocation?,at:Long)=
        updateLiveWalkingEvidence(old,tracker,sample,id,end,JourneySettings(),null,at)
    @Test fun uncertainFixDoesNotBlankAnUnexpiredWalkingPathOrClaimANewObservation() {
        val old=previous();val tracker=tracker()
        tracker.update(fix(),now)
        val result=update(old,tracker,fix(4,accuracy=100.0),now+4000)
        assertEquals(old.location,result.location)
        assertEquals(now,result.walking!!.fetchedAtEpochMillis)
        assertEquals(old.distanceMeters,result.distanceMeters)
        assertEquals(old.walking!!.durationSeconds,result.walking.durationSeconds)
    }
    @Test fun missingFixAndInaccuratePedestrianProjectionUseSettingsWithoutInventingSpeed() {
        val old=previous()
        assertEquals(old.walking!!.durationSeconds,update(old,tracker(),null,now+4000).walking!!.durationSeconds)
        val result=update(old,tracker(),fix(4,3.0,30.0).copy(speedMetersPerSecond=null,speedAccuracyMetersPerSecond=null),now+4000)
        assertNull(result.location!!.speedMetersPerSecond)
        assertEquals(JourneySettings().walkingSeconds(600,500,null),result.walking!!.durationSeconds)
    }
    @Test fun routeProgressUsesActualGeometryWhileStationaryTimeNeverShrinksDistance() {
        val tracker=tracker()
        val first=update(previous(),tracker,fix(),now)
        val moved=update(first,tracker,fix(8,10.4),now+8000)
        assertTrue(moved.distanceMeters!!<first.distanceMeters!!)
        val stopped=update(moved,tracker,fix(20,10.4).copy(speedMetersPerSecond=0.0),now+20000)
        assertEquals(moved.distanceMeters,stopped.distanceMeters)
        assertEquals(moved.walking!!.fetchedAtEpochMillis,stopped.walking!!.fetchedAtEpochMillis)
    }
    @Test fun gpsJumpDoesNotEraseFreshEvidenceButRealOffRouteOrExpiryDoes() {
        val tracker=tracker();tracker.update(fix(),now)
        val jumped=update(previous(),tracker,fix(4,300.0).copy(speedMetersPerSecond=30.0),now+4000)
        assertEquals(point,jumped.location!!.point);assertNotNull(jumped.walking)
        val off=fix(20,10.0).copy(point=point.copy(x=point.x+0.001))
        val offTracker=tracker();offTracker.update(fix(),now)
        assertNull(update(previous(),offTracker,off,now+20000).walking)
        assertNull(update(previous(),tracker(),fix(124),now+124000).walking)
    }
    @Test fun changedBoardingIdentityCannotReusePreviousWalkingEvidence() {
        val result=updateLiveWalkingEvidence(previous(),null,fix(4),id.copy(directionId="5"),end,JourneySettings(),null,now+4000)
        assertNull(result.walking)
    }
    @Test fun staleFixDoesNotBecomeFreshThroughRefreshAndDefaultSpeedIsIndependentOfUiZero() {
        val result=update(previous(),null,null,now+31000)
        assertNull(result.location)
        assertEquals(now,result.walking!!.fetchedAtEpochMillis)
        val settings=JourneySettings(speedKmh=3.24)
        assertEquals(200,settings.walkingSeconds(180,180,null))
        assertEquals(200,settings.walkingSeconds(180,180,0.0))
    }
}
