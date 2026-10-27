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
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import com.example.run_or_walk.ui.theme.RUN_or_WALKTheme
import java.io.File
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=Application::class,qualifiers="w360dp-h1100dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GuidanceRefreshStabilityUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    private lateinit var previousTimeZone: java.util.TimeZone
    @Before fun setDeviceTimeZone() { previousTimeZone=java.util.TimeZone.getDefault();java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Seoul")) }
    @After fun restoreTimeZone() { java.util.TimeZone.setDefault(previousTimeZone) }
    private val time=1_800_000_000_000L
    private val origin=MapPoint(127.0,37.0)
    private val boarding=MapPoint(127.0,37.0021)
    private val route=OfficialSeoulBusRoute("105900050","105900003","동대문01","4","회기역","2")
    private fun row(vehicle:String,slot:Int,seconds:Long,source:Long=time)=OfficialSeoulBusArrival(route.identity,vehicle,route.name,route.direction,
        RealtimeArrivalSnapshot(route.identity,source+seconds*1000,fetchedAtEpochMillis=source,validForMillis=60000,receivedAtEpochMillis=source),
        gatewayFetchedAtEpochMillis=source,officialSlot=slot,sourceEtaSeconds=seconds)
    private fun activity(content:@Composable ()->Unit,checks:(ComponentActivity)->Unit) {
        val lifecycle=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try { lifecycle.get().setContent { RUN_or_WALKTheme { content() } };checks(lifecycle.get()) }
        finally { lifecycle.pause().stop().destroy() }
    }
    private fun save(activity:ComponentActivity,name:String) {
        System.getProperty("pacenudge.proofDir")?.let { directory ->
            val view=activity.window.decorView
            val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
            File(directory).apply { mkdirs() }.resolve(name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        }
    }
    @Test fun missingSpeedDisplaysZeroAcrossRecompositionAndNeverChangesConfiguredWalkingSpeed() {
        var speed by mutableStateOf<Double?>(null)
        var tick by mutableIntStateOf(0)
        activity({Column { JourneyMetrics(speed,250);Text("시각 $tick") }}) {
            compose.onNodeWithText("0.0 km/h").assertIsDisplayed()
            compose.onNodeWithText("측정 중").assertDoesNotExist()
            compose.runOnIdle { tick++ }
            compose.onNodeWithText("0.0 km/h").assertIsDisplayed()
            compose.runOnIdle { speed=1.4 }
            compose.onNodeWithText("5.0 km/h").assertIsDisplayed()
            compose.runOnIdle { speed=null }
            compose.onNodeWithText("0.0 km/h").assertIsDisplayed()
            Assert.assertEquals(200,JourneySettings(speedKmh=3.24).walkingSeconds(180,180,null))
        }
    }
    @Test fun refreshKeepsBothBusesAndWalkThenUsesNewOfficialEtaWithoutAnUnknownInterlude() {
        val screen=JourneyScreenState(FeatureMode.STOP)
        screen.selectedBusStop=OfficialSeoulBusStop(route.stopId,"06510","경희대의료원",boarding)
        screen.selectedBusRoute=route
        screen.applyBusArrivals(listOf(row("105000001",1,300),row("105000002",2,780)),time)
        var clock by mutableLongStateOf(time)
        val settings=JourneySettings(bufferSeconds=60,measuredRunningSpeedKmh=9.0)
        activity({
            val first=screen.currentBusArrival(clock)?.snapshot
            val input=AutoGuidanceInput(GuidanceLocation(origin,3.0,time),GuidanceBoarding(route.identity,boarding,true),
                GuidanceWalkingRoute(origin,boarding,route.identity,180,time,120000,234),first,true,arrivalRequestFailed=screen.arrivalRequestFailed,bufferSeconds=60)
            val assessment=calculateMovementGuidance(input,clock,settings)
            Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    PaceNudgeHeader();JourneyMetrics(null,234)
                    AutomaticGuidanceCard(assessment,route.name,"경희대의료원","",clock,first?.firstArrivalAtEpochMillis,
                        first?.usableNextArrivalAt(clock),first?.fetchedAtEpochMillis,180,vehicleLabel="버스")
                }
            }
        }) { activity ->
            compose.onNodeWithText("걸으세요").assertIsDisplayed()
            compose.onNodeWithText("버스 5분 0초 후").assertIsDisplayed()
            compose.onNodeWithText("이동 여유 약 1분").performClick()
            compose.onNodeWithText("다음 버스 · 13분 0초 후").assertIsDisplayed()
            save(activity,"refresh-before.png")
            compose.runOnIdle { screen.beginLiveRequest();clock=time+15000 }
            compose.onNodeWithText("걸으세요").assertIsDisplayed()
            compose.onNodeWithText("버스 4분 45초 후").assertIsDisplayed()
            compose.onNodeWithText("다음 버스 · 12분 45초 후").assertIsDisplayed()
            compose.onNodeWithText("도보 약 3분").assertIsDisplayed()
            compose.onNodeWithText("도착정보 확인 중").assertDoesNotExist()
            save(activity,"refresh-during.png")
            compose.runOnIdle { screen.applyBusArrivals(listOf(row("105000001",1,185,time+15000)),clock);screen.liveLoading=false }
            compose.onNodeWithText("빠르게 이동하세요").assertIsDisplayed()
            compose.onNodeWithText("버스 3분 5초 후").assertIsDisplayed()
            compose.onNodeWithText("다음 버스 · 12분 45초 후").assertIsDisplayed()
            compose.onNodeWithText("도보 약 3분").assertIsDisplayed()
            Assert.assertEquals(time,screen.busArrivalOptions.single { it.officialSlot==2 }.snapshot.receivedAtEpochMillis)
            save(activity,"refresh-after.png")
        }
    }
    @Test fun transientFailureKeepsValidEtaButExpiryAndConfirmedNoServiceAreVisible() {
        var clock by mutableLongStateOf(time)
        var failed by mutableStateOf(false)
        var rows by mutableStateOf(listOf(row("105000001",1,300)))
        var responseAt by mutableLongStateOf(time)
        activity({
            val arrival=chooseMatchingBusArrival(route.identity,rows,clock)?.snapshot
            val assessment=AutoGuidanceAssessment(if(arrival!=null) AutoGuidanceStatus.WALK else AutoGuidanceStatus.UNKNOWN,bufferSeconds=60)
            AutomaticGuidanceCard(assessment,route.name,"경희대의료원","",clock,arrival?.firstArrivalAtEpochMillis,null,arrival?.fetchedAtEpochMillis,180,
                unknownTitle=missingArrivalText(responseAt,rows.isNotEmpty(),failed,rows.isEmpty(),clock),vehicleLabel="버스",
                refreshError="연결 일시 오류".takeIf { failed })
        }) {
            compose.runOnIdle { failed=true;clock=time+15000 }
            compose.onNodeWithText("이동 여유 45초").performClick()
            compose.onNodeWithText("최근 갱신 오류 · 연결 일시 오류").assertIsDisplayed()
            compose.onNodeWithText("걸으세요").assertIsDisplayed()
            compose.onNodeWithText("버스 4분 45초 후").assertIsDisplayed()
            compose.runOnIdle { clock=time+61000 }
            compose.onNodeWithText("도착정보를 불러오지 못했어요").assertIsDisplayed()
            compose.onNodeWithText("버스 3분 59초 후").assertDoesNotExist()
            compose.runOnIdle { failed=false }
            compose.onNodeWithText("최신 도착정보 확인 필요").assertIsDisplayed()
            compose.runOnIdle { rows=emptyList();responseAt=clock }
            compose.onNodeWithText("운행정보 없음").assertIsDisplayed()
        }
    }
}
