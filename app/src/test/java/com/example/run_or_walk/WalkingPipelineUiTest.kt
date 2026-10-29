package com.example.run_or_walk

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import com.example.run_or_walk.ui.theme.RUN_or_WALKTheme
import java.io.File
import java.time.Duration
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=Application::class,qualifiers="w360dp-h1100dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WalkingPipelineUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    private fun save(activity:ComponentActivity,name:String) {
        System.getProperty("pacenudge.proofDir")?.let {dir ->
            val view=activity.window.decorView;val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap));File(dir).apply {mkdirs()}.resolve(name).outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
    }
    @Test fun firstGpsToHttpResponseThenThirtySecondRefreshFailureAndGpsRecoveryRenderRealState() {
        val app=RuntimeEnvironment.getApplication()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val gps=MutableSharedFlow<GuidanceLocation>(replay=1)
        val answers=Channel<Result<KakaoWalkingRoute>>(Channel.UNLIMITED)
        val origin=MapPoint(127.0,37.0);val end=MapPoint(127.0,37.003)
        var calls=0
        try {
            val vm=ViewModelProvider(activity.get(),ViewModelProvider.AndroidViewModelFactory(activity.get().application))[JourneySessionViewModel::class.java]
            vm.screens.values.forEach {it.clearRoutesAndBoarding()};vm.mode=FeatureMode.ROUTE
            vm.updateSettings(JourneySettings(useProviderWalkingTime=true,gpsAutoCalibrationEnabled=false))
            vm.walkingLocations={gps}
            vm.walkingLocation={gps.first {sample ->
                locationSampleFailure(vm.displayClock.alignLocation(sample),vm.displayClock.now())==null
            }}
            vm.walkingSearch={_,_->calls++;answers.receive().getOrThrow()}
            val screen=vm.screens.getValue(FeatureMode.ROUTE)
            screen.boarding=JourneyPlace("boarding","승차점","",end)
            screen.departure.selected=screen.boarding;screen.destination.selected=JourneyPlace("destination","목적지","",end.copy(y=37.02))
            screen.selectedRoute=JourneyTransitRoute("route",KakaoTransitChoice(1800,0,"273","승차점",end,firstTransitType="BUS"))
            activity.get().setContent {RUN_or_WALKTheme {PaceNudgeScreen(activity.get(),vm)}}
            compose.waitForIdle();Assert.assertEquals(0,calls);save(activity.get(),"walking-01-before-gps.png")
            fun fix()=GuidanceLocation(origin,5.0,System.currentTimeMillis(),1.3,0.2,android.os.SystemClock.elapsedRealtime())
            compose.runOnIdle {gps.tryEmit(fix())};compose.waitForIdle()
            Assert.assertEquals("error=${screen.error}, loc=${screen.location}, last=${screen.walkingRefreshCadence.lastRequestedElapsed}, subs=${gps.subscriptionCount.value}",1,calls);Assert.assertTrue(screen.walkingRefreshCadence.requesting)
            save(activity.get(),"walking-02-first-request.png")
            // Official identity can finish while the physical request remains in flight.
            compose.runOnIdle {screen.confirmedServiceIdentity=OfficialBoardingIdentity("seoul-bus","105900050","105900003","4","105900003")}
            compose.waitForIdle();Assert.assertTrue(screen.walkingRefreshCadence.requesting);Assert.assertEquals(1,calls)
            compose.runOnIdle {answers.trySend(Result.success(KakaoWalkingRoute(420,360,listOf(origin,end),origin,end)))}
            compose.onNodeWithText("420m").assertIsDisplayed();compose.onNodeWithText("도보 약 6분").assertIsDisplayed()
            save(activity.get(),"walking-03-first-success.png")
            fun advance(ms:Long) {
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));compose.mainClock.advanceTimeBy(ms);compose.waitForIdle()
            }
            compose.runOnIdle {gps.tryEmit(fix())}
            advance(31000)
            compose.runOnIdle {gps.tryEmit(fix())};compose.waitForIdle()
            Assert.assertEquals(2,calls)
            compose.onNodeWithText("420m").assertIsDisplayed();compose.onNodeWithText("도보 약 6분").assertIsDisplayed()
            save(activity.get(),"walking-04-refresh-pending.png")
            compose.runOnIdle {answers.trySend(Result.success(KakaoWalkingRoute(350,300,listOf(origin,end),origin,end)))}
            compose.onNodeWithText("350m").assertIsDisplayed();compose.onNodeWithText("도보 약 5분").assertIsDisplayed()
            save(activity.get(),"walking-05-new-result.png")
            advance(31000);compose.runOnIdle {gps.tryEmit(fix())};compose.waitForIdle()
            compose.runOnIdle {answers.trySend(Result.failure(KakaoApiException(KakaoFailure.SERVICE,503)))}
            compose.waitForIdle();Assert.assertFalse(screen.walkingRefreshCadence.requesting)
            compose.onNodeWithText("350m").assertIsDisplayed();save(activity.get(),"walking-06-api-failure.png")
            compose.runOnIdle {gps.tryEmit(fix().copy(accuracyMeters=100.0))};compose.waitForIdle()
            compose.runOnIdle {gps.tryEmit(fix())};compose.waitForIdle()
            advance(31000);compose.runOnIdle {gps.tryEmit(fix())};compose.waitForIdle()
            Assert.assertEquals(4,calls)
            compose.runOnIdle {answers.trySend(Result.success(KakaoWalkingRoute(350,300,listOf(origin,end),origin,end)))}
            compose.onNodeWithText("350m").assertIsDisplayed();Assert.assertNull(screen.walkingRefreshCadence.error)
            save(activity.get(),"walking-07-gps-recovered.png")
        } finally {activity.pause().stop().destroy();answers.close()}
    }
}
