package com.example.run_or_walk

import org.junit.Assert.*
import org.junit.Test

class FavoriteBusTest {
    private val point=MapPoint(127.052311,37.594127)
    private val stop=OfficialSeoulBusStop("105900050","06510","경희대의료원.경희여중고",point)
    private val route=OfficialSeoulBusRoute(stop.stopId,"105900003","동대문01","4","회기역","2")
    private fun seoul(order: String="4")=FavoriteBus(JourneyMonitoringRequest(stop.name,point,route.copy(stopOrder=order).identity,
        busStop=stop,busRoute=route.copy(stopOrder=order)))
    @Test fun codecRestoresOfficialIdsAndAliasesWithoutLiveDataOrPersonalSettings() {
        val original=seoul().copy(alias="학교 가는 버스",request=seoul().request.copy(settings=JourneySettings(measuredRunningSpeedKmh=9.0),vibrationEnabled=true))
        val payload=FavoriteBusCodec.encode(listOf(original,original))
        val restored=FavoriteBusCodec.decode(payload).single()
        assertEquals(original.key,restored.key);assertEquals(original.alias,restored.alias)
        assertEquals(stop,restored.request.busStop);assertEquals(route,restored.request.busRoute)
        assertEquals(JourneySettings(),restored.request.settings);assertFalse(restored.request.vibrationEnabled)
        assertFalse(payload.contains("arrivalSeconds"));assertFalse(payload.contains("vehicleId"))
    }
    @Test fun sameBusNumberOppositeOrdersAndDifferentProvidersRemainDistinct() {
        val match=GyeonggiOfficialMatch("200000001","234000001","12","동대문01","서울역",point)
        val gg=FavoriteBus(JourneyMonitoringRequest("정류장",point,match.identity,gyeonggiMatch=match))
        val values=FavoriteBusCodec.decode(FavoriteBusCodec.encode(listOf(seoul(),seoul("5"),gg)))
        assertEquals(3,values.size);assertEquals(3,values.map { it.key }.toSet().size)
        val renamed=match.copy(routeName="새 표기")
        assertEquals(gg.key,FavoriteBus(JourneyMonitoringRequest("정류장",point,renamed.identity,gyeonggiMatch=renamed)).key)
    }
    @Test fun invalidMixedProviderAndSubwaySelectionsAreRejected() {
        assertTrue(FavoriteBusCodec.decode("invalid").isEmpty())
        assertTrue(FavoriteBusCodec.decode("""{"version":99,"items":[]}""").isEmpty())
        val invalid=seoul().copy(request=seoul().request.copy(identity=route.identity.copy(authority="GBIS")))
        assertFalse(invalid.isValid());assertTrue(FavoriteBusCodec.decode(FavoriteBusCodec.encode(listOf(invalid))).isEmpty())
    }
    @Test fun restoredFavoriteUsesExactOfficialSelectionAndStartsWithNoCachedEtaOrGps() {
        val state=JourneyScreenState(FeatureMode.STOP)
        state.destination.selected=JourneyPlace("old","이전 목적지","",point)
        restoreFavoriteBus(state,seoul())
        assertNull(state.destination.selected)
        assertEquals(route.identity,state.confirmedServiceIdentity)
        assertEquals(route,state.selectedBusRoute);assertEquals(stop,state.selectedBusStop)
        assertTrue(state.boardingConfirmed);assertTrue(state.isBusJourney)
        assertNull(state.activeArrivalSnapshot);assertNull(state.location);assertNull(state.guidanceWalkingRoute)
        assertEquals(point,state.boarding!!.point)
    }
    @Test fun gyeonggiFavoriteRestoresWithoutKakaoRouteOrGuessingAnotherStop() {
        val match=GyeonggiOfficialMatch("200000001","234000001","12","M5107","서울역",point)
        val value=FavoriteBus(JourneyMonitoringRequest("정류장",point,match.identity,gyeonggiMatch=match))
        val restored=FavoriteBusCodec.decode(FavoriteBusCodec.encode(listOf(value))).single()
        val state=JourneyScreenState(FeatureMode.STOP);restoreFavoriteBus(state,restored)
        assertEquals(match,state.selectedGyeonggiMatch);assertEquals(match.identity,state.confirmedServiceIdentity)
        assertNull(state.selectedRoute);assertTrue(state.isBusJourney)
    }
    @Test fun favoriteSelectionSurvivesSessionRestartWithOfficialGyeonggiIdentity() {
        val match=GyeonggiOfficialMatch("200000001","234000001","12","M5107","서울역",point)
        val screens=restoreJourneyScreens(null)
        restoreFavoriteBus(screens.getValue(FeatureMode.STOP),FavoriteBus(JourneyMonitoringRequest("정류장",point,match.identity,gyeonggiMatch=match)))
        val restored=restoreJourneyScreens(decodeJourneySession(encodeJourneySession(captureJourneySession(FeatureMode.STOP,screens))))
        assertEquals(match,restored.getValue(FeatureMode.STOP).selectedGyeonggiMatch)
    }
    @Test fun loaderUsesSavedOfficialIdsAndRejectsMissingDirectionWithoutAnotherRouteFallback()=kotlinx.coroutines.runBlocking {
        var calls=0
        val now=System.currentTimeMillis()
        val snapshot=RealtimeArrivalSnapshot(route.identity,now+240000,fetchedAtEpochMillis=now,validForMillis=600000,receivedAtEpochMillis=now)
        val loader=FavoriteBusArrivalLoader(seoulRoutes={ assertEquals(stop,it);listOf(route) },seoulArrivals={ actualStop,actualRoute ->
            assertEquals(stop,actualStop);assertEquals(route,actualRoute);calls++
            listOf(OfficialSeoulBusArrival(route.identity,"bus",route.name,route.direction,snapshot)) })
        val result=loader.load(seoul())
        assertEquals(snapshot.firstArrivalAtEpochMillis,result.snapshot!!.firstArrivalAtEpochMillis)
        assertEquals(seoul().key,result.selection!!.key);assertEquals(1,calls)
        val retired=FavoriteBusArrivalLoader(seoulRoutes={listOf(route.copy(stopOrder="5"))},seoulArrivals={_,_->error("must not query another direction")})
        val rejected=retired.load(seoul())
        assertNull(rejected.selection);assertTrue(rejected.message.contains("다시 선택"))
    }
    @Test fun gyeonggiFavoritesAtSameStopShareOneResponseAndRateLimitsStayVisible()=kotlinx.coroutines.runBlocking {
        var queries=0
        val one=GyeonggiOfficialMatch("200000001","234000001","12","M5107","서울역",point)
        val two=one.copy(routeId="234000002",routeName="5100")
        val loader=FavoriteBusArrivalLoader(ggVia={ listOf(one,two).map { GyeonggiStopRoute(it.routeId,it.routeName,it.staOrder,it.destination) } },
            ggArrivals={ queries++;emptyList() })
        for(match in listOf(one,two)) loader.load(FavoriteBus(JourneyMonitoringRequest("정류장",point,match.identity,gyeonggiMatch=match)))
        assertEquals(1,queries)
        val limited=FavoriteBusArrivalLoader(seoulRoutes={ throw SeoulBusApiException("limit",SeoulBusFailure.REQUEST_LIMIT,System.currentTimeMillis()+30000) })
        assertTrue(limited.load(seoul()).message.contains("조회 제한"))
        assertNull(limited.load(seoul()).selection)
    }

}
