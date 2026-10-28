package com.example.run_or_walk

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelProvider
import com.example.run_or_walk.ui.theme.RUN_or_WALKTheme
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=Application::class,qualifiers="w360dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RouteWalkingDisplayUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun validGpsAndWalkingRouteDisplayDistanceBeforeOfficialBusBindingFinishes() {
        val app=RuntimeEnvironment.getApplication()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val owner=object:LifecycleOwner {
            val registry=LifecycleRegistry(this).apply {currentState=Lifecycle.State.CREATED}
            override val lifecycle:Lifecycle get()=registry
        }
        try {
            val vm=ViewModelProvider(activity.get(),ViewModelProvider.AndroidViewModelFactory(activity.get().application))[JourneySessionViewModel::class.java]
            vm.screens.values.forEach {it.clearRoutesAndBoarding()};vm.mode=FeatureMode.ROUTE
            val start=MapPoint(127.0,37.0);val end=MapPoint(127.0,37.005)
            val now=System.currentTimeMillis()
            val screen=vm.screens.getValue(FeatureMode.ROUTE)
            screen.boarding=JourneyPlace("boarding","승차 정류소","",end)
            screen.departure.selected=screen.boarding
            screen.destination.selected=JourneyPlace("destination","목적지","",end.copy(y=37.02))
            screen.selectedRoute=JourneyTransitRoute("route",KakaoTransitChoice(1800,0,"273","승차 정류소",end,firstTransitType="BUS"))
            screen.location=GuidanceLocation(start,5.0,now)
            screen.walkRoute=WalkRoute(650,500)
            screen.walkOrigin=start;screen.walkFetchedAtEpochMillis=now
            screen.walkingPoints=listOf(start,end)
            Assert.assertNull(screen.confirmedServiceIdentity)
            Assert.assertNull(screen.guidanceWalkingRoute)
            activity.get().setContent {RUN_or_WALKTheme {PaceNudgeScreen(owner,vm)}}
            compose.onNode(hasText("650m") and hasAnyAncestor(hasTestTag("journey-metrics"))).assertIsDisplayed()
            compose.onNodeWithText("도보 약 8분").assertIsDisplayed()
            compose.onNode(hasText("확인 중") and hasAnyAncestor(hasTestTag("journey-metrics"))).assertDoesNotExist()
            compose.onNodeWithContentDescription("설정").performClick()
            compose.onNodeWithContentDescription("경로 찾기").performClick()
            compose.onNode(hasText("650m") and hasAnyAncestor(hasTestTag("journey-metrics"))).assertIsDisplayed()
            compose.onNodeWithText("도보 약 8분").assertIsDisplayed()
            System.getProperty("pacenudge.proofDir")?.let {dir ->
                val view=activity.get().window.decorView
                val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
                File(dir).apply {mkdirs()}.resolve("route-walking-pending-binding.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            }
        } finally {activity.pause().stop().destroy()}
    }
}
