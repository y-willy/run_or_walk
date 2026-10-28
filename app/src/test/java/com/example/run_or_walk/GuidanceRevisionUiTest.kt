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
import androidx.lifecycle.ViewModelProvider
import com.example.run_or_walk.ui.theme.RUN_or_WALKTheme
import java.io.File
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=Application::class,qualifiers="w360dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GuidanceRevisionUiTest {
 @get:Rule val compose=createEmptyComposeRule()
 private val now=1800000000000L
 private val point=MapPoint(127.0,37.0)
 private val id=OfficialBoardingIdentity("seoul-bus","105900050","105900003","4","105900003")
 private fun assessment(slack:Int)=calculateMovementGuidance(AutoGuidanceInput(GuidanceLocation(point,3.0,now,1.3,0.2),
  GuidanceBoarding(id,point,true),GuidanceWalkingRoute(point,point,id,300,now,120000,390),snapshot(slack),true),now,JourneySettings(bufferSeconds=60))
 private fun snapshot(slack:Int)=RealtimeArrivalSnapshot(id,now+(360+slack)*1000L,nextArrivalAtEpochMillis=now+1500000,
  fetchedAtEpochMillis=now,validForMillis=600000,receivedAtEpochMillis=now)
 private fun save(activity:ComponentActivity,name:String) {
  System.getProperty("pacenudge.proofDir")?.let {dir -> val v=activity.window.decorView
   val bitmap=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(bitmap))
   File(dir).apply {mkdirs()}.resolve(name).outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)} }
 }
 private fun render(slack:Int,dark:Boolean,name:String,scale:Float=1f) {
  RuntimeEnvironment.setFontScale(scale)
  val a=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
  try {
   a.get().setContent {RUN_or_WALKTheme(darkTheme=dark) {
    Scaffold(bottomBar={DashboardNavigation(0) {}}) {padding ->
     Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
      GuidanceHeader {};Text("동대문01 · 경희대의료원 · 회기역 방면")
      JourneyMetrics(1.3,390)
      val value=assessment(slack)
      AutomaticGuidanceCard(value,"동대문01","경희대의료원","",now,value.firstArrivalAtEpochMillis,value.nextArrivalAtEpochMillis,
       value.arrivalFetchedAtEpochMillis,value.walkingSeconds,vehicleLabel="버스")
     }
    }
   }}
   compose.onNodeWithText(movementGuidanceTitle(assessment(slack),now)).assertIsDisplayed()
   compose.onNodeWithContentDescription("지도·도착정보").assertIsDisplayed()
   compose.onNodeWithText("도착정보 확인 중").assertDoesNotExist()
   save(a.get(),name)
  } finally {a.pause().stop().destroy()}
 }
 @Test fun relaxedScreen()=render(300,false,"guidance-relaxed.png")
 @Test fun walkScreen()=render(180,false,"guidance-walk.png")
 @Test fun cautionScreen()=render(60,false,"guidance-caution.png")
 @Test fun runScreen()=render(-30,false,"guidance-run.png")
 @Test fun nextScreen()=render(-120,false,"guidance-next.png")
 @Test fun darkScreen()=render(60,true,"guidance-dark.png")
 @Test @Config(qualifiers="w320dp-h1100dp-mdpi") fun compactNextScreenAt130Percent()=render(-120,false,"guidance-compact.png",1.3f)
 @Test fun informationScreenIncludesCriteriaWalkingVibrationAndSources() {
  val a=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
  try {
   a.get().setContent {RUN_or_WALKTheme {GuidanceInformationPage(JourneySettings(),null,4.3,1.3,{}, {})}}
   compose.onNodeWithText("이동 안내 기준").assertIsDisplayed()
   guidanceBands.forEach {compose.onNodeWithText(it.first).performScrollTo().assertIsDisplayed()}
   compose.onNodeWithText("이동 안내 기준").performScrollTo();save(a.get(),"guidance-information.png")
   compose.onNodeWithText("진동별 의미").performScrollTo().assertIsDisplayed()
   for(label in listOf("여유 있어요: 진동 없음","걸으세요: 1회","놓칠 수 있어요: 2회","뛰세요: 3회","다음 차량 안내: 2초 연속"))
    compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
   save(a.get(),"information-haptic-patterns.png")
   compose.onNodeWithText("현재 이동속도: 0.0 km/h").performScrollTo().assertIsDisplayed()
   compose.onNodeWithText("개인 평균 걷기 속도: 4.3 km/h").performScrollTo().assertIsDisplayed()
   guidanceDataSources.forEach {compose.onNodeWithText(it.name).performScrollTo().assertIsDisplayed()}
  } finally {a.pause().stop().destroy()}
 }
 @Test fun mapArrivalScreenUsesSameEtaAndSeparatesIndependentFirstAndNext() {
  val a=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
  try {
   a.get().setContent {RUN_or_WALKTheme {
    Scaffold(bottomBar={DashboardNavigation(1) {}}) {padding ->
     Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
      GuidanceHeader {};Text("지도·도착정보",style=MaterialTheme.typography.titleLarge)
      JourneyMapCard(true,{}) {Surface(Modifier.fillMaxWidth().height(220.dp),color=MaterialTheme.colorScheme.surfaceVariant) {
       Box(contentAlignment=androidx.compose.ui.Alignment.Center) {Text("Kakao Map 영역\n실제 지도 타일은 기기에서 표시")}
      }}
      MapArrivalOverview("동대문01","경희대의료원","회기역 방면",snapshot(180),null,now,"도착정보 확인 중",null)
     }
    }
   }}
   compose.onNodeWithText("첫 번째 차량 · 약 9분 0초 후 도착").assertIsDisplayed()
   compose.onNodeWithText("다음 차량 · 약 25분 0초 후 도착").assertIsDisplayed()
   save(a.get(),"guidance-map-arrivals.png")
  } finally {a.pause().stop().destroy()}
 }
 @Test fun realRootTabsAndInfoPreserveSearchSessionAndKeepStopSearchAccessible() {
  val a=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
  try {
   val vm=ViewModelProvider(a.get())[JourneySessionViewModel::class.java]
   vm.screens.values.forEach {it.clearRoutesAndBoarding()};vm.mode=FeatureMode.ROUTE
   JourneyMonitoringController.publish(JourneyMonitoringState())
   a.get().setContent {RUN_or_WALKTheme {PaceNudgeScreen(a.get(),vm)}}
   compose.onNodeWithContentDescription("지도·도착정보").performClick()
   compose.onNodeWithText("경로를 먼저 선택해 주세요").assertIsDisplayed()
   Assert.assertEquals(FeatureMode.ROUTE,vm.mode)
   compose.onNodeWithContentDescription("경로 찾기").performClick()
   compose.onNodeWithText("정류소 직접 검색").performClick()
   Assert.assertEquals(FeatureMode.STOP,vm.mode)
   compose.runOnIdle {vm.screens.getValue(FeatureMode.STOP).stop.query="망포"}
   compose.onNodeWithContentDescription("지도·도착정보").performClick()
   Assert.assertEquals("망포",vm.screens.getValue(FeatureMode.STOP).stop.query)
   compose.onNodeWithContentDescription("경로 찾기").performClick()
   compose.onNodeWithContentDescription("앱 정보").performClick()
   org.robolectric.shadows.ShadowLooper.idleMainLooper()
   compose.onNodeWithText("앱 정보").assertIsDisplayed()
   compose.onNodeWithText("닫기").performClick()
   compose.onNodeWithText("앱 정보").assertDoesNotExist()
   Assert.assertEquals(FeatureMode.STOP,vm.mode)
  } finally {a.pause().stop().destroy()}
 }
 @Test fun validLiveDataOverridesAnOldUnknownMonitoringAssessmentImmediately() {
  Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
  val a=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
  try {
   val vm=ViewModelProvider(a.get())[JourneySessionViewModel::class.java]
   vm.screens.values.forEach {it.clearRoutesAndBoarding()};vm.mode=FeatureMode.STOP
   vm.updateSettings(JourneySettings(bufferSeconds=60))
   val screen=vm.screens.getValue(FeatureMode.STOP)
   val stop=OfficialSeoulBusStop(id.stopId,"06510","경희대의료원",point)
   val route=OfficialSeoulBusRoute(id.stopId,id.routeId,"동대문01",id.directionId,"회기역","2")
   screen.boarding=stop.toJourneyPlace();screen.stop.selected=stop.toJourneyPlace();screen.boardingChosenByUser=true
   screen.selectedBusStop=stop;screen.selectedBusRoute=route;screen.confirmedServiceIdentity=route.identity
   val time=System.currentTimeMillis()
   val arrival=RealtimeArrivalSnapshot(route.identity,time+540000,fetchedAtEpochMillis=time,validForMillis=600000,receivedAtEpochMillis=time)
   val request=JourneyMonitoringRequest(stop.name,point,route.identity,busStop=stop,busRoute=route,settings=vm.settings)
   JourneyMonitoringController.publish(JourneyMonitoringState(true,request,assessment=AutoGuidanceAssessment(AutoGuidanceStatus.UNKNOWN),
    location=GuidanceLocation(point,3.0,time),walkingRoute=GuidanceWalkingRoute(point,point,id,300,time,120000,390),
    arrivals=arrival,walkingDistanceMeters=390,providerWalkingSeconds=300))
   a.get().setContent {RUN_or_WALKTheme {PaceNudgeScreen(a.get(),vm)}}
   compose.onNodeWithText("걸으세요").performScrollTo().assertIsDisplayed()
   compose.onNodeWithText("이동 정보 확인 필요").assertDoesNotExist()
   Assert.assertTrue(JourneyMonitoringController.state.value.running)
  } finally {a.pause().stop().destroy();JourneyMonitoringController.publish(JourneyMonitoringState())}
 }

 @Test fun selectedRouteShowsGuideBeforeSecondaryActionsAndSavesFavoritesInsideTheCard() {
  Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
  RuntimeEnvironment.getApplication().getSharedPreferences("favorite_buses",0).edit().clear().commit()
  val a=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
  try {
   val vm=ViewModelProvider(a.get())[JourneySessionViewModel::class.java]
   vm.screens.values.forEach {it.clearRoutesAndBoarding()};vm.mode=FeatureMode.ROUTE
   vm.updateSettings(JourneySettings(bufferSeconds=60))
   val screen=vm.screens.getValue(FeatureMode.ROUTE)
   val stop=OfficialSeoulBusStop(id.stopId,"06510","경희대의료원",point)
   val route=OfficialSeoulBusRoute(id.stopId,id.routeId,"동대문01",id.directionId,"회기역","2")
   val choice=JourneyTransitRoute("selected",KakaoTransitChoice(1200,0,route.name,stop.name,point,firstTransitType="BUS"))
   screen.routes=listOf(choice);screen.selectedRoute=choice
   screen.boarding=stop.toJourneyPlace();screen.boardingChosenByUser=true
   screen.selectedBusStop=stop;screen.selectedBusRoute=route;screen.confirmedServiceIdentity=route.identity
   screen.error="위치 설정을 확인해 주세요."
   val time=System.currentTimeMillis()
   val request=JourneyMonitoringRequest(stop.name,point,route.identity,busStop=stop,busRoute=route,settings=vm.settings)
   JourneyMonitoringController.publish(JourneyMonitoringState(true,request,assessment=AutoGuidanceAssessment(AutoGuidanceStatus.UNKNOWN),
    location=GuidanceLocation(point,3.0,time),walkingRoute=GuidanceWalkingRoute(point,point,id,300,time,120000,390),
    arrivals=RealtimeArrivalSnapshot(route.identity,time+540000,fetchedAtEpochMillis=time,validForMillis=600000,receivedAtEpochMillis=time),
    walkingDistanceMeters=390,providerWalkingSeconds=300))
   a.get().setContent {RUN_or_WALKTheme {PaceNudgeScreen(a.get(),vm)}}
   compose.onNodeWithText("이동가이드").assertIsDisplayed()
   compose.onNodeWithText("지금 이동 안내").assertDoesNotExist()
   compose.onNodeWithText("이동 가이드").assertDoesNotExist()
   val summary=compose.onNodeWithText("총 20분 · 동대문01").getUnclippedBoundsInRoot()
   val guide=compose.onNodeWithTag("guidance-card").getUnclippedBoundsInRoot()
   val metrics=compose.onNodeWithTag("journey-metrics").getUnclippedBoundsInRoot()
   Assert.assertTrue("Metrics must be above the guide",metrics.top>=summary.bottom && guide.top>=metrics.bottom && guide.top-metrics.bottom<=10.dp)
   val stopButton=compose.onNodeWithText("이동 안내 종료").getUnclippedBoundsInRoot()
   Assert.assertTrue("Monitoring controls must immediately follow the guide",stopButton.top>=guide.bottom && stopButton.top-guide.bottom<=10.dp)
   for(label in listOf("다른 경로 보기","현재 위치·경로 다시 확인","기기 위치 설정"))
    Assert.assertTrue("$label must follow the guide",compose.onNodeWithText(label).getUnclippedBoundsInRoot().top>=guide.bottom)
   compose.onAllNodesWithText("자주 타는 버스 등록").assertCountEquals(1)
   compose.onNodeWithText("자주 타는 버스 등록").assert(hasAnyAncestor(hasTestTag("guidance-card"))).performClick()
   Assert.assertEquals(route.identity,vm.favorites.items.single().request.identity)
   compose.onNodeWithText("자주 타는 버스 해제").assertIsDisplayed()
   save(a.get(),"guide-controls-haptics.png")
   compose.onNodeWithText("자주 타는 버스 해제").performClick()
   Assert.assertTrue(vm.favorites.items.isEmpty())
   for(label in listOf("다른 경로 보기","현재 위치·경로 다시 확인","기기 위치 설정")) {
    val bounds=compose.onNodeWithText(label).getUnclippedBoundsInRoot()
    Assert.assertTrue(bounds.bottom-bounds.top>=52.dp);Assert.assertTrue(bounds.right-bounds.left>=300.dp)
   }
   compose.onNodeWithText("기기 위치 설정").performScrollTo()
   save(a.get(),"guide-action-buttons.png")
   compose.onNodeWithText("다른 경로 보기").performScrollTo().performClick()
   compose.onNodeWithText("다른 경로 접기").assertIsDisplayed()
   compose.onNodeWithTag("guidance-card").performScrollTo()
   Assert.assertTrue(compose.onNodeWithText("다른 경로 접기").getUnclippedBoundsInRoot().top>=compose.onNodeWithTag("guidance-card").getUnclippedBoundsInRoot().bottom)
   Assert.assertEquals(choice,screen.selectedRoute)
  } finally {a.pause().stop().destroy();JourneyMonitoringController.publish(JourneyMonitoringState())}
 }

 @Test fun positiveSubsecondSlackDoesNotRenderAsZeroOrContradictTheCautionTitle() {
  val a=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
  try {
   val state=AutoGuidanceAssessment(AutoGuidanceStatus.CAUTION,bufferSeconds=60)
   a.get().setContent {RUN_or_WALKTheme {AutomaticGuidanceCard(state,"동대문01","정류장","",now,now+360001,null,now,300)}}
   compose.onNodeWithText("놓칠 수 있어요").assertIsDisplayed()
   compose.onNodeWithText("이동 여유 1초 미만").assertIsDisplayed()
  } finally {a.pause().stop().destroy()}
 }

}
