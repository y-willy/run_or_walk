package com.example.run_or_walk

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ArrivalRefreshStabilityTest {
    private val now=1_800_000_000_000L
    private val route=OfficialSeoulBusRoute("105900050","105900003","동대문01","4","회기역","2")
    @Test fun seoulNextVehicleExpiryDoesNotExpireCurrentVehicle() {
        fun instant(value:Long)=Instant.ofEpochMilli(value).toString()
        val payload="""{"provider":"seoul-bus","fetchedAt":"${instant(now)}","stopId":"105900050","routeId":"105900003","stopOrder":"4","arrivals":[
          {"stopId":"105900050","routeId":"105900003","stopOrder":"4","vehicleId":"105000001","arrivalSeconds":120,"observedAt":"${instant(now)}"},
          {"stopId":"105900050","routeId":"105900003","stopOrder":"4","vehicleId":"105000002","arrivalSeconds":900,"observedAt":"${instant(now-595000)}"}]}"""
        val rows=parseWorkerSeoulBusArrivals(payload,route,now)
        val current=chooseMatchingBusArrival(route.identity,rows,now+6000)
        assertNotNull("The current vehicle still has 54 s of its own receipt lifetime",current)
        assertTrue(current!!.snapshot.hasUsableOfficialEta(now+6000))
        assertNull(current.snapshot.nextArrivalAtEpochMillis)
    }
    @Test fun gyeonggiNextVehicleExpiryDoesNotExpireCurrentSnapshot() {
        val match=GyeonggiOfficialMatch("200000001","234000001","12","M5107","서울역",MapPoint(127.0,37.0))
        val first=GyeonggiBusArrivalDisplay(match.stationId,match.routeId,match.staOrder,match.routeName,match.destination,
            "100000001",180,now,now,ArrivalTrustState.VALID,1)
        val next=first.copy(vehicleId="100000002",seconds=600,fetchedAtMillis=now-50000,observedAtMillis=now-50000,sequence=2)
        val current=chooseMatchingGyeonggiArrival(match,listOf(first,next),now)!!
        assertTrue("The current snapshot must keep its own lifetime",current.snapshot.hasUsableOfficialEta(now+11000))
    }
    private fun row(vehicle:String,slot:Int,at:Long=now+300000,source:Long=now)=OfficialSeoulBusArrival(
        route.identity,vehicle,route.name,route.direction,
        RealtimeArrivalSnapshot(route.identity,at,fetchedAtEpochMillis=source,validForMillis=60000,receivedAtEpochMillis=source),
        gatewayFetchedAtEpochMillis=source,officialSlot=slot,sourceEtaSeconds=(at-source)/1000)
    private fun merge(old:List<OfficialSeoulBusArrival>,fresh:List<OfficialSeoulBusArrival>,at:Long=now)=
        mergeSeoulBusArrivals(route.identity,old,fresh,at)
    @Test fun firstAndNextRefreshIndependentlyWithoutRenewingTheOtherObservation() {
        val a=row("105000001",1);val b=row("105000002",2,now+780000)
        val updatedA=a.copy(snapshot=a.snapshot.copy(firstArrivalAtEpochMillis=now+240000,fetchedAtEpochMillis=now+10000,receivedAtEpochMillis=now+10000),gatewayFetchedAtEpochMillis=now+10000)
        val afterA=merge(listOf(a,b),listOf(updatedA),now+10000)
        assertEquals(b,afterA.single { it.vehicleId==b.vehicleId })
        assertEquals(now+780000,chooseMatchingBusArrival(route.identity,afterA,now+10000)!!.snapshot.nextArrivalAtEpochMillis)
        val updatedB=b.copy(snapshot=b.snapshot.copy(firstArrivalAtEpochMillis=now+700000,fetchedAtEpochMillis=now+20000,receivedAtEpochMillis=now+20000),gatewayFetchedAtEpochMillis=now+20000)
        val afterB=merge(afterA,listOf(updatedB),now+20000)
        assertEquals(updatedA,afterB.single { it.vehicleId==a.vehicleId })
        assertEquals(now+700000,chooseMatchingBusArrival(route.identity,afterB,now+20000)!!.snapshot.usableNextArrivalAt(now+20000))
    }
    @Test fun partialRefreshNeverExtendsMissingVehiclesReceiptOrExpiry() {
        val a=row("105000001",1);val b=row("105000002",2,now+780000)
        var rows=listOf(a,b)
        for(seconds in listOf(10,30,50,61)) {
            val at=now+seconds*1000L
            rows=merge(rows,listOf(row(a.vehicleId,1,at+240000,at)),at)
        }
        assertEquals(listOf(a.vehicleId),rows.map { it.vehicleId })
        assertNull(chooseMatchingBusArrival(route.identity,rows,now+61000)!!.snapshot.nextArrivalAtEpochMillis)
    }
    @Test fun explicitPassedEndedFullOrDirectionMismatchRetiresThatVehicleOnly() {
        val a=row("105000001",1);val b=row("105000002",2,now+780000)
        val at=now+5000
        val passed=row(a.vehicleId,1,at,at).copy(sourceEtaSeconds=0,
            snapshot=row(a.vehicleId,1,at,at).snapshot.copy(trustState=ArrivalTrustState.STALE,reliable=false))
        val ended=row(a.vehicleId,1,at+300000,at).copy(issues=setOf("SERVICE_ENDED"))
        val full=row(a.vehicleId,1,at+300000,at).copy(isFull=true)
        val wrong=row(a.vehicleId,1,at+300000,at).let { value ->
            val id=value.identity.copy(directionId="5")
            value.copy(identity=id,snapshot=value.snapshot.copy(identity=id))
        }
        for(fresh in listOf(passed,ended,full,wrong)) {
            val chosen=chooseMatchingBusArrival(route.identity,merge(listOf(a,b),listOf(fresh),at),at)
            assertEquals(b.vehicleId,chosen!!.vehicleId)
            assertEquals(b.snapshot.fetchedAtEpochMillis,chosen.snapshot.fetchedAtEpochMillis)
        }
    }
    @Test fun slotTakeoverAndDuplicateVehicleIdsCannotCreateTwoCopiesOfOneBus() {
        val a=row("105000001",1);val b=row("105000002",2,now+780000)
        val moved=row(b.vehicleId,1,now+500000,now+5000)
        val rows=merge(listOf(a,b),listOf(moved),now+5000)
        assertEquals(listOf(b.vehicleId),rows.map { it.vehicleId })
        assertNull(chooseMatchingBusArrival(route.identity,listOf(moved,moved.copy(officialSlot=2)),now+5000)!!.snapshot.nextArrivalAtEpochMillis)
    }
    @Test fun emptyConfirmedResponseRetiresOldBusesAndOlderResponsesCannotOverwriteNewerEta() {
        val fresh=row("105000001",1,now+240000,now+10000)
        val cached=row("105000001",1,now+300000,now)
        assertEquals(fresh,merge(listOf(fresh),listOf(cached),now+10000).single())
        assertTrue(merge(listOf(fresh),emptyList(),now+10000).isEmpty())
    }
    @Test fun anonymousOfficialSlotsWorkWithoutInventingVehicleIdsOrSecondPrecision() {
        val a=row("",1).copy(snapshot=row("",1).snapshot.copy(vehicleIdentityFallback="official-slot:1",etaPrecision=ArrivalTimePrecision.MINUTES))
        val b=row("",2,now+780000).copy(snapshot=row("",2,now+780000).snapshot.copy(vehicleIdentityFallback="official-slot:2",etaPrecision=ArrivalTimePrecision.MINUTES))
        val rows=merge(listOf(a,b),listOf(a),now+10000)
        val snapshot=chooseMatchingBusArrival(route.identity,rows,now+10000)!!.snapshot
        assertEquals(ArrivalTimePrecision.MINUTES,snapshot.etaPrecision)
        assertEquals(ArrivalTimePrecision.MINUTES,snapshot.nextArrivalPrecision)
        assertNotNull(snapshot.usableNextArrivalAt(now+10000))
        assertTrue(rows.all { it.vehicleId.isEmpty() })
    }
    @Test fun screenRequestGenerationsPreserveDataButRejectOutOfOrderRepliesAndRetireChangedSelection() {
        val screen=JourneyScreenState(FeatureMode.STOP)
        screen.selectedBusStop=OfficialSeoulBusStop(route.stopId,"06510","경희대의료원",MapPoint(127.0,37.0))
        screen.selectedBusRoute=route;screen.applyBusArrivals(listOf(row("105000001",1)),now)
        val first=screen.beginLiveRequest();val second=screen.beginLiveRequest()
        assertTrue(screen.liveLoading)
        assertNotNull(screen.currentBusArrival(now))
        assertFalse(screen.acceptsLiveResponse(first));assertTrue(screen.acceptsLiveResponse(second))
        screen.invalidateRequests(preserveTimetable=true)
        assertFalse(screen.acceptsLiveResponse(second));assertNotNull(screen.currentBusArrival(now))
        screen.clearSelectedBusRoute()
        assertNull(screen.currentBusArrival(now));assertTrue(screen.busArrivalOptions.isEmpty())
    }
    @Test fun foregroundAndBackgroundChooseTheSameSurvivingBusAfterPassageFailureAndExpiry() {
        val point=MapPoint(127.0,37.0)
        val request=JourneyMonitoringRequest("정류장",point,route.identity,busStop=OfficialSeoulBusStop(route.stopId,"06510","정류장",point),busRoute=route,
            settings=JourneySettings(bufferSeconds=30,measuredRunningSpeedKmh=9.0))
        val a=row("105000001",1,now+10000);val b=row("105000002",2,now+300000)
        val seed=JourneyMonitoringState(true,request,location=GuidanceLocation(point,3.0,now+11000),
            walkingRoute=GuidanceWalkingRoute(point,point,route.identity,60,now,120000,78),
            arrivals=chooseMatchingBusArrival(route.identity,listOf(a,b),now)!!.snapshot,
            walkingDistanceMeters=78,providerWalkingSeconds=60,arrivalRequestFailed=true,seoulBusObservations=listOf(a,b))
        val changed=monitoringStateWithSettings(seed,request,now+11000,true)
        assertEquals(chooseMatchingBusArrival(route.identity,listOf(a,b),now+11000)!!.snapshot,changed.arrivals)
        assertEquals(b.snapshot.firstArrivalAtEpochMillis,changed.arrivals!!.firstArrivalAtEpochMillis)
        assertEquals(AutoGuidanceStatus.WALK,changed.assessment!!.status)
        val expired=monitoringStateWithSettings(changed.copy(location=GuidanceLocation(point,3.0,now+61000)),request,now+61000,true)
        assertNull(expired.arrivals);assertEquals(AutoGuidanceStatus.UNKNOWN,expired.assessment!!.status)
    }
    @Test fun gyeonggiPartialResponseKeepsOtherSlotAndExplicitTerminationRemovesOnlyItsBus() {
        val match=GyeonggiOfficialMatch("200000001","234000001","12","M5107","서울역",MapPoint(127.0,37.0))
        val a=GyeonggiBusArrivalDisplay(match.stationId,match.routeId,match.staOrder,match.routeName,match.destination,"100000001",240,now,now,ArrivalTrustState.VALID,1)
        val b=a.copy(vehicleId="100000002",seconds=480,sequence=2)
        val rows=mergeGyeonggiBusArrivals(match,listOf(a,b),listOf(a.copy(seconds=200,fetchedAtMillis=now+10000,observedAtMillis=now+10000)),now+10000)
        assertEquals(b,rows.single { it.sequence==2 })
        assertEquals(now+480000,chooseMatchingGyeonggiArrival(match,rows,now+10000)!!.snapshot.usableNextArrivalAt(now+10000))
        val ended=a.copy(trust=ArrivalTrustState.UNKNOWN,serviceEnded=true,fetchedAtMillis=now+20000,observedAtMillis=now+20000)
        val next=chooseMatchingGyeonggiArrival(match,mergeGyeonggiBusArrivals(match,rows,listOf(ended),now+20000),now+20000)!!
        assertEquals(b.vehicleId,next.vehicleId)
        assertEquals(b.fetchedAtMillis,next.snapshot.receivedAtEpochMillis)
    }
    @Test fun noDataStatesDistinguishInitialFailureExpiryAndFreshConfirmedAbsence() {
        assertEquals("도착정보 확인 중",missingArrivalText(null,false,false,true,now))
        assertEquals("도착정보를 불러오지 못했어요",missingArrivalText(now,true,true,false,now))
        assertEquals("최신 도착정보 확인 필요",missingArrivalText(now,true,false,false,now))
        assertEquals("운행정보 없음",missingArrivalText(now,false,false,true,now))
        assertEquals("최신 도착정보 확인 필요",missingArrivalText(now-61000,false,false,true,now))
    }

    @Test fun olderCachedSlotTakeoverCannotRemoveTheCurrentNewerFirstBus() {
        val a=row("105000001",1,now+240000,now+10000)
        val b=row("105000002",2,now+700000,now+10000)
        val oldB=row(b.vehicleId,1,now+800000,now)
        val rows=merge(listOf(a,b),listOf(oldB),now+10000)
        assertEquals(a,rows.single { it.vehicleId==a.vehicleId })
        assertEquals(b,rows.single { it.vehicleId==b.vehicleId })
    }
    @Test fun repeatedFutureTolerantCacheDoesNotMoveArrivalOrResurrectExpiredObservation() {
        fun instant(value:Long)=Instant.ofEpochMilli(value).toString()
        val payload="""{"provider":"seoul-bus","fetchedAt":"${instant(now+30000)}","stopId":"105900050","routeId":"105900003","stopOrder":"4","arrivals":[
          {"stopId":"105900050","routeId":"105900003","stopOrder":"4","vehicleId":"105000001","arrivalSeconds":300,"observedAt":"${instant(now+10000)}"}]}"""
        val first=parseWorkerSeoulBusArrivals(payload,route,now)
        val during=merge(first,parseWorkerSeoulBusArrivals(payload,route,now+15000),now+15000)
        assertEquals(first.single().snapshot.firstArrivalAtEpochMillis,during.single().snapshot.firstArrivalAtEpochMillis)
        assertEquals(first.single().snapshot.validForMillis,during.single().snapshot.validForMillis)
        assertNull(chooseMatchingBusArrival(route.identity,merge(during,parseWorkerSeoulBusArrivals(payload,route,now+61000),now+61000),now+61000))
        val match=GyeonggiOfficialMatch("200000001","234000001","12","M5107","서울역",MapPoint(127.0,37.0))
        val gg=GyeonggiBusArrivalDisplay(match.stationId,match.routeId,match.staOrder,match.routeName,match.destination,"100000001",300,
            now+30000,now+10000,ArrivalTrustState.VALID,1,normalizedAtMillis=now)
        val repeated=mergeGyeonggiBusArrivals(match,listOf(gg),listOf(gg.copy(normalizedAtMillis=now+15000)),now+15000)
        assertEquals(gg,repeated.single())
        assertNull(chooseMatchingGyeonggiArrival(match,mergeGyeonggiBusArrivals(match,repeated,listOf(gg.copy(normalizedAtMillis=now+61000)),now+61000),now+61000))
    }

    @Test fun startingBackgroundGuidanceReusesOnlyTheSameSelectionsUnexpiredScreenEvidence() {
        val point=MapPoint(127.0,37.0)
        val stop=OfficialSeoulBusStop(route.stopId,"06510","정류장",point)
        val request=JourneyMonitoringRequest(stop.name,point,route.identity,busStop=stop,busRoute=route,settings=JourneySettings(bufferSeconds=30))
        val screen=JourneyScreenState(FeatureMode.STOP)
        screen.boarding=stop.toJourneyPlace();screen.boardingChosenByUser=true
        screen.confirmedServiceIdentity=route.identity;screen.selectedBusStop=stop;screen.selectedBusRoute=route
        screen.location=GuidanceLocation(point,3.0,now)
        screen.walkRoute=WalkRoute(78,60)
        screen.guidanceWalkingRoute=GuidanceWalkingRoute(point,point,route.identity,60,now-90000,120000,78)
        screen.applyBusArrivals(listOf(row("105000001",1)),now)
        val handover=monitoringScreenHandover(screen,request,now,true)!!
        assertEquals(AutoGuidanceStatus.WALK,handover.assessment!!.status)
        assertEquals(now-90000,handover.walkingRoute!!.fetchedAtEpochMillis)
        assertEquals(now,handover.arrivals!!.receivedAtEpochMillis)
        assertNull(monitoringScreenHandover(screen,request.copy(identity=route.identity.copy(directionId="5")),now,true))
        assertNull(monitoringScreenHandover(screen,request.copy(boardingPoint=point.copy(y=point.y+0.001)),now,true))
        val expired=monitoringScreenHandover(screen,request,now+61000,true)!!
        assertNull(expired.arrivals);assertEquals(AutoGuidanceStatus.UNKNOWN,expired.assessment!!.status)
    }

    @Test fun countdownClockUsesMonotonicElapsedTimeWithoutRebasingArrivalDates() {
        var elapsed=1000L
        val clock=GuidanceDisplayClock(now,elapsed) { elapsed }
        val arrivalAt=now+300000
        assertEquals(300000L,arrivalAt-clock.now())
        elapsed+=15000
        assertEquals(285000L,arrivalAt-clock.now())
        elapsed+=45000 // includes a device sleep interval supplied by elapsedRealtime
        assertEquals(240000L,arrivalAt-clock.now())
        // The wall clock is intentionally not read on each tick, so changing it cannot
        // refresh receipt/source ages or reset this countdown.
        assertEquals(now+60000,clock.now())
    }

    @Test fun busBackoffKeepsVerifiedNextVehicleRecommendationWithoutResettingVibrationPolicy() {
        val point=MapPoint(127.0,37.0)
        val request=JourneyMonitoringRequest("정류장",point,route.identity,busStop=OfficialSeoulBusStop(route.stopId,"06510","정류장",point),busRoute=route,
            settings=JourneySettings(bufferSeconds=30,measuredRunningSpeedKmh=9.0))
        val rows=listOf(row("105000001",1,now+60000),row("105000002",2,now+900000))
        val seed=JourneyMonitoringState(true,request,location=GuidanceLocation(point,3.0,now),
            walkingRoute=GuidanceWalkingRoute(point,point,route.identity,462,now,120000,600),
            arrivals=chooseMatchingBusArrival(route.identity,rows,now)!!.snapshot,
            walkingDistanceMeters=600,providerWalkingSeconds=500,arrivalRequestFailed=true,seoulBusObservations=rows,
            retryAtEpochMillis=now+30000,arrivalError="조회 제한")
        val result=monitoringStateWithSettings(seed,request,now+5000,true)
        assertEquals(AutoGuidanceStatus.NEXT_VEHICLE,result.assessment!!.status)
        assertEquals(now+900000,result.assessment.recommendedArrivalAtEpochMillis)
        assertEquals(now+900000,result.arrivals!!.usableNextArrivalAt(now+5000))
        assertEquals("조회 제한",result.arrivalError)
    }
    @Test fun tabSwitchInvalidatesRepliesWithoutDiscardingTheOriginalTimetableAnalysis() {
        val screen=JourneyScreenState(FeatureMode.STOP)
        val analysis=SubwayTimetableAnalysis(emptyList(),emptyList(),true,null,"1075075241","1075")
        screen.timetableAnalysis=analysis
        val generation=screen.beginLiveRequest()
        screen.invalidateRequests(preserveTimetable=true)
        assertSame(analysis,screen.timetableAnalysis);assertFalse(screen.acceptsLiveResponse(generation))
        screen.clearStopDestinationDirection()
        assertNull(screen.timetableAnalysis)
    }

    @Test fun subwayNextTrainExpiryDoesNotEraseTheFirstTrainsValidNumericEta() {
        val station=OfficialSubwayStation("1075075241","1075","망포","수인분당선")
        val format=java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(java.time.ZoneId.of("Asia/Seoul"))
        fun source(at:Long)=format.format(Instant.ofEpochMilli(at))
        val payload="""{"fetchedAt":"${Instant.ofEpochMilli(now)}","arrivals":[
          {"statnId":"1075075241","subwayId":"1075","statnNm":"망포","updnLine":"하행","trainLineNm":"인천행 - 매탄권선방면","btrainNo":"6521","barvlDt":"120","arvlCd":"99","recptnDt":"${source(now)}"},
          {"statnId":"1075075241","subwayId":"1075","statnNm":"망포","updnLine":"하행","trainLineNm":"인천행 - 매탄권선방면","btrainNo":"6523","barvlDt":"900","arvlCd":"99","recptnDt":"${source(now-595000)}"}]}"""
        val rows=parseWorkerSubwayArrivals(payload,station,now)
        val first=rows.single { it.trainId=="6521" }.snapshot
        assertTrue(first.hasUsableOfficialEta(now+6000))
        assertNull(first.usableNextArrivalAt(now+6000))
    }

    @Test fun laterTrainRecommendationsUseThatTrainsOwnDeadlineAfterExpiryIsSeparated() {
        val point=MapPoint(127.0,37.0)
        val snapshot=RealtimeArrivalSnapshot(route.identity,now+120000,fetchedAtEpochMillis=now,validForMillis=600000,
            receivedAtEpochMillis=now,laterArrivalAtEpochMillis=listOf(now+305000),
            laterArrivalValidUntilEpochMillis=mapOf(now+305000 to now+5000))
        val input=AutoGuidanceInput(GuidanceLocation(point,3.0,now+6000),GuidanceBoarding(route.identity,point,true),
            GuidanceWalkingRoute(point,point,route.identity,462,now,120000,600),snapshot,true)
        val result=calculateMovementGuidance(input,now+6000,JourneySettings(bufferSeconds=30,measuredRunningSpeedKmh=9.0))
        assertEquals(AutoGuidanceStatus.NEXT_VEHICLE,result.status)
        assertNull(result.recommendedArrivalAtEpochMillis)
        assertEquals(NextVehicleState.ETA_PENDING,result.nextVehicleState)
    }

    @Test fun confirmedMissingSelectedTrainCannotKeepItsOldEtaThroughTheSavedPreference() {
        val screen=JourneyScreenState(FeatureMode.STOP)
        val id=OfficialBoardingIdentity("seoul","1075075241","1075","하행","인천행")
        val selected=OfficialSubwayArrival(id,"하행","인천행","6521",
            RealtimeArrivalSnapshot(id,now+300000,fetchedAtEpochMillis=now,validForMillis=600000,receivedAtEpochMillis=now),officialSlot=1)
        screen.selectedArrival=selected;screen.subwayChoice=SubwayArrivalChoice.from(selected)
        screen.confirmedServiceIdentity=id
        screen.timetableAnalysis=SubwayTimetableAnalysis(listOf(selected),emptyList(),true,null,id.stopId,id.routeId)
        screen.clearMissingSubwayArrival()
        assertNull(screen.arrivalSnapshotAt(now));assertNull(screen.timetableAnalysis)
        assertEquals(SubwayArrivalChoice.from(selected),screen.subwayChoice)
        assertEquals(id,screen.confirmedServiceIdentity)
    }

}
