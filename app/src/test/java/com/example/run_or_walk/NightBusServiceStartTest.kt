package com.example.run_or_walk

import android.Manifest
import android.app.Application
import android.content.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE,application=Application::class)
class NightBusServiceStartTest {
    @Test fun actualServiceAcceptsTheNightRequestAndKeepsTheHandoverGuidance() {
        val app=RuntimeEnvironment.getApplication()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        JourneyMonitoringController.publish(JourneyMonitoringState())
        val lifecycle=Robolectric.buildService(JourneyMonitoringService::class.java).create()
        val service=lifecycle.get()
        try {
            service.locationSamples={emptyFlow()}
            service.positionSample={awaitCancellation()}
            service.seoulArrivalQuery={_,_->awaitCancellation()}
            service.walkingSearch={_,_->awaitCancellation()}
            val end=MapPoint(126.9876130976,37.5685792736);val origin=end.copy(y=end.y-0.001)
            val stop=OfficialSeoulBusStop("100000004","01004","종로2가.삼일교",end)
            val route=OfficialSeoulBusRoute(stop.stopId,"100100585","N37","81","복정역환승센터","15")
            val request=JourneyMonitoringRequest(stop.name,end,route.identity,busStop=stop,busRoute=route)
            val clock=service.javaClass.getDeclaredField("displayClock").apply {isAccessible=true}.get(service) as GuidanceDisplayClock
            val now=clock.now()
            val handover=JourneyMonitoringState(true,request,location=GuidanceLocation(origin,5.0,now),
                walkingRoute=GuidanceWalkingRoute(origin,end,route.identity,120,now,120000,150),
                walkingDistanceMeters=150,providerWalkingSeconds=120,walkingPoints=listOf(origin,end),
                arrivals=RealtimeArrivalSnapshot(route.identity,now+600000,fetchedAtEpochMillis=now,receivedAtEpochMillis=now,validForMillis=60000),
                walkingGeometry=WalkingRouteTracker(origin,KakaoWalkingRoute(150,120,listOf(origin,end),origin,end),now))
            val context=object:ContextWrapper(app) {
                override fun startForegroundService(intent:Intent):ComponentName? {
                    service.onStartCommand(intent,0,1)
                    return ComponentName(app,JourneyMonitoringService::class.java)
                }
            }
            assertTrue(JourneyMonitoringController.start(context,request,handover))
            val running=JourneyMonitoringController.state.value
            assertTrue(running.running)
            assertEquals(request,running.request)
            assertEquals(handover.arrivals,running.arrivals)
            assertNotEquals(AutoGuidanceStatus.UNKNOWN,running.assessment?.status)
            assertNull(JourneyMonitoringController.lastStartError)
            assertNotNull(Shadows.shadowOf(service).lastForegroundNotification)
            service.onStartCommand(Intent().setAction(JourneyMonitoringService.ACTION_STOP),0,2)
            assertFalse(JourneyMonitoringController.state.value.running)
        } finally {lifecycle.destroy();JourneyMonitoringController.publish(JourneyMonitoringState())}
    }
}
