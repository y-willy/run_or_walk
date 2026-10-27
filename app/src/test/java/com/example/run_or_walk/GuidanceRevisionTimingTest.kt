package com.example.run_or_walk
import org.junit.Assert.*
import org.junit.Test
class GuidanceRevisionTimingTest {
 @Test fun everyMillisecondBoundaryIsExactBeforeAnyDisplayRounding() {
  for((ms,status) in listOf(240001L to AutoGuidanceStatus.RELAXED,240000L to AutoGuidanceStatus.WALK,120001L to AutoGuidanceStatus.WALK,120000L to AutoGuidanceStatus.CAUTION,1L to AutoGuidanceStatus.CAUTION,0L to AutoGuidanceStatus.FAST_WALK,-90000L to AutoGuidanceStatus.FAST_WALK,-90001L to AutoGuidanceStatus.NEXT_VEHICLE)) assertEquals(status,movementStatusForSlackMillis(ms))
 }
 @Test fun actualPulseGateBlocksAllPathsUrgencyAnd29900MsButAllowsThirtySeconds() {
  val shared=ActualVibrationGate();var count=0
  fun screen(at:Long)=shared.emit(at) {count++;true}
  fun service(at:Long)=shared.emit(at) {count++;true}
  assertTrue(screen(1000));assertFalse(service(11000));assertFalse(screen(30900));assertFalse(service(30999))
  assertTrue(service(31000));assertEquals(2,count)
 }
 @Test fun unsupportedOrFailedVibrationDoesNotConsumeCooldownAndNoQueueExists() {
  val gate=ActualVibrationGate();var count=0
  assertFalse(gate.emit(0) {false});assertTrue(gate.emit(1) {count++;true})
  assertFalse(gate.emit(10000) {count++;true});assertEquals(1,count)
  // Time passing alone invokes no callback.
  assertEquals(1,count);assertTrue(gate.emit(30001) {count++;true});assertEquals(2,count)
 }
 @Test fun walkingRequestGateReusesSameSelectionAndResetsForANewSelection() {
  val gate=WalkingRefreshCadence()
  assertTrue(gate.tryRequest(0));assertFalse(gate.tryRequest(29999));assertTrue(gate.tryRequest(30000));assertFalse(gate.tryRequest(30000))
  assertTrue(WalkingRefreshCadence().tryRequest(1))
 }
 @Test fun distanceAndWalkingTimeStayVisibleUntilThirtySecondCalculation() {
  val now=1800000000000L;val origin=MapPoint(127.0,37.0)
  val end=origin.copy(y=37.003867);val id=OfficialBoardingIdentity("seoul-bus","105900050","105900003","4","105900003")
  val route=KakaoWalkingRoute(430,360,listOf(origin,end));val tracker=WalkingRouteTracker(origin,route,now)
  val settings=JourneySettings(useProviderWalkingTime=true)
  val initial=GuidanceLocation(origin,3.0,now,1.3,0.2)
  val seed=LiveWalkingEvidence(initial,GuidanceWalkingRoute(origin,end,id,360,now,120000,430),430,360,route.points,now)
  tracker.update(initial,now)
  val sample=initial.copy(point=origin.copy(y=origin.y+25.0/111195.0),observedAtEpochMillis=now+20000)
  val waiting=updateLiveWalkingEvidence(seed,tracker,sample,id,end,settings,null,now+20000,allowRecalculation=false)
  assertEquals(430,waiting.distanceMeters);assertEquals(360,waiting.walking!!.durationSeconds);assertEquals(now,waiting.calculatedAtEpochMillis)
  val next=updateLiveWalkingEvidence(waiting,tracker,sample.copy(observedAtEpochMillis=now+30000,speedMetersPerSecond=0.0),id,end,settings,null,now+30000,allowRecalculation=true)
  assertTrue(next.distanceMeters!!<430);assertTrue(next.walking!!.durationSeconds<360)
  assertEquals(now,next.walking.fetchedAtEpochMillis);assertEquals(now+30000,next.calculatedAtEpochMillis)
 }
 @Test fun informationPulseDescriptionMatchesAllFiveActualPatterns() {
  assertEquals(listOf(1,1,2,2,3,0),AutoGuidanceStatus.entries.map(::movementPulseCount))
  assertEquals(5,guidanceBands.size);assertTrue(guidanceDataSources.all { it.url.startsWith("https://") })
 }
 @Test fun genuineBacktrackingInvalidatesAnOverlyOptimisticCachedDistance() {
  val now=1800000000000L;val origin=MapPoint(127.0,37.0);val end=origin.copy(y=37.0054)
  val id=OfficialBoardingIdentity("seoul-bus","105900050","105900003","4","105900003")
  val route=KakaoWalkingRoute(600,500,listOf(origin,end));val tracker=WalkingRouteTracker(origin,route,now)
  fun fix(meters:Double,seconds:Int)=GuidanceLocation(origin.copy(y=origin.y+meters/111195.0),3.0,now+seconds*1000,1.3,0.2)
  val seed=LiveWalkingEvidence(fix(0.0,0),GuidanceWalkingRoute(origin,end,id,462,now,120000,600),600,500,route.points,now)
  tracker.update(fix(0.0,0),now)
  val moved=updateLiveWalkingEvidence(seed,tracker,fix(25.0,20),id,end,JourneySettings(),null,now+20000)
  val back=updateLiveWalkingEvidence(moved,tracker,fix(10.0,30),id,end,JourneySettings(),null,now+30000,allowRecalculation=false)
  assertTrue(back.distanceMeters!!>moved.distanceMeters!!)
  assertEquals(now,back.walking!!.fetchedAtEpochMillis)
 }

}
