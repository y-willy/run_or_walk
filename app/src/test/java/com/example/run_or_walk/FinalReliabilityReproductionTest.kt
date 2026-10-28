package com.example.run_or_walk

import android.app.Application
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=Application::class)
class FinalReliabilityReproductionTest {
    private val point=MapPoint(127.0,37.0)
    private val stop=OfficialSeoulBusStop("105900050","06510","정류소",point)
    private val route=OfficialSeoulBusRoute(stop.stopId,"105900003","동대문01","4","회기역","2")
    private val request get()=JourneyMonitoringRequest(stop.name,point,route.identity,busStop=stop,busRoute=route)
    @Test fun failedReplacementMustNotEraseAnAlreadyRunningJourney() {
        val previous=JourneyMonitoringState(true,request,message="걸으세요")
        JourneyMonitoringController.publish(previous)
        try {
            assertFalse(JourneyMonitoringController.start(RuntimeEnvironment.getApplication(),request.copy(identity=route.identity.copy(stopId="invalid"))))
            assertTrue(JourneyMonitoringController.state.value.running)
            assertEquals(previous.request,JourneyMonitoringController.state.value.request)
        } finally {JourneyMonitoringController.publish(JourneyMonitoringState())}
    }
    @Test fun temporaryFavoriteFailureMustKeepTheOriginalUnexpiredEta()=runBlocking {
        var now=System.currentTimeMillis()
        val snapshot=RealtimeArrivalSnapshot(route.identity,now+240000,fetchedAtEpochMillis=now,validForMillis=600000,receivedAtEpochMillis=now)
        var calls=0
        val loader=FavoriteBusArrivalLoader(seoulRoutes={listOf(route)},seoulArrivals={_,_->
            if(++calls>1) throw IOException("offline")
            listOf(OfficialSeoulBusArrival(route.identity,"vehicle",route.name,route.direction,snapshot))},clock={now})
        val favorite=FavoriteBus(request)
        assertNotNull(loader.load(favorite).snapshot)
        now+=30000
        assertEquals(snapshot.firstArrivalAtEpochMillis,loader.load(favorite).snapshot?.firstArrivalAtEpochMillis)
        assertEquals(2,calls)
    }
    @Test @Config(sdk=[28]) fun foregroundStartExceptionPreservesTheRunningJourneyAndItsData() {
        val app=RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
        val previous=JourneyMonitoringState(true,request,message="걸으세요")
        val failing=object:android.content.ContextWrapper(app) {
            override fun startForegroundService(intent:android.content.Intent):android.content.ComponentName? = throw IllegalStateException("not allowed")
        }
        JourneyMonitoringController.publish(previous)
        try {
            assertFalse(JourneyMonitoringController.start(failing,request))
            assertEquals(previous,JourneyMonitoringController.state.value)
            assertNotNull(JourneyMonitoringController.lastStartError)
        } finally {JourneyMonitoringController.publish(JourneyMonitoringState())}
    }
    @Test @Config(sdk=[28]) fun validReplacementStagesNewEvidenceUntilTheServiceAcceptsIt() {
        val app=RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
        val previous=JourneyMonitoringState(true,request,message="걸으세요")
        val nextRoute=route.copy(stopOrder="5")
        val next=request.copy(busRoute=nextRoute,identity=nextRoute.identity)
        val staged=JourneyMonitoringState(true,next)
        var starts=0
        val context=object:android.content.ContextWrapper(app) {
            override fun startForegroundService(intent:android.content.Intent):android.content.ComponentName? {
                starts++;return android.content.ComponentName(app,JourneyMonitoringService::class.java)
            }
        }
        JourneyMonitoringController.publish(previous)
        try {
            assertTrue(JourneyMonitoringController.start(context,next,staged))
            assertEquals(1,starts);assertEquals(previous,JourneyMonitoringController.state.value)
            assertEquals(staged,JourneyMonitoringController.consumeHandover(next))
            assertNull(JourneyMonitoringController.consumeHandover(next))
        } finally {JourneyMonitoringController.publish(JourneyMonitoringState())}
    }

}
