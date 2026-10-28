package com.example.run_or_walk

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

class SeoulRepositoryConcurrencyTest {
    private fun response(url:String):String {
        val stop=URI(url).rawQuery.split('&').single {it.startsWith("stopId=")}.substringAfter('=')
        return JSONObject().put("provider","seoul-bus").put("fetchedAt","2026-10-28T00:00:00Z").put("stopId",stop).put("routes",org.json.JSONArray()).toString()
    }
    @Test fun realRepositoryKeepsUnrelatedStopIndependentAndSameKeySingleFlight()=runBlocking {
        val one=OfficialSeoulBusStop("105900050","06510","A",MapPoint(127.0,37.0))
        val two=one.copy(stopId="105900051")
        val hold=CompletableDeferred<Unit>();val started=CompletableDeferred<Unit>()
        val calls=AtomicInteger()
        val repository=SeoulBusRepository("https://example.test","",transport={url ->
            calls.incrementAndGet()
            if(url.contains(one.stopId)) {started.complete(Unit);hold.await()}
            response(url)
        })
        val first=async {repository.routes(one)}
        started.await()
        try {
            assertNotNull(withTimeoutOrNull(500) {repository.routes(two)})
            val duplicate=async {repository.routes(two)};duplicate.await()
            assertEquals(2,calls.get())
        } finally {hold.complete(Unit);first.await()}
    }
    @Test fun concurrentRepositoryBackoffKeepsTheLongestServerDeadline()=runBlocking {
        val gate=SeoulBusRequestBackoff();val now=1_800_000_000_000L
        coroutineScope {
            (1..20).map {i ->async(Dispatchers.Default) {
                gate.record(SeoulBusApiException("limit",SeoulBusFailure.REQUEST_LIMIT,now+i*1000))
            }}.awaitAll()
        }
        try {gate.check(now+19000);fail("Longest deadline must still block")}
        catch(error:SeoulBusApiException) {assertEquals(now+20000,error.retryAfterEpochMillis)}
        gate.check(now+20000)
    }
}
