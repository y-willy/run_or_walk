package com.example.run_or_walk

import org.junit.Assert.*
import org.junit.Test

class NextVehiclePolicyTest {
    private val now = 1_800_000_000_000L
    private val point = MapPoint(127.0,37.2)
    private val identity = OfficialBoardingIdentity("GBIS","200000001","234000001","12","M5107")
    private fun assess(walk: Int, first: Int, next: Int? = null, distance: Int = 600,
        error: Boolean = false, ended: Boolean = false, later: List<Int> = emptyList(), buffer: Int = 120): AutoGuidanceAssessment {
        val snapshot = RealtimeArrivalSnapshot(identity,now+first*1000,next?.let { now+it*1000 },
            now,60_000,etaPrecision=ArrivalTimePrecision.MINUTES,
            laterArrivalAtEpochMillis=later.map { now+it*1000 })
        return calculateAutoGuidance(AutoGuidanceInput(GuidanceLocation(point,10.0,now),
            GuidanceBoarding(identity,point,true),GuidanceWalkingRoute(point,point,identity,walk,now,120_000,distance),
            snapshot,true,error,buffer,furtherServiceEnded=ended),now)
    }
    @Test fun scenario1NextEtaMissingDoesNotBlockTheDecision() {
        val value=assess(360,240);assertEquals(AutoGuidanceStatus.NEXT_VEHICLE,value.status)
        assertEquals(NextVehicleState.ETA_PENDING,value.nextVehicleState)
        assertNull(value.recommendedArrivalAtEpochMillis)
    }
    @Test fun scenario2AlreadyReceivedEightMinutesAreUsedImmediately() {
        val value=assess(360,240,480);assertEquals(AutoGuidanceStatus.NEXT_VEHICLE,value.status)
        assertEquals(now+480_000,value.recommendedArrivalAtEpochMillis)
        assertTrue(nextVehicleGuidanceText(value,now).contains("8분"))
        assertTrue(nextVehicleGuidanceText(value,now+60_000).contains("7분"))
    }
    @Test fun scenario3TwelveMinuteNextVehicle() {assertEquals(now+720_000,assess(360,240,720).recommendedArrivalAtEpochMillis)}
    @Test fun scenario4NeitherOfTheFirstTwoIsReachable() {
        val value=assess(720,240,480,1000);assertEquals(AutoGuidanceStatus.NEXT_VEHICLE,value.status)
        assertEquals(NextVehicleState.LATER_VEHICLES_REQUIRED,value.nextVehicleState)
        assertNull(value.recommendedArrivalAtEpochMillis)
        assertEquals(now+900_000,assess(720,240,480,1000,later=listOf(900)).recommendedArrivalAtEpochMillis)
    }
    @Test fun scenario5OrdinaryWalking() {assertEquals(AutoGuidanceStatus.CAUTION,assess(240,480,900).status)}
    @Test fun scenario6FastWalkingKeepsItsBuffer() {
        assertEquals(AutoGuidanceStatus.NEXT_VEHICLE,assess(360,300,720,300).status)
        assertEquals(AutoGuidanceStatus.NEXT_VEHICLE,assess(360,300,720,400).status)
    }
    @Test fun scenario7NextRequestErrorDoesNotEraseAValidFirstEta() {
        val value=assess(360,240,error=true);assertEquals(AutoGuidanceStatus.NEXT_VEHICLE,value.status)
        assertEquals(NextVehicleState.API_ERROR,value.nextVehicleState)
    }
    @Test fun scenario8OnlyExplicitEndStopsFurtherVehicleLookup() {
        assertEquals(NextVehicleState.SERVICE_ENDED,assess(360,240,ended=true).nextVehicleState)
        assertEquals(NextVehicleState.ETA_PENDING,assess(360,240).nextVehicleState)
    }
    private val match=GyeonggiOfficialMatch("200000001","234000001","12","M5107","서울역",point)
    private fun row(seconds:Int,sequence:Int,vehicle:String,order:String="12")=GyeonggiBusArrivalDisplay(
        match.stationId,match.routeId,order,"M5107","서울역",vehicle,seconds,now,now,ArrivalTrustState.VALID,sequence)
    @Test fun scenario9RepeatedVehicleIsNotTheNextVehicle() {
        assertNull(chooseMatchingGyeonggiArrival(match,listOf(row(240,1,"123"),row(480,2,"123")),now)!!.snapshot.nextArrivalAtEpochMillis)
    }
    @Test fun scenario10OppositeDirectionCannotSupplyTheNextEta() {
        assertNull(chooseMatchingGyeonggiArrival(match,listOf(row(240,1,"123"),row(480,2,"456","13")),now)!!.snapshot.nextArrivalAtEpochMillis)
    }
    @Test fun accessRoutesUseTheSameCalculatorAndDoNotReplaceTheOriginalEndpoints() {
        assertTrue(requiresTransitAccess(MapPoint(127.0,37.2),MapPoint(126.97,37.55)))
        assertFalse(requiresTransitAccess(point,point))
        val root=JourneyScreenState(FeatureMode.ROUTE)
        root.departure.selected=JourneyPlace("seoul","서울역","",MapPoint(126.97,37.55))
        root.destination.selected=JourneyPlace("yongsan","용산역","",MapPoint(126.96,37.53))
        root.accessJourney=JourneyScreenState(FeatureMode.ROUTE)
        root.accessJourney!!.departure.selected=JourneyPlace("gps","현재 위치","",point)
        assertEquals("서울역",root.departure.selected!!.name)
        assertEquals("용산역",root.destination.selected!!.name)
        assertEquals(AutoGuidanceStatus.NEXT_VEHICLE,assess(360,240).status)
    }
    @Test fun villageAndCityBusRouteMatchingAcceptsUniqueOfficialStopOccurrence() {
        for (name in listOf("동대문01","273")) {
            val route=OfficialSeoulBusRoute("123456789","234567891",name,"12","","",true)
            assertEquals(route,resolveOfficialSeoulBusRoute(listOf(route),route.stopId,name,"서울역"))
        }
    }
    @Test fun arrivalAlertsDoNotUrgeBoardingAMissedFirstVehicleAndDoNotRepeat() {
        val gate=JourneyMonitoringAlertGate();val value=assess(360,30)
        val snapshot=RealtimeArrivalSnapshot(identity,now+30_000,fetchedAtEpochMillis=now,validForMillis=60_000)
        assertEquals(JourneyMonitoringAlertKind.GUIDANCE_CHANGED,gate.claim(identity,"bus1",value,snapshot,now)!!.kind)
        assertNull(gate.claim(identity,"bus1",value,snapshot,now+1000))
    }
    @Test fun missingExpiredVehicleResponseCannotKeepTheOldMissedDecision() {
        val previous=assess(360,240)
        val later=now+250_000
        val result=calculateAutoGuidance(AutoGuidanceInput(GuidanceLocation(point,10.0,later),
            GuidanceBoarding(identity,point,true),GuidanceWalkingRoute(point,point,identity,360,later,120_000,600),
            null,true,previousAssessment=previous),later)
        assertEquals(AutoGuidanceStatus.UNKNOWN,result.status)
        assertNull(result.nextVehicleState)
        assertNull(result.recommendedArrivalAtEpochMillis)
        val another=calculateAutoGuidance(AutoGuidanceInput(GuidanceLocation(point,10.0,later),
            GuidanceBoarding(identity.copy(routeId="234000002"),point,true),null,null,true,previousAssessment=previous),later)
        assertEquals(AutoGuidanceStatus.UNKNOWN,another.status)
    }
}
