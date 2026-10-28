package com.example.run_or_walk

import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class FinalReliabilityPolicyTest {
    private val initial=1800000000000L
    private val point=MapPoint(127.0,37.0)
    private val stop=OfficialSeoulBusStop("105900050","06510","정류소",point)
    private val route=OfficialSeoulBusRoute(stop.stopId,"105900003","동대문01","4","회기역","2")
    private val favorite=FavoriteBus(JourneyMonitoringRequest(stop.name,point,route.identity,busStop=stop,busRoute=route))
    private fun row(at:Long,slot:Int,eta:Int)=OfficialSeoulBusArrival(route.identity,"vehicle-$slot",route.name,route.direction,
        RealtimeArrivalSnapshot(route.identity,at+eta*1000,fetchedAtEpochMillis=at,validForMillis=600000,receivedAtEpochMillis=at),
        gatewayFetchedAtEpochMillis=at,officialSlot=slot,sourceEtaSeconds=eta.toLong())
    @Test fun favoritesRefreshAfterThirtySecondsKeepOtherVehiclesAndNeverRenewTheirReceipt()=runBlocking {
        var now=initial;var calls=0
        val loader=FavoriteBusArrivalLoader(seoulRoutes={listOf(route)},seoulArrivals={_,_->
            calls++;if(calls==1) listOf(row(now,1,240),row(now,2,480)) else listOf(row(now,1,210))},clock={now})
        val first=loader.load(favorite);assertEquals(initial+480000,first.snapshot!!.nextArrivalAtEpochMillis)
        now+=29999;loader.load(favorite);assertEquals(1,calls)
        now++;val refreshed=loader.load(favorite);assertEquals(2,calls)
        assertEquals(now+210000,refreshed.snapshot!!.firstArrivalAtEpochMillis)
        assertEquals(initial+480000,refreshed.snapshot.nextArrivalAtEpochMillis)
        now=initial+61000;val expiredNext=loader.load(favorite)
        assertNull(expiredNext.snapshot!!.nextArrivalAtEpochMillis)
    }
    @Test fun temporaryFailureKeepsOnlyUnexpiredEtaAndBacksOffWithoutRequestStorm()=runBlocking {
        var now=initial;var calls=0
        val loader=FavoriteBusArrivalLoader(seoulRoutes={listOf(route)},seoulArrivals={_,_->
            if(++calls>1) throw IOException("offline") else listOf(row(now,1,240))},clock={now})
        loader.load(favorite);now+=30000
        val failed=loader.load(favorite);assertTrue(failed.failed);assertNotNull(failed.snapshot)
        repeat(20) {loader.load(favorite)};assertEquals(2,calls)
        now=initial+61000;val expired=loader.load(favorite);assertNull(expired.snapshot)
        repeat(20) {loader.load(favorite)};assertEquals(3,calls)
        assertTrue(expired.retryAtEpochMillis!!>=now+60000)
    }
    @Test fun serverBackoffAppliesAcrossFavoritesAndCannotBeBypassedByManualRefresh()=runBlocking {
        var now=initial;var calls=0
        val loader=FavoriteBusArrivalLoader(seoulRoutes={calls++;throw SeoulBusApiException("limit",SeoulBusFailure.REQUEST_LIMIT,initial+86400000)},clock={now})
        assertEquals(initial+86400000,loader.load(favorite).retryAtEpochMillis)
        val next=favorite.copy(request=favorite.request.copy(busRoute=route.copy(routeId="105900004"),identity=route.copy(routeId="105900004").identity))
        now+=60000;repeat(10) {loader.load(next);loader.load(favorite)};assertEquals(1,calls)
    }
    @Test fun longNetworkOutageNeverStopsAutomaticRecoveryOrBypassesBackoff()=runBlocking {
        var now=initial;var calls=0;var offline=true
        val loader=FavoriteBusArrivalLoader(seoulRoutes={calls++;if(offline) throw IOException("offline") else listOf(route)},
            seoulArrivals={_,_->listOf(row(now,1,240))},clock={now})
        var result=loader.load(favorite)
        repeat(12) {
            val due=requireNotNull(result.retryAtEpochMillis)
            repeat(5) {loader.load(favorite)}
            val before=calls
            now=due-1;loader.load(favorite);assertEquals(before,calls)
            now=due;result=loader.load(favorite);assertEquals(before+1,calls)
        }
        assertEquals(FavoriteBusFailure.TRANSIENT,result.failureType)
        assertTrue(result.message.contains("연결 상태 확인 필요"))
        offline=false;now=requireNotNull(result.retryAtEpochMillis)
        val recovered=loader.load(favorite)
        assertNotNull(recovered.snapshot);assertFalse(recovered.failed);assertNull(recovered.failureType)
        now+=30000;val previous=calls;loader.load(favorite);assertEquals(previous+1,calls)
    }
    @Test fun authenticationFailuresWaitForSafeExplicitRecheckInsteadOfRetryingForever()=runBlocking {
        var now=initial;var configured=false
        val loader=FavoriteBusArrivalLoader(seoulRoutes={if(!configured) throw SeoulBusApiException("auth",SeoulBusFailure.ACCESS_DENIED) else listOf(route)},
            seoulArrivals={_,_->listOf(row(now,1,240))},clock={now})
        val rejected=loader.load(favorite)
        assertEquals(FavoriteBusFailure.CONFIGURATION,rejected.failureType)
        assertEquals(initial+300000,rejected.retryAtEpochMillis)
        configured=true;now=requireNotNull(rejected.retryAtEpochMillis)
        assertTrue(loader.load(favorite).failed)
        assertEquals(Long.MAX_VALUE,loader.nextRefreshAt(favorite))
        assertNotNull(loader.load(favorite,recheckConfiguration=true).snapshot)
    }
    @Test fun providerConfirmedDirectionChangeRetiresFavoriteAndItsOldEta()=runBlocking {
        var now=initial;var moved=false
        val loader=FavoriteBusArrivalLoader(seoulRoutes={listOf(if(moved) route.copy(stopOrder="5") else route)},
            seoulArrivals={_,_->listOf(row(now,1,240))},clock={now})
        assertNotNull(loader.load(favorite).snapshot);now+=30000;moved=true
        val result=loader.load(favorite);assertNull(result.selection);assertNull(result.snapshot)
    }
    @Test fun sameStopFavoritesShareRoutesAndConcurrentQueriesAreBounded()=runBlocking {
        var routeCalls=0;var active=0;var maximum=0
        val routeTwo=route.copy(routeId="105900004")
        val loader=FavoriteBusArrivalLoader(seoulRoutes={routeCalls++;listOf(route,routeTwo)},seoulArrivals={_,_->
            active++;maximum=maxOf(maximum,active);yield();active--;emptyList()},clock={initial})
        val second=FavoriteBus(favorite.request.copy(busRoute=routeTwo,identity=routeTwo.identity))
        coroutineScope { listOf(favorite,second,favorite).map {async {loader.load(it)}}.awaitAll() }
        assertEquals(1,routeCalls);assertEquals(2,maximum)
    }
    @Test fun mapOnlyRequestsObeyVisibilityInFlightAndBackoffWithoutDeletingSuccessAge() {
        val gate=MapGeometryRequestGate()
        assertFalse(gate.begin("route",0,false));assertTrue(gate.begin("route",0,true))
        assertFalse(gate.begin("route",100,true));gate.complete(false,100)
        assertFalse(gate.begin("route",30000,true));assertTrue(gate.begin("route",30100,true));gate.complete(false,30100)
        assertFalse(gate.begin("route",60100,true));assertTrue(gate.begin("route",90100,true));gate.complete(true,90200)
        assertTrue(gate.isGeometryFresh(100000));assertFalse(gate.isGeometryFresh(210201))
        assertTrue(gate.begin("new-destination",100000,true))
    }
    @Test fun mapCoordinatesReuseSmallGpsMovementAndRefreshChangedDestination() {
        assertFalse(shouldRefreshAccessMap(point,point,point.copy(x=127.00001),point,true))
        assertTrue(shouldRefreshAccessMap(point,point,point.copy(x=127.01),point,true))
        assertTrue(shouldRefreshAccessMap(point,point,point,point.copy(y=37.001),true))
    }
    private val subwayId=OfficialBoardingIdentity("seoul","1075075241","1075","상행","왕십리")
    private fun estimate(at:Long,source:OfficialTimetableEstimateSource=OfficialTimetableEstimateSource.OFFICIAL_TIMETABLE_POSITION)=
        OfficialTimetableEstimate(subwayId,"train-$at",java.time.LocalDate.of(2026,10,28),OfficialServiceDayType.WEEKDAY,
            OfficialTrainOperationType.LOCAL,"pattern","previous",3,at,at,at,0,0,initial,initial,0,initial+60000,
            OfficialTimetableSource("official","official","v1",initial-100000),"calendar",source=source)
    @Test fun eachMapVehicleRetainsItsOwnOfficialOrTimetableSourceAndPrecision() {
        val live=RealtimeArrivalSnapshot(subwayId,initial+300000,fetchedAtEpochMillis=initial,validForMillis=600000,receivedAtEpochMillis=initial)
        val evidence=TimetableArrivalEvidence(subwayId,estimate(initial+300000),estimate(initial+780000,OfficialTimetableEstimateSource.OFFICIAL_TIMETABLE_SCHEDULE))
        val (first,next)=mapArrivalRows(live,evidence,initial)
        assertEquals(GuidanceArrivalSource.OFFICIAL_REALTIME,first!!.source);assertEquals(ArrivalTimePrecision.SECONDS,first.precision)
        assertEquals(GuidanceArrivalSource.OFFICIAL_TIMETABLE_SCHEDULE,next!!.source);assertEquals(ArrivalTimePrecision.MINUTES,next.precision)
        val both=mapArrivalRows(live.copy(nextArrivalAtEpochMillis=initial+600000),evidence,initial)
        assertEquals(GuidanceArrivalSource.OFFICIAL_REALTIME,both.second!!.source)
    }
    @Test fun mapRowsRejectExpiredOrWrongDirectionAndNeverInventNext() {
        val first=estimate(initial+300000)
        val evidence=TimetableArrivalEvidence(subwayId,first)
        assertNotNull(mapArrivalRows(null,evidence,initial).first);assertNull(mapArrivalRows(null,evidence,initial).second)
        assertNull(mapArrivalRows(null,evidence,initial+61000).first)
        assertNull(mapArrivalRows(null,evidence.copy(first=first.copy(identity=subwayId.copy(directionId="하행"))),initial).first)
    }
}
