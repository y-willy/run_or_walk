package com.example.run_or_walk

import android.Manifest
import android.app.Application
import android.content.*
import android.os.Looper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE,application=Application::class)
class JourneyHandoverFollowupTest {
    private val point=MapPoint(127.0,37.0)
    private val stop=OfficialSeoulBusStop("105900050","06510","정류소",point)
    private val route=OfficialSeoulBusRoute(stop.stopId,"105900003","동대문01","4","회기역","2")
    private val old get()=JourneyMonitoringRequest(stop.name,point,route.identity,busStop=stop,busRoute=route)
    private val next get()=old.copy(busRoute=route.copy(stopOrder="5"),identity=route.copy(stopOrder="5").identity)
    private var evidenceNow:()->Long=System::currentTimeMillis
    private fun state(request:JourneyMonitoringRequest):JourneyMonitoringState {
        val now=evidenceNow()
        return JourneyMonitoringState(true,request,location=GuidanceLocation(point,5.0,now),
            walkingRoute=GuidanceWalkingRoute(point,point,request.identity,180,now,120000,234),walkingDistanceMeters=234,
            providerWalkingSeconds=180,arrivals=RealtimeArrivalSnapshot(request.identity,now+600000,
                fetchedAtEpochMillis=now,receivedAtEpochMillis=now,validForMillis=600000))
    }
    private fun exercise(block:(JourneyMonitoringService,Job,(JourneyMonitoringRequest)->Unit)->Unit) {
        val app=RuntimeEnvironment.getApplication()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val lifecycle=Robolectric.buildService(JourneyMonitoringService::class.java).create()
        val service=lifecycle.get();val job=Job()
        val clock=service.javaClass.getDeclaredField("displayClock").apply {isAccessible=true}.get(service) as GuidanceDisplayClock
        evidenceNow=clock::now
        service.positionSample={GuidanceLocation(point,5.0,clock.now())}
        service.locationSamples={emptyFlow()}
        service.seoulArrivalQuery={_,_->awaitCancellation()}
        service.javaClass.getDeclaredField("currentRequest").apply {isAccessible=true}.set(service,old)
        service.javaClass.getDeclaredField("monitoringJob").apply {isAccessible=true}.set(service,job)
        JourneyMonitoringController.publish(state(old))
        val context=object:ContextWrapper(app) {
            override fun startForegroundService(intent:Intent):ComponentName? {
                service.onStartCommand(intent,0,2);return ComponentName(app,JourneyMonitoringService::class.java)
            }
        }
        try {block(service,job) {request ->assertTrue(JourneyMonitoringController.start(context,request))}}
        finally {lifecycle.destroy();JourneyMonitoringController.publish(JourneyMonitoringState())}
    }
    private fun drain()=Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
    @Test fun apiOfflineAndTimeoutAndMismatchedDirectionNeverCancelTheOldSession() {
        for(error in listOf(IOException("offline"),java.net.SocketTimeoutException("timeout"),IllegalArgumentException("direction"))) exercise {service,job,start ->
            service.replacementPreparation={_,_->throw error}
            start(next);drain()
            assertFalse(job.isCancelled);assertEquals(old,JourneyMonitoringController.state.value.request)
            assertNotNull(JourneyMonitoringController.lastStartError)
        }
    }
    @Test fun actualPreparationRejectsFirstEtaFailureWithoutStoppingTheOldTracking()=exercise {service,job,start ->
        service.seoulArrivalQuery={_,_->throw IOException("first ETA failed")}
        start(next);drain()
        assertFalse(job.isCancelled);assertEquals(old,JourneyMonitoringController.state.value.request)
        assertNotNull(JourneyMonitoringController.lastStartError)
    }
    @Test fun gpsInitialFailureRejectsOnlyTheReplacement()=exercise {service,job,start ->
        JourneyMonitoringController.publish(state(old).copy(location=null))
        service.positionSample={throw DeviceLocationException(LocationFailure.TIMEOUT)}
        start(next);drain()
        assertFalse(job.isCancelled);assertEquals(old,JourneyMonitoringController.state.value.request)
        assertTrue(JourneyMonitoringController.lastStartError!!.contains("위치"))
    }
    @Test fun mismatchedPreparedIdentityCannotCommit()=exercise {service,job,start ->
        service.replacementPreparation={_,_->state(old)}
        start(next);drain()
        assertFalse(job.isCancelled);assertEquals(old,JourneyMonitoringController.state.value.request)
    }
    @Test fun delayedSuccessCommitsOnlyAfterEvidenceAndCancelsOldJobsAtThatPoint()=exercise {service,job,start ->
        val ready=CompletableDeferred<JourneyMonitoringState>()
        service.replacementPreparation={_,_->ready.await()}
        start(next);drain();assertFalse(job.isCancelled);assertEquals(old,JourneyMonitoringController.state.value.request)
        ready.complete(state(next));drain()
        assertTrue("Expected commit; error=${JourneyMonitoringController.lastStartError}, state=${JourneyMonitoringController.state.value}",job.isCancelled);assertEquals(next,JourneyMonitoringController.state.value.request)
        assertNotEquals(AutoGuidanceStatus.UNKNOWN,JourneyMonitoringController.state.value.assessment?.status)
    }
    @Test fun explicitStopDuringPendingReplacementCannotBeResurrectedByALateResponse()=exercise {service,_,start ->
        val ready=CompletableDeferred<JourneyMonitoringState>()
        service.replacementPreparation={_,_->withContext(NonCancellable) {ready.await()}}
        start(next);drain()
        service.onStartCommand(Intent().setAction(JourneyMonitoringService.ACTION_STOP),0,3)
        ready.complete(state(next));drain()
        assertFalse(JourneyMonitoringController.state.value.running)
    }
    @Test fun newerStartWinsEvenIfCancelledOldQueryReturnsLast()=exercise {service,job,start ->
        val first=CompletableDeferred<JourneyMonitoringState>();val second=CompletableDeferred<JourneyMonitoringState>()
        val newer=next.copy(busRoute=route.copy(stopOrder="6"),identity=route.copy(stopOrder="6").identity)
        service.replacementPreparation={r,_->withContext(NonCancellable) {if(r.isSameJourney(next)) first.await() else second.await()}}
        start(next);drain();start(newer);drain()
        second.complete(state(newer));drain();assertTrue(job.isCancelled)
        first.complete(state(next));drain();assertEquals(newer,JourneyMonitoringController.state.value.request)
    }
    @Test fun fortySecondPreparationDeadlineRetainsTheExistingTrackingJob()=exercise {service,job,start ->
        service.replacementPreparation={_,_->awaitCancellation()}
        start(next);drain()
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(41))
        assertFalse(job.isCancelled);assertEquals(old,JourneyMonitoringController.state.value.request)
        assertTrue(JourneyMonitoringController.lastStartError!!.contains("초과"))
    }
    @Test fun leavingTheSelectionDuringPreparationCannotCommitTheResult()=exercise {service,job,_ ->
        val pending=CompletableDeferred<JourneyMonitoringState>();var selected=true
        service.replacementPreparation={_,_->pending.await()}
        val app=RuntimeEnvironment.getApplication()
        val context=object:ContextWrapper(app) {
            override fun startForegroundService(intent:Intent):ComponentName? {
                service.onStartCommand(intent,0,2);return ComponentName(app,JourneyMonitoringService::class.java)
            }
        }
        assertTrue(JourneyMonitoringController.start(context,next,canActivate={selected}));drain()
        selected=false;pending.complete(state(next));drain()
        assertFalse(job.isCancelled);assertEquals(old,JourneyMonitoringController.state.value.request)
        assertTrue(JourneyMonitoringController.lastStartError!!.contains("취소"))
    }
    @Test fun missingPermissionDoesNotReplaceOldDataAndRejectsNewIntent()=exercise {_,job,start ->
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)
        assertFalse(JourneyMonitoringController.start(RuntimeEnvironment.getApplication(),next))
        assertFalse(job.isCancelled);assertEquals(old,JourneyMonitoringController.state.value.request)
    }
}
