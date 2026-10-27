package com.example.run_or_walk

import org.junit.Assert.*
import org.junit.Test

class MovementAdviceTest {
    private val now=1_800_000_000_000L
    private val point=MapPoint(127.0,37.0)
    private val id=OfficialBoardingIdentity("seoul","1001000133","1001","상행","서울역")
    private fun input(etaSeconds: Int=600) = AutoGuidanceInput(GuidanceLocation(point,5.0,now),
        GuidanceBoarding(id,point,true),GuidanceWalkingRoute(point,point,id,400,now,120000,500),
        RealtimeArrivalSnapshot(id,now+etaSeconds*1000, fetchedAtEpochMillis=now,validForMillis=600000,
            receivedAtEpochMillis=now),true,bufferSeconds=60)
    private val settings=JourneySettings(bufferSeconds=60,measuredRunningSpeedKmh=9.0)
    @Test fun thresholdsUseSlackAndKeepOptionalMeasuredRunTimeSeparate() {
        assertEquals(MovementAdvice.WALK,calculateMovementGuidance(input(600),now,settings).movementAdvice())
        val run=calculateMovementGuidance(input(430),now,settings)
        assertEquals(MovementAdvice.RUN,run.movementAdvice());assertEquals(200,run.fastTravelSeconds)
        val without=calculateMovementGuidance(input(430),now,settings.copy(measuredRunningSpeedKmh=null))
        assertEquals(MovementAdvice.RUN,without.movementAdvice());assertNull(without.fastTravelSeconds)
        val wait=calculateMovementGuidance(input(300),now,settings)
        assertEquals(MovementAdvice.WAIT_NEXT,wait.movementAdvice())
        assertEquals("다음 차량 정보 확인 필요",movementGuidanceTitle(wait,now))
    }
    @Test fun firstStateIsSilentChangedStateIsImmediateAndPulsesRequireStability() {
        val gate=MovementAdviceTransitions()
        fun advice(status:AutoGuidanceStatus)=AutoGuidanceAssessment(status,boardingIdentity=id)
        assertEquals(0,gate.observe(advice(AutoGuidanceStatus.WALK),0,true).vibrationCount)
        val immediate=gate.observe(advice(AutoGuidanceStatus.FAST_WALK),4000,true)
        assertEquals(AutoGuidanceStatus.FAST_WALK,immediate.assessment.status);assertEquals(0,immediate.vibrationCount)
        assertEquals(2,gate.observe(advice(AutoGuidanceStatus.FAST_WALK),7000,true).vibrationCount)
        assertEquals(0,gate.observe(advice(AutoGuidanceStatus.FAST_WALK),40000,true).vibrationCount)
    }
    @Test fun disabledChangesAreConsumedAndUnknownDoesNotBecomeAQueuedPulse() {
        val gate=MovementAdviceTransitions()
        fun value(status:AutoGuidanceStatus)=AutoGuidanceAssessment(status,boardingIdentity=id)
        gate.observe(value(AutoGuidanceStatus.WALK),0,true)
        gate.observe(value(AutoGuidanceStatus.FAST_WALK),4000,false)
        assertEquals(0,gate.observe(value(AutoGuidanceStatus.FAST_WALK),7000,false).vibrationCount)
        assertEquals(0,gate.observe(value(AutoGuidanceStatus.UNKNOWN),10000,true).vibrationCount)
        assertEquals(0,gate.observe(value(AutoGuidanceStatus.FAST_WALK),40000,true).vibrationCount)
    }
    @Test fun missingStaleLocationOrEtaCannotReusePreviousCatchRecommendation() {
        val previous=calculateMovementGuidance(input(220),now,settings)
        for(value in listOf(input().copy(arrivals=null), input().copy(location=null),
            input().copy(location=GuidanceLocation(point,5.0,now-31000)),
            input().copy(arrivals=input().arrivals!!.copy(receivedAtEpochMillis=now-61000)))) {
            assertEquals(MovementAdvice.UNKNOWN,calculateMovementGuidance(value.copy(previousAssessment=previous),now,settings).movementAdvice())
        }
    }
    @Test fun vibrationWaveformsContainOneTwoThreePulsesAndNoRepeatSlot() {
        for(count in 1..3) {
            val pattern=movementVibrationPattern(count)
            assertEquals(count*2,pattern.size)
            assertEquals(count,pattern.filterIndexed { i,_ -> i%2==1 }.count { it==100L })
        }
        assertTrue(movementVibrationPattern(0).isEmpty())
    }
    @Test fun savedSpeedIsExplicitPersonalizationAndSettingsRemainBackwardsCompatible() {
        val personal=settings.copy(personalWalkingSpeedKmh=3.6,usePersonalWalkingSpeed=true)
        assertEquals(100,personal.walkingSeconds(100,80))
        assertEquals(80,personal.copy(usePersonalWalkingSpeed=false,useProviderWalkingTime=true).walkingSeconds(100,80))
        assertEquals(personal,JourneySettingsCodec.decode(JourneySettingsCodec.encode(personal)))
        val older=JourneySettingsCodec.decode("""{"version":2,"speedKmh":4.68,"gpsAutoCalibrationEnabled":false}""")
        assertFalse(older.gpsAutoCalibrationEnabled); assertNull(older.measuredRunningSpeedKmh)
        assertFalse(older.usePersonalWalkingSpeed)
    }
    @Test fun foregroundAndBackgroundUseTheSameMeasuredPolicyAndPreserveProviderEtas() {
        val stop=OfficialSubwayStation(id.stopId,id.routeId,"서울역","1호선")
        val request=JourneyMonitoringRequest("서울역",point,id,subwayStation=stop,settings=settings)
        for(seconds in listOf(600,300,220)) {
            val source=input(seconds)
            val state=JourneyMonitoringState(true,request,location=source.location,walkingRoute=source.walkingRoute,
                arrivals=source.arrivals,walkingDistanceMeters=500,providerWalkingSeconds=400)
            val actual=monitoringStateWithSettings(state,request,now,true)
            val expected=calculateMovementGuidance(source.copy(walkingRoute=source.walkingRoute!!.copy(
                durationSeconds=settings.walkingSeconds(500,400))),now,settings)
            assertEquals(expected,actual.assessment)
            assertEquals(source.arrivals,actual.arrivals)
        }
    }
    @Test fun officialRealtimeWinsAndPositionTimetableUsesItsMidpointWithoutInventedNextArrival() {
        val estimate=OfficialTimetableEstimate(id,"6132",java.time.LocalDate.of(2026,10,26),
            OfficialServiceDayType.WEEKDAY,OfficialTrainOperationType.LOCAL,"pattern","previous",3,
            now+240000,now+180000,now+300000,0,0,now,now,0,now+60000,
            OfficialTimetableSource("official","official","v1",now-100000),"calendar")
        val evidence=TimetableArrivalEvidence(id,estimate)
        val realtime=calculateMovementGuidance(input(600).copy(timetableArrivals=evidence),now,settings)
        assertEquals(GuidanceArrivalSource.OFFICIAL_REALTIME,realtime.arrivalSource)
        assertEquals(now+600000,realtime.firstArrivalAtEpochMillis!!)
        val position=calculateMovementGuidance(input().copy(arrivals=null,timetableArrivals=evidence),now,settings)
        assertEquals(GuidanceArrivalSource.OFFICIAL_TIMETABLE_POSITION,position.arrivalSource)
        assertEquals(now+240000,position.firstArrivalAtEpochMillis!!)
        assertNull(position.nextArrivalAtEpochMillis)
        assertEquals(ArrivalTimePrecision.MINUTES,position.arrivalPrecision)
    }
}
