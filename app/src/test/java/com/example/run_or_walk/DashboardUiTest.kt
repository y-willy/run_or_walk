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
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=Application::class,qualifiers="w360dp-h960dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashboardUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    private fun render(dark: Boolean,name: String) {
        RuntimeEnvironment.setFontScale(if(name.contains("compact")) 1.3f else 1f)
        val time=1800000000000L
        val lifecycle=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            lifecycle.get().setContent { RUN_or_WALKTheme(darkTheme=dark) {
                Scaffold(bottomBar={DashboardNavigation(0) {}}) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        PaceNudgeHeader();Text("수인분당선 · 망포 · 왕십리 방면")
                        JourneyMetrics(1.3,250)
                        AutomaticGuidanceCard(AutoGuidanceAssessment(AutoGuidanceStatus.WALK,walkingSeconds=190,bufferSeconds=60),
                            "수인분당선","망포역","",time,time+400000,null,time,190)
                    }
                }
            } }
            compose.onNodeWithText("걸으세요").assertIsDisplayed()
            compose.onNodeWithText("현재 이동속도").assertIsDisplayed()
            compose.onNodeWithContentDescription("자주 타는 버스").assertIsDisplayed()
            compose.onNodeWithText("도착시간 비교").performScrollTo().assertIsDisplayed()
            System.getProperty("pacenudge.proofDir")?.let { directory ->
                val view=lifecycle.get().window.decorView
                val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
                File(directory).apply { mkdirs() }.resolve(name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            }
        } finally { lifecycle.pause().stop().destroy() }
    }
    @Test fun lightDashboard()=render(false,"dashboard-light.png")
    @Test fun darkDashboard()=render(true,"dashboard-dark.png")
    @Test @Config(qualifiers="w320dp-h1100dp-mdpi") fun compactDashboard()=render(false,"dashboard-compact.png")
    @Test fun favoritesPersistToggleRenameOrderAndDeleteAcrossStoreRecreation() {
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("favorite_buses",0).edit().clear().commit()
        val store=FavoriteBusStore(app)
        val point=MapPoint(127.05,37.59)
        fun favorite(order: String): FavoriteBus {
            val stop=OfficialSeoulBusStop("105900050","06510","경희대의료원",point)
            val route=OfficialSeoulBusRoute(stop.stopId,"105900003","동대문01",order,"회기역","2")
            return FavoriteBus(JourneyMonitoringRequest(stop.name,point,route.identity,busStop=stop,busRoute=route))
        }
        val first=favorite("4");val second=favorite("5")
        store.toggle(first);store.toggle(second);store.rename(first.key,"통학")
        store.moveUp(second.key)
        val restored=FavoriteBusStore(app)
        Assert.assertEquals(second.key,restored.items.first().key)
        Assert.assertEquals("통학",restored.items.last().alias)
        restored.toggle(second)
        Assert.assertEquals(1,FavoriteBusStore(app).items.size)
        restored.remove(first.key)
        Assert.assertTrue(FavoriteBusStore(app).items.isEmpty())
    }
    @Test @Config(qualifiers="w320dp-h960dp-mdpi") fun favoriteCardShowsOfficialEtaSelectsExactDirectionAndCanBeRemoved() {
        RuntimeEnvironment.setFontScale(1.3f)
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("favorite_buses",0).edit().clear().commit()
        val store=FavoriteBusStore(app)
        val point=MapPoint(127.05,37.59)
        val stop=OfficialSeoulBusStop("105900050","06510","경희대의료원",point)
        val route=OfficialSeoulBusRoute(stop.stopId,"105900003","동대문01","4","회기역","2")
        val favorite=FavoriteBus(JourneyMonitoringRequest(stop.name,point,route.identity,busStop=stop,busRoute=route))
        store.toggle(favorite)
        val now=System.currentTimeMillis()
        val snapshot=RealtimeArrivalSnapshot(route.identity,now+240000,fetchedAtEpochMillis=now,validForMillis=600000,receivedAtEpochMillis=now)
        var queries=0
        val loader=FavoriteBusArrivalLoader(seoulRoutes={listOf(route)},seoulArrivals={_,_->queries++;
            listOf(OfficialSeoulBusArrival(route.identity,"vehicle",route.name,route.direction,snapshot)) })
        var chosen: FavoriteBus?=null
        val lifecycle=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            lifecycle.get().setContent { RUN_or_WALKTheme { FavoriteBusesScreen(store,lifecycle.get(),loader) { chosen=it } } }
            compose.waitForIdle()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            compose.waitUntil(10000) { queries>=1 }
            compose.onNodeWithText("이 버스로 이동 안내").assertIsEnabled().performClick()
            Assert.assertEquals(favorite.key,chosen!!.key)
            compose.onNodeWithText("★ 해제").assertIsDisplayed()
            Assert.assertTrue(compose.onNodeWithText("★ 해제").fetchSemanticsNode().boundsInRoot.right<=320f)
            compose.onNodeWithText("★ 해제").performClick()
            compose.onNodeWithText("아직 저장한 버스가 없어요").assertIsDisplayed()
            Assert.assertEquals(1,queries)
        } finally { lifecycle.pause().stop().destroy() }
    }

}
