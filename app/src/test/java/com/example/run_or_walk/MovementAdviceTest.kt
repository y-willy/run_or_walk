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
    @Test fun walkRunAndWaitUseMeasuredRunTimeAndSingleAbsoluteEtaSubtraction() {
        assertEquals(MovementAdvice.WALK,calculateMovementGuidance(input(600),now,settings).movementAdvice())
        val run=calculateMovementGuidance(input(300),now,settings)
        assertEquals(MovementAdvice.RUN,run.movementAdvice()); assertEquals(200,run.fastTravelSeconds)
        val wait=calculateMovementGuidance(input(220),now,settings)
        assertEquals(MovementAdvice.WAIT_NEXT,wait.movementAdvice())
        assertNull(wait.recommendedArrivalAtEpochMillis)
        val later=calculateMovementGuidance(input(300).copy(location=GuidanceLocation(point,5.0,now+10000)),now+10000,settings)
        assertEquals(now+300000,later.firstArrivalAtEpochMillis!!)
    }
    @Test fun noMeasuredRunningSpeedProducesUncertaintyRatherThanInventingRunOrMissed() {
        val value=calculateMovementGuidance(input(300),now,settings.copy(measuredRunningSpeedKmh=null))
        assertEquals(MovementAdvice.UNKNOWN,value.movementAdvice())
        assertEquals(AutoGuidanceUnknownReason.RUNNING_SPEED_UNAVAILABLE,value.unknownReason)
        assertNull(value.fastTravelSeconds)
        assertEquals(MovementAdvice.WALK,calculateMovementGuidance(input(600),now,settings.copy(measuredRunningSpeedKmh=null)).movementAdvice())
    }
    @Test fun missingStaleLocationOrEtaCannotReusePreviousCatchRecommendation() {
        val previous=calculateMovementGuidance(input(220),now,settings)
        for(value in listOf(input().copy(arrivals=null), input().copy(location=null),
            input().copy(location=GuidanceLocation(point,5.0,now-31000)),
            input().copy(arrivals=input().arrivals!!.copy(receivedAtEpochMillis=now-61000)))) {
            assertEquals(MovementAdvice.UNKNOWN,calculateMovementGuidance(value.copy(previousAssessment=previous),now,settings).movementAdvice())
        }
    }
    private fun advice(status: AutoGuidanceStatus,at: Long=now) = AutoGuidanceAssessment(status,
        firstArrivalAtEpochMillis=at+600000,walkingSeconds=100,fastTravelSeconds=60,bufferSeconds=30,boardingIdentity=id)
    @Test fun everyConfirmedStateTransitionHasTheRequestedPulseCountAndFirstStateIsSilent() {
        val statuses=listOf(AutoGuidanceStatus.WALK,AutoGuidanceStatus.FAST_WALK,AutoGuidanceStatus.NEXT_VEHICLE)
        for(from in statuses) for(to in statuses) {
            val gate=MovementAdviceTransitions()
            assertEquals(0,gate.observe(advice(from),now,true).vibrationCount)
            assertEquals(0,gate.observe(advice(to,now+40000),now+40000,true).vibrationCount)
            val change=gate.observe(advice(to,now+44000),now+44000,true)
            val expected=if(from==to) 0 else when(to) { AutoGuidanceStatus.WALK->1; AutoGuidanceStatus.FAST_WALK->2; AutoGuidanceStatus.NEXT_VEHICLE->3; else->0 }
            assertEquals("$from -> $to",expected,change.vibrationCount)
            assertEquals(to,change.assessment.status)
            assertEquals(0,gate.observe(advice(to,now+80000),now+80000,true).vibrationCount)
        }
    }
    @Test fun jitterUnknownAndDisabledVibrationDoNotRepeatedlyBuzz() {
        val gate=MovementAdviceTransitions()
        gate.observe(advice(AutoGuidanceStatus.WALK),now,true)
        repeat(8) { i ->
            val status=if(i%2==0) AutoGuidanceStatus.FAST_WALK else AutoGuidanceStatus.WALK
            assertEquals(0,gate.observe(advice(status,now+i*4000),now+i*4000,true).vibrationCount)
        }
        assertEquals(0,gate.observe(advice(AutoGuidanceStatus.UNKNOWN),now+40000,true).vibrationCount)
        gate.observe(advice(AutoGuidanceStatus.NEXT_VEHICLE,now+50000),now+50000,false)
        assertEquals(0,gate.observe(advice(AutoGuidanceStatus.NEXT_VEHICLE,now+54000),now+54000,false).vibrationCount)
        assertEquals(0,gate.observe(advice(AutoGuidanceStatus.NEXT_VEHICLE,now+90000),now+90000,true).vibrationCount)
    }
    @Test fun hysteresisCooldownAndFreshEtaAllowRecoveryFromWait() {
        val gate=MovementAdviceTransitions()
        gate.observe(advice(AutoGuidanceStatus.FAST_WALK),now,true)
        val edge=advice(AutoGuidanceStatus.WALK).copy(firstArrivalAtEpochMillis=now+135000,walkingSeconds=100,bufferSeconds=30)
        assertEquals(AutoGuidanceStatus.FAST_WALK,gate.observe(edge,now,true).assessment.status)
        gate.observe(advice(AutoGuidanceStatus.NEXT_VEHICLE,now+40000),now+40000,true)
        assertEquals(3,gate.observe(advice(AutoGuidanceStatus.NEXT_VEHICLE,now+44000),now+44000,true).vibrationCount)
        gate.observe(advice(AutoGuidanceStatus.WALK,now+50000),now+50000,true)
        assertEquals(0,gate.observe(advice(AutoGuidanceStatus.WALK,now+54000),now+54000,true).vibrationCount)
        assertEquals(AutoGuidanceStatus.WALK,gate.observe(advice(AutoGuidanceStatus.WALK,now+90000),now+90000,true).assessment.status)
        assertEquals(MovementAdvice.WAIT_NEXT,calculateMovementGuidance(input(220),now,settings).movementAdvice())
        assertEquals(MovementAdvice.RUN,calculateMovementGuidance(input(300),now,settings).movementAdvice())
        assertEquals(MovementAdvice.WALK,calculateMovementGuidance(input(600),now,settings).movementAdvice())
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

    @Test fun adviceTitlesStayFixedEvenWhenTheCountdownCrossesTheOldExtraSpareTimeBoundary() {
        val walk=calculateMovementGuidance(input(900),now,settings)
        assertEquals("걸으세요",movementGuidanceTitle(walk,now))
        assertEquals("걸으세요",movementGuidanceTitle(walk,now+300000))
        assertEquals("정보 확인 중",movementGuidanceTitle(walk.copy(status=AutoGuidanceStatus.UNKNOWN),now))
    }

    @Test fun escalationInsideCooldownAlertsAndChangesUiWithoutWaitingForVibrationConfirmation() {
        val gate=MovementAdviceTransitions()
        gate.observe(advice(AutoGuidanceStatus.WALK),now,true)
        val runPending=gate.observe(advice(AutoGuidanceStatus.FAST_WALK,now+4000),now+4000,true)
        assertEquals(AutoGuidanceStatus.FAST_WALK,runPending.assessment.status)
        assertEquals(0,runPending.vibrationCount)
        assertEquals(2,gate.observe(advice(AutoGuidanceStatus.FAST_WALK,now+8000),now+8000,true).vibrationCount)
        val waitPending=gate.observe(advice(AutoGuidanceStatus.NEXT_VEHICLE,now+12000),now+12000,true)
        assertEquals(AutoGuidanceStatus.NEXT_VEHICLE,waitPending.assessment.status)
        assertEquals(0,waitPending.vibrationCount)
        val waitConfirmed=gate.observe(advice(AutoGuidanceStatus.NEXT_VEHICLE,now+16000),now+16000,true)
        assertEquals(AutoGuidanceStatus.NEXT_VEHICLE,waitConfirmed.assessment.status)
        assertEquals(3,waitConfirmed.vibrationCount)
    }
    @Test fun higherUrgencyVibrationIsNotConsumedByTheNormalThirtySecondCooldown() {
        val gate=MovementAdviceTransitions()
        gate.observe(advice(AutoGuidanceStatus.WALK),now,true)
        gate.observe(advice(AutoGuidanceStatus.FAST_WALK,now+4000),now+4000,true)
        assertEquals(2,gate.observe(advice(AutoGuidanceStatus.FAST_WALK,now+8000),now+8000,true).vibrationCount)
        gate.observe(advice(AutoGuidanceStatus.NEXT_VEHICLE,now+12000),now+12000,true)
        assertEquals(3,gate.observe(advice(AutoGuidanceStatus.NEXT_VEHICLE,now+16000),now+16000,true).vibrationCount)
    }

    @Test fun sameLevelOscillationDoesNotRepeatUrgentAlertsInsideCooldown() {
        val gate=MovementAdviceTransitions()
        fun sample(status: AutoGuidanceStatus,seconds: Int)=gate.observe(advice(status,now+seconds*1000L),now+seconds*1000L,true)
        sample(AutoGuidanceStatus.WALK,0)
        sample(AutoGuidanceStatus.FAST_WALK,4)
        assertEquals(2,sample(AutoGuidanceStatus.FAST_WALK,8).vibrationCount)
        sample(AutoGuidanceStatus.NEXT_VEHICLE,12)
        assertEquals(3,sample(AutoGuidanceStatus.NEXT_VEHICLE,16).vibrationCount)
        assertEquals(AutoGuidanceStatus.FAST_WALK,sample(AutoGuidanceStatus.FAST_WALK,20).assessment.status)
        assertEquals(0,sample(AutoGuidanceStatus.FAST_WALK,24).vibrationCount)
        assertEquals(AutoGuidanceStatus.NEXT_VEHICLE,sample(AutoGuidanceStatus.NEXT_VEHICLE,28).assessment.status)
        assertEquals(0,sample(AutoGuidanceStatus.NEXT_VEHICLE,32).vibrationCount)
        // A suppressed same-level return is consumed, never replayed after cooldown ends.
        assertEquals(0,sample(AutoGuidanceStatus.NEXT_VEHICLE,60).vibrationCount)
        sample(AutoGuidanceStatus.FAST_WALK,64)
        assertEquals(2,sample(AutoGuidanceStatus.FAST_WALK,68).vibrationCount)
        sample(AutoGuidanceStatus.NEXT_VEHICLE,72)
        assertEquals(3,sample(AutoGuidanceStatus.NEXT_VEHICLE,76).vibrationCount)
    }
    @Test fun walkRunWaitEachAlertOnceAsUrgencyRisesWithinThirtySeconds() {
        val gate=MovementAdviceTransitions()
        gate.observe(advice(AutoGuidanceStatus.FAST_WALK),now,true)
        val changes=listOf(AutoGuidanceStatus.WALK to 1,AutoGuidanceStatus.FAST_WALK to 2,AutoGuidanceStatus.NEXT_VEHICLE to 3)
        changes.forEachIndexed { index,(status,count) ->
            val at=now+4000+index*8000
            assertEquals(status,gate.observe(advice(status,at),at,true).assessment.status)
            assertEquals(count,gate.observe(advice(status,at+4000),at+4000,true).vibrationCount)
        }
    }
    @Test fun recoveryUiIsImmediateWhileItsOrdinaryVibrationStillRespectsCooldown() {
        val gate=MovementAdviceTransitions()
        gate.observe(advice(AutoGuidanceStatus.FAST_WALK),now,true)
        gate.observe(advice(AutoGuidanceStatus.NEXT_VEHICLE,now+4000),now+4000,true)
        assertEquals(3,gate.observe(advice(AutoGuidanceStatus.NEXT_VEHICLE,now+8000),now+8000,true).vibrationCount)
        val recovery=gate.observe(advice(AutoGuidanceStatus.WALK,now+12000),now+12000,true)
        assertEquals(AutoGuidanceStatus.WALK,recovery.assessment.status)
        assertEquals(0,recovery.vibrationCount)
        assertEquals(0,gate.observe(advice(AutoGuidanceStatus.WALK,now+16000),now+16000,true).vibrationCount)
        assertEquals(0,gate.observe(advice(AutoGuidanceStatus.WALK,now+40000),now+40000,true).vibrationCount)
    }
    @Test fun disabledAndUnknownObservationsDoNotConsumeTheNextEnabledUrgentAlert() {
        val gate=MovementAdviceTransitions()
        gate.observe(advice(AutoGuidanceStatus.WALK),now,true)
        gate.observe(advice(AutoGuidanceStatus.FAST_WALK,now+4000),now+4000,true)
        assertEquals(2,gate.observe(advice(AutoGuidanceStatus.FAST_WALK,now+8000),now+8000,true).vibrationCount)
        gate.observe(advice(AutoGuidanceStatus.NEXT_VEHICLE,now+12000),now+12000,false)
        assertEquals(0,gate.observe(advice(AutoGuidanceStatus.NEXT_VEHICLE,now+16000),now+16000,false).vibrationCount)
        assertEquals(0,gate.observe(advice(AutoGuidanceStatus.UNKNOWN,now+20000),now+20000,true).vibrationCount)
        gate.observe(advice(AutoGuidanceStatus.FAST_WALK,now+24000),now+24000,true)
        assertEquals(0,gate.observe(advice(AutoGuidanceStatus.FAST_WALK,now+28000),now+28000,true).vibrationCount)
        gate.observe(advice(AutoGuidanceStatus.NEXT_VEHICLE,now+32000),now+32000,true)
        assertEquals(3,gate.observe(advice(AutoGuidanceStatus.NEXT_VEHICLE,now+36000),now+36000,true).vibrationCount)
    }
    @Test fun anotherBoardingSelectionStartsSilentlyAndDoesNotInheritPreviousUrgencyLevel() {
        val gate=MovementAdviceTransitions()
        gate.observe(advice(AutoGuidanceStatus.FAST_WALK),now,true)
        gate.observe(advice(AutoGuidanceStatus.NEXT_VEHICLE,now+4000),now+4000,true)
        assertEquals(3,gate.observe(advice(AutoGuidanceStatus.NEXT_VEHICLE,now+8000),now+8000,true).vibrationCount)
        val other=id.copy(stopId="other-station")
        assertEquals(0,gate.observe(advice(AutoGuidanceStatus.FAST_WALK,now+12000).copy(boardingIdentity=other),now+12000,true).vibrationCount)
        val wait=advice(AutoGuidanceStatus.NEXT_VEHICLE,now+16000).copy(boardingIdentity=other)
        gate.observe(wait,now+16000,true)
        assertEquals(3,gate.observe(wait,now+20000,true).vibrationCount)
    }

}
