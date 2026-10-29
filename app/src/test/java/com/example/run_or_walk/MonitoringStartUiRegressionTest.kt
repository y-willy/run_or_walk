package com.example.run_or_walk

import android.Manifest
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.*
import com.example.run_or_walk.ui.theme.RUN_or_WALKTheme
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=Application::class,qualifiers="w360dp-h1200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MonitoringStartUiRegressionTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun nightBusStartButtonIsEnabledAndItsTapEntersTheStartHandler()=exercise("15",false)
    @Test fun unrelatedWalkingRefreshDoesNotDisableAnAlreadyConfirmedJourney()=exercise("3",true)
    private fun exercise(type:String,loading:Boolean) {
        val app=RuntimeEnvironment.getApplication()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.POST_NOTIFICATIONS)
        JourneyMonitoringController.publish(JourneyMonitoringState())
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        // Stop at the OS lifecycle boundary: no repository/network polling in this UI test.
        val owner=object:LifecycleOwner {
            override val lifecycle=LifecycleRegistry(this).apply {currentState=Lifecycle.State.CREATED}
        }
        try {
            val vm=ViewModelProvider(activity.get())[JourneySessionViewModel::class.java]
            vm.screens.values.forEach {it.clearRoutesAndBoarding()};vm.mode=FeatureMode.ROUTE
            val point=MapPoint(126.9876130976,37.5685792736)
            val stop=OfficialSeoulBusStop("100000004","01004","종로2가.삼일교",point)
            val route=OfficialSeoulBusRoute(stop.stopId,"100100585",if(type=="15") "N37" else "470","81","복정역환승센터",type)
            val screen=vm.screens.getValue(FeatureMode.ROUTE)
            screen.boarding=stop.toJourneyPlace();screen.boardingMatchedAutomatically=true
            screen.busRoutePathVerified=true;screen.confirmedServiceIdentity=route.identity
            screen.selectedBusStop=stop;screen.selectedBusRoute=route
            screen.selectedRoute=JourneyTransitRoute("selected",KakaoTransitChoice(1800,0,route.name,stop.name,point,firstTransitType="BUS"))
            screen.loading=loading
            activity.get().setContent {RUN_or_WALKTheme {PaceNudgeScreen(owner,vm)}}
            compose.onNodeWithText("이동 안내 시작").performScrollTo().assertIsEnabled().performClick()
            compose.onNodeWithText("이동 안내 시작 중").assertIsDisplayed().assertIsNotEnabled()
        } finally {activity.pause().stop().destroy();JourneyMonitoringController.publish(JourneyMonitoringState())}
    }
}
