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
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.example.run_or_walk.ui.theme.RUN_or_WALKTheme
import java.io.File
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=Application::class,qualifiers="w360dp-h960dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FinalUiPresentationTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val time=1_800_000_000_000L
    private fun withActivity(content:@Composable ()->Unit,checks:(ComponentActivity)->Unit) {
        val lifecycle=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try { lifecycle.get().setContent(content=content);checks(lifecycle.get()) }
        finally { lifecycle.pause().stop().destroy() }
    }
    @Test fun slackIncludesSafetyBufferAndExpandsActualArrivalClocks() {
        withActivity({RUN_or_WALKTheme { AutomaticGuidanceCard(
            AutoGuidanceAssessment(AutoGuidanceStatus.WALK,bufferSeconds=60),"수인분당선","망포역","",time,time+360000,null,time,180) }}) {
            compose.onNodeWithText("이동 여유 약 2분").assertIsDisplayed()
            compose.onNodeWithText("공식 실시간").assertIsDisplayed()
            compose.onNodeWithText("차량 예정",substring=true).assertDoesNotExist()
            compose.onNodeWithText("이동 여유 약 2분").performClick()
            compose.onNodeWithText("차량 예정",substring=true).assertIsDisplayed()
            compose.onNodeWithText("승차 준비",substring=true).assertIsDisplayed()
        }
    }
    @Test fun invalidEtaDoesNotCreateSlackOrProgressBar() {
        var approved by mutableStateOf<Boolean?>(false)
        var sourceAt by mutableLongStateOf(time)
        withActivity({RUN_or_WALKTheme { AutomaticGuidanceCard(
            AutoGuidanceAssessment(AutoGuidanceStatus.UNKNOWN),"수인분당선","망포역","",time,time+360000,null,sourceAt,180,validatedEta=approved) }}) {
            compose.onNodeWithText("이동 여유",substring=true).assertDoesNotExist()
            compose.onNodeWithText("정보 확인 중").assertIsDisplayed()
            compose.runOnIdle { approved=null;sourceAt=time-700000 }
            compose.onNodeWithText("이동 여유",substring=true).assertDoesNotExist()
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
        }
    }
    @Test fun timetableEtaAndWarningRemainDistinguishableWithoutChangingAssessment() {
        withActivity({RUN_or_WALKTheme { AutomaticGuidanceCard(
            AutoGuidanceAssessment(AutoGuidanceStatus.FAST_WALK,bufferSeconds=60),"수인분당선","망포역","",time,time+180000,null,time,180,
            arrivalSource=GuidanceArrivalSource.OFFICIAL_TIMETABLE_POSITION,validatedEta=true) }}) {
            compose.onNodeWithText("빠르게 이동하세요").assertIsDisplayed()
            compose.onNodeWithText("이동시간 약 1분 부족").assertIsDisplayed()
            compose.onNodeWithText("열차 위치 · 시간표 추정").assertIsDisplayed()
        }
    }
    @Test fun fourMaterialTabsKeepClickSelectionAt360DpAnd130Percent() {
        RuntimeEnvironment.setFontScale(1.3f)
        withActivity({RUN_or_WALKTheme {
            var selected by remember { mutableIntStateOf(0) }
            DashboardNavigation(selected) { selected=it }
        }}) {
            for(label in listOf("경로 찾기","정류장 찾기","자주 타는 버스","설정")) {
                compose.onNodeWithContentDescription(label).performClick().assertIsSelected()
            }
        }
    }
    @Test fun favoriteScreenRendersRealCardsAndManagementMenuKeepsOrdering() {
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("favorite_buses",0).edit().clear().commit()
        val store=FavoriteBusStore(app)
        val point=MapPoint(127.052311,37.594127)
        val stop=OfficialSeoulBusStop("105900050","06510","경희대의료원.경희여중고",point)
        val routes=listOf(OfficialSeoulBusRoute(stop.stopId,"105900003","동대문01","4","회기역 방면","2"),
            OfficialSeoulBusRoute(stop.stopId,"100000025","동대문A01","12","경희의료원 방면","3"))
        routes.forEach { store.toggle(FavoriteBus(JourneyMonitoringRequest(stop.name,point,it.identity,busStop=stop,busRoute=it))) }
        var queries=0
        val now=System.currentTimeMillis()
        val loader=FavoriteBusArrivalLoader(seoulRoutes={routes},seoulArrivals={_,route->queries++;
            listOf(OfficialSeoulBusArrival(route.identity,"fixture-${route.routeId}",route.name,route.direction,
                RealtimeArrivalSnapshot(route.identity,now+240000,fetchedAtEpochMillis=now,validForMillis=600000,receivedAtEpochMillis=now))) })
        val lifecycle=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            lifecycle.get().setContent { RUN_or_WALKTheme {
                Scaffold(bottomBar={DashboardNavigation(2) {}}) { padding ->
                    Box(Modifier.padding(padding)) { FavoriteBusesScreen(store,lifecycle.get(),loader) {} }
                }
            } }
            compose.waitForIdle();org.robolectric.shadows.ShadowLooper.idleMainLooper()
            compose.waitUntil(10000) { queries==2 }
            compose.onAllNodesWithText("이동 안내")[0].assertIsEnabled()
            System.getProperty("pacenudge.proofDir")?.let { directory ->
                val view=lifecycle.get().window.decorView
                val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
                File(directory).apply { mkdirs() }.resolve("favorites-light.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            }
            compose.onAllNodesWithContentDescription("즐겨찾기 관리")[1].performClick()
            compose.onNodeWithText("위로 이동").performClick()
            Assert.assertEquals(routes[1].routeId,store.items[0].request.identity.routeId)
            compose.onAllNodesWithContentDescription("즐겨찾기 관리")[0].performClick()
            compose.onNodeWithText("표시 이름 변경").assertIsDisplayed()
            compose.onNodeWithText("도착정보 새로고침").assertIsDisplayed()
            // Alias persistence is covered by DashboardUiTest. Native dialog input
            // needs device verification: Robolectric's popup/text input looper does
            // not reliably become idle here.
            Assert.assertEquals(2,queries)
        } finally { lifecycle.pause().stop().destroy() }
    }
}
