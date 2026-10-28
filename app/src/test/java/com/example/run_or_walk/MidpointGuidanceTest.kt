package com.example.run_or_walk
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class MidpointGuidanceTest {
 private val now=1800000000000L
 private val point=MapPoint(127.0,37.2)
 private val id=OfficialBoardingIdentity("seoul","station","1075","하행","service")
 private val date=LocalDate.of(2026,10,26)
 private val source=OfficialTimetableSource("공식","official","v1",now-86400000)
 private fun estimate(low:Int=180,high:Int=300,no:String="train")=OfficialTimetableEstimate(id,no,date,
  OfficialServiceDayType.WEEKDAY,OfficialTrainOperationType.LOCAL,"path","previous",2,now+240000,
  now+low*1000,now+high*1000,0,0,now,now,0,now+60000,source,"calendar")
 private fun input(walk:Int,distance:Int=200,first:OfficialTimetableEstimate=estimate(),next:OfficialTimetableEstimate?=null)=
  AutoGuidanceInput(GuidanceLocation(point,10.0,now),GuidanceBoarding(id,point,true),
   GuidanceWalkingRoute(point,point,id,walk,now,120000,distance),null,true,bufferSeconds=30,
   timetableArrivals=TimetableArrivalEvidence(id,first,next))
 @Test fun threeToFiveMinutesIsFourMinutesForBothDisplayAndDecision() {
  val e=estimate();assertEquals(now+240000,e.midpointArrivalAtEpochMillis())
  assertEquals("약 4분 후",subwayEstimateText(e,now))
  val value=calculateAutoGuidance(input(200),now)
  assertEquals(AutoGuidanceStatus.CAUTION,value.status) // Earliest180s would incorrectly require faster movement.
  assertEquals(now+240000,value.firstArrivalAtEpochMillis)
  assertEquals("놓칠 수 있어요",movementGuidanceTitle(value,now))
  assertFalse(subwayEstimateText(e,now).contains("~"))
 }
 @Test fun fiveThresholdMovementTitlesArePlainAndShared() {
  val run=calculateAutoGuidance(input(250,distance=200),now)
  assertEquals(AutoGuidanceStatus.FAST_WALK,run.status);assertEquals("뛰세요",movementGuidanceTitle(run,now))
  val next=calculateAutoGuidance(input(700,distance=1000),now)
  assertEquals("다음 차량 정보 확인 필요",movementGuidanceTitle(next,now))
  val relaxed=calculateAutoGuidance(input(100,first=estimate(420,540)),now)
  assertEquals("여유 있어요",movementGuidanceTitle(relaxed,now))
  val walk=calculateAutoGuidance(input(50),now)
  assertEquals("걸으세요",movementGuidanceTitle(walk,now))
 }
 @Test fun nextVehicleAlsoUsesItsMiddleArrivalAndIgnoresPassedMiddles() {
  val next=estimate(480,600,"next")
  val value=calculateAutoGuidance(input(360,600,estimate(),next),now)
  assertEquals(AutoGuidanceStatus.NEXT_VEHICLE,value.status)
  assertEquals(now+540000,value.recommendedArrivalAtEpochMillis)
  assertTrue(nextVehicleGuidanceText(value,now).contains("9분"))
  val row=OfficialSubwayArrival(id,"하행","train","",RealtimeArrivalSnapshot(id,0,fetchedAtEpochMillis=now,validForMillis=60000))
  val past=LinkedSubwayTimetableEstimate(row,estimate(-90,30))
  val future=LinkedSubwayTimetableEstimate(row,estimate())
  val selected=selectTimetableEvidence(SubwayTimetableAnalysis(listOf(row),listOf(past,future),true,null),now)!!
  assertEquals(estimate().midpointArrivalAtEpochMillis(),selected.first.midpointArrivalAtEpochMillis())
 }
 @Test fun foregroundAndBackgroundCompareTheSameMidpoint() {
  val normal=calculateAutoGuidance(input(200),now)
  val background=calculateAutoGuidance(input(200).copy(arrivalRequestFailed=true),now)
  assertEquals(normal,background)
  for(mode in FeatureMode.entries) {
   val state=JourneyScreenState(mode)
   state.timetableAnalysis=SubwayTimetableAnalysis(emptyList(),emptyList(),true,null)
   assertEquals("놓칠 수 있어요",movementGuidanceTitle(normal,now))
  }
 }
}
