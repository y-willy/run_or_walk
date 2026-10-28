package com.example.run_or_walk

import org.junit.Assert.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.junit.Test

class WalkingRequestRecoveryTest {
    private val now=1_800_000_000_000L
    private val origin=MapPoint(127.056,37.245)
    private val end=MapPoint(127.057,37.246)
    private val id=OfficialBoardingIdentity("seoul-bus","105900050","105900003","4","105900003")
    @Test fun bindingCompletedDuringHttpRequestMustNotDiscardItsValidPhysicalWalkingResult() {
        val s=JourneyScreenState(FeatureMode.ROUTE)
        s.boarding=JourneyPlace("boarding","승차점","",end);s.location=GuidanceLocation(origin,5.0,now)
        // The request began with null service ID; official lookup finishes while HTTP is pending.
        s.confirmedServiceIdentity=id
        assertTrue(s.storeWalkingResult(s.location!!,s.boarding!!,null,KakaoWalkingRoute(187,196,listOf(origin,end)),JourneySettings(),null,now,now))
        assertEquals(id,s.guidanceWalkingRoute!!.boardingIdentity)
        assertNotNull(s.walkRoute)
    }

    private fun route()=KakaoWalkingRoute(187,196,listOf(origin,end),origin,end)
    @Test fun successfulRequestBoundRouteWithSnappedStartOver50MetersIsNotDiscarded() {
        val snapped=origin.copy(y=origin.y+54.0/111195.0)
        val response=KakaoWalkingRoute(187,196,listOf(snapped,end),origin,end)
        assertTrue(validWalkingAccess(response,origin,end))
        assertFalse(validWalkingAccess(response.copy(requestedOrigin=null,requestedDestination=null),origin,end))
        assertFalse(validWalkingAccess(response,origin,end.copy(x=end.x+0.01)))
        val tracker=WalkingRouteTracker(origin,response,now)
        assertTrue(tracker.update(GuidanceLocation(origin,5.0,now),now)!!.onRoute)
        assertEquals(187,tracker.update(GuidanceLocation(origin,5.0,now),now)!!.remainingMeters)
    }
    @Test fun timeoutReleasesLoadingAndRetriesAutomaticallyWithoutLosingTheThirtySecondFloor()=runBlocking {
        var elapsed=0L;var calls=0
        val gate=WalkingRefreshCadence()
        val query:suspend (MapPoint,MapPoint)->KakaoWalkingRoute={_,_->calls++;if(calls==1) awaitCancellation() else route()}
        try {gate.fetch(origin,end,{elapsed},{now},20,query);fail("timeout expected")}
        catch(error:KakaoApiException) {assertEquals(KakaoFailure.TIMEOUT,error.failure)}
        assertFalse(gate.requesting);assertNotNull(gate.error)
        elapsed=29999;assertNull(gate.fetch(origin,end,{elapsed},{now},20,query));assertEquals(1,calls)
        elapsed=30000;assertNotNull(gate.fetch(origin,end,{elapsed},{now+elapsed},1000,query))
        assertFalse(gate.requesting);assertNull(gate.error);assertEquals(2,calls)
    }
    @Test fun sameBoardingRequestIsSingleFlightAndRetryAfterCannotBeBypassed()=runBlocking {
        var elapsed=0L;val waiting=CompletableDeferred<KakaoWalkingRoute>();val gate=WalkingRefreshCadence();var calls=0
        val first=async {runCatching {gate.fetch(origin,end,{elapsed},{now},query={_,_->calls++;waiting.await()})}}
        yield();assertTrue(gate.requesting)
        assertNull(gate.fetch(origin,end,{elapsed},{now},query={_,_->calls++;route()}));assertEquals(1,calls)
        waiting.completeExceptionally(KakaoApiException(KakaoFailure.REQUEST_LIMIT,429,now+120000))
        assertTrue(first.await().exceptionOrNull() is KakaoApiException)
        elapsed=119999;gate.permitConfigurationRetry()
        assertNull(gate.fetch(origin,end,{elapsed},{now+elapsed},query={_,_->calls++;route()}))
        assertEquals(1,calls)
        elapsed=120000
        assertNotNull(gate.fetch(origin,end,{elapsed},{now+elapsed},query={_,_->calls++;route()}));assertEquals(2,calls)
    }
    @Test fun authenticationErrorsStayDistinctAndDoNotRetryForever()=runBlocking {
        val gate=WalkingRefreshCadence();var elapsed=0L;var calls=0
        val query:suspend (MapPoint,MapPoint)->KakaoWalkingRoute={_,_->calls++;throw KakaoApiException(KakaoFailure.AUTHENTICATION,401)}
        try {gate.fetch(origin,end,{elapsed},{now},query=query)} catch(_:KakaoApiException) {}
        elapsed=600000
        assertNull(gate.fetch(origin,end,{elapsed},{now+elapsed},query=query));assertEquals(1,calls)
        gate.permitConfigurationRetry()
        try {gate.fetch(origin,end,{elapsed},{now+elapsed},query=query)} catch(_:KakaoApiException) {}
        assertEquals(2,calls);assertFalse(gate.requesting)
    }
    @Test fun cancelledAndFailedRequestsAlwaysReleaseTheirRequestState()=runBlocking {
        var elapsed=0L;val gate=WalkingRefreshCadence()
        val request=launch {gate.fetch(origin,end,{elapsed},{now},query={_,_->awaitCancellation()})}
        yield();assertTrue(gate.requesting);request.cancelAndJoin();assertFalse(gate.requesting)
        elapsed=30000
        assertNotNull(gate.fetch(origin,end,{elapsed},{now+elapsed},query={_,_->route()}))
    }
    @Test fun gpsStreamRecoversAfterProviderFailureWithoutReopeningTheActivity()=runBlocking {
        var attempts=0;var failures=0
        val fix=GuidanceLocation(origin,5.0,now)
        val result=recoveringLocationSamples({flow {if(++attempts==1) throw DeviceLocationException(LocationFailure.UNAVAILABLE);emit(fix)}},
            onFailure={failures++},retryMillis=1).first()
        assertEquals(fix,result);assertEquals(2,attempts);assertEquals(1,failures)
    }

    @Test fun gpsAgeUsesTheOriginalSensorClockAndNotTheWallClockAtCallbackTime() {
        var elapsed=1000L
        val clock=GuidanceDisplayClock(now,1000L) {elapsed}
        val sample=GuidanceLocation(origin,5.0,now+5000,observedElapsedRealtimeMillis=1000L)
        assertEquals(now,clock.alignLocation(sample).observedAtEpochMillis)
        elapsed=32001
        assertEquals(LocationFailure.STALE,locationSampleFailure(clock.alignLocation(sample),clock.now()))
        assertEquals(-1L,clock.alignLocation(sample.copy(observedElapsedRealtimeMillis=elapsed+1)).observedAtEpochMillis)
    }
}
