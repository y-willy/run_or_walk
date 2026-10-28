package com.example.run_or_walk

import android.Manifest
import android.app.Application
import android.content.Intent
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE,application=Application::class)
class GuidanceFollowupReproductionTest {
    private val point=MapPoint(127.0,37.0)
    private val stop=OfficialSeoulBusStop("105900050","06510","정류소",point)
    private val route=OfficialSeoulBusRoute(stop.stopId,"105900003","동대문01","4","회기역","2")
    private val request get()=JourneyMonitoringRequest(stop.name,point,route.identity,busStop=stop,busRoute=route)
    @Test fun serviceMustNotCancelTheOldJourneyBeforeReplacementEvidenceIsReady() {
        val lifecycle=Robolectric.buildService(JourneyMonitoringService::class.java).create()
        val service=lifecycle.get()
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val oldJob=Job()
        service.javaClass.getDeclaredField("currentRequest").apply {isAccessible=true}.set(service,request)
        service.javaClass.getDeclaredField("monitoringJob").apply {isAccessible=true}.set(service,oldJob)
        val previous=JourneyMonitoringState(true,request,message="걸으세요")
        JourneyMonitoringController.publish(previous)
        val nextRoute=route.copy(stopOrder="5")
        val next=request.copy(busRoute=nextRoute,identity=nextRoute.identity)
        try {
            service.onStartCommand(Intent().setAction(JourneyMonitoringService.ACTION_START)
                .putExtra(JourneyMonitoringService.EXTRA_REQUEST,JourneyMonitoringRequestCodec.encode(next)),0,2)
            assertFalse("New data is not ready; the old tracking job must remain alive",oldJob.isCancelled)
            assertEquals(request,JourneyMonitoringController.state.value.request)
        } finally {lifecycle.destroy()}
    }
    @Test fun aSlowFavoriteMustNotBlockAnUnrelatedStop()=runBlocking {
        val delayed=CompletableDeferred<Unit>()
        val otherStop=stop.copy(stopId="105900051",arsId="06511")
        val otherRoute=route.copy(stopId=otherStop.stopId)
        val other=FavoriteBus(request.copy(busStop=otherStop,busRoute=otherRoute,identity=otherRoute.identity))
        val loader=FavoriteBusArrivalLoader(seoulRoutes={s ->
            if(s.stopId==stop.stopId) {delayed.await();listOf(route)} else listOf(otherRoute)
        },seoulArrivals={_,_->emptyList()})
        val first=async {loader.load(FavoriteBus(request))}
        yield()
        try {
            val second=withTimeoutOrNull(500) {loader.load(other)}
            assertNotNull("Independent stop should finish before the stalled stop",second)
            assertNotNull(second!!.selection)
        } finally {delayed.complete(Unit);first.await()}
    }
}
