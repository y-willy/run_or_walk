package com.example.run_or_walk

import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic official rows; no operating responses or external test directory. */
class FavoriteConcurrencyFollowupTest {
    private val now=1_800_000_000_000L
    private val point=MapPoint(127.0,37.0)
    private fun favorite(i:Int):FavoriteBus {
        val stop=OfficialSeoulBusStop("1059000"+i.toString().padStart(2,'0'),"06510","정류소 $i",point)
        val route=OfficialSeoulBusRoute(stop.stopId,"105900003","동대문01","4","회기역","2")
        return FavoriteBus(JourneyMonitoringRequest(stop.name,point,route.identity,busStop=stop,busRoute=route))
    }
    @Test fun twelveDistinctStopsHaveTwoConcurrentQueriesAndNoDuplicateSameKeyRequests()=runBlocking {
        var active=0;var maximum=0;var metadata=0;var arrivals=0
        val favorites=(1..12).map(::favorite)
        val loader=FavoriteBusArrivalLoader(seoulRoutes={stop ->
            active++;maximum=maxOf(maximum,active);metadata++;delay(10);active--
            listOf(favorites.single {it.request.busStop==stop}.request.busRoute!!)
        },seoulArrivals={_,_->arrivals++;emptyList()},clock={now})
        coroutineScope { (favorites+favorites).map {async {loader.load(it)}}.awaitAll() }
        assertEquals(2,maximum);assertEquals(12,metadata);assertEquals(12,arrivals)
        repeat(60) {favorites.forEach {loader.cached(it);assertEquals(now+30000,loader.nextRefreshAt(it))}}
        assertEquals(12,arrivals)
    }
    @Test fun timeoutReleasesPermitAndKeepsUnexpiredEtaThenRecovers()=runBlocking {
        val f=favorite(1);var time=now;var slow=false
        val loader=FavoriteBusArrivalLoader(seoulRoutes={listOf(f.request.busRoute!!)},seoulArrivals={_,_->
            if(slow) awaitCancellation()
            listOf(OfficialSeoulBusArrival(f.request.identity,"vehicle",f.routeName,f.direction,
                RealtimeArrivalSnapshot(f.request.identity,time+240000,fetchedAtEpochMillis=time,
                    validForMillis=600000,receivedAtEpochMillis=time)))
        },clock={time},timeoutMillis=40)
        val original=loader.load(f);slow=true;time+=30000
        val failed=loader.load(f)
        assertTrue(failed.failed);assertEquals(FavoriteBusFailure.TIMEOUT,failed.failureType)
        assertEquals(original.snapshot,failed.snapshot)
        slow=false;time=failed.retryAtEpochMillis!!
        assertFalse(loader.load(f).failed)
    }
    @Test fun transientFailureAtOneStopDoesNotBackOffHealthyStop()=runBlocking {
        val one=favorite(1);val two=favorite(2);var calls=0
        val loader=FavoriteBusArrivalLoader(seoulRoutes={stop ->
            if(stop==one.request.busStop) throw IOException("offline") else listOf(two.request.busRoute!!)
        },seoulArrivals={_,_->calls++;emptyList()},clock={now})
        assertTrue(loader.load(one).failed);assertNotNull(loader.load(two).selection);assertEquals(1,calls)
    }
    @Test fun providerDeadlineReceivedWhileQueuedAlsoBlocksQueuedAndManualRequests()=runBlocking {
        val a=favorite(1);val b=favorite(2);val c=favorite(3)
        val release=CompletableDeferred<Unit>();var calls=0
        val loader=FavoriteBusArrivalLoader(seoulRoutes={stop ->
            calls++
            if(stop==b.request.busStop) release.await()
            throw SeoulBusApiException("limit",SeoulBusFailure.REQUEST_LIMIT,now+86400000)
        },clock={now})
        coroutineScope {
            val first=async {loader.load(b)};yield()
            val second=async {loader.load(a)};yield()
            val third=async {loader.load(c)};yield()
            release.complete(Unit);awaitAll(first,second,third)
        }
        repeat(20) {loader.load(a);loader.load(b);loader.load(c)}
        assertEquals(2,calls)
        assertEquals(now+86400000,loader.nextRefreshAt(c))
    }
}
