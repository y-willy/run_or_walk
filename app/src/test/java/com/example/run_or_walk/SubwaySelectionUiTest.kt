package com.example.run_or_walk
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
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
@Config(sdk=[35],manifest=Config.NONE,application=Application::class,qualifiers="w411dp-h960dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SubwaySelectionUiTest {
 @get:Rule val compose=createEmptyComposeRule()
 @Test fun secondThirdAndNextPageRemainSelectableDuringAndAfterBackgroundRefresh() {
  val now=1800000000000L
  val station=OfficialSubwayStation("1075075241","1075","망포","수인분당선")
  val id=OfficialBoardingIdentity("seoul",station.stopId,station.routeId,"하행","고색행")
  val rows=(1..4).map { n -> OfficialSubwayArrival(id,"하행 · 고색행","열차 $n","613$n",
   RealtimeArrivalSnapshot(id,0,fetchedAtEpochMillis=now,validForMillis=600000,reliable=false,trustState=ArrivalTrustState.UNKNOWN),
   arrivalCode="99",officialSlot=n,officialArrivalMessage="[$n]번째 전역") }
  val state=JourneyScreenState(FeatureMode.STOP)
  state.selectedStation=station;state.arrivalOptions=rows;state.beginLiveRequest()
  val controller=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
  val activity=controller.get()
  activity.setContent { RUN_or_WALKTheme { Surface(Modifier.fillMaxSize()) {
   Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
    Text("망포역 도착정보",style=MaterialTheme.typography.titleLarge)
    SubwayArrivalPicker(state,station,now,state::chooseSubwayArrival)
   }
  } } }
  compose.onNodeWithText("열차 2",substring=true).assertIsEnabled().performClick()
  assertEquals("6132",state.subwayChoice!!.trainId)
  compose.onNodeWithText("✓ 열차 2",substring=true).assertIsDisplayed()
  compose.runOnIdle { state.beginLiveRequest() }
  compose.onNodeWithText("열차 3",substring=true).assertIsEnabled().performClick()
  assertEquals("6133",state.subwayChoice!!.trainId)
  compose.runOnIdle { state.arrivalOptions=listOf(rows[0],rows[1],rows[2].copy(officialSlot=1),rows[3]);state.beginLiveRequest() }
  compose.onNodeWithText("✓ 열차 3",substring=true).assertIsDisplayed()
  compose.onNodeWithText("로딩 중").assertDoesNotExist()
  compose.onNodeWithText("다음 결과").performClick()
  compose.onNodeWithText("열차 4",substring=true).performClick()
  assertEquals("6134",state.subwayChoice!!.trainId)
  compose.runOnIdle { state.arrivalOptions=state.arrivalOptions.map { it.copy(statusMessage="다음 갱신") };state.beginLiveRequest() }
  compose.onNodeWithText("✓ 열차 4",substring=true).assertIsDisplayed()
  compose.onNodeWithText("열차 1",substring=true).assertDoesNotExist()
  System.getProperty("pacenudge.proofDir")?.let { directory ->
   val view=activity.window.decorView
   val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
   File(directory).apply { mkdirs() }.resolve("fourth-selected.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
  }
  controller.pause().stop().destroy()
 }
}
