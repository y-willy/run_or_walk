package com.example.run_or_walk

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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
class SettingsInlineUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun settingsTabExposesEverySettingWithoutOpeningAnotherSheetOrAdvancedSection()=settings(false)
    @Test @Config(qualifiers="w320dp-h1000dp-mdpi") fun compactSettingsExposeControlsAt130Percent()=settings(true)
    private fun save(activity:ComponentActivity,name:String) {
        System.getProperty("pacenudge.proofDir")?.let {dir ->
            val view=activity.window.decorView
            val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
            File(dir).apply {mkdirs()}.resolve(name).outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
    }
    private fun settings(compact:Boolean) {
        RuntimeEnvironment.setFontScale(if(compact) 1.3f else 1f)
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            val vm=ViewModelProvider(activity.get(),ViewModelProvider.AndroidViewModelFactory(activity.get().application))[JourneySessionViewModel::class.java]
            vm.updateSettings(JourneySettings())
            vm.screens.values.forEach {it.clearRoutesAndBoarding()}
            activity.get().setContent {RUN_or_WALKTheme {PaceNudgeScreen(activity.get(),vm)}}
            compose.onNodeWithContentDescription("설정").performClick()
            compose.onNodeWithText("평소 걸음속도").assertIsDisplayed()
            compose.onNodeWithContentDescription("GPS 걸음속도 자동 보정").performScrollTo().assertIsDisplayed()
            save(activity.get(),if(compact) "settings-compact.png" else "settings-inline.png")
            compose.onNodeWithContentDescription("GPS 걸음속도 자동 보정").performClick()
            Assert.assertFalse(vm.settings.gpsAutoCalibrationEnabled)
            compose.onNodeWithContentDescription("지도 도보시간 우선").performScrollTo().performClick()
            Assert.assertTrue(vm.settings.useProviderWalkingTime)
            compose.onNodeWithText("지도 도보시간 우선").performScrollTo().assertIsDisplayed()
            compose.onNodeWithContentDescription("안전 여유시간 늘리기").performScrollTo().performClick()
            Assert.assertEquals(150,vm.settings.bufferSeconds)
            compose.runOnIdle {Assert.assertEquals(vm.settings,JourneySettingsStore(activity.get().application).load())}
            save(activity.get(),if(compact) "settings-detail-compact.png" else "settings-details.png")
            compose.onNodeWithText("안전 여유·기존 지도 시간 설정").assertDoesNotExist()
        } finally {activity.pause().stop().destroy()}
    }

    @Test fun routeHeaderDoesNotRepeatTheSavedSubwayStationAndDirection() {
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            val vm=ViewModelProvider(activity.get(),ViewModelProvider.AndroidViewModelFactory(activity.get().application))[JourneySessionViewModel::class.java]
            vm.screens.values.forEach {it.clearRoutesAndBoarding()};vm.mode=FeatureMode.ROUTE
            val point=MapPoint(127.0,37.0)
            val station=OfficialSubwayStation("1075075241","1075","망포","수인분당선")
            val id=OfficialBoardingIdentity("seoul",station.stopId,station.routeId,"하행","고색")
            vm.screens.getValue(FeatureMode.ROUTE).apply {
                selectedStation=station;confirmedServiceIdentity=id
                boarding=JourneyPlace("boarding","망포","",point);boardingChosenByUser=true
            }
            JourneyMonitoringController.publish(JourneyMonitoringState(true,JourneyMonitoringRequest("망포",point,id,subwayStation=station)))
            activity.get().setContent {RUN_or_WALKTheme {PaceNudgeScreen(activity.get(),vm)}}
            compose.onNodeWithText("지하철 · 수인분당선").assertDoesNotExist()
            compose.onNodeWithText("망포 · 하행").assertDoesNotExist()
            save(activity.get(),"route-header-clean.png")
        } finally {activity.pause().stop().destroy();JourneyMonitoringController.publish(JourneyMonitoringState())}
    }
}
