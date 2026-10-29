package com.example.run_or_walk

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.*
import com.example.run_or_walk.ui.theme.RUN_or_WALKTheme
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=Application::class,qualifiers="w360dp-h1100dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GuidanceUnknownUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun sixRealComposeRendersKeepPairedMetricsUntilTheirActualExpiry() {
        val epoch=1_800_000_000_000L;val origin=MapPoint(127.0,37.0);val end=origin.copy(y=37.00377)
        val bus=OfficialSeoulBusRoute("100000001","100000002","종로02","4","종각역","2") // synthetic IDs
        val stop=OfficialSeoulBusStop(bus.stopId,"01001","모의 승차점",end)
        val settings=JourneySettings(useProviderWalkingTime=true,gpsAutoCalibrationEnabled=false)
        val request=JourneyMonitoringRequest(stop.name,end,bus.identity,busStop=stop,busRoute=bus,settings=settings)
        fun arrival(at:Long,seconds:Int=420)=RealtimeArrivalSnapshot(bus.identity,at+seconds*1000,at+900000,
            at,60000,receivedAtEpochMillis=at)
        var clock by mutableLongStateOf(epoch)
        var state by mutableStateOf(JourneyMonitoringState(true,request,
            location=GuidanceLocation(origin,3.0,epoch,0.0,0.2),
            walkingRoute=GuidanceWalkingRoute(origin,end,bus.identity,300,epoch,120000,420),arrivals=arrival(epoch),
            walkingDistanceMeters=420,providerWalkingSeconds=300,walkingPoints=listOf(origin,end),walkingCalculatedAtEpochMillis=epoch))
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val images=mutableListOf<Bitmap>()
        val directory=System.getProperty("pacenudge.proofDir")?.let {File(it).apply {mkdirs()}}
        fun save(name:String) {
            compose.waitForIdle();val view=activity.get().window.decorView
            val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap));images+=bitmap
            directory?.resolve(name)?.outputStream()?.use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
        try {
            activity.get().setContent {RUN_or_WALKTheme {
                val current=monitoringStateWithSettings(state,request,clock,true)
                val metrics=current.walkingRoute?.displayMetrics(current.location,end,clock)
                val assessment=current.assessment!!
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
                    verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    PaceNudgeHeader();JourneyMetrics(displayedWalkingSpeed(current.location,clock),metrics?.distanceMeters)
                    AutomaticGuidanceCard(assessment,bus.name,stop.name,"",clock,current.arrivals?.firstArrivalAtEpochMillis,
                        current.arrivals?.usableNextArrivalAt(clock),current.arrivals?.fetchedAtEpochMillis,metrics?.durationSeconds,
                        unknownTitle=unknownGuidanceTitle(assessment.unknownReason,"이동 정보 확인 필요"),vehicleLabel="버스")
                }
            }}
            compose.onNodeWithText("420m").assertIsDisplayed();compose.onNodeWithText("뛰세요").assertIsDisplayed()
            save("01-normal.png")
            compose.runOnIdle {clock=epoch+5000;state=state.copy(walkingRequestElapsed=5000)}
            compose.onNodeWithText("420m").assertIsDisplayed();compose.onNodeWithText("도보 약 5분").assertIsDisplayed()
            compose.onNodeWithText("뛰세요").assertIsDisplayed();save("02-walking-request.png")
            compose.runOnIdle {clock=epoch+10000;state=state.copy(arrivalRequestFailed=true,arrivalError="모의 일시적 연결 지연")}
            compose.onNodeWithText("420m").assertIsDisplayed();compose.onNodeWithText("뛰세요").assertIsDisplayed()
            compose.onNodeWithText("버스 6분 50초 후").assertIsDisplayed();save("03-eta-request.png")
            compose.runOnIdle {
                clock=epoch+20000
                state=state.copy(location=state.location!!.copy(observedAtEpochMillis=clock),
                    walkingRoute=state.walkingRoute!!.copy(distanceMeters=400,durationSeconds=290,fetchedAtEpochMillis=epoch+15000),
                    walkingDistanceMeters=400,providerWalkingSeconds=290,arrivals=arrival(clock,480),arrivalRequestFailed=false)
            }
            compose.onNodeWithText("400m").assertIsDisplayed();compose.onNodeWithText("놓칠 수 있어요").assertIsDisplayed()
            save("04-new-response.png")
            compose.runOnIdle {
                clock=epoch+22000
                val old=LiveWalkingEvidence(state.location,state.walkingRoute,400,290,state.walkingPoints,epoch+15000)
                val kept=updateLiveWalkingEvidence(old,null,state.location!!.copy(accuracyMeters=100.0,
                    observedAtEpochMillis=clock),bus.identity,end,settings,null,clock,false)
                state=state.copy(location=kept.location,walkingRoute=kept.walking,
                    walkingDistanceMeters=kept.distanceMeters,providerWalkingSeconds=kept.providerSeconds)
            }
            compose.onNodeWithText("400m").assertIsDisplayed();compose.onNodeWithText("도보 약 5분").assertIsDisplayed()
            compose.onNodeWithText("정보 확인 중").assertDoesNotExist();save("05-uncertain-gps.png")
            compose.runOnIdle {clock=epoch+141000;state=state.copy(location=state.location!!.copy(observedAtEpochMillis=clock),arrivals=arrival(clock))}
            compose.onNodeWithText("400m").assertDoesNotExist();compose.onNodeWithText("도보 약 5분").assertDoesNotExist()
            compose.onNodeWithText("현재 위치 기준으로 다시 계산해요").assertIsDisplayed();save("06-expired.png")
            if(directory!=null) {
                val width=images.first().width;val height=images.first().height;val labelHeight=40
                val combined=Bitmap.createBitmap(width*3,(height+labelHeight)*2,Bitmap.Config.ARGB_8888)
                val canvas=Canvas(combined);canvas.drawColor(android.graphics.Color.rgb(245,247,250))
                val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=android.graphics.Color.rgb(15,23,42);textSize=18f}
                val labels=listOf("1. 정상 안내","2. 도보 재계산 중","3. ETA 갱신 지연","4. 신규 응답 즉시 적용","5. GPS 일시적 불안정","6. 실제 유효기간 만료")
                images.forEachIndexed {index,bitmap ->val x=(index%3)*width;val y=(index/3)*(height+labelHeight)
                    canvas.drawText(labels[index],x+12f,y+26f,paint);canvas.drawBitmap(bitmap,x.toFloat(),(y+labelHeight).toFloat(),null)}
                directory.resolve("comparison.png").outputStream().use {combined.compress(Bitmap.CompressFormat.PNG,100,it)}
            }
            compose.runOnIdle {
                clock=epoch
                state=state.copy(location=GuidanceLocation(origin,3.0,epoch,0.0,0.2),
                    walkingRoute=GuidanceWalkingRoute(origin,end,bus.identity,300,epoch,120000,420),
                    walkingDistanceMeters=420,providerWalkingSeconds=300,arrivals=arrival(epoch,600))
            }
            compose.onNodeWithText("걸으세요").assertIsDisplayed()
            compose.waitForIdle()
            directory?.let {dir ->
                val bounds=compose.onNodeWithTag("guidance-card").getUnclippedBoundsInRoot()
                val view=activity.get().window.decorView
                val bitmap=Bitmap.createBitmap((bounds.right.value-bounds.left.value).toInt(),
                    (bounds.bottom.value-bounds.top.value).toInt(),Bitmap.Config.ARGB_8888)
                val canvas=Canvas(bitmap);canvas.translate(-bounds.left.value,-bounds.top.value);view.draw(canvas)
                dir.resolve("cream-mint-card.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            }
        } finally {activity.pause().stop().destroy()}
    }

    @Test fun monitoringCannotMixItsWalkingRouteWithAnotherForegroundLocation() {
        val app=RuntimeEnvironment.getApplication();Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val owner=object:LifecycleOwner {val registry=LifecycleRegistry(this).apply {currentState=Lifecycle.State.CREATED};override val lifecycle:Lifecycle get()=registry}
        try {
            val vm=ViewModelProvider(activity.get(),ViewModelProvider.AndroidViewModelFactory(activity.get().application))[JourneySessionViewModel::class.java]
            vm.screens.values.forEach {it.clearRoutesAndBoarding()};vm.mode=FeatureMode.ROUTE
            val settings=JourneySettings(useProviderWalkingTime=true,gpsAutoCalibrationEnabled=false);vm.updateSettings(settings)
            val now=vm.displayClock.now();val origin=MapPoint(127.0,37.0);val end=origin.copy(y=37.00377)
            val bus=OfficialSeoulBusRoute("100000001","100000002","종로02","4","종각역","2")
            val stop=OfficialSeoulBusStop(bus.stopId,"01001","모의 승차점",end)
            val request=JourneyMonitoringRequest(stop.name,end,bus.identity,busStop=stop,busRoute=bus,settings=settings)
            val screen=vm.screens.getValue(FeatureMode.ROUTE)
            screen.boarding=stop.toJourneyPlace();screen.boardingChosenByUser=true
            screen.selectedBusStop=stop;screen.selectedBusRoute=bus;screen.confirmedServiceIdentity=bus.identity
            val fix=GuidanceLocation(origin,3.0,now-5000,0.0,0.2)
            val snapshot=RealtimeArrivalSnapshot(bus.identity,now+420000,now+900000,now,60000,receivedAtEpochMillis=now)
            val state=JourneyMonitoringState(true,request,location=fix,
                walkingRoute=GuidanceWalkingRoute(origin,end,bus.identity,300,now,120000,420),arrivals=snapshot,
                walkingDistanceMeters=420,providerWalkingSeconds=300,walkingPoints=listOf(origin,end))
            JourneyMonitoringController.publish(monitoringStateWithSettings(state,request,now,true))
            vm.observeLocation(fix.copy(point=origin.copy(y=origin.y+80.0/111195),observedAtEpochMillis=now),now)
            activity.get().setContent {RUN_or_WALKTheme {PaceNudgeScreen(owner,vm)}}
            compose.waitForIdle()
            System.getProperty("pacenudge.proofDir")?.let {dir ->
                val view=activity.get().window.decorView;val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap));File(dir).apply {mkdirs()}.resolve("monitoring-coherence.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            }
            compose.onNodeWithText("420m").assertIsDisplayed()
            compose.onNodeWithText("도보 약 5분").assertIsDisplayed()
            compose.onNodeWithText("뛰세요").assertIsDisplayed()
            compose.onNodeWithText("승차점까지 도보 경로를 확인해 주세요").assertDoesNotExist()
        } finally {activity.pause().stop().destroy();JourneyMonitoringController.publish(JourneyMonitoringState())}
    }
}
