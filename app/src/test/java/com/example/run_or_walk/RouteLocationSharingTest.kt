package com.example.run_or_walk

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=Application::class)
class RouteLocationSharingTest {
    private val now=1_800_000_000_000L
    private val origin=MapPoint(127.0,37.0)
    private val destination=MapPoint(127.0,37.005)
    private fun screen()=JourneyScreenState(FeatureMode.ROUTE).apply {
        boarding=JourneyPlace("boarding","승차점","",this@RouteLocationSharingTest.destination)
        location=GuidanceLocation(origin,5.0,now,1.3,0.2)
        storeWalkingResult(location!!,boarding!!,null,KakaoWalkingRoute(650,500,listOf(origin,this@RouteLocationSharingTest.destination)),JourneySettings(),null,now,now)
    }
    @Test fun freshGpsIsSharedAfterRouteChangeWithoutSharingAnOldBoardingOrEta() {
        val vm=JourneySessionViewModel(RuntimeEnvironment.getApplication())
        val source=vm.screens.getValue(FeatureMode.STOP)
        source.location=GuidanceLocation(origin,5.0,now)
        assertTrue(vm.observeLocation(source.location!!,now))
        val route=vm.screens.getValue(FeatureMode.ROUTE)
        route.clearWalking()
        assertEquals(source.location,vm.locationAt(now,route.location))
        assertNull(route.guidanceWalkingRoute);assertNull(route.walkRoute);assertNull(route.confirmedServiceIdentity)
        assertNull(vm.locationAt(now+30001))
    }
    @Test fun oldInaccurateAndFutureSamplesCannotReplaceTheLatestSharedLocation() {
        val vm=JourneySessionViewModel(RuntimeEnvironment.getApplication())
        val fix=GuidanceLocation(origin,5.0,now)
        assertTrue(vm.observeLocation(fix,now))
        assertFalse(vm.observeLocation(fix.copy(observedAtEpochMillis=now-1000),now))
        assertFalse(vm.observeLocation(fix.copy(accuracyMeters=100.0),now))
        assertFalse(vm.observeLocation(fix.copy(observedAtEpochMillis=now+1),now))
        assertEquals(fix,vm.locationAt(now))
        val next=fix.copy(point=origin.copy(y=37.0001),observedAtEpochMillis=now+1000)
        assertEquals(next,vm.locationAt(now+1000,fix,next))
    }
    @Test fun realWalkingMetricsAreVisibleBeforeOfficialBindingButCannotEnableMovementAdvice() {
        val screen=screen();val display=screen.walkingDisplay(screen.location,JourneySettings(useProviderWalkingTime=true),null,now)!!
        assertEquals(650,display.distanceMeters);assertEquals(500,display.durationSeconds)
        assertNull(screen.guidanceWalkingRoute);assertNull(screen.confirmedServiceIdentity)
    }
    @Test fun expiredWrongBoardingOrDistantGpsCannotReuseDisplayMetrics() {
        val screen=screen()
        assertNull(screen.walkingDisplay(screen.location,JourneySettings(),null,now+120001))
        assertNull(screen.walkingDisplay(screen.location!!.copy(point=origin.copy(x=127.01)),JourneySettings(),null,now))
        screen.boarding=screen.boarding!!.copy(point=destination.copy(x=127.01))
        assertNull(screen.walkingDisplay(screen.location,JourneySettings(),null,now))
    }
    @Test fun walkingProgressBeforeBindingKeepsOriginalGeometryAndReceiptWithoutResettingToConfirmation() {
        val screen=screen()
        screen.updateUnboundWalkingDisplay(screen.location!!,now)
        val moved=screen.location!!.copy(point=origin.copy(y=37.00054),observedAtEpochMillis=now+30000)
        screen.updateUnboundWalkingDisplay(moved,now+30000)
        val display=screen.walkingDisplay(moved,JourneySettings(),null,now+30000)!!
        assertTrue(display.distanceMeters<650)
        assertEquals(now,screen.walkFetchedAtEpochMillis)
        assertNull(screen.guidanceWalkingRoute)
    }
}
