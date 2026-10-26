package com.example.run_or_walk

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.example.run_or_walk.ui.theme.RUN_or_WALKTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=Application::class,qualifiers="w360dp-h960dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WalkingAnalysisUiTest {
 @get:Rule val compose=createEmptyComposeRule()
 @Test fun measurementSummaryShowsObservedStrideAndSettingsOpenTheAnalysisEntry() {
    var opened=false
    val controller=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
    val activity=controller.get()
    activity.setContent { RUN_or_WALKTheme { Surface(Modifier.fillMaxSize()) {
        Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("내 걷기 분석",style=MaterialTheme.typography.headlineMedium)
            WalkingAnalysisSummary(WalkingMeasurementResult(120.0,150,120.0,150,120.0,140.0,StepSensorMode.COUNTER))
            JourneySettingsPanel(JourneySettings(),onWalkingAnalysis={ opened=true }) {}
        }
    } } }
    compose.onNodeWithText("평균 한 걸음  80.0 cm").assertIsDisplayed()
    compose.onNodeWithText("평균 걷기 속도  3.6 km/h").assertIsDisplayed()
    compose.onNodeWithText("1 km 예상시간  16분 40초").assertIsDisplayed()
    System.getProperty("pacenudge.proofDir")?.let { directory ->
        val view=activity.window.decorView
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
        File(directory).apply { mkdirs() }.resolve("walking-summary.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    compose.onNodeWithText("설정").performClick()
    compose.onAllNodesWithText("내 걷기 분석")[1].performClick()
    assertTrue(opened)
    controller.pause().stop().destroy()
 }
 @Test fun unsupportedSensorSummaryDoesNotClaimMeasuredStride() {
    val controller=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
    controller.get().setContent { RUN_or_WALKTheme { WalkingAnalysisSummary(WalkingMeasurementResult()) } }
    compose.onNodeWithText("걸음 수  센서 사용 불가").assertIsDisplayed()
    compose.onNodeWithText("평균 한 걸음  측정 부족 / 확인 불가").assertIsDisplayed()
    compose.onNodeWithText("측정 신뢰도 부족",substring=true).assertIsDisplayed()
    controller.pause().stop().destroy()
 }
 @Test fun runningSpeedCanBeEnteredSavedReloadedAndClearedThroughSettings() {
    var settings by mutableStateOf(JourneySettings())
    var changes=0
    val controller=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
    val activity=controller.get()
    val store=JourneySettingsStore(activity)
    activity.setContent { RUN_or_WALKTheme { Surface(Modifier.fillMaxSize()) {
        Column(Modifier.padding(20.dp)) {
            JourneySettingsPanel(settings) { value ->
                settings=value
                store.save(value)
                changes++
            }
        }
    } } }
    compose.onNodeWithText("설정").performClick()
    compose.onNodeWithText("안전 여유·기존 지도 시간 설정").performScrollTo().performClick()
    compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("9.0")
    compose.onNodeWithText("달리기 속도 저장").performScrollTo().assertIsEnabled().performClick()
    compose.runOnIdle {
        assertEquals(1,changes)
        assertEquals(9.0,settings.measuredRunningSpeedKmh!!,0.0)
        assertEquals(9.0,JourneySettingsStore(activity).load().measuredRunningSpeedKmh!!,0.0)
    }
    compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("13.0")
    compose.onNodeWithText("달리기 속도 저장").performScrollTo().assertIsNotEnabled()
    compose.runOnIdle {
        assertEquals(1,changes)
        assertEquals(9.0,JourneySettingsStore(activity).load().measuredRunningSpeedKmh!!,0.0)
    }
    compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("")
    compose.onNodeWithText("달리기 속도 저장").performScrollTo().assertIsEnabled().performClick()
    compose.runOnIdle {
        assertEquals(2,changes)
        assertNull(settings.measuredRunningSpeedKmh)
        assertNull(JourneySettingsStore(activity).load().measuredRunningSpeedKmh)
    }
    controller.pause().stop().destroy()
 }

}
