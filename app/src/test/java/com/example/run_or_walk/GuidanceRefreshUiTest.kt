package com.example.run_or_walk

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.example.run_or_walk.ui.theme.RUN_or_WALKTheme
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
class GuidanceRefreshUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun completedGuidanceRemainsVisibleDuringPositionHandoffThenUsesTheNewEta() {
        val time=1_800_000_000_000L
        val origin=MapPoint(127.0,37.0)
        val boarding=MapPoint(127.0,37.004)
        val id=OfficialBoardingIdentity("seoul","1075075241","1075","상행","왕십리행")
        val row=OfficialSubwayArrival(id,"상행","왕십리행","6132",
            RealtimeArrivalSnapshot(id,time+600000,fetchedAtEpochMillis=time,validForMillis=600000,receivedAtEpochMillis=time),
            arrivalCode="99",officialSlot=1,sourceEtaSeconds=600)
        val old=SubwayTimetableAnalysis(listOf(row),emptyList(),true,null,id.stopId,id.routeId)
        val choice=SubwayArrivalChoice.from(row)
        var rows by mutableStateOf(listOf(row))
        var clock by mutableLongStateOf(time)
        var loading by mutableStateOf(false)
        val settings=JourneySettings(bufferSeconds=60,measuredRunningSpeedKmh=9.0)
        val lifecycle=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            lifecycle.get().setContent { RUN_or_WALKTheme { Surface(Modifier.fillMaxSize()) {
                val arrival=rows.firstOrNull { it.snapshot.hasUsableOfficialEta(clock) }
                    ?: retainedSubwayArrivalDuringRefresh(old,rows,id,choice,clock)
                val assessment=calculateMovementGuidance(AutoGuidanceInput(
                    GuidanceLocation(origin,5.0,time),GuidanceBoarding(id,boarding,true),
                    GuidanceWalkingRoute(origin,boarding,id,400,time-92000,120000,500),arrival?.snapshot,true,
                    bufferSeconds=60),clock,settings)
                AutomaticGuidanceCard(assessment,"수인분당선 · 왕십리행","망포역",
                    if(loading) "갱신 중" else "",clock,arrival?.snapshot?.firstArrivalAtEpochMillis,null,
                    arrival?.snapshot?.fetchedAtEpochMillis,assessment.walkingSeconds)
            } } }
            compose.onNodeWithText("걸으세요").assertIsDisplayed()
            compose.runOnIdle {
                loading=true
                rows=listOf(row.copy(snapshot=row.snapshot.copy(firstArrivalAtEpochMillis=0,
                    reliable=false,trustState=ArrivalTrustState.UNKNOWN),sourceEtaSeconds=0,
                    officialArrivalMessage="[5]번째 전역 (고색)",reportedStationName="고색",
                    sourceReceivedAtEpochMillis=time,gatewayFetchedAtEpochMillis=time,gatewayContract=SubwayGatewayContract.RAW_FIELDS))
            }
            compose.onNodeWithText("걸으세요").assertIsDisplayed()
            compose.onNodeWithText("도착정보 확인 중").assertDoesNotExist()
            compose.runOnIdle { loading=false; rows=listOf(row.copy(snapshot=row.snapshot.copy(firstArrivalAtEpochMillis=time+220000),sourceEtaSeconds=220)) }
            compose.onNodeWithText("다음 차량 이용").assertIsDisplayed()
            // A refresh never extends real GPS/source/route validity.
            compose.runOnIdle { clock=time+61000 }
            compose.onNodeWithText("도착정보 확인 중").assertIsDisplayed()
        } finally { lifecycle.pause().stop().destroy() }
    }
}
