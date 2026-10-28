package com.example.run_or_walk
import java.io.File
import java.time.LocalDate
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SelectionRefreshRegressionTest {
 private val now=1800000000000L
 private val station=OfficialSubwayStation("1075075241","1075","망포","수인분당선")
 private val id=OfficialBoardingIdentity("seoul",station.stopId,station.routeId,"하행","고색행")
 private fun row(number:String,seconds:Int,slot:Int)=OfficialSubwayArrival(id,"하행","고색행",number,
  RealtimeArrivalSnapshot(id,now+seconds*1000,fetchedAtEpochMillis=now,validForMillis=600000,receivedAtEpochMillis=now),officialSlot=slot,arrivalCode="99")
 private val rows=listOf(row("first",240,1),row("second",480,2),row("third",720,3))
 private fun analysis(rows:List<OfficialSubwayArrival> = this.rows)=SubwayTimetableAnalysis(rows,emptyList(),true,null,station.stopId,station.routeId)
 private fun estimate(row:OfficialSubwayArrival,seconds:Int):LinkedSubwayTimetableEstimate {
  val source=OfficialTimetableSource("공식","official","v1",now-86400000)
  return LinkedSubwayTimetableEstimate(row,OfficialTimetableEstimate(row.identity,row.trainId,LocalDate.of(2026,10,26),
   OfficialServiceDayType.WEEKDAY,OfficialTrainOperationType.LOCAL,"path","previous",2,now+seconds*1000,
   now+(seconds-60)*1000,now+(seconds+60)*1000,0,0,now,now,0,now+60000,source,"calendar"))
 }
 @Test fun secondAndThirdRemainTheSelectedVehicleRatherThanTheEarliestService() {
  val state=JourneyScreenState(FeatureMode.STOP)
  for(index in 1..2) {
   state.chooseSubwayArrival(rows[index])
   assertEquals(rows[index],state.selectedArrival)
   assertEquals(rows[index].trainId,selectDestinationSubwayArrival(analysis(),now,state.subwayChoice)!!.trainId)
  }
  assertEquals("first",selectDestinationSubwayArrival(analysis(),now)!!.trainId)
 }
 @Test fun officialPositionRowsRemainSelectableDuringRefreshAndForAnotherDirection() {
  val other=rows[2].copy(identity=id.copy(directionId="상행"),snapshot=rows[2].snapshot.copy(identity=id.copy(directionId="상행"),reliable=false,firstArrivalAtEpochMillis=0))
  assertTrue(canSelectSubwayArrival(other,station))
  assertTrue(canSelectSubwayArrival(rows[1].copy(snapshot=rows[1].snapshot.copy(reliable=false,firstArrivalAtEpochMillis=0)),station))
  assertFalse(canSelectSubwayArrival(rows[0].copy(arrivalCode="2"),station))
  assertFalse(canSelectSubwayArrival(other,station.copy(stopId="wrong")))
 }
 @Test fun refreshOrderChangesAndSlotChangesDoNotChangeAnIdentifiedChoice() {
  val choice=SubwayArrivalChoice.from(rows[1])
  val refreshed=rows[1].copy(officialSlot=1)
  val updated=analysis(listOf(rows[2],rows[0],refreshed))
  assertEquals("second",selectDestinationSubwayArrival(updated,now,choice)!!.trainId)
  assertEquals("third",selectDestinationSubwayArrival(updated,now,choice)!!.snapshot.nextArrivalAtEpochMillis?.let { at -> updated.matchingArrivals.first { it.snapshot.firstArrivalAtEpochMillis==at }.trainId })
 }
 @Test fun disappearedChoiceDoesNotSilentlyFallBackToFirstTrain() {
  val choice=SubwayArrivalChoice.from(rows[1])
  assertNull(selectDestinationSubwayArrival(analysis(listOf(rows[0],rows[2])),now,choice))
 }
 @Test fun positionOnlySecondTrainUsesItsOwnEtaAndItsFollowingTrain() {
  val raw=rows.map { it.copy(snapshot=it.snapshot.copy(firstArrivalAtEpochMillis=0,reliable=false)) }
  val linked=raw.mapIndexed { i,row -> estimate(row,(i+1)*240) }
  val data=SubwayTimetableAnalysis(raw,linked,true,null,station.stopId,station.routeId)
  val evidence=selectTimetableEvidence(data,now,SubwayArrivalChoice.from(raw[1]))!!
  assertEquals("second",evidence.first.trainNo)
  assertEquals(now+480000,evidence.first.midpointArrivalAtEpochMillis())
  assertEquals("third",evidence.next!!.trainNo)
 }
 @Test fun choosingDuringRefreshCancelsOldResponseButKeepsAnalysisAndWalk() {
  val state=JourneyScreenState(FeatureMode.STOP)
  state.timetableAnalysis=analysis()
  state.guidanceWalkingRoute=GuidanceWalkingRoute(MapPoint(127.0,37.2),MapPoint(127.0,37.2),id,120,now,120000,100)
  val old=state.beginLiveRequest()
  assertNotNull(state.timetableAnalysis)
  state.chooseSubwayArrival(rows[2])
  assertFalse(state.acceptsLiveResponse(old))
  assertFalse(state.liveLoading)
  assertNotNull(state.timetableAnalysis)
  assertNotNull(state.guidanceWalkingRoute)
 }
 @Test fun backgroundRequestCodecPreservesSpecificTrainSelection() {
  val point=MapPoint(127.0,37.2)
  val selected=SubwayArrivalChoice.from(rows[2])
  val request=JourneyMonitoringRequest("망포",point,id,subwayStation=station,subwayChoice=selected)
  assertTrue(request.isValid())
  val decoded=JourneyMonitoringRequestCodec.decode(JourneyMonitoringRequestCodec.encode(request))!!
  assertEquals(selected,decoded.subwayChoice)
  assertFalse(decoded.isSameJourney(request.copy(subwayChoice=SubwayArrivalChoice.from(rows[0]))))
 }
 @Test fun walkingMapBoundsIncludePinsAndRealPathButNotRemoteFinalDestination() {
  val current=MapPoint(127.0,37.2);val boarding=MapPoint(127.001,37.201);val corner=MapPoint(127.002,37.199)
  val remote=MapPoint(126.97,37.59)
  val result=walkingMapViewportPoints(listOf(current,corner,boarding),current,boarding,listOf(listOf(boarding,remote)))
  assertTrue(result.containsAll(listOf(current,corner,boarding)));assertFalse(result.contains(remote))
  assertEquals(listOf(current,boarding),walkingMapViewportPoints(emptyList(),current,boarding,emptyList()))
  assertEquals(listOf(current),walkingMapViewportPoints(emptyList(),current,current,emptyList()))
 }
 @Test fun datedStationTimetableIsReusedWithoutMixingServiceDays()=runBlocking {
  val repo=OfficialTimetableRepository.fromAssets { File("src/main/assets/$it").inputStream() }
  val day=LocalDate.of(2026,10,26)
  val first=repo.timetableForStation("1075",station.stopId,day,OfficialServiceDayType.WEEKDAY,"calendar")!!
  val same=repo.timetableForStation("1075",station.stopId,day,OfficialServiceDayType.WEEKDAY,"calendar")!!
  assertSame(first,same)
  val tomorrow=repo.timetableForStation("1075",station.stopId,day.plusDays(1),OfficialServiceDayType.WEEKDAY,"calendar")!!
  assertNotSame(first,tomorrow);assertEquals(day.plusDays(1),tomorrow.calendar.single().serviceDate)
 }
 @Test fun timetableAnalysisDoesNotRunOnTheCallingUiThread()=runBlocking {
  val threadNames=mutableListOf<String>()
  val analyzer=SubwayTimetableAnalyzer({ _,_,_,_,_,_ -> threadNames+=Thread.currentThread().name;null },
   { _,_,_,_,_,_ -> threadNames+=Thread.currentThread().name;null })
  Executors.newSingleThreadExecutor { r -> Thread(r,"SelectionUiThread") }.asCoroutineDispatcher().use { dispatcher ->
   withContext(dispatcher) { analyzeSubwayWithScheduleFallback(analyzer,station,null,emptyList(),now,
    listOf(OfficialTimetableCalendarDay(LocalDate.of(2026,10,26),OfficialServiceDayType.WEEKDAY,"calendar"))) }
  }
  assertTrue(threadNames.isNotEmpty());assertTrue(threadNames.none { it=="SelectionUiThread" })
 }
 @Test fun changingTrainInBackgroundKeepsAgedWalkAndUsesTheNewTrain() {
  val point=MapPoint(127.0,37.2)
  val old=JourneyMonitoringRequest("망포",point,id,subwayStation=station,subwayChoice=SubwayArrivalChoice.from(rows[0]))
  val next=old.copy(subwayChoice=SubwayArrivalChoice.from(rows[1]))
  val state=JourneyMonitoringState(true,old,location=GuidanceLocation(point,10.0,now),
   walkingRoute=GuidanceWalkingRoute(point,point,id,120,now-10000,120000,100),subwayObservations=rows,
   timetableAnalysis=analysis(),walkingDistanceMeters=100,providerWalkingSeconds=120)
  val result=monitoringSelectionHandover(state,next,now,true)!!
  assertEquals(now+480000,result.arrivals!!.firstArrivalAtEpochMillis)
  assertEquals(now-10000,result.walkingRoute!!.fetchedAtEpochMillis)
  assertEquals(next,result.request)
  assertNull(monitoringSelectionHandover(state,next.copy(boardingPoint=point.copy(x=128.0)),now,true))
 }
}
