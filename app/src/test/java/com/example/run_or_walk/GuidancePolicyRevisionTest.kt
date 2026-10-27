package com.example.run_or_walk
import org.junit.Assert.*
import org.junit.Test
class GuidancePolicyRevisionTest {
 private val now=1800000000000L
 private val point=MapPoint(127.0,37.0)
 private val id=OfficialBoardingIdentity("seoul-bus","105900050","105900003","4","105900003")
 private fun input(slack:Int)=AutoGuidanceInput(GuidanceLocation(point,3.0,now),GuidanceBoarding(id,point,true),
  GuidanceWalkingRoute(point,point,id,300,now,120000,390),RealtimeArrivalSnapshot(id,now+(360+slack)*1000L,
   nextArrivalAtEpochMillis=now+1500000,fetchedAtEpochMillis=now,validForMillis=600000,receivedAtEpochMillis=now),true)
 @Test fun exactSecondThresholdLabelsMatchTheNewContract() {
  for((slack,label) in listOf(241 to "여유 있어요",240 to "걸으세요",121 to "걸으세요",120 to "놓칠 수 있어요",1 to "놓칠 수 있어요",0 to "뛰세요",-90 to "뛰세요",-91 to "다음 차량을 탑승하세요")) {
   assertEquals("slack=$slack",label,movementGuidanceTitle(calculateMovementGuidance(input(slack),now,JourneySettings(bufferSeconds=60)),now))
  }
 }
 @Test fun walkingRequestsCannotRefreshAgainBeforeThirtySeconds() {
  val end=point.copy(y=37.006)
  val tracker=WalkingRouteTracker(point,KakaoWalkingRoute(660,500,listOf(point,end)),now)
  val fix=GuidanceLocation(point.copy(y=37.00072),3.0,now+20000,1.3,0.2)
  assertFalse(shouldRefreshWalkingRoute(tracker,fix,now+20000,now))
 }
 @Test fun missingNextNeverInventsAnotherVehicleAtTheNegativeSlackBand() {
  val value=input(-91)
  val result=calculateMovementGuidance(value.copy(arrivals=value.arrivals!!.copy(nextArrivalAtEpochMillis=null)),now,JourneySettings(bufferSeconds=60))
  assertEquals(AutoGuidanceStatus.NEXT_VEHICLE,result.status);assertNull(result.recommendedArrivalAtEpochMillis)
  assertEquals("다음 차량 정보 확인 필요",movementGuidanceTitle(result,now))
 }
 @Test fun zeroExpiredOrOfficiallyPassedEtaCannotProduceRunEvenAfterPreviousRun() {
  val valid=input(0)
  val original=requireNotNull(valid.arrivals)
  for(snapshot in listOf(original.copy(firstArrivalAtEpochMillis=now),
   original.copy(firstArrivalAtEpochMillis=now-1000),original.copy(trustState=ArrivalTrustState.UNKNOWN,reliable=false))) {
   val result=calculateMovementGuidance(valid.copy(arrivals=snapshot,previousAssessment=AutoGuidanceAssessment(AutoGuidanceStatus.FAST_WALK)),now,JourneySettings(bufferSeconds=60))
   assertEquals(AutoGuidanceStatus.UNKNOWN,result.status)
  }
 }
 @Test fun allOfficialProvidersUseTheSameSlackRuleAndRejectAnotherDirectionsTuple() {
  for(authority in listOf("seoul-bus","GBIS","seoul")) {
   val identity=id.copy(authority=authority)
   val value=input(121)
   val matched=value.copy(boarding=value.boarding!!.copy(identity=identity),walkingRoute=value.walkingRoute!!.copy(boardingIdentity=identity),arrivals=value.arrivals!!.copy(identity=identity))
   assertEquals(AutoGuidanceStatus.WALK,calculateMovementGuidance(matched,now,JourneySettings(bufferSeconds=60)).status)
   val wrong=matched.copy(arrivals=matched.arrivals!!.copy(identity=identity.copy(directionId="other")))
   assertEquals(AutoGuidanceStatus.UNKNOWN,calculateMovementGuidance(wrong,now,JourneySettings(bufferSeconds=60)).status)
  }
 }

}
