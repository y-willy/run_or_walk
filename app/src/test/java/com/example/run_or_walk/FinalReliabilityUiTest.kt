package com.example.run_or_walk

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.example.run_or_walk.ui.theme.RUN_or_WALKTheme
import java.io.File
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=Application::class,qualifiers="w360dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FinalReliabilityUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val point=MapPoint(127.0,37.0)
    private val stop=OfficialSeoulBusStop("105900050","06510","경희대의료원",point)
    private val route=OfficialSeoulBusRoute(stop.stopId,"105900003","동대문01","4","회기역 방면","2")
    private val request get()=JourneyMonitoringRequest(stop.name,point,route.identity,busStop=stop,busRoute=route)
    private fun save(activity:ComponentActivity,name:String) {
        System.getProperty("pacenudge.proofDir")?.let { dir ->
            val view=activity.window.decorView;val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap));File(dir).apply {mkdirs()}.resolve(name).outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
    }
    private fun row(now:Long,seconds:Int)=OfficialSeoulBusArrival(route.identity,"vehicle",route.name,route.direction,
        RealtimeArrivalSnapshot(route.identity,now+seconds*1000,fetchedAtEpochMillis=now,validForMillis=600000,receivedAtEpochMillis=now))
    @Test fun favoritePollingKeepsEtaDuringRefreshAndStopsWithTheInactiveScreen() {
        val app=RuntimeEnvironment.getApplication();app.getSharedPreferences("favorite_buses",0).edit().clear().commit()
        val store=FavoriteBusStore(app);store.toggle(FavoriteBus(request))
        val clock=GuidanceDisplayClock(System.currentTimeMillis(),android.os.SystemClock.elapsedRealtime(),android.os.SystemClock::elapsedRealtime)
        val pending=CompletableDeferred<List<OfficialSeoulBusArrival>>()
        var calls=0
        val loader=FavoriteBusArrivalLoader(seoulRoutes={listOf(route)},seoulArrivals={_,_->
            calls++;if(calls==2) pending.await() else listOf(row(clock.now(),240))},clock=clock::now)
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            activity.get().setContent {RUN_or_WALKTheme {FavoriteBusesScreen(store,activity.get(),loader,arrivalClock=clock) {}}}
            compose.waitForIdle();Assert.assertEquals(1,calls)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31))
            compose.mainClock.advanceTimeBy(31000)
            compose.waitForIdle();Assert.assertEquals(2,calls)
            compose.onNodeWithText("후 도착",substring=true).assertIsDisplayed()
            compose.onNodeWithText("첫 도착정보를 연결하고 있어요").assertDoesNotExist()
            save(activity.get(),"final-favorites-refreshing.png")
            val before=compose.onNodeWithText("후 도착",substring=true).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString()
            org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMillis(400))
            compose.runOnIdle {pending.complete(listOf(row(clock.now(),901)))}
            compose.mainClock.advanceTimeByFrame();compose.waitForIdle()
            save(activity.get(),"favorite-response-proof.png")
            compose.onNodeWithText("후 도착",substring=true).assertIsDisplayed()
            val after=compose.onNodeWithText("후 도착",substring=true).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString()
            Assert.assertNotEquals(before,after)
            Assert.assertTrue(after.contains("14분") || after.contains("15분"))
            save(activity.get(),"final-favorites.png")
            activity.pause().stop();compose.waitForIdle()
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31))
            compose.mainClock.advanceTimeBy(31000)
            Assert.assertEquals(2,calls)
            activity.start().resume().visible();compose.mainClock.advanceTimeBy(1000);compose.waitForIdle()
            Assert.assertEquals(3,calls)
        } finally {activity.pause().stop().destroy()}
    }
    @Test fun favoriteReplacementCanBeCancelledAndSearchEditingKeepsBackgroundGuidance() {
        val app=RuntimeEnvironment.getApplication();app.getSharedPreferences("favorite_buses",0).edit().clear().commit()
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            val vm=ViewModelProvider(activity.get())[JourneySessionViewModel::class.java]
            vm.screens.values.forEach {it.clearRoutesAndBoarding()};vm.mode=FeatureMode.ROUTE
            vm.favorites.toggle(FavoriteBus(request))
            var queryTime=System.currentTimeMillis()
            var offline=false
            val loader=FavoriteBusArrivalLoader(seoulRoutes={if(offline) throw java.io.IOException("offline") else listOf(route)},
                seoulArrivals={_,_->listOf(row(queryTime,240))},clock={queryTime})
            vm.javaClass.getDeclaredField("favoriteArrivals").apply {isAccessible=true}.set(vm,loader)
            val previous=request.copy(busRoute=route.copy(stopOrder="5"),identity=route.copy(stopOrder="5").identity)
            JourneyMonitoringController.publish(JourneyMonitoringState(true,previous,message="걸으세요"))
            activity.get().setContent {RUN_or_WALKTheme {PaceNudgeScreen(activity.get(),vm)}}
            compose.onNodeWithText("출발지").performTextInput("수원역")
            Assert.assertEquals(previous,JourneyMonitoringController.state.value.request)
            Assert.assertTrue(JourneyMonitoringController.state.value.running)
            compose.onNodeWithContentDescription("자주 타는 버스").performClick()
            compose.onNodeWithText("이동 안내").performClick()
            compose.onNodeWithText("새 경로로 변경").assertIsDisplayed()
            Assert.assertEquals(previous,JourneyMonitoringController.state.value.request)
            compose.onNodeWithText("기존 안내 유지").performClick()
            Assert.assertTrue(JourneyMonitoringController.state.value.running)
            compose.onNodeWithText("이동 안내 변경").assertDoesNotExist()
            compose.runOnIdle {queryTime+=30000;offline=true}
            compose.onNodeWithText("이동 안내").performClick()
            compose.onNodeWithText("새 경로로 변경").performClick()
            compose.onNodeWithText("기존 안내를 유지했어요").assertIsDisplayed()
            Assert.assertEquals(previous,JourneyMonitoringController.state.value.request)
            Assert.assertTrue(JourneyMonitoringController.state.value.running)
            compose.onNodeWithText("확인").performClick()
            compose.runOnIdle {activity.get().onBackPressedDispatcher.onBackPressed()}
            compose.onNodeWithContentDescription("경로 찾기").assertIsSelected()
            Assert.assertEquals(previous,JourneyMonitoringController.state.value.request)
        } finally {activity.pause().stop().destroy();JourneyMonitoringController.publish(JourneyMonitoringState())}
    }
    @Test fun favoriteDownwardOrderingPersistsAndDeletionRequiresConfirmation() {
        val app=RuntimeEnvironment.getApplication();app.getSharedPreferences("favorite_buses",0).edit().clear().commit()
        val store=FavoriteBusStore(app);val one=FavoriteBus(request)
        val otherRoute=route.copy(routeId="100000025",name="동대문A01")
        val two=FavoriteBus(request.copy(busRoute=otherRoute,identity=otherRoute.identity))
        store.toggle(one);store.toggle(two)
        store.moveDown(one.key);Assert.assertEquals(listOf(two.key,one.key),FavoriteBusStore(app).items.map {it.key})
        val loader=FavoriteBusArrivalLoader(seoulRoutes={listOf(route,otherRoute)},seoulArrivals={_,_->emptyList()})
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            activity.get().setContent {RUN_or_WALKTheme {FavoriteBusesScreen(store,activity.get(),loader) {}}}
            compose.onAllNodesWithContentDescription("즐겨찾기 해제")[0].performClick()
            Assert.assertEquals(2,store.items.size)
            compose.onNodeWithText("유지").performClick();Assert.assertEquals(2,store.items.size)
            compose.onAllNodesWithContentDescription("즐겨찾기 해제")[0].performClick()
            compose.onNodeWithText("삭제").performClick();Assert.assertEquals(1,store.items.size)
        } finally {activity.pause().stop().destroy()}
    }
    @Test fun onlyTheAdviceHeadingAnnouncesChangesNotTheWholeEtaCard() {
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var now by mutableLongStateOf(1800000000000L)
        try {
            activity.get().setContent {RUN_or_WALKTheme {AutomaticGuidanceCard(AutoGuidanceAssessment(AutoGuidanceStatus.WALK),
                "수인분당선","망포역","",now,1800000300000L,null,1800000000000L,180)}}
            compose.onNodeWithTag("guidance-card").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
            compose.onNodeWithText("걸으세요").assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion,LiveRegionMode.Polite))
            compose.runOnIdle {now+=1000}
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertCountEquals(1)
        } finally {activity.pause().stop().destroy()}
    }
    @Test fun missingAndMeasuredZeroSpeedHaveDifferentAccessibleGpsStatus() {
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var speed by mutableStateOf<Double?>(null)
        try {
            activity.get().setContent {RUN_or_WALKTheme {JourneyMetrics(speed,390)}}
            compose.onNodeWithText("0.0 km/h").assertIsDisplayed()
            compose.onNodeWithContentDescription("GPS 속도 정보 없음. 도보 계산에는 기본 설정을 사용합니다.").assertExists()
            compose.runOnIdle {speed=0.0}
            compose.onNodeWithText("0.0 km/h").assertIsDisplayed()
            compose.onNodeWithContentDescription("유효한 GPS 속도 수신").assertExists()
        } finally {activity.pause().stop().destroy()}
    }
    @Test @Config(qualifiers="w360dp-h1100dp-mdpi") fun mapShowsMixedSourcesPerVehicleAndCollapseLeavesArrivalsVisible() {
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val now=1800000000000L
        val id=OfficialBoardingIdentity("seoul","1075075241","1075","상행","왕십리")
        fun estimate(at:Long,train:String)=OfficialTimetableEstimate(id,train,java.time.LocalDate.of(2026,10,28),
            OfficialServiceDayType.WEEKDAY,OfficialTrainOperationType.LOCAL,"pattern","previous",3,at,at,at,0,0,now,now,0,now+60000,
            OfficialTimetableSource("official","official","v1",now-100000),"calendar")
        val live=RealtimeArrivalSnapshot(id,now+300000,fetchedAtEpochMillis=now,validForMillis=600000,receivedAtEpochMillis=now)
        val evidence=TimetableArrivalEvidence(id,estimate(now+300000,"first"),estimate(now+780000,"next"))
        var expanded by mutableStateOf(true)
        try {
            activity.get().setContent {RUN_or_WALKTheme {
                Scaffold(bottomBar={DashboardNavigation(1) {}}) {padding ->
                    Column(Modifier.padding(padding).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        GuidanceHeader {};Text("지도·도착정보",style=MaterialTheme.typography.titleLarge)
                        JourneyMapCard(expanded,{expanded=!expanded}) {
                            Surface(Modifier.fillMaxWidth().height(if(expanded) 340.dp else 112.dp),color=MaterialTheme.colorScheme.surfaceVariant) {
                                Box(contentAlignment=androidx.compose.ui.Alignment.Center) {Text("네이티브 Kakao Map 영역\n실제 지도 타일은 기기에서 검증")}
                            }
                        }
                        MapArrivalOverview("수인분당선","망포역","영통·왕십리 방면",live,evidence,now,"도착정보 없음",null)
                    }
                }
            }}
            compose.onNodeWithText("첫 번째 차량 · 약 5분 0초 후 도착 · 공식 실시간").assertIsDisplayed()
            compose.onNodeWithText("다음 차량 · 약 13분 후 · 열차 위치 · 시간표 추정").assertIsDisplayed()
            save(activity.get(),"final-map-arrivals.png")
            compose.onNodeWithText("지도 줄이기").performClick()
            compose.onNodeWithText("지도 펼치기").assertIsDisplayed()
            compose.onNodeWithText("다음 차량 · 약 13분 후 · 열차 위치 · 시간표 추정").assertIsDisplayed()
        } finally {activity.pause().stop().destroy()}
    }

}
